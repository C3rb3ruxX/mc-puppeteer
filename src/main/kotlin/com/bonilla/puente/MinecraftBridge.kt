package com.bonilla.puente

import com.google.gson.JsonArray
import com.google.gson.JsonNull
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

	/** Jugadores actualmente en el tab list. */
	fun onlinePlayers(): List<RemotePlayerInfo>

	/**
	 * Instantanea del inventario del jugador: barra rapida, mochila, armadura y
	 * mano secundaria.
	 *
	 * Falla con `409 not_connected` si no hay mundo, porque sin `LocalPlayer` no
	 * hay inventario que leer.
	 */
	fun inventory(): PlayerInventory

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
	}
}

/**
 * Un hueco del inventario.
 *
 * Los huecos vacios **se conservan** con `id = null` y `count = 0` en lugar de
 * omitirse. Asi `hotbar[0]` es siempre el slot 0 y un bot puede indexar por
 * posicion sin tener que saltar los huecos, que es el error clasico al
 * depender de una lista compacta.
 *
 * @param id ruta del item en el registro, p. ej. `minecraft:diamond_sword`.
 *   Es el identificador estable; [name] es texto traducido y puede cambiar con
 *   el idioma, asi que no sirve para automatizar.
 * @param damage / [maxDamage] solo se rellenan en objetos con durabilidad; en
 *   el resto van a `null` para no sugerir que existe un desgaste.
 */
data class ItemSlot(
	val index: Int,
	val id: String?,
	val count: Int,
	val name: String?,
	val damage: Int?,
	val maxDamage: Int?,
) {
	val isEmpty: Boolean get() = id == null

	fun toJson(): JsonObject = JsonObject().apply {
		addProperty("index", index)
		addProperty("id", id)
		addProperty("count", count)
		addProperty("name", name)
		addProperty("damage", damage)
		addProperty("maxDamage", maxDamage)
	}
}

/**
 * Inventario completo del jugador.
 *
 * @param armor indexado por el nombre de la pieza (`head`, `chest`, `legs`,
 *   `feet`). Los nombres salen de `EquipmentSlot`, no de suponer el orden.
 */
data class PlayerInventory(
	val selectedSlot: Int,
	val hotbar: List<ItemSlot>,
	val main: List<ItemSlot>,
	val armor: Map<String, ItemSlot>,
	val offhand: ItemSlot?,
) {
	/** Cuantos huecos tienen algo. Un solo numero para pintar de un vistazo. */
	val filled: Int
		get() = hotbar.count { !it.isEmpty } +
			main.count { !it.isEmpty } +
			armor.values.count { !it.isEmpty } +
			if (offhand?.isEmpty == false) 1 else 0

	fun toJson(): JsonObject = JsonObject().apply {
		addProperty("selectedSlot", selectedSlot)
		add("hotbar", slotsToJson(hotbar))
		add("main", slotsToJson(main))
		add("armor", JsonObject().apply { armor.forEach { (piece, slot) -> add(piece, slot.toJson()) } })
		if (offhand == null) add("offhand", JsonNull.INSTANCE) else add("offhand", offhand.toJson())
		addProperty("filled", filled)
	}

	private fun slotsToJson(slots: List<ItemSlot>): JsonArray =
		JsonArray().also { array -> slots.forEach { array.add(it.toJson()) } }
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
