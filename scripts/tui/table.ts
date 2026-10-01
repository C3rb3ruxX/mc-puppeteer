/**
 * table.ts — el panel de los bots: una instancia por fila, y cada fila son dos
 * lineas.
 *
 * Las dos lineas son para las barras: la de comida arriba y la de vida debajo,
 * en la misma columna, para poder leer las dos sin que la tabla crezca hacia los
 * lados. El compositor da el ancho (`view.ts`); aqui solo se elige que columnas
 * caben y se pinta.
 *
 * Las columnas se eligen segun el ancho: primero las basicas, y despues, si
 * sobra, mundo, jugadores, fps y latencia. Es una lista, asi que anadir una
 * columna nueva es una linea mas en `COLUMNS`; los anchos minimos de cada grupo
 * salen de esa misma lista, asi que no hay numeros sueltos que actualizar.
 */

import { bar, c, num, paint, pad, shortDim } from './ansi.ts'

/** Fila de la tabla: lo que se pinta de una instancia. */
type Row = {
	name: string
	port: number
	up: boolean
	selected: boolean
	status: Record<string, unknown> | null
	latencyMs: number | null
}

/** Caracteres de las barras de comida y vida. */
const BAR_SIZE = 20
/** La comida va en amarillo fijo; la vida por nivel, para que se distinguan. */
const TONO_COMIDA = c.yellow

/** Como se ve la pantalla del juego en una sola palabra. */
const screenOf = (status: Record<string, unknown> | null): string => {
	if (!status) return ''
	if (status.inWorld === true) return String(status.serverName ?? status.serverAddress ?? 'en juego')
	return String(status.screen ?? '')
}

/**
 * Los 4 ultimos caracteres del UUID.
 *
 * Del UUID entero (36 caracteres) no cabe ni un rastro en la tabla, pero los 4
 * ultimos si, y son los que distinguen a dos bots con el mismo nombre en
 * servidores distintos.
 */
const uuidCell = (status: Record<string, unknown>): string => {
	const uuid = String(status.playerUuid ?? '')
	return uuid.length >= 4 ? uuid.slice(-4) : '----'
}

/** `[x, y, z]`: las tres coordenadas enteras y con los corchetes. */
const posCell = (status: Record<string, unknown>): string => {
	if (status.x === undefined && status.z === undefined) return pad('[-, -, -]', POS_W)
	const x = Math.round(Number(status.x))
	const y = Math.round(Number(status.y))
	const z = Math.round(Number(status.z))
	return pad(`[${x}, ${y}, ${z}]`, POS_W)
}

/**
 * Una barra de `BAR_SIZE` con su numero a la derecha.
 *
 * El numero va fuera de la barra y no dentro, que es como se lee de un parte:
 * primero cuanto queda, luego de que.
 */
const gaugeCell = (value: unknown, max: unknown, tono?: string): string =>
	value === undefined || value === null
		? `${paint('-'.repeat(BAR_SIZE), c.dim)} ${'-'.padStart(4)}`
		: `${bar(value, max, BAR_SIZE, tono)} ${num(value, 4)}`

/**
 * Ancho de la columna de coordenadas.
 *
 * Se cuenta y no se pone a ojo: `[` + x + `, ` + y + `, ` + z + `]`, con cinco
 * caracteres para cada una por el signo y por las cifras. Las coordenadas no se
 * alinean dentro de los corchetes (quedaria `[    1,  -60,     2]`, que no lo
 * lee nadie) sino que se alinean por la derecha como el resto de celdas.
 */
const POS_X = 5
const POS_Y = 5
const POS_Z = 5
const POS_W = 1 + POS_X + 2 + POS_Y + 2 + POS_Z + 1

type Column = {
	title: string
	/** Titulo de la segunda linea; vacio si la columna no ocupa dos. */
	title2?: string
	width: number
	cell: (status: Record<string, unknown>, row: Row) => string
	/** Segunda linea de la celda; vacio si no la tiene. */
	cell2?: (status: Record<string, unknown>, row: Row) => string
}

/**
 * Las columnas y lo que ocupa cada una. El orden de prioridad es el de esta
 * lista: primero lo basico (instancia, puerto, uuid, estado, dimension,
 * coordenadas y las dos barras) y despues, segun el ancho que sobre, mundo,
 * jugadores, fps y latencia.
 */
const COLUMNS: Column[] = [
	{ title: 'inst', width: 8, cell: (_s, row) => row.name },
	{ title: 'puerto', width: 6, cell: (_s, row) => String(row.port) },
	{ title: 'uuid', width: 4, cell: s => uuidCell(s) },
	{ title: 'estado', width: 12, cell: (_s, row) => screenOf(row.status) },
	{ title: 'dim', width: 6, cell: (s, row) => (row.up ? shortDim(s.dimension) : '-') },
	{ title: 'pos', width: POS_W, cell: s => posCell(s) },
	{
		title: 'comida',
		title2: 'vida',
		width: BAR_SIZE + 5,
		cell: s => gaugeCell(s.food, 20, TONO_COMIDA),
		cell2: s => gaugeCell(s.health, s.maxHealth ?? 20),
	},
	{ title: 'mundo', width: 10, cell: s => String(s.worldName ?? '-') },
	{ title: 'jug', width: 7, cell: (s, row) => (row.up ? `${s.playerCount ?? 0}/${s.maxPlayers ?? '?'}` : '-') },
	{ title: 'fps', width: 5, cell: s => num(s.fps, 5) },
	{ title: 'ms', width: 5, cell: (_s, row) => num(row.latencyMs, 5) },
]

/** Prefijo de cada fila: `> ` + `●` + ` `; la segunda linea va sin el. */
const PREFIX = 4

/**
 * Hueco entre columnas.
 *
 * Sin el, dos celdas contiguas quedan pegadas (`puertouuidestado`) y hay que
 * contar los caracteres para saber donde acaba una y empieza otra. Uno solo
 * basta: los numeros ya van alineados dentro de su celda.
 */
const COL_GAP = 1

/** Junta celdas con `COL_GAP` entre medias. */
const cells = (parts: string[]): string => parts.join(' '.repeat(COL_GAP))

/** Ancho que ocupa una lista de columnas, con el prefijo y los huecos. */
const sumOf = (cols: Column[]): number =>
	PREFIX + cols.reduce((total, col) => total + col.width + COL_GAP, 0) - COL_GAP

/**
 * Ancho minimo (con prefijo) para meter cada grupo de columnas. Salen de la
 * propia lista, asi que anadir o ensanchar una columna no deja numeros sueltos
 * que se queden cortos.
 */
const BASICAS = sumOf(COLUMNS.slice(0, 7))
const CON_MUNDO = sumOf(COLUMNS.slice(0, 8))
const CON_JUG = sumOf(COLUMNS.slice(0, 9))
const CON_FPS = sumOf(COLUMNS.slice(0, 11))

/**
 * Columnas que se van primero cuando el ancho aprieta, en este orden.
 *
 * Son las que se deducen de las demas o de un vistazo: la dimension se lee en la
 * posicion, y el estado se ve en si el punto de la fila esta verde. Las barras no
 * son deducibles de nada, asi que son las ultimas en irse.
 */
const DEDUCIBLES = ['dim', 'estado', 'pos']

/**
 * Que columnas caben en `width`.
 *
 * Se eligen de la lista por orden de prioridad y, si aun asi no caben (una
 * terminal muy estrecha), se van quitando: primero las de `DEDUCIBLES` y luego
 * las de la derecha. Quitar columnas deja huecos coherentes; dejarlas
 * desbordadas las cortaria por la mitad con un `…` en mitad de un numero, que es
 * peor que no ver esa columna.
 */
export const visibleColumns = (width: number): Column[] => {
	const cols = COLUMNS.slice(0, 7)
	if (width >= CON_MUNDO) cols.push(COLUMNS[7]!)
	if (width >= CON_JUG) cols.push(COLUMNS[8]!)
	if (width >= CON_FPS) cols.push(COLUMNS[9]!, COLUMNS[10]!)
	while (cols.length > 1 && sumOf(cols) > width) {
		const deducible = cols.findIndex(col => DEDUCIBLES.includes(col.title))
		cols.splice(deducible > 0 ? deducible : cols.length - 1, 1)
	}
	return cols
}

/**
 * Las lineas de la tabla, ya pintadas y con la anchura del panel.
 *
 * Cada instancia ocupa dos lineas: la primera con sus datos y la barra de
 * comida, la segunda solo con la de vida, alineada debajo. `focus` es el indice
 * de la instancia cuyo inventario se mira, o `null` cuando el inventario esta en
 * vista global y no hay ninguna en concreto.
 */
export const renderTable = (
	targets: Row[],
	width: number,
	height: number,
	focus: number | null,
): string[] => {
	const cols = visibleColumns(width)
	// Dos lineas de cabecera, para que se sepa cual de las dos barras es cual.
	const head = paint(
		' '.repeat(PREFIX) + cells(cols.map(col => pad(col.title, col.width))),
		c.bold,
	)
	const head2 = paint(
		' '.repeat(PREFIX) + cells(cols.map(col => pad(col.title2 ?? '', col.width))),
		c.bold,
	)

	const lines: string[] = [head, head2]
	// Cada instancia son dos lineas, y el "y N mas" una mas.
	const room = Math.max(0, Math.floor((height - 3) / 2))
	const shown = targets.slice(0, room)

	for (const [i, row] of shown.entries()) {
		const status = row.status ?? {}
		const nombre = paint(pad(row.name, 8), row.selected ? c.blue : c.dim)
		const primera = cells(
			cols.map(col => (col.title === 'inst' ? nombre : pad(col.cell(status, row), col.width))),
		)
		const segunda = cells(
			cols.map(col => pad(col.cell2 ? col.cell2(status, row) : '', col.width)),
		)
		// Roja si no responde, amarilla si vive pero el estado aun no ha llegado.
		const tinta = !row.up ? c.red : row.status === null ? c.yellow : null
		const prefijo = `${i === focus ? paint('>', c.yellow) : ' '} ${row.up ? paint('●', c.green) : paint('○', c.red)} `
		lines.push(tinta ? paint(`${prefijo}${primera}`, tinta) : `${prefijo}${primera}`)
		lines.push(' '.repeat(PREFIX) + segunda)
	}

	if (targets.length > shown.length) {
		lines.push(paint(`  ... y ${targets.length - shown.length} mas`, c.dim))
	}
	while (lines.length < height) lines.push('')
	return lines.slice(0, height).map(line => pad(line, width))
}
