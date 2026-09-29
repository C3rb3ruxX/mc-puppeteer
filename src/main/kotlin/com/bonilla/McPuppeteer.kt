package com.bonilla

import com.sun.net.httpserver.HttpServer
import net.fabricmc.api.ModInitializer
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object McPuppeteer : ModInitializer {
	const val MOD_ID: String = "mc-puppeteer"

	private val LOGGER = LoggerFactory.getLogger(MOD_ID)

	private val PORT = 8080

	private lateinit var http_server: HttpServer
	private lateinit var executor: ExecutorService

	val httpThread = Executors.newSingleThreadExecutor {
		Thread(it, "my-mod-http-server").apply {
			isDaemon = true
		}
	}

	override fun onInitialize() {
		httpThread.submit {
			startHttpServer()
		}
	}

	private fun registerFunctions(){
		
	}

	private fun startHttpServer() {
		val server = HttpServer.create(
			InetSocketAddress("127.0.0.1", 8080),
			0
		)

		server.createContext("/") { exchange ->
			val response = "Hello from Minecraft!"

			exchange.sendResponseHeaders(
				200,
				response.toByteArray().size.toLong()
			)

			exchange.responseBody.use {
				it.write(response.toByteArray())
			}
		}

		server.executor = Executors.newCachedThreadPool()
		server.start()

		println("Web UI running at http://127.0.0.1:8080")
	}

	fun id(path: String): Identifier
		= Identifier.fromNamespaceAndPath(MOD_ID, path)
}
