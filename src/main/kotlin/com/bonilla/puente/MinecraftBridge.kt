package com.bonilla.puente

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Puente hacia Minecraft.
 *
 * REGLA DE ORO: todos los metodos de esta interfaz se ejecutan **siempre en el
 * hilo principal de Minecraft**. Quien llama (el servidor HTTP) no debe
 * tocarlos directamente; debe pasar por [MainThreadBridge.callOnMainThread],
 * que los agenda con `Minecraft.execute`.
 *
 * Vive en el source set `main` para que la capa HTTP no tenga ninguna
 * referencia a clases de cliente de Minecraft.
 */
interface MinecraftBridge {

	/** Instantanea inmutable del estado del cliente. */
	fun status(): ClientStatus

	/**
	 * Envia un mensaje de chat al servidor.
	 * @param message texto sin barra inicial.
	 */
	fun sendChat(message: String)

	/**
	 * Envia un comando al servidor.
	 * @param command comando **sin** la barra inicial (p. ej. `list`, no `/list`).
	 */
	fun sendCommand(command: String)

	/** Conecta a un servidor arbitrario. */
	fun connect(host: String, port: Int, name: String)

	/** Sale del mundo/servidor actual. */
	fun disconnect()

	/** Jugadores actualmente en el tab list. */
	fun onlinePlayers(): List<RemotePlayerInfo>

	/** Libera recursos del lado del cliente. */
	fun dispose()
}

data class ClientStatus(
	val modVersion: String,
	val minecraftVersion: String,
	val inWorld: Boolean,
	val screen: String?,
	val playerName: String?,
	val playerUuid: String?,
	val serverAddress: String?,
	val serverName: String?,
	val worldName: String?,
	val dimension: String?,
	val fps: Int,
	val playerCount: Int,
	val maxPlayers: Int,
	val windowActive: Boolean,
) {
	fun toJson(): JsonObject = JsonObject().apply {
		addProperty("modVersion", modVersion)
		addProperty("minecraftVersion", minecraftVersion)
		addProperty("inWorld", inWorld)
		addProperty("screen", screen)
		addProperty("playerName", playerName)
		addProperty("playerUuid", playerUuid)
		addProperty("serverAddress", serverAddress)
		addProperty("serverName", serverName)
		addProperty("worldName", worldName)
		addProperty("dimension", dimension)
		addProperty("fps", fps)
		addProperty("playerCount", playerCount)
		addProperty("maxPlayers", maxPlayers)
		addProperty("windowActive", windowActive)
	}
}

data class RemotePlayerInfo(
	val name: String,
	val uuid: String,
	val latencyMs: Int,
	val displayName: String?,
) {
	fun toJson(): JsonObject = JsonObject().apply {
		addProperty("name", name)
		addProperty("uuid", uuid)
		addProperty("latencyMs", latencyMs)
		addProperty("displayName", displayName)
	}
}

/** Mensaje capturado en el hilo principal, publicado de forma inmutable. */
data class CapturedMessage(
	val epochMillis: Long,
	val kind: Kind,
	val text: String,
	val sender: String?,
	val overlay: Boolean,
) {
	enum class Kind { CHAT, GAME, SYSTEM, SENT }

	fun toJson(): JsonObject = JsonObject().apply {
		addProperty("epochMillis", epochMillis)
		addProperty("kind", kind.name.lowercase())
		addProperty("text", text)
		addProperty("sender", sender)
		addProperty("overlay", overlay)
	}
}

// Los dos helpers se erasurean a la misma firma JVM; @JvmName los distingue.
@JvmName("remotePlayersToJsonArray")
internal fun List<RemotePlayerInfo>.toJsonArray(): JsonArray =
	JsonArray().also { array -> forEach { array.add(it.toJson()) } }

@JvmName("messagesToJsonArray")
internal fun List<CapturedMessage>.toJsonArray(): JsonArray =
	JsonArray().also { array -> forEach { array.add(it.toJson()) } }
