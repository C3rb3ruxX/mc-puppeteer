import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// El script tiene una extension llamada `java`, asi que `java.net.URI` busca
// una propiedad `net` dentro de ella y no encuentra nada. Por eso se importan
// los tipos sueltos en vez de cualificarlos.
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

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

/**
 * Pone el Baritone de la variante *api* en `run/mods`, comprobando el checksum.
 *
 * Vive ahi y no en el classpath porque Baritone no se controla por API: el mod
 * le escribe un mensaje de chat con prefijo `#` y Baritone contesta por el mismo
 * chat, y ese reparto lo hace el mixin `MixinClientPlayNetHandler`, que solo
 * esta en la variante *api*. La de 1.20 que se usaba antes no lo traia.
 *
 * Y vive en `run/mods` porque `run/` esta en el .gitignore, asi que hay que
 * ponerlo a mano en cada clon o las instancias que arranca el panel salen sin
 * Baritone y los botones de minar no hacen nada, sin avisar de nada. Por eso
 * esta tarea, y por eso el checksum va clavado: es un jar de un tercero, y si
 * cambia algo tiene que gritar en vez de descargarse en silencio.
 *
 *     ./gradlew ensureBaritone
 *
 * Upstream no publica 1.21.5, asi que el jar viene del fork `smorbes/baritone`
 * (mod id `baritone-meteor`), no de c5-6-jared. Si en algun momento upstream lo
 * publique, se cambia URL, nombre y SHA aqui y ya esta.
 */
abstract class EnsureBaritone : DefaultTask() {

	@get:Input
	abstract val url: Property<String>

	@get:Input
	abstract val sha256Esperado: Property<String>

	@get:OutputFile
	abstract val destino: RegularFileProperty

	@TaskAction
	fun instalar() {
		val archivo = destino.get().asFile
		val esperado = sha256Esperado.get()

		// Ya esta y con el checksum correcto: no se vuelve a bajar. Sin este
		// chequeo, `up-to-date` de Gradle no serviria de nada, porque el jar
		// puede haberse sustituido a mano.
		if (archivo.isFile) {
			val actual = sha256(archivo)
			if (actual == esperado) {
				logger.lifecycle("Baritone ya esta en ${archivo.name} (sha256 correcto).")
				return
			}
			logger.lifecycle("El Baritone que hay no cuadra (sha256 $actual); se vuelve a bajar.")
			archivo.delete()
		}

		val dir = archivo.parentFile
		if (!dir.isDirectory && !dir.mkdirs()) {
			throw GradleException("No se pudo crear ${dir.absolutePath}")
		}

		val de = url.get()
		logger.lifecycle("Bajando $de")
		val conexion = URI(de).toURL().openConnection() as HttpURLConnection
		conexion.connectTimeout = 20_000
		conexion.readTimeout = 120_000
		conexion.instanceFollowRedirects = true
		if (conexion.responseCode / 100 != 2) {
			throw GradleException("No se pudo bajar el jar: HTTP ${conexion.responseCode} en $de")
		}
		conexion.inputStream.use { entrada -> entrada.copyTo(archivo.outputStream()) }

		val calculado = sha256(archivo)
		if (calculado != esperado) {
			// Se borra: dejar un jar sin verificar seria justo lo que la tarea
			// existe para evitar.
			archivo.delete()
			throw GradleException(
				"El jar descargado no es el esperado.\n" +
					"  url:             $de\n" +
					"  esperado sha256: $esperado\n" +
					"  descargado:      $calculado\n" +
					"No se ha instalado nada."
			)
		}
		logger.lifecycle("Baritone instalado en ${archivo.name} (sha256 verificado).")
	}

	/** `sha256` en minusculas, como lo publica GitHub en sus releases. */
	private fun sha256(fichero: File): String {
		val digest = MessageDigest.getInstance("SHA-256")
		fichero.inputStream().use { entrada ->
			val buffer = ByteArray(64 * 1024)
			while (true) {
				val leidos = entrada.read(buffer)
				if (leidos <= 0) break
				digest.update(buffer, 0, leidos)
			}
		}
		return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
	}
}

val baritoneVersion = "1.21.5"

tasks.register<EnsureBaritone>("ensureBaritone") {
	group = "setup"
	description = "Descarga el Baritone api de 1.21.5 a run/mods si no esta o no cuadra el checksum"
	url.set(
		"https://github.com/smorbes/baritone/releases/download/" +
			"$baritoneVersion/baritone-api-fabric-$baritoneVersion-SNAPSHOT.jar"
	)
	sha256Esperado.set("64e0d4e5672869a3652c3a233477975824a94bb18ab3393fd25c03a947bc9db1")
	destino.set(layout.projectDirectory.file("run/mods/baritone-api-fabric-$baritoneVersion.jar"))
}

// El enganche que evita tener que acordarse. `run/` esta en el .gitignore, asi
// que en una clonacion nueva el jar no esta y todo lo que arranque un cliente
// sale sin Baritone: los botones de minar mandan la orden, Baritone no la
// oye, y el panel no dice nada raro, solo que no ocurre nada.
//
// Con el jar ya puesto y el checksum correcto la tarea no hace red, solo
// comprueba el fichero, asi que engancharla no hace la build dependiente de
// internet. Solo descarga cuando falta o cuando algo lo ha cambiado.
listOf("build", "runClient").forEach { nombre ->
	tasks.named(nombre).configure { dependsOn("ensureBaritone") }
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
