import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
	id("net.fabricmc.fabric-loom-remap")
	`maven-publish`
	id("org.jetbrains.kotlin.jvm") version "2.4.20"
}

repositories {
	// Add repositories to retrieve artifacts from in here.
	// You should only use this when depending on other mods because
	// Loom adds the essential maven repositories to download Minecraft and libraries from automatically.
	// See https://docs.gradle.org/current/userguide/declaring_repositories.html
	// for more information about repositories.
}

loom {
	splitEnvironmentSourceSets()

	mods {
		register("mc-puppeteer") {
			sourceSet(sourceSets.main.get())
			sourceSet(sourceSets.getByName("client"))
		}
	}
}

fabricApi {
	configureDataGeneration {
		client = true
		modId = "mc-puppeteer"
	}
}

dependencies {
	// To change the versions see the gradle.properties file
	minecraft("com.mojang:minecraft:${providers.gradleProperty("minecraft_version").get()}")
	// Minecraft 1.21.5 se distribuye ofuscado, asi que hacen falta mappings
	// para poder compilar. Se usan las oficiales de Mojang (mojmap), las mismas
	// que usa fabric-example-mod para 1.21.5, porque los nombres de clase y
	// metodo (`Minecraft`, `ClientPacketListener`, `Component`,
	// `ResourceLocation`, ...) coinciden con los que ya usa el codigo de este
	// mod, y no con los de Yarn.
	mappings(loom.officialMojangMappings())
	// `modImplementation` (y no `implementation`) porque Loader, Fabric API y
	// FLK son mods: Loom los remapea de intermediary al namespace elegido
	// (mojmap). Con `implementation` el source set `client` los veria todavia en
	// intermediary (`net.minecraft.class_2561`) y no casaria con las clases de
	// Minecraft, que ya vienen remapeadas.
	modImplementation("net.fabricmc:fabric-loader:${providers.gradleProperty("loader_version").get()}")

	// Fabric API. This is technically optional, but you probably want it anyway.
	modImplementation("net.fabricmc.fabric-api:fabric-api:${providers.gradleProperty("fabric_api_version").get()}")
	modImplementation("net.fabricmc:fabric-language-kotlin:${providers.gradleProperty("fabric_kotlin_version").get()}")
}

tasks.processResources {
	val version = version
	inputs.property("version", version)

	filesMatching("fabric.mod.json") {
		expand("version" to version)
	}
}

// Minecraft 1.21.5 corre sobre Java 21: se compila para 21, que es lo que
// espera el juego en runtime. Se usa `release`/`jvmTarget` en vez de un
// toolchain para que tambien compile con un JDK mas nuevo instalado.
tasks.withType<JavaCompile>().configureEach {
	options.release = 21
}

kotlin {
	compilerOptions {
		jvmTarget = JvmTarget.JVM_21
	}
}

java {
	// Loom will automatically attach sourcesJar to a RemapSourcesJar task and to the "build" task
	// if it is present.
	// If you remove this line, sources will not be generated.
	withSourcesJar()

	sourceCompatibility = JavaVersion.VERSION_21
	targetCompatibility = JavaVersion.VERSION_21
}

tasks.jar {
    val projectName = project.name
    inputs.property("projectName", projectName)

    from("LICENSE") {
        rename { "${it}_$projectName" }
    }
}

/**
 * Panel de control de instancias.
 *
 * Es un programa independiente que usa las clases de `main` (solo necesita el
 * Gson que ya trae Minecraft), asi que se puede levantar sin arrancar el juego:
 *
 *     ./gradlew runDashboard
 *     ./gradlew runDashboard --args="--port 25590"
 */
tasks.register<JavaExec>("runDashboard") {
    group = "application"
    description = "Arranca el panel web de control de instancias en http://127.0.0.1:25590"
    mainClass.set("com.bonilla.puente.dashboard.Dashboard")
    classpath = sourceSets.main.get().runtimeClasspath
}

// configure the maven publication
publishing {
	publications {
		register<MavenPublication>("mavenJava") {
			from(components["java"])
		}
	}

	// See https://docs.gradle.org/current/userguide/publishing_maven.html for information on how to set up publishing.
	repositories {
		// Add repositories to publish to here.
		// Notice: This block does NOT have the same function as the block in the top level.
		// The repositories here will be used for publishing your artifact, not for
		// retrieving dependencies.
	}
}
