/**
 * store.ts — las ordenes de guardar el inventario en un cofre.
 *
 * `/store` y `/storenow` no son una llamada y se acabó: el mod las ejecuta
 * durante varios ticks (caminar, colocar, abrir, volcar) y devuelve 202 al
 * momento. Asi que aqui, en vez de esperar bloqueados, se manda la peticion y se
 * va leyendo `GET /store` hasta que el estado es terminal.
 *
 * Se imprime una linea cada vez que el estado **cambia**, no en cada consulta:
 * si no, el feed se llena de lineas iguales.
 */

import { c, paint } from './ansi.ts'
import { call, trimBody } from './api.ts'
import { chestDe, listarChests, type Coord } from './chests.ts'
import { push, warnLine } from './state.ts'
import type { Reply, Target } from './types.ts'

/** Los estados en los que ya no va a pasar nada mas. */
const TERMINALES = new Set(['done', 'failed'])

/** El estado que devuelve el mod, tal cual. */
export type StoreState = {
	state: string
	target: { x: number; y: number; z: number } | null
	moved: boolean
	placed: boolean
	stored: number
	reason: string | null
}

const isStoreState = (value: unknown): value is StoreState => {
	const data = value as Partial<StoreState> | null
	return typeof data?.state === 'string'
}

/** Como se lee un estado en el feed. */
const describe = (data: StoreState): string => {
	switch (data.state) {
		case 'walking':
			return 'caminando al cofre'
		case 'placing':
			return 'colocando el cofre'
		case 'opening':
			return 'abriendo el cofre'
		case 'storing':
			return 'vaciando el inventario'
		case 'done':
			return `hecho: ${data.stored} unidad(es) al cofre`
		case 'failed':
			return `fallo: ${data.reason ?? 'sin motivo'}`
		default:
			return data.state
	}
}

/** El uso, que sale cuando no se entiende lo que se ha escrito. */
const USO = 'uso -> store [x y z | <nombre>]   (sin nada, el cofre de la config de cada instancia)'

/**
 * A donde va el cofre, ya en coordenadas.
 *
 * Tres cosas se pueden escribir: tres numeros, un nombre guardado con `chest`
 * (`store cofre`), o nada. Lo de `nombrado` es para no repetir en el feed unas
 * coordenadas que se acaban de ver escribir, y avisar si en cambio ha habido
 * que mirar un nombre.
 */
type Destino =
	| { ok: true; xyz: Coord | null; de: string; nombrado: boolean }
	| { ok: false; motivo: string }

const parseTarget = (args: string): Destino => {
	const partes = args.trim().split(/\s+/).filter(Boolean)
	// Sin destino: cada bot usa el cofre de su propia config, y el mod lo
	// resuelve. Aqui no hay nada que traducir.
	if (partes.length === 0) {
		return { ok: true, xyz: null, de: 'cofre de la config de cada instancia', nombrado: true }
	}
	// Un solo token: casi siempre un nombre, porque `x y z` son tres.
	if (partes.length === 1) {
		const nombre = partes[0]!
		const xyz = chestDe(nombre)
		if (xyz) return { ok: true, xyz, de: `'${nombre}' = ${xyz.x} ${xyz.y} ${xyz.z}`, nombrado: true }
		const nombres = listarChests()
			.map(([n]) => n)
			.join(', ')
		return {
			ok: false,
			motivo: nombres
				? `no hay ningun cofre con el nombre '${nombre}' (los guardados: ${nombres})`
				: `no hay ningun cofre con el nombre '${nombre}' y aun no hay ninguno guardado ` +
					`(chest <nombre> <x y z>)`,
		}
	}
	if (partes.length !== 3) return { ok: false, motivo: USO }
	const [x, y, z] = partes.map(Number)
	if (![x, y, z].every(Number.isInteger)) return { ok: false, motivo: USO }
	return { ok: true, xyz: { x: x!, y: y!, z: z! }, de: `${x} ${y} ${z}`, nombrado: false }
}

/**
 * Manda la orden y sigue el estado hasta que acaba.
 *
 * El tiempo maximo es propio de cada orden: colocar un cofre y abrirlo son unos
 * segundos, pero caminar puede tardar un minuto. Pasado ese tiempo se avisa y se
 * deja de preguntar: el mod sigue Working igual y su estado se puede volver a
 * mirar con `GET /store` (el panel lo enseña la proxima vez que se pulse `r`).
 */
const start = async (
	target: Target,
	route: string,
	body: unknown,
	timeoutMs: number,
): Promise<number> => {
	const reply: Reply = await call(target, 'POST', route, body, 5000)
	if (!reply.ok) {
		const verb = route === '/store/now' ? 'storenow' : 'store'
		push(
			`  ${paint(target.name.padEnd(6), c.blue)} ${paint('err ', c.red)} ` +
				`${verb}: ${trimBody(reply.body)}`,
		)
		return 1
	}

	const verb = route === '/store/now' ? 'storenow' : 'store'
	const tag = paint(target.name.padEnd(6), c.blue)
	push(`${paint(verb, c.bold)} -> ${tag} ${paint('aceptado (202)', c.dim)}; esperando...`)

	const deadline = Date.now() + timeoutMs
	let anterior = ''
	while (Date.now() < deadline) {
		await new Promise(resolve => setTimeout(resolve, 700))
		const read = await call(target, 'GET', '/store', undefined, 5000)
		if (!read.ok) continue
		let data: StoreState
		try {
			const parsed = (JSON.parse(read.body) as { data?: unknown }).data
			if (!isStoreState(parsed)) continue
			data = parsed
		} catch {
			continue
		}

		const linea = describe(data)
		// Solo cuando cambia: si no, el feed se llena de la misma linea.
		if (linea !== anterior) {
			anterior = linea
			const tono = data.state === 'failed' ? c.red : data.state === 'done' ? c.green : c.dim
			push(`  ${tag} ${paint(linea, tono)}`)
		}
		if (TERMINALES.has(data.state)) return data.state === 'failed' ? 1 : 0
	}

	push(
		`  ${tag} ${paint('sigue sin acabar', c.yellow)} ` +
			`(agotados los ${Math.round(timeoutMs / 1000)}s de espera; el mod sigue a lo suyo)`,
	)
	return 0
}

/** `store [x y z]`: vuelca el inventario en el cofre de esas coordenadas. */
export const store = async (args: string, targets: Target[]): Promise<number> => {
	if (targets.length === 0) {
		warnLine('store: no hay instancias seleccionadas')
		return 1
	}
	const destino = parseTarget(args)
	if (!destino.ok) {
		warnLine(`store: ${destino.motivo}`)
		return 1
	}
	// Solo si no se han escrito las coordenadas: escribirlas aqui seria repetir
	// la linea de arriba, pero de donde salen (un nombre, la config) no se ve.
	if (destino.nombrado) push(`store: ${destino.de}`)
	let failures = 0
	// En paralelo: cada bot va a lo suyo y no espera a los demas.
	const results = await Promise.all(
		targets.map(async t => [t, await start(t, '/store', destino.xyz ?? {}, 150_000)] as const),
	)
	for (const [, code] of results) failures += code
	return failures
}

/** `storenow`: coloca un cofre donde este el bot y lo deja ahi. */
export const storeNow = async (_args: string, targets: Target[]): Promise<number> => {
	if (targets.length === 0) {
		warnLine('storenow: no hay instancias seleccionadas')
		return 1
	}
	const results = await Promise.all(
		targets.map(async t => [t, await start(t, '/store/now', {}, 30_000)] as const),
	)
	return results.reduce((total, [, code]) => total + code, 0)
}
