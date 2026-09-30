package com.bonilla.puente

import com.bonilla.puente.http.HttpError

/**
 * Traduce peticiones estructuradas a comandos de Baritone.
 *
 * Baritone no tiene API HTTP: se controla con mensajes de chat que empiezan
 * por `#` (`#goto 1000 500`, `#mine diamond_ore`, ...). Esta clase construye
 * esos mensajes y valida la entrada antes, de modo que un cliente HTTP no
 * pueda inyectar texto arbitrario en un comando.
 *
 * Va en el source set `main` a proposito: no depende de Minecraft ni de
 * Baritone. Que Baritone este instalado es responsabilidad de quien llama, y
 * el resultado se ve en el chat, que el mod ya captura.
 *
 * ## De donde salen los nombres
 *
 * Los nombres y alias de [COMMANDS] estan extraidos de las firmas del codigo
 * fuente de Baritone (los `Command.java` de `baritone/command/defaults` y
 * `DefaultCommands.java`), no de su documentacion. Se decidio asi porque
 * varios nombres razonables resultaban ser inexistentes:
 *
 * - No hay `StopCommand` ni `ClearAreaCommand`: `stop` es alias de `cancel`,
 *   y `cleararea` es un subcomando de `sel`.
 * - No hay `ModifiedCommand`: `modified` es un `CommandAlias` de `set`.
 * - `schematica` esta comentado en `DefaultCommands` y **no** esta disponible.
 *
 * El punto de intercepcion tambien se verifico en el binario: el mixin
 * `MixinClientPlayNetHandler` de `baritone-api-fabric-1.20.0.jar` inyecta en
 * `ClientPacketListener.sendChat(String)` con `@At("HEAD")` y
 * `cancellable = true`. Esa es exactamente la llamada que hace
 * `ClientBridge.sendChat`, y por eso las rutas de baritone usan `sendChat` y no
 * `sendCommand`.
 *
 * La prueba de humo contrasta este registro contra
 * `agents/tests/BaritoneCommandSignatures.java`, asi que si Baritone renombra o
 * retira un comando se detecta al compilar en vez de fallar en juego.
 */
object BaritoneTranslator {

	/** Prefijo de chat de Baritone. Setting `prefixControl`, activo por defecto. */
	const val PREFIX = "#"

	/**
	 * Construye un comando con prefijo. Se usa en vez de interpolar `"$PREFIX"`
	 * pegado al nombre porque Kotlin leeria `$PREFIXgoto` como una variable
	 * llamada `PREFIXgoto`.
	 */
	private fun cmd(name: String): String = "$PREFIX$name"

	/**
	 * Registro de comandos, extraido de `DefaultCommands.createAll()`,
	 * `ExecutionControlCommands` y los subcomandos de `sel`.
	 *
	 * `requiresArgs` marca los que Baritone rechaza sin argumentos. Esta
	 * verificado contra `IArgConsumer.requireMin`/`requireMax`/`requireExactly`:
	 * `thisway` exige exactamente 1 (`requireExactly(1)`), mientras que `path`,
	 * `invert`, `blacklist`, `click` y `elytra` los aceptan con 0
	 * (`requireMax(0)`).
	 */
	data class Command(
		val names: List<String>,
		val aliases: List<String> = emptyList(),
		val requiresArgs: Boolean = false,
		val note: String = "",
	)

	val COMMANDS: List<Command> = listOf(
		Command(listOf("help"), listOf("?")),
		Command(listOf("set"), listOf("setting", "settings"), requiresArgs = true),
		Command(listOf("modified"), listOf("mod", "baritone", "modifiedsettings"),
			note = "CommandAlias -> set modified"),
		Command(listOf("reset"), note = "CommandAlias -> set reset"),
		Command(listOf("goal")),
		Command(listOf("goto")),
		Command(listOf("path")),
		Command(listOf("proc")),
		Command(listOf("eta")),
		Command(listOf("version")),
		Command(listOf("repack"), listOf("rescan")),
		Command(listOf("build"), requiresArgs = true),
		Command(listOf("litematica")),
		Command(listOf("come")),
		Command(listOf("axis"), listOf("highway")),
		Command(listOf("forcecancel")),
		Command(listOf("gc")),
		Command(listOf("invert")),
		Command(listOf("tunnel"), requiresArgs = true),
		Command(listOf("render")),
		Command(listOf("farm"), requiresArgs = true),
		Command(listOf("follow"), requiresArgs = true),
		Command(listOf("pickup"), requiresArgs = true),
		Command(listOf("explorefilter"), requiresArgs = true),
		Command(listOf("reloadall")),
		Command(listOf("saveall")),
		Command(listOf("explore"), requiresArgs = true,
			note = "admite cero: explora desde donde esta el jugador"),
		Command(listOf("blacklist")),
		Command(listOf("find"), requiresArgs = true),
		Command(listOf("mine"), requiresArgs = true),
		Command(listOf("click")),
		Command(listOf("surface"), listOf("top")),
		Command(listOf("thisway"), listOf("forward"), requiresArgs = true),
		Command(listOf("waypoints"), listOf("waypoint", "wp"), requiresArgs = true),
		Command(listOf("sethome"), note = "CommandAlias -> waypoints save home"),
		Command(listOf("home"), note = "CommandAlias -> waypoints goto home"),
		Command(listOf("sel"), listOf("selection", "s"), requiresArgs = true,
			note = "cleararea es subcomando suyo"),
		Command(listOf("elytra")),
		// ExecutionControlCommands, anadidos aparte por createAll()
		Command(listOf("pause"), listOf("p", "paws")),
		Command(listOf("resume"), listOf("r", "unpause", "unpaws")),
		Command(listOf("paused")),
		Command(listOf("cancel"), listOf("c", "stop")),
	)

	/** Comandos que Baritone acepta sin argumentos. */
	private val NO_ARG_COMMANDS: Set<String> =
		COMMANDS.filterNot { it.requiresArgs }.flatMap { it.names }.toSet()

	/**
	 * Comandos de solo lectura, los que no cambian nada. Se exponen por GET.
	 * `sel` se queda fuera porque fija la seleccion, y `set` porque reescribe
	 * configuracion. `waypoints` sin argumentos solo lista, asi que entra.
	 */
	private val READ_ONLY_COMMANDS: Set<String> =
		setOf("version", "proc", "eta", "modified", "paused", "gc", "help", "waypoints")

	/**
	 * Comandos que tocan cache o repintado. POST aunque no afecten al mundo.
	 */
	private val MAINTENANCE_COMMANDS: Set<String> =
		setOf("repack", "reloadall", "saveall", "render")

	/** Nombre canonico de un comando o alias, o null si no existe. */
	fun resolve(name: String): String? {
		val needle = name.trim().lowercase()
		COMMANDS.forEach { c ->
			if (c.names.any { it.equals(needle, ignoreCase = true) }) return c.names[0]
			if (c.aliases.any { it.equals(needle, ignoreCase = true) }) return c.names[0]
		}
		return null
	}

	/** Traduce un comando de solo lectura. `help` acepta un filtro opcional. */
	fun readOnly(name: String, query: String? = null): String {
		val canonical = requireKnown(name)
		if (canonical !in READ_ONLY_COMMANDS) {
			throw HttpError(
				400, "not_read_only",
				"'$name' no es de solo lectura; usa un endpoint POST para acciones",
			)
		}
		if (name.equals("help", ignoreCase = true) && !query.isNullOrBlank()) {
			return cmd(canonical) + " " + requireToken("query", query, TOKEN)
		}
		return cmd(canonical)
	}

	// ------------------------------------------------------------- acciones

	/**
	 * Baritone acepta `goto x y z`, `goto x z` y `goto y` (solo el eje, que
	 * busca un lugar con esa altura). Cualquier otra mezcla se rechaza en vez
	 * de dejar que Baritone la interprete de forma distinta a la esperada.
	 */
	fun goto(x: Int?, y: Int?, z: Int?): String = goalLike("goto", x, y, z)

	fun goal(x: Int?, y: Int?, z: Int?): String = goalLike("goal", x, y, z)

	private fun goalLike(command: String, x: Int?, y: Int?, z: Int?): String = when {
		x != null && y != null && z != null ->
			"${cmd(command)} ${requireCoord("x", x)} ${requireCoord("y", y)} ${requireCoord("z", z)}"
		x != null && y == null && z != null ->
			"${cmd(command)} ${requireCoord("x", x)} ${requireCoord("z", z)}"
		x == null && y != null && z == null ->
			"${cmd(command)} ${requireCoord("y", y)}"
		else -> throw HttpError(
			400, "invalid_goal",
			"Baritone admite 'x y z', 'x z' o solo 'y'; recibido (x=$x, y=$y, z=$z)",
		)
	}

	fun gotoBlock(block: String): String = cmd("goto") + " " + requireToken("block", block, BLOCK)

	fun find(block: String): String = cmd("find") + " " + requireToken("block", block, BLOCK)

	/**
	 * `#mine [<cantidad>] <bloque> [<bloque>...]`.
	 *
	 * La cantidad va **primera**, delante del bloque. Mandandola detras,
	 * Baritone contesta `Error at argument #2: Expected ...` y no mina nada
	 * (comprobado contra Baritone de 1.21.5). Sin cantidad mina todos los
	 * bloques que encuentre; con ella, solo esos. Acepta varios bloques a
	 * continuacion, pero por la API se manda uno solo.
	 */
	fun mine(block: String, amount: Int?): String {
		val cantidad = if (amount == null) "" else " ${requireRange("amount", amount, 1, MAX_MINE_AMOUNT)}"
		return cmd("mine") + cantidad + " " + requireToken("block", block, BLOCK)
	}

	fun build(file: String, origin: Triple<Int, Int, Int>?): String {
		val base = cmd("build") + " " + requireToken("file", file, SCHEMATIC)
		if (origin == null) return base
		return "$base ${requireCoord("x", origin.first)} ${requireCoord("y", origin.second)} ${requireCoord("z", origin.third)}"
	}

	fun follow(target: String): String =
		cmd("follow") + " " + requireToken("target", target, TARGET)

	/**
	 * `cancel` acepta alias `c` y `stop`; se emite el nombre canonico.
	 * `forcecancel` es un comando aparte, registrado junto a `cancel`.
	 */
	fun stop(force: Boolean): String = if (force) cmd("forcecancel") else cmd("cancel")

	/**
	 * `pause` y `resume` son los controles de ejecucion. `paused` consulta el
	 * estado sin cambiarlo, y va por GET.
	 */
	fun pause(): String = cmd("pause")

	fun resume(): String = cmd("resume")

	fun axis(y: Int?): String {
		if (y == null) {
			throw HttpError(400, "missing_field", "'axis' necesita la altura 'y' (alias 'highway')")
		}
		return cmd("axis") + " " + requireRange("y", y, MIN_AXIS_Y, MAX_AXIS_Y)
	}

	fun tunnel(height: Int, width: Int, length: Int): String {
		requireRange("height", height, 1, MAX_DIMENSION)
		requireRange("width", width, 1, MAX_DIMENSION)
		requireRange("length", length, 1, MAX_DIMENSION)
		return cmd("tunnel") + " $height $width $length"
	}

	/**
	 * `cleararea` no es un comando propio: es un subcomando de `sel`
	 * (`CLEARAREA("cleararea", "ca")`), y equivale a poner aire.
	 */
	fun cleararea(radius: Int): String {
		requireRange("radius", radius, 1, MAX_DIMENSION)
		return cmd("sel") + " cleararea $radius"
	}

	fun explore(x: Int?, z: Int?): String {
		// "explore x z" o "explore" a secas (explora desde donde esta el jugador).
		if (x == null && z == null) return cmd("explore")
		if (x == null || z == null) {
			throw HttpError(400, "invalid_field", "'explore' necesita x y z juntos, o ninguno")
		}
		return cmd("explore") + " ${requireCoord("x", x)} ${requireCoord("z", z)}"
	}

	/**
	 * `thisway` exige exactamente un argumento (`requireExactly(1)`), asi que no
	 * puede ir en el grupo de comandos sin argumento.
	 */
	fun thisway(distance: Int): String =
		cmd("thisway") + " " + requireRange("distance", distance, 1, MAX_THISWAY)

	/**
	 * Traduce un comando sin argumentos, validando contra el registro real.
	 * Rechaza los que Baritone exige con al menos uno, para no generar un
	 * comando que Baritone responderia con `CommandNotEnoughArgumentsException`.
	 */
	fun noArg(name: String): String {
		val canonical = requireKnown(name)
		if (canonical !in NO_ARG_COMMANDS) {
			throw HttpError(
				400, "requires_arguments",
				"'$name' necesita argumentos en Baritone; no se puede enviar vacio",
			)
		}
		return cmd(canonical)
	}

	fun maintenance(name: String): String {
		val canonical = requireKnown(name)
		if (canonical !in MAINTENANCE_COMMANDS) {
			throw HttpError(
				400, "not_maintenance",
				"'$name' no es un comando de mantenimiento de Baritone",
			)
		}
		return cmd(canonical)
	}

	private fun requireKnown(name: String): String = resolve(name)
		?: throw HttpError(404, "unknown_baritone_command", "Comando de Baritone desconocido: $name")

	// -------------------------------------------------------- validaciones

	private fun requireRange(name: String, value: Int?, min: Int, max: Int): Int {
		if (value == null) throw HttpError(400, "missing_field", "falta el campo obligatorio '$name'")
		if (value !in min..max) {
			throw HttpError(400, "invalid_field", "'$name' fuera de rango ($min-$max): $value")
		}
		return value
	}

	private fun requireCoord(name: String, value: Int): Int = requireRange(name, value, MIN_COORD, MAX_COORD)

	private fun requireToken(name: String, value: String, pattern: Regex): String {
		val trimmed = value.trim()
		if (trimmed.isEmpty()) throw HttpError(400, "empty_field", "'$name' no puede estar vacio")
		if (!pattern.matches(trimmed)) {
			// No se devuelve el valor recibido: seria un vector de reflexion
			// innecesario y el patron ya explica bastante.
			throw HttpError(400, "invalid_field", "'$name' no cumple el formato esperado: $PATTERN_HINT")
		}
		return trimmed
	}

	private const val PATTERN_HINT =
		"bloques con letras, digitos, '_', ':' o '.'; nombres de jugador; ficheros .schematic"

	// Nombres de bloque de Minecraft: minecraft:diamond_ore, stone, etc.
	private val BLOCK = Regex("[A-Za-z0-9_:.-]{1,64}")
	// Nombres de jugador de Minecraft: 3-16 caracteres, sin espacios.
	private val TARGET = Regex("[A-Za-z0-9_]{1,16}")
	// Ficheros de schematico: blah.schematic
	private val SCHEMATIC = Regex("[A-Za-z0-9_-]{1,64}\\.schematic")
	// Texto libre de la ayuda de Baritone: palabras, espacios y signos.
	private val TOKEN = Regex("[A-Za-z0-9_ -]{1,64}")

	private const val MIN_COORD = -30_000_000
	private const val MAX_COORD = 30_000_000
	private const val MAX_AXIS_Y = 320
	private const val MIN_AXIS_Y = -64
	private const val MAX_DIMENSION = 64
	private const val MAX_MINE_AMOUNT = 4096
	private const val MAX_THISWAY = 10_000
}
