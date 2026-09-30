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

	/**
	 * Reaparece si el jugador esta muerto.
	 *
	 * Falla con `409 not_dead` si sigue vivo, y con `409 not_connected` si no hay
	 * mundo. Es idempotente en el sentido de que repetirla sobre un jugador vivo
	 * no hace daño: simplemente se rechaza.
	 */
	fun respawn()

	/**
	 * Inventario del jugador en el hilo principal: una entrada por ranura
	 * **ocupada**.
	 *
	 * Solo se devuelven las ranuras con algo dentro (ver [InventorySnapshot]): el
	 * contenedor tiene 41 ranuras y mandar 41 objetos en cada consulta seria
	 * ruido puro para quien lee, que lo que quiere es lo que lleva encima.
	 *
	 * Falla con `409 not_connected` si todavia no hay jugador.
	 */
	fun inventory(): InventorySnapshot

	/** Jugadores actualmente en el tab list. */
	fun onlinePlayers(): List<RemotePlayerInfo>

	/**
	 * Identidad con la que el cliente se presenta al conectar.
	 *
	 * Es distinta de `ClientStatus.playerName`: aquele es el jugador **ya
	 * conectado** (`LocalPlayer`), este es el nombre y UUID con los que se
	 * construira el proximo `ServerboundHelloPacket`.
	 */
	fun playerIdentity(): PlayerIdentity

	/**
	 * Cambia la identidad offline usada en la proxima conexion.
	 *
	 * El nombre se valida con las reglas de Minecraft (`[A-Za-z0-9_]{1,16}`) y
	 * el UUID se deriva con el mismo algoritmo del servidor, de modo que la
	 * pareja nombre/UUID es siempre coherente.
	 *
	 * No desconecta ni reinicia: solo sustituye el objeto de identidad. El
	 * cambio **surte efecto en la siguiente conexion**; si ya estas dentro de un
	 * mundo, sigues siendo el jugador anterior hasta que salgas y vuelvas a
	 * conectar. Ver [PlayerIdentity.appliesOnNextConnect].
	 */
	fun setPlayerName(name: String): PlayerIdentity

	/** Libera recursos del lado del cliente. */
	fun dispose()
}

/**
 * Identidad de la sesion: nombre y UUID con los que se conecta el cliente.
 *
 * @param appliesOnNextConnect `true` si se cambia mientras ya hay conexion, y
 * por tanto el nombre nuevo aun no es el efectivo en el mundo actual.
 */
data class PlayerIdentity(
	val name: String,
	val uuid: String,
	val appliesOnNextConnect: Boolean = false,
) {
	fun toJson(): JsonObject = JsonObject().apply {
		addProperty("name", name)
		addProperty("uuid", uuid)
		addProperty("appliesOnNextConnect", appliesOnNextConnect)
	}
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
	/** `true` si hay jugador y tiene 0 de vida. Permite encadenar con `/respawn`. */
	val dead: Boolean = false,
	/**
	 * Vida actual, en medios corazones (`20` = 10 corazones).
	 *
	 * `0` cuando no hay jugador: se manda como numero plano, no como `null`.
	 */
	val health: Float = 0f,
	/** Vida maxima del jugador. `20` es el valor normal; lo suben los efectos. */
	val maxHealth: Float = 20f,
	/** Puntos de comida, de 0 a 20 (`20` =barra llena). */
	val food: Int = 0,
	/** Saturacion de la comida, de 0 a 20. Determina cuanto aguanta sin comer. */
	val saturation: Float = 0f,
	/** Coordenada X del jugador, con decimales. `0` si no hay jugador. */
	val x: Double = 0.0,
	/** Coordenada Y (altura) del jugador, con decimales. `0` si no hay jugador. */
	val y: Double = 0.0,
	/** Coordenada Z del jugador, con decimales. `0` si no hay jugador. */
	val z: Double = 0.0,
	/** Nivel de experiencia (`totalExperience / xpProgress` dan la barra). */
	val xpLevel: Int = 0,
	/** Progreso dentro del nivel actual, de 0 a 1. */
	val xpProgress: Float = 0f,
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
		addProperty("dead", dead)
		addProperty("health", health)
		addProperty("maxHealth", maxHealth)
		addProperty("food", food)
		addProperty("saturation", saturation)
		addProperty("x", x)
		addProperty("y", y)
		addProperty("z", z)
		addProperty("xpLevel", xpLevel)
		addProperty("xpProgress", xpProgress)
	}
}

/**
 * Un item del inventario, tal y como estaba en su ranura.
 *
 * @param slot indice de la ranura en el contenedor del jugador (ver
 * [InventorySnapshot.items] para el reparto de indices).
 */
data class ItemStackInfo(
	val id: String,
	val name: String,
	val count: Int,
	val slot: Int,
) {
	fun toJson(): JsonObject = JsonObject().apply {
		addProperty("id", id)
		addProperty("name", name)
		addProperty("count", count)
		addProperty("slot", slot)
	}
}

/**
 * Foto del inventario del jugador.
 *
 * @param items **solo** las ranuras ocupadas, en orden de indice de ranura. El
 * contenedor tiene 41 ranuras (36 de inventario + 4 de armadura + 1 de mano
 * secundaria) y devolverlas todas haria que cada respuesta trajese 41
 * entradas, la mayoria vacias, para quien lo consume.
 */
data class InventorySnapshot(
	val items: List<ItemStackInfo>,
) {
	fun toJson(): JsonObject = JsonObject().apply {
		add("items", JsonArray().apply { items.forEach { add(it.toJson()) } })
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
