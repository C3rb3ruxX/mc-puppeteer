package com.bonilla

import net.fabricmc.api.ModInitializer
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.resources.ResourceLocation
import org.slf4j.LoggerFactory

object McPuppeteer : ModInitializer {
	const val MOD_ID: String = "mc-puppeteer"

	private val LOGGER = LoggerFactory.getLogger(MOD_ID)

	override fun onInitialize() {
		// Solo se registra el identificador del mod aqui. Toda la logica (servidor
		// HTTP, puente con el cliente) vive en el source set `client` porque necesita
		// clases que solo existen en el cliente de Minecraft.
		LOGGER.info("mc-puppeteer inicializado (version {})", FabricLoader.getInstance().getModContainer(MOD_ID)
			.map { it.metadata.version.friendlyString }.orElse("desconocida"))
	}

	fun id(path: String): ResourceLocation
		= ResourceLocation.fromNamespaceAndPath(MOD_ID, path)
}
