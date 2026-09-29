package com.bonilla.puente

import com.bonilla.puente.http.HttpError
import com.bonilla.puente.http.Json
import com.bonilla.puente.http.Json.optInt
import com.bonilla.puente.http.Json.optString
import com.google.gson.JsonObject

/**
 * Logica de negocio expuesta por HTTP.
 *
 * No conoce el transporte: recibe argumentos ya validados y devuelve objetos
 * listos para serializar. Toda llamada a [MinecraftBridge] pasa por
 * [MainThreadBridge], de modo que aqui nunca se toca el juego desde un hilo HTTP.
 */
class PuenteController(
	private val bridge: MinecraftBridge,
	private val mainThread: MainThreadBridge,
	private val chatLog: ChatLog,
	private val modVersion: String,
) {
	private val startedAt = System.nanoTime()

	val uptimeMillis: Long get() = (System.nanoTime() - startedAt) / 1_000_000

	fun health(): JsonObject = Json.obj().apply {
		addProperty("status", "ok")
		addProperty("uptimeMs", uptimeMillis)
		addProperty("modVersion", modVersion)
	}

	fun status(): ClientStatus = mainThread.callOnMainThread { bridge.status() }

	fun players(): List<RemotePlayerInfo> = mainThread.callOnMainThread { bridge.onlinePlayers() }

	fun chat(limit: Int): List<CapturedMessage> = chatLog.drain(limit, all = false)

	fun chatHistory(limit: Int): List<CapturedMessage> = chatLog.snapshot(limit)

	fun sentHistory(): List<String> = chatLog.recentSent()

	fun sendChat(raw: String): String {
		val message = raw.trim()
		if (message.isEmpty()) throw HttpError(400, "empty_message", "el mensaje no puede estar vacio")
		if (message.length > MAX_MESSAGE_LENGTH) {
			throw HttpError(400, "message_too_long", "el mensaje supera los $MAX_MESSAGE_LENGTH caracteres")
		}
		// Enviar "/algo" por /chat seria ambiguo: el usuario querria un comando.
		// Se rechaza explicitamente para que el error sea visible y no silencioso.
		if (message.startsWith("/")) {
			throw HttpError(400, "leading_slash", "usa /puppeteer/command para comandos; aqui no se admite la barra inicial")
		}
		mainThread.callOnMainThread { bridge.sendChat(message) }
		return message
	}

	fun sendCommand(raw: String): String {
		var command = raw.trim()
		if (command.isEmpty()) throw HttpError(400, "empty_command", "el comando no puede estar vacio")
		if (command.length > MAX_MESSAGE_LENGTH) {
			throw HttpError(400, "command_too_long", "el comando supera los $MAX_MESSAGE_LENGTH caracteres")
		}
		// ClientPacketListener.sendCommand espera el comando SIN barra.
		// Aceptar ambas formas aqui seria mas comodo, pero la barra es una
		// ambiguedad clasica; se normaliza y se documenta.
		if (command.startsWith("/")) command = command.substring(1).trim()
		if (command.isEmpty()) throw HttpError(400, "empty_command", "el comando no puede estar vacio")

		mainThread.callOnMainThread { bridge.sendCommand(command) }
		return command
	}

	fun connect(body: JsonObject): String {
		// Forma preferida: "address": "host:puerto" (acepta tambien "host" sin puerto).
		var host = body.optString("host")?.trim().orEmpty()
		var port = body.optInt("port") ?: PuenteConfig.DEFAULT_PORT
		val address = body.optString("address")?.trim()
		if (address != null && address.isNotEmpty()) {
			val parsed = parseAddress(address)
			host = parsed.first
			port = parsed.second
		}

		if (host.isEmpty()) throw HttpError(400, "missing_host", "falta 'host' o 'address'")
		if (port !in 1..65535) throw HttpError(400, "invalid_port", "puerto fuera de rango (1-65535): $port")
		if (!isPlausibleHost(host)) throw HttpError(400, "invalid_host", "host invalido: $host")

		val name = body.optString("name")?.trim()?.takeIf { it.isNotEmpty() } ?: host
		mainThread.callOnMainThread { bridge.connect(host, port, name) }
		return formatAddress(host, port)
	}

	fun disconnect() {
		mainThread.callOnMainThread { bridge.disconnect() }
	}

	fun debugInfo(): JsonObject = Json.obj().apply {
		addProperty("chatBuffered", chatLog.size())
		addProperty("chatDropped", chatLog.droppedCount())
		addProperty("uptimeMs", uptimeMillis)
	}

	// ------------------------------------------------------------- Baritone

	/**
	 * Envia un comando de Baritone como **mensaje de chat**, nunca como comando
	 * de servidor. Es la diferencia que hace que esto funcione: Baritone
	 * intercepta el prefijo `#` en el chat, mientras que `sendCommand` va al
	 * servidor y lo rechaza.
	 *
	 * La respuesta de Baritone llega al chat, asi que se lee luego por
	 * `/chat` o `/chat/history`.
	 */
	fun baritone(command: String): String {
		mainThread.callOnMainThread { bridge.sendChat(command) }
		return command
	}

	fun baritoneReadOnly(name: String, query: String?): String = baritone(BaritoneTranslator.readOnly(name, query))

	fun baritoneMaintenance(name: String): String = baritone(BaritoneTranslator.maintenance(name))

	fun baritoneNoArg(name: String): String = baritone(BaritoneTranslator.noArg(name))

	fun baritoneGoto(x: Int?, y: Int?, z: Int?): String = baritone(BaritoneTranslator.goto(x, y, z))

	fun baritoneGotoBlock(block: String): String = baritone(BaritoneTranslator.gotoBlock(block))

	fun baritoneFind(block: String): String = baritone(BaritoneTranslator.find(block))

	fun baritoneMine(block: String, amount: Int?): String = baritone(BaritoneTranslator.mine(block, amount))

	fun baritoneBuild(file: String, origin: Triple<Int, Int, Int>?): String =
		baritone(BaritoneTranslator.build(file, origin))

	fun baritoneFollow(target: String): String = baritone(BaritoneTranslator.follow(target))

	fun baritoneStop(force: Boolean): String = baritone(BaritoneTranslator.stop(force))

	fun baritonePause(): String = baritone(BaritoneTranslator.pause())

	fun baritoneResume(): String = baritone(BaritoneTranslator.resume())

	fun baritoneThisway(distance: Int): String = baritone(BaritoneTranslator.thisway(distance))

	fun baritoneAxis(y: Int?): String = baritone(BaritoneTranslator.axis(y))

	fun baritoneTunnel(height: Int, width: Int, length: Int): String =
		baritone(BaritoneTranslator.tunnel(height, width, length))

	fun baritoneCleararea(radius: Int): String = baritone(BaritoneTranslator.cleararea(radius))

	fun baritoneExplore(x: Int?, z: Int?): String = baritone(BaritoneTranslator.explore(x, z))

	fun baritoneGoal(x: Int?, y: Int?, z: Int?): String = baritone(BaritoneTranslator.goal(x, y, z))

	private fun parseAddress(address: String): Pair<String, Int> {
		val trimmed = address.trim()
		if (trimmed.isEmpty()) throw HttpError(400, "invalid_address", "'address' vacio")

		// [ipv6]:puerto  |  host:puerto  |  host
		if (trimmed.startsWith("[")) {
			val end = trimmed.indexOf(']')
			if (end < 0) throw HttpError(400, "invalid_address", "direccion IPv6 sin cerrar: $address")
			val host = trimmed.substring(1, end)
			val rest = trimmed.substring(end + 1)
			val port = if (rest.startsWith(":")) parsePort(rest.substring(1), address) else PuenteConfig.DEFAULT_PORT
			return host to port
		}

		val lastColon = trimmed.lastIndexOf(':')
		if (lastColon < 0) return trimmed to PuenteConfig.DEFAULT_PORT

		// Sin puntos y con dos puntos es IPv6 sin puerto -> no tratarlo como host:puerto.
		if (trimmed.count { it == ':' } > 1) return trimmed to PuenteConfig.DEFAULT_PORT

		return trimmed.substring(0, lastColon) to parsePort(trimmed.substring(lastColon + 1), address)
	}

	private fun parsePort(text: String, address: String): Int {
		val port = text.toIntOrNull()
			?: throw HttpError(400, "invalid_port", "puerto invalido en '$address'")
		if (port !in 1..65535) throw HttpError(400, "invalid_port", "puerto fuera de rango en '$address': $port")
		return port
	}

	private fun isPlausibleHost(host: String): Boolean {
		if (host.length > 253) return false
		if (host.any { it.isWhitespace() || it == '/' }) return false
		return true
	}

	/**
	 * `host:port` legible. Un IPv6 sin corchetes seria ambiguo
	 * ("::1:25566" no dice donde acaba el host), asi que se entrecorcheta igual
	 * que hace la interfaz de Minecraft al escribir una direccion.
	 */
	private fun formatAddress(host: String, port: Int): String =
		if (host.contains(':')) "[$host]:$port" else "$host:$port"

	private companion object {
		const val MAX_MESSAGE_LENGTH = 256
	}
}
