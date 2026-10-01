/**
 * targets.ts — como se descubren las instancias y a quien va cada orden.
 *
 * Hay dos fuentes: el registro que escribe `run-instances.ts` (trae nombre y
 * carpeta del log) y un barrido de puertos por si se arrancaron a mano. Se
 * juntan las dos y se descartan las que ya estaban.
 */

import { existsSync, readFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { healthy } from './api.ts'
import { aplicaAsignados } from './switch.ts'
import { options, warnLine } from './state.ts'
import type { Target } from './types.ts'

const blank = (name: string, host: string, port: number): Target => ({
	name,
	host,
	port,
	token: options.token ?? '',
	dir: '',
	selected: true,
	status: null,
	inventory: null,
	inventoryError: null,
	latencyMs: null,
	up: false,
	lastChat: 0,
	cofre: null,
})

export const readRegistry = (projectDir: string): Target[] => {
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
		...blank(String(raw.name ?? `inst${i + 1}`), String(raw.host ?? options.host), Number(raw.port ?? 0)),
		token: String(raw.token ?? options.token ?? ''),
		dir: String(raw.gameDir ?? ''),
	}))
}

/** Si no hay registro, se buscan clientes vivos en un rango de puertos. */
const scanPorts = async (): Promise<Target[]> => {
	const found: Target[] = []
	for (let port = options.scanFrom; port <= options.scanTo; port++) {
		const started = Date.now()
		if (await healthy(options.host, port, '')) {
			const target = blank(`:${port}`, options.host, port)
			target.latencyMs = Date.now() - started
			target.up = true
			found.push(target)
		}
	}
	return found
}

export const discover = async (projectDir: string): Promise<Target[]> => {
	const fromRegistry = readRegistry(projectDir)
	const scanned = await scanPorts()
	if (fromRegistry.length === 0) return scanned
	const known = new Set(fromRegistry.map(t => `${t.host}:${t.port}`))
	for (const extra of scanned) {
		if (!known.has(`${extra.host}:${extra.port}`)) fromRegistry.push(extra)
	}
	return fromRegistry
}

/** Anade las que falten; de las que ya estaban solo se refresca el token. */
export const mergeTargets = (current: Target[], found: Target[]): void => {
	for (const target of found) {
		const existing = current.find(t => t.host === target.host && t.port === target.port)
		if (existing) {
			if (target.token) existing.token = target.token
			continue
		}
		current.push(target)
	}
	// El cofre del modo switch se refresca para todas: se puede haber cambiado en
	// la config desde la ultima vez, y al panel no le vale lo que se quedo en
	// memoria. En una sola pasada, que leer el fichero por instancia es leerlo
	// diez veces para nada.
	aplicaAsignados(current)
}

/** `@all`, `@1,3`, `@mc2`... -> que instancias entran. */
export const resolveTargets = (input: string, targets: Target[]): Target[] => {
	const match = /^\s*@([\w,]+)\s+/.exec(input)
	if (!match) return targets.filter(t => t.selected)
	const spec = match[1]
	if (spec === 'all') return targets
	const wanted = spec.split(',')
	return targets.filter(t => wanted.includes(t.name) || wanted.includes(String(targets.indexOf(t) + 1)))
}

export const stripTarget = (input: string): string => input.replace(/^\s*@[\w,]+\s+/, '')
