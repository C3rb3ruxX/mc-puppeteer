/**
 * view.ts — el compositor del panel.
 *
 * Cuatro zonas, en dos columnas:
 *
 *     ┌ bots ───────────────────┬ chat ──────────────────┐
 *     │ una instancia por fila, │ el feed: todo el chat │
 *     │ dos lineas cada una     │ y las respuestas       │
 *     ├─────────────────────────┼───────────────────────┤
 *     │ inventario              │ > _                   │
 *     └─────────────────────────┴───────────────────────┘
 *
 * El reparto es 66% / 30% del ancho util y 60% / 40% del alto, asi que se
 * adapta a la terminal sin que haya que tocar nada. Los anchos de cada zona se
 * calculan aqui y solo aqui: `table.ts` e `inventory.ts` reciben el ancho que
 * les toca y pintan dentro. Con una terminal estrecha, la tabla va quitandose
 * columnas hasta que caben, empezando por las que se deducen de las demas.
 */

import process from 'node:process'
import { c, clock, paint, pad, wrap } from './ansi.ts'
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

/** Columnas del separador vertical: `│ ` + `│`. */
const GAP = 3

/**
 * Reparto del ancho util entre las dos columnas: 66% y 30%.
 *
 * Los dos numeros estan medidos sobre lo que queda sin el separador, que es lo
 * unico que se puede repartir: 66 + 30 no son 100 porque el 4% que falta es el
 * `GAP`. En cualquier terminal sale 2:1, asi que la de la derecha es la
 * estrecha por diseno.
 */
const W_LEFT = 0.66
const W_RIGHT = 0.3

/**
 * Reparto del alto util entre la zona de arriba y la de abajo: 60% y 40%.
 *
 * Se aplica a las dos columnas a la vez, asi que el chat y los bots terminan a
 * la misma altura y el inventario y la linea de ordenes tambien.
 */
const H_TOP = 0.6

/** Titulo de la zona de los bots. */
const titleBots = (): string => 'bots'

/**
 * El titulo del panel de inventario. Sin foco es la suma de todas, que es la
 * vista normal; con foco, se esta mirando una en concreto.
 */
const titleInventory = (focus: Target | null): string =>
	focus ? `inventario · ${focus.name}` : 'inventario · todos'

/** Cuantas lineas de feed caben arriba a la derecha, con scroll. */
const renderFeed = (width: number, height: number): string[] => {
	// El chat es ancho y la columna es estrecha: se parte en varias lineas en
	// vez de cortarlo, que dejaria el final del mensaje fuera.
	const todas = feedLines().flatMap(linea => wrap(linea, width))
	const room = Math.max(1, height)
	const from = Math.max(0, todas.length - room + state.scroll)
	const lines = todas.slice(from, Math.min(todas.length, from + room)).map(line => pad(line, width))
	while (lines.length < room) lines.push('')
	return lines
}

/**
 * El recordatorio de atajos de la zona de ordenes.
 *
 * Va entero, sin recortar: se parte solo en varias lineas si la zona es
 * estrecha, porque un recordatorio al que le falta el final no recuerda nada. La
 * lista completa esta en `agents/08-teclas.md`.
 */
const SHORTCUTS =
	'/say /cmd /baritone /disperse /store /chest /switch /storenow /connect /items /focus /every /sel /quit · @1,3 · 1-9 · Q'

/** Linea de la zona de ordenes: el prompt arriba y el recordatorio abajo. */
const renderPrompt = (width: number, height: number): string[] => {
	const lines: string[] = []
	const prompt = `${paint('>', c.green)} ${state.input}`
	// Con la linea vacia el prompt no esta al final del texto, asi que se
	// rellena: el cursor del terminal se queda donde toca en cualquier caso.
	lines.push(pad(prompt, width))
	// El recordatorio abajo del todo, y solo las ultimas lineas que caben.
	const pie = wrap(SHORTCUTS, width).map(line => pad(line, width)).slice(-Math.max(0, height - 1))
	for (let i = 1; i < height - pie.length; i++) lines.push('')
	lines.push(...pie)
	while (lines.length < height) lines.push('')
	return lines.slice(0, height)
}

/**
 * Reparte el alto util entre la zona de arriba y la de abajo, en lineas.
 *
 * `util` son las lineas que quedan entre los bordes, y devuelve cuantos se
 * lleva cada zona. Con pocas lineas manda la de arriba: los bots y el chat son
 * lo que hay que ver, el inventario y el prompt pueden esperar.
 */
const splitRows = (util: number): [number, number] => {
	if (util <= 3) return [Math.max(1, util - 1), Math.max(1, util - 1)]
	const top = Math.max(1, Math.round(util * H_TOP))
	return [top, Math.max(1, util - top)]
}

/** El panel entero, como texto con saltos de linea. */
export const render = (): string => {
	const rows = termRows()
	const cols = Math.max(40, Math.min(240, termCols()))
	const util = cols - GAP
	const leftW = Math.max(30, Math.round((util * W_LEFT) / (W_LEFT + W_RIGHT)))
	const rightW = Math.max(16, util - leftW)
	// El marco son la linea de arriba, el separador central y la de abajo.
	const [topH, bottomH] = splitRows(rows - 4)

	const targets = state.targets
	// `state.focus === null` es la vista global: el inventario suma todas.
	const focus =
		state.focus === null
			? null
			: (targets[Math.min(state.focus, Math.max(0, targets.length - 1))] ?? null)

	const alive = targets.filter(t => t.up).length
	const lines: string[] = [
		`${paint('mc-puppeteer', c.bold)} ${paint('·', c.dim)} ` +
			paint(`${targets.length} instancia(s), ${alive} viva(s) · cada ${options.interval}s · ${clock()}`, c.frame),
	]

	// ---- la barra de arriba: titulos de las cuatro zonas.
	lines.push(
		paint(
			`┌ ${pad(titleBots(), leftW - 2)} ┬ ${pad('chat', rightW - 2)} ┐`,
			c.frame,
		),
	)

	const bots = renderTable(targets, leftW - 2, topH, state.focus)
	const chat = renderFeed(rightW - 2, topH)
	for (let i = 0; i < topH; i++) {
		lines.push(
			`${paint('│', c.frame)} ${pad(bots[i] ?? '', leftW - 2)} ` +
				`${paint('│', c.frame)} ${pad(chat[i] ?? '', rightW - 2)} ${paint('│', c.frame)}`,
		)
	}

	// ---- el separador horizontal: pasa a ser el borde de las dos zonas de abajo.
	// Mismos caracteres que las filas: `leftW` rayas y `rightW` rayas, para que
	// las columnas de cada zona caigan justo encima de su raya.
	lines.push(paint(`├${'─'.repeat(leftW)}┼${'─'.repeat(rightW)}┤`, c.frame))

	const items = renderInventory(targets, focus, leftW - 2, bottomH)
	const prompt = renderPrompt(rightW - 2, bottomH)
	for (let i = 0; i < bottomH; i++) {
		lines.push(
			`${paint('│', c.frame)} ${pad(items[i] ?? '', leftW - 2)} ` +
				`${paint('│', c.frame)} ${pad(prompt[i] ?? '', rightW - 2)} ${paint('│', c.frame)}`,
		)
	}
	lines.push(paint(`└${'─'.repeat(leftW)}┴${'─'.repeat(rightW)}┘`, c.frame))
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
