package com.bonilla.puppeteer.http

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException

/** Utilidades JSON para la capa HTTP. Sin dependencias externas: usa el Gson que ya trae Minecraft. */
object Json {
	private val gson: Gson = GsonBuilder().serializeNulls().create()
	private val pretty: Gson = GsonBuilder().serializeNulls().setPrettyPrinting().create()

	fun write(value: Any?): String = gson.toJson(value)

	fun writePretty(value: Any?): String = pretty.toJson(value)

	fun parse(text: String): JsonObject = try {
		val element: JsonElement = JsonParser.parseString(text)
		if (element !is JsonObject) throw JsonSyntaxException("el cuerpo no es un objeto JSON")
		element
	} catch (e: JsonSyntaxException) {
		throw HttpError(400, "invalid_json", "JSON invalido: ${e.message}")
	}

	fun obj(): JsonObject = JsonObject()

	fun arr(): JsonArray = JsonArray()

	fun JsonObject.optString(key: String): String? {
		val el = get(key) ?: return null
		if (el.isJsonNull) return null
		if (!el.isJsonPrimitive) throw HttpError(400, "invalid_field", "el campo '$key' debe ser texto")
		return el.asString
	}

	/**
	 * Exige un campo de texto presente. Devuelve el valor **recortado** pero
	 * admite que quede vacio: la validacion de "vacio" la hace quien conoce el
	 * dominio, para poder responder con un codigo especifico
	 * (`empty_message`, `empty_command`, ...) en vez de un generico.
	 */
	fun JsonObject.requireString(key: String): String =
		optString(key)?.trim()
			?: throw HttpError(400, "missing_field", "falta el campo obligatorio '$key'")

	fun JsonObject.optInt(key: String): Int? {
		val el = get(key) ?: return null
		if (el.isJsonNull) return null
		if (!el.isJsonPrimitive) throw HttpError(400, "invalid_field", "el campo '$key' debe ser un numero")
		return try {
			el.asInt
		} catch (e: NumberFormatException) {
			throw HttpError(400, "invalid_field", "el campo '$key' no es un entero valido")
		}
	}

	fun JsonObject.optBoolean(key: String, fallback: Boolean): Boolean {
		val el = get(key) ?: return fallback
		if (el.isJsonNull) return fallback
		if (!el.isJsonPrimitive) throw HttpError(400, "invalid_field", "el campo '$key' debe ser booleano")
		return el.asBoolean
	}
}

/** Error con codigo HTTP que la capa superior traduce tal cual. */
class HttpError(val status: Int, val code: String, message: String) : RuntimeException(message)
