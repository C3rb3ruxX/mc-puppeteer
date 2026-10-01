/**
 * tui.ts — panel de control por terminal para las instancias de mc-puppeteer.
 *
 *     bun   scripts/tui.ts
 *     node scripts/tui.ts
 *
 * Este fichero es solo el arranque: argumentos, bucle de refresco y apagado. La
 * logica esta partida en `scripts/tui/` para poder editarla por partes:
 *
 *     ansi.ts       colores, anchos visibles y barras
 *     types.ts      los tipos compartidos
 *     state.ts      estado, feed y ganchos hacia este arranque
 *     api.ts        llamadas HTTP al mod de cada instancia
 *     targets.ts    descubrimiento y resolucion de destinos (@1,3)
 *     poll.ts       lo que se pregunta en cada refresco
 *     table.ts      panel izquierdo: una fila por instancia
 *     inventory.ts  panel derecho: items de mas a menos
 *     view.ts       el compositor de los tres bloques
 *     commands.ts   las ordenes
 *     config.ts     `scripts/.tui-config.json`: cofres con nombre y modo switch
 *     store.ts      `store` y `storenow`: manda la orden y sigue el estado
 *     switch.ts     modo switch: al anochecer, cada bot a su cofre
 *     panel.ts      lo que pasa al escribir o pulsar
 *
 * Modo script (sin TUI, util en cron):
 *   node scripts/tui.ts --once --connect 1.2.3.4:25565
 *   node scripts/tui.ts --every 10 -c "say hola"
 *
 * Sin dependencias: solo `node:*` y `fetch`.
 */

import process from 'node:process'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { setColor } from './tui/ansi.ts'
import { HELP } from './tui/commands.ts'
import { handleKey, isTty, releaseInput, runOrder, startInput } from './tui/panel.ts'
import { pullChat, pullInventory, refresh } from './tui/poll.ts'
import { options, push, setEcho, setHooks, state } from './tui/state.ts'
import { aplicaAsignados, revisaNoche } from './tui/switch.ts'
import { discover, mergeTargets } from './tui/targets.ts'
import { draw, render } from './tui/view.ts'
import type { Options } from './tui/types.ts'

// --------------------------------------------------------------------------- args

const fail = (msg: string): never => {
	process.stderr.write(`${msg}\n`)
	process.exit(1)
}

const parseArgs = (argv: string[]): Options => {
	const need = (i: number, flag: string): string => {
		const value = argv[i + 1]
		if (value === undefined) fail(`${flag} necesita un valor`)
		return value
	}
	for (let i = 0; i < argv.length; i++) {
		const arg = argv[i]!
		switch (arg) {
			case '--registry':
				options.registry = need(i, arg)
				i++
				break
			case '--chests':
				options.chests = need(i, arg)
				i++
				break
			case '--scan-from':
				options.scanFrom = Number(need(i, arg))
				i++
				break
			case '--scan-to':
				options.scanTo = Number(need(i, arg))
				i++
				break
			case '-e':
			case '--every':
			case '--interval':
				options.interval = Number(need(i, arg))
				i++
				break
			case '--token':
				options.token = need(i, arg)
				i++
				break
			case '--host':
				options.host = need(i, arg)
				i++
				break
			case '--dir':
				options.dir = need(i, arg)
				i++
				break
			case '-c':
			case '--command':
				options.command = need(i, arg)
				i++
				break
			case '--once':
				options.once = true
				break
			case '--no-color':
				options.noColor = true
				break
			case '-h':
			case '--help':
				options.help = true
				break
			default:
				fail(`Opcion desconocida: ${arg} (--help para la lista)`)
		}
	}
	if (!Number.isInteger(options.interval) || options.interval < 1) fail('--every debe ser >= 1')
	if (options.scanTo < options.scanFrom) fail('--scan-to no puede ser menor que --scan-from')
	return options
}

// --------------------------------------------------------------------------- bucle

let ticker: ReturnType<typeof setInterval> | null = null
let renderer: ReturnType<typeof setInterval> | null = null
let painting = false
let quitting = false
let projectDir = ''
/** Con `/every 1` y un cliente atascado, dos refrescos pueden solaparse. */
let ticking = false
/** Orden que se repite cada refresco en modo script (`--every` sin `--once`). */
let repeatCommand: string | null = null

const tick = async (): Promise<void> => {
	if (quitting || ticking) return
	ticking = true
	try {
		await refresh(state.targets)
		await pullChat(state.targets)
		await pullInventory(state.targets)
		// Al final, y solo con el estado ya al dia: la decision de "ha anochecido"
		// se toma con la `dayTime` de este mismo refresco (reducida a la hora del
		// dia dentro de `revisaNoche`). Va sin `await` porque suelta los `store`
		// en segundo plano y no tiene nada que esperar.
		revisaNoche(state.targets)
	} finally {
		ticking = false
	}
}

const restartTicker = (): void => {
	if (ticker) clearInterval(ticker)
	ticker = setInterval(() => {
		void tick().then(() => {
			if (repeatCommand) void runOrder(repeatCommand)
		})
	}, options.interval * 1000)
}

const paintLoop = (): void => {
	if (!isTty() || painting || quitting) return
	painting = true
	draw(render())
	painting = false
}

const quit = (): void => {
	if (quitting) return
	quitting = true
	if (ticker) clearInterval(ticker)
	if (renderer) clearInterval(renderer)
	releaseInput()
	if (isTty()) process.stdout.write('\x1b[?1049l\x1b[?25h')
	// Nada de process.exit: al vaciarse el bucle de eventos Node descarga stdout
	// y no se pierde ni la ultima linea cuando la salida va a un fichero o a
	// una tuberia. Una peticion que se quedara colgada caduca sola (5 s).
	process.exitCode = 0
}

// --------------------------------------------------------------------------- main

const main = async (): Promise<void> => {
	parseArgs(process.argv.slice(2))
	setColor(!options.noColor)
	if (options.help) {
		console.log(HELP)
		return
	}
	projectDir = resolve(dirname(fileURLToPath(import.meta.url)), '..')
	process.on('SIGINT', quit)
	process.on('SIGTERM', quit)

	setHooks({
		redraw: paintLoop,
		quit,
		tick,
		restartTicker,
		rescan: async () => {
			const found = await discover(projectDir)
			mergeTargets(state.targets, found)
			return found.length
		},
	})

	const found = await discover(projectDir)
	// Los cofres del modo switch se aplican nada mas descubrir, para que un bot
	// con el switch puesto en la sesion anterior empiece a guardarse al llegar la
	// noche en vez de tener que reasignarse a mano.
	aplicaAsignados(found)
	state.targets = found
	// En modo script todo lo que se va apilando en el feed se escribe en
	// stdout, y eso incluye la linea de las instancias detectadas.
	setEcho(options.command !== null)
	push(
		found.length > 0
			? `instancias: ${found.map(t => `${t.name}:${t.port}`).join('  ')}`
			: 'instancias: ninguna (arranca run-instances.ts, o /scan)',
	)

	// ---- modo script: una orden y fuera, o repetida cada --every.
	if (options.command) {
		await tick()
		await runOrder(options.command)
		if (options.once) return
		repeatCommand = options.command
		restartTicker()
		return
	}

	// ---- sin terminal: comportamiento de `watch`.
	if (!isTty()) {
		await tick()
		console.log(render())
		renderer = setInterval(() => {
			void tick().then(() => console.log(render()))
		}, options.interval * 1000)
		return
	}

	// ---- panel interactivo.
	process.stdout.write('\x1b[?1049h\x1b[?25l') // pantalla alternativa, sin cursor
	push('escribe /help para la lista de ordenes')
	await tick()

	startInput(key => {
		if (!handleKey(key)) {
			quit()
			return
		}
		paintLoop()
	})

	restartTicker()
	renderer = setInterval(paintLoop, 500)
	paintLoop()
}

void main().catch(error => {
	process.stderr.write(`${error instanceof Error ? (error.stack ?? error.message) : String(error)}\n`)
	process.exit(1)
})
