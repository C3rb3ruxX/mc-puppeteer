package com.bonilla.puente.client

import com.bonilla.puente.ChestTarget
import com.bonilla.puente.PuenteController
import com.bonilla.puente.StorePhase
import com.bonilla.puente.StoreRequest
import com.bonilla.puente.StoreState
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.MultiPlayerGameMode
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import org.slf4j.LoggerFactory

/**
 * Vuelca el inventario del bot en un cofre, colocandolo si hace falta.
 *
 * ## Por que una maquina de estados y no una llamada
 *
 * `POST /store` tiene que responder `202` **al momento**: lo que camina,
 * coloca y abre tarda segundos, y bloquear con eso el hilo HTTP (o el
 * principal) seria justo el problema que motivo todo el arreglo de
 * `baritoneAsync`. Asi que el endpoint deja la peticion en [pendiente] y
 * devuelve; aqui, en el tick, se ejecuta **una fase por llamada**.
 *
 * De ahi sale la regla que no se puede romper: **nada de `Thread.sleep`, ni de
 * `sleepTick`, ni de `future.get()` dentro del tick**. El paso del tiempo se
 * cuenta con ticks ([Trabajo.ticksFase]). Cada tick hace una cantidad de
 * trabajo acotada y vuelve, asi que el juego sigue a su framerate.
 *
 * No hay plazos por tiempo: una fase aguanta lo que haga falta hasta que el
 * paso se completa o falla por un motivo de verdad (sin mundo, sin cofre, sin
 * sitio...). Cortar por reloj desconectaba bots que solo iban despacio.
 *
 * ## Las fases
 *
 * `walking` -> `placing` -> `opening` -> `storing` -> `done`, con `failed` como
 * salida de error. `placing` se salta si en el destino ya hay un cofre, y
 * `storing` se salta si la peticion era de las que solo colocan
 * ([StoreRequest.storeNow]).
 *
 * ## Por que `walking` no va al destino sino a un radio
 *
 * `#goto x y z` de Baritone pone al bot **en** el bloque que se le pide, no
 * cerca. Apuntando al destino, el bot intenta meterse en el hueco donde va el
 * cofre y, como alli no cabe, se coloca un bloque debajo para subirse: ese
 * bloque es justo el de apoyo que necesita el cofre, asi que despues `placing`
 * ya no lo puede poner. Ademas el bot se queda metido en el destino, y
 * [elegirSitio] descarta todo sitio donde quepa el jugador.
 *
 * Asi que `walking` busca un sitio **a un radio** del destino
 * ([puntoDeAproximacion]) y, al llegar, para a Baritone ([entrarEn]). El cofre
 * va igual a las coordenadas exactas que se han pedido, pero se pone desde
 * fuera.
 *
 * ## Lo que se respeta del API de Minecraft (verificado con `javap`)
 *
 * - El clic va al **centro** del bloque ([BlockPos.getCenter]): `stillValid`
 *   descarta en silencio los clics a mas de `blockInteractionRange()+4`, y un
 *   punto en la cara del bloque se queda fuera antes.
 * - `useItemOn` **no hace raycast**: da igual hacia donde mire el jugador, lo
 *   que cuenta es el `BlockHitResult` que se le pasa. Por eso la rotacion no se
 *   toca en ningun sitio de este fichero.
 * - Antes de usar un objeto hay que seleccionar su ranura en **el mismo
 *   tick**: `useItemOn` usa lo que haya en la mano seleccionada ahora.
 * - Colocar **no** abre el menu, y abrir necesita la mano vacia: son dos
 *   `useItemOn` distintos.
 * - `Minecraft.gameMode` (no `jugador.gameMode`) es el `MultiPlayerGameMode`,
 *   y `LocalPlayer.closeContainer()` es el unico cierre que hay: no existe
 *   `gameMode.closeContainer()`.
 *
 * ## Lo que no se puede hacer
 *
 * Un `ChestMenu` solo tiene las ranuras del cofre y las 36 del inventario
 * ([MENU_INVENTARIO]); **no** tiene ranuras de armadura ni de mano
 * secundaria, que son las 36..40 de `Inventory`. Por eso [volcar] recorre
 * 0..35: pasarle una ranura de mas a `handleInventoryMouseClick` haria
 * `slots.get(i)` fuera de rango, y `AbstractContainerMenu.clicked` convierte
 * eso en un crash. La armadura y la mano secundaria se quedan puestas.
 */
object ChestStash {

	private val logger = LoggerFactory.getLogger("mc-puppeteer/cofre")

	/** Peticion esperando a ser atendida. La escribe el hilo HTTP, la lee el tick. */
	@Volatile
	private var pendiente: StoreRequest? = null

	/** Lo que ve `GET /store`. Se publica entero cada vez que cambia. */
	@Volatile
	private var estado: StoreState = StoreState.IDLE

	/** Baritone y desconexion: los aporta el controlador en [registrar]. */
	private var controlador: PuenteController? = null

	private var trabajo: Trabajo? = null

	/**
	 * Conecta la maquina al controlador y la engancha al tick.
	 *
	 * Se llama desde [PuenteClient] al arrancar, **antes** de abrir el puerto
	 * HTTP: asi no hay ventana en la que llegue una peticion sin nadie que la
	 * atienda.
	 */
	fun registrar(controlador: PuenteController) {
		this.controlador = controlador
		ClientTickEvents.END_CLIENT_TICK.register { tick() }
		logger.info("Volcado en cofre disponible: POST /store y /store/now encolan trabajo en el tick.")
	}

	/**
	 * Deja una peticion para el proximo tick y devuelve el estado resultante.
	 *
	 * No ejecuta nada: ni caminar, ni colocar, ni abrir. Por eso el endpoint
	 * puede responder `202` sin esperar a nada.
	 *
	 * El estado que se devuelve es `idle` con el cofre **ya decidido**: el
	 * `202` sale antes de que el tick empiece a trabajar, y un `done` o un
	 * `failed` de una vuelta anterior ya no describiria lo que va a pasar. El
	 * `GET /store` siguiente es el que empieza a moverse por `walking`,
	 * `placing`, ...
	 */
	fun request(peticion: StoreRequest): StoreState {
		pendiente = peticion
		val destino = peticion.target
		estado = if (destino == null) StoreState.IDLE else StoreState(StorePhase.IDLE, destino)
		return estado
	}

	/** Estado actual. Lo lee `GET /store`. */
	fun snapshot(): StoreState = estado

	// ------------------------------------------------------------------ tick

	private fun tick() {
		// 1) ¿Hay peticion nueva? Se atiende aqui, no en el hilo HTTP.
		pendiente?.let { peticion ->
			pendiente = null
			arrancar(peticion)
		}

		val t = trabajo ?: return
		try {
			avanzar(t)
		} catch (e: Throwable) {
			// Un fallo aqui no puede tumbar el juego: se marca `failed` (que
			// ademas desconecta) y el tick siguiente sigue con normalidad.
			logger.error("Fallo inesperado en la maquina del cofre", e)
			fallar(t, "error inesperado en '${t.fase.name.lowercase()}': ${e.message ?: e.javaClass.simpleName}")
		}
	}

	private fun arrancar(peticion: StoreRequest) {
		// Una peticion nueva cancela la anterior: si no, dos volcados se
		// pisan el contenedor abierto y los clicks van a donde no toca.
		if (trabajo != null) {
			logger.info("Llega una peticion nueva de cofre; se abandona la anterior (fase {}).", trabajo!!.fase)
			descartarTrabajo()
		}

		val player = Minecraft.getInstance().player
		if (player == null) {
			estado = StoreState(
				phase = StorePhase.FAILED,
				target = peticion.target,
				reason = "El cliente no esta en ningun mundo: no hay ningun bot al que guardarle el inventario",
			)
			desconectarPorFallo()
			return
		}

		// Un destino `null` solo llega si nadie lo resolvio (ni el cuerpo ni la
		// config dijeron uno): entonces el cofre va donde este el bot ahora.
		val destino = peticion.target?.let { BlockPos(it.x, it.y, it.z) } ?: bloqueDeLosPies(player)
		val t = Trabajo(destino, peticion.storeNow)
		trabajo = t
		entrarEn(t, StorePhase.WALKING)
		logger.info("Peticion de cofre en {} ({}).", t.destino, if (t.storeNow) "solo colocar" else "volcar")
	}

	/** El bloque que pisa el jugador: en el que cabria un cofre "aqui mismo". */
	private fun bloqueDeLosPies(player: LocalPlayer): BlockPos =
		BlockPos.containing(player.x, player.y, player.z)

	// ---------------------------------------------------------------- fases

	private fun avanzar(t: Trabajo) {
		val mc = Minecraft.getInstance()
		val level = mc.level ?: return fallar(t, "El mundo se ha descargado a mitad del volcado")
		val player = mc.player
			?: return fallar(t, "El cliente se ha quedado sin jugador a mitad del volcado")
		if (!player.isAlive) return fallar(t, "El bot esta muerto a mitad del volcado")
		// El `MultiPlayerGameMode` cuelga de `Minecraft`, no del jugador.
		val gameMode = mc.gameMode
			?: return fallar(t, "El cliente no tiene modo de juego: no hay sesion con la que interactuar")

		when (t.fase) {
			// A menos de ALCANCE ya no se camina: se interactua desde donde este.
			StorePhase.WALKING -> caminar(t, level, player)
			StorePhase.PLACING -> colocar(t, level, player, gameMode)
			StorePhase.OPENING -> abrir(t, player, gameMode)
			StorePhase.STORING -> volcar(t, player, gameMode)
			// Terminales: `done` y `failed` se limpian en `terminar`/`fallar`.
			StorePhase.IDLE, StorePhase.DONE, StorePhase.FAILED -> return
		}

		// La fase puede haber terminado el trabajo por su cuenta (al entrar en
		// `colocar` sin sitio se llama a `fallar`, que deja `trabajo = null`).
		// Sin esta comprobacion, `publicar` volveria a escribir encima del
		// estado terminal que se acaba de fijar.
		if (trabajo !== t) return

		// El contador de la fase avanza aqui, al final: hay fases que usan
		// `ticksFase` para lo que solo pasa en su **primer** tick (lanzar el
		// intento de colocar o abrir), asi que tiene que seguir valiendo 0 en
		// esa pasada.
		t.ticksFase++
		publicar(t)
	}

	/**
	 * Fase 1: caminar con Baritone hasta el alcance del cofre.
	 *
	 * La orden sale por el mismo camino que `POST /baritone/goto`
	 * ([PuenteController.baritoneGoto]), que con `PUPPETEER_BARITONE_ASYNC=1`
	 * la manda desde un hilo propio. Es importante que sea ese camino y no un
	 * `sendChat` a pelo: un `#goto` puede quedarse esperando el registro
	 * dinamico del servidor, y hacerlo en el hilo principal cuelga el juego.
	 *
	 * Se manda **una sola vez** al entrar en la fase: repetirla cada tick
	 * reiniciaria el pathing sin dejarle avanzar.
	 *
	 * El punto al que se manda no es el destino sino uno a un radio
	 * ([puntoDeAproximacion]): ir al destino es lo que hacia que Baritone
	 * colocase un bloque debajo y que el cofre ya no se pudiera poner ahi.
	 */
	private fun caminar(t: Trabajo, level: Level, player: LocalPlayer) {
		val distancia = player.eyePosition.distanceTo(t.destino.center)
		if (distancia <= ALCANCE) {
			entrarEn(t, StorePhase.PLACING)
			return
		}
		if (!t.ordenEnviada) {
			val punto = puntoDeAproximacion(level, player, t.destino) ?: t.destino
			controlador?.baritoneGoto(punto.x, punto.y, punto.z)
			t.ordenEnviada = true
			// `moved` se marca **aqui** y no al entrar en la fase: si el cofre ya
			// estaba a tiro (que es lo normal con el `chest` de la config), el
			// bot nunca camina y el `202`/`done` tiene que decir `false`.
			t.movido = true
			publicar(t)
			if (punto == t.destino) {
				logger.info("Baritone: caminando al cofre de {} (sin sitio a un radio)", t.destino)
			} else {
				logger.info("Baritone: caminando a {} para poner el cofre de {}", punto, t.destino)
			}
		}
		// Si no llega, se sigue intentando: no hay plazo que corte la fase.
	}

	/**
	 * Fase 2: poner el cofre, si en el destino no hay ya uno.
	 *
	 * Si hay un cofre, `useItemOn` devolveria FAIL (no se coloca uno encima de
	 * otro) asi que ni se intenta: se pasa directo a abrir.
	 */
	private fun colocar(t: Trabajo, level: Level, player: LocalPlayer, gameMode: MultiPlayerGameMode) {
		// El cofre se ha apartado (otro jugador, un piston, una explosion) o el
		// bot ya no lo tiene a tiro: no hay nada que hacer desde aqui, asi que
		// se vuelve a `walking`. Con tope de vueltas, para que esto no sea un
		// bucle infinito.
		if (reintentarSiLejos(t, player)) return

		if (esCofre(level, t.destino)) {
			entrarEn(t, StorePhase.OPENING)
			return
		}

		// Colocacion ya lanzada: se espera a que el servidor mande el bloque.
		// Es la unica forma de saber que ha salido de verdad, porque el
		// `SUCCESS` de `useItemOn` es solo la prediccion del cliente.
		val lanzado = t.sitio
		if (lanzado != null) {
			if (esCofre(level, lanzado.pos)) {
				t.destino = lanzado.pos
				t.colocado = true
				t.sitio = null
				logger.info("Cofre colocado en {}", t.destino)
				entrarEn(t, StorePhase.OPENING)
			}
			return
		}

		val ranura = ranuraDelCofreEnLaBarra(player, gameMode) ?: return fallar(t, motivoSinCofre(player))
		val sitio = elegirSitio(level, player, t.destino) ?: return fallar(t, motivoSinSitio(t.destino))

		// Un intento cada [CADA_CUANTOS_TICKS_INTENTO]: cada `useItemOn` es un
		// paquete al servidor y, si el sitio elegido es invalido de una forma que
		// el cliente no ve, reenviarlo 20 veces por segundo no arregla nada y si
		// huele a kick por spam de interaccion.
		if (t.ticksFase % CADA_CUANTOS_TICKS_INTENTO != 0) return

		// En el mismo tick y antes de usar el objeto: `useItemOn` lee la mano
		// que este seleccionada ahora, no la que lo estuviera antes.
		player.inventory.selectedSlot = ranura

		// El clic va al centro del bloque de apoyo, con la cara `UP`: asi el
		// cofre aparece encima de el, que es donde se busca.
		val hit = BlockHitResult(sitio.soporte.center, Direction.UP, sitio.soporte, false)
		val resultado = gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit)

		if (resultado == InteractionResult.FAIL) {
			// Lo ha rechazado el propio cliente (un bot en medio, una pieza que no
			// se puede colocar, ...). Se reintenta con otro sitio.
			t.intentos++
			logger.warn("No se pudo colocar el cofre en {} (intento {})", sitio.pos, t.intentos)
			if (t.intentos >= INTENTOS) fallar(t, "el juego no ha dejado colocar el cofre en ${sitio.pos}")
			return
		}

		t.sitio = sitio
	}

	/**
	 * Fase 3: abrir el cofre con la mano vacia.
	 *
	 * Colocar **no** abre el menu (el clic de colocar consume el uso), asi que
	 * hace falta un segundo `useItemOn`. Y con un cofre en la mano el juego
	 * intenta colocar encima en vez de abrir, asi que la mano se vacia antes.
	 *
	 * El `containerMenu` lo asigna el paquete del servidor ([ChestMenu]), o
	 * sea que **no** esta aqui al volver la llamada: se espera por ticks.
	 */
	private fun abrir(t: Trabajo, player: LocalPlayer, gameMode: MultiPlayerGameMode) {
		// Mismo reencaje que en `colocar`: si el cofre ya no esta a tiro, se
		// vuelve a caminar en vez de insistir desde donde sea que este el bot.
		if (reintentarSiLejos(t, player)) return

		val menu = player.containerMenu
		if (menu is ChestMenu) {
			// `storenow` coloca y deja: aqui se acaba, sin volcar nada. Aun asi
			// se cierra el menu que se acaba de abrir, para no dejar al bot con
			// la pantalla del cofre puesta.
			if (t.storeNow) {
				cerrarSiEstaAbierto()
				terminar(t)
			} else {
				entrarEn(t, StorePhase.STORING)
			}
			return
		}

		// Un intento cada [CADA_CUANTOS_TICKS_INTENTO], por el mismo motivo que
		// en `colocar`: cada `useItemOn` es un paquete al servidor.
		if (t.ticksFase % CADA_CUANTOS_TICKS_INTENTO != 0) return

		// Mano vacia: se busca un hueco en la barra rapida. Si no hay ninguno
		// se usa la que haya, porque abrir un cofre con cualquier objeto en la
		// mano tambien funciona y quedarse sin poder actuar es peor.
		primerHuecoEnLaBarra(player)?.let { player.inventory.selectedSlot = it }

		val hit = BlockHitResult(t.destino.center, Direction.UP, t.destino, false)
		val resultado = gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit)

		if (resultado == InteractionResult.FAIL) {
			t.intentos++
			if (t.intentos >= INTENTOS) fallar(t, "el juego no ha dejado abrir el cofre de ${t.destino}")
		}
		// Si no falla, se espera: el menu llega del servidor en unos ticks. Si no
		// llega nunca, lo corta el plazo de la fase.
	}

	/**
	 * Ranura del menu del cofre donde cae la ranura `s` del inventario.
	 *
	 * El [ChestMenu] coloca primero el cofre, luego la mochila (inventario 9..35)
	 * y al final la barra rapida (0..8). Por eso la mochila va 9 ranuras por
	 * debajo de su numero y la barra 27 por encima. Restarle 27 tambien a la
	 * mochila mandaba el clic a las ranuras del propio cofre: no guardaba
	 * ninguna de las 27, y con el cofre ya con cosas las sacaba de vuelta.
	 *
	 * El mapeo esta comprobado contra la clase real de 1.21.5 (ver la tabla de
	 * [volcar]).
	 */
	private fun ranuraEnElMenuDelCofre(s: Int, chestSize: Int): Int =
		chestSize + if (s < HOTBAR_SIZE) MOCHILA_SIZE + s else s - HOTBAR_SIZE

	/**
	 * Fase 4: volcar el inventario con shift+click ranura a ranura.
	 *
	 * El mapeo del [ChestMenu] (comprobado contra la clase real de 1.21.5, que
	 * hace `addChestGrid` y despues `addStandardInventorySlots`) es:
	 *
	 * | ranura del menu        | contenido                        |
	 * |------------------------|----------------------------------|
	 * | `0..chestSize`         | el cofre                         |
	 * | `chestSize..+27`       | inventario 9..35 (la mochila)    |
	 * | `+27..+36`             | barra rapida 0..8                |
	 *
	 * o sea, la ranura `s` del inventario va en
	 * `chestSize + (s < 9 ? s + 27 : s - 9)`, que es [ranuraEnElMenuDelCofre].
	 *
	 * Se cuenta lo que **sale** del inventario (antes menos despues) y no los
	 * clics enviados: con el cofre casi lleno un shift+click se queda con
	 * parte, y `stored` tiene que decir unidades de verdad.
	 */
	private fun volcar(t: Trabajo, player: LocalPlayer, gameMode: MultiPlayerGameMode) {
		val menu = player.containerMenu as? ChestMenu
			?: return fallar(t, "el menu del cofre se ha cerrado antes de volcar nada")

		// La foto del inventario se toma en el **primer** tick de la fase, y eso
		// lo dice una bandera propia y no `ticksFase`: `entrarEn` pone el
		// contador a 0 y `avanzar` lo incrementa al final, asi que cuando se
		// ejecuta la primera pasada ya va por 1 y la comprobacion se escapaba
		// siempre, con la lista de ranuras sin llenar.
		if (!t.iniciado) {
			t.iniciado = true
			t.unidadesAntes = unidadesEnElInventario(player)
			// Solo 0..35: la armadura y la mano secundaria (36..40) no tienen
			// ranura en un ChestMenu (ver la nota de la cabecera del fichero).
			t.pendientes.addAll(0 until MENU_INVENTARIO)
		}

		val chestSize = menu.rowCount * 9
		var enviados = 0
		while (t.pendientes.isNotEmpty() && enviados < CLICS_POR_TICK) {
			val s = t.pendientes.removeFirst()
			val stack = player.inventory.getItem(s)
			// `ItemStack.isEmpty` es metodo, no campo.
			if (stack.isEmpty) continue
			val ranura = ranuraEnElMenuDelCofre(s, chestSize)
			gameMode.handleInventoryMouseClick(menu.containerId, ranura, 0, ClickType.QUICK_MOVE, player)
			enviados++
		}

		if (t.pendientes.isNotEmpty()) return

		// Un par de ticks de margen para que el servidor confirme los ultimos
		// clics antes de cerrar: si se cierra antes, los cancela y `stored`
		// contaria unidades que se han vuelto al inventario.
		t.turnosParaCerrar++
		if (t.turnosParaCerrar < TICKS_ANTES_DE_CERRAR) return

		t.guardados = (t.unidadesAntes - unidadesEnElInventario(player)).coerceAtLeast(0)
		player.closeContainer()
		terminar(t)
	}

	// ---------------------------------------------------------------- salida

	private fun terminar(t: Trabajo) {
		val destino = t.destino
		val guardados = t.guardados
		val movido = t.movido
		val colocado = t.colocado
		trabajo = null
		estado = StoreState(StorePhase.DONE, destino.deChestTarget(), movido, colocado, guardados)
		logger.info("Cofre listo en {}: {} unidades (movido={}, colocado={}).", destino, guardados, movido, colocado)
	}

	/**
	 * Camino de error.
	 *
	 * Ademas de `failed`, el bot **se desconecta**: es lo que pidio el usuario
	 * ("si no lo tiene, que se desconecte"). Un bot parado sin inventario con la
	 * orden incumplida es peor que un bot fuera, porque ni se ve ni se
	 * distingue de uno sano. Se reutiliza el camino que ya usa `POST /disconnect`
	 * ([PuenteController.disconnect]).
	 */
	private fun fallar(t: Trabajo, motivo: String) {
		val destino = t.destino
		val movido = t.movido
		val colocado = t.colocado
		val guardados = t.guardados
		trabajo = null
		cerrarSiEstaAbierto()
		estado = StoreState(StorePhase.FAILED, destino.deChestTarget(), movido, colocado, guardados, motivo)
		logger.error("Cofre: {} (destino {})", motivo, destino)
		desconectarPorFallo()
	}

	/**
	 * Desconexion que acompaña a todo `failed`.
	 *
	 * Se reutiliza el camino que ya usa `POST /disconnect`
	 * ([PuenteController.disconnect]) porque es el unico que sabe dejar al
	 * cliente de verdad en la pantalla de titulo. Puede lanzar (409 si no hay
	 * sesion), asi que se traga la excepcion: un fallo al desconectar no
	 * cambia el veredicto, que ya esta publicado como `failed`.
	 */
	private fun desconectarPorFallo() {
		try {
			controlador?.disconnect()
		} catch (e: Exception) {
			// Sin sesion no hay nada que desconectar: se avisa y ya esta.
			logger.warn("No se pudo desconectar tras el fallo del cofre: {}", e.message)
		}
	}

	/** Abandona el trabajo en curso sin marcar `failed` (llega una peticion nueva). */
	private fun descartarTrabajo() {
		cerrarSiEstaAbierto()
		trabajo = null
		estado = StoreState.IDLE
	}

	/** Cierra el menu que hubiera abierto, para no dejar al bot con la pantalla del cofre. */
	private fun cerrarSiEstaAbierto() {
		val player = Minecraft.getInstance().player ?: return
		if (player.containerMenu is ChestMenu) {
			runCatching { player.closeContainer() }
				.onFailure { logger.warn("No se pudo cerrar el menu del cofre: {}", it.message) }
		}
	}

	// --------------------------------------------------------------- utiles

	private fun entrarEn(t: Trabajo, fase: StorePhase) {
		// Antes de resetear el contador: al salir de `walking` hay que parar a
		// Baritone, y solo si de verdad se le habia mandado una orden. Sin esto
		// el bot sigue andando mientras [colocar] trabaja, que es justo cuando
		// llega a su destino y se coloca un bloque debajo.
		if (fase != StorePhase.WALKING && t.ordenEnviada) pararBaritone(t)
		t.fase = fase
		t.ticksFase = 0
		t.intentos = 0
		t.ordenEnviada = false
		t.iniciado = false
		publicar(t)
	}

	/**
	 * Le dice a Baritone que deje de moverse.
	 *
	 * Sin esto el bot sigue caminando mientras coloca y abre el cofre, y como
	 * su destino era el bloque del cofre, el bloque que se coloca al llegar cae
	 * justo donde iba el cofre. Es el `cancel` de verdad, no el `forcecancel`:
	 * si esta cavando se le deja acabar el paso antes de pararse.
	 */
	private fun pararBaritone(t: Trabajo) {
		try {
			controlador?.baritoneStop(false)
			logger.info("Baritone parado en {} para poner el cofre.", t.destino)
		} catch (e: Exception) {
			// Sin controlador no hay nadie a quien pararlo, y si el envio falla se
			// sigue adelante: el cofre se intentara poner igual, solo que el bot
			// seguira moviendose mientras tanto.
			logger.warn("No se pudo parar Baritone: {}", e.message)
		}
	}

	/**
	 * Vuelve a `walking` si el cofre se ha quedado lejos, y dice si lo ha hecho.
	 *
	 * Se llama al entrar en `placing` y en `opening`: son las fases que
	 * necesitan la mano a [ALCANCE] y las dos unicas que se pueden quedar
	 * "en sitio" sin que el cofre este ahi (el `storenow` lo pone al lado del
	 * bot, pero despues se puede abrir la pantalla, teletransportarse con un
	 * comando del servidor, empujar al bot...).
	 *
	 * Solo se permite un par de vueltas ([MAX_VUELTAS]): reintentar sin tope
	 * seria un bucle infinito en cuanto el cofre no se pueda alcanzar.
	 */
	private fun reintentarSiLejos(t: Trabajo, player: LocalPlayer): Boolean {
		if (player.eyePosition.distanceTo(t.destino.center) <= ALCANCE) return false

		t.vueltas++
		if (t.vueltas > MAX_VUELTAS) {
			fallar(t, "el cofre de ${t.destino} se ha quedado fuera de alcance y no se ha podido alcanzar")
			return true
		}
		logger.warn(
			"El cofre de {} esta lejos (vuelta {} de {}); se vuelve a caminar.",
			t.destino, t.vueltas, MAX_VUELTAS,
		)
		entrarEn(t, StorePhase.WALKING)
		return true
	}

	private fun publicar(t: Trabajo) {
		estado = StoreState(t.fase, t.destino.deChestTarget(), t.movido, t.colocado, t.guardados)
	}

	/** Un [BlockPos] como [ChestTarget]: lo que viaja en el JSON. */
	private fun BlockPos.deChestTarget() = ChestTarget(x, y, z)

	/**
	 * Si hay un cofre en el bloque.
	 *
	 * Se compara `state.block` en vez de usar `state.is(Blocks.CHEST)` porque
	 * `is` es palabra reservada en Kotlin y habria que escribirlo con acentos
	 * graves.
	 */
	private fun esCofre(level: Level, pos: BlockPos): Boolean =
		level.isLoaded(pos) && level.getBlockState(pos).block == Blocks.CHEST

	/**
	 * Ranura de la barra rapida (0..8) donde hay un cofre, o `null` si no la hay.
	 *
	 * Solo la barra rapida sirve: en vanilla un cofre se coloca con la ranura
	 * **seleccionada**, asi que uno que este en la mochila no se puede usar. En
	 * vez de rendirse, se cambia a la primera ranura libre de la barra con un
	 * unico clic (`ClickType.SWAP`) en el menu del jugador, que es lo mismo que
	 * pulsar la tecla de la barra con el raton encima del cofre.
	 */
	private fun ranuraDelCofreEnLaBarra(
		player: LocalPlayer,
		gameMode: MultiPlayerGameMode,
	): Int? {
		for (i in 0 until HOTBAR_SIZE) {
			val stack = player.inventory.getItem(i)
			if (!stack.isEmpty && stack.item == Items.CHEST) return i
		}

		val origen = ranuraDelCofreEnLaMochila(player) ?: return null
		// Sin sitio en la barra no hay nada que hacer: cambiar un objeto que el
		// bot lleva en la mano por el cofre seria vaciarle la mano sin avisar.
		val destino = primerHuecoEnLaBarra(player) ?: return null

		// El indice del menu no es el de la ranura: el menu del jugador mete
		// primero los 5 de la fabricacion y luego la armadura y la mano
		// secundaria, asi que se pregunta con `findSlot`, que es la que usa el
		// juego por dentro (compara `container` y `getContainerSlot`).
		val menu = player.containerMenu
		val ranuraMenu = menu.findSlot(player.inventory, origen).orElse(-1)
		if (ranuraMenu < 0) return null
		gameMode.handleInventoryMouseClick(menu.containerId, ranuraMenu, destino, ClickType.SWAP, player)

		// El clic se aplica en el cliente antes de que conteste el servidor, asi
		// que normalmente el cofre ya esta en su sitio. Si el servidor lo
		// rechaza, aqui se devuelve `null` y el siguiente tick se reintenta.
		return if (player.inventory.getItem(destino).item == Items.CHEST) destino else null
	}

	/** Ranura de la mochila (9..35) donde hay un cofre, o `null`. */
	private fun ranuraDelCofreEnLaMochila(player: LocalPlayer): Int? {
		for (i in HOTBAR_SIZE until MENU_INVENTARIO) {
			if (player.inventory.getItem(i).item == Items.CHEST) return i
		}
		return null
	}

	private fun primerHuecoEnLaBarra(player: LocalPlayer): Int? {
		for (i in 0 until HOTBAR_SIZE) {
			if (player.inventory.getItem(i).isEmpty) return i
		}
		return null
	}

	/**
	 * Motivo del fallo cuando no hay un cofre Usable en la barra rapida.
	 *
	 * A estas alturas el cofre ya deberia haberse cambiado de sitio solo, asi que
	 * esto es o que no lleva ninguno o que la barra esta llena y no cabe.
	 */
	private fun motivoSinCofre(player: LocalPlayer): String {
		val mochila = ranuraDelCofreEnLaMochila(player)
		return if (mochila == null) {
			"no lleva ningun cofre en el inventario: no se puede colocar uno"
		} else if (primerHuecoEnLaBarra(player) == null) {
			"el cofre esta en la ranura $mochila del inventario y la barra rapida esta llena: " +
				"no hay sitio para pasarlo"
		} else {
			"el cofre de la ranura $mochila no se ha podido pasar a la barra rapida: " +
				"el servidor no ha aceptado el cambio"
		}
	}

	private fun motivoSinSitio(destino: BlockPos): String =
		"no hay sitio libre para un cofre en $destino ni a su alrededor " +
			"(hace falta un bloque vacio, con suelo debajo y al alcance de la mano)"

	/**
	 * Donde poner el cofre: el destino primero y, si no vale, lo mas cerca posible.
	 *
	 * Se prueban en tres pasos, y el primero que acierta gana:
	 *
	 * 1. el destino exacto, que es lo que se ha pedido;
	 * 2. los siete bloques que ya se probaban (debajo, encima y los cuatro lados
	 *    a la misma altura), por no perder ningun caso que ya funcionaba;
	 * 3. y si ninguno vale, **anillos completos** alrededor del destino, de uno
	 *    en uno y de dentro hacia fuera.
	 *
	 * El paso 3 es el que arregla `/store/now`: ahi el destino es el bloque que
	 * pisa el bot, que siempre esta descartado, y la lista de 7 no llega a ningun
	 * sitio en cuanto el bot esta en un suelo que no es plano y despejado a los
	 * cuatro lados. Con un anillo de 2 ya sale al borde de una plataforma, y el
	 * suelo de al lado, un bloque mas abajo, tambien cuenta porque se prueba a
	 * distintas alturas.
	 *
	 * Dentro de cada anillo el orden es el de las coordenadas, asi que el sitio
	 * elegido es siempre el mismo para el mismo mundo: el que se ha pedido si es
	 * valido, y si no, el primero valido en la esquina (-1, -1).
	 *
	 * Todos los candidatos pasan por [sitioValido], que descarta lo que el
	 * servidor va a rechazar.
	 */
	private fun elegirSitio(level: Level, player: LocalPlayer, destino: BlockPos): Sitio? {
		val ojo = player.eyePosition

		fun intentar(candidato: BlockPos): Sitio? =
			if (sitioValido(level, player, ojo, candidato)) Sitio(candidato, candidato.below()) else null

		intentar(destino)?.let { return it }
		listOf(
			destino.below(),
			destino.above(),
			destino.offset(1, 0, 0),
			destino.offset(-1, 0, 0),
			destino.offset(0, 0, 1),
			destino.offset(0, 0, -1),
			destino.above(2),
		).firstNotNullOfOrNull { intentar(it) }?.let { return it }

		for (radio in 1..RADIO_SITIO) {
			for (dy in ALTURAS_SITIO) {
				anillo(destino, radio, dy).firstNotNullOfOrNull { intentar(it) }?.let { return it }
			}
		}
		return null
	}

	/**
	 * Los bloques de un anillo alrededor de `origen`, a `dy` de altura.
	 *
	 * El anillo es el borde de un cuadrado de lado `2 * radio + 1`, no el
	 * relleno: los puntos de dentro serian el propio origen o estarian encima,
	 * que es justo donde cabe el jugador.
	 */
	private fun anillo(origen: BlockPos, radio: Int, dy: Int): List<BlockPos> {
		val salida = ArrayList<BlockPos>(radio * 8)
		for (dx in -radio..radio) {
			for (dz in -radio..radio) {
				if (dx != -radio && dx != radio && dz != -radio && dz != radio) continue
				salida.add(origen.offset(dx, dy, dz))
			}
		}
		return salida
	}

	/**
	 * Si un cofre se puede poner en `candidato` desde donde esta el jugador.
	 *
	 * Descarta los sitios que el servidor va a rechazar:
	 * - el bloque tiene algo que no se puede reemplazar (`canBeReplaced`);
	 * - el jugador lo ocupa: `BlockItem.canPlace` exige `isUnobstructed` con la
	 *   forma del propio jugador, asi que un cofre en sus pies no se pone. Por
	 *   eso `/store/now` acaba poniendo el cofre **al lado** del bot;
	 * - no hay suelo debajo: un cofre no sobrevive sin cara superior firme
	 *   (`isFaceSturdy`) y `place` devuelve FAIL;
	 * - el bloque de apoyo queda a mas de [ALCANCE] de la mano. El clic va al
	 *   centro del bloque de apoyo, asi que el alcance se mide ahi y no al sitio
	 *   donde ira el cofre.
	 */
	private fun sitioValido(
		level: Level,
		player: LocalPlayer,
		ojo: Vec3,
		candidato: BlockPos,
	): Boolean {
		if (candidato.y !in MIN_Y..MAX_Y) return false
		if (!level.isLoaded(candidato)) return false
		val estado = level.getBlockState(candidato)
		if (!estado.canBeReplaced()) return false
		if (estado.block == Blocks.CHEST) return false
		if (AABB(candidato).intersects(player.boundingBox)) return false

		val soporte = candidato.below()
		if (!level.isLoaded(soporte)) return false
		if (!level.getBlockState(soporte).isFaceSturdy(level, soporte, Direction.UP)) return false
		return ojo.distanceTo(soporte.center) <= ALCANCE
	}

	/**
	 * A que punto se manda a Baritone: a un radio del destino, no al destino.
	 *
	 * Se prueban los anillos de [RADIOS], de mas cerca a mas lejos, y dentro de
	 * cada uno los puntos de [ALTURAS]. Gana el anillo mas cercano que tenga
	 * algo, porque de el ya se puede poner el cofre, y dentro de el el punto que
	 * menos camino le cuesta al bot.
	 *
	 * @return el punto al que ir, o `null` si alrededor del destino no hay
	 * ninguno (en un tunel de uno, por ejemplo). Entonces se va al destino como
	 * antes y decide [elegirSitio].
	 */
	private fun puntoDeAproximacion(level: Level, player: LocalPlayer, destino: BlockPos): BlockPos? {
		// El clic va al centro del bloque de apoyo, y lo que decide si llega es
		// solo a cuanta distancia horizontal queda el jugador: el anillo de fuera
		// tiene esquinas, y esas son las que se podrian quedar sin alcance.
		val soporte = destino.below().center
		val ojos = player.eyePosition

		for (radio in RADIOS) {
			var elegido: BlockPos? = null
			var recorrido = Double.MAX_VALUE
			for (dy in ALTURAS) {
				for (punto in anillo(destino, radio, dy)) {
					if (!sePuedePonerse(level, punto)) continue
					val desde = punto.center.add(0.0, ALTURA_OJOS, 0.0)
					if (desde.distanceTo(soporte) > ALCANCE) continue

					val distancia = ojos.distanceTo(punto.center)
					if (distancia < recorrido) {
						recorrido = distancia
						elegido = punto
					}
				}
			}
			if (elegido != null) return elegido
		}
		return null
	}

	/**
	 * Si el bot se puede poner de pie en `pos`.
	 *
	 * Dos bloques de aire encima (el jugador es algo mas de uno alto) y algo
	 * firme debajo. La misma comprobacion que hace el mundo al decidir donde se
	 * puede estar, sin llamar a la API de colision.
	 */
	private fun sePuedePonerse(level: Level, pos: BlockPos): Boolean {
		if (pos.y !in MIN_Y..MAX_Y) return false
		if (!level.isLoaded(pos) || !level.isLoaded(pos.above(2))) return false
		if (!level.getBlockState(pos).isFaceSturdy(level, pos, Direction.UP)) return false
		return level.getBlockState(pos.above()).canBeReplaced() &&
			level.getBlockState(pos.above(2)).canBeReplaced()
	}

	private fun unidadesEnElInventario(player: LocalPlayer): Int {
		var total = 0
		val inventario = player.inventory
		for (i in 0 until inventario.containerSize) {
			val stack = inventario.getItem(i)
			if (!stack.isEmpty) total += stack.count
		}
		return total
	}

	// --------------------------------------------------------------- tipos

	/** Un trabajo en curso. Solo lo toca el tick. */
	private class Trabajo(
		var destino: BlockPos,
		val storeNow: Boolean,
	) {
		var fase: StorePhase = StorePhase.WALKING
		var movido = false
		var colocado = false
		var guardados = 0
		var ticksFase = 0
		var intentos = 0
		/**
		 * `true` cuando la fase `storing` ya ha tomado su foto del inventario.
		 *
		 * No se deduce de `ticksFase` porque ese contador lo pone a cero
		 * [entrarEn] y lo incrementa [avanzar] al final de la pasada: cuando el
		 * codigo de la fase llega a ejecutarse por primera vez ya vale 1.
		 */
		var iniciado = false
		/** `true` cuando ya se le mando el `#goto` a Baritone. */
		var ordenEnviada = false
		/**
		 * Veces que se ha vuelto a `walking` por cofre fuera de alcance
		 * (ver [reintentarSiLejos]). Con tope de [MAX_VUELTAS].
		 */
		var vueltas = 0
		/** Colocacion lanzada a la espera de que el bloque aparezca. */
		var sitio: Sitio? = null
		/** Ranuras del inventario que quedan por volcar. */
		val pendientes = ArrayDeque<Int>()
		var unidadesAntes = 0
		var turnosParaCerrar = 0
	}

	/** Bloque donde va el cofre y bloque al que hay que hacer clic. */
	private class Sitio(val pos: BlockPos, val soporte: BlockPos)

	/**
	 * Distancia a la que se deja de caminar.
	 *
	 * Son 4 bloques, el alcance de la mano en vanilla. Se usa el mismo numero
	 * para parar de caminar y para decidir si se puede interactuar, porque el
	 * punto al que se hace clic es el **centro** del bloque: si el centro esta
	 * a 4, el clic tambien, y entra de sobra en lo que `stillValid` deja.
	 */
	private const val ALCANCE = 4.0

	/**
	 * Anillos, en bloques, donde se busca un sitio donde ponerse al lado del
	 * cofre. De mas cerca a mas lejos, y gana el primero que tenga algo.
	 *
	 * Son 2 y 3, no mas: a 4 el clic al centro del bloque de apoyo ya se queda
	 * fuera de [ALCANCE], y a 2 el bot llega antes y coloca antes.
	 */
	private val RADIOS = listOf(2, 3)

	/**
	 * Alturas que se prueban para el sitio donde ponerse, de la misma forma
	 * relativa al destino: a su altura, una mas abajo y una mas arriba.
	 *
	 * La de abajo va la segunda a proposito: el terreno alrededor puede estar un
	 * bloque mas bajo que el destino, y bajarse no hace dano mientras que subir
	 * y caer si.
	 */
	private val ALTURAS = listOf(0, -1, 1)

	/**
	 * Altura de los ojos sobre el centro del bloque donde se esta de pie.
	 *
	 * Los 1.62 del jugador vanilla. Se miden ahi y no en el sitio donde ira el
	 * cofre porque el clic va al centro del bloque de apoyo.
	 */
	private const val ALTURA_OJOS = 1.62

	/**
	 * Hasta que anillo se busca sitio para el cofre cuando el destino y sus
	 * vecinos immediate fallan.
	 *
	 * Tres, porque a mas distancia el clic no llega: el alcance son 4 bloques
	 * desde los ojos, y mas alla de 3 el bloque de apoyo ya queda fuera. Es
	 * decir, el anillo 3 es el ultimo que puede dar algo, y no un tope chose.
	 */
	private const val RADIO_SITIO = 3

	/**
	 * Alturas que se prueban al buscar el sitio del cofre: a la del destino, una
	 * mas abajo y una mas arriba.
	 *
	 * La de abajo hace falta mas que ninguna otra: al pie de una plataforma o
	 * al lado de un escalon el suelo esta un bloque mas bajo que donde se ha
	 * pedido el cofre, y es el unico sitio donde hay apoyo firme.
	 */
	private val ALTURAS_SITIO = listOf(0, -1, 1)

	private const val HOTBAR_SIZE = 9
	/** Ranuras de la mochila: 9..35, las 27 que van antes de la barra. */
	private const val MOCHILA_SIZE = 27
	/** Ranuras del inventario que existen en un `ChestMenu`: 0..35. */
	private const val MENU_INVENTARIO = 36

	/** Vertical valida de un mundo de 1.21.5 (o del supermar). */
	private const val MIN_Y = -2048
	private const val MAX_Y = 2048

	/** Intentos de colocar o de abrir antes de darse por vencido. */
	private const val INTENTOS = 3

	/**
	 * Ticks entre dos intentos de colocar o de abrir.
	 *
	 * Cada `useItemOn` es un paquete al servidor. Cuando el sitio elegido es
	 * invalido por algo que el cliente no ve, reenviarlo cada tick (20 veces
	 * por segundo) no lo arregla y si se parece al spam de interaccion que
	 * expulsa a los clientes.
	 */
	private const val CADA_CUANTOS_TICKS_INTENTO = 5

	/**
	 * Veces que se puede volver a caminar por cofre fuera de alcance.
	 *
	 * Con dos ya se cubre el caso normal (el cofre se ha movido mientras se
	 * caminaba); a la tercera se rinde y falla, porque a partir de ahi lo
	 * probable es que el destino sea inalcanzable de verdad.
	 */
	private const val MAX_VUELTAS = 2

	/** Clics de shift por tick: cada uno es un paquete al servidor. */
	private const val CLICS_POR_TICK = 2

	/** Margen antes de cerrar, para que el servidor confirme los ultimos clics. */
	private const val TICKS_ANTES_DE_CERRAR = 3
}
