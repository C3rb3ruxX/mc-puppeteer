package com.bonilla.puente.dashboard

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Unica direccion a la que el hub se enlaza y a la que hace proxy.
 *
 * Vive a nivel de fichero (y no en el companion de [Instance]) porque se usa
 * como valor por defecto en el constructor, y los miembros del companion no
 * estan en alcance ahi.
 */
internal const val LOOPBACK = "127.0.0.1"

/** Hosts que el hub acepta. Cualquier otro se rechaza: ver [InstanceRegistry.validate]. */
internal val LOOPBACK_HOSTS = listOf("127.0.0.1", "::1", "localhost")

/**
 * Una instancia de Minecraft, identificada por el puerto donde escucha su
 * puente HTTP.
 *
 * No hay token. El hub y las instancias que lanza viven los dos en loopback, asi
 * que el puerto hace de identificador y el panel no pide ni guarda credenciales.
 */
data class Instance(
	val id: String,
	val name: String,
	val host: String = LOOPBACK,
	val port: Int,
) {
	/** El hub solo habla con loopback; ver [InstanceRegistry.validate]. */
	val isLoopback: Boolean get() = host in LOOPBACK_HOSTS

	fun toJson(): JsonObject = JsonObject().apply {
		addProperty("id", id)
		addProperty("name", name)
		addProperty("host", host)
		addProperty("port", port)
	}
}

/**
 * Registro de instancias, persistido en un JSON.
 *
 * Se escribe de forma atomica (fichero temporal + `move`) porque la pagina
 * puede estar guardando en ese momento y un JSON a medias dejaria al hub sin
 * configuracion al arrancar.
 */
class InstanceRegistry(registryFile: Path) {

	/**
	 * Se normaliza a absoluto porque un path relativo sin carpeta no tiene
	 * `parent`, y `Files.createDirectories(null)` revienta con NPE. Pasarlo tal
	 * cual solo falla al guardar, no al cargar, que es el peor momento para
	 * descubrirlo.
	 */
	private val file: Path = registryFile.toAbsolutePath()

	private val gson = GsonBuilder().setPrettyPrinting().create()

	// Un unico lock: las escrituras vienen de la pagina y las lecturas del
	// listado, y no compensa mas complicatez para un fichero de este tamano.
	private val lock = Any()

	@Volatile
	private var instances: List<Instance> = emptyList()

	fun load(): List<Instance> {
		val loaded = if (!Files.exists(file)) {
			// Sin configuracion previa se ofrece la instancia que corresponde al
			// puerto por defecto de Puente, para que la pagina no salga vacia.
			listOf(Instance(id = "p25580", name = "Instancia 1", port = 25580))
		} else {
			try {
				val root = JsonParser.parseString(Files.readString(file))
				if (!root.isJsonObject) {
					error("la raiz debe ser un objeto JSON")
				}
				root.asJsonObject.getAsJsonArray("instances").map { parse(it.asJsonObject) }
			} catch (e: Exception) {
				// Un registro ilegible no debe impedir arrancar: se avisa y se
				// sigue con la instancia por defecto para que la pagina siga
				// siendo utilizable y el usuario pueda arreglarlo desde la UI.
				System.err.println("[dashboard] registro ilegible ($file): ${e.message}")
				listOf(Instance(id = "p25580", name = "Instancia 1", port = 25580))
			}
		}

		synchronized(lock) { instances = loaded }
		// Se materializa aunque venga de la lista por defecto, para que exista
		// un fichero que editar.
		if (!Files.exists(file)) save(loaded)
		return loaded
	}

	fun all(): List<Instance> = instances

	fun find(id: String): Instance? = instances.firstOrNull { it.id == id }

	/** Anade o reemplaza por id. Devuelve la instancia resultante. */
	fun upsert(name: String, host: String, port: Int): Instance {
		val problems = validate(name, host, port)
		if (problems.isNotEmpty()) {
			throw BadRequest(problems.joinToString("; "), "invalid_instance")
		}

		val instance = Instance(
			id = "p$port",
			name = name.trim(),
			host = host.trim(),
			port = port,
		)

		synchronized(lock) {
			instances = (instances.filterNot { it.id == instance.id } + instance)
				.sortedBy { it.port }
			save(instances)
		}
		return instance
	}

	fun remove(id: String): Boolean = synchronized(lock) {
		val before = instances.size
		instances = instances.filterNot { it.id == id }
		if (instances.size != before) save(instances)
		instances.size != before
	}

	/**
	 * Un hub que reenvia peticiones no debe poder usarse para alcanzar la red
	 * interna: solo loopback, y con un puerto valido.
	 */
	fun validate(name: String, host: String, port: Int): List<String> {
		val problems = ArrayList<String>()
		if (name.isBlank()) problems += "el nombre no puede estar vacio"
		if (host !in LOOPBACK_HOSTS) {
			problems += "solo se permiten hosts de loopback (${LOOPBACK_HOSTS.joinToString(", ")}), no '$host'"
		}
		if (port !in 1..65535) problems += "puerto fuera de rango (1-65535): $port"
		return problems
	}

	private fun parse(o: JsonObject): Instance = Instance(
		id = o.get("id")?.asString ?: error("falta 'id'"),
		name = o.get("name")?.asString ?: error("falta 'name'"),
		host = o.get("host")?.asString ?: LOOPBACK,
		port = o.get("port")?.asInt ?: error("falta 'port'"),
	)

	private fun save(list: List<Instance>) {
		Files.createDirectories(file.parent)
		val root = JsonObject().apply {
			add("instances", JsonArray().also { array -> list.forEach { array.add(it.toJson()) } })
		}
		val tmp = file.resolveSibling("${file.fileName}.tmp")
		Files.writeString(tmp, gson.toJson(root))
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
	}
}

/** Error de entrada con el codigo que devuelve el hub. */
class BadRequest(message: String, val code: String = "invalid_request") : RuntimeException(message)
