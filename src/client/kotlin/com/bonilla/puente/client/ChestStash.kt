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
 * cuenta con ticks ([Trabajo.ticksFase]) y con marcas de reloj
 * ([Trabajo.limite]), que se consultan, nunca se esperan. Cada tick hace una
 * cantidad de trabajo acotada y vuelve, asi que el juego sigue a su framerate.
 *
 * ## Las fases
 *
 * `walking` -> `placing` -> `opening` -> `storing` -> `done`, con `failed` como
 * salida de error. `placing` se salta si en el destino ya hay un cofre, y
 * `storing` se salta si la peticion era de las que solo colocan
 * ([StoreRequest.storeNow]).
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
		t.limiteTotal = System.currentTimeMillis() + TOTAL_MS
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

		if (System.currentTimeMillis() > t.limite) {
			return fallar(t, "la fase '${t.fase.name.lowercase()}' no ha terminado en su plazo de ${t.plazoLegible()}")
		}

		// Plazo total del trabajo, por encima de los plazos por fase: sin el,
		// una maquina que se reembolsa entre fases (volver a `walking` cuando el
		// cofre se ha quedado lejos, por ejemplo) podria seguir indefinidamente sin
		// que ninguna fase llegue a su propio limite.
		if (System.currentTimeMillis() > t.limiteTotal) {
			return fallar(
				t,
				"el volcado no ha terminado en su plazo total de ${t.plazoTotalLegible()} " +
					"(fase '${t.fase.name.lowercase()}', destino ${t.destino})",
			)
		}

		when (t.fase) {
			// A menos de ALCANCE ya no se camina: se interactua desde donde este.
			StorePhase.WALKING -> caminar(t, player)
			StorePhase.PLACING -> colocar(t, level, player, gameMode)
			StorePhase.OPENING -> abrir(t, player, gameMode)
			StorePhase.STORING -> volcar(t, player, gameMode)
			// Terminales: `done` y `failed` se limpian en `terminar`/`fallar`.
			StorePhase.IDLE, StorePhase.DONE, StorePhase.FAILED -> return
		}

		// La fase puede haber terminado el trabajo por su cuenta (al entrar en
		// `colocar` sin sitio se llama a `fallar`, que deja `trabajo = null`).
		// Sin esta comprobacion, el reloj de la fase y `publicar` volverian a
		// escribir encima del estado terminal que se acaba de fijar.
		if (trabajo !== t) return

		// El reloj de la fase avanza aqui, al final: hay fases que usan
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
	 */
	private fun caminar(t: Trabajo, player: LocalPlayer) {
		val distancia = player.eyePosition.distanceTo(t.destino.center)
		if (distancia <= ALCANCE) {
			entrarEn(t, StorePhase.PLACING)
			return
		}
		if (distancia > DISTANCIA_MAXIMA) {
			return fallar(
				t,
				"el cofre esta a ${"%.1f".format(distancia)} bloques y el maximo son ${DISTANCIA_MAXIMA.toInt()}",
			)
		}
		if (!t.ordenEnviada) {
			controlador?.baritoneGoto(t.destino.x, t.destino.y, t.destino.z)
			t.ordenEnviada = true
			// `moved` se marca **aqui** y no al entrar en la fase: si el cofre ya
			// estaba a tiro (que es lo normal con el `chest` de la config), el
			// bot nunca camina y el `202`/`done` tiene que decir `false`.
			t.movido = true
			publicar(t)
			logger.info("Baritone: caminando al cofre de {}", t.destino)
		}
		// Si no llega, lo corta el plazo de la fase (120 s) en `avanzar`.
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
		// bucle infinito (el plazo total de [Trabajo.limiteTotal] es la red
		// de seguridad de ahi).
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

		val ranura = ranuraDelCofreEnLaBarra(player) ?: return fallar(t, motivoSinCofre(player))
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
	 * Fase 4: volcar el inventario con shift+click ranura a ranura.
	 *
	 * El mapeo del [ChestMenu] (verificado en su fuente de 1.21.5, que hace
	 * `addChestGrid` y despues `addStandardInventorySlots`) es:
	 *
	 * | ranura del menu        | contenido                        |
	 * |------------------------|----------------------------------|
	 * | `0..chestSize`         | el cofre                         |
	 * | `chestSize..+27`       | inventario 9..35 (la mochila)    |
	 * | `+27..+36`             | barra rapida 0..8                |
	 *
	 * o sea, la ranura `s` del inventario va en
	 * `chestSize + (s < 9 ? s + 27 : s - 9)`.
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
			val ranura = chestSize + if (s < 9) s + HOTBAR_OFFSET else s - HOTBAR_OFFSET
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
		t.fase = fase
		t.ticksFase = 0
		t.intentos = 0
		t.ordenEnviada = false
		t.iniciado = false
		t.limite = System.currentTimeMillis() + plazoDe(fase)
		publicar(t)
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
	 * seria un bucle infinito en cuanto el cofre no se pueda alcanzar, y la
	 * red de seguridad ([Trabajo.limiteTotal]) esta pensada para cortes duros,
	 * no para esperar cuatro minutos.
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

	private fun plazoDe(fase: StorePhase): Long = when (fase) {
		StorePhase.WALKING -> CAMINAR_MS
		StorePhase.PLACING -> COLOCAR_MS
		StorePhase.OPENING -> ABRIR_MS
		StorePhase.STORING -> VOLCAR_MS
		StorePhase.IDLE, StorePhase.DONE, StorePhase.FAILED -> 0L
	}

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
	 * Ranura de la barra rapida (0..8) donde hay un cofre, o `null`.
	 *
	 * Solo la barra rapida: es la unica que el jugador puede usar sin abrir el
	 * inventario a mano, y por eso el clic tiene que llevar el cofre ya
	 * seleccionado.
	 */
	private fun ranuraDelCofreEnLaBarra(player: LocalPlayer): Int? {
		for (i in 0 until HOTBAR_SIZE) {
			val stack = player.inventory.getItem(i)
			if (!stack.isEmpty && stack.item == Items.CHEST) return i
		}
		return null
	}

	private fun primerHuecoEnLaBarra(player: LocalPlayer): Int? {
		for (i in 0 until HOTBAR_SIZE) {
			if (player.inventory.getItem(i).isEmpty) return i
		}
		return null
	}

	/** Motivo del fallo cuando no hay cofre: donde esta, si aparece en otro sitio. */
	private fun motivoSinCofre(player: LocalPlayer): String {
		for (i in HOTBAR_SIZE until player.inventory.containerSize) {
			if (player.inventory.getItem(i).item == Items.CHEST) {
				return "el unico cofre esta en la ranura $i del inventario, y de ahi no se puede usar: " +
					"tiene que estar en la barra rapida (0..8)"
			}
		}
		return "no lleva ningun cofre en el inventario: no se puede colocar uno"
	}

	private fun motivoSinSitio(destino: BlockPos): String =
		"no hay sitio libre para un cofre en $destino ni a su alrededor " +
			"(hace falta un bloque vacio, con suelo debajo y al alcance de la mano)"

	/**
	 * Donde poner el cofre: el destino primero y, si no vale, lo mas cerca posible.
	 *
	 * Descarta los sitios que el servidor va a rechazar:
	 * - el bloque tiene algo que no se puede reemplazar (`canBeReplaced`);
	 * - el jugador lo ocupa: `BlockItem.canPlace` exige `isUnobstructed` con la
	 *   forma del propio jugador, asi que un cofre en sus pies no se pone. Por
	 *   eso `/store/now` acaba poniendo el cofre **al lado** del bot;
	 * - no hay suelo debajo: un cofre no sobrevive sin cara superior firme
	 *   (`isFaceSturdy`) y `place` devuelve FAIL;
	 * - el bloque de apoyo queda a mas de [ALCANCE] de la mano.
	 */
	private fun elegirSitio(level: Level, player: LocalPlayer, destino: BlockPos): Sitio? {
		val ojo = player.eyePosition
		val candidatos = listOf(
			destino,
			destino.below(),
			destino.above(),
			destino.offset(1, 0, 0),
			destino.offset(-1, 0, 0),
			destino.offset(0, 0, 1),
			destino.offset(0, 0, -1),
			destino.above(2),
		)
		for (candidato in candidatos) {
			if (candidato.y !in MIN_Y..MAX_Y) continue
			if (!level.isLoaded(candidato)) continue
			val estado = level.getBlockState(candidato)
			if (!estado.canBeReplaced()) continue
			if (estado.block == Blocks.CHEST) continue
			if (AABB(candidato).intersects(player.boundingBox)) continue

			val soporte = candidato.below()
			if (!level.isLoaded(soporte)) continue
			if (!level.getBlockState(soporte).isFaceSturdy(level, soporte, Direction.UP)) continue
			// El clic va al centro del bloque de apoyo, asi que el alcance se
			// mide ahi y no al sitio donde ira el cofre.
			if (ojo.distanceTo(soporte.center) > ALCANCE) continue

			return Sitio(candidato, soporte)
		}
		return null
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
		/** Fin de la fase en milisegulos de reloj; se consulta, nunca se espera. */
		var limite = 0L
		/**
		 * Fin del trabajo entero, por encima de los plazos por fase.
		 *
		 * Sin el, una maquina que se reembolsa entre fases no llegaria nunca a
		 * que expire ningun plazo por fase y podria quedarse dando vueltas.
		 */
		var limiteTotal = 0L
		/** Colocacion lanzada a la espera de que el bloque aparezca. */
		var sitio: Sitio? = null
		/** Ranuras del inventario que quedan por volcar. */
		val pendientes = ArrayDeque<Int>()
		var unidadesAntes = 0
		var turnosParaCerrar = 0

		/** El plazo de la fase en segundos, para el mensaje de error. */
		fun plazoLegible(): String = "${plazoDe(this.fase) / 1000} s"

		/** El plazo total del trabajo en segundos, para el mensaje de error. */
		fun plazoTotalLegible(): String = "${TOTAL_MS / 1000} s"
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

	/** Distancia maxima a la que se camina antes de rendirse. */
	private const val DISTANCIA_MAXIMA = 64.0

	private const val HOTBAR_SIZE = 9
	/** Ranuras de la barra rapida que quedan despues de las 27 de la mochila. */
	private const val HOTBAR_OFFSET = 27
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

	private const val CAMINAR_MS = 120_000L
	private const val COLOCAR_MS = 4_000L
	private const val ABRIR_MS = 3_000L
	private const val VOLCAR_MS = 15_000L

	/**
	 * Plazo del trabajo entero, por encima de los plazos por fase.
	 *
	 * Suma generosa de los cuatro: normal (120 s) + colocar (4) + abrir (3) +
	 * volcar (15) = 142 s, y se dejan 100 de margen para las vueltas extra de
	 * [MAX_VUELTAS] y para las pausas largas de fps.
	 */
	private const val TOTAL_MS = 240_000L
}