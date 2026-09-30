/**
 * commands.ts — las ordenes que se pueden escribir en el panel.
 *
 * Casi todas son un `broadcast`: la misma peticion a varias instancias. Las
 * pocas que no (disperse, items, focus) hacen su propio reparto porque cada
 * instancia necesita una peticion distinta.
 */

import { bar, c, paint, pad } from './ansi.ts'
import { broadcast, call, trimBody } from './api.ts'
import { groupItems } from './inventory.ts'
import { push, state, warnLine } from './state.ts'
import { store, storeNow } from './store.ts'
import type { Handler, ItemInfo, Target } from './types.ts'

/** Consultas de Baritone que el mod expone como GET y sin argumentos. */
const BARITONE_QUERIES = new Set(['version', 'proc', 'eta', 'modified', 'paused', 'wp', 'gc'])

/**
 * Ordenes de Baritone que dejan el hilo principal de Minecraft esperando un
 * `CompletableFuture` de Baritone para siempre: la instancia queda con
 * `/health` en 200 y todo lo demas en 503, y no se recupera sin reiniciarla.
 * `#goto x y z` con numeros va bien, asi que solo se avisa del caso bloque.
 * Detalle y comandos seguros en `agents/05-api.md`.
 */
const lethalBaritone = (args: string): boolean => {
	const parts = args.trim().split(/\s+/)
	if (parts[0] === 'mine') return true
	return parts[0] === 'goto' && parts.length > 1 && !/^-?\d+$/.test(parts[1])
}

/** Limites del mundo que impone el mod en las coordenadas (`agents/05-api.md`). */
const WORLD_LIMIT = 30_000_000
const MIN_Y = -64
const MAX_Y = 320

/**
 * Reparte `count` puntos dentro de un disco de `radius` bloques, en espiral de
 * angulo aureo: mismo reparto para el mismo numero de instancias, todos dentro
 * del disco, y la primera instancia cae justo en el centro. Se elige esto y no
 * un punto al azar porque con varias instancias al azar se amontonan, y porque
 * repetir la misma orden tiene que llevar a las mismas instancias al mismo sitio.
 */
const scatter = (count: number, radius: number): Array<{ dx: number; dz: number }> => {
	const golden = Math.PI * (3 - Math.sqrt(5))
	return Array.from({ length: count }, (_, i) => {
		if (count === 1) return { dx: 0, dz: 0 }
		const d = radius * Math.sqrt(i / (count - 1))
		const a = i * golden
		return { dx: Math.round(d * Math.cos(a)), dz: Math.round(d * Math.sin(a)) }
	})
}

/** Mismo reparto a cada instancia, pero con su propio `#goto`. */
const disperse = async (args: string, targets: Target[]): Promise<number> => {
	const parts = args.trim().split(/\s+/)
	if (parts.length !== 4) {
		warnLine('disperse: uso -> disperse <x> <y> <z> <radio>')
		return 1
	}
	const [cx, cy, cz, radius] = parts.map(Number)
	if (![cx, cy, cz, radius].every(Number.isInteger)) {
		warnLine('disperse: x, y, z y radio tienen que ser numeros enteros')
		return 1
	}
	if (radius < 0) {
		warnLine('disperse: el radio no puede ser negativo')
		return 1
	}
	if (cy < MIN_Y || cy > MAX_Y) {
		warnLine(`disperse: la altura tiene que estar entre ${MIN_Y} y ${MAX_Y} (recibido ${cy})`)
		return 1
	}
	if (Math.abs(cx) + radius > WORLD_LIMIT || Math.abs(cz) + radius > WORLD_LIMIT) {
		warnLine(`disperse: el centro mas el radio se sale del mundo (limite ${WORLD_LIMIT})`)
		return 1
	}
	if (targets.length === 0) {
		warnLine('disperse: no hay instancias seleccionadas')
		return 1
	}

	const puntos = scatter(targets.length, radius)
	const results = await Promise.all(
		targets.map(async (target, i) => {
			const p = puntos[i]!
			const body = { x: cx + p.dx, y: cy, z: cz + p.dz }
			return { target, body, reply: await call(target, 'POST', '/baritone/goto', body) }
		}),
	)
	let failures = 0
	for (const { target, body, reply } of results) {
		const tag = paint(target.name.padEnd(6), target.selected ? c.blue : c.grey)
		if (reply.ok) push(`  ${tag} ${paint('ok  ', c.green)} ${trimBody(reply.body)}`)
		else {
			failures++
			push(`  ${tag} ${paint('err ', c.red)} ${trimBody(reply.body)}`)
		}
	}
	push(
		`${paint('disperse', c.bold)} -> ${targets.length} instancia(s) en ${radius} bloques ` +
			`alrededor de ${cx} ${cy} ${cz}${failures ? `, ${failures} con error` : ''}`,
	)
	return failures
}

/** Inventario de varias instancias en el feed (la columna lo muestra de una en una). */
const items = async (_args: string, targets: Target[]): Promise<number> => {
	if (targets.length === 0) {
		warnLine('items: no hay instancias seleccionadas')
		return 1
	}
	let failures = 0
	for (const target of targets) {
		const tag = paint(target.name.padEnd(6), target.selected ? c.blue : c.grey)
		const reply = await call(target, 'GET', '/inventory')
		if (!reply.ok) {
			failures++
			push(`  ${tag} ${paint('err ', c.red)} ${trimBody(reply.body)}`)
			continue
		}
		let rows: ReturnType<typeof groupItems> = []
		try {
			const data = (JSON.parse(reply.body) as { data?: { items?: ItemInfo[] } }).data
			rows = groupItems(data?.items ?? [])
		} catch {
			failures++
			push(`  ${tag} ${paint('err ', c.red)} respuesta ilegible`)
			continue
		}
		if (rows.length === 0) {
			push(`  ${tag} ${paint('vacio', c.dim)} (sin items)`)
			continue
		}
		const units = rows.reduce((sum, row) => sum + row.count, 0)
		const top = rows[0]!.count
		push(`  ${tag} ${paint(`${rows.length} tipo(s)`, c.bold)}, ${units} unidad(es)`)
		for (const row of rows.slice(0, 20)) {
			push(
				`    ${pad(row.name || row.id, 24)} ${bar(row.count, top, 16)} ` +
					paint(String(row.count).padStart(5), c.dim),
			)
		}
		if (rows.length > 20) push(`    ${paint(`... y ${rows.length - 20} tipo(s) mas`, c.dim)}`)
	}
	return failures
}

/** Elige que instancia se mira en el panel de la derecha. */
const focus = (args: string): void => {
	const targets = state.targets
	if (targets.length === 0) {
		warnLine('focus: no hay instancias')
		return
	}
	const arg = args.trim()
	if (arg === '' || arg === 'next') {
		state.focus = (state.focus + 1) % targets.length
	} else if (arg === 'first') {
		state.focus = 0
	} else {
		const index = targets.findIndex(t => t.name === arg)
		if (index >= 0) state.focus = index
		else {
			const n = Number(arg)
			if (!Number.isInteger(n) || n < 1 || n > targets.length) {
				warnLine(`focus: usa un numero del 1 al ${targets.length}, un nombre, o 'next'`)
				return
			}
			state.focus = n - 1
		}
	}
	push(`inventario de ${targets[state.focus]?.name}`)
}

export const COMMANDS: Record<string, Handler> = {
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
	items,
	disperse,
	store,
	storenow: storeNow,
	focus: async (args, _targets) => {
		focus(args)
	},
	baritone: async (args, targets) => {
		const action = args.split(/\s+/)[0] ?? ''
		// Consulta simple sin argumentos: el endpoint propio del mod. Cualquier
		// otra cosa se manda como chat con '#', que es como Baritone espera
		// recibirla y ademas admite argumentos libres (`#goto 100 64 200`).
		if (BARITONE_QUERIES.has(action)) {
			return broadcast(targets, 'GET', `/baritone/${action}`, undefined, `baritone ${action}`)
		}
		if (lethalBaritone(args)) {
			push(
				`${paint('  aviso  ', c.red)}#${args} necesita PUPPETEER_BARITONE_ASYNC=1 ` +
					`(${targets.map(t => t.name).join(', ')}): sin ella cuelga el hilo principal.`,
			)
		}
		return broadcast(targets, 'POST', '/chat', { message: `#${args}` }, 'baritone')
	},
	// El feed lee el historial con `history`; `chat` es abreviatura de `say`,
	// que es lo que espera cualquiera que escriba "chat hola".
	chat: async (args, targets) => broadcast(targets, 'POST', '/chat', { message: args }, 'say'),
	history: async (args, targets) =>
		broadcast(targets, 'GET', `/chat/history?limit=${Number(args) || 15}`, undefined, 'history'),
}
COMMANDS.hist = COMMANDS.history
COMMANDS.bc = COMMANDS.baritone
COMMANDS.inv = COMMANDS.items

export const HELP = `
Panel de control de las instancias de mc-puppeteer.

  bun   scripts/tui.ts
  node scripts/tui.ts

Zonas del panel:
  izquierda  una fila por instancia: estado, mundo, vida, comida, posicion.
  derecha    inventario de la instancia marcada con >, de mas a menos.
  abajo      feed con el chat y las respuestas; se recorre con las flechas.

Ordenes (con / delante o escribiendolas tal cual):
  say <texto>         Envia chat a las seleccionadas. (chat = say)
  cmd <comando>       Envia comando de servidor (sin la barra).
  baritone <orden>    Consulta a Baritone, u orden libre como #goto 1 2 3.
                      #mine y #goto <bloque> necesitan PUPPETEER_BARITONE_ASYNC=1
                      (el lanzador de instancias ya la pone).
  disperse <x y z r>  Reparte las seleccionadas en un radio de r bloques
                      alrededor de x y z, con un #goto a cada una.
  store [x y z]       Vuelca el inventario de las seleccionadas en un cofre.
                      Sin coordenadas, usa el de la config de cada instancia.
                      Camina con Baritone si el cofre esta lejos, coloca uno si
                      no hay ninguno, y se desconecta si no puede.
  storenow            Coloca un cofre donde este cada bot y lo deja.
  connect <servidor>  Conecta a un servidor.
  disconnect          Sale al titulo.
  respawn             Reaparicion.
  profile <nombre>    Cambia la identidad offline.
  status              Fuerza la lectura del estado.
  players             Quien esta en el mundo.
  items               Inventario de todas, en el feed. El de la derecha es
                      automatico para la instancia marcada con >.
  focus <n|nombre>    Cambia la instancia cuyo inventario se mira (next, first).
  history [n]         Historial de chat (por defecto 15). No lo vacia.
  log <n> [mcN]       Ultimas lineas del log de una instancia.
  every <seg>         Cambia el intervalo de refresco.
  scan                Vuelve a descubrir instancias.
  token <t|clear>     Token Bearer de las peticiones.
  sel <n|all|none>    Selecciona instancias (alterna: 1-9 con la linea vacia).
  target              A quien iran las ordenes.
  clear               Limpia el feed.
  help                Esto.
  quit                Sale.

Destino:  @all  @1,3  @mc2      (por defecto, las seleccionadas)
Atajo:    !texto = say a todas
Teclas:   con la linea vacia, 1-9 seleccionan, a todas, n ninguna,
          r refresca, l log, Q sale, flechas para recorrer el feed.

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
