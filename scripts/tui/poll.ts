/**
 * poll.ts — lo que se pregunta a las instancias en cada refresco.
 *
 * Tres cosas, cada una con su presupuesto de tiempo:
 *   - `/status` entra en el hilo principal del juego, asi que con varios
 *     clientes en la misma maquina puede tardar: se le da poco margen y, si no
 *     llega, se comprueba con `/health`, que no toca el juego.
 *   - el historial de chat se lee sin vaciar el buffer del mod: solo se pintan
 *     los mensajes nuevos, y la marca avanza al final del volcado.
 *   - el inventario se pide solo para la instancia que se esta mirando en el
 *     panel de la derecha; son hasta 41 ranuras y no hace falta en todas.
 */

import { c, paint } from './ansi.ts'
import { call, healthy } from './api.ts'
import { fetchInventory } from './inventory.ts'
import { push, state } from './state.ts'
import type { Target } from './types.ts'

export const refresh = async (targets: Target[]): Promise<void> => {
	await Promise.all(
		targets.map(async target => {
			const started = Date.now()
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
			target.up = await healthy(target.host, target.port, target.token)
			if (!target.up) target.status = null
		}),
	)
}

/** Chat de todas, sin vaciar buffers: solo se pintan los mensajes nuevos. */
export const pullChat = async (targets: Target[]): Promise<void> => {
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
							`  ${paint(target.name.padEnd(6), c.blue)}${kind} ` +
								paint(message.text.slice(0, 150), c.dim),
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

/** Inventario de la instancia enfocada en el panel. */
export const pullInventory = async (): Promise<void> => {
	const focus = state.targets[Math.min(state.focus, Math.max(0, state.targets.length - 1))]
	if (!focus || !focus.up) return
	await fetchInventory(focus)
}
