/**
 * inventory.ts — el panel de la derecha: que lleva el bot, de mas a menos.
 *
 * El mod expone `GET /inventory` con una entrada por ranura ocupada; aqui se
 * suma por tipo de item y se ordena por cantidad. Las barras van escaladas al
 * item mas numeroso de esa instancia, para que se comparen de un vistazo.
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

/** Las lineas del panel de inventario, ya con la anchura del panel. */
export const renderInventory = (
	target: Target | null,
	width: number,
	height: number,
): string[] => {
	if (!target) return [paint('  sin instancias', c.dim)]

	if (target.inventoryError !== null) {
		const detalle = target.inventoryError
			.replace(/\s+/g, ' ')
			.slice(0, width - 8)
		return [
			paint('  sin inventario', c.yellow),
			`  ${paint(detalle, c.dim)}`,
			paint(`  (falta el endpoint /inventory en el mod de ${target.name})`, c.dim),
		]
	}
	if (target.inventory === null) return [paint('  leyendo inventario...', c.dim)]

	const rows = groupItems(target.inventory)
	if (rows.length === 0) {
		return [paint(`  ${target.name}: inventario vacio`, c.dim)]
	}

	const units = rows.reduce((sum, row) => sum + row.count, 0)
	const top = rows[0]!.count
	const nameW = Math.max(10, Math.min(20, width - 22))
	const barW = Math.max(4, width - nameW - 11)

	const lines: string[] = [
		paint(`  ${rows.length} tipo(s), ${units} unidad(es)`, c.dim),
	]
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
