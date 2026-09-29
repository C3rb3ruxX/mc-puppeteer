/**
 * tui.ts — panel de control por terminal para las instancias de mc-puppeteer.
 *
 *     bun   scripts/tui.ts
 *     node scripts/tui.ts
 *
 * Que hace:
 *   - Descubre las instancias (leyendo el registro que escribe `run-instances.ts`,
 *     o barriendo un rango de puertos) y las lista con su estado en vivo:
 *     pantalla, mundo, FPS, jugadores, latencia y si responden.
 *   - Manda la misma orden a varias instancias a la vez: `say`, `cmd`,
 *     `baritone`, `connect`, `disconnect`, `respawn`, `profile`.
 *   - Junta en un solo feed el chat que llega de todas, sin vaciar buffers.
 *   - El intervalo se cambia en caliente con `/every 2`.
 *
 * Ejemplos:
 *   /all say hola a todos        (a las seleccionadas; por defecto, todas)
 *   @2,3 cmd list                (solo a mc2 y mc3)
 *   @mc1 connect 1.2.3.4:25565
 *   /connect 1.2.3.4:25565       (a todas)
 *   !hola rapido                 (= /all say hola)
 *
 * Modo script (sin TUI, util en cron):
 *   node scripts/tui.ts --once --connect 1.2.3.4:25565
 *   node scripts/tui.ts --every 10 -c "say hola"
 *
 * Sin dependencias: solo `node:*` y `fetch`.
 */

import { existsSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

// --------------------------------------------------------------------------- tipos

type Target = {
	name: string
	host: string
	port: number
	token: string
	dir: string
	selected: boolean
	/** Ultimo /status conocido. */
	status: Record<string, unknown> | null
	latencyMs: number | null
	up: boolean
	/** Marca del ultimo mensaje de chat ya pintado, para no repetir. */
	lastChat: number
}

type Options = {
	registry: string | null
	scanFrom: number
	scanTo: number
	interval: number
	token: string | null
	host: string
	dir: string
	command: string | null
	once: boolean
	noColor: boolean
	help: boolean
}

/** Una pulsacion ya interpretada. */
type Key = { name: string; char: string }

/** Cada orden del panel: recibe el resto del texto y las instancias destino. */
type Handler = (args: string, targets: Target[]) => Promise<number | void>

// --------------------------------------------------------------------------- opciones

const DEFAULTS: Options = {
	registry: null,
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

let options: Options = { ...DEFAULTS }

// --------------------------------------------------------------------------- salida

let color = true
const c = {
	reset: '\x1b[0m',
	bold: '\x1b[1m',
	dim: '\x1b[2m',
	red: '\x1b[31m',
	green: '\x1b[32m',
	yellow: '\x1b[33m',
	blue: '\x1b[36m',
	grey: '\x1b[90m',
}
const paint = (text: string, ...styles: string[]): string =>
	color ? `${styles.join('')}${text}${c.reset}` : text

/** El log del juego viene con los colores de la consola de Minecraft. */
const stripAnsi = (text: string): string => text.replace(/\x1b\[[0-9;]*[A-Za-z]/g, '')

const clock = (): string => new Date().toLocaleTimeString('es-ES', { hour12: false })

/** En modo script el feed se escribe en stdout; en el panel, a la vez. */
let echo = false

let feed: string[] = []
const FEED_MAX = 400

const push = (line: string): void => {
	feed.push(line)
	if (feed.length > FEED_MAX) feed.splice(0, feed.length - FEED_MAX)
	if (echo) process.stdout.write(`${line}\n`)
}
const warnLine = (line: string): void => push(`${paint('!', c.yellow)} ${line}`)

const fail = (msg: string): never => {
	process.stderr.write(`${msg}\n`)
	process.exit(1)
}

// --------------------------------------------------------------------------- args

const parseArgs = (argv: string[]): Options => {
	const opts: Options = { ...DEFAULTS }
	const need = (i: number, flag: string): string => {
		const value = argv[i + 1]
		if (value === undefined) fail(`${flag} necesita un valor`)
		return value
	}
	for (let i = 0; i < argv.length; i++) {
		const arg = argv[i]
		switch (arg) {
			case '--registry':
				opts.registry = need(i, arg)
				i++
				break
			case '--scan-from':
				opts.scanFrom = Number(need(i, arg))
				i++
				break
			case '--scan-to':
				opts.scanTo = Number(need(i, arg))
				i++
				break
			case '-e':
			case '--every':
			case '--interval':
				opts.interval = Number(need(i, arg))
				i++
				break
			case '--token':
				opts.token = need(i, arg)
				i++
				break
			case '--host':
				opts.host = need(i, arg)
				i++
				break
			case '--dir':
				opts.dir = need(i, arg)
				i++
				break
			case '-c':
			case '--command':
				opts.command = need(i, arg)
				i++
				break
			case '--once':
				opts.once = true
				break
			case '--no-color':
				opts.noColor = true
				break
			case '-h':
			case '--help':
				opts.help = true
				break
			default:
				fail(`Opcion desconocida: ${arg} (--help para la lista)`)
		}
	}
	if (!Number.isInteger(opts.interval) || opts.interval < 1) fail('--every debe ser >= 1')
	if (opts.scanTo < opts.scanFrom) fail('--scan-to no puede ser menor que --scan-from')
	return opts
}

const HELP = `
Panel de control de las instancias de mc-puppeteer.

  bun   scripts/tui.ts
  node scripts/tui.ts

Ordenes (con / delante o escribiendolas tal cual):
  say <texto>         Envia chat a las seleccionadas. (chat = say)
  cmd <comando>       Envia comando de servidor (sin la barra).
  baritone <orden>    Consulta a Baritone, u orden libre como #goto 1 2 3.
  connect <servidor>  Conecta a un servidor.
  disconnect          Sale al titulo.
  respawn             Reaparicion.
  profile <nombre>    Cambia la identidad offline.
  status              Fuerza la lectura del estado.
  players             Quien esta en el mundo.
  history [n]         Historial de chat (por defecto 15). No lo vacia.
  log <n> [mcN]       Ultimas lineas del log de una instancia.
  every <seg>         Cambia el intervalo de refresco.
  scan                Vuelve a descubrir instancias.
  token <t|clear>     Token Bearer de las peticiones.
  sel <n|all|none>    Selecciona instancias.
  target              A quien iran las ordenes.
  clear               Limpia el feed.
  help                Esto.
  quit                Sale.

Destino:  @all  @1,3  @mc2      (por defecto, las seleccionadas)
Atajo:    !texto = say a todas
Teclas:   con la linea vacia, 1-9 seleccionan, a todas, n ninguna,
          r refresca, Q sale, flechas para recorrer el feed.

Modo script (sin panel):
  node scripts/tui.ts --once --connect 1.2.3.4:25565
  node scripts/tui.ts --every 10 -c "say hola"

Opciones:
      --registry F    Registro de instancias (def. run-instances/.instances.json)
      --scan-from N   Primer puerto del barrido (def. 25580)
      --scan-to N     Ultimo puerto del barrido (def. 25589)
  -e, --every SEG     Intervalo de refresco (def. 2)
      --token T       Token Bearer
      --host H        Host de las instancias (def. 127.0.0.1)
      --dir D         Carpeta de instancias (def. run-instances)
  -c, --command TXT   Orden al arrancar (y en cada refresco si hay --every)
      --once          Ejecuta la orden y sale
      --no-color      Sin colores
`

// --------------------------------------------------------------------------- descubrimiento

const readRegistry = (projectDir: string): Target[] => {
	const path = options.registry ? resolve(options.registry) : join(projectDir, options.dir, '.instances.json')
	if (!existsSync(path)) return []

	let parsed: { instances?: Array<Record<string, unknown>> }
	try {
		parsed = JSON.parse(readFileSync(path, 'utf8'))
	} catch {
		warnLine(`No se pudo leer ${path}`)
		return []
	}
	return (parsed.instances ?? []).map((raw, i) => ({
		name: String(raw.name ?? `inst${i + 1}`),
		host: String(raw.host ?? options.host),
		port: Number(raw.port ?? 0),
		token: String(raw.token ?? options.token ?? ''),
		dir: String(raw.gameDir ?? ''),
		selected: true,
		status: null,
		latencyMs: null,
		up: false,
		lastChat: 0,
	}))
}

/** Si no hay registro, se buscan clientes vivos en un rango de puertos. */
const scanPorts = async (): Promise<Target[]> => {
	const found: Target[] = []
	for (let port = options.scanFrom; port <= options.scanTo; port++) {
		const started = Date.now()
		if (await healthy(options.host, port, '')) {
			found.push({
				name: `:${port}`,
				host: options.host,
				port,
				token: options.token ?? '',
				dir: '',
				selected: true,
				status: null,
				latencyMs: Date.now() - started,
				up: true,
				lastChat: 0,
			})
		}
	}
	return found
}

const healthy = async (host: string, port: number, token: string): Promise<boolean> => {
	try {
		const res = await fetch(`http://${host}:${port}/puppeteer/health`, {
			signal: AbortSignal.timeout(1200),
			headers: token ? { Authorization: `Bearer ${token}` } : {},
		})
		return res.ok
	} catch {
		return false
	}
}

const discover = async (projectDir: string): Promise<Target[]> => {
	const fromRegistry = readRegistry(projectDir)
	const scanned = await scanPorts()
	if (fromRegistry.length === 0) return scanned
	const known = new Set(fromRegistry.map(t => `${t.host}:${t.port}`))
	for (const extra of scanned) {
		if (!known.has(`${extra.host}:${extra.port}`)) fromRegistry.push(extra)
	}
	return fromRegistry
}

const mergeTargets = (current: Target[], found: Target[]): void => {
	for (const target of found) {
		const existing = current.find(t => t.host === target.host && t.port === target.port)
		if (existing) {
			if (target.token) existing.token = target.token
			continue
		}
		current.push(target)
	}
}

// --------------------------------------------------------------------------- API

const trimBody = (body: string): string => {
	try {
		const json = JSON.parse(body) as { ok?: boolean; data?: unknown; error?: { message?: string } }
		if (json.ok) return JSON.stringify(json.data).slice(0, 160)
		return json.error?.message ?? JSON.stringify(json).slice(0, 160)
	} catch {
		return body.slice(0, 160)
	}
}

const call = async (
	target: Target,
	method: 'GET' | 'POST',
	route: string,
	body?: unknown,
	timeoutMs = 5000,
): Promise<{ ok: boolean; body: string }> => {
	try {
		const res = await fetch(`http://${target.host}:${target.port}/puppeteer${route}`, {
			method,
			signal: AbortSignal.timeout(timeoutMs),
			headers: {
				...(target.token ? { Authorization: `Bearer ${target.token}` } : {}),
				...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
			},
			...(body === undefined ? {} : { body: JSON.stringify(body) }),
		})
		return { ok: res.ok, body: await res.text() }
	} catch (error) {
		return { ok: false, body: error instanceof Error ? error.message : String(error) }
	}
}

/** Manda la misma peticion a varias instancias y recoge los resultados. */
const broadcast = async (
	targets: Target[],
	method: 'GET' | 'POST',
	route: string,
	body: unknown,
	label: string,
): Promise<number> => {
	if (targets.length === 0) {
		warnLine(`${label}: no hay instancias seleccionadas`)
		return 1
	}
	const results = await Promise.all(
		targets.map(async target => ({ target, reply: await call(target, method, route, body) })),
	)
	let failures = 0
	for (const { target, reply } of results) {
		const tag = paint(target.name.padEnd(6), target.selected ? c.blue : c.grey)
		if (reply.ok) push(`  ${tag} ${paint('ok  ', c.green)} ${trimBody(reply.body)}`)
		else {
			failures++
			push(`  ${tag} ${paint('err ', c.red)} ${trimBody(reply.body)}`)
		}
	}
	push(`${paint(label, c.bold)} -> ${targets.length} instancia(s)${failures ? `, ${failures} con error` : ''}`)
	return failures
}

// --------------------------------------------------------------------------- estado

const refresh = async (targets: Target[]): Promise<void> => {
	await Promise.all(
		targets.map(async target => {
			const started = Date.now()
			// /status entra en el hilo principal del juego: con varios clientes en
			// la misma maquina puede tardar segundos. Presupuesto corto a proposito;
			// si no llega, la fila se queda en el ultimo estado y se comprueba con
			// /health, que no toca el juego.
			const reply = await call(target, 'GET', '/status', undefined, 3000)
			target.latencyMs = Date.now() - started
			if (reply.ok) {
				target.up = true
				try {
					target.status = (JSON.parse(reply.body) as { data?: Record<string, unknown> }).data ?? {}
				} catch {
					/* respuesta ilegible: se deja sin estado */
				}
				return
			}
			// /status va al hilo principal del juego, asi que con tres clientes
			// en la misma maquina puede tardar. Si solo se retrasa, la fila se
			// queda en gris con el ultimo estado; si murio, se pone en rojo.
			target.up = await healthy(target.host, target.port, target.token)
			if (!target.up) target.status = null
		}),
	)
}

/** Chat de todas, sin vaciar buffers: solo se pintan los mensajes nuevos. */
const pullChat = async (targets: Target[]): Promise<void> => {
	await Promise.all(
		targets.map(async target => {
			if (!target.up) return
			const reply = await call(target, 'GET', '/chat/history?limit=15', undefined, 2500)
			if (!reply.ok) return
			try {
				const data = (JSON.parse(reply.body) as {
					data?: { messages?: Array<{ epochMillis: number; text: string; kind: string }> }
				}).data
				// La marca se avanza al final, con el mensaje mas nuevo de todos:
				// si se avanzara dentro del bucle, cada mensaje pareceria nuevo
				// respecto al anterior y saldria el historial entero.
				let newest = target.lastChat
				for (const message of data?.messages ?? []) {
					// Con la marca a 0 (primer volcado) no se pinta nada: al abrir
					// el panel no interesa el historial de hace una hora.
					if (target.lastChat !== 0 && message.epochMillis > target.lastChat) {
						const kind = message.kind === 'chat' ? '' : paint(` ${message.kind}`, c.dim)
						push(
							`  ${paint(target.name.padEnd(6), c.blue)}${kind} ${paint(message.text.slice(0, 150), c.dim)}`,
						)
					}
					if (message.epochMillis > newest) newest = message.epochMillis
				}
				target.lastChat = newest
			} catch {
				/* respuesta rara: se ignora */
			}
		}),
	)
}

// --------------------------------------------------------------------------- dibujo

/**
 * Filas y columnas del terminal. Algunos pty (los de `script`, algunos CI)
 * las dan como 0: se trata como "desconocido" y se dibuja como si fuera de 24x100.
 */
const termRows = (): number => (process.stdout.rows > 2 ? process.stdout.rows : 24)
const termCols = (): number => (process.stdout.columns > 20 ? process.stdout.columns : 100)

const pad = (text: string, width: number): string =>
	text.length >= width ? `${text.slice(0, Math.max(0, width - 1))}…` : text.padEnd(width)

const screenOf = (status: Record<string, unknown> | null): string => {
	if (!status) return ''
	if (status.inWorld === true) return String(status.serverName ?? status.serverAddress ?? 'en juego')
	return String(status.screen ?? '')
}

/** `minecraft:overworld` -> `over`; en el panel no cabe la ruta entera. */
const shortDim = (dimension: unknown): string => {
	const text = String(dimension ?? '-')
	return text.includes(':') ? text.split(':').pop()! : text
}

const COLUMNS: Array<[string, number]> = [
	['inst', 8],
	['puerto', 7],
	['estado', 20],
	['mundo', 14],
	['dim', 7],
	['jug', 7],
	['fps', 5],
	['ms', 5],
]

const render = (targets: Target[], input: string, scroll: number): string => {
	const width = Math.max(60, Math.min(170, termCols()))
	const lines: string[] = []

	const alive = targets.filter(t => t.up).length
	lines.push(
		`${paint('mc-puppeteer', c.bold)}  ${targets.length} instancia(s), ${alive} viva(s)  ` +
			`${paint(`cada ${options.interval}s`, c.dim)}  ${paint(clock(), c.grey)}`,
	)
	lines.push(paint('─'.repeat(width), c.grey))
	lines.push(paint(COLUMNS.map(([title, size]) => pad(title, size)).join(''), c.bold))

	if (targets.length === 0) {
		lines.push(paint('  ninguna instancia: arranca run-instances.ts o escribe /scan', c.yellow))
	}

	for (const [i, target] of targets.entries()) {
		const status = target.status ?? {}
		const dot = target.up ? paint('●', c.green) : paint('○', c.red)
		const sel = target.selected ? c.blue : c.dim
		const players = target.up ? `${status.playerCount ?? 0}/${status.maxPlayers ?? '?'}` : '-'
		const line =
			`${dot} ${paint(pad(`${i + 1}. ${target.name}`, COLUMNS[0][1]), sel)}` +
			pad(String(target.port), COLUMNS[1][1]) +
			pad(screenOf(target.status), COLUMNS[2][1]) +
			pad(String(status.worldName ?? '-'), COLUMNS[3][1]) +
			pad(target.up ? shortDim(status.dimension) : '-', COLUMNS[4][1]) +
			pad(players, COLUMNS[5][1]) +
			pad(String(status.fps ?? '-'), COLUMNS[6][1]) +
			pad(target.latencyMs === null ? '-' : String(target.latencyMs), COLUMNS[7][1])
		// Roja si no responde, amarilla si vive pero el estado aun no ha llegado.
		lines.push(!target.up ? paint(line, c.red) : target.status === null ? paint(line, c.yellow) : line)
	}

	lines.push(paint('─'.repeat(width), c.grey))

	// El feed ocupa lo que queda entre la tabla y la linea de entrada.
	const room = Math.max(3, termRows() - lines.length - 3)
	const from = Math.max(0, feed.length - room + scroll)
	const visible = feed.slice(from, Math.min(feed.length, from + room))
	for (const line of visible) lines.push(line)
	while (lines.length < from + room) lines.push('')

	lines.push(paint('─'.repeat(width), c.grey))
	lines.push(`${paint('>', c.green)} ${input}`)
	lines.push(
		paint(
			'/say /cmd /baritone /connect /disconnect /respawn /profile /history /log /every /scan /token /sel /quit · @1,3 · 1-9 · Q',
			c.grey,
		),
	)
	return lines.join('\n')
}

const draw = (text: string): void => {
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

// --------------------------------------------------------------------------- entrada

type Stdin = {
	isTTY?: boolean
	setRawMode?: (mode: boolean) => void
	resume: () => void
	setEncoding?: (encoding: string) => void
	on: (event: string, handler: (chunk?: string) => void) => void
}

const isTty = (): boolean => Boolean((process.stdin as Stdin).isTTY)

const startInput = (onKey: (key: Key) => void): void => {
	const stdin = process.stdin as Stdin
	if (stdin.isTTY) stdin.setRawMode?.(true)
	stdin.resume()
	stdin.setEncoding?.('utf8')

	let buffer = ''
	stdin.on('data', chunk => {
		buffer += chunk
		while (buffer.length > 0) {
			if (buffer.startsWith('\x1b')) {
				// Secuencia de escape: se interpreta entera o se espera mas.
				const match = /^\x1b(\[[0-9;]*[A-Za-z~]|O[A-Za-z]|.)/.exec(buffer)
				if (!match) return
				buffer = buffer.slice(match[0].length)
				const sequence = match[1]
				const name =
					sequence === '[A' ? 'up'
					: sequence === '[B' ? 'down'
					: sequence === '[5~' ? 'pageup'
					: sequence === '[6~' ? 'pagedown'
					: 'escape'
				onKey({ name, char: '' })
				continue
			}
			const char = buffer[0]
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

// --------------------------------------------------------------------------- ordenes

/** Consultas de Baritone que el mod expone como GET y sin argumentos. */
const BARITONE_QUERIES = new Set(['version', 'proc', 'eta', 'modified', 'paused', 'wp', 'gc'])

const COMMANDS: Record<string, Handler> = {
	say: async (args, targets) => broadcast(targets, 'POST', '/chat', { message: args }, 'say'),
	cmd: async (args, targets) =>
		broadcast(targets, 'POST', '/command', { command: args.replace(/^\//, '') }, 'cmd'),
	connect: async (args, targets) => broadcast(targets, 'POST', '/connect', { address: args }, 'connect'),
	disconnect: async (_args, targets) => broadcast(targets, 'POST', '/disconnect', {}, 'disconnect'),
	disco: async (_args, targets) => broadcast(targets, 'POST', '/disconnect', {}, 'disconnect'),
	respawn: async (_args, targets) => broadcast(targets, 'POST', '/respawn', {}, 'respawn'),
	profile: async (args, targets) => broadcast(targets, 'POST', '/profile', { name: args }, 'profile'),
	status: async (_args, targets) => broadcast(targets, 'GET', '/status', undefined, 'status'),
	health: async (_args, targets) => broadcast(targets, 'GET', '/health', undefined, 'health'),
	players: async (_args, targets) => broadcast(targets, 'GET', '/players', undefined, 'players'),
	// El feed lee el historial con `history`; `chat` es abreviatura de `say`,
	// que es lo que espera cualquiera que escriba "chat hola".
	chat: async (args, targets) => broadcast(targets, 'POST', '/chat', { message: args }, 'say'),
	history: async (args, targets) =>
		broadcast(targets, 'GET', `/chat/history?limit=${Number(args) || 15}`, undefined, 'history'),
	baritone: async (args, targets) => {
		const action = args.split(/\s+/)[0] ?? ''
		// Consulta simple sin argumentos: el endpoint propio del mod. Cualquier
		// otra cosa se manda como chat con '#', que es como Baritone espera
		// recibirla y ademas admite argumentos libres (`#goto 100 64 200`).
		if (BARITONE_QUERIES.has(action)) {
			return broadcast(targets, 'GET', `/baritone/${action}`, undefined, `baritone ${action}`)
		}
		return broadcast(targets, 'POST', '/chat', { message: `#${args}` }, 'baritone')
	},
}
COMMANDS.hist = COMMANDS.history
COMMANDS.bc = COMMANDS.baritone

/** `@all`, `@1,3`, `@mc2`... -> que instancias entran. */
const resolveTargets = (input: string, targets: Target[]): Target[] => {
	const match = /^\s*@([\w,]+)\s+/.exec(input)
	if (!match) return targets.filter(t => t.selected)
	const spec = match[1]
	if (spec === 'all') return targets
	const wanted = spec.split(',')
	return targets.filter(t => wanted.includes(t.name) || wanted.includes(String(targets.indexOf(t) + 1)))
}

const stripTarget = (input: string): string => input.replace(/^\s*@[\w,]+\s+/, '')

/** `/log 20 mc2` -> ultimas lineas del stdout de esa instancia. */
const showLog = (args: string, targets: Target[]): void => {
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

const runOrder = async (raw: string, state: { targets: Target[]; input: string }, redraw: () => void): Promise<void> => {
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
		await COMMANDS.say!(line.slice(1).trim(), targets)
		redraw()
		return
	}

	switch (verb) {
		case 'help':
			for (const helpLine of HELP.trim().split('\n')) push(helpLine)
			break
		case 'quit':
		case 'exit':
		case 'q':
			quit()
			return
		case 'clear':
			feed = []
			break
		case 'every': {
			const seconds = Number(args)
			if (!Number.isFinite(seconds) || seconds < 1) warnLine('uso: /every <segundos>')
			else {
				options.interval = seconds
				restartTicker()
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
			const found = await discover(projectDir)
			mergeTargets(state.targets, found)
			push(`${found.length} instancia(s) descubiertas`)
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
	redraw()
}

// --------------------------------------------------------------------------- bucle

const state = { targets: [] as Target[], input: '', scroll: 0 }
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
	} finally {
		ticking = false
	}
}

const restartTicker = (): void => {
	if (ticker) clearInterval(ticker)
	ticker = setInterval(() => {
		void tick().then(() => {
			if (repeatCommand) void runOrder(repeatCommand, state, () => {})
		})
	}, options.interval * 1000)
}

const paintLoop = (): void => {
	if (!isTty() || painting || quitting) return
	painting = true
	draw(render(state.targets, state.input, state.scroll))
	painting = false
}

const quit = (): void => {
	if (quitting) return
	quitting = true
	if (ticker) clearInterval(ticker)
	if (renderer) clearInterval(renderer)
	const stdin = process.stdin as Stdin
	// Hay que devolver el terminal a su estado normal o se queda en modo raw.
	if (stdin.isTTY) stdin.setRawMode?.(false)
	if (isTty()) process.stdout.write('\x1b[?1049l\x1b[?25h')
	stdin.pause?.()
	// Nada de process.exit: al vaciarse el bucle de eventos Node descarga stdout
	// y no se pierde ni la ultima linea cuando la salida va a un fichero o a
	// una tuberia. Una peticion que se quedara colgada caduca sola (5 s).
	process.exitCode = 0
}

// --------------------------------------------------------------------------- main

const main = async (): Promise<void> => {
	options = parseArgs(process.argv.slice(2))
	color = !options.noColor
	if (options.help) {
		console.log(HELP)
		return
	}
	projectDir = resolve(dirname(fileURLToPath(import.meta.url)), '..')
	process.on('SIGINT', quit)
	process.on('SIGTERM', quit)

	const found = await discover(projectDir)
	state.targets = found
	// En modo script todo lo que se va apilando en el feed se escribe en
	// stdout, y eso incluye la linea de las instancias detectadas.
	echo = options.command !== null
	push(
		found.length > 0
			? `instancias: ${found.map(t => `${t.name}:${t.port}`).join('  ')}`
			: 'instancias: ninguna (arranca run-instances.ts, o /scan)',
	)

	// ---- modo script: una orden y fuera, o repetida cada --every.
	if (options.command) {
		await tick()
		await runOrder(options.command, state, () => {})
		if (options.once) return
		repeatCommand = options.command
		restartTicker()
		return
	}

	// ---- sin terminal: comportamiento de `watch`.
	if (!isTty()) {
		await tick()
		console.log(render(state.targets, '', 0))
		renderer = setInterval(() => {
			void tick().then(() => console.log(render(state.targets, '', 0)))
		}, options.interval * 1000)
		return
	}

	// ---- panel interactivo.
	process.stdout.write('\x1b[?1049h\x1b[?25l') // pantalla alternativa, sin cursor
	push('escribe /help para la lista de ordenes')
	await tick()

	startInput(({ name, char }) => {
		switch (name) {
			case 'ctrl-c':
			case 'escape':
				quit()
				return
			case 'enter': {
				const typed = state.input
				state.input = ''
				void runOrder(typed, state, paintLoop)
				return
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
					if (char === 'Q') {
						quit()
						return
					}
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
						void tick().then(paintLoop)
						break
					}
					if (char === 'l') {
						showLog('15', state.targets)
						break
					}
				}
				state.input += char
				break
			}
			default:
				break
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
