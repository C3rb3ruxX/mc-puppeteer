package com.bonilla.puente

import com.bonilla.puente.http.HttpError
import com.bonilla.puente.http.Json
import com.bonilla.puente.http.Json.optInt
import com.bonilla.puente.http.Json.optString
import com.bonilla.puente.http.Json.requireString
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.slf4j.Logger
import java.io.ByteArrayOutputStream
import java.net.BindException
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Transporte HTTP del mod.
 *
 * Es lo unico que corre en hilos propios: un pool de hilos daemon separado
 * del hilo de render de Minecraft. Aqui no se toca nunca la API del juego;
 * eso ocurre unicamente dentro de [MainThreadBridge].
 *
 * Usa `com.sun.net.httpserver` de la JDK para no anadir dependencias.
 */
class PuenteHttpServer(
	private val config: PuenteConfig,
	private val controller: PuenteController,
	private val logger: Logger,
) {
	private var server: HttpServer? = null
	private var pool: ExecutorService? = null
	private val rateLimiter = RateLimiter()

	@Volatile
	private var running = false

	fun start(): StartResult {
		if (running) return StartResult(started = true, boundAddress = boundAddress())

		return try {
			val executor = Executors.newFixedThreadPool(config.httpThreads, DaemonThreadFactory("mc-puppeteer-http"))
			val http = HttpServer.create(InetSocketAddress(config.host, config.port), BACKLOG)
			http.executor = { task ->
				try {
					executor.execute(task)
				} catch (e: RejectedExecutionException) {
					// Pool saturado tras el apagado: se descarta la peticion en vez de propagar.
					logger.debug("peticion HTTP descartada, pool en apagado")
				}
			}
			http.createContext(BASE_PATH, ::handle)
			http.createContext("/", ::handleUnknown)
			http.start()

			pool = executor
			server = http
			running = true

			val addr = "${config.host}:${http.address.port}"
			if (!config.isLoopback) {
				logger.warn(
					"ATENCION: el servidor HTTP escucha en {} (no es loopback). Cualquiera que alcance " +
						"ese puerto puede enviar comandos y chat con la cuenta de Minecraft activa.",
					addr,
				)
			}
			StartResult(started = true, boundAddress = addr)
		} catch (e: BindException) {
			logger.error("No se pudo abrir {}:{} - {} ya esta en uso o no permitido.", config.host, config.port, e.message)
			StartResult(started = false, boundAddress = null, error = "bind_failed: ${e.message}")
		} catch (e: Exception) {
			logger.error("No se pudo iniciar el servidor HTTP", e)
			StartResult(started = false, boundAddress = null, error = e.message ?: e.javaClass.name)
		}
	}

	fun stop() {
		if (!running) return
		running = false
		try {
			server?.stop(0)
		} catch (e: Exception) {
			logger.debug("error al detener HttpServer: ${e.message}")
		}
		server = null
		pool?.shutdownNow()
		pool = null
		logger.info("Servidor HTTP de mc-puppeteer detenido.")
	}

	private fun boundAddress(): String? = server?.let { "${config.host}:${it.address.port}" }

	// ---------------------------------------------------------------- routing

	private fun handleUnknown(exchange: HttpExchange) {
		// 404 en vez de propagar: este servidor no debe interferir si alguien
		// monta un servidor HTTP global en el mismo proceso.
		send(exchange, 404, errorBody("not_found", "Ruta desconocida: ${exchange.requestURI.path}"))
	}

	private fun handle(exchange: HttpExchange) {
		try {
			exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
			exchange.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type, Authorization")
			exchange.responseHeaders.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS")

			if (exchange.requestMethod == "OPTIONS") {
				exchange.sendResponseHeaders(204, -1)
				return
			}

			val ip = exchange.remoteAddress?.address?.hostAddress ?: "?"
			if (!rateLimiter.allow(ip)) {
				exchange.responseHeaders.add("Retry-After", RETRY_AFTER_SECONDS.toString())
				send(exchange, 429, errorBody("rate_limited", "Demasiadas peticiones, espera $RETRY_AFTER_SECONDS s"))
				return
			}

			val route = exchange.requestURI.path.removePrefix(BASE_PATH).trimEnd('/')

			// /health es el unico endpoint sin autenticacion: un supervisor puede
			// comprobar que el proceso vive sin que se exponga nada.
			if (route == "/health") {
				send(exchange, 200, okBody(controller.health()))
				return
			}

			if (!authorized(exchange)) {
				exchange.responseHeaders.add("WWW-Authenticate", "Bearer realm=\"mc-puppeteer\"")
				send(exchange, 401, errorBody("unauthorized", "Falta o es invalido el token de autorizacion"))
				return
			}

			dispatch(exchange, route)
		} catch (e: HttpError) {
			send(exchange, e.status, errorBody(e.code, e.message ?: e.code))
		} catch (e: PuenteException) {
			send(exchange, e.status, errorBody(e.code, e.message ?: e.code))
		} catch (e: Exception) {
			logger.error("Error no controlado en {} {}", exchange.requestMethod, exchange.requestURI.path, e)
			send(exchange, 500, errorBody("internal_error", e.message ?: e.javaClass.simpleName))
		}
	}

	private fun dispatch(exchange: HttpExchange, route: String) {
		when (route) {
			"", "/" -> send(exchange, 200, okBody(indexBody()))

			"/status" -> {
				requireMethod(exchange, "GET")
				send(exchange, 200, okBody(controller.status().toJson()))
			}

			"/debug" -> {
				requireMethod(exchange, "GET")
				send(exchange, 200, okBody(controller.debugInfo()))
			}

			"/players" -> {
				requireMethod(exchange, "GET")
				send(exchange, 200, okBody(JsonObject().apply {
					add("players", controller.players().toJsonArray())
				}))
			}

			"/chat" -> when (exchange.requestMethod) {
				"GET" -> {
					val limit = queryInt(exchange, "limit", 50, 1, 1000)
					send(exchange, 200, okBody(JsonObject().apply {
						addProperty("drained", true)
						add("messages", controller.chat(limit).toJsonArray())
					}))
				}
				"POST" -> {
					val body = readJson(exchange)
					val sent = controller.sendChat(body.requireString("message"))
					send(exchange, 202, okBody(JsonObject().apply { addProperty("sent", sent) }))
				}
				else -> methodNotAllowed(exchange, listOf("GET", "POST"))
			}

			"/chat/history" -> {
				requireMethod(exchange, "GET")
				val limit = queryInt(exchange, "limit", 50, 1, 1000)
				send(exchange, 200, okBody(JsonObject().apply {
					add("messages", controller.chatHistory(limit).toJsonArray())
					add("recentlySent", JsonArray().apply { controller.sentHistory().forEach { add(it) } })
				}))
			}

			"/command" -> {
				requireMethod(exchange, "POST")
				val body = readJson(exchange)
				val sent = controller.sendCommand(body.requireString("command"))
				send(exchange, 202, okBody(JsonObject().apply { addProperty("sent", sent) }))
			}

			"/connect" -> {
				requireMethod(exchange, "POST")
				val target = controller.connect(readJson(exchange))
				send(exchange, 202, okBody(JsonObject().apply { addProperty("connecting", target) }))
			}

			"/disconnect" -> {
				requireMethod(exchange, "POST")
				controller.disconnect()
				send(exchange, 202, okBody(JsonObject().apply { addProperty("disconnected", true) }))
			}

			"/baritone" -> send(exchange, 200, okBody(baritoneIndex()))

			// --- Baritone: consultas por GET ---------------------------------
			// GET con efecto secundario a proposito: Baritone es una interfaz de
			// comandos de una sola llamada y no tiene equivalente idempotente.
			// Es aceptable en loopback; documentado en agents/05-api.md.
			"/baritone/version" -> getBaritone(exchange) { controller.baritoneReadOnly("version", null) }
			"/baritone/proc" -> getBaritone(exchange) { controller.baritoneReadOnly("proc", null) }
			"/baritone/eta" -> getBaritone(exchange) { controller.baritoneReadOnly("eta", null) }
			"/baritone/modified" -> getBaritone(exchange) { controller.baritoneReadOnly("modified", null) }
			"/baritone/wp" -> getBaritone(exchange) { controller.baritoneReadOnly("wp", null) }
			"/baritone/help" -> getBaritone(exchange) { controller.baritoneReadOnly("help", queryString(exchange, "q")) }
			"/baritone/find" -> getBaritone(exchange) { controller.baritoneFind(queryString(exchange, "block").orEmpty()) }
			"/baritone/gc" -> getBaritone(exchange) { controller.baritoneReadOnly("gc", null) }

			// --- Baritone: mantenimiento por POST ----------------------------
			"/baritone/repack" -> postBaritone(exchange) { controller.baritoneMaintenance("repack") }
			"/baritone/reloadall" -> postBaritone(exchange) { controller.baritoneMaintenance("reloadall") }
			"/baritone/saveall" -> postBaritone(exchange) { controller.baritoneMaintenance("saveall") }
			"/baritone/render" -> postBaritone(exchange) { controller.baritoneMaintenance("render") }

			// --- Baritone: acciones por POST ---------------------------------
			"/baritone/goto" -> postBaritone(exchange) {
				val b = readJson(exchange)
				val (x, y, z) = coords(b)
				val block = b.optString("block")
				if (block != null) controller.baritoneGotoBlock(block)
				else controller.baritoneGoto(x, y, z)
			}

			"/baritone/goal" -> postBaritone(exchange) {
				val b = readJson(exchange)
				val (x, y, z) = coords(b)
				controller.baritoneGoal(x, y, z)
			}

			"/baritone/mine" -> postBaritone(exchange) {
				val b = readJson(exchange)
				controller.baritoneMine(
					b.requireString("block"),
					b.optInt("amount"),
				)
			}

			"/baritone/build" -> postBaritone(exchange) {
				val b = readJson(exchange)
				controller.baritoneBuild(b.requireString("file"), origin(b))
			}

			"/baritone/follow" -> postBaritone(exchange) {
				controller.baritoneFollow(readJson(exchange).requireString("target"))
			}

			"/baritone/stop" -> postBaritone(exchange) {
				controller.baritoneStop(exchange.requestURI.query?.contains("force") == true)
			}

			"/baritone/axis" -> postBaritone(exchange) {
				controller.baritoneAxis(readJson(exchange).optInt("y"))
			}

			"/baritone/tunnel" -> postBaritone(exchange) {
				val b = readJson(exchange)
				controller.baritoneTunnel(
					b.optInt("height") ?: 1,
					b.optInt("width") ?: 1,
					b.optInt("length") ?: 1,
				)
			}

			"/baritone/cleararea" -> postBaritone(exchange) {
				controller.baritoneCleararea(readJson(exchange).optInt("radius") ?: 1)
			}

			"/baritone/explore" -> postBaritone(exchange) {
				val b = readJson(exchange)
				controller.baritoneExplore(b.optInt("x"), b.optInt("z"))
			}

			"/baritone/surface", "/baritone/top", "/baritone/invert",
			"/baritone/come", "/baritone/blacklist", "/baritone/elytra",
			"/baritone/farm", "/baritone/cancel", "/baritone/path", "/baritone/thisway" ->
				postBaritone(exchange) { controller.baritoneNoArg(route.substringAfterLast("/")) }

			else -> send(exchange, 404, errorBody("not_found", "Endpoint desconocido: $BASE_PATH$route"))
		}
	}

	private fun getBaritone(exchange: HttpExchange, action: () -> String) {
		requireMethod(exchange, "GET")
		send(exchange, 202, okBody(JsonObject().apply { addProperty("sent", action()) }))
	}

	private fun postBaritone(exchange: HttpExchange, action: () -> String) {
		requireMethod(exchange, "POST")
		send(exchange, 202, okBody(JsonObject().apply { addProperty("sent", action()) }))
	}

	/** Lee x/y/z del cuerpo. Devuelve nulls en los ausentes; el traductor valida. */
	private fun coords(body: JsonObject): Triple<Int?, Int?, Int?> =
		Triple(body.optInt("x"), body.optInt("y"), body.optInt("z"))

	private fun origin(body: JsonObject): Triple<Int, Int, Int>? {
		val x = body.optInt("x") ?: return null
		val y = body.optInt("y") ?: return null
		val z = body.optInt("z") ?: return null
		return Triple(x, y, z)
	}

	private fun baritoneIndex(): JsonObject = Json.obj().apply {
		addProperty("mod", "mc-puppeteer")
		addProperty("component", "baritone")
		addProperty("note", "Los comandos se envian como chat con prefijo '#'. La respuesta llega al chat: leela en /chat.")
		add("get", JsonArray().apply {
			READ_ONLY_ROUTES.forEach { add("GET  $BASE_PATH/baritone/$it") }
		})
		add("post", JsonArray().apply {
			POST_ROUTES.forEach { add("POST $BASE_PATH/baritone/$it") }
		})
	}

	private val READ_ONLY_ROUTES = listOf("version", "proc", "eta", "modified", "wp", "help", "find", "gc")
	private val POST_ROUTES = listOf(
		"goto", "goal", "mine", "build", "follow", "stop", "axis", "tunnel", "cleararea", "explore",
		"surface", "top", "invert", "come", "blacklist", "elytra", "farm", "cancel", "path", "thisway",
		"repack", "reloadall", "saveall", "render",
	)

	private fun indexBody(): JsonObject = JsonObject().apply {
		addProperty("mod", "mc-puppeteer")
		addProperty("basePath", BASE_PATH)
		add("endpoints", JsonArray().apply {
			add("GET  $BASE_PATH/health")
			add("GET  $BASE_PATH/status")
			add("GET  $BASE_PATH/debug")
			add("GET  $BASE_PATH/players")
			add("GET  $BASE_PATH/chat?limit=N")
			add("GET  $BASE_PATH/chat/history?limit=N")
			add("POST $BASE_PATH/chat      { \"message\": \"hola\" }")
			add("POST $BASE_PATH/command   { \"command\": \"list\" }   (sin barra)")
			add("POST $BASE_PATH/connect   { \"address\": \"host:puerto\" }")
			add("POST $BASE_PATH/disconnect")
		})
	}

	// ------------------------------------------------------------------ auth

	/**
	 * Comparacion en tiempo constante para no filtrar el token por temporizacion:
	 * `MessageDigest.isEqual` no sale antes de tiempo aunque difiera la longitud.
	 */
	private fun authorized(exchange: HttpExchange): Boolean {
		if (!config.requireToken) return true

		val header = exchange.requestHeaders.getFirst("Authorization") ?: return false
		if (!header.startsWith("Bearer ", ignoreCase = true)) return false
		val presented = header.substring(7).trim()
		if (presented.isEmpty()) return false

		return MessageDigest.isEqual(
			presented.toByteArray(Charsets.UTF_8),
			config.authToken.toByteArray(Charsets.UTF_8),
		)
	}

	// ---------------------------------------------------------------- helpers

	private fun requireMethod(exchange: HttpExchange, vararg allowed: String) {
		if (exchange.requestMethod !in allowed) methodNotAllowed(exchange, allowed.toList())
	}

	private fun methodNotAllowed(exchange: HttpExchange, allowed: List<String>) {
		val list = allowed.joinToString(", ")
		exchange.responseHeaders.add("Allow", list)
		throw HttpError(405, "method_not_allowed", "Metodo ${exchange.requestMethod} no permitido; usa $list")
	}

	private fun queryInt(exchange: HttpExchange, name: String, default: Int, min: Int, max: Int): Int {
		val raw = queryString(exchange, name) ?: return default
		return raw.toIntOrNull()?.takeIf { it in min..max }
			?: throw HttpError(400, "invalid_query", "$name debe ser un entero entre $min y $max")
	}

	/** Valor de un parametro de query, o null si no esta o va vacio. */
	private fun queryString(exchange: HttpExchange, name: String): String? =
		exchange.requestURI.query?.split('&')
			?.firstOrNull { it.substringBefore('=') == name }
			?.substringAfter('=', "")
			?.takeIf { it.isNotEmpty() }

	/** Lee el body con tope de tamano: un body enorme no debe agotar la memoria. */
	private fun readBody(exchange: HttpExchange): String {
		exchange.requestBody.use { input ->
			val buffer = ByteArray(8192)
			val out = ByteArrayOutputStream()
			while (true) {
				val read = input.read(buffer)
				if (read < 0) break
				if (out.size() + read > config.maxBodyBytes) {
					throw HttpError(413, "body_too_large", "El cuerpo supera el maximo de ${config.maxBodyBytes} bytes")
				}
				out.write(buffer, 0, read)
			}
			return out.toString(Charsets.UTF_8.name())
		}
	}

	private fun readJson(exchange: HttpExchange): JsonObject {
		val raw = readBody(exchange)
		if (raw.isBlank()) throw HttpError(400, "empty_body", "Se esperaba un cuerpo JSON")
		return Json.parse(raw)
	}

	private fun okBody(data: com.google.gson.JsonElement): String =
		Json.write(JsonObject().apply {
			addProperty("ok", true)
			add("data", data)
		})

	private fun errorBody(code: String, message: String): String =
		Json.write(JsonObject().apply {
			addProperty("ok", false)
			add("error", JsonObject().apply {
				addProperty("code", code)
				addProperty("message", message)
			})
		})

	private fun send(exchange: HttpExchange, status: Int, body: String) {
		try {
			val bytes = body.toByteArray(Charsets.UTF_8)
			exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
			exchange.sendResponseHeaders(status, bytes.size.toLong())
			exchange.responseBody.use { it.write(bytes) }
		} catch (e: Exception) {
			logger.debug("no se pudo enviar la respuesta: ${e.message}")
		} finally {
			exchange.close()
		}
	}

	data class StartResult(
		val started: Boolean,
		val boundAddress: String?,
		val error: String? = null,
	)

	private companion object {
		const val BASE_PATH = "/puppeteer"
		const val BACKLOG = 16
		const val RETRY_AFTER_SECONDS = 5
	}
}

/**
 * Limitador de tasa por IP con ventana deslizante.
 *
 * El deque se sincroniza por objeto: se hace `removeFirst` en cada peticion
 * para ir purgando, que es O(1) amortizado con un solo writer tipico.
 */
private class RateLimiter(
	private val max: Int = 120,
	private val windowMs: Long = 10_000L,
) {
	private val hits = ConcurrentHashMap<String, ArrayDeque<Long>>()

	fun allow(key: String): Boolean {
		val now = System.currentTimeMillis()
		val deque = hits.computeIfAbsent(key) { ArrayDeque() }
		synchronized(deque) {
			// ArrayDeque de Kotlin: `first()` en vez del `peekFirst()` de java.util.
			while (deque.isNotEmpty() && now - deque.first() > windowMs) deque.removeFirst()
			if (deque.size >= max) return false
			deque.addLast(now)
			return true
		}
	}
}

/** Hilos daemon: no deben impedir que la JVM de Minecraft termine. */
private class DaemonThreadFactory(private val prefix: String) : ThreadFactory {
	private val counter = AtomicInteger()
	override fun newThread(r: Runnable): Thread =
		Thread(r, "$prefix-${counter.incrementAndGet()}").apply { isDaemon = true }
}
