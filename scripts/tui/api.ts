/**
 * api.ts — las llamadas HTTP al mod de cada instancia.
 *
 * El mod escucha en `http://host:puerto/puppeteer/...` y devuelve siempre
 * `{ok, data}` o `{ok, error}`. Aqui solo se traduce eso a algo que el panel
 * pueda pintar, con tiempo limite para que una instancia colgada no bloquee el
 * refresco de las demas.
 */

import { c, paint } from './ansi.ts'
import { push, warnLine } from './state.ts'
import type { Reply, Target } from './types.ts'

/** Como se ve la respuesta en el feed: el dato, o el error si lo hubo. */
export const trimBody = (body: string): string => {
	try {
		const json = JSON.parse(body) as { ok?: boolean; data?: unknown; error?: { message?: string } }
		if (json.ok) return JSON.stringify(json.data).slice(0, 160)
		return json.error?.message ?? JSON.stringify(json).slice(0, 160)
	} catch {
		return body.slice(0, 160)
	}
}

/** Una llamada a una instancia. Nunca lanza: el error va en `ok: false`. */
export const call = async (
	target: Target,
	method: 'GET' | 'POST',
	route: string,
	body?: unknown,
	timeoutMs = 5000,
): Promise<Reply> => {
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

/** `/health` no toca el juego: sirve para saber si el proceso sigue vivo. */
export const healthy = async (host: string, port: number, token: string): Promise<boolean> => {
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

/** Manda la misma peticion a varias instancias y recoge los resultados. */
export const broadcast = async (
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
