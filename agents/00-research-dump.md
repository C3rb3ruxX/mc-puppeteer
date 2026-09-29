# Dump de investigación — mc-puppeteer (Fabric MC)

> Documento generado **antes** de escribir código. Contiene todo lo investigado
> sobre el estado del proyecto y las APIs reales de Minecraft.
> Cualquier decisión de diseño posterior debe justificarse contra este documento.
>
> **Este archivo es una foto histórica del análisis previo.** Varias cosas se
> Movieron al escribir el código. Donde la implementación difiere del plan que
> aquí se propone, la lista está en `02-implementacion.md` §11.
>
> **Actualizado tras la migración a 1.21.5** (rama `migrate/1.21.5`): las
> secciones que nombraban 26.3 se han reescrito contra las firmas reales de
> 1.21.5, verificadas con `javap`. El resumen de la migración está en
> `06-migracion-1.21.5.md`.

---

## 1. Objetivo declarado por el usuario

1. El cliente de Minecraft debe abrir un **servidor HTTP** en un **hilo aparte**.
2. Ese servidor HTTP debe poder:
   - **enviar comandos** al servidor de Minecraft conectado,
   - **enviar chat** al servidor de Minecraft conectado,
   - **recibir** lo que ocurre en el cliente (chat recibido, estado, etc.),
   - **conectar** a un servidor arbitrario que se le envíe por HTTP.
3. Restricción dura: **no impacting el rendimiento de Minecraft**.
4. Por lo tanto hay que **controlar el Minecraft entero**.

## 2. Restricción de diseño más importante

El requisito #3 (no impactar el rendimiento) se traduce en tres reglas que
condicionan toda la arquitectura:

| Regla | Motivo |
|---|---|
| **Ningún hilo HTTP puede tocar la API de Minecraft.** | La API de MC (y en especial `ClientPacketListener`) asume que se ejecuta en el hilo de render/cliente. Llamarla desde otro hilo produce races y crashes. |
| **Toda acción que mute el mundo se encola al hilo principal** vía `Minecraft.execute { }`. | Único mecanismo soportado para cruzar de hilo. |
| **La captura de eventos debe ser O(1) y no bloqueante** (`offer` a cola concurrente, tamaño acotado). | El callback de chat corre en el hilo de render; bloquearlo ahí baja los FPS. |

## 3. Estado actual del repositorio

Proyecto recién generado desde la plantilla oficial de Fabric
(`fabric-example-mod`), **sin ninguna funcionalidad implementada**.

### Árbol de fuentes

```
src/
├── main/
│   ├── java/com/bonilla/mixin/ExampleMixin.java
│   ├── kotlin/com/bonilla/McPuppeteer.kt            (ModInitializer)
│   └── resources/
│       ├── fabric.mod.json
│       ├── mc-puppeteer.mixins.json
│       └── assets/mc-puppeteer/icon.png
└── client/
    ├── java/com/bonilla/client/mixin/ExampleClientMixin.java
    ├── kotlin/com/bonilla/client/PuenteClient.kt   (ClientModInitializer)
    ├── kotlin/com/bonilla/client/McPuppeteerDataGenerator.kt
    └── resources/mc-puppeteer.client.mixins.json
```

### Versiones (gradle.properties)

| Propiedad | Valor |
|---|---|
| `minecraft_version` | `1.21.5` |
| `loader_version` | `0.19.5` |
| `loom_version` | `1.17.21` |
| `fabric_kotlin_version` | `1.14.1+kotlin.2.4.20` |
| `fabric_api_version` | `0.128.2+1.21.5` |
| `version` (mod) | `1.0.0` |
| `group` | `com.bonilla` |
| Java / Kotlin target | 21 |
| Kotlin plugin | `2.4.20` |

### Build

- `splitEnvironmentSourceSets()` está **activo** → existen `main` y `client`.
- Mod registrado con **ambas** source sets (`main` + `client`).
- `environment: "*"` en `fabric.mod.json` (aunque el mod es client-only en la práctica).
- Entry points declarados con `"adapter": "kotlin"`.
- Datagen de cliente habilitado (`configureDataGeneration { client = true }`).
- CI en `.github/workflows/build.yml` (solo build, no pruebas), con Java 21.
- Gradle: `org.gradle.jvmargs=-Xmx1G`, `parallel=true`, `configuration-cache=true`.
- Plugin de Loom: `net.fabricmc.fabric-loom-remap` (**no** `net.fabricmc.fabric-loom`,
  que es el alias "sin remapeo" pensado para las versiones que Mojang ya publica
  legibles). Mappings: `mappings(loom.officialMojangMappings())`, los oficiales.

---

## 4. HALLAZGO CRÍTICO — con qué nombres hay que llamar a Minecraft

Verificado por inspección directa del jar remapeado de 1.21.5
(`~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-{clientonly,common}/1.21.5-*/`).

1.21.5 se distribuye **ofuscada**, así que hay que elegir un juego de mappings
antes de compilar. Este proyecto usa las **mappings oficiales de Mojang**
(mojmap), con lo que se compila contra los nombres reales de las clases de
Minecraft:

| Nombre de Yarn (el de los tutoriales de 1.20/1.21) | Nombre real en 1.21.5 (mojmap) | Paquete real |
|---|---|---|
| `MinecraftClient` | **`Minecraft`** | `net.minecraft.client.Minecraft` |
| `ClientPlayNetworkHandler` | **`ClientPacketListener`** | `net.minecraft.client.multiplayer.ClientPacketListener` |
| `Identifier` | **`ResourceLocation`** | `net.minecraft.resources.ResourceLocation` |
| `LocalPlayer` | `LocalPlayer` (igual) | `net.minecraft.client.player.LocalPlayer` |
| `ClientWorld` | **`ClientLevel`** | `net.minecraft.client.multiplayer.ClientLevel` |
| `ServerAddress` | `ServerAddress` (igual) | `net.minecraft.client.multiplayer.resolver.ServerAddress` |
| `ChatHud` | **`ChatComponent`** | `net.minecraft.client.gui.components.ChatComponent` |
| `ClientLifecycleEvents` | igual | `net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents` |

Si el proyecto compila con Yarn en vez de mojmap, hay que invertir **toda** esta
tabla. El template inicial (`McPuppeteer.kt`) importaba
`net.minecraft.resources.Identifier`, o sea que venia escrito para la nomenclatura
de 26.x; en 1.21.5 ese import pasa a ser `ResourceLocation` (único cambio de
`main` que hizo falta).

Lo que **no** cambia entre 1.21.5 y 26.x es el nombre de estas clases: ambas
versiones usan los nombres de Mojang. Por eso el código escrito para 26.x casi
compilaba tal cual aquí; los cambios reales están en los métodos, no en las
clases (ver `06-migracion-1.21.5.md`).

---

## 5. APIs verificadas (firma exacta, vía `javap` sobre el jar deobf)

### 5.1 Hilo principal — `net.minecraft.client.Minecraft`

```java
public class Minecraft extends net.minecraft.util.thread.ReentrantBlockableEventLoop<Runnable>
        implements com.mojang.blaze3d.platform.WindowEventHandler {
    public static Minecraft getInstance();
    public void execute(Runnable);          // heredado de BlockableEventLoop
    public boolean isSameThread();          // ídem
    public ClientPacketListener getConnection();   // null si no hay servidor
    public ServerData getCurrentServer();
    public LocalPlayer player;              // campo público
    public ClientLevel level;               // campo público
    public Screen screen;                   // campo público
    public void setScreen(Screen);
    public void disconnect();                        // = disconnect(new ProgressScreen(true), false)
    public void disconnect(Screen);
    public void disconnect(Screen, boolean);
    public IntegratedServer getSingleplayerServer();
    public boolean isLocalServer();
    public User getUser();                  // User.getName() / getProfileId() / getAccessToken()
    public boolean isWindowActive();
    public int getFps();
}
```

Puntos de atención:
- **`setScreen(Screen)` sí existe** (no hay `setScreenAndShow`, que era el nombre
  en 26.x).
- **No hay `disconnectFromWorld(Component)`**, que era la forma de "salir al
  título" en 26.x. En 1.21.5 esa operación se compone de tres pasos, los mismos
  que hace `PauseScreen.onDisconnect()`: `Connection.disconnect(motivo)` +
  `Minecraft.disconnect()` + `setScreen(TitleScreen())`. Está encapsulado en
  `ClientBridge.leaveWorld()`.
- `Minecraft` **no** tiene `getGameProfile()`: la identidad se lee de
  `getUser()`, y su campo `private final User user` se sustituye por reflexión
  (mismo truco que en 26.x).
- `execute(Runnable)` viene de `BlockableEventLoop`; también existen
  `submit(Runnable)`, `submit(Supplier)`, `executeIfPossible`, `schedule(R)`,
  `isSameThread()`. `execute` es el correcto para acciones diferidas (no bloqueantes).
- `getConnection()` es `ClientPacketListener` en 1.21.5 también, no
  `ClientCommonPacketListenerImpl`. Devuelve `null` en el menú principal.

### 5.2 Envío de chat y comandos — `ClientPacketListener`

```java
public void sendChat(java.lang.String);   // sin "/" delante
public void sendCommand(java.lang.String); // SIN la barra: "list", no "/list"
public boolean isAcceptingMessages();
public Connection getConnection();
public CommandDispatcher<ClientSuggestionProvider> getCommands();
```

**Regla crítica de formato:** `sendCommand` espera el comando **sin** la
barra inicial. `sendChat` espera el texto **sin** barra. Quien llama por HTTP
debe ser explícito: campos separados `message` (chat) y `command` (comando),
no un único campo ambiguo. Elegir explícitamente evita que un chat que empieza
por `/` se interprete como comando o al revés.

### 5.3 Conexión a un servidor arbitrario

`net.minecraft.client.gui.screens.ConnectScreen` (factory estática):

```java
public static void startConnecting(
        Screen parent,
        Minecraft minecraft,
        ServerAddress address,
        ServerData serverData,
        boolean quickPlay,
        TransferState transferState
);
```

`net.minecraft.client.multiplayer.resolver.ServerAddress`:
```java
public ServerAddress(String host, int puerto);   // ctor usado por el puente
public static ServerAddress parseString(String);   // "host:puerto" o "host"
public static boolean isValidAddress(String);
public String getHost(); public int getPort();
```

`net.minecraft.client.multiplayer.ServerData`:
```java
public ServerData(String name, String ip, ServerData.Type type);
public ServerData.Type type();  // SERVER / OTHER
public String name; public String ip;                              // campos públicos
public ServerStatus.Players players;                               // record -> max()
```

→ Para "conectarse al servidor que se mande" por HTTP: construir
`ServerAddress.parseString(input)` + `ServerData` y llamar a
`ConnectScreen.startConnecting(...)` **desde el hilo principal**.

### 5.4 Captura de chat — eventos Fabric API

Disponibles en `fabric-message-api-v1` (`ClientReceiveMessageEvents`):

```java
// net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
Event<Chat>  CHAT;   // (Component, PlayerChatMessage, GameProfile, MessageType.Parameters, Instant)
Event<Game>  GAME;   // (Component message, boolean overlay)
Event<ChatCanceled>  CHAT_CANCELED;
Event<GameCanceled>  GAME_CAMELED;

// net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents
Event<Chat>    CHAT;    // (String)  -> lo que el usuario escribe
Event<Command> COMMAND; // (String)  -> sin barra
```

Estos eventos se disparan **ya en el hilo principal**, así que dentro del
callback solo se hace un `concurrentQueue.offer(...)` (no bloqueante).

### 5.5 Ciclo de vida de conexión — `ClientPlayConnectionEvents`

Vive en `fabric-networking-api-v1` (**no** en lifecycle-events), paquete
`net.fabricmc.fabric.api.client.networking.v1`:

```java
Event<Init>       INIT;
Event<Join>       JOIN;
Event<Disconnect> DISCONNECT;
```

### 5.6 Ciclo de vida del cliente

`net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents`:
```java
Event<ClientStarted>  CLIENT_STARTED;
Event<ClientStopping> CLIENT_STOPPING;   // para apagar el servidor HTTP limpiamente
```

### 5.7 Renderizado de chat (alternativa a eventos)

`net.minecraft.client.gui.components.ChatComponent` expone
`getRecentChat(): ArrayListDeque<String>`. Se documenta aquí por si más adelante
se quiere leer historial bajo demanda, pero **no** se usará como fuente
principal porque obliga a leer en el hilo de render y no distingue tipos.

### 5.8 JSON

`com.google.gson` está disponible: Minecraft lo incluye como dependencia y
Loom lo expone en el classpath de compilación. No hace falta añadir
dependencias externas.

---

## 6. Decisiones de arquitectura derivadas

### 6.1 Separación de source sets

| Source set | Contiene | ¿Por qué? |
|---|---|---|
| `main` | Servidor HTTP, config, estado, router, JSON, **interfaz** del puente | Sin una sola referencia a clases de cliente de MC → compila igual en servidor y cliente, y deja el núcleo testeable. |
| `client` | Implementación del puente con llamadas reales a MC, y el cableado de eventos Fabric | Único lugar que conoce `Minecraft`, `ClientPacketListener`, etc. |

El "puente" es el patrón que hace posible el requisito #3:
`main` define una interfaz; `client` la implementa. El hilo HTTP solo conoce
la interfaz.

### 6.2 Flujo de una petición "enviar comando"

```
Hilo HTTP                         Cola task                  Hilo principal (render)
──────────                        ─────────                  ─────────────────────
POST /command
  ├─ valida auth
  ├─ valida body
  ├─ tasks.offer { -> 202 }  ───▶  Minecraft.execute {
  │                                  bridge.sendCommand(cmd)  ──▶ ClientPacketListener
  └─ responde 202                 }                          │      .sendCommand(cmd)
     (no espera)                                                    └─▶ paquete al server
```

Se responde **202 Accepted** de inmediato. El cliente HTTP no bloquea, y el
juego nunca espera. Esto es lo que garantiza que la API lenta del usuario no
arrastre el hilo del juego.

### 6.3 Flujo de captura de chat

```
Hilo principal                                  Hilo HTTP
──────────────                                  ─────────
ClientReceiveMessageEvents.CHAT
  └─ offer a ConcurrentLinkedQueue   ────────▶  GET /chat?limit=N
     (O(1), no bloqueante)                       └─ drena hasta N y responde
```

Cola **acotada** (`ArrayBlockingQueue`) con descarte del más viejo: si el
consumidor HTTP va lento, no crece sin límite la memoria ni se ralentiza el
juego.

### 6.4 Servidor HTTP

`com.sun.net.httpserver.HttpServer` de la JDK: cero dependencias, sin libs de
terceros, hilo propio. Se crea con un `Executor` de pool acotado. Es
exactamente lo que pide el requisito "hilo aparte".

### 6.5 Seguridad (no negociable)

Un endpoint HTTP que ejecuta comandos de Minecraft es **ejecución remota de
comandos**. Mitigaciones por defecto:

1. **Bind a `127.0.0.1`** por defecto (no `0.0.0.0`).
2. **Token Bearer** obligatorio salvo que se desactive explícitamente en config.
3. **Límite de tasa** por IP para el arranque del servidor.
4. **Cota de tamaño de body** para evitar agotar memoria.

Se deja documentado que exponerlo a la red equivale a dar control del juego a
quien escuche ese puerto.

### 6.6 Autenticación sin dependencias

Comparación de token en **tiempo constante** (`MessageDigest.isEqual`) para no
filtrar el token por temporización.

---

## 7. Superficie de API HTTP (propuesta)

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/puppeteer/status` | Estado del cliente, mundo, conexión, FPS, jugador. |
| GET | `/puppeteer/chat?limit=N` | Últimos N mensajes de chat (drena el buffer). |
| GET | `/puppeteer/chat/recent` | Historial reciente, sin drenar. |
| POST | `/puppeteer/chat` | `{"message":"hola"}` → `sendChat`. |
| POST | `/puppeteer/command` | `{"command":"list"}` → `sendCommand` (sin barra). |
| POST | `/puppeteer/connect` | `{"address":"host:puerto","name":"..."}` → conecta. |
| POST | `/puppeteer/disconnect` | Desconecta del mundo. |
| GET | `/puppeteer/players` | Jugadores conectados. |
| GET | `/puppeteer/health` | Vivo, sin autenticación. Prefijo `/puppeteer` para no colisionar con nada. |

Todo devuelve JSON. `command` **no** acepta barra inicial (se rechaza con 400
si viene), para que el error sea explícito y no silencioso.

---

## 8. Pendiente / riesgos

| Riesgo | Mitigación |
|---|---|
| El nombre de una clase cambia entre builds de MC | Todo lo verificado con `javap` contra el jar real, no de memoria. |
| `setScreen` vs `setScreenAndShow` | Verificado en 1.21.5: solo existe `setScreen(Screen)`. |
| Que mappings se apliquen mal (clases en intermediary) | Dependencias de mods con `modImplementation`, no `implementation`: es lo que hace que Loom las remapee al namespace elegido. |
| `sendCommand` con barra | Se documenta y se valida en el borde. |
| Bloquear el hilo de render | Cola acotada + `offer` no bloqueante. |
| Puerto ocupado al arrancar | Se captura el `BindException` y se avisa por log, sin romper el juego. |
| Filtración del token | `MessageDigest.isEqual`. |
| Colisión de puertos con otro proceso | Puerto configurable, default 25580. |

---

## 9. Verificación

Al final de cada cambio: `./gradlew build` debe pasar.
Fuente de verdad para firmas: `javap` sobre
`~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-{clientonly,common}/1.21.5-*/`.

Además del build, en la migración a 1.21.5 se ejecutó `./gradlew runDatagen`, que
arranca el juego real (sin ventana) con Loader 0.19.5, FLK y Fabric API: sirve
para confirmar que el mod carga, que las mappings son las correctas y que el
núcleo HTTP levanta dentro del cliente.
