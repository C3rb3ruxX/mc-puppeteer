package com.bonilla.puente

import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Configuracion persistente del servidor HTTP.
 *
 * Todos los campos tienen valor por defecto, de modo que Kotlin genera el
 * constructor sin argumentos que Gson necesita para instanciarla: los campos
 * ausentes en el archivo conservan el valor por defecto.
 */
data class PuenteConfig(
	var enabled: Boolean = true,
	var host: String = DEFAULT_HOST,
	var port: Int = DEFAULT_PORT,
	var requireToken: Boolean = false,
	var authToken: String = "",
	var chatBufferSize: Int = 256,
	var maxBodyBytes: Int = 16 * 1024,
	var requestTimeoutMs: Long = 5_000L,
	var httpThreads: Int = 4,
) {
	val isLoopback: Boolean
		get() = host == "127.0.0.1" || host == "::1" || host == "localhost"

	fun validate(): List<String> {
		val problems = ArrayList<String>()
		if (port !in 1..65535) problems += "port fuera de rango (1-65535): $port"
		if (host.isBlank()) problems += "host vacio"
		if (httpThreads !in 1..64) problems += "httpThreads fuera de rango (1-64): $httpThreads"
		if (chatBufferSize !in 16..65536) problems += "chatBufferSize fuera de rango (16-65536): $chatBufferSize"
		if (maxBodyBytes !in 256..1_048_576) problems += "maxBodyBytes fuera de rango (256-1048576): $maxBodyBytes"
		if (requestTimeoutMs !in 100..60_000) problems += "requestTimeoutMs fuera de rango (100-60000): $requestTimeoutMs"
		if (requireToken && authToken.isBlank()) problems += "requireToken=true pero authToken esta vacio"
		return problems
	}

	companion object {
		const val DEFAULT_HOST = "127.0.0.1"
		const val DEFAULT_PORT = 25580

		private val GSON = GsonBuilder().setPrettyPrinting().create()

		fun configFile(): Path =
			FabricLoader.getInstance().configDir.resolve("mc-puppeteer.json")

		fun load(path: Path = configFile()): PuenteConfig {
			if (!Files.exists(path)) {
				val fresh = PuenteConfig()
				save(fresh, path)
				return fresh
			}

			return try {
				val text = Files.readString(path)
				GSON.fromJson(text, PuenteConfig::class.java) ?: PuenteConfig()
			} catch (e: JsonSyntaxException) {
				System.err.println("[mc-puppeteer] config invalido, se usan valores por defecto: ${e.message}")
				PuenteConfig()
			} catch (e: Exception) {
				System.err.println("[mc-puppeteer] no se pudo leer la config, se usan valores por defecto: ${e.message}")
				PuenteConfig()
			}
		}

		fun save(config: PuenteConfig, path: Path = configFile()) {
			Files.createDirectories(path.parent)
			val tmp = path.resolveSibling("${path.fileName}.tmp")
			Files.writeString(tmp, GSON.toJson(config))
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
		}
	}
}
