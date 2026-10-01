/**
 * commands.ts — las ordenes que se pueden escribir en el panel.
 *
 * Casi todas son un `broadcast`: la misma peticion a varias instancias. Las
 * pocas que no (disperse, items, focus) hacen su propio reparto porque cada
 * instancia necesita una peticion distinta.
 */

import { bar, c, paint, pad } from './ansi.ts'
import { broadcast, call, trimBody } from './api.ts'
import {
	chestDe,
	listarChests,
	NIGHT_AT_MAX,
	NIGHT_AT_MIN,
	nightAt,
	nombreValido,
	ponerAsignado,
	ponerChest,
	ponerNightAt,
	quitarChest,
	rutaConfig,
} from './config.ts'
import { groupItems } from './inventory.ts'
import { push, state, warnLine } from './state.ts'
import { store, storeNow } from './store.ts'
import { aplicaAsignados, describeAsignados } from './switch.ts'
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

/**
 * Elige que instancia se mira en el panel de inventario.
 *
 * Sin foco se ve la suma de todas, que es lo normal. `focus` estrecha la vista a
 * una sola, y `focus all` (o `next` dando la vuelta entera) vuelve a la global.
 */
const focus = (args: string): void => {
	const targets = state.targets
	if (targets.length === 0) {
		warnLine('focus: no hay instancias')
		return
	}
	const arg = args.trim()
	if (arg === '' || arg === 'next') {
		// El foco recorre 0, 1, ... y vuelta a la suma de todas. Se cuenta desde
		// -1 para que desde la vista global entre directamente en la primera.
		const actual = state.focus === null ? -1 : state.focus
		state.focus = actual + 1 >= targets.length ? null : actual + 1
	} else if (arg === 'all' || arg === 'todos' || arg === 'none') {
		state.focus = null
	} else if (arg === 'first') {
		state.focus = 0
	} else {
		const index = targets.findIndex(t => t.name === arg)
		if (index >= 0) state.focus = index
		else {
			const n = Number(arg)
			if (!Number.isInteger(n) || n < 1 || n > targets.length) {
				warnLine(
					`focus: usa un numero del 1 al ${targets.length}, un nombre, ` +
						`'all' (volver a la suma de todas) o 'next'`,
				)
				return
			}
			state.focus = n - 1
		}
	}
	push(
		state.focus === null
			? 'inventario de todas las instancias (sumado)'
			: `inventario de ${targets[state.focus]?.name}`,
	)
}

/**
 * `chest [list | <nombre> | <nombre> x y z | <nombre> rm]`: los cofres con
 * nombre que luego acepta `store`.
 *
 * Vive en el panel y no en el mod a proposito: un nombre es un atajo de quien
 * escribe la orden, no parte del bot. Para el mod `store cofre` es exactamente
 * `store 10 -60 4`, y `store` a secas sigue siendo el cofre de la config.
 */
const cofres = (args: string): void => {
	const partes = args.trim().split(/\s+/).filter(Boolean)
	const primero = partes[0] ?? ''

	if (primero === '' || primero === 'list') {
		const lista = listarChests()
		if (lista.length === 0) {
			push(`sin cofres con nombre. Se anaden con  chest <nombre> <x y z>   (${rutaConfig()})`)
			return
		}
		push(`cofres con nombre, para  store <nombre>  (${rutaConfig()}):`)
		// La columna se ajusta al nombre mas largo: con un ancho fijo, uno de 20
		// caracteres empuja a las coordenadas de los demas y la lista deja de
		// leerse en columna.
		const ancho = Math.max(...lista.map(([nombre]) => nombre.length)) + 2
		for (const [nombre, xyz] of lista) {
			push(`  ${paint(nombre.padEnd(ancho), c.blue)}${xyz.x} ${xyz.y} ${xyz.z}`)
		}
		return
	}

	const nombre = primero
	if (partes.length === 1) {
		const xyz = chestDe(nombre)
		if (!xyz) {
			const otros = listarChests()
				.map(([n]) => n)
				.join(', ')
			warnLine(
				`chest: no hay ningun cofre con el nombre '${nombre}'` +
					(otros ? ` (los guardados: ${otros})` : ''),
			)
			return
		}
		push(`${paint('cofre', c.bold)} ${nombre} = ${xyz.x} ${xyz.y} ${xyz.z}   (store ${nombre})`)
		return
	}

	if (partes.length === 2 && (partes[1] === 'rm' || partes[1] === 'del')) {
		const feito = quitarChest(nombre)
		if (feito === 'borrado') push(`cofre ${nombre}: borrado`)
		else if (feito === 'no estaba') warnLine(`chest: no hay ningun cofre con el nombre '${nombre}'`)
		// 'roto' ya ha avisado `leerConfig` del JSON ilegible.
		return
	}

	if (partes.length === 4) {
		const [x, y, z] = partes.slice(1).map(Number)
		if (![x, y, z].every(Number.isInteger)) {
			warnLine(`chest ${nombre}: '${partes.slice(1).join(' ')}' no son tres enteros`)
			return
		}
		if (!nombreValido(nombre)) {
			warnLine(`chest: '${nombre}' no vale como nombre (letras, digitos, _ - y ., hasta 32)`)
			return
		}
		// Con el JSON roto `leerConfig` ya ha avisado, y aqui solo se devuelve
		// el `false`: no se escribe encima de algo que no se ha podido leer.
		if (ponerChest(nombre, { x: x!, y: y!, z: z! })) {
			push(`${paint('cofre', c.bold)} ${nombre} = ${x} ${y} ${z}   (store ${nombre})`)
		}
		return
	}

	warnLine('uso -> chest [list | <nombre> | <nombre> x y z | <nombre> rm]')
}

/**
 * `switch [list | off | <nombre> | <hora 1-23999>]`: el modo switch.
 *
 * Con un nombre, se lo asigna a las instancias seleccionadas y las activa: al
 * anochecer, cada una va ahi con un `store`. Se elige a quien con lo de
 * siempre, `@1,3` o `sel`, igual que cualquier otra orden.
 *
 * Un numero cambia la hora del mundo a la que se considera que ha anochecido
 * (`13000` es la noche de vanilla, el atardecer es a las `12000`). No se confunde
 * con un nombre de cofre porque un nombre no puede ser un numero suelto: lo
 * comprueba `nombreValido`, asi que `switch 13000` solo puede ser la hora.
 *
 * `off` le quita el cofre a las seleccionadas, y `list` enseña quien va a donde
 * y a que hora dispara. Todo se guarda en `.tui-config.json`, asi que sigue
 * puesto en la siguiente sesion.
 */
const modoSwitch = (args: string, targets: Target[]): void => {
	const partes = args.trim().split(/\s+/).filter(Boolean)
	const primero = partes[0] ?? ''

	if (primero === '' || primero === 'list') {
		const lineas = describeAsignados(targets)
		push(`switch: al caer la noche, al cofre asignado. Anochece a las ${nightAt()} de las 24000.`)
		if (lineas.length === 0) {
			push(`  sin bots asignados; se asignan con  @<sel> switch <nombre>`)
		} else {
			for (const linea of lineas) push(linea)
		}
		return
	}

	if (partes.length !== 1) {
		warnLine('uso -> switch [list | off | <nombre> | <hora 1-23999>]   (se aplica a las seleccionadas)')
		return
	}
	if (targets.length === 0) {
		warnLine('switch: no hay instancias seleccionadas')
		return
	}

	if (primero === 'off') {
		const fuera = targets.filter(t => t.cofre)
		for (const target of targets) ponerAsignado(target.name, null)
		// Y a memoria: escribir la config no basta, que el disparo y la columna
		// de la tabla leen `target.cofre`, no el fichero.
		aplicaAsignados(targets)
		push(fuera.length > 0 ? `switch: fuera ${fuera.map(t => t.name).join(', ')}` : 'switch: no habia ninguno asignado')
		return
	}

	// Numero: la hora. Un nombre de cofre no puede ser un numero, asi que no hay
	// nada mas que probar antes de entrar por aqui.
	if (/^-?\d+$/.test(primero)) {
		const hora = Number(primero)
		if (hora < NIGHT_AT_MIN || hora > NIGHT_AT_MAX) {
			warnLine(
				`switch: ${hora} no vale como hora; de ${NIGHT_AT_MIN} a ${NIGHT_AT_MAX}. Con 0 o 24000 no ` +
					'queda ningun momento de dia al que volver a disparar',
			)
			return
		}
		if (!ponerNightAt(hora)) return
		push(`switch: anochece a las ${hora} de las 24000`)
		return
	}

	if (!nombreValido(primero)) {
		warnLine(`switch: '${primero}' no vale como nombre (letras, digitos, _ - y ., hasta 32)`)
		return
	}
	if (chestDe(primero) === null) {
		const otros = listarChests().map(([n]) => n)
		warnLine(
			`switch: no hay ningun cofre con el nombre '${primero}'` +
				(otros.length > 0 ? ` (los guardados: ${otros.join(', ')})` : ' y aun no hay ninguno guardado'),
		)
		return
	}
	for (const target of targets) ponerAsignado(target.name, primero)
	// Y a memoria, que el fichero no lo lee nadie en cada refresco.
	aplicaAsignados(targets)
	push(`switch: ${targets.map(t => t.name).join(', ')} -> '${primero}', al anochecer (${nightAt()} de las 24000)`)
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
	chest: async (args, _targets) => {
		cofres(args)
	},
	switch: async (args, targets) => {
		modoSwitch(args, targets)
	},
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
// El cofre con nombre tambien se puede llamar como el resto de las cosas.
COMMANDS.chests = COMMANDS.chest
COMMANDS.cofre = COMMANDS.chest

export const HELP = `
Panel de control de las instancias de mc-puppeteer.

  bun   scripts/tui.ts
  node scripts/tui.ts

Zonas del panel:
  arriba izq.  bots: dos lineas por instancia, con sus datos y las barras de
               comida y vida una debajo de otra.
  arriba der.  feed con el chat y las respuestas; se recorre con las flechas.
  abajo izq.   inventario de todos los bots sumado, de mas a menos. Con focus
               se mira solo el de uno, que es el que marca la tabla con >.
  abajo der.   la linea donde se escriben las ordenes.

Ordenes (con / delante o escribiendolas tal cual):
  say <texto>         Envia chat a las seleccionadas. (chat = say)
  cmd <comando>       Envia comando de servidor (sin la barra).
  baritone <orden>    Consulta a Baritone, u orden libre como #goto 1 2 3.
                      #mine y #goto <bloque> necesitan PUPPETEER_BARITONE_ASYNC=1
                      (el lanzador de instancias ya la pone).
  disperse <x y z r>  Reparte las seleccionadas en un radio de r bloques
                      alrededor de x y z, con un #goto a cada una.
  store [x y z]       Vuelca el inventario de las seleccionadas en un cofre.
                      Acepta tambien un nombre guardado con chest: store cofre.
                      Sin coordenadas, usa el de la config de cada instancia.
                      Camina con Baritone si el cofre esta lejos, coloca uno si
                      no hay ninguno, y se desconecta si no puede.
  storenow            Coloca un cofre donde este cada bot y lo deja.
  chest <n> <x y z>   Guarda un cofre con nombre para store <nombre>.
  chest list          Los cofres con nombre que hay. (chest solo tambien)
  chest <n> rm        Borra uno. (chests y cofre tambien valen)
  switch <n>          Modo switch: al anochecer, las seleccionadas van con un
                      store al cofre <n>. A quien se elige con @1,3 o sel.
  switch off          Se lo quita a las seleccionadas.
  switch list         Quien va a donde, y a que hora se dispara.
  switch <1-23999>    La hora del mundo a la que se considera que ha
                      anochecido (13000 es la noche de vanilla). No se confunde
                      con un nombre: un cofre no puede llamarse 13000.
  connect <servidor>  Conecta a un servidor.
  disconnect          Sale al titulo.
  respawn             Reaparicion.
  profile <nombre>    Cambia la identidad offline.
  status              Fuerza la lectura del estado.
  players             Quien esta en el mundo.
  items               Inventario de cada seleccionada, en el feed. El panel de
                      abajo suma el de todas.
  focus <n|nombre>    Mira el inventario de una sola instancia en vez de la suma
                      de todas (next, first, all para volver a la global).
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
          La leyenda entera esta en agents/08-teclas.md

Modo script (sin panel):
  node scripts/tui.ts --once --connect 1.2.3.4:25565
  node scripts/tui.ts --every 10 -c "say hola"

Opciones:
      --registry F    Registro de instancias (def. run-instances/.instances.json)
      --chests F      Cofres con nombre (def. scripts/.tui-config.json)
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
