package com.bonilla.puente.client

import com.bonilla.McPuppeteer
import com.bonilla.puente.ChatLog
import com.bonilla.puente.MainThreadBridge
import com.bonilla.puente.PuenteConfig
import com.bonilla.puente.PuenteController
import com.bonilla.puente.PuenteHttpServer
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.Base64

/**
 * Punto de entrada del lado del cliente.
 *
 * Aqui se conecta todo: config -> puente -> controlador -> transporte HTTP.
 * El nucleo (source set `main`) no sabe nada de Minecraft; este archivo es el
 * unico que hace de costura entre ambos mundos.
 */
object PuenteClient : ClientModInitializer {

	private val logger = LoggerFactory.getLogger(McPuppeteer.MOD_ID)

	private var server: PuenteHttpServer? = null

	override fun onInitializeClient() {
		val config = PuenteConfig.load()
		val problems = config.validate()

		if (!config.enabled) {
			logger.info("mc-puppeteer esta deshabilitado en la configuracion (enabled=false).")
			return
		}

		if (problems.isNotEmpty()) {
			problems.forEach { logger.error("Configuracion invalida: {}", it) }
			logger.error("Se usan valores por defecto para poder arrancar; revisa la config y reinicia.")
			applyDefaults(config)
		}

		if (config.requireToken && config.authToken.isBlank()) {
			val generated = generateToken()
			config.authToken = generated
			PuenteConfig.save(config)
			logger.warn(
				"Se genero un token de autorizacion nuevo. Guardalo: solo se muestra una vez. " +
					"Usalo como 'Authorization: Bearer <token>'",
			)
			logger.warn("TOKEN: {}", generated)
		}

		val chatLog = ChatLog(config.chatBufferSize)
		val bridge = ClientBridge(modVersion(), logger)
		val controller = PuenteController(
			bridge = bridge,
			mainThread = MainThreadBridge(ClientMainThread, config.requestTimeoutMs),
			chatLog = chatLog,
			modVersion = modVersion(),
		)

		ChatCapture(chatLog).register()

		val http = PuenteHttpServer(config, controller, logger)
		val result = http.start()
		server = http

		if (!result.started) {
			logger.error("El servidor HTTP no arranco: {}", result.error)
			return
		}

		logger.info("mc-puppeteer escuchando en http://{}", result.boundAddress)
		logger.info("  Base de la API: http://{}/puppeteer", result.boundAddress)
		logger.info("  Autenticacion: {}", if (config.requireToken) "Bearer token (obligatoria)" else "DESHABILITADA")
		logger.info("  No se envia ningun dato fuera de esta maquina salvo que cambies 'host' en la config.")

		ClientLifecycleEvents.CLIENT_STOPPING.register {
			// Apagado limpio: si el proceso muere con el pool vivo, quedan hilos
			// no-daemon colgando. Son daemon, pero se cierra igual por orden.
			http.stop()
			bridge.dispose()
			server = null
		}
	}

	/** Deja la config en un estado arrancable tras un `validate()` fallido. */
	private fun applyDefaults(config: PuenteConfig) {
		if (config.port !in 1..65535) config.port = PuenteConfig.DEFAULT_PORT
		if (config.host.isBlank()) config.host = PuenteConfig.DEFAULT_HOST
		if (config.httpThreads !in 1..64) config.httpThreads = 4
		if (config.chatBufferSize !in 16..65536) config.chatBufferSize = 256
		if (config.maxBodyBytes !in 256..1_048_576) config.maxBodyBytes = 16 * 1024
		if (config.requestTimeoutMs !in 100..60_000L) config.requestTimeoutMs = 5_000L
		if (config.requireToken && config.authToken.isBlank()) config.requireToken = false
	}

	private fun modVersion(): String =
		runCatching {
			net.fabricmc.loader.api.FabricLoader.getInstance()
				.getModContainer(McPuppeteer.MOD_ID)
				.orElse(null)?.metadata?.version?.friendlyString
		}.getOrNull() ?: "desconocida"

	/** 256 bits de entropia en Base64 URL-safe: 32 bytes de SecureRandom. */
	private fun generateToken(): String {
		val bytes = ByteArray(32)
		SecureRandom().nextBytes(bytes)
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
	}
}
