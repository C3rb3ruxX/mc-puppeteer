/**
 * table.ts — el panel de la izquierda: una fila por instancia.
 *
 * Las columnas se eligen segun el ancho que le deja el compositor: en una
 * terminal estrecha se ven las basicas enteras, y vida/comida/posicion solo
 * cuando hay sitio de sobra (ver `view.ts`).
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

/** Como se ve la pantalla del juego en una sola palabra. */
const screenOf = (status: Record<string, unknown> | null): string => {
	if (!status) return ''
	if (status.inWorld === true) return String(status.serverName ?? status.serverAddress ?? 'en juego')
	return String(status.screen ?? '')
}

/** `x y z` con las tres coordenadas enteras. */
const posCell = (status: Record<string, unknown>): string => {
	if (status.x === undefined && status.z === undefined) return '-'.padStart(17)
	return `${num(Math.round(Number(status.x)), 6)} ${num(Math.round(Number(status.y)), 4)} ` +
		num(Math.round(Number(status.z)), 6)
}

/** Barra + numero de vida o comida (11 caracteres). */
const gaugeCell = (value: unknown, max: unknown): string =>
	value === undefined || value === null
		? paint('-'.padStart(11), c.dim)
		: `${bar(value, max)} ${num(value, 4)}`

type Column = { title: string; width: number; cell: (status: Record<string, unknown>, row: Row) => string }

/**
 * Las columnas y lo que ocupa cada una. El orden de prioridad es el de esta
 * lista: primero lo basico, y despues, segun el ancho que sobre, vida y comida,
 * posicion, y por ultimo fps y latencia (lo primero que se cae si no cabe).
 */
const COLUMNS: Column[] = [
	{ title: 'inst', width: 8, cell: (_s, row) => row.name },
	{ title: 'puerto', width: 7, cell: (_s, row) => String(row.port) },
	{ title: 'estado', width: 14, cell: (_s, row) => screenOf(row.status) },
	{ title: 'mundo', width: 10, cell: s => String(s.worldName ?? '-') },
	{ title: 'dim', width: 7, cell: (s, row) => (row.up ? shortDim(s.dimension) : '-') },
	{ title: 'jug', width: 7, cell: (s, row) => (row.up ? `${s.playerCount ?? 0}/${s.maxPlayers ?? '?'}` : '-') },
	{ title: 'vida', width: 12, cell: s => gaugeCell(s.health, s.maxHealth ?? 20) },
	{ title: 'comida', width: 12, cell: s => gaugeCell(s.food, 20) },
	{ title: 'pos', width: 18, cell: s => posCell(s) },
	{ title: 'fps', width: 5, cell: s => num(s.fps, 5) },
	{ title: 'ms', width: 5, cell: (_s, row) => num(row.latencyMs, 5) },
]

/** Ancho minimo que hace falta para meter cada grupo de columnas. */
const BASIC = 53
const NEEDS_GAUGES = BASIC + 24
const NEEDS_POS = NEEDS_GAUGES + 18
const NEEDS_LATENCY = NEEDS_POS + 10

/** Ancho que ocupa una lista de columnas. */
const sumOf = (cols: Column[]): number => cols.reduce((total, col) => total + col.width, 0)

/**
 * Que columnas caben en `width` (ya descontado el prefijo de la fila).
 *
 * Se eligen de la lista por orden de prioridad y, si aun asi no caben (una
 * terminal muy estrecha), se van quitando por la derecha. Quitar columnas
 * deja huecos coherentes; dejarlas overflowing las cortaria por la mitad con un
 * `…` en mitad de un numero, que es peor que no ver esa columna.
 */
export const visibleColumns = (width: number): Column[] => {
	const cols = COLUMNS.slice(0, 6)
	if (width >= NEEDS_GAUGES) cols.push(COLUMNS[6]!, COLUMNS[7]!)
	if (width >= NEEDS_POS) cols.push(COLUMNS[8]!)
	if (width >= NEEDS_LATENCY) cols.push(COLUMNS[9]!, COLUMNS[10]!)
	while (cols.length > 1 && sumOf(cols) > width) cols.pop()
	return cols
}

/** Prefijo de cada fila: `> ` + `●` + ` `. */
const PREFIX = 4

/**
 * Las filas de la tabla, ya pintadas y con la anchura del panel.
 * `focus` es el indice de la instancia que se mira al lado, o `null` cuando el
 * panel de la derecha esta en vista global y no hay ninguna en concreto.
 */
export const renderTable = (
	targets: Row[],
	width: number,
	height: number,
	focus: number | null,
): string[] => {
	const cols = visibleColumns(width - PREFIX)
	// El titulo tambien lleva el hueco del prefijo, para que cada columna de la
	// cabecera caiga encima de la suya.
	const head = paint(
		' '.repeat(PREFIX) + cols.map(col => pad(col.title, col.width)).join(''),
		c.bold,
	)
	const rows: string[] = [head]
	const room = Math.max(1, height - 1)
	const shown = targets.slice(0, room)

	for (const [i, row] of shown.entries()) {
		const status = row.status ?? {}
		const dot = row.up ? paint('●', c.green) : paint('○', c.red)
		const mark = i === focus ? paint('>', c.yellow) : ' '
		const name = paint(pad(row.name, 8), row.selected ? c.blue : c.dim)
		const cells = cols
			.map(col => (col.title === 'inst' ? name : pad(col.cell(status, row), col.width)))
			.join('')
		const line = `${mark} ${dot} ${cells}`
		// Roja si no responde, amarilla si vive pero el estado aun no ha llegado.
		rows.push(!row.up ? paint(line, c.red) : row.status === null ? paint(line, c.yellow) : line)
	}

	if (targets.length > shown.length) {
		rows.push(paint(`  ... y ${targets.length - shown.length} mas`, c.dim))
	}
	while (rows.length < height) rows.push('')
	return rows.slice(0, height).map(line => pad(line, width))
}
