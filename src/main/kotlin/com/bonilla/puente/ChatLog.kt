package com.bonilla.puente

import java.util.ArrayDeque

/**
 * Buffer circular acotado de mensajes de chat.
 *
 * - `record` se llama desde el hilo principal de render: solo hace `addLast` y
 *   un `removeFirst` occasiona. Nunca bloquea, asi que no puede bajar los FPS.
 * - `snapshot`/`drain` se llaman desde los hilos HTTP.
 *
 * El lock es un `synchronized` sobre un objeto privado con secciones de
 * unas pocas nanosegundos; la contension es practicamente nula porque solo un
 * hilo escribe y los lectores son pocos. No se usa una cola bloqueante
 * precisamente para no hacer *wait* al hilo de render.
 */
class ChatLog(private val capacity: Int) {

	private val lock = Any()
	private val buffer = ArrayDeque<CapturedMessage>(capacity)
	private val recentSent = ArrayDeque<String>(32)
	private var dropped = 0L

	@Synchronized
	fun record(message: CapturedMessage) {
		if (buffer.size >= capacity) {
			buffer.removeFirst()
			dropped++
		}
		buffer.addLast(message)
	}

	@Synchronized
	fun recordSent(text: String) {
		if (recentSent.size >= 32) recentSent.removeFirst()
		recentSent.addLast(text)
	}

	/** Copia los ultimos [limit] mensajes sin consumirlos. Mas antiguo primero. */
	fun snapshot(limit: Int = capacity): List<CapturedMessage> = synchronized(lock) {
		val take = minOf(limit.coerceIn(0, capacity), buffer.size)
		buffer.toList().subList(buffer.size - take, buffer.size)
	}

	/**
	 * Consume hasta [limit] mensajes. Con [all] se entrega el historico
	 * completo accumulated; sin el, solo lo que llego desde la ultima llamada.
	 */
	fun drain(limit: Int, all: Boolean): List<CapturedMessage> = synchronized(lock) {
		if (all) {
			val out = buffer.toList()
			buffer.clear()
			return out
		}
		val take = minOf(limit.coerceAtLeast(0), buffer.size)
		val out = ArrayList<CapturedMessage>(take)
		repeat(take) { out.add(buffer.removeFirst()) }
		out
	}

	fun recentSent(): List<String> = synchronized(lock) { recentSent.toList() }

	fun size(): Int = synchronized(lock) { buffer.size }

	fun droppedCount(): Long = synchronized(lock) { dropped }

	fun clear() = synchronized(lock) {
		buffer.clear()
		recentSent.clear()
		dropped = 0
	}
}
