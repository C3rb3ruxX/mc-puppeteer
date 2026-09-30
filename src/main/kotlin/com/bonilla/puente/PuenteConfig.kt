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
 *
 * @param chest cofre de destino de `POST /store` sin coordenadas. Va a
 * `null` por defecto, y entonces se usa el sitio donde este el bot. Es **por
 * instancia**: cada instancia tiene su propio archivo de config, asi que cada
 * bot puede tener el suyo.
 *
 * `@JvmOverloads` genera tambien los constructores con menos argumentos, para
 * que el llamante de Java pueda usar los que existed antes de anadir `chest`
 * sin tener que tocar su codigo (Kotlin no tiene parametros por defecto).
 */
data class PuenteConfig @JvmOverloads constructor(
	var enabled: Boolean = true,
	var host: String = DEFAULT_HOST,
	var port: Int = DEFAULT_PORT,
	var requireToken: Boolean = false,
	var authToken: String = "",
	var chatBufferSize: Int = 256,
	var maxBodyBytes: Int = 16 * 1024,
	var requestTimeoutMs: Long = 5_000L,
	var httpThreads: Int = 4,
	var chest: ChestConfig? = null,
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
		// El cofre se valida aqui y no en el endpoint: es configuracion, y un
		// valor imposible se detecta al arrancar, no a mitad de un volcado.
		chest?.let {
			if (it.x !in MIN_X..MAX_X) problems += "chest.x fuera de rango ($MIN_X..$MAX_X): ${it.x}"
			if (it.z !in MIN_X..MAX_X) problems += "chest.z fuera de rango ($MIN_X..$MAX_X): ${it.z}"
			if (it.y !in MIN_Y..MAX_Y) problems += "chest.y fuera de rango ($MIN_Y..$MAX_Y): ${it.y}"
		}
		return problems
	}

	companion object {
		const val DEFAULT_HOST = "127.0.0.1"
		const val DEFAULT_PORT = 25580

		/**
		 * Limites de coordenadas de bloque de Minecraft 1.21.5.
		 *
		 * El eje vertical es generoso a proposito (`-2048..2048`) en vez del
		 * `-64..320` del mundo de sobrecreviente: asi una config copiada de un
		 * servidor con otro rango de alturas no se marca como invalida al
		 * arrancar. Los laterales si son los del mundo (`±30.000.000`), que es
		 * donde el servidor ya no responde.
		 */
		const val MIN_X = -30_000_000
		const val MAX_X = 30_000_000
		const val MIN_Y = -2048
		const val MAX_Y = 2048

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

/**
 * Cofre de destino en la config, como `{"chest":{"x":10,"y":-60,"z":4}}`.
 *
 * Va aparte de [PuenteConfig] y no como tres enteros sueltos para que el JSON
 * sea legible y para que `null` signifique de forma inequivoca "no hay cofre
 * configurado" (mismo valor que si el campo no estuviera).
 *
 * Todos los campos tienen valor por defecto, asi que sigue generandose el
 * constructor sin argumentos que Gson necesita: si el bloque `chest` esta a
 * medias en el archivo, sale con los que falten a 0 en vez de romper la carga.
 */
data class ChestConfig(
	var x: Int = 0,
	var y: Int = 0,
	var z: Int = 0,
)
