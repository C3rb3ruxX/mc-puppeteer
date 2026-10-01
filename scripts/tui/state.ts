/**
 * state.ts — el estado compartido y el feed.
 *
 * Vive aparte para que ningun modulo tenga que importar al que dibuja: los
 * modulos de arriba (ordenes, tabla, inventario) dependen solo de este fichero.
 * Los ganchos hacia el bucle principal se rellenan al arrancar, para que el
 * panel no tenga que conocer a quien lo arranca.
 */

import process from 'node:process'
import { c, paint } from './ansi.ts'
import type { Options, Target } from './types.ts'

export const options: Options = {
	registry: null,
	chests: null,
	scanFrom: 25580,
	scanTo: 25589,
	interval: 2,
	token: null,
	host: '127.0.0.1',
	dir: 'run-instances',
	command: null,
	once: false,
	noColor: false,
	help: false,
}

/** Lo que hay en pantalla ahora mismo. */
export const state = {
	targets: [] as Target[],
	input: '',
	scroll: 0,
	/**
 * Indice de la instancia cuyo inventario se mira en el panel de inventario, o
 * `null` para la vista global: la suma del inventario de todas. Es el
 * defecto, porque lo que se quiere ver de un vistazo es cuanto hay entre
 * todos; `focus` sirve para cuando hace falta mirar uno en concreto.
 */
	focus: null as number | null,
}

/**
 * Ganchos que el bucle principal registra al arrancar. Asi el panel puede
 * redibujar, salir o forzar un refresco sin importar el arranque (y al reves).
 */
export const hooks: {
	redraw: () => void
	quit: () => void
	tick: () => Promise<void>
	restartTicker: () => void
	rescan: () => Promise<number>
} = {
	redraw: () => {},
	quit: () => {},
	tick: async () => {},
	restartTicker: () => {},
	rescan: async () => 0,
}

export const setHooks = (next: Partial<typeof hooks>): void => {
	Object.assign(hooks, next)
}

// --------------------------------------------------------------------------- feed

let lines_: string[] = []
let echo = false
const FEED_MAX = 400

/** En modo script el feed se escribe en stdout; en el panel, a la vez. */
export const setEcho = (value: boolean): void => {
	echo = value
}

export const feed = (): readonly string[] => lines_

export const push = (line: string): void => {
	lines_.push(line)
	if (lines_.length > FEED_MAX) lines_.splice(0, lines_.length - FEED_MAX)
	if (echo) process.stdout.write(`${line}\n`)
}

export const clearFeed = (): void => {
	lines_ = []
}

export const warnLine = (line: string): void => push(`${paint('!', c.yellow)} ${line}`)
