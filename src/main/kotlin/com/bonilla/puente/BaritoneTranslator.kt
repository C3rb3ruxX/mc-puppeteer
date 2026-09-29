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
 */
object BaritoneTranslator {

	/** Prefijo de chat de Baritone. Setting `prefixControl`, activo por defecto. */
	const val PREFIX = "#"

	/**
	 * Comandos que solo consultan informacion. No cambian el estado, asi que se
	 * exponen por GET.
	 */
	val READ_ONLY_COMMANDS: Map<String, String> = linkedMapOf(
		"version" to "version",
		"proc" to "proc",
		"eta" to "eta",
		"modified" to "modified",
		"wp" to "wp",
		"help" to "help",
		"gc" to "gc",
	)

	/**
	 * Comandos de mantenimiento. Tuercen la cache o fuerzan un repintado, asi
	 * que van por POST aunque no toquen el mundo.
	 */
	val MAINTENANCE_COMMANDS: Set<String> = setOf(
		"repack", "reloadall", "saveall", "render",
	)

	/** Traduce un comando de solo lectura. `help` acepta un filtro opcional. */
	fun readOnly(name: String, query: String? = null): String {
		val base = READ_ONLY_COMMANDS[name]
			?: throw HttpError(404, "unknown_baritone_command", "Comando de Baritone desconocido: $name")

		if (name == "help" && !query.isNullOrBlank()) {
			return "$PREFIX$base ${requireToken("query", query, TOKEN)}"
		}
		return "$PREFIX$base"
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
			"$PREFIX$command ${requireCoord("x", x)} ${requireCoord("y", y)} ${requireCoord("z", z)}"
		x != null && y == null && z != null ->
			"$PREFIX$command ${requireCoord("x", x)} ${requireCoord("z", z)}"
		x == null && y != null && z == null ->
			"$PREFIX$command ${requireCoord("y", y)}"
		else -> throw HttpError(
			400, "invalid_goal",
			"Baritone admite 'x y z', 'x z' o solo 'y'; recibido (x=$x, y=$y, z=$z)",
		)
	}

	fun gotoBlock(block: String): String = "${PREFIX}goto ${requireToken("block", block, BLOCK)}"

	fun find(block: String): String = "${PREFIX}find ${requireToken("block", block, BLOCK)}"

	fun mine(block: String, amount: Int?): String {
		// `amount` es opcional: sin el, Baritone mina hasta que se agote el
		// bloque. Si viene, se valida el rango.
		val base = "${PREFIX}mine ${requireToken("block", block, BLOCK)}"
		return if (amount == null) base else "$base ${requireRange("amount", amount, 1, MAX_MINE_AMOUNT)}"
	}

	fun build(file: String, origin: Triple<Int, Int, Int>?): String {
		val base = "${PREFIX}build ${requireToken("file", file, SCHEMATIC)}"
		if (origin == null) return base
		return "$base ${requireCoord("x", origin.first)} ${requireCoord("y", origin.second)} ${requireCoord("z", origin.third)}"
	}

	fun follow(target: String): String =
		"${PREFIX}follow ${requireToken("target", target, TARGET)}"

	fun stop(force: Boolean): String = if (force) "${PREFIX}forcecancel" else "${PREFIX}stop"

	fun axis(y: Int?): String {
		requireRange("y", y, MIN_AXIS_Y, MAX_AXIS_Y)
		return if (y == null) "${PREFIX}axis" else "${PREFIX}axis $y"
	}

	fun tunnel(height: Int, width: Int, length: Int): String {
		requireRange("height", height, 1, MAX_DIMENSION)
		requireRange("width", width, 1, MAX_DIMENSION)
		requireRange("length", length, 1, MAX_DIMENSION)
		return "${PREFIX}tunnel $height $width $length"
	}

	fun cleararea(radius: Int): String {
		requireRange("radius", radius, 1, MAX_DIMENSION)
		return "${PREFIX}cleararea $radius"
	}

	fun explore(x: Int?, z: Int?): String {
		// "explore x z" o "explore" a secas (explora desde donde esta el jugador).
		if (x == null && z == null) return "${PREFIX}explore"
		if (x == null || z == null) {
			throw HttpError(400, "invalid_field", "'explore' necesita x y z juntos, o ninguno")
		}
		return "${PREFIX}explore ${requireCoord("x", x)} ${requireCoord("z", z)}"
	}



	fun noArg(name: String): String {
		val allowed = setOf("surface", "top", "invert", "come", "blacklist", "elytra", "farm", "cancel", "path", "thisway")
		if (name !in allowed) {
			throw HttpError(404, "unknown_baritone_command", "Comando de Baritone desconocido: $name")
		}
		return "$PREFIX$name"
	}

	fun maintenance(name: String): String {
		if (name !in MAINTENANCE_COMMANDS) {
			throw HttpError(404, "unknown_baritone_command", "Comando de mantenimiento desconocido: $name")
		}
		return "$PREFIX$name"
	}

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
}
