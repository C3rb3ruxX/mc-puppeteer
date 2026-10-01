/**
 * config.ts — el fichero de configuracion del panel, `scripts/.tui-config.json`.
 *
 * Son dos cosas y las dos se guardan en el mismo fichero:
 *
 * - Los **cofres con nombre**, para no tener que escribir `store 10 -60 4` cada
 *   vez. `store cofre` los traduce a coordenadas. El mod no se entera: para el
 *   sigue siendo `POST /store` con unas coordenadas, sin nombres ni alias.
 * - El **modo switch**: a que hora del mundo se considera que anochece y que
 *   cofre le toca a cada bot. Ver `switch.ts`.
 *
 * Vive al lado del codigo y no dentro de `run-instances/` porque ese esta en
 * `.gitignore`: esto es cosa del proyecto y conviene que viaje con el. Con
 * `--chests` se apunta a otro fichero.
 *
 * ```
 * {
 *   "chests": { "cofre": { "x": 10, "y": -60, "z": 4 } },
 *   "switch": { "nightAt": 13000, "asignados": { "mc1": "cofre" } }
 * }
 * ```
 *
 * **Un solo modulo escribe este fichero, y es este.** Con dos, cada uno
 * reescribiendo lo suyo entero, uno se comia la seccion del otro sin que se
 * note: por eso el que sabe de cofres y el que sabe de switch estan aqui
 * juntos y no repartidos.
 */

import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { options, warnLine } from './state.ts'

/** Una terna de coordenadas, tal y como la entiende `POST /store`. */
export type Coord = { x: number; y: number; z: number }

/**
 * Hora del mundo a la que empieza a hacerse de noche, si no se dice otra cosa.
 *
 * `13000` es el valor de vanilla: el atardecer es a las `12000` y a las `13000`
 * ya sale el sol. Es el unico umbral que el juego usa de verdad para poner de
 * noche los bichos, asi que es el que menos sorprende.
 */
export const NIGHT_AT_POR_DEFECTO = 13000

/**
 * El rango que se admite para esa hora.
 *
 * Ni `0` ni `24000` valen: en los dos casos el dia entero seria "noche" y no
 * quedaria ninguna ventana de dia a la que volver a disparar, asi que el
 * guardado no saltaria nunca sin decir por que. De ahi el `1` y el `23999`.
 */
export const NIGHT_AT_MIN = 1
export const NIGHT_AT_MAX = 23999

/** Lo que hay en el fichero, ya limpio y con sus valores por defecto. */
export type Config = {
	chests: Record<string, Coord>
	/** A que hora del mundo (0-24000) se dispara el guardado automatico. */
	nightAt: number
	/** Que cofre le toca a cada bot, por nombre de instancia. */
	asignados: Record<string, string>
}

/** El fichero donde viven, o el que se haya pasado con `--chests`. */
export const rutaConfig = (): string =>
	options.chests
		? resolve(options.chests)
		: resolve(dirname(fileURLToPath(import.meta.url)), '..', '.tui-config.json')

/**
 * Como puede ser un nombre.
 *
 * Sin espacios, porque `store <nombre>` es una sola palabra; sin signos raros,
 * porque el nombre va al feed y a la consola; y no un numero suelto, para que
 * `store 10` no se confunda con unas coordenadas a medio escribir ni con la
 * hora del modo switch.
 */
const NOMBRE = /^[\w.-]{1,32}$/

export const nombreValido = (nombre: string): boolean => NOMBRE.test(nombre) && !/^-?\d+$/.test(nombre)

const entero = (value: unknown): value is number => typeof value === 'number' && Number.isInteger(value)

const coordValida = (value: unknown): value is Coord => {
	const data = value as Partial<Coord> | null
	return data != null && entero(data.x) && entero(data.y) && entero(data.z)
}

/** Si la ultima [leerConfig] se tropezo con un JSON que no se podia entender. */
let roto = false

/** Por que se sigue sin poder escribir, o `null` si no hay nada que lo impida. */
export const motivoNoEscribible = (): string | null =>
	roto ? `el fichero esta roto; arreglalo o borralo (${rutaConfig()})` : null

/**
 * El fichero entero, ya limpio.
 *
 * Tolera lo que se encuentre: si el JSON esta roto o algo no tiene la forma
 * que toca, avisa y sigue con lo demas, porque un dato mal escrito no puede
 * dejar sin panel a nadie. Y marca el fichero como ilegible cuando el JSON no
 * ni siquiera cuadra, para que no se escriba encima: eso dejaria al usuario sin
 * sus cofres y sin ningun aviso de por que.
 */
export const leerConfig = (): Config => {
	const path = rutaConfig()
	const vacio: Config = { chests: {}, nightAt: NIGHT_AT_POR_DEFECTO, asignados: {} }
	if (!existsSync(path)) {
		roto = false
		return vacio
	}
	let parsed: Record<string, unknown>
	try {
		parsed = JSON.parse(readFileSync(path, 'utf8')) as Record<string, unknown>
	} catch (e) {
		roto = true
		warnLine(`config: no se ha podido leer ${path} (${(e as Error).message}); se sigue con los valores por defecto`)
		return vacio
	}
	roto = false

	// Un `chests` que no sea un objeto sale vacio de `Object.entries`, sin fallo.
	const chests: Record<string, Coord> = {}
	for (const [nombre, valor] of Object.entries((parsed.chests ?? {}) as Record<string, unknown>)) {
		if (coordValida(valor)) chests[nombre] = { x: valor.x, y: valor.y, z: valor.z }
		else warnLine(`config: el cofre '${nombre}' no tiene x, y, z enteros; se ignora`)
	}

	const sw = (parsed.switch ?? {}) as Record<string, unknown>
	const nightAt = entero(sw.nightAt) && sw.nightAt >= NIGHT_AT_MIN && sw.nightAt <= NIGHT_AT_MAX
		? sw.nightAt
		: NIGHT_AT_POR_DEFECTO
	if (entero(sw.nightAt) && nightAt !== sw.nightAt) {
		warnLine(
			`config: 'switch.nightAt' fuera de ${NIGHT_AT_MIN}-${NIGHT_AT_MAX}; se usa ${NIGHT_AT_POR_DEFECTO}`,
		)
	}
	const asignados: Record<string, string> = {}
	for (const [inst, nombre] of Object.entries((sw.asignados ?? {}) as Record<string, unknown>)) {
		if (typeof nombre === 'string' && nombre) asignados[inst] = nombre
	}

	return { chests, nightAt, asignados }
}

/** Escribe por temporal + rename: si el panel muere a medias no queda un config corrupto. */
export const escribirConfig = (config: Config): void => {
	const path = rutaConfig()
	mkdirSync(dirname(path), { recursive: true })
	const tmp = `${path}.tmp`
	const salida: Record<string, unknown> = { chests: config.chests }
	// La seccion `switch` solo se escribe si tiene algo: un `nightAt` de
	// fabrica con las asignaciones vacias es ruido en el fichero.
	if (config.nightAt !== NIGHT_AT_POR_DEFECTO || Object.keys(config.asignados).length > 0) {
		salida.switch = { nightAt: config.nightAt, asignados: config.asignados }
	}
	writeFileSync(tmp, `${JSON.stringify(salida, null, '\t')}\n`, 'utf8')
	renameSync(tmp, path)
}

/** Aplica un cambio y lo guarda. `false` si no se ha escrito (ya avisado). */
const guardar = (cambia: (config: Config) => void): boolean => {
	const config = leerConfig()
	if (motivoNoEscribible()) return false
	cambia(config)
	escribirConfig(config)
	return true
}

// --------------------------------------------------------------------------- cofres

/** El cofre de ese nombre, o `null` si no lo hay. */
export const chestDe = (nombre: string): Coord | null => leerConfig().chests[nombre] ?? null

/** Los cofres, ordenados por nombre, que es como se listan. */
export const listarChests = (): Array<[string, Coord]> =>
	Object.entries(leerConfig().chests).sort(([a], [b]) => a.localeCompare(b, 'es'))

/** Guarda un cofre con nombre. `false` si no se ha escrito, con el motivo ya avisado. */
export const ponerChest = (nombre: string, xyz: Coord): boolean =>
	guardar(config => {
		config.chests[nombre] = xyz
	})

/**
 * Quita el cofre de ese nombre.
 *
 * Devuelve `'borrado'`, `'no estaba'` o `'roto'`, y no avisa de nada: quien
 * llama elige el mensaje, que aqui se avisaria tres veces.
 */
export const quitarChest = (nombre: string): 'borrado' | 'no estaba' | 'roto' => {
	const config = leerConfig()
	if (motivoNoEscribible()) return 'roto'
	if (!(nombre in config.chests)) return 'no estaba'
	delete config.chests[nombre]
	escribirConfig(config)
	return 'borrado'
}

// --------------------------------------------------------------------------- switch

/** La hora del mundo a la que se dispara el guardado automatico. */
export const nightAt = (): number => leerConfig().nightAt

/** Cambia esa hora. `false` si el valor no vale o no se ha podido escribir. */
export const ponerNightAt = (valor: number): boolean => {
	if (!entero(valor) || valor < NIGHT_AT_MIN || valor > NIGHT_AT_MAX) return false
	return guardar(config => {
		config.nightAt = valor
	})
}

/** Que cofre le toca a cada bot, por nombre de instancia. */
export const asignados = (): Record<string, string> => leerConfig().asignados

/**
 * Le asigna un cofre a un bot, o se lo quita si `null`.
 *
 * Va por nombre de instancia y no por indice: el nombre es lo unico que se
 * sabe cuando el panel vuelve a arrancar, y las instancias se numeran al
 * descubrirlas, asi que el mismo bot puede salir en otro numero segun cuales
 * estan levantadas.
 */
export const ponerAsignado = (instancia: string, cofre: string | null): boolean =>
	guardar(config => {
		if (cofre === null) delete config.asignados[instancia]
		else config.asignados[instancia] = cofre
	})
