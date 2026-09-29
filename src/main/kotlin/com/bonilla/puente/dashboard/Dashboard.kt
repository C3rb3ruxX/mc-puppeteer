package com.bonilla.puente.dashboard

import java.awt.Desktop
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * Arranque del panel de control.
 *
 * Se lanza con `./gradlew runDashboard` y abre `http://127.0.0.1:25590`.
 *
 * ```
 * ./gradlew runDashboard --args="--port 25590"
 * ```
 *
 * No necesita Minecraft ni Fabric: solo el Gson que ya trae el proyecto, asi
 * que se puede levantar para inspeccionar las instancias sin arrancar el juego.
 */
object Dashboard {

	@JvmStatic
	fun main(args: Array<String>) {
		val options = parseArgs(args)
		val port = options["port"]?.toIntOrNull() ?: DashboardServer.DEFAULT_PORT
		if (port !in 1..65535) {
			System.err.println("[dashboard] puerto invalido: $port")
			exitProcess(2)
		}

		val file: Path = options["instances"]?.let { Paths.get(it) }
			?: Paths.get("dashboard-instances.json")

		val registry = InstanceRegistry(file).apply { load() }
		val server = DashboardServer(registry, port)
		val bound = try {
			server.start()
		} catch (e: Exception) {
			System.err.println("[dashboard] no se pudo arrancar en el puerto $port: ${e.message}")
			exitProcess(1)
		}

		val url = "http://${LOOPBACK}:$bound/"
		println("""
			|
			|  Panel de Puente
			|  $url
			|
			|  Instancias: ${registry.all().joinToString { "${it.name} (:${it.port})" }}
			|  Registro:   ${file.toAbsolutePath()}
			|
			|  Ctrl+C para salir. El panel solo escucha en loopback.
			|
		""".trimMargin())

		openBrowser(url, options["no-open"] == null)

		// El hub vive hasta que lo corten: los hilos son daemon, asi que basta
		// con no salir de aqui.
		Thread.currentThread().join()
	}

	private fun openBrowser(url: String, enabled: Boolean) {
		if (!enabled) return
		try {
			if (Desktop.isDesktopSupported()) {
				Desktop.getDesktop().browse(URI(url))
			}
		} catch (e: Exception) {
			// En un entorno sin escritorio no es un problema: la URL ya esta
			// impresa por pantalla.
		}
	}

	/** Acepta `--clave valor` y `--clave=valor`. */
	private fun parseArgs(args: Array<String>): Map<String, String> {
		val out = HashMap<String, String>()
		var i = 0
		while (i < args.size) {
			val arg = args[i]
			if (!arg.startsWith("--")) {
				i++
				continue
			}
			val body = arg.removePrefix("--")
			val eq = body.indexOf('=')
			if (eq >= 0) {
				out[body.substring(0, eq)] = body.substring(eq + 1)
				i++
			} else if (i + 1 < args.size && !args[i + 1].startsWith("--")) {
				out[body] = args[i + 1]
				i += 2
			} else {
				// Bandera suelta, como `--no-open`.
				out[body] = "true"
				i++
			}
		}
		return out
	}
}
