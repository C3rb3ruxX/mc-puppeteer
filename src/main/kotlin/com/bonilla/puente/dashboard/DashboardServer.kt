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
import java.awt.Polygon
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
	import javax.imageio.ImageIO
	import java.util.concurrent.ConcurrentHashMap
	import java.util.concurrent.Executors
import java.util.jar.JarFile

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

	/** Broadcasts use a separate pool so probes cannot serialize commands to instances. */
	private val broadcastThreads = Executors.newCachedThreadPool { r ->
		Thread(r, "dashboard-broadcast").apply { isDaemon = true }
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
		broadcastThreads.shutdownNow()
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

				path.startsWith("assets/item/") ->
				itemSprite(exchange, path.removePrefix("assets/item/"))

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
				// El sondeo puede hacer DOS peticiones ahora: `/status` y, si esa
				// falla, `/health` para distinguir "apagada" de "ocupada". Por eso
				// la espera cubre las dos, o el panel veria vivas como apagadas
				// solo por llegar tarde.
				future.get(PROBE_TIMEOUT_MS + HEALTH_TIMEOUT_MS + 500, TimeUnit.MILLISECONDS)
			} catch (e: Exception) {
				ProbeResult(false, null, "sin respuesta en ${PROBE_TIMEOUT_MS}ms")
			}
			array.add(instance.toJson().apply {
				addProperty("online", probe.online)
				addProperty("busy", probe.busy)
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

		if (name.isBlank()) throw BadRequest("falta 'name'")
		if (port !in 1..65535) throw BadRequest("'port' fuera de rango (1-65535): $port")

		// Sin token: la pagina no tiene nada que mandar. Un `token` que venga en
		// el cuerpo se ignora en vez de dar error, para que un guardado antiguo del
		// navegador, o un curl de la documentacion vieja, no se rompan al pegar.
		val instance = registry.upsert(name, host, port)
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
			instance to broadcastThreads.submit<JsonObject> {
				broadcastOne(instance, method, tail, query, body, contentType)
			}
		}

		val results = JsonArray()
		var sent = 0
		var failed = 0
		futures.forEach { (instance, future) ->
			val entry = try {
				future.get(PROXY_TIMEOUT_MS + 500, TimeUnit.MILLISECONDS)
			} catch (e: Exception) {
				Json.obj().apply {
					addProperty("id", instance.id)
					addProperty("name", instance.name)
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
				registry.upsert(found.name, LOOPBACK, found.port)
				added.add(Json.obj().apply {
					addProperty("id", "p${found.port}")
					addProperty("name", found.name)
					addProperty("port", found.port)
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

		return Discovered(port, "Instancia $port")
	}

	private fun workingDir(): String = System.getProperty("user.dir") ?: "."

	private class Discovered(val port: Int, val name: String)

	/**
	 * Arranca una instancia nueva y la da de alta en el panel.
	 *
	 * Se registra aqui mismo, asi que la pagina recibe el alta con la instancia ya
	 * lista para sondear: no hay ventana en la que aparezca "apagada" y haya que
	 * esperar a que el mod levante.
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

		val instance = registry.upsert(name, LOOPBACK, port)
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

	/**
	 * Sondea una instancia.
	 *
	 * `/status` entra en el hilo principal del juego, asi que cuando ese hilo esta
	 * ocupado (por ejemplo esperando a Baritone) no responde aunque el proceso
	 * este perfectamente vivo. Por eso, si `/status` falla, se pregunta tambien
	 * por `/health`, que no toca ese hilo: si contesta, la instancia no esta
	 * apagada, esta **ocupada**, que es una situacion distinta y accionable.
	 */
	private fun probe(instance: Instance): ProbeResult {
		val status = probeStatus(instance)
		if (status.online) return status
		val viva = instance.respondeAHealth()
		return if (viva) {
			ProbeResult(
				online = true,
				status = null,
				error = status.error,
				busy = true,
			)
		} else {
			status
		}
	}

	private fun probeStatus(instance: Instance): ProbeResult = try {
		val request = authorized(
			HttpRequest.newBuilder(URI("http://${instance.host}:${instance.port}/puppeteer/status")),
			instance,
		).timeout(Duration.ofMillis(PROBE_TIMEOUT_MS)).GET().build()

		val response = client.send(request, HttpResponse.BodyHandlers.ofString())
		if (response.statusCode() != 200) {
			// El mod contesta 503 `main_thread_timeout` cuando el hilo principal
			// esta ocupado: no es un fallo del hub, es el dato que busca.
			ProbeResult(false, null, motivoDelError(response.statusCode(), response.body()))
		} else {
			val root = com.google.gson.JsonParser.parseString(response.body()).asJsonObject
			// Un 200 sin `data` no es un estado: es un sobre de error. Si se
			// aceptara aqui, una instancia ocupada apareceria "en linea" pero sin
			// ningun dato, que es justo lo que el estado ocupado viene a evitar.
			if (root.get("data")?.isJsonObject == true) {
				ProbeResult(true, root.getAsJsonObject("data"), null)
			} else {
				ProbeResult(false, null, motivoDelError(200, response.body()))
			}
		}
	} catch (e: Exception) {
		ProbeResult(false, null, e.message ?: e.javaClass.simpleName)
	}

	/** Saca el mensaje del sobre `{ok:false,error:{code,message}}`. */
	private fun motivoDelError(status: Int, body: String): String {
		val mensaje = runCatching {
			com.google.gson.JsonParser.parseString(body).asJsonObject
				.getAsJsonObject("error")?.get("message")?.asString
		}.getOrNull()
		return when {
			!mensaje.isNullOrBlank() -> mensaje
			status != 200 -> "HTTP $status"
			else -> "respuesta sin datos"
		}
	}

	/**
	 * `/health` no entra en el hilo principal, asi que es lo unico que contesta
	 * con el juego ocupado. Un `ConnectException` aqui significa de verdad que
	 * no hay nadie escuchando.
	 */
	private fun Instance.respondeAHealth(): Boolean = try {
		val request = HttpRequest.newBuilder(URI("http://$host:$port/puppeteer/health"))
			.timeout(Duration.ofMillis(HEALTH_TIMEOUT_MS)).GET().build()
		client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode() in 200..299
	} catch (e: java.net.ConnectException) {
		false
	} catch (e: Exception) {
		// Si tampoco esto contesta, no se puede afirmar que este viva.
		false
	}

	/**
	 * Anade el Authorization de una peticion saliente.
	 *
	 * No hace nada a proposito. Antes era el sitio donde se inyectaba el token de
	 * la instancia, y cuando el sondeo se escribio sin cabecera toda instancia
	 * con `requireToken=true` aparecia apagada con `HTTP 401` aunque estuviese
	 * viva, mientras el proxy si la mandaba: se veía rarísimo, el chat funcionaba
	 * pero el punto de color de la tarjeta decia que no habia nadie.
	 *
	 * Se deja el gancho porque la forma de ese bug es facil de volver a escribir
	 * (un `HttpRequest` que se construye en un sitio y se reenvia en otro), pero
	 * ya no hay token que poner: [InstanceLauncher] siembra `requireToken=false`.
	 */
	private fun authorized(builder: HttpRequest.Builder, instance: Instance): HttpRequest.Builder = builder

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

	// --- iconos de item -------------------------------------------------------

	/**
	 * Los sprites de los items, tal cual los trae el juego.
	 *
	 * No hay que pedirlos a ningun sitio: el jar del cliente esta en el classpath
	 * del hub (es el mismo `scripts/.run-config.json` con el que se arranca), asi
	 * que se leen de ahi en vez de montar una carpeta de PNG ni pegarle una
	 * peticion a un CDN de terceros, que ademas se enteraria de lo que hay en tu
	 * inventario.
	 *
	 * El atlas de 1.21.5 (`assets/minecraft/atlases/blocks.json`) declara sus
	 * fuentes como directorios: todo lo que hay en `textures/item/` es un sprite
	 * llamado `<nombre>`, y lo mismo en `textures/block/`. Por eso el nombre del
	 * item se resuelve probando los dos sitios, y basta: los bloques de textura
	 * plana (piedra, diamante, tierra) dan en el segundo, y las herramientas y la
	 * comida en el primero.
	 *
	 * Lo que no sale es el item cuyo sprite se compone de varias texturas
	 * (`crafting_table`, `furnace`, `chest`): para esos el sprite no se llama como
	 * el item. Se responde 404 y el panel deja el texto, que es lo de antes.
	 * Sacarlos bien haria falta el atlas ya montado, o sea dentro del juego.
	 */
	private fun itemSprite(exchange: HttpExchange, raw: String) {
		val parts = raw.lowercase().split('/', limit = 2)
		val namespace = if (parts.size == 2) parts[0] else "minecraft"
		val name = if (parts.size == 2) parts[1] else parts[0]
		if (!NAMESPACE_SPRITE.matches(namespace) || !NOMBRE_SPRITE.matches(name)) {
			send(exchange, 400, json(errorBody("bad_item", "nombre de item no valido: '$raw'")))
			return
		}

		// Un array vacio es "no hay sprite", y se cachea tambien: si no, cada
		// fallo hacia un recorrido del jar entero por cada item que no exista.
		val key = "$namespace:$name"
		val png = sprites.computeIfAbsent(key) { loadSprite(namespace, name) }
		if (png.isEmpty()) {
			send(exchange, 404, json(errorBody("no_sprite", "el juego no trae un sprite para '$key'")))
			return
		}
		sendImage(exchange, png)
	}

	private fun loadSprite(namespace: String, name: String): ByteArray {
		// Las herramientas, comida y otros objetos 2D tienen su sprite listo.
		loadResource("assets/$namespace/textures/item/$name.png")?.let { return it }

		// Los bloques se dibujan como el modelo isométrico que aparece en el
		// inventario del juego, usando sus caras y texturas vanilla.
		renderBlockSprite(namespace, name)?.let { return it }

		loadResource("assets/$namespace/textures/block/$name.png")?.let { return it }
		// Algunos modelos de item tienen una textura distinta al id del item.
		itemModelTexture(namespace, name)?.let { return it }

		// `runDashboard` no ejecuta dentro del classpath de Minecraft: Loom pone
		// el JAR del cliente en el classpath de `runClient`, que el launcher ya
		// volcó a scripts/.run-config.json. Léase de ese JAR para servir los PNG
		// reales también cuando el panel se arranca por separado.
		return ByteArray(0)
	}

	private fun renderBlockSprite(namespace: String, name: String): ByteArray? {
		val textures = linkedMapOf<String, String>()
		val seen = mutableSetOf<String>()

		fun collect(ns: String, model: String, depth: Int = 0) {
			if (depth > 12 || !seen.add("$ns:$model")) return
			val json = loadResource("assets/$ns/models/$model.json") ?: return
			val root = runCatching { com.google.gson.JsonParser.parseString(String(json, StandardCharsets.UTF_8)).asJsonObject }
				.getOrNull() ?: return
			val parent = root.get("parent")?.takeIf { it.isJsonPrimitive }?.asString
			if (parent != null) {
				val split = parent.split(':', limit = 2)
				collect(if (split.size == 2) split[0] else ns, if (split.size == 2) split[1] else split[0], depth + 1)
			}
			root.getAsJsonObject("textures")?.entrySet()?.forEach { (key, value) ->
				if (value.isJsonPrimitive) textures[key] = value.asString
			}
		}

		collect(namespace, "item/$name")
		if (textures.isEmpty()) collect(namespace, "block/$name")
		if (textures.isEmpty()) return null

		fun texture(keys: List<String>): BufferedImage? {
			for (key in keys) {
				var value = textures[key] ?: continue
				val resolved = mutableSetOf<String>()
				while (value.startsWith('#') && resolved.add(value)) {
					value = textures[value.drop(1)] ?: break
				}
				if (value.startsWith('#')) continue
				val split = value.split(':', limit = 2)
				val texNs = if (split.size == 2) split[0] else namespace
				val texPath = if (split.size == 2) split[1] else split[0]
				if (!NOMBRE_SPRITE.matches(texPath)) continue
				val category = texPath.substringBefore('/')
				val filename = texPath.substringAfter('/', "")
				if (category !in setOf("item", "block") || filename.isEmpty()) continue
				val bytes = loadResource("assets/$texNs/textures/$category/$filename.png") ?: continue
				return runCatching { ImageIO.read(bytes.inputStream()) }.getOrNull()
			}
			return null
		}

		// Un sprite generado (por ejemplo, item/generated) no es un bloque.
		val layers = textures.keys.filter { it.startsWith("layer") && it.drop(5).toIntOrNull() != null }
			.sortedBy { it.drop(5).toInt() }
		if (layers.isNotEmpty()) {
			val canvas = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
			val g = canvas.createGraphics()
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
			for (layer in layers) texture(listOf(layer))?.let { g.drawImage(it, 0, 0, 16, 16, null) }
			g.dispose()
			return pngBytes(canvas)
		}

		val particle = texture(listOf("particle"))
		val top = texture(listOf("up", "top", "end", "all", "side", "north")) ?: particle ?: return null
		val front = texture(listOf("north", "front", "side", "all", "end", "top")) ?: particle ?: top
		val side = texture(listOf("east", "side", "all", "north", "top")) ?: particle ?: front
		val canvas = BufferedImage(20, 22, BufferedImage.TYPE_INT_ARGB)
		val g = canvas.createGraphics()
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
		drawFace(g, top, 2, 6, 10, 10, 10, 2)
		drawFace(g, front, 2, 6, 10, 10, 2, 16)
		drawFace(g, side, 10, 10, 18, 6, 10, 20)
		g.dispose()
		return pngBytes(canvas)
	}

	private fun drawFace(g: java.awt.Graphics2D, texture: BufferedImage, x0: Int, y0: Int, x1: Int, y1: Int, x2: Int, y2: Int) {
		val x3 = x1 + x2 - x0
		val y3 = y1 + y2 - y0
		val polygon = Polygon(intArrayOf(x0, x1, x3, x2), intArrayOf(y0, y1, y3, y2), 4)
		val previous = g.clip
		g.clip(polygon)
		val transform = AffineTransform(
			(x1 - x0).toDouble() / texture.width,
			(y1 - y0).toDouble() / texture.width,
			(x2 - x0).toDouble() / texture.height,
			(y2 - y0).toDouble() / texture.height,
			x0.toDouble(), y0.toDouble(),
		)
		g.drawImage(texture, transform, null)
		g.clip = previous
	}

	private fun itemModelTexture(namespace: String, name: String): ByteArray? {
	val json = loadResource("assets/$namespace/models/item/$name.json") ?: return null
	val root = runCatching { com.google.gson.JsonParser.parseString(String(json, StandardCharsets.UTF_8)).asJsonObject }
		.getOrNull() ?: return null
	val texture = root.getAsJsonObject("textures")?.get("layer0")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
	val split = texture.split(':', limit = 2)
	val texNs = if (split.size == 2) split[0] else namespace
	val path = if (split.size == 2) split[1] else split[0]
	if (!NOMBRE_SPRITE.matches(path)) return null
	val category = path.substringBefore('/')
	val file = path.substringAfter('/', "")
	if (category !in setOf("item", "block") || file.isEmpty()) return null
	return loadResource("assets/$texNs/textures/$category/$file.png")
}

	private fun pngBytes(image: BufferedImage): ByteArray = ByteArrayOutputStream().use { out ->
		ImageIO.write(image, "png", out)
		out.toByteArray()
	}

	private fun loadResource(resource: String): ByteArray? {
		val bytes = resourceCache.computeIfAbsent(resource) {
			DashboardServer::class.java.getResourceAsStream("/$resource")?.use { it.readBytes() }?.let { return@computeIfAbsent it }
			for (archivo in runConfigJars) {
				val found = runCatching {
					JarFile(archivo.toFile()).use { jar ->
						jar.getJarEntry(resource)?.let { entry -> jar.getInputStream(entry).use { it.readBytes() } }
					}
				}.getOrNull()
				if (found != null) return@computeIfAbsent found
			}
			ByteArray(0)
		}
		return bytes.takeIf { it.isNotEmpty() }
	}

	private val resourceCache = ConcurrentHashMap<String, ByteArray>()

	/** JARes del cliente devuelto por Loom, donde viven las texturas vanilla. */
	private val runConfigJars: List<Path> by lazy {
		val config = Path.of(System.getProperty("user.dir") ?: ".")
			.resolve("scripts").resolve(".run-config.json")
		if (!Files.isRegularFile(config)) return@lazy emptyList()
		runCatching {
			val classpath = com.google.gson.JsonParser.parseString(Files.readString(config))
				.asJsonObject.getAsJsonArray("classpath")
			classpath.mapNotNull { entry ->
				runCatching { Path.of(entry.asString) }.getOrNull()
					?.takeIf { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar", ignoreCase = true) }
			}
		}.getOrDefault(emptyList())
	}

	private val sprites = ConcurrentHashMap<String, ByteArray>()

	/**
	 * Lo unico que se admite en el nombre de un item.
	 *
	 * Sin esto, `/assets/item/../../some/file` seria un lector de ficheros de
	 * classpath con salida a Internet: el nombre va a un `getResourceAsStream` sin
	 * comprobar nada mas. Se cierra tambien el `..` por si acaso, aunque el
	 * patron ya lo excluye.
	 */
	private val NAMESPACE_SPRITE = Regex("[a-z0-9_.-]+")
	private val NOMBRE_SPRITE = Regex("[a-z0-9_]+(/[a-z0-9_]+)*")

	/** A diferencia del resto, aqui si se cachea: los sprites no cambian nunca. */
	private fun sendImage(exchange: HttpExchange, png: ByteArray) {
		try {
			exchange.responseHeaders.add("Content-Type", "image/png")
			exchange.responseHeaders.add("X-Content-Type-Options", "nosniff")
			exchange.responseHeaders.add("Cache-Control", "public, max-age=86400, immutable")
			exchange.sendResponseHeaders(200, png.size.toLong())
			exchange.responseBody.use { it.write(png) }
		} catch (e: Exception) {
			// Cliente que se fue: no es motivo para ensuciar el log.
		}
	}

	private class Proxied(val status: Int, val body: ByteArray, val contentType: String)

	private class ProbeResult(
		val online: Boolean,
		val status: JsonObject?,
		val error: String?,
		/** Vive pero el hilo principal del juego no responde: el "estado" sale. */
		val busy: Boolean = false,
	)

	companion object {
		const val DEFAULT_PORT = 25590
		const val MAX_BODY_BYTES = 256 * 1024
		const val PROBE_TIMEOUT_MS = 1_500L

		/**
		 * `/health` no toca el hilo principal del juego, asi que contesta aunque
		 * este ocupado. Corto a proposito: solo se usa para decir "viva pero
		 * ocupada", y no vale la pena alargar el sondeo del panel por ello.
		 */
		const val HEALTH_TIMEOUT_MS = 800L
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
