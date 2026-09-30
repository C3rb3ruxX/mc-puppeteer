/**
 * panel.ts — lo que pasa cuando se escribe o se pulsa una tecla.
 *
 * Aqui esta el reparto entre "orden del mod" (COMMANDS) y "orden del panel"
 * (help, sel, scan, log...). El bucle principal solo llama a `runOrder` y a
 * `handleKey`, y no necesita saber nada mas.
 */

import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import process from 'node:process'
import { c, paint, stripAnsi } from './ansi.ts'
import { COMMANDS, HELP } from './commands.ts'
import { clearFeed, hooks, options, push, state, warnLine } from './state.ts'
import { resolveTargets, stripTarget } from './targets.ts'
import type { Key, Target } from './types.ts'

/** `/log 20 mc2` -> ultimas lineas del stdout de esa instancia. */
export const showLog = (args: string, targets: Target[]): void => {
	const [countRaw, name] = args.split(/\s+/)
	const count = Number(countRaw) > 0 ? Number(countRaw) : 15
	const target = name ? targets.find(t => t.name === name) : targets.find(t => t.dir)
	if (!target?.dir) {
		warnLine('no hay log conocido (hace falta el registro de run-instances.ts)')
		return
	}
	const path = join(target.dir, 'logs', 'stdout.log')
	if (!existsSync(path)) {
		warnLine(`no existe ${path}`)
		return
	}
	push(paint(`--- ${target.name} · ${path}`, c.dim))
	for (const line of readFileSync(path, 'utf8').split('\n').filter(Boolean).slice(-count)) {
		// El log del juego viene con los colores de la consola: se quitan para
		// que el feed no salga hecho un mosaico.
		push(`  ${stripAnsi(line).slice(0, 160)}`)
	}
}

/** Ejecuta una linea escrita por quien esta delante del panel. */
export const runOrder = async (raw: string): Promise<void> => {
	const line = raw.trim()
	if (!line) return

	push(`${paint('>', c.green)} ${line}`)
	// El destino (@1,3 / @mc2) va delante del verbo: se quita antes de
	// interpretar la orden, o `@all say hola` no encontraria ninguna.
	const bare = stripTarget(line.replace(/^\//, ''))
	const verb = (bare.split(/\s+/)[0] ?? '').toLowerCase()
	const args = bare.slice(verb.length).trim()
	const targets = resolveTargets(line.replace(/^\//, ''), state.targets)

	// Atajo: !texto = say a las seleccionadas.
	if (line.startsWith('!')) {
		await COMMANDS.say(line.slice(1).trim(), targets)
		hooks.redraw()
		return
	}

	switch (verb) {
		case 'help':
			for (const helpLine of HELP.trim().split('\n')) push(helpLine)
			break
		case 'quit':
		case 'exit':
		case 'q':
			hooks.quit()
			return
		case 'clear':
			clearFeed()
			break
		case 'every': {
			const seconds = Number(args)
			if (!Number.isFinite(seconds) || seconds < 1) warnLine('uso: /every <segundos>')
			else {
				options.interval = seconds
				hooks.restartTicker()
				push(`intervalo: ${seconds}s`)
			}
			break
		}
		case 'token':
			options.token = args || null
			for (const target of state.targets) target.token = options.token ?? ''
			push(options.token ? `token: ${options.token}` : 'token: ninguno')
			break
		case 'scan': {
			const found = await hooks.rescan()
			push(`${found} instancia(s) descubiertas`)
			break
		}
		case 'sel': {
			if (args === 'all') state.targets.forEach(t => (t.selected = true))
			else if (args === 'none') state.targets.forEach(t => (t.selected = false))
			else {
				for (const n of args.split(/[ ,]+/).filter(Boolean)) {
					const target = state.targets[Number(n) - 1]
					if (target) target.selected = !target.selected
				}
			}
			push(`seleccionadas: ${state.targets.filter(t => t.selected).map(t => t.name).join(', ') || 'ninguna'}`)
			break
		}
		case 'target':
			push(`destino @all @1,2 @mc2 · seleccionadas: ${state.targets.filter(t => t.selected).map(t => t.name).join(', ') || 'ninguna'}`)
			break
		case 'log':
			showLog(args, state.targets)
			break
		default: {
			const handler = COMMANDS[verb]
			if (!handler) {
				warnLine(`orden desconocida: ${verb} (/help)`)
				break
			}
			await handler(args, targets)
		}
	}
	hooks.redraw()
}

/**
 * Una pulsacion. Devuelve `false` cuando hay que salir, para que el bucle
 * principal no tenga que volver a mirar nada.
 */
export const handleKey = (key: Key): boolean => {
	const { name, char } = key
	switch (name) {
		case 'ctrl-c':
		case 'escape':
			return false
		case 'enter': {
			const typed = state.input
			state.input = ''
			void runOrder(typed)
			return true
		}
		case 'backspace':
			state.input = state.input.slice(0, -1)
			break
		case 'up':
			state.scroll++
			break
		case 'down':
			state.scroll = Math.max(0, state.scroll - 1)
			break
		case 'pageup':
			state.scroll += 10
			break
		case 'pagedown':
			state.scroll = Math.max(0, state.scroll - 10)
			break
		case 'char': {
			// Los atajos de una tecla solo funcionan con la linea vacia:
			// si no, estarian pisando el texto que se esta escribiendo.
			if (state.input === '') {
				if (char === 'Q') return false
				if (/^[1-9]$/.test(char)) {
					const target = state.targets[Number(char) - 1]
					if (target) {
						target.selected = !target.selected
						push(`${target.name} ${target.selected ? 'seleccionada' : 'deseleccionada'}`)
					}
					break
				}
				if (char === 'a') {
					state.targets.forEach(t => (t.selected = true))
					push('todas seleccionadas')
					break
				}
				if (char === 'n') {
					state.targets.forEach(t => (t.selected = false))
					push('ninguna seleccionada')
					break
				}
				if (char === 'r') {
					void hooks.tick()
					break
				}
				if (char === 'l') {
					showLog('15', state.targets)
					break
				}
				if (char === 'f') {
					void runOrder('focus next')
					break
				}
			}
			state.input += char
			break
		}
		default:
			break
	}
	return true
}

type Stdin = {
	isTTY?: boolean
	setRawMode?: (mode: boolean) => void
	resume: () => void
	pause?: () => void
	setEncoding?: (encoding: string) => void
	on: (event: string, handler: (chunk?: string) => void) => void
}

export const isTty = (): boolean => Boolean((process.stdin as Stdin).isTTY)

/** Pone el terminal en modo raw y traduce las pulsaciones a nombres. */
export const startInput = (onKey: (key: Key) => void): void => {
	const stdin = process.stdin as Stdin
	if (stdin.isTTY) stdin.setRawMode?.(true)
	stdin.resume()
	stdin.setEncoding?.('utf8')

	let buffer = ''
	stdin.on('data', chunk => {
		buffer += chunk ?? ''
		while (buffer.length > 0) {
			if (buffer.startsWith('\x1b')) {
				// Secuencia de escape: se interpreta entera o se espera mas.
				const match = /^\x1b(\[[0-9;]*[A-Za-z~]|O[A-Za-z]|.)/.exec(buffer)
				if (!match) return
				buffer = buffer.slice(match[0].length)
				const sequence = match[1]
				const key =
					sequence === '[A' ? 'up'
					: sequence === '[B' ? 'down'
					: sequence === '[5~' ? 'pageup'
					: sequence === '[6~' ? 'pagedown'
					: 'escape'
				onKey({ name: key, char: '' })
				continue
			}
			const char = buffer[0]!
			buffer = buffer.slice(1)
			if (char === '\x03') onKey({ name: 'ctrl-c', char })
			else if (char === '\r' || char === '\n') onKey({ name: 'enter', char: '' })
			else if (char === '\x7f' || char === '\b') onKey({ name: 'backspace', char: '' })
			else if (char === '\t') onKey({ name: 'tab', char: '' })
			else if (char < ' ') onKey({ name: 'other', char })
			else onKey({ name: 'char', char })
		}
	})

	// Si el terminal se cierra (EOF) no hay a quien volver: se sale igual.
	stdin.on('end', () => onKey({ name: 'ctrl-c', char: '' }))
}

/** Devuelve el terminal a su estado normal (hay que hacerlo al salir). */
export const releaseInput = (): void => {
	const stdin = process.stdin as Stdin
	if (stdin.isTTY) stdin.setRawMode?.(false)
	stdin.pause?.()
}
