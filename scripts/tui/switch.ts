/**
 * switch.ts — el modo switch: al anochecer, cada bot va a su cofre.
 *
 * La idea es no tener que vigilar el panel: se le dice a cada bot que cofre es
 * el suyo y a que hora del mundo se considera que ha anochecido, y el panel se
 * encarga solo. Al amanecer se rearma para la noche siguiente.
 *
 * Como se sabe que ha anochecido: por la hora del mundo que manda `GET /status`
 * (`dayTime`), reducida antes a hora del dia con [horaDelDia]. No por el reloj
 * del sistema, que no tiene nada que ver con el dia del mundo: un servidor con
 * la noche puesta a las ocho de la manana seguia siendo de noche a las once. En
 * un mundo sin sol (Nether, End) la cuenta tambien avanza, asi que ahi dispara
 * igual; quien quiera evitarlo puede mirar la `dimension` que ya viene en el
 * mismo `/status`.
 *
 * El disparo es por flanco: se guarda la hora anterior de cada bot y solo se
 * guarda cuando ha pasado de estar de dia a estar de noche. Sin eso, cada
 * refresco durante la noche volveria a mandar un `store`.
 *
 * Al arrancar el panel no se dispara nada aunque ya sea de noche: se toma la
 * hora actual como referencia y se espera a la siguiente puesta de sol. Es lo
 * que evita un volcado sorpresa al abrir el panel de la tarde.
 *
 * Un bot al que ya se le esta guardando no entra: el flanco se consume igualmente
 * y esa noche se queda sin guardar. No se ha visto nunca, porque `store` acaba
 * en un plazo de 150 s y la noche dura mas de diez minutos, pero es lo que
 * pasa si se solapan.
 */

import { c, paint } from './ansi.ts'
import { asignados, chestDe, nightAt } from './config.ts'
import { store } from './store.ts'
import { push } from './state.ts'
import type { Target } from './types.ts'

/** Los ticks que dura un dia entero de Minecraft. */
const TICKS_POR_DIA = 24000

/**
 * La hora del dia a partir del `dayTime` que manda el mod.
 *
 * `dayTime` es el contador de ticks del mundo y **no se reinicia**: cuando un
 * dia llega a `24000`, el siguiente empieza en `24001`, no en `0`. Lo que si da
 * la vuelta es el resto de dividir entre los ticks del dia, y esa es la hora que
 * usa de verdad el juego: `/time query daytime` contesta exactamente
 * `dayTime % 24000`. El `+ TICKS_POR_DIA` es por si el mundo se puso en negativo
 * con `/time set`: en JS el `%` de un numero negativo devuelve negativo.
 */
export const horaDelDia = (dayTime: number): number =>
	((dayTime % TICKS_POR_DIA) + TICKS_POR_DIA) % TICKS_POR_DIA

/** Hora del dia de la ultima comprobacion, por instancia. `null` = sin referencia. */
const anterior = new Map<string, number>()

/** Instancias a las que se les ha mandado guardar y aun no han terminado. */
const ocupadas = new Set<string>()

/**
 * Si un bot puede dispararse ahora mismo.
 *
 * Se comprueba que el cofre asignado **siga existiendo** en la config: si se
 * ha borrado con `chest <n> rm` desde otro sitio, el nombre se queda pegado al
 * bot y sin el no hay nada a donde ir.
 *
 * Y que este en juego, que es lo importante: `store` a un bot en el menu de
 * titulo falla y ademas **desconecta**, asi que un bot que se ha caido no debe
 * disparar nada.
 */
const disparable = (target: Target): boolean =>
	target.up && !ocupadas.has(target.name) && !!target.cofre && target.status?.inWorld === true &&
	chestDe(target.cofre) !== null

/**
 * Lo que hay que hacer en cada refresco.
 *
 * El `store` se suelta sin `await` a proposito: guardar tarda hasta dos minutos
 * y el bucle del panel no puede quedarse parado esperandose. [store] escribe su
 * propio avance en el feed y va solo, asi que aqui solo hace falta soltarlo.
 */
export const revisaNoche = (targets: Target[]): void => {
	const umbral = nightAt()

	for (const target of targets) {
		const bruto = Number(target.status?.dayTime)
		if (!Number.isFinite(bruto)) {
			// Sin hora conocida (bot caido, o un mod viejo que no manda `dayTime`)
			// no se puede decidir nada. Se olvida la referencia para que la
			// siguiente vez se tome la nueva como punto de partida.
			anterior.delete(target.name)
			continue
		}
		const hora = horaDelDia(bruto)
		const antes = anterior.get(target.name)
		anterior.set(target.name, hora)
		// La primera vez que se ve a un bot solo se memoriza su hora: el panel
		// acaba de arrancar y no se quiere disparar a mitad de la noche.
		if (antes === undefined) continue
		if (antes >= umbral || hora < umbral) continue
		// El `cofre` se saca aqui para que el compilador lo estreche a `string`
		// y no haga falta un `!` en la llamada a `store`; `disparable` ya lo
		// comprueba, pero eso no estrecha nada fuera de la funcion.
		const cofre = target.cofre
		if (!cofre || !disparable(target)) continue

		ocupadas.add(target.name)
		anuncia(target, cofre, hora)
		void store(cofre, [target]).then(() => ocupadas.delete(target.name))
	}
}

/**
 * La linea que sale en el feed cuando un bot se guarda solo.
 *
 * La hora va en ticks del dia (`0`-`23999`) y no como reloj: `13:000` no lo lee
 * nadie. Es la misma unidad en la que se escribe `switch <hora>`.
 */
const anuncia = (target: Target, cofre: string, hora: number): void => {
	push(
		`${paint('switch', c.bold)} -> ${paint(target.name.padEnd(6), c.blue)} ` +
			paint(`anochece (${hora}), al cofre '${cofre}'`, c.dim),
	)
}

/** Que cofre tiene asignado cada bot, en una linea por instancia. */
export const describeAsignados = (targets: Target[]): string[] => {
	const conCofre = targets.filter(t => t.cofre)
	if (conCofre.length === 0) return []
	// Las columnas se ajustan al nombre mas largo de los que salen, como en
	// `chest list`: con un ancho fijo, un nombre de 20 caracteres lo descuadra
	// todo. Un nombre de instancia puede llegar a 32 y el de un cofre tambien.
	const anchoInst = Math.max(...conCofre.map(t => t.name.length)) + 2
	const anchoCofre = Math.max(...conCofre.map(t => t.cofre!.length)) + 2
	return conCofre.map(target => {
		const nombre = target.cofre!
		const xyz = chestDe(nombre)
		return (
			`  ${paint(target.name.padEnd(anchoInst), c.blue)} ${nombre.padEnd(anchoCofre)} ` +
			(xyz ? `${xyz.x} ${xyz.y} ${xyz.z}` : paint('(ese nombre ya no esta guardado)', c.red))
		)
	})
}

/**
 * Las asignaciones de la config, aplicadas a unas instancias.
 *
 * Va al descubrir y en cada `/scan`, porque las instancias pueden aparecer con
 * el panel ya en marcha, y tambien al arrancar, para que un cofre asignado en
 * una sesion anterior siga puesto.
 *
 * Por nombre de instancia y no por indice: el nombre es lo unico que se sabe
 * cuando el panel vuelve a arrancar, y las instancias se numeran al
 * descubrirlas, asi que el mismo bot puede salir en otro numero segun cuales
 * estan levantadas.
 */
export const aplicaAsignados = (targets: Target[]): void => {
	const guardados = asignados()
	for (const target of targets) {
		target.cofre = guardados[target.name] ?? null
	}
}
