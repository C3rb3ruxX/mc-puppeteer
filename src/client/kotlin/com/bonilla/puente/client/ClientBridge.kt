package com.bonilla.puente.client

import com.bonilla.puente.ClientStatus
import com.bonilla.puente.ItemSlot
import com.bonilla.puente.MinecraftBridge
import com.bonilla.puente.PlayerIdentity
import com.bonilla.puente.PlayerInventory
import com.bonilla.puente.PuenteException
import com.bonilla.puente.RemotePlayerInfo
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.User
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.core.UUIDUtil
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import org.slf4j.Logger
import java.util.Optional

/**
 * Implementacion de [MinecraftBridge] contra las APIs reales de MC 1.21.5.
 *
 * El mod compila contra los mappings **oficiales de Mojang**, asi que los
 * nombres son los reales de las clases del juego y no los de Yarn. Todo lo de
 * abajo se verifico con `javap` sobre los jars remapeados que deja Loom en
 * `~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/`, no de memoria
 * (el detalle esta en `agents/06-migracion-1.21.5.md`):
 * - `net.minecraft.client.Minecraft`               (no `MinecraftClient`: ese es
 *   el nombre de Yarn; Mojang llama a la clase `Minecraft`).
 * - `net.minecraft.client.multiplayer.ClientPacketListener` (no `ClientPlayNetworkHandler`).
 * - `Minecraft.level` / `.player` / `.screen` son **campos publicos**, no
 *   getters, y `disconnectFromWorld(...)` (el nombre de 26.x) no existe: el
 *   equivalente a "salir al titulo" son tres llamadas, ver [leaveWorld].
 * - `ClientCommonPacketListenerImpl.sendChat(String)` / `.sendCommand(String)`:
 *   en 1.21.5 heredan de ahi en vez de estar en `ClientPacketListener`, pero los
 *   nombres son los mismos.
 * - `ResourceKey.location()`, no `identifier()` (que era el nombre de 26.x).
 * - `User` tiene seis parametros de constructor, el ultimo es `User.Type`, y
 *   `LEGACY` es el de las cuentas sin autenticar (ver [setPlayerName]).
 * - `GameProfile` es una clase con getters (`getName()`/`getId()`), no un record
 *   como en 26.x, asi que Kotlin los expone como propiedades.
 *
 * TODOS los metodos se invocan desde el hilo principal de juego: lo garantiza
 * `MainThreadBridge`, que los agenda con `Minecraft.execute`.
 */
class ClientBridge(
	private val modVersion: String,
	private val logger: Logger,
) : MinecraftBridge {

	override fun status(): ClientStatus {
		val mc = Minecraft.getInstance()
		val connection = mc.getConnection()
		val level = mc.level
		val player = mc.player
		val serverData = connection?.serverData ?: mc.currentServer

		// Solo los mundos integrados (singleplayer) tienen nombre propio; en
		// multijugador ese dato es el nombre del servidor, que ya viaja arriba.
		val worldName = mc.singleplayerServer?.worldData?.levelName

		return ClientStatus(
			modVersion = modVersion,
			minecraftVersion = minecraftVersion,
			inWorld = level != null,
			screen = mc.screen?.javaClass?.simpleName,
			playerName = player?.gameProfile?.name,
			playerUuid = player?.uuid?.toString(),
			serverAddress = serverData?.ip,
			serverName = serverData?.name,
			worldName = worldName,
			dimension = level?.dimension()?.location()?.toString(),
			fps = mc.fps,
			playerCount = level?.players()?.size ?: 0,
			maxPlayers = serverData?.players?.max() ?: 0,
			windowActive = mc.isWindowActive,
			dead = player != null && !player.isAlive(),
		)
	}

	override fun respawn() {
		val mc = Minecraft.getInstance()
		val player = mc.player
			?: throw PuenteException(409, "not_connected", "El cliente no esta en ningun mundo")

		// `LocalPlayer.respawn()` envia un ServerboundClientCommandPacket de tipo
		// respawn. Si el jugador sigue vivo el servidor lo rechaza, asi que se
		// comprueba antes: mejor un 409 explicito que un error opaco del servidor.
		if (player.isAlive()) {
			throw PuenteException(
				409, "not_dead",
				"El jugador no esta muerto: no hay nada que reaparecer",
			)
		}

		player.respawn()
		logger.info("Reaparicion solicitada por la API HTTP")
	}

	override fun sendChat(message: String) {
		// `sendChat` espera el texto tal cual: la barra inicial ya se valido y
		// se rechazo en la capa HTTP, de modo que nunca llega como comando.
		requireConnection().sendChat(message)
	}

	override fun sendCommand(command: String) {
		// `sendCommand` espera el comando SIN barra inicial. La normalizacion
		// (quitar "/") se hace en la capa HTTP, no aqui.
		requireConnection().sendCommand(command)
	}

	override fun connect(host: String, port: Int, name: String) {
		val mc = Minecraft.getInstance()

		if (mc.level != null || mc.getConnection() != null) {
			// Hay que cerrar la sesion actual antes de abrir otra; si no, MC se
			// queda en una pantalla intermedia sin opcion de continuar.
			leaveWorld(mc)
		}

		val address = try {
			ServerAddress(host, port)
		} catch (e: Exception) {
			throw PuenteException(400, "invalid_address", "Direccion no valida $host:$port - ${e.message}")
		}

		val data = ServerData(name, "$host:$port", ServerData.Type.OTHER)
		// `parent` = pantalla actual (o la de titulo si no hay ninguna): al
		// cancelar la conexion se vuelve a ella. En 1.21.5 el ultimo parametro
		// es el `TransferState` (para "transfer" entre servidores) y admite null.
		ConnectScreen.startConnecting(mc.screen ?: TitleScreen(), mc, address, data, false, null)
		logger.info("Conectando a {}:{} solicitado por la API HTTP", host, port)
	}

	override fun disconnect() {
		val mc = Minecraft.getInstance()
		if (mc.level == null && mc.getConnection() == null) {
			throw PuenteException(409, "not_connected", "El cliente no esta conectado a ningun mundo")
		}
		leaveWorld(mc)
		logger.info("Desconexion solicitada por la API HTTP")
	}

	override fun onlinePlayers(): List<RemotePlayerInfo> {
		val connection = Minecraft.getInstance().getConnection() ?: return emptyList()
		return connection.onlinePlayers.map { info ->
			val profile = info.profile
			RemotePlayerInfo(
				name = profile.name,
				uuid = profile.id?.toString().orEmpty(),
				latencyMs = info.latency,
				displayName = info.tabListDisplayName?.string,
			)
		}
	}

	override fun inventory(): PlayerInventory {
		val player = Minecraft.getInstance().player
			?: throw PuenteException(409, "not_connected", "El cliente no esta en ningun mundo")

		// La mochila y la barra rapida son los 36 primeros slots de `Inventory`:
		// 0-8 barra rapida, 9-35 mochila. Se leen con `getItem(i)` para no
		// depender de como este partido el NonNullList por dentro.
		val inv = player.inventory
		val hotbar = (0 until HOTBAR_SIZE).map { inv.getItem(it).toSlot(it) }
		val main = (HOTBAR_SIZE until inv.containerSize).map { inv.getItem(it).toSlot(it) }

		return PlayerInventory(
			selectedSlot = inv.selectedSlot,
			hotbar = hotbar,
			main = main,
			armor = armorSlots(player),
			// La mano secundaria se lee del jugador, no del menu: es un metodo
			// publico y asi no hay que depender de la posicion del slot.
			offhand = player.getOffhandItem().toSlot(OFFHAND_INDEX),
		)
	}

	/**
	 * Armadura indexada por pieza.
	 *
	 * Cada pieza se lee con `LivingEntity.getItemBySlot`, que es metodo publico y
	 * estable: lo que lleva puesto la entidad, sin depender de como este partido
	 * el menu por dentro. Asi se evita `Player.inventoryMenu`, que obligaba a
	 * identificar los slots instances de `ArmorSlot`.
	 *
	 * La clase `ArmorSlot` no se puede nombrar desde aqui: en 1.21.5 es
	 * package-private (`class ArmorSlot extends Slot`, sin `public`), asi que
	 * referenciarla en codigo Kotlin no compila. En 26.3 si era publica, y por eso
	 * la version antigua de este metodo si podia hacer `slot is ArmorSlot`. El
	 * precio de no depender de ella es tener que conocer el orden del menu, que
	 * es fijo y es el que documenta `/inventory` (5 cabeza, 6 pecho, 7 piernas,
	 * 8 pies).
	 */
	private fun armorSlots(player: net.minecraft.client.player.LocalPlayer): Map<String, ItemSlot> {
		val pieces = LinkedHashMap<String, ItemSlot>()

		var slot = ARMOR_SLOT_START
		for (piece in ARMOR_PIECES) {
			pieces[piece.name.lowercase()] = player.getItemBySlot(piece).toSlot(slot)
			slot++
		}

		return pieces
	}

	/**
	 * Convierte un stack en un hueco del JSON.
	 *
	 * El identificador sale de la clave del registro (`minecraft:diamond_sword`),
	 * no del nombre traducido: el nombre depende del idioma del juego y no sirve
	 * para automatizar.
	 */
	private fun ItemStack?.toSlot(index: Int): ItemSlot {
		if (this == null || isEmpty) {
			return ItemSlot(index, id = null, count = 0, name = null, damage = null, maxDamage = null)
		}

		// `getMaxDamage()` es 0 en los objetos que no se estropean; solo tiene
		// sentido leer el desgaste en los que si.
		val maxDamage = getMaxDamage()
		return ItemSlot(
			index = index,
			// Se pide la clave al registro y no `builtInRegistryHolder()`, que
			// esta obsoleto. `DefaultedRegistry.getKey` es la via vigente.
			id = BuiltInRegistries.ITEM.getKey(getItem()).toString(),
			count = count,
			name = getHoverName().string,
			damage = if (maxDamage > 0) getDamageValue() else null,
			maxDamage = if (maxDamage > 0) maxDamage else null,
		)
	}

	override fun playerIdentity(): PlayerIdentity {
		val user = Minecraft.getInstance().user
		return PlayerIdentity(name = user.name, uuid = user.profileId.toString())
	}

	override fun setPlayerName(name: String): PlayerIdentity {
		val mc = Minecraft.getInstance()
		val current = mc.user

		if (!NAME_PATTERN.matches(name)) {
			throw PuenteException(
				400, "invalid_player_name",
				"Nombre invalido '$name'. Solo letras, digitos y guion bajo, de 1 a " +
					"${SharedConstants.MAX_PLAYER_NAME_LENGTH} caracteres",
			)
		}

		// Mismo algoritmo que usa el servidor para las cuentas sin autenticar, de
		// modo que la pareja nombre/UUID que envies sea la que el servidor espera.
		// Verificado en `net.minecraft.core.UUIDUtil.createOfflinePlayerUUID`:
		// `UUID.nameUUIDFromBytes(("OfflinePlayer:" + nombre).getBytes(UTF_8))`.
		val uuid = UUIDUtil.createOfflinePlayerUUID(name)

		// `net.minecraft.client.User` no tiene setters y su campo en `Minecraft`
		// es `private final`, asi que no hay API: hay que sustituir el objeto.
		//
		// El campo se localiza **por tipo** y no por nombre a proposito. En
		// runtime Fabric remapea a intermediary, asi que el nombre real del campo
		// no es `user`; el tipo, en cambio, si se conserva.
		val field = userField()
		val replacement = User(
			name,
			uuid,
			// El token se conserva: solo es relevante en servidores con modo
			// online, y esta identidad es valida para offline.
			current.accessToken,
			Optional.empty(),
			Optional.empty(),
			// `LEGACY` es la sesion sin autenticar de Mojang; con `MSA` el juego
			// intentaria autenticar contra Yggdrasil con un token que no
			// corresponde a este UUID. Con `LEGACY` se usa `MinecraftSessionService`,
			// que es lo que corresponde a una identidad offline.
			User.Type.LEGACY,
		)

		try {
			field.set(mc, replacement)
		} catch (e: IllegalAccessException) {
			throw PuenteException(
				500, "identity_not_writable",
				"No se pudo sustituir la identidad del cliente: ${e.message}",
			)
		}

		val inWorld = mc.level != null || mc.getConnection() != null
		val effective = mc.user.name
		if (effective != name) {
			// No deberia pasar: si `set` no hubiera surtido efecto, mejor fallar
			// aqui que devolver una identidad que no es la real.
			throw PuenteException(
				500, "identity_not_applied",
				"La identidad cambio a '$effective' en vez de '$name'",
			)
		}

		logger.info(
			"Identidad offline cambiada a {} (UUID {}) por la API HTTP{}", name, uuid,
			if (inWorld) ". Se aplicara en la proxima conexion" else "",
		)
		return PlayerIdentity(name = name, uuid = uuid.toString(), appliesOnNextConnect = inWorld)
	}

	override fun dispose() = Unit

	private fun requireConnection() =
		Minecraft.getInstance().getConnection()
			?: throw PuenteException(409, "not_connected", "El cliente no esta conectado a ningun servidor")

	/**
	 * "Salir al titulo" en 1.21.5, que es la operacion que hace el boton
	 * "Desconectar" del menu de pausa.
	 *
	 * En 26.3 era una sola llamada (`disconnectFromWorld(Component)`); aqui no
	 * existe tal metodo, asi que se compone con las tres piezas que usa el
	 * propio juego, en el mismo orden que `PauseScreen.onDisconnect()`:
	 *
	 * 1. `Connection.disconnect(motivo)`: el servidor recibe "menu.quitting" en
	 *    vez de un cierre generico.
	 * 2. `Minecraft.disconnect()`: cierra la conexion y desmonta el mundo. Para
	 *    un mundo integrado espera a que el servidor termine de guardar.
	 * 3. `setScreen(TitleScreen())`: `disconnect()` deja una pantalla de
	 *    progreso; el titulo es lo que espera quien llama a la API.
	 */
	private fun leaveWorld(mc: Minecraft) {
		mc.getConnection()?.connection?.disconnect(Component.translatable("menu.quitting"))
		mc.disconnect()
		mc.setScreen(TitleScreen())
	}

	/**
	 * Campo de [Minecraft] que guarda la identidad, localizado por tipo.
	 *
	 * `net.minecraft.client.User` es unico entre los campos de `Minecraft`
	 * (`userApiService` es de otro tipo), asi que el desempate es seguro.
	 */
	private fun userField(): java.lang.reflect.Field {
		val matches = Minecraft::class.java.declaredFields.filter { it.type == User::class.java }
		if (matches.size != 1) {
			throw PuenteException(
				500, "identity_field_not_found",
				"Se esperaba 1 campo de tipo net.minecraft.client.User y hay ${matches.size}. " +
					"La version de Minecraft podria no ser compatible con Puente.",
			)
		}
		val field = matches.single()
		// `Field.set` sobre un `final` no estatico funciona con `setAccessible`
		// (verificado en Java 21, que es el runtime de 1.21.5); los campos
		// estaticos finales si que lo requieren.
		field.isAccessible = true
		return field
	}

	companion object {
		/** Reglas de nombre de jugador de Minecraft: `SharedConstants` no expone el patron. */
		private val NAME_PATTERN = Regex("^[A-Za-z0-9_]{1,16}$")

		/** Slots 0-8 de `Inventory`: la barra rapida. */
		private const val HOTBAR_SIZE = 9

		/**
		 * Indice que se usa para el `index` de la mano secundaria.
		 *
		 * No es el del slot real (depende del menu), sino uno estable y
		 * negativo para que un bot distinga la mano secundaria de la mochila sin
		 * depender de la posicion.
		 */
		private const val OFFHAND_INDEX = -1

		/**
		 * Piezas de armadura en el orden en el que las recorre el menu de
		 * vanilla, que es el que refleja el `index` que documenta `/inventory`.
		 *
		 * El menu de un jugador mete 4 slots de fabricacion (0-4) y despues la
		 * armadura de cabeza a pies, asi que el indice se cuenta hacia delante
		 * desde [ARMOR_SLOT_START].
		 */
		private val ARMOR_PIECES = listOf(
			EquipmentSlot.HEAD,
			EquipmentSlot.CHEST,
			EquipmentSlot.LEGS,
			EquipmentSlot.FEET,
		)

		/** Primer slot de armadura del menu del jugador. */
		private const val ARMOR_SLOT_START = 5

		/** Resuelto una sola vez: la version de MC no cambia durante la sesion. */
		val minecraftVersion: String by lazy {
			runCatching { SharedConstants.getCurrentVersion().name }.getOrDefault("desconocida")
		}
	}
}
