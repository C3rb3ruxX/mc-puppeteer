/**
 * types.ts — los tipos que se comparten entre los modulos del panel.
 */

export type Target = {
	name: string
	host: string
	port: number
	token: string
	dir: string
	selected: boolean
	/** Ultimo /status conocido. */
	status: Record<string, unknown> | null
	/** Ultimo /inventory conocido del bot. */
	inventory: ItemInfo[] | null
	/** Texto de error del ultimo /inventory, si lo hubo. */
	inventoryError: string | null
	latencyMs: number | null
	up: boolean
	/** Marca del ultimo mensaje de chat ya pintado, para no repetir. */
	lastChat: number
}

export type ItemInfo = {
	id: string
	name: string
	count: number
	slot: number
}

export type Options = {
	registry: string | null
	/** Fichero de los cofres con nombre; `null` = el de por defecto. */
	chests: string | null
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
export type Key = { name: string; char: string }

export type Reply = { ok: boolean; body: string }

/** Cada orden del panel: recibe el resto del texto y las instancias destino. */
export type Handler = (args: string, targets: Target[]) => Promise<number | void>
