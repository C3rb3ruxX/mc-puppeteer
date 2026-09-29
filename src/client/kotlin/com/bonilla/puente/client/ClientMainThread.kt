package com.bonilla.puente.client

import com.bonilla.puente.MainThreadExecutor
import net.minecraft.client.Minecraft

/**
 * [MainThreadExecutor] respaldado por el event loop de Minecraft.
 *
 * `Minecraft` hereda de `ReentrantBlockableEventLoop<Runnable>`, que ya es un
 * `Executor`: `execute` encola en la cola del hilo de render y `isSameThread`
 * permite detectar si el llamador ya esta en el hilo correcto.
 */
object ClientMainThread : MainThreadExecutor {

	/**
	 * `Minecraft.getInstance()` esta anotado como no nulo: `instance` se asigna en
	 * el propio constructor de `Minecraft`, antes de que Fabric invoque los
	 * entrypoints de cliente. Por eso no hace falta ninguna guarda.
	 */
	override fun execute(task: Runnable) {
		Minecraft.getInstance().execute(task)
	}

	override fun isMainThread(): Boolean = Minecraft.getInstance().isSameThread
}
