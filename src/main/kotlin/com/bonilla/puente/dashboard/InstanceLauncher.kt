package com.bonilla.puente.dashboard

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Arranca y para instancias de Minecraft desde el panel.
 *
 * ## Por que esto no es "ejecutar un comando"
 *
 * Un hub HTTP al que se le puede pedir "lanza esto" es el agujero favorito de
 * cualquier auditoria. Si el navegador puede mandar el comando, cualquier pagina
 * que se abra en ese navegador puede arrancar procesos con los permisos del
 * usuario. Asi que aqui el navegador solo manda **un numero de puerto**, y todo
 * lo demas esta cocido de antemano:
 *
 * - El puerto tiene que estar en la lista blanca ([allowedPorts]), que es el
 *   mismo rango que se escanea al descubrir. Nada de puertos sueltos.
 * - El ejecutable es el `java` del propio JDK que corre el hub, no uno que venga
 *   de fuera.
 * - La clase principal, el `launch.cfg` y el fichero de argumentos son rutas
 *   fijas dentro del proyecto ([launchConfig], [argFile], [runConfigFile]).
 * - El directorio de trabajo lo elige el hub ([instanceDir]) y se deriva del
 *   puerto, de modo que dos instancias nunca comparten mundo ni opciones.
 *
 * Lo unico que acepta del exterior es el puerto, y ademas solo uno del rango.
 *
 * ## De donde sale el comando
 *
 * No se codifica a mano. `devlaunchinjector` es quien sabe como arrancar un
 * cliente de Fabric en desarrollo, y lee su configuracion de `.gradle/loom-cache/
 * launch.cfg` (propiedades comunes, argumentos de cliente y del classpath) mas
 * el classpath de `build/loom-cache/argFiles/runClient`. Los dos los genera loom
 * en cada `./gradlew build`, y por eso la version de Minecraft no aparece
 * escrita en ningun sitio de este fichero: migrar de 26.3 a 1.21.5 no obliga a
 * tocarlo, porque el unico path que se menciona es el del propio repositorio.
 *
 * ## Aislamiento
 *
 * Minecraft toma el directorio de juego del directorio de trabajo del proceso,
 * y loom pasa las rutas absolutas de assets y de classes del mod. Cambiar solo
 * el CWD aísla mundo, opciones, config y logs sin tocar el classpath: es lo que
 * permite que la instancia 25581 no se pise con la 25580.
 */
class InstanceLauncher @JvmOverloads constructor(
	private val projectDir: Path = Path.of(System.getProperty("user.dir") ?: "."),
	private val allowedPorts: IntRange = DashboardServer.DEFAULT_SCAN_FROM..DashboardServer.DEFAULT_SCAN_TO,
) {

	/** PID de las instancias que ha arrancado **este** hub. */
	private val live = ConcurrentHashMap<Int, Long>()

	// --- rutas ---------------------------------------------------------------

	/** Carpeta de la instancia. Es la unica que se crea: el juego cuelga de aqui. */
	fun instanceDir(port: Int): Path = projectDir.resolve("run-instances").resolve("p$port")

	/**
	 * Carpeta de juego de la instancia: **es la propia** carpeta de la instancia.
	 *
	 * No hay un `run` por medio. Fabric Loader deduce el directorio de juego del
	 * directorio de trabajo del proceso, y el arranque del panel lo pone en
	 * [instanceDir], asi que ahi es donde aparecen `mods/`, `logs/` y `config/`.
	 * Se comprobo en el log de `p25581`: creo `mods/` y `logs/` en la raiz de la
	 * instancia, no en un `run` dentro. Por eso la config del mod va en
	 * `.../p<port>/config/mc-puppeteer.json`.
	 */
	fun gameDir(port: Int): Path = instanceDir(port)

	/** Config del mod de esta instancia, que es la que lleva su puerto y su token. */
	fun configFile(port: Int): Path = instanceDir(port).resolve("config").resolve("mc-puppeteer.json")

	/**
	 * Volcado de como arranca el cliente, que genera la rama de 1.21.5 con
	 * `./gradlew -I scripts/gradle-run-config.init.gradle dumpRunConfig`.
	 */
	fun runConfigFile(): Path = projectDir.resolve("scripts").resolve(".run-config.json")

	/**
	 * Classpath de desarrollo, en el formato `@fichero` que entiende la JVM.
	 *
	 * Windows no aguanta un classpath de 189 entradas en la linea de comandos, asi
	 * que va en fichero igual que hace `scripts/run-instances.ts`.
	 */
	fun argFile(): Path = projectDir.resolve("scripts").resolve(".cache").resolve("classpath.txt")

	/** Fichero de configuracion del arranque, que escribe loom (`configureLaunch`). */
	fun launchConfig(): Path = projectDir.resolve(".gradle").resolve("loom-cache").resolve("launch.cfg")

	/** Si el proyecto esta compilado y el volcado existe. */
	fun ready(): Boolean = Files.isRegularFile(runConfigFile()) && Files.isRegularFile(launchConfig())

	// --- config --------------------------------------------------------------

	/**
	 * Deja la configuracion del mod lista para arrancar en [port].
	 *
	 * Se escribe **antes** de lanzar porque el puerto se lee al arrancar: si no,
	 * todas las instancias saldrian en 25580 y solo una podria escuchar. El token
	 * se genera aqui y se devuelve para que el hub lo registre, con lo que la
	 * pagina no lo conoce nunca y el fichero de configuracion queda como unico
	 * sitio donde vive el secreto.
	 */
	fun seedConfig(port: Int): String {
		val file = configFile(port)
		Files.createDirectories(file.parent)

		val json = JsonObject()
		runCatching {
			val existing = JsonParser.parseString(Files.readString(file)).asJsonObject
			existing.entrySet().forEach { (k, v) -> json.add(k, v) }
		}

		json.addProperty("enabled", true)
		json.addProperty("host", "127.0.0.1")
		json.addProperty("port", port)
		// Se exige token siempre: son instancias que el navegador puede mandar a
		// cualquier parte, y el puerto por si solo no es una credencial.
		json.addProperty("requireToken", true)

		var token = json.get("authToken")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
		if (token.isBlank()) {
			token = newToken()
			json.addProperty("authToken", token)
		}

		Files.writeString(file, GSON.toJson(json))
		return token
	}

	/**
	 * Token nuevo: 32 bytes de [SecureRandom] en base64url.
	 *
	 * Se usa base64url y no hexadecimal para que no traer ni `+` ni `/`, que en
	 * una cabecera `Authorization` o en un fichero JSON dan mas problemas de los
	 * que aportan.
	 */
	private fun newToken(): String {
		val bytes = ByteArray(32)
		SecureRandom().nextBytes(bytes)
		return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
	}

	/**
	 * Token que hay sembrado en la configuracion de [port], o cadena vacia.
	 *
	 * Es la forma de que el hub se entere del token sin que se lo mande el
	 * navegador ni lo lea de una peticion: se lee del fichero que escribio
	 * [seedConfig], que es el unico sitio donde vive.
	 */
	fun tokenForPort(port: Int): String = runCatching {
		val file = configFile(port)
		if (!Files.isRegularFile(file)) return@runCatching ""
		JsonParser.parseString(Files.readString(file)).asJsonObject
			.get("authToken")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
	}.getOrDefault("")

	/**
	 * Copia los mods sueltos de la instancia principal a la nueva.
	 *
	 * El mod va por classpath, asi que el juego arranca sin esto. Lo que no llega
	 * por classpath es Baritone, que vive en `run/mods`. Sin copiarlo, la
	 * instancia arrancaria sin el y pareceria que el panel no puede mandar
	 * ningun comando de minado.
	 */
	private fun seedMods(port: Int) {
		val origen = projectDir.resolve("run").resolve("mods")
		if (!Files.isDirectory(origen)) return

		val destino = gameDir(port).resolve("mods")
		Files.createDirectories(destino)
		Files.list(origen).use { mods ->
			mods.filter { Files.isRegularFile(it) }.forEach { mod ->
				val copia = destino.resolve(mod.fileName.toString())
				if (!Files.exists(copia)) {
					Files.copy(mod, copia, StandardCopyOption.REPLACE_EXISTING)
				}
			}
		}
	}

	// --- proceso -------------------------------------------------------------

	/** PID de la instancia si sigue viva, o `null`. */
	fun runningPid(port: Int): Long? {
		val pid = live[port] ?: return null
		if (!alive(pid)) {
			live.remove(port)
			return null
		}
		return pid
	}

	// --- arranque ------------------------------------------------------------

	/** Lo que dice el volcado de como arranca el cliente. */
	private class RunConfig(
		val mainClass: String,
		val classpath: List<String>,
		val jvmArgs: List<String>,
		val programArgs: List<String>,
	)

	/**
	 * Lee `scripts/.run-config.json`, que escribe la tarea `dumpRunConfig`.
	 *
	 * Es el mismo volcado que usa `scripts/run-instances.ts`, y se lee aqui a
	 * proposito: la alternativa, usar `build/loom-cache/argFiles/runClient` tal
	 * cual, se rompe en cuanto el proyecto se migra de version. Ese fichero lo
	 * escribe loom solo al correr la tarea del cliente, asi que con un `build` a
	 * secas se queda con el classpath de la version anterior. Pasaron 189 entradas
	 * de Fabric API 0.161.0+26.3 mientras el juego ya era 1.21.5, y el cliente
	 * moria con "Incompatible mods found" antes de abrir la ventana.
	 */
	private fun readRunConfig(): RunConfig {
		val json = try {
			JsonParser.parseString(Files.readString(runConfigFile())).asJsonObject
		} catch (e: Exception) {
			throw InstanceLaunchException("scripts/.run-config.json no se puede leer: ${e.message}")
		}

		fun strings(key: String): List<String> {
			val arr = json.get(key) ?: return emptyList()
			if (!arr.isJsonArray) return emptyList()
			return arr.asJsonArray.mapNotNull { it.takeIf { v -> !v.isJsonNull }?.asString }
		}

		val classpath = strings("classpath")
		if (classpath.isEmpty()) {
			throw InstanceLaunchException(
				"scripts/.run-config.json no trae classpath; regeneralo con dumpRunConfig",
			)
		}

		return RunConfig(
			mainClass = json.get("mainClass")?.asString
				?: throw InstanceLaunchException("el volcado no trae 'mainClass'"),
			classpath = classpath,
			jvmArgs = strings("jvmArgs"),
			programArgs = strings("programArgs"),
		)
	}

	/**
	 * Escribe el classpath a un fichero `@args` y devuelve su ruta.
	 *
	 * El formato es el que la JVM entiende en un fichero de argumentos: una
	 * linea con `-classpath` y otra con las entradas separadas por `;` en Windows.
	 */
	private fun writeArgFile(classpath: List<String>): Path {
		val target = argFile()
		Files.createDirectories(target.parent)
		val separator = System.getProperty("path.separator")
		Files.writeString(target, "-classpath\n" + classpath.joinToString(separator) + "\n")
		return target
	}

	/**
	 * Arranca el cliente de [port].
	 *
	 * @throws InstanceLaunchException si el puerto no esta permitido, si ya hay
	 *   una viva, o si el proyecto no esta compilado.
	 */
	fun launch(port: Int): Long {
		if (port !in allowedPorts) {
			throw InstanceLaunchException(
				"puerto $port fuera de la lista permitida (${allowedPorts.first}..${allowedPorts.last})",
			)
		}
		if (!ready()) {
			throw InstanceLaunchException(
				"falta el volcado de arranque; ejecuta " +
					"'./gradlew -I scripts/gradle-run-config.init.gradle dumpRunConfig' (y un build antes)",
			)
		}
		runningPid(port)?.let { throw InstanceLaunchException("la instancia $port ya esta viva (pid $it)") }

		val cfg = readRunConfig()
		seedMods(port)
		seedConfig(port)

		val java = Path.of(System.getProperty("java.home"), "bin", "java.exe")
			.takeIf { Files.isRegularFile(it) }
			?: Path.of(System.getProperty("java.home"), "bin", "java")

		// El classpath se pasa por fichero porque son ~190 entradas y Windows
		// tiene un tope de longitud de linea de comandos.
		val argFile = writeArgFile(cfg.classpath)

		val command = buildList {
			add(java.toString())
			// Los `-Dfabric.dli.*` los calculo loom y vienen en el volcado: si se
			// compusieran aqui habria que acordarse de mantenerlos al migrar.
			addAll(cfg.jvmArgs)
			add("@${argFile.toAbsolutePath()}")
			add(cfg.mainClass)
			addAll(cfg.programArgs)
		}

		val dir = instanceDir(port)
		Files.createDirectories(dir)

		val log = dir.resolve("consola.log")
		val process = ProcessBuilder(command)
			.directory(dir.toFile())
			.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
			.redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()))
			.start()

		live[port] = process.pid()

		// Si el proceso muere al instante (puerto ocupado, sin memoria) no se
		// deja un PID fantasma en el mapa: se avisa aqui y no en el proximo stop.
		// Se comprueba que el PID sea el nuestro, por si en el hueco se hubiera
		// vuelto a lanzar otra instancia del mismo puerto.
		process.onExit().thenAccept { exited ->
			live.computeIfPresent(port) { _, pid -> if (pid == exited.pid()) null else pid }
		}
		if (!process.isAlive) {
			live.remove(port)
			throw InstanceLaunchException(
				"el cliente de $port se cerro nada mas arrancar; mira ${instanceDir(port).resolve("consola.log")}",
			)
		}

		return process.pid()
	}

	/**
	 * Para la instancia de [port].
	 *
	 * Solo se para lo que este hub arranco: el PID tiene que estar en [live]. No
	 * se acepta un PID de fuera, para que el panel no se convierta en un
	 * "mata procesos" disguise.
	 */
	fun stop(port: Int): Boolean {
		val pid = live[port] ?: return false
		if (!alive(pid)) {
			live.remove(port)
			return false
		}

		ProcessHandle.of(pid).ifPresent { handle ->
			handle.descendants().forEach { it.destroy() }
			handle.destroy()
		}

		// Minecraft tarda en cerrar; si sigue por ahi se le da margen antes de
		// forzar. Forzar a los 10 s evita que el bot quede vivo pero sin panel.
		val deadline = System.nanoTime() + 10_000_000_000L
		while (alive(pid) && System.nanoTime() < deadline) Thread.sleep(100)
		if (alive(pid)) {
			ProcessHandle.of(pid).ifPresent { it.destroyForcibly() }
		}

		live.remove(port)
		return true
	}

	/** Para todo lo que quede vivo. Lo usa el apagado del hub. */
	fun stopAll() {
		live.keys.toList().forEach { runCatching { stop(it) } }
	}

	private fun alive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

	/** Error de arranque, con mensaje pensado para verse en la pagina. */
	class InstanceLaunchException(message: String) : RuntimeException(message)

	companion object {
		private val GSON = GsonBuilder().setPrettyPrinting().create()
	}
}
