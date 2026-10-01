/**
 * inventory.ts — el panel de inventario: que llevan los bots, de mas a menos.
 *
 * Por defecto es la **suma de todas las instancias**: lo que se quiere ver de un
 * vistazo es cuanto hay entre todos. Con `focus <n>` se mira una sola, que es
 * cuando hace falta saber de quien es cada cosa.
 *
 * El mod expone `GET /inventory` con una entrada por ranura ocupada; aqui se
 * suma por tipo de item y se ordena por cantidad. Las barras van escaladas al
 * item mas numeroso de lo que se esta mirando, para que se comparen de un vistazo.
 */

import { bar, c, paint, pad } from './ansi.ts'
import { call } from './api.ts'
import type { ItemInfo, Target } from './types.ts'

/** Una fila ya sumada por tipo de item. */
export type ItemRow = { id: string; name: string; count: number }

/** Suma las ranuras por tipo y las deja de mas a menos. */
export const groupItems = (items: ItemInfo[]): ItemRow[] => {
	const grouped = new Map<string, ItemRow>()
	for (const item of items) {
		const id = String(item.id ?? '?')
		const count = Number(item.count) || 0
		const prev = grouped.get(id)
		if (prev) prev.count += count
		else grouped.set(id, { id, name: String(item.name ?? ''), count })
	}
	return [...grouped.values()].sort((a, b) => b.count - a.count)
}

/**
 * Suma los inventarios de varias instancias por tipo de item. Los ids se
 * combinan porque es la misma partida para todos, asi que dos bots con 60 de
 * roble son 120 de roble; si interesa ver quien lleva cada cosa, para eso esta
 * `focus`.
 */
export const sumInventories = (targets: Target[]): ItemRow[] => {
	const grouped = new Map<string, ItemRow>()
	for (const target of targets) {
		for (const item of target.inventory ?? []) {
			const id = String(item.id ?? '?')
			const count = Number(item.count) || 0
			const prev = grouped.get(id)
			if (prev) prev.count += count
			else grouped.set(id, { id, name: String(item.name ?? ''), count })
		}
	}
	return [...grouped.values()].sort((a, b) => b.count - a.count)
}

/** Lee el inventario de una instancia y lo deja en el target para dibujarlo. */
export const fetchInventory = async (target: Target): Promise<void> => {
	const reply = await call(target, 'GET', '/inventory', undefined, 3000)
	if (!reply.ok) {
		target.inventory = null
		target.inventoryError = reply.body
		return
	}
	try {
		const data = (JSON.parse(reply.body) as { data?: { items?: ItemInfo[] } }).data
		target.inventory = data?.items ?? []
		target.inventoryError = null
	} catch {
		target.inventory = null
		target.inventoryError = 'respuesta ilegible'
	}
}

/**
 * Las lineas del panel de inventario, ya con la anchura del panel.
 *
 * `focus` es la instancia a la que se mira, o `null` para la suma de todas las
 * que estan vivas. Las caidas se tratan aparte: si ninguna contesta sale el
 * error, y si contestan algunas se suman esas y se avisa de quantas no.
 */
export const renderInventory = (
	targets: Target[],
	focus: Target | null,
	width: number,
	height: number,
): string[] => {
	const vivas = targets.filter(t => t.up)
	if (vivas.length === 0) return [paint('  sin instancias vivas', c.dim)]

	// Sin foco se suma todo lo vivo; con foco, solo esa instancia.
	const espejo = focus ? [focus] : vivas
	const conDatos = espejo.filter(t => t.inventory !== null)
	const roto = espejo.find(t => t.inventoryError !== null)

	if (conDatos.length === 0) {
		const detalle = (roto?.inventoryError ?? 'sin inventario')
			.replace(/\s+/g, ' ')
			.slice(0, width - 8)
		const donde = focus ? ` en el mod de ${focus.name}` : ' en el mod'
		return [
			paint('  sin inventario', c.yellow),
			`  ${paint(detalle, c.dim)}`,
			paint(`  (falta el endpoint /inventory${donde})`, c.dim),
		]
	}

	const quien = focus ? focus.name : `${conDatos.length} bot(s)`
	const rows = sumInventories(conDatos)
	if (rows.length === 0) {
		return [paint(`  ${quien}: inventario vacio`, c.dim)]
	}

	const units = rows.reduce((sum, row) => sum + row.count, 0)
	const top = rows[0]!.count
	const nameW = Math.max(10, Math.min(20, width - 22))
	// Tope de la barra: en una zona ancha, una barra de 70 caracteres solo empuja
	// el nombre a la izquierda y no aporta mas informacion que una de 28.
	const barW = Math.max(4, Math.min(28, width - nameW - 11))

	const lines: string[] = [
		paint(`  ${rows.length} tipo(s), ${units} unidad(es)`, c.dim),
	]
	// Solo en la vista global: cuantos bots se han sumado y quantos no han
	// contestado, para que una cifra incompleta no parezca la real.
	if (!focus) {
		const extras: string[] = [`${conDatos.length} bots`]
		if (conDatos.length < vivas.length) extras.push(`${vivas.length - conDatos.length} sin datos`)
		lines.push(paint(`  ${extras.join(' · ')}`, c.dim))
	}

	const room = Math.max(1, height - lines.length - 1)
	for (const row of rows.slice(0, room)) {
		// Cantidad a la izquierda (como se lee de un parte de recogida), nombre y barra.
		lines.push(
			`  ${pad(String(row.count), 5)} ${paint(pad(row.name || row.id, nameW), c.blue)} ` +
				`${bar(row.count, top, barW)}`,
		)
	}
	if (rows.length > room) {
		lines.push(paint(`  ... y ${rows.length - room} tipo(s) mas (/items)`, c.dim))
	}
	while (lines.length < height) lines.push('')
	return lines.slice(0, height).map(line => pad(line, width))
}
