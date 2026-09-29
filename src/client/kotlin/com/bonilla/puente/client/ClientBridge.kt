package com.bonilla.puente.client

import com.bonilla.puente.ClientStatus
import com.bonilla.puente.MinecraftBridge
import com.bonilla.puente.PlayerIdentity
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
import net.minecraft.network.chat.Component
import org.slf4j.Logger
import java.util.Optional

/**
 * Implementacion de [MinecraftBridge] contra las APIs reales de MC 26.3.
 *
 * Nombres verificados con `javap` sobre el jar deobfuscado de 26.3 (no de memoria):
 * - `net.minecraft.client.Minecraft`                          (antes `MinecraftClient`)
 * - `net.minecraft.client.multiplayer.ClientPacketListener`  (antes `ClientPlayNetworkHandler`)
 * - `Minecraft.setScreenAndShow(...)` -> delega en `Minecraft.gui.setScreen(...)`;
 *   el campo `screen` **ya no existe** en `Minecraft` (ver `agents/00-research-dump.md`).
 * - `ResourceKey.identifier()` en vez de `location()`.
 * - `GameProfile` es un `record` en authlib 10: `id()` / `name()` explicitos.
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
			screen = mc.gui.screen()?.javaClass?.simpleName,
			playerName = player?.gameProfile?.name(),
			playerUuid = player?.uuid?.toString(),
			serverAddress = serverData?.ip,
			serverName = serverData?.name,
			worldName = worldName,
			dimension = level?.dimension()?.identifier()?.toString(),
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
			// `disconnectFromWorld` es el equivalente a "salir al titulo" en 26.3:
			// desconecta el nivel, cierra la conexion y muestra la TitleScreen.
			mc.disconnectFromWorld(Component.translatable("menu.quitting"))
		}

		val address = try {
			ServerAddress(host, port)
		} catch (e: Exception) {
			throw PuenteException(400, "invalid_address", "Direccion no valida $host:$port - ${e.message}")
		}

		val data = ServerData(name, "$host:$port", ServerData.Type.OTHER)
		// `parent` = pantalla actual (o la de titulo si no hay ninguna): al
		// cancelar la conexion se vuelve a ella. El parametro esta anotado como
		// no nulo en 26.3, asi que hay que garantizarlo aqui.
		ConnectScreen.startConnecting(mc.gui.screen() ?: TitleScreen(), mc, address, data, false, null)
		logger.info("Conectando a {}:{} solicitado por la API HTTP", host, port)
	}

	override fun disconnect() {
		val mc = Minecraft.getInstance()
		if (mc.level == null && mc.getConnection() == null) {
			throw PuenteException(409, "not_connected", "El cliente no esta conectado a ningun mundo")
		}
		mc.disconnectFromWorld(Component.translatable("menu.quitting"))
		logger.info("Desconexion solicitada por la API HTTP")
	}

	override fun onlinePlayers(): List<RemotePlayerInfo> {
		val connection = Minecraft.getInstance().getConnection() ?: return emptyList()
		return connection.getOnlinePlayers().map { info ->
			// `getProfile()` esta anotado como no nulo en 26.3.
			val profile = info.profile
			RemotePlayerInfo(
				name = profile.name(),
				uuid = profile.id()?.toString().orEmpty(),
				latencyMs = info.latency,
				displayName = info.tabListDisplayName?.string,
			)
		}
	}

	override fun playerIdentity(): PlayerIdentity {
		val user = Minecraft.getInstance().getUser()
		return PlayerIdentity(name = user.getName(), uuid = user.getProfileId().toString())
	}

	override fun setPlayerName(name: String): PlayerIdentity {
		val mc = Minecraft.getInstance()
		val current = mc.getUser()

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
			current.getAccessToken(),
			Optional.empty(),
			Optional.empty(),
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
		val effective = mc.getUser().getName()
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
		// (probado en Java 25); los campos estaticos finales si que lo requieren.
		field.isAccessible = true
		return field
	}

	companion object {
		/** Reglas de nombre de jugador de Minecraft: `SharedConstants` no expone el patron. */
		private val NAME_PATTERN = Regex("^[A-Za-z0-9_]{1,16}$")

		/** Resuelto una sola vez: la version de MC no cambia durante la sesion. */
		val minecraftVersion: String by lazy {
			runCatching { SharedConstants.getCurrentVersion().name() }.getOrDefault("desconocida")
		}
	}
}
