/**
 * ansi.ts — colores, anchos y barras.
 *
 * Todo lo que pinta caracteres en el terminal pasa por aqui, para que el ancho
 * que se calcula sea el ancho *visible*: los codigos de color ocupan cero
 * columnas y si se cuentan como texto las celdas se descuadran.
 */

let color = true

export const setColor = (value: boolean): void => {
	color = value
}

export const c = {
	reset: '\x1b[0m',
	bold: '\x1b[1m',
	dim: '\x1b[2m',
	red: '\x1b[31m',
	green: '\x1b[32m',
	yellow: '\x1b[33m',
	blue: '\x1b[36m',
	grey: '\x1b[90m',
	/**
	 * Los marcos de las cajas. No es `grey` a proposito: el gris (ANSI 90) lo
	 * pintan de negro algunos temas de terminal, y un marco negro sobre fondo
	 * negro no se ve. El azul claro se distingue siempre del texto.
	 */
	frame: '\x1b[94m',
}

export const paint = (text: string, ...styles: string[]): string =>
	color && text !== '' ? `${styles.join('')}${text}${c.reset}` : text

const ANSI = /\x1b\[[0-9;]*m/g

/** Quita los codigos de color (el log del juego viene con los suyos). */
export const stripAnsi = (text: string): string => text.replace(/\x1b\[[0-9;]*[A-Za-z]/g, '')

/** Ancho en pantalla: los codigos de color no ocupan sitio. */
export const visible = (text: string): number => text.replace(ANSI, '').length

/** Rellena o recorta hasta `width` caracteres visibles. */
export const pad = (text: string, width: number): string => {
	const len = visible(text)
	if (len > width) return `${stripColorCodes(text).slice(0, Math.max(0, width - 1))}…`
	return text + ' '.repeat(width - len)
}

const stripColorCodes = (text: string): string => text.replace(ANSI, '')

/**
 * Barra de `size` caracteres, coloreada segun cuanto queda.
 *
 * `tono` fija el color y salta el del nivel: la barra de comida va siempre en
 * amarillo y la de vida en verde/amarillo/rojo, para que las dos se distingan
 * de un vistazo aunque las dos esten llenas.
 */
export const bar = (value: unknown, max: unknown, size = 6, tono?: string): string => {
	const v = Number(value)
	const m = Number(max)
	if (!Number.isFinite(v) || !Number.isFinite(m) || m <= 0) return paint('-'.repeat(size), c.dim)
	const ratio = Math.max(0, Math.min(1, v / m))
	const filled = Math.round(ratio * size)
	const color = tono ?? (ratio <= 0.25 ? c.red : ratio <= 0.5 ? c.yellow : c.green)
	return paint('█'.repeat(filled), color) + paint('░'.repeat(size - filled), c.dim)
}

/** Token de una linea: una secuencia de escape entera, o un caracter. */
const TOKEN = /\x1b\[[0-9;]*[A-Za-z]|[\s\S]/g

/**
 * Parte un texto en lineas de `width` columnas visibles, sin cortar una
 * secuencia de escape por la mitad y partiendo por espacios cuando se puede.
 *
 * El feed va en una columna estrecha, asi que casi todo el chat necesita mas de
 * una linea. Cortarlo con el `…` de `pad` perderia el final del mensaje, que es
 * justo la parte interesante.
 */
export const wrap = (text: string, width: number): string[] => {
	const out: string[] = []
	for (const logica of text.split('\n')) {
		const tokens = logica.match(TOKEN) ?? []
		if (visible(logica) <= width) {
			out.push(logica)
			continue
		}

		let actual = ''
		let vis = 0
		let ultimoEspacio = -1
		for (const token of tokens) {
			const escape = token.startsWith('\x1b')
			if (!escape && vis >= width) {
				// Corte por el ultimo espacio si lo hubo; si no, a lo bruto.
				const sobra = ultimoEspacio > 0 ? actual.slice(ultimoEspacio + 1) : ''
				out.push(ultimoEspacio > 0 ? actual.slice(0, ultimoEspacio) : actual)
				actual = escape ? '' : sobra
				vis = visible(sobra)
				ultimoEspacio = -1
			}
			if (!escape && token === ' ') ultimoEspacio = actual.length
			actual += token
			if (!escape) vis++
		}
		if (actual !== '' || out.length === 0) out.push(actual)
	}
	return out
}

/** Numero alineado a la derecha; `-` si no viene o no es un numero. */
export const num = (value: unknown, size: number): string => {
	if (value === null || value === undefined) return '-'.padStart(size)
	const n = Number(value)
	if (!Number.isFinite(n)) return '-'.padStart(size)
	const text = String(Math.round(n * 10) / 10)
	return text.length >= size ? text.slice(0, size) : text.padStart(size)
}

/** `minecraft:overworld` -> `sobre`; en el panel no cabe la ruta entera. */
export const shortDim = (dimension: unknown): string => {
	const text = String(dimension ?? '-')
	if (text.includes(':')) {
		const last = text.split(':').pop()!
		if (last === 'overworld') return 'sobre'
		if (last === 'the_nether') return 'nether'
		return last
	}
	return text
}

export const clock = (): string => new Date().toLocaleTimeString('es-ES', { hour12: false })
