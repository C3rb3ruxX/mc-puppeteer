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
				path == "api/health" && method == "GET" -> send(exchange, 200, json(okBody(Json.obj().apply {
					addProperty("status", "ok")
					addProperty("instances", registry.all().size)
				})))

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

	private fun routeProxy(exchange: HttpExchange, method: String, path: String) {
		val rest = path.removePrefix("api/instances/")
		val slash = rest.indexOf('/')
		val id = if (slash < 0) rest else rest.substring(0, slash)
		val tail = if (slash < 0) "" else rest.substring(slash)

		val instance = registry.find(id)
			?: throw BadRequest("no existe la instancia '$id'", "unknown_instance")

		// Barrera anti-SSRF: aunque el registro se hubiera manipulated, aqui no
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
		val suffix = if (tail.isEmpty()) "/status" else tail
		val target = URI(
			"http://${instance.host}:${instance.port}/puppeteer$suffix" +
				if (query != null) "?$query" else "",
		)

		val builder = HttpRequest.newBuilder(target)
			.timeout(Duration.ofMillis(PROXY_TIMEOUT_MS))
			.header("Accept", "application/json")

		if (instance.token.isNotEmpty()) {
			// Deliberadamente **no** se reenvia la Authorization del navegador:
			// la pagina no necesita conocer el token de cada instancia.
			builder.header("Authorization", "Bearer ${instance.token}")
		}

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
		val request = HttpRequest.newBuilder(
			URI("http://${instance.host}:${instance.port}/puppeteer/status"),
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

		/** El panel va empaquetado como recurso, no en un string gigantic. */
		private val INDEX: String by lazy {
			DashboardServer::class.java.getResourceAsStream("/puente-dashboard/index.html")
				?.use { String(it.readBytes(), StandardCharsets.UTF_8) }
				?: error("falta el recurso /puente-dashboard/index.html")
		}
	}
}
