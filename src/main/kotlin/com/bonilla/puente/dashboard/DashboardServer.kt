package com.bonilla.puente.dashboard

import com.bonilla.puente.http.Json
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Hub local que sirve el panel y hace de proxy hacia cada instancia de Puente.
 *
 * ## Por que un hub y no CORS
 *
 * El objetivo era "una pagina para todas las instancias". La via obvia seria
 * servir un HTML suelto y que el mod respondiera con `Access-Control-Allow-Origin`.
 * Eso se descarta a proposito: la API del mod va **sin autenticacion por
 * defecto** y puede mandar comandos, cambiar la identidad y conectar a
 * servidores. Con CORS permisivo, cualquier pagina que se abriera en ese
 * navegador podria POSTear a `127.0.0.1:25580` y manejar el bot. Los
 * navegadores bloquean esas peticiones precisamente para evitarlo, asi que
 *شطة CORS las desactivaria.
 *
 * Con esta arquitectura el navegador solo habla con el hub (mismo origen, sin
 * CORS) y el hub habla con las instancias de servidor a servidor, donde el
 * navegador no tiene nada que ver. **El mod no gana ni una linea de codigo de
 * red nueva.**
 *
 * ## Defensas
 *
 * 1. Solo se enlaza a loopback. [start] se niega a hacerlo en otro sitio.
 * 2. Se valida la cabecera `Host`, que es lo que frena el *DNS rebinding*: sin
 *    esto, una web maliciosa podria resolver su dominio a 127.0.0.1 y driving
 *    este hub creyendo que es suyo. Ver [hostAllowed].
 * 3. Solo se hace proxy a loopback, para que el hub no sirva de trampolín
 *    hacia la red interna. Ver [InstanceRegistry.validate].
 * 4. No se reenvian cabeceras `Authorization` del navegador: se usa el token
 *    guardado de cada instancia. Asi la pagina no necesita conocerlo.
 * 5. No se emite ninguna cabecera `Access-Control-Allow-*`.
 */
class DashboardServer @JvmOverloads constructor(
	private val registry: InstanceRegistry,
	private val port: Int = DEFAULT_PORT,
	private val host: String = LOOPBACK,
	private val launcher: InstanceLauncher = InstanceLauncher(),
) {

	private val client: HttpClient = HttpClient.newBuilder()
		.connectTimeout(Duration.ofMillis(1_500))
		.version(HttpClient.Version.HTTP_1_1)
		.build()

	/** Sondeos de estado: varios a la vez, con techo, para no bloquear el listado. */
	private val prober = Executors.newFixedThreadPool(8) { r ->
		Thread(r, "dashboard-probe").apply { isDaemon = true }
	}

	private val httpThreads = Executors.newFixedThreadPool(8) { r ->
		Thread(r, "dashboard-http").apply { isDaemon = true }
	}

	private var server: HttpServer? = null

	/** Puerto ya enlazado; lo necesita [hostAllowed]. */
	@Volatile
	private var boundPort: Int = -1

	fun start(): Int {
		if (host != LOOPBACK) {
			throw IllegalArgumentException(
				"El panel solo se enlaza a loopback, no a '$host': expondría el control de las instancias a la red.",
			)
		}

		val bound = HttpServer.create(InetSocketAddress(host, port), 0)
		bound.executor = httpThreads
		bound.createContext("/") { exchange -> handle(exchange) }
		bound.start()
		server = bound
		boundPort = bound.address.port
		return boundPort
	}

	fun stop() {
		// Las instancias que arrancara este hub se van con el: si el panel se
		// cierra dejando bots vivos, no hay forma de pararlos desde la pagina.
		launcher.stopAll()
		server?.stop(0)
		prober.shutdownNow()
		httpThreads.shutdownNow()
	}

	private fun handle(exchange: HttpExchange) {
		try {
			if (!hostAllowed(exchange.requestHeaders.getFirst("Host"))) {
				// No es un 403 "bonito": es exactamente el caso de DNS rebinding.
				send(exchange, 403, json(errorBody("bad_host", "cabecera Host no permitida")))
				return
			}

			val path = exchange.requestURI.path.removePrefix("/").trim('/')
			val method = exchange.requestMethod

			when {
				path.isEmpty() -> sendHtml(exchange, INDEX)

				path == "api/instances" && method == "GET" -> send(exchange, 200, listInstances())
				path == "api/instances" && method == "POST" -> upsert(exchange)
				path == "api/discover" && method == "POST" -> discover(exchange)

				path == "api/launch" && method == "POST" -> launch(exchange)
				path == "api/stop" && method == "POST" -> stop(exchange)
				path == "api/launcher" && method == "GET" -> send(exchange, 200, json(okBody(launcherState())))

				path == "api/health" && method == "GET" -> send(exchange, 200, json(okBody(Json.obj().apply {
					addProperty("status", "ok")
					addProperty("instances", registry.all().size)
				})))

				path.startsWith("api/broadcast/") -> {
					// Ojo: se quita `api/broadcast` y NO la barra. La cola tiene que
					// llegar a `forward` con su `/` inicial, si no el destino se
					// construye como `/puppeteerchat` y la instancia responde 404.
					val rest = path.removePrefix("api/broadcast")
					broadcast(exchange, method, rest, exchange.requestURI.rawQuery)
				}

				path.startsWith("api/instances/") -> {
					val rest = path.removePrefix("api/instances/")
					// DELETE sin cola.quita la instancia del registro. Con cola
					// seria un proxy, asi que la distincion es el propio `slash`.
					if (method == "DELETE" && !rest.contains('/')) {
						removeInstance(exchange, rest)
					} else {
						routeProxy(exchange, method, path)
					}
				}

				else -> send(exchange, 404, json(errorBody("not_found", "ruta desconocida: /$path")))
			}
		} catch (e: BadRequest) {
			send(exchange, 400, json(errorBody(e.code, e.message ?: e.code)))
		} catch (e: Exception) {
			send(exchange, 500, json(errorBody("internal_error", e.message ?: e.javaClass.simpleName)))
		} finally {
			exchange.close()
		}
	}

	// --- registro de instancias --------------------------------------------

	private fun listInstances(): ByteArray {
		// Se sondea en paralelo: una instancia apagada no debe retrasar el
		// listado de las demas.
		val probed = registry.all().map { instance ->
			val future = prober.submit<ProbeResult> { probe(instance) }
			instance to future
		}

		val array = JsonArray()
		probed.forEach { (instance, future) ->
			val probe = try {
				future.get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
			} catch (e: Exception) {
				ProbeResult(false, null, "sin respuesta en ${PROBE_TIMEOUT_MS}ms")
			}
			array.add(instance.toJson().apply {
				addProperty("online", probe.online)
				if (probe.status != null) add("status", probe.status) else add("status", com.google.gson.JsonNull.INSTANCE)
				if (probe.error != null) addProperty("error", probe.error) else add("error", com.google.gson.JsonNull.INSTANCE)
			})
		}

		return json(okBody(JsonObject().apply { add("instances", array) }))
	}

	private fun upsert(exchange: HttpExchange) {
		val body = Json.parse(String(readBody(exchange), StandardCharsets.UTF_8))
		val name = body.get("name")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
		val host = body.get("host")?.takeIf { !it.isJsonNull }?.asString ?: LOOPBACK
		val port = body.get("port")?.takeIf { !it.isJsonNull }?.asInt
			?: throw BadRequest("falta 'port'")
		val token = body.get("token")?.takeIf { !it.isJsonNull }?.asString.orEmpty()

		if (name.isBlank()) throw BadRequest("falta 'name'")
		if (port !in 1..65535) throw BadRequest("'port' fuera de rango (1-65535): $port")

		val instance = registry.upsert(name, host, port, registry.withExistingToken(port, token))
		send(exchange, 200, json(okBody(instance.toJson())))
	}

	private fun removeInstance(exchange: HttpExchange, id: String) {
		val removed = registry.remove(id)
		if (!removed) {
			send(exchange, 400, json(errorBody("unknown_instance", "no existe la instancia '$id'")))
			return
		}
		send(exchange, 200, json(okBody(Json.obj().apply { addProperty("removed", id) })))
	}

	/**
	 * Reenvia la misma peticion a TODAS las instancias a la vez.
	 *
	 * Es lo que hace falta para "manda esto a todos": un `POST /broadcast/chat`
	 * va a cada instancia en paralelo y devuelve un resultado por cada una,
	 * con su estado HTTP o su error. No es todo-o-nada a proposito: si una
	 * instancia esta apagada, las otras han recibido igual el mensaje y eso
	 * hay que poder verlo.
	 *
	 * Sin esto, mandar un comando a 6 bots era 6 peticiones del navegador y
	 * ademas no se podia saber cual habia recibido el mensaje.
	 */
	private fun broadcast(exchange: HttpExchange, method: String, tail: String, query: String?) {
		if (tail.isBlank()) {
			throw BadRequest("falta la ruta: /api/broadcast/{ruta} (por ejemplo /broadcast/chat)")
		}
		// Se comprueba aqui y no solo en el registro: el broadcast itera sobre
		// lo que hay ahora, y el registro puede haber cambiado desde el arranque.
		val targets = registry.all().filter { it.isLoopback }
		if (targets.isEmpty()) {
			send(exchange, 400, json(errorBody("no_instances", "no hay instancias registradas")))
			return
		}

		val body = readBody(exchange)
		val contentType = exchange.requestHeaders.getFirst("Content-Type")

		// Todas en paralelo y con el mismo techo que el proxy: una instancia
		// colgada no puede retrasar al resto del broadcast.
		val futures = targets.map { instance ->
			prober.submit<JsonObject> { broadcastOne(instance, method, tail, query, body, contentType) }
		}

		val results = JsonArray()
		var sent = 0
		var failed = 0
		futures.forEach { future ->
			val entry = try {
				future.get(PROXY_TIMEOUT_MS + 500, TimeUnit.MILLISECONDS)
			} catch (e: Exception) {
				Json.obj().apply {
					addProperty("id", "?")
					addProperty("ok", false)
					addProperty("error", "sin respuesta en ${PROXY_TIMEOUT_MS}ms")
				}
			}
			results.add(entry)
			if (entry.get("ok")?.asBoolean == true) sent++ else failed++
		}

		send(exchange, 200, json(okBody(Json.obj().apply {
			add("results", results)
			addProperty("total", targets.size)
			addProperty("sent", sent)
			addProperty("failed", failed)
		})))
	}

	/** Una instancia dentro del broadcast. Nunca lanza: el fallo es un dato. */
	private fun broadcastOne(
		instance: Instance,
		method: String,
		tail: String,
		query: String?,
		body: ByteArray,
		contentType: String?,
	): JsonObject {
		val entry = Json.obj().apply {
			addProperty("id", instance.id)
			addProperty("name", instance.name)
		}
		return try {
			val response = forward(instance, method, tail, query, body, contentType)
			entry.addProperty("ok", response.status in 200..299)
			entry.addProperty("status", response.status)
			// El cuerpo de cada instancia se devuelve tal cual, para que la pagina
			// pueda decir "el bot 3 dijo X" y no solo "ok".
			val parsed = runCatching {
				com.google.gson.JsonParser.parseString(String(response.body, StandardCharsets.UTF_8))
			}.getOrNull()
			if (parsed != null) entry.add("body", parsed) else entry.add("body", com.google.gson.JsonNull.INSTANCE)
			entry
		} catch (e: java.net.ConnectException) {
			entry.addProperty("ok", false)
			entry.addProperty("error", "apagada (nadie escucha en ${instance.port})")
			entry
		} catch (e: java.net.http.HttpTimeoutException) {
			entry.addProperty("ok", false)
			entry.addProperty("error", "no respondio a tiempo")
			entry
		} catch (e: Exception) {
			entry.addProperty("ok", false)
			entry.addProperty("error", e.message ?: e.javaClass.simpleName)
			entry
		}
	}

	/**
	 * Busca instancias de Puente en un rango de puertos y da de alta las que
	 * todavia no estan en el panel.
	 *
	 * Existe para el caso "la he abierto yo desde la terminal, que me salga en
	 * la pagina": arrancar Minecraft a mano no pasa por el hub, asi que sin esto
	 * esa instancia seria invisible hasta que alguien la diese de alta a mano.
	 *
	 * Solo se dan de alta si contestan como Puente de verdad, y se comprueba en
	 * el registro de la configuracion de juego de cada instancia para traer su
	 * token. Asi el token no lo pone el navegador ni viaja por la pagina: lo lee
	 * el hub del sitio donde el propio mod lo escribio.
	 */
	private fun discover(exchange: HttpExchange) {
		val from = DEFAULT_SCAN_FROM
		val to = DEFAULT_SCAN_TO

		val known = registry.all().map { it.port }.toSet()
		val candidates = (from..to).filterNot { it in known }
		if (candidates.isEmpty()) {
			send(exchange, 200, json(okBody(Json.obj().apply {
				addProperty("scanned", 0)
				add("added", JsonArray())
			})))
			return
		}

		val futures = candidates.map { candidate ->
			prober.submit<Discovered?> { detect(candidate) }
		}

		val added = JsonArray()
		var scanned = 0
		futures.forEach { future ->
			val found = try {
				future.get(DISCOVERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
			} catch (e: Exception) {
				null
			}
			scanned++
			if (found != null) {
				registry.upsert(found.name, LOOPBACK, found.port, found.token)
				added.add(Json.obj().apply {
					addProperty("id", "p${found.port}")
					addProperty("name", found.name)
					addProperty("port", found.port)
					addProperty("hasToken", found.token.isNotEmpty())
				})
			}
		}

		send(exchange, 200, json(okBody(Json.obj().apply {
			addProperty("scanned", scanned)
			add("added", added)
		})))
	}

	/**
	 * Pregunta a un puerto si hay un Puente y de donde es.
	 *
	 * Se conforma con un 401 a proposito: si el puerto responde 401 a /status es
	 * que **si** hay un Puente con token, y basta para darlo de alta. Descubrir
	 * "hay algo aqui" no necesita permiso, y exigirlo dejaria fuera justamente
	 * las instancias bien configuradas, que son las que mas importan.
	 */
	private fun detect(port: Int): Discovered? {
		val target = URI("http://$LOOPBACK:$port/puppeteer/status")
		val request = HttpRequest.newBuilder(target)
			.timeout(Duration.ofMillis(DISCOVERY_TIMEOUT_MS))
			.GET()
			.build()

		val response = try {
			client.send(request, HttpResponse.BodyHandlers.ofString())
		} catch (e: Exception) {
			return null
		}

		val isPuente = when (response.statusCode()) {
			// Con token: responde 401, pero el 401 es de Puente.
			401 -> response.body().contains("unauthorized")
			200 -> runCatching {
				val data = com.google.gson.JsonParser.parseString(response.body())
					.asJsonObject.getAsJsonObject("data")
				// `modVersion` solo lo escribe el mod; sirve de firma.
				data.has("modVersion")
			}.getOrDefault(false)
			else -> false
		}
		if (!isPuente) return null

		val token = tokenForPort(port)
		return Discovered(port, "Instancia $port", token)
	}

	/**
	 * Busca el token de un puerto en las carpetas de juego conocidas.
	 *
	 * Se leen los ficheros que el propio mod escribe al arrancar, no se le pide
	 * el token a nadie: asi el secreto nunca sale hacia el navegador y el hub
	 * no necesita inventar nada.
	 *
	 * La ruta de una instancia lanzada por el hub la da [InstanceLauncher], que
	 * es quien decidio donde vive su carpeta de juego. Para las sueltas se prueba
	 * `run-instances/p<port>/config` y tambien el `run/config` de una instancia
	 * montada a mano; el token se busca en los dos y gana el primero que exista.
	 */
	private fun tokenForPort(port: Int): String {
		val candidates = listOf(
			// La instancia principal, arrancada a mano.
			Path.of(workingDir(), "run", "config", "mc-puppeteer.json"),
			// Las que arranca el hub desde el panel.
			launcher.configFile(port),
		)
		return candidates.firstNotNullOfOrNull { file ->
			runCatching {
				val json = com.google.gson.JsonParser.parseString(Files.readString(file)).asJsonObject
				// Solo si ese fichero describe ESTE puerto: si no, su token es
				// de otra instancia y mandarlo seria mandar una credencial ajena.
				if (json.get("port")?.asInt != port) return@runCatching null
				json.get("authToken")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
			}.getOrNull()
		}.orEmpty()
	}

	private fun workingDir(): String = System.getProperty("user.dir") ?: "."

	private class Discovered(val port: Int, val name: String, val token: String)

	/**
	 * Arranca una instancia nueva y la da de alta en el panel.
	 *
	 * El token lo genera y siembra [InstanceLauncher] y se registra aqui, asi que
	 * la pagina recibe el alta con la instancia ya lista para sondear: no hay
	 * ventana en la que aparezca "apagada" y haya que esperar a que el mod levante.
	 *
	 * Solo se acepta `port`, y solo del rango permitido. El proceso, su clase y su
	 * classpath no se negocian: estan en [InstanceLauncher].
	 */
	private fun launch(exchange: HttpExchange) {
		val body = Json.parse(String(readBody(exchange), StandardCharsets.UTF_8))
		val port = body.get("port")?.takeIf { !it.isJsonNull }?.asInt
			?: throw BadRequest("falta 'port'")
		val name = body.get("name")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
			?: "Instancia $port"

		val pid = try {
			launcher.launch(port)
		} catch (e: InstanceLauncher.InstanceLaunchException) {
			send(exchange, 400, json(errorBody("launch_failed", e.message ?: e.javaClass.simpleName)))
			return
		}

		val instance = registry.upsert(name, LOOPBACK, port, launcher.tokenForPort(port))
		send(exchange, 200, json(okBody(Json.obj().apply {
			add("instance", instance.toJson())
			addProperty("pid", pid)
			addProperty("started", true)
		})))
	}

	/**
	 * Para una instancia.
	 *
	 * Acepta `port` y no `pid`: el hub solo para lo que el mismo ha arrancado, y
	 * por puerto se llega a ese proceso sin que el navegador pueda influir en ello.
	 */
	private fun stop(exchange: HttpExchange) {
		val body = Json.parse(String(readBody(exchange), StandardCharsets.UTF_8))
		val port = body.get("port")?.takeIf { !it.isJsonNull }?.asInt
			?: throw BadRequest("falta 'port'")

		val stopped = launcher.stop(port)
		send(exchange, 200, json(okBody(Json.obj().apply {
			addProperty("stopped", stopped)
			addProperty("port", port)
			if (!stopped) addProperty("note", "el hub no habia arrancado esa instancia")
		})))
	}

	/** Lo que el frontend necesita para pintar los botones: si se puede y que vive. */
	private fun launcherState(): JsonObject = Json.obj().apply {
		addProperty("ready", launcher.ready())
		addProperty("from", DashboardServer.DEFAULT_SCAN_FROM)
		addProperty("to", DashboardServer.DEFAULT_SCAN_TO)
		val running = JsonArray()
		for (p in DashboardServer.DEFAULT_SCAN_FROM..DashboardServer.DEFAULT_SCAN_TO) {
			launcher.runningPid(p)?.let { running.add(Json.obj().apply { addProperty("port", p); addProperty("pid", it) }) }
		}
		add("running", running)
	}

	private fun routeProxy(exchange: HttpExchange, method: String, path: String) {
		val rest = path.removePrefix("api/instances/")
		val slash = rest.indexOf('/')
		val id = if (slash < 0) rest else rest.substring(0, slash)
		val tail = if (slash < 0) "" else rest.substring(slash)

		val instance = registry.find(id)
			?: throw BadRequest("no existe la instancia '$id'", "unknown_instance")

		// Barrera anti-SSRF: aunque el registro se hubiera manipulado, aqui no
		// sale nada hacia un host que no sea loopback.
		if (!instance.isLoopback) {
			send(exchange, 403, json(errorBody("forbidden_host", "el hub solo hace proxy a loopback")))
			return
		}

		val body = readBody(exchange)
		val contentType = exchange.requestHeaders.getFirst("Content-Type")

		val response = try {
			forward(instance, method, tail, exchange.requestURI.rawQuery, body, contentType)
		} catch (e: java.net.ConnectException) {
			// Caso normal: la instancia esta apagada. No es un error del hub.
			return send(exchange, 502, json(errorBody("instance_offline", "no hay nadie escuchando en ${instance.host}:${instance.port}")))
		} catch (e: java.net.http.HttpTimeoutException) {
			return send(exchange, 504, json(errorBody("instance_timeout", "la instancia no respondio a tiempo")))
		}

		sendRaw(exchange, response.status, response.body, response.contentType)
	}

	private fun forward(
		instance: Instance,
		method: String,
		tail: String,
		query: String?,
		body: ByteArray,
		contentType: String?,
	): Proxied {
		// `tail` siempre lleva su `/` inicial. Se normaliza aqui porque esta
		// funcion concatena a pelo: si un dia llega `chat` en vez de `/chat` el
		// destino sale `/puppeteerchat` y el fallo aparece como un 404 en la
		// instancia, que no dice nada de que el error fue montar mal la ruta.
		val suffix = when {
			tail.isEmpty() -> "/status"
			tail.startsWith("/") -> tail
			else -> "/$tail"
		}
		val target = URI(
			"http://${instance.host}:${instance.port}/puppeteer$suffix" +
				if (query != null) "?$query" else "",
		)

		val builder = authorized(
			HttpRequest.newBuilder(target)
				.timeout(Duration.ofMillis(PROXY_TIMEOUT_MS))
				.header("Accept", "application/json"),
			instance,
		)

		val publisher = when (method.uppercase()) {
			"GET", "DELETE" -> HttpRequest.BodyPublishers.noBody()
			else -> HttpRequest.BodyPublishers.ofByteArray(body)
		}
		if (contentType != null) builder.header("Content-Type", contentType)

		val response = client.send(builder.method(method.uppercase(), publisher).build(), HttpResponse.BodyHandlers.ofByteArray())
		return Proxied(
			status = response.statusCode(),
			body = response.body(),
			contentType = response.headers().firstValue("content-type").orElse("application/json; charset=utf-8"),
		)
	}

	private fun probe(instance: Instance): ProbeResult = try {
		val request = authorized(
			HttpRequest.newBuilder(URI("http://${instance.host}:${instance.port}/puppeteer/status")),
			instance,
		).timeout(Duration.ofMillis(PROBE_TIMEOUT_MS)).GET().build()

		val response = client.send(request, HttpResponse.BodyHandlers.ofString())
		if (response.statusCode() == 200) {
			val root = com.google.gson.JsonParser.parseString(response.body())
			val data = root.asJsonObject.getAsJsonObject("data")
			ProbeResult(true, data, null)
		} else {
			ProbeResult(false, null, "HTTP ${response.statusCode()}")
		}
	} catch (e: Exception) {
		ProbeResult(false, null, e.message ?: e.javaClass.simpleName)
	}

	/**
	 * Anade el token de la instancia a una peticion saliente.
	 *
	 * Lo usan TANTO el sondeo como el proxy, y por eso es una funcion y no una
	 * linea duplicada: cuando el sondeo se escribio sin cabecera, toda instancia
	 * con `requireToken=true` (o sea, la configuracion recomendada) aparecia
	 * como apagada con `HTTP 401` en el panel, aunque la instancia estuviese
	 * perfectamente viva. El proxy si la mandaba, asi que se veia raro: el chat y
	 * las acciones funcionaban pero el punto de color de la tarjeta decia que
	 * no habia nadie.
	 */
	private fun authorized(builder: HttpRequest.Builder, instance: Instance): HttpRequest.Builder {
		if (instance.token.isNotEmpty()) {
			// Deliberadamente **no** se reenvia la Authorization del navegador:
			// la pagina no necesita conocer el token de cada instancia.
			builder.header("Authorization", "Bearer ${instance.token}")
		}
		return builder
	}

	/**
	 * Solo se aceptan peticiones cuyo `Host` sea loopback.
	 *
	 * Es la defensa contra DNS rebinding: un sitio atacante puede hacer que su
	 * dominio resuelva a 127.0.0.1 y su `fetch` llevaria `Host: evil.com`. Como
	 * este hub no pide credenciales, sin esta comprobacion el sitio podria
	 * controlarlo todo.
	 *
	 * El puertoAllowed sale del **puerto realmente enlazado**, no de una
	 * constante: si se comprueba contra el puerto por defecto, arrancar con
	 * `--port 9999` haria que el panel se rechazase a si mismo.
	 */
	internal fun hostAllowed(hostHeader: String?): Boolean {
		val port = boundPort
		if (hostHeader == null || port < 0) return false

		val withPort = setOf("127.0.0.1:$port", "localhost:$port", "[::1]:$port")
		// Sin puerto tambien se acepta: hay clientes HTTP que no lo envian, y
		// seguir exigiendolo no aporta seguridad (el attacker pone un dominio,
		// no un loopback).
	 val withoutPort = LOOPBACK_HOSTS
		return hostHeader in withPort || hostHeader in withoutPort
	}

	// --- utilidades ---------------------------------------------------------

	private fun readBody(exchange: HttpExchange): ByteArray {
		val declared = exchange.requestHeaders.getFirst("Content-Length")?.toIntOrNull() ?: 0
		if (declared > MAX_BODY_BYTES) throw BadRequest("cuerpo demasiado grande (max $MAX_BODY_BYTES bytes)")
		return exchange.requestBody.readNBytes(MAX_BODY_BYTES + 1).also {
			if (it.size > MAX_BODY_BYTES) throw BadRequest("cuerpo demasiado grande (max $MAX_BODY_BYTES bytes)")
		}
	}

	private fun okBody(data: com.google.gson.JsonElement): JsonObject = Json.obj().apply {
		addProperty("ok", true)
		add("data", data)
	}

	private fun errorBody(code: String, message: String): JsonObject = Json.obj().apply {
		addProperty("ok", false)
		add("error", Json.obj().apply {
			addProperty("code", code)
			addProperty("message", message)
		})
	}

	private fun json(body: JsonObject): ByteArray = Json.write(body).toByteArray(StandardCharsets.UTF_8)

	private fun send(exchange: HttpExchange, status: Int, body: ByteArray) =
		sendRaw(exchange, status, body, "application/json; charset=utf-8")

	private fun sendRaw(exchange: HttpExchange, status: Int, body: ByteArray, contentType: String) {
		try {
			exchange.responseHeaders.add("Content-Type", contentType)
			// El panel es solo de loopback: que ningun documento embebido pueda
			// inyectar scripts a traves de las respuestas del hub.
			exchange.responseHeaders.add("X-Content-Type-Options", "nosniff")
			exchange.responseHeaders.add("Cache-Control", "no-store")
			exchange.sendResponseHeaders(status, body.size.toLong())
			exchange.responseBody.use { it.write(body) }
		} catch (e: Exception) {
			// Cliente que se fue: no es motivo para ensuciar el log.
		}
	}

	private fun sendHtml(exchange: HttpExchange, html: String) =
		sendRaw(exchange, 200, html.toByteArray(StandardCharsets.UTF_8), "text/html; charset=utf-8")

	private class Proxied(val status: Int, val body: ByteArray, val contentType: String)

	private class ProbeResult(val online: Boolean, val status: JsonObject?, val error: String?)

	companion object {
		const val DEFAULT_PORT = 25590
		const val MAX_BODY_BYTES = 256 * 1024
		const val PROBE_TIMEOUT_MS = 1_500L
		const val PROXY_TIMEOUT_MS = 8_000L
		const val DISCOVERY_TIMEOUT_MS = 400L

		/**
		 * Rango que se escanea al descubrir. 25580 es el puerto por defecto de
		 * Puente, asi que el rango arranca ahi y no lo solapa con el del panel
		 * (25590), que vive por encima.
		 */
		const val DEFAULT_SCAN_FROM = 25580
		const val DEFAULT_SCAN_TO = 25599

		/** El panel va empaquetado como recurso, no en un string gigantic. */
		private val INDEX: String by lazy {
			DashboardServer::class.java.getResourceAsStream("/puente-dashboard/index.html")
				?.use { String(it.readBytes(), StandardCharsets.UTF_8) }
				?: error("falta el recurso /puente-dashboard/index.html")
		}
	}
}
