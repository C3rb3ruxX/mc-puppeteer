package com.bonilla.puente.client

import com.bonilla.puente.CapturedMessage
import com.bonilla.puente.ChatLog
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents
import java.time.Instant

/**
 * Captura el chat con eventos de Fabric API, sin mixins y sin interceptar paquetes.
 *
 * Por que esto NO impacta el rendimiento:
 * - Los eventos se disparan **ya en el hilo principal**, asi que no hay ningun
 *   cruce de hilos ni ejecucion diferida.
 * - El unico trabajo dentro del callback es construir un objeto pequeño e
 *   insertarlo en un buffer acotado (`addLast` + un `removeFirst` ocasional).
 *   No hay E/S, ni locks compartidos con otras system's, ni asignaciones grandes.
 * - Si nadie consume por HTTP, el buffer descarta los mensajes viejos; la
 *   memoria queda acotada por configuracion.
 */
class ChatCapture(private val log: ChatLog) {

	fun register() {
		ClientReceiveMessageEvents.CHAT.register { message, _, sender, _, _ ->
			log.record(
				CapturedMessage(
					epochMillis = Instant.now().toEpochMilli(),
					kind = CapturedMessage.Kind.CHAT,
					text = message.string,
					sender = sender?.name(),
					overlay = false,
				)
			)
		}

		ClientReceiveMessageEvents.GAME.register { message, overlay ->
			log.record(
				CapturedMessage(
					epochMillis = Instant.now().toEpochMilli(),
					kind = if (overlay) CapturedMessage.Kind.SYSTEM else CapturedMessage.Kind.GAME,
					text = message.string,
					sender = null,
					overlay = overlay,
				)
			)
		}

		// Lo que el usuario escribe a mano, util para depurar y para que el
		// controlador externo sepa que envio el operador.
		ClientSendMessageEvents.CHAT.register { text -> log.recordSent(text) }
		ClientSendMessageEvents.COMMAND.register { text -> log.recordSent("/$text") }
	}
}
