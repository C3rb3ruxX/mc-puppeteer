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

/** `x y z` del cofre, o `null` si no se han dado. */
const parseTarget = (args: string): { x: number; y: number; z: number } | null | 'invalido' => {
	const parts = args.trim().split(/\s+/)
	if (parts.length === 0) return null
	if (parts.length !== 3) return 'invalido'
	const [x, y, z] = parts.map(Number)
	if (![x, y, z].every(Number.isInteger)) return 'invalido'
	return { x: x!, y: y!, z: z! }
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
	const target = parseTarget(args)
	if (target === 'invalido') {
		warnLine('store: uso -> store [x y z]   (sin coordenadas, usa el cofre de la config)')
		return 1
	}
	let failures = 0
	// En paralelo: cada bot va a lo suyo y no espera a los demas.
	const results = await Promise.all(
		targets.map(async t => [t, await start(t, '/store', target ?? {}, 150_000)] as const),
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
