package com.bonilla.client

import com.bonilla.puppeteer.ClientStatus
import com.bonilla.puppeteer.MinecraftBridge
import com.bonilla.puppeteer.PuppeteerException
import com.bonilla.puppeteer.RemotePlayerInfo
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.network.chat.Component
import org.slf4j.Logger

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
		)
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
			throw PuppeteerException(400, "invalid_address", "Direccion no valida $host:$port - ${e.message}")
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
			throw PuppeteerException(409, "not_connected", "El cliente no esta conectado a ningun mundo")
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

	override fun dispose() = Unit

	private fun requireConnection() =
		Minecraft.getInstance().getConnection()
			?: throw PuppeteerException(409, "not_connected", "El cliente no esta conectado a ningun servidor")

	companion object {
		/** Resuelto una sola vez: la version de MC no cambia durante la sesion. */
		val minecraftVersion: String by lazy {
			runCatching { SharedConstants.getCurrentVersion().name() }.getOrDefault("desconocida")
		}
	}
}
