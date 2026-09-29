pluginManagement {
	repositories {
		maven {
			name = "Fabric"
			url = uri("https://maven.fabricmc.net/")
		}
		mavenCentral()
		gradlePluginPortal()
	}

	plugins {
		// Minecraft 1.21.5 sigue llegando ofuscado de Mojang, asi que hace falta
		// el plugin que remapea: `net.fabricmc.fabric-loom` es el alias "sin
		// remapeo" (para las versiones que Mojang ya publica legibles, como
		// 26.x) y no vale aqui.
		id("net.fabricmc.fabric-loom-remap") version providers.gradleProperty("loom_version")
	}
}

// Should match your modid
rootProject.name = "mc-puppeteer"
