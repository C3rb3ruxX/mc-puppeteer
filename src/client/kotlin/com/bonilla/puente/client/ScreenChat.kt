package com.bonilla.puente.client

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.ChatComponent

/**
 * Lee el chat que esta **en pantalla**, que no es lo mismo que el chat capturado
 * por eventos de red.
 *
 * Hace falta por Baritone. Cuando se le manda una orden, no contesta por el
 * servidor: escribe la respuesta directamente en el chat local del cliente, con
 * `Gui.chat.addMessage(...)`. Los eventos `ClientReceiveMessageEvents` de Fabric
 * API se disparan en la entrada de la red, asi que de ese camino no pasa nada y
 * `/chat` se queda vacio. El efecto era que una orden rechazada por Baritone
 * (por ejemplo `mine acacia_block 32`, porque en 1.21.5 ese bloque ya no se
 * llama asi) contestaba "Error at argument #2: Expected ForBlockOptionalMeta"
 * a un sitio donde nadie lo lee: el panel decia "ok" y no se podia saber si
 * Baritone habia hecho algo o no.
 *
 * Asi que aqui se leen las lineas ya formateadas del `ChatComponent`, que
 * incluyen el chat del servidor, los mensajes del juego y las respuestas locales
 * de Baritone.
 *
 * Se usa reflexion y no un mixin a proposito: el resto del mod se ha escrito
 * expresamente sin mixins, y un accessor para un unico campo no merece esa
 * infraestructura. El coste es que si Mojang renombra `allMessages` esto deja
 * de funcionar, pero falla con un mensaje claro en vez de romperse en silencio,
 * que es justo lo que se busca.
 */
object ScreenChat {

	private const val CAMPO = "allMessages"

	/**
	 * Devuelve las [limit] ultimas lineas del chat, de la mas antigua a la mas
	 * reciente, como texto plano.
	 *
	 * @throws IllegalStateException si el campo privado deja de existir.
	 */
	fun last(limit: Int): List<String> {
		if (limit <= 0) return emptyList()
		val chat: ChatComponent = Minecraft.getInstance().gui.getChat()

		val lineas = leer(chat) ?: throw IllegalStateException(
			"ya no se puede leer el chat en pantalla: ChatComponent no tiene el campo '$CAMPO'. " +
				"Ha cambiado la version de Minecraft; hay que actualizar ScreenChat."
		)

		// La lista interna se esta moviendo en el hilo principal, pero aqui ya
		// estamos en el: se copia igual, porque `drop` sobre la original crearia
		// una vista perezosa que el juego podria mutar mientras se serializa.
		val copia = ArrayList<Any?>(lineas)
		if (copia.size <= limit) copia else copia.subList(copia.size - limit, copia.size).toList()

		return copia.mapNotNull { mensaje ->
			runCatching {
				val contenido = mensaje!!.javaClass.getMethod("content").invoke(mensaje)
				val texto = contenido as net.minecraft.network.chat.Component
				texto.getString().takeIf { it.isNotBlank() }
			}.getOrNull()
		}
	}

	/**
	 * El campo es privado, asi que hay que buscarlo a mano. Se resuelve una vez y
	 * se cachea: buscarlo en cada peticion seria trabajo tirado en el peor sitio
	 * posible, que es una peticion HTTP.
	 */
	private var cache: java.lang.reflect.Field? = null

	private fun leer(chat: ChatComponent): List<Any?>? {
		cache?.let { return it.get(chat) as? List<Any?> }

		val encontrado = runCatching {
			ChatComponent::class.java.getDeclaredField(CAMPO).apply { isAccessible = true }
		}.getOrNull() ?: return null

		cache = encontrado
		return encontrado.get(chat) as? List<Any?>
	}
}
