/**
 * view.ts — el compositor del panel.
 *
 * Las tres zonas que se ven, de arriba abajo:
 *
 *     ┌ instancias ─────────────┬ inventario · mc1 ───────────┐
 *     │ una fila por instancia  │ items de mas a menos con   │
 *     │                         │ barra                      │
 *     └─────────────────────────┴────────────────────────────┘
 *       feed (todo el chat y las respuestas), con scroll
 *     ─────────────────────────────────────────────────────────
 *     > _
 *     /say /cmd ... · 1-9 · Q
 *
 * Los anchos se reparten aqui y solo aqui: `table.ts` e `inventory.ts` reciben
 * el ancho que les toca y pintan dentro. Si la terminal es estrecha para las
 * dos zonas, el inventario desaparece y la tabla se queda con todo el ancho.
 */

import process from 'node:process'
import { c, clock, paint, pad } from './ansi.ts'
import { renderInventory } from './inventory.ts'
import { options, state, feed as feedLines } from './state.ts'
import { renderTable } from './table.ts'
import type { Target } from './types.ts'

/**
 * Filas y columnas del terminal. Algunos pty (los de `script`, algunos CI)
 * las dan como 0: se trata como "desconocido" y se dibuja como 24x100.
 */
export const termRows = (): number => (process.stdout.rows > 2 ? process.stdout.rows : 24)
export const termCols = (): number => (process.stdout.columns > 20 ? process.stdout.columns : 100)

/** Anchos minimos: la tabla basica y un panel de inventario util. */
const LEFT_MIN = 76
const RIGHT_MIN = 32
const GAP = 3
/** Parte del ancho que se lleva la tabla; el resto es el inventario. */
const LEFT_SHARE = 0.7

/**
 * Reparte el ancho en dos zonas, o devuelve `null` si no caben las dos y es
 * mejor quedarse solo con la tabla. La tabla se lleva la parte grande porque
 * sus columnas (vida, comida, posicion) son las que antes se recortan.
 */
const splitPanes = (cols: number): [number, number] | null => {
	if (cols - GAP < LEFT_MIN + RIGHT_MIN) return null
	const left = Math.max(LEFT_MIN, Math.min(cols - RIGHT_MIN - GAP, Math.round(cols * LEFT_SHARE)))
	return [left, cols - left - GAP]
}

/** El titulo de la zona de instancias. */
const titleTable = (): string => 'instancias'

/**
 * El titulo del panel derecho. Sin foco es la suma de todas, que es la vista
 * normal; con foco, se esta mirando una en concreto.
 */
const titleInventory = (focus: Target | null): string =>
	focus ? `inventario · ${focus.name}` : 'inventario · todos'

/**
 * Cuantas filas de contenido caben arriba. El minimo de 8 es para que la lista
 * de items se vea aunque haya pocas instancias: es la zona que mas se usa.
 */
const contentRows = (rows: number, targets: Target[]): number =>
	Math.max(4, Math.min(Math.max(8, targets.length + 1), 14, rows - 10))

/** El feed: las ultimas lineas que quepan, respetando el scroll. */
const renderFeed = (width: number, height: number): string[] => {
	const all = feedLines()
	const room = Math.max(1, height)
	const from = Math.max(0, all.length - room + state.scroll)
	const shown = all.slice(from, Math.min(all.length, from + room))
	const lines = shown.map(line => pad(line, width))
	while (lines.length < room) lines.push('')
	return lines
}

/** El recordatorio de atajos de la ultima linea. */
const SHORTCUTS =
	'/say /cmd /baritone /disperse /store /storenow /connect /items /focus /every /sel /quit · @1,3 · 1-9 · Q'

/** El panel entero, como texto con saltos de linea. */
export const render = (): string => {
	const rows = termRows()
	const cols = Math.max(60, Math.min(200, termCols()))
	const panes = splitPanes(cols)

	const targets = state.targets
	// `state.focus === null` es la vista global: el panel derecho suma todas.
	const focus =
		state.focus === null
			? null
			: (targets[Math.min(state.focus, Math.max(0, targets.length - 1))] ?? null)
	const content = contentRows(rows, targets)

	const alive = targets.filter(t => t.up).length
	const lines: string[] = [
		`${paint('mc-puppeteer', c.bold)} ${paint('·', c.dim)} ` +
			paint(`${targets.length} instancia(s), ${alive} viva(s) · cada ${options.interval}s · ${clock()}`, c.grey),
	]

	// ---- zona de arriba: tabla a la izquierda, inventario a la derecha.
	if (panes) {
		const [leftW, rightW] = panes
		const head =
			`┌ ${pad(titleTable(), leftW - 2)} ` +
			`┬ ${pad(titleInventory(focus), rightW - 2)} ┐`
		lines.push(paint(head, c.frame))

		const left = renderTable(targets, leftW - 2, content, state.focus)
		const right = renderInventory(targets, focus, rightW - 2, content)
		for (let i = 0; i < content; i++) {
			lines.push(
				`${paint('│', c.frame)} ${pad(left[i] ?? '', leftW - 2)} ` +
					`${paint('│', c.frame)} ${pad(right[i] ?? '', rightW - 2)} ${paint('│', c.frame)}`,
			)
		}
		lines.push(
			paint(`└${'─'.repeat(leftW - 1)}┴${'─'.repeat(rightW - 1)}┘`, c.frame),
		)
	} else {
		lines.push(paint(`┌ ${pad(titleTable(), cols - 4)} ┐`, c.frame))
		for (const line of renderTable(targets, cols - 4, content, state.focus)) {
			lines.push(`${paint('│', c.frame)} ${pad(line, cols - 4)} ${paint('│', c.frame)}`)
		}
		lines.push(paint(`└${'─'.repeat(cols - 2)}┘`, c.frame))
	}

	// ---- zona del medio: el feed.
	const feedHeight = Math.max(2, rows - lines.length - 4)
	lines.push(paint('─'.repeat(cols), c.frame))
	for (const line of renderFeed(cols, feedHeight)) lines.push(line)
	lines.push(paint('─'.repeat(cols), c.frame))

	// ---- zona de abajo: la linea de ordenes y el recordatorio.
	lines.push(`${paint('>', c.green)} ${state.input}`)
	lines.push(paint(SHORTCUTS, c.dim))
	return lines.slice(0, rows).join('\n')
}

/** Pinta el panel entero en el sitio, sin limpiar la pantalla de golpe. */
export const draw = (text: string): void => {
	const rows = termRows()
	process.stdout.write('\x1b[H')
	process.stdout.write(
		text
			.split('\n')
			.slice(0, rows)
			.map(line => `\x1b[2K${line}`)
			.join('\n'),
	)
	process.stdout.write('\x1b[J')
}
