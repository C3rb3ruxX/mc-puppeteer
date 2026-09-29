# Migración a Minecraft 1.21.5

> Registro de la bajada de **Minecraft 26.3 a 1.21.5** (rama `migrate/1.21.5`).
> No cambia el comportamiento del mod: cambia la versión contra la que se
> compila. Este documento esta para que el próximo que toque una firma no tenga
> que repetir los mismos tres dias de `javap`.

---

## 1. Por que 1.21.5 y no bajar un poco

El proyecto venia compilando contra 26.3, la version que trae el juego de
turno. Bajar a 1.21.5 es una decision de entorno (versiones mas antiguas,
todavia largementemente en uso) y no una mejora tecnica: 1.21.5 es **anterior**,
asi que parte del codigo escrito para 26.x no aplica tal cual.

Lo que si cambia a favor: 1.21.5 sigue siendo una version con comunidad, Fabric
API y mappings published, y compila en Java 21 en vez de 25.

## 2. Versiones

| Propiedad | Antes (26.3) | Ahora (1.21.5) |
|---|---|---|
| `minecraft_version` | `26.3` | `1.21.5` |
| `fabric_api_version` | `0.161.0+26.3` | `0.128.2+1.21.5` |
| `loader_version` | `0.19.5` | `0.19.5` (sin cambio) |
| `fabric_kotlin_version` | `1.14.1+kotlin.2.4.20` | `1.14.1+kotlin.2.4.20` (sin cambio) |
| `loom_version` | `1.18-SNAPSHOT` / `1.18.2` | `1.17.21` |
| Java (target y CI) | 25 | 21 |
| Gradle | 9.7.1 | 9.7.1 (sin cambio) |

`fabric-loader` 0.19.5 y FLK 1.14.1 son los mismos: ambos soportan 1.21.5
(Fabric API 0.128.2+1.21.5 requiere Loader >= 0.16.10, y FLK 1.14.1 requiere
Loader >= 0.19.5).

### Por que Loom 1.17.21 y no 1.18.2

Loom 1.18 esta compilado para **Java 25**, asi que el daemon de Gradle tiene que
arrancar en 25 aunque el mod se compile para 21:

```
> Could not resolve net.fabricmc:fabric-loom:1.18.2.
   > Dependency requires at least JVM runtime version 25. This build uses a Java 21 JVM.
```

Loom 1.17.21 esta compilado para Java 21 (bytecode mayor 65), corre en el mismo
JDK que 1.21.5 necesita, y soporta Gradle 9. Se eligio el 1.17.21 (el ultimo de
esa linea) para que `./gradlew build` funcione sin tocar nada del entorno: da
igual si el JDK por defecto del desarrollador es el 21 o el 25.

## 3. Cambios en el build

### 3.1 El plugin de Loom: `net.fabricmc.fabric-loom` -> `...-remap`

Este cambio es el que mas confunde, porque **el error no menciona mappings**.
Desde Loom 1.18 hay cuatro plugin ids distintos:

| Id | Que hace |
|---|---|
| `net.fabricmc.fabric-loom` | **sin remapeo** (`disableObfuscation = true`): para las versiones que Mojang ya publica legibles (26.x) |
| `net.fabricmc.fabric-loom-remap` | remapea de intermediary al namespace elegido: para 1.21.5, que si llega ofuscada |
| `fabric-loom` | el plugin interno, sin terminar en `-remap` ni `-no-remap` |
| `net.fabricmc.fabric-loom-no-remap` | alias explicito del anterior |

El proyecto usaba el id "sin remapeo", porque venia de 26.3. Al bajar la version
hay que cambiarlo por el de remapeo, en los **dos** sitios donde aparece: el
`pluginManagement` de `settings.gradle.kts` y el bloque `plugins` de
`build.gradle.kts`.

Si se olvida uno de los dos, el sintoma es este, que no dice nada de mappings:

```
* What went wrong:
A problem occurred configuring root project 'mc-puppeteer'.
> Failed to setup Minecraft, java.lang.IllegalArgumentException:
  Configuration 'mappings' has no dependencies
```

### 3.2 Mappings oficiales de Mojang, y donde se declaran

Con mappings de Yarn habria que reescribir cada clase del puente
(`MinecraftClient`, `ClientPlayNetworkHandler`, `Text`, `Identifier`, ...). Con
los **mappings oficiales de Mojang** los nombres coinciden casi todos con los que
ya usaba el codigo, porque 26.x tambien es Mojang lo que nombra las clases. Asi
que:

```kotlin
dependencies {
    minecraft("com.mojang:minecraft:${...}")
    mappings(loom.officialMojangMappings())
    ...
}
```

Dos detalles que cuestan un rato si no se saben:

1. Va en el bloque `dependencies`, **no** dentro de `loom { }`. El metodo
   devuelve un `Dependency` que hay que añadir a la configuracion `mappings`;
   si se llama dentro de `loom { }` y se descarta el resultado, la configuracion
   se queda vacia y aparece el error de arriba.
2. Con mappings de Mojang hay que aceptar la licencia: Loom avisa por log
   ("Using of the official minecraft mappings is at your own risk!"). Es
   exatamente lo que hace `fabric-example-mod` en su rama de 1.21.5.

### 3.3 `modImplementation` en vez de `implementation`

Loader, Fabric API y FLK son mods, no librerias. Con `implementation`, Loom los
deja como estan (en intermediary) mientras que las clases de Minecraft ya estan
remapeadas, y el source set `client` revienta con:

```
e: ChatCapture.kt:24:46 Cannot access class 'net.minecraft.class_2561'.
  Check your module classpath for missing or conflicting dependencies.
```

Con `modImplementation`, Loom los remapea al namespace elegido y ambos source sets
(compilan con mojmap) cuadran. El mod sigue funcionando igual en el juego: el jar
publicado va con intermediary, como se comprueba desensamblando
`ClientBridge.class` (llama a `net/minecraft/class_310.method_1551`).

### 3.4 Java 21 sin toolchain

`options.release = 21` para Kotlin y Java, y `java-version: '21'` en CI. Sin
`toolchain {}`: pedir una toolchain hace que Gradle intente descargar un JDK si no
la encuentra, y en una maquina sin red eso convierte un build normal en un fallo.

### 3.5 `fabric.mod.json`

```
"minecraft": "~1.21.5",
"java": ">=21",
```

## 4. Cambios de API (26.3 -> 1.21.5)

Todos verificados con `javap` sobre los jars remapeados que deja Loom en
`~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-{clientonly,common}/1.21.5-*/`,
no de memoria.

### 4.1 Lo que cambio de verdad

| En 26.3 | En 1.21.5 | Detalle |
|---|---|---|
| `net.minecraft.resources.Identifier` | `ResourceLocation` | unico cambio en el source set `main` |
| `ResourceKey.identifier()` | `ResourceKey.location()` | |
| `Minecraft.gui.screen()` | `Minecraft.screen` | **vuelve a ser campo publico** |
| `Minecraft.setScreenAndShow(s)` | `Minecraft.setScreen(s)` | |
| `Minecraft.disconnectFromWorld(motivo)` | `disconnect()` + `setScreen(TitleScreen())` | sin equivalente de una llamada, ver abajo |
| `Minecraft.hasSingleplayerServer()` | `Minecraft.getSingleplayerServer()` | |
| `Minecraft.getGameProfile()` | `Minecraft.getUser()` | `User.getName()`, `getProfileId()`, `getAccessToken()` |
| `User(name, uuid, token, xuid, clientId)` | `User(name, uuid, token, xuid, clientId, type)` | el 6.º parametro es `User.Type` |
| `profile.name()` / `profile.id()` | `profile.name` / `profile.id` | authlib 10 (record) -> authlib 6.x (clase con getters) |
| `WorldVersion.name()` | `WorldVersion.getName()` | en 1.21.5 `WorldVersion` es una interfaz, no un record |
| anotaciones de nulabilidad de MC | tipos de plataforma | los jars de 1.21.5 llegan a Kotlin sin anotaciones: `?:` y `null` vuelven a estar permitidos |

### 4.2 "Salir al titulo": tres pasos en lugar de uno

Lo unico que de verdad cambia el comportamiento es la desconexion. En 26.3 una
sola llamada (`disconnectFromWorld(Component)`) hacia todo: avisar al servidor del
motivo, cerrar la sesion y mostrar el titulo. En 1.21.5 no existe tal metodo, asi
que hay que componerlo con los mismos tres pasos que usa el propio juego en
`PauseScreen.onDisconnect()`:

```kotlin
mc.getConnection()?.connection?.disconnect(Component.translatable("menu.quitting"))
mc.disconnect()
mc.setScreen(TitleScreen())
```

- El primer paso es opcional en cuanto al resultado (si se omite, el servidor ve
  un cierre generico), pero se mantiene para que el log del servidor diga lo
  mismo que cuando el usuario pulsa "Desconectar" en el juego.
- `Minecraft.disconnect()` sin argumentos es `disconnect(new ProgressScreen(true), false)`:
  cierra la conexion y desmonta el mundo, y **espera a que el servidor integrado
  termine de guardar**, pero deja una pantalla de progreso.
- El tercer paso pone el titulo, que es lo que espera quien llama a la API.

Todo esto vive en `ClientBridge.leaveWorld()`, que usan tanto `POST /disconnect`
como `POST /connect` (cerrar la sesion actual antes de abrir otra).

### 4.3 `User.Type`: por que `LEGACY`

El constructor de `User` en 1.21.5 pide un `User.Type` (`LEGACY`, `MOJANG` o
`MSA`). No es cosmetico: `Minecraft.createUserApiService()` decide el servicio de
autenticacion con el, y con `MSA` el juego intentaria autenticar contra Yggdrasil
con un token que no corresponde al UUID offline que estamos inventando. `LEGACY`
es la sesion sin autenticar, y es la correcta para una identidad offline.

### 4.4 Lo que no cambio (y se verifico igualmente)

| API | Estado en 1.21.5 |
|---|---|
| `ClientCommonPacketListenerImpl.sendChat(String)` / `.sendCommand(String)` | iguales (y por eso Baritone sigue funcionando igual: intercepta el chat) |
| `LocalPlayer.respawn()` | igual |
| `ServerAddress(String, int)` | igual |
| `ServerData(String name, String ip, ServerData.Type)` + campos `name`, `ip`, `players` | igual |
| `ConnectScreen.startConnecting(Screen, Minecraft, ServerAddress, ServerData, boolean, TransferState)` | igual; en 1.21.5 el 6.º parametro es el `TransferState` y admite `null` |
| `UUIDUtil.createOfflinePlayerUUID(String)` | igual: `nameUUIDFromBytes("OfflinePlayer:" + nombre)` |
| `SharedConstants.MAX_PLAYER_NAME_LENGTH` | igual |
| `PlayerInfo.getProfile()` / `getLatency()` / `getTabListDisplayName()` | igual |
| `ClientLevel.players()` | igual (declarado en `EntityGetter`, devuelve `List<AbstractClientPlayer>`) |
| `ClientReceiveMessageEvents.CHAT` | `(Component, PlayerChatMessage, GameProfile, MessageType.Parameters, Instant)`; `GameProfile` con getters |
| `ClientLifecycleEvents.CLIENT_STOPPING` | igual |

### 4.5 La trampa de la reflexion sobre `User`

`Minecraft.user` es `private final User` y `User` no tiene setters, asi que
cambiar la identidad offline sigue siendo sustituir el objeto por reflexion. El
campo se localiza **por tipo**, no por nombre, porque en runtime Fabric remapea a
intermediary y alli no se llama `user`.

Verificado en el jar de 1.21.5 que hay **exactamente un** campo de tipo
`net.minecraft.client.User` en `Minecraft` (`userApiService` es de otro tipo), que
`setAccessible(true)` responde y que `Field.set` sobre un `final` no estatico
funciona en Java 21, que es el runtime de 1.21.5.

## 5. Como se verifico

1. **`javap` sobre los jars de 1.21.5**, antes de tocar una sola linea: con
   mappings de Mojang los nombres son los reales, asi que la firma se lee tal cual
   se va a escribir.
2. **`./gradlew build`**: compila `main`, `client` y produce el jar remapeado.
   Comprobado que el jar lleva nombres intermediary y no mojmap.
3. **`./gradlew runDatagen`**: arranca el juego de verdad (sin ventana) con
   Loader 0.19.5, Fabric API 0.128.2+1.21.5 y FLK. Confirma que el mod carga, que
   las mappings son las correctas y que el nucleo HTTP levanta dentro del
   cliente (`mc-puppeteer escuchando en http://127.0.0.1:25580`).
4. **Prueba de humo** (`03-testing.md`): 156 aserciones, todas en verde.

## 6. Lo que sigue sin verificar

Lo mismo que antes de la migracion, porque no depende de la version:

- Que el servidor acepte de verdad la identidad offline y el paquete de
  reaparicion: hace falta un cliente conectado a un servidor real.
- Que Baritone este escuchando: la prueba comprueba que se llama a `sendChat`, no
  que el mod lo intercepte.
- El comportamiento en transiciones de mundo (ver `03-testing.md`, "El hueco que
  mas me preocupa").

Y una cosa que si es nueva: **`POST /disconnect` y `POST /connect` nunca se
ejecutaron contra el juego**. Antes eran una llamada a metodo; ahora son tres
llamadas que tienen que encadenar bien. Merece una prueba manual en cuanto haya
cliente y servidor.
