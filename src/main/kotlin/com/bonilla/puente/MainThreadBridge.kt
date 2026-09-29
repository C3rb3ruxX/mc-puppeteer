package com.bonilla.puente

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Abstraccion del hilo principal de Minecraft.
 *
 * La implementacion real (source set `client`) la respalda con
 * `Minecraft.getInstance().execute { }` / `isSameThread()`.
 */
fun interface MainThreadExecutor {
	fun execute(task: Runnable)

	fun isMainThread(): Boolean = false
}

/**
 * Error de negocio que la capa HTTP traduce a un codigo y mensaje claros.
 */
class PuenteException(val status: Int, val code: String, message: String) : RuntimeException(message)

/**
 * Coordina el acceso entre los hilos HTTP y el hilo principal.
 *
 * El unico patron permitido es `callOnMainThread { ... }`: el hilo HTTP espera,
 * el juego nunca espera a nadie. Esto garantiza el requisito de "no impactar
 * el rendimiento de Minecraft": el juego no se bloquea jamas por una peticion
 * lenta, y la peticion si se queda esperando (con timeout).
 */
class MainThreadBridge(
	private val mainThread: MainThreadExecutor,
	private val timeoutMs: Long,
) {
	fun <T> callOnMainThread(action: () -> T): T {
		if (mainThread.isMainThread()) return action()

		val future = CompletableFuture<T>()
		mainThread.execute {
			try {
				future.complete(action())
			} catch (t: Throwable) {
				future.completeExceptionally(t)
			}
		}

		return try {
			future.get(timeoutMs, TimeUnit.MILLISECONDS)
		} catch (e: TimeoutException) {
			throw PuenteException(503, "main_thread_timeout", "El hilo principal de Minecraft no respondio en ${timeoutMs}ms")
		} catch (e: ExecutionException) {
			val cause = e.cause ?: e
			if (cause is PuenteException) throw cause
			throw PuenteException(500, "main_thread_error", cause.message ?: cause.javaClass.name)
		}
	}
}
