/**
 * chests.ts — los cofres con nombre.
 *
 * Escribir `store 10 -60 4` cada vez que hay que guardar el inventario es un
 * fallo esperando: las coordenadas casi nunca son las mismas. Aqui se guardan
 * con un nombre, `store cofre`, y el panel las traduce a `x y z` antes de
 * mandarle la peticion al mod. El mod no se entera de nada: para el sigue
 * siendo `POST /store` con unas coordenadas, sin nombres ni alias.
 *
 * El fichero es `scripts/.tui-config.json`, al lado del codigo, y no dentro de
 * `run-instances/` porque ese esta en `.gitignore`: la lista de cofres es cosa
 * del proyecto y conviene que viaje con el. Con `--chests` se apunta a otro.
 *
 * ```
 * { "chests": { "cofre": { "x": 10, "y": -60, "z": 4 } } }
 * ```
 */

import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { options, warnLine } from './state.ts'

/** Una terna de coordenadas, tal y como la entiende `POST /store`. */
export type Coord = { x: number; y: number; z: number }

/** El fichero donde viven, o el que se haya pasado con `--chests`. */
export const rutaChests = (): string =>
	options.chests
		? resolve(options.chests)
		: resolve(dirname(fileURLToPath(import.meta.url)), '..', '.tui-config.json')

/**
 * Como puede ser un nombre.
 *
 * Sin espacios, porque `store <nombre>` es una sola palabra; sin signos raros,
 * porque el nombre va al feed y a la consola; y no un numero suelto, para que
 * `store 10` no se confunda con unas coordenadas a medio escribir.
 */
const NOMBRE = /^[\w.-]{1,32}$/

export const nombreValido = (nombre: string): boolean => NOMBRE.test(nombre) && !/^-?\d+$/.test(nombre)

const entero = (value: unknown): value is number => typeof value === 'number' && Number.isInteger(value)

const coordValida = (value: unknown): value is Coord => {
	const data = value as Partial<Coord> | null
	return data != null && entero(data.x) && entero(data.y) && entero(data.z)
}

/**
 * Los cofres del fichero, por nombre.
 *
 * Tolera lo que se encuentre: si el JSON esta roto o una entrada no trae las
 * tres coordenadas enteras, avisa y sigue con las demas, porque un cofre mal
 * escrito no puede dejar sin panel a nadie.
 *
 * Marca el fichero como ilegible cuando el JSON no cuadra, para que [ponerChest]
 * no lo pise: escribir encima de un fichero que no se ha podido leer deja al
 * usuario sin los cofres que tenia y sin ningun aviso de por que.
 */
export const leerChests = (): Record<string, Coord> => {
	const path = rutaChests()
	if (!existsSync(path)) {
		roto = false
		return {}
	}
	let parsed: { chests?: Record<string, unknown> }
	try {
		parsed = JSON.parse(readFileSync(path, 'utf8')) as typeof parsed
	} catch (e) {
		roto = true
		warnLine(`chests: no se ha podido leer ${path} (${(e as Error).message}); se sigue sin cofres`)
		return {}
	}
	roto = false
	const salida: Record<string, Coord> = {}
	// Un `chests` que no sea un objeto sale vacio de `Object.entries`, sin fallo.
	for (const [nombre, valor] of Object.entries(parsed.chests ?? {})) {
		if (coordValida(valor)) salida[nombre] = { x: valor.x, y: valor.y, z: valor.z }
		else warnLine(`chests: '${nombre}' no tiene x, y, z enteros; se ignora`)
	}
	return salida
}

/** Si la ultima [leerChests] se tropezo con un JSON que no se podia entender. */
let roto = false
/** Por que se sigue sin poder escribir, o `null` si no hay nada que lo impida. */
export const motivoNoEscribible = (): string | null =>
	roto ? `el fichero esta roto; arreglalo o borralo (${rutaChests()})` : null

/** Escribe por temporal + rename: si el panel muere a medias no queda un config corrupto. */
const escribirChests = (chests: Record<string, Coord>): void => {
	const path = rutaChests()
	mkdirSync(dirname(path), { recursive: true })
	const tmp = `${path}.tmp`
	writeFileSync(tmp, `${JSON.stringify({ chests }, null, '\t')}\n`, 'utf8')
	renameSync(tmp, path)
}

/** El cofre de ese nombre, o `null` si no lo hay. */
export const chestDe = (nombre: string): Coord | null => leerChests()[nombre] ?? null

/**
 * Guarda un cofre con nombre. `false` si no se ha escrito, con el motivo ya avisado.
 *
 * Se reescribe el fichero entero en vez de tocar una linea: son cuatro o cinco
 * entradas y asi no hay que llevar la cuenta de comas y llaves.
 */
export const ponerChest = (nombre: string, xyz: Coord): boolean => {
	const chests = leerChests()
	if (motivoNoEscribible()) return false
	chests[nombre] = xyz
	escribirChests(chests)
	return true
}

/**
 * Quita el cofre de ese nombre.
 *
 * Devuelve `'no estaba'`, `'roto'` o `null` para que sea quien llama el que
 * elija el aviso: aqui no se avisa de nada, que se avisa tres veces ya.
 */
export const quitarChest = (nombre: string): 'borrado' | 'no estaba' | 'roto' => {
	const chests = leerChests()
	if (motivoNoEscribible()) return 'roto'
	if (!(nombre in chests)) return 'no estaba'
	delete chests[nombre]
	escribirChests(chests)
	return 'borrado'
}

/** Los cofres, ordenados por nombre, que es como se listan. */
export const listarChests = (): Array<[string, Coord]> =>
	Object.entries(leerChests()).sort(([a], [b]) => a.localeCompare(b, 'es'))
