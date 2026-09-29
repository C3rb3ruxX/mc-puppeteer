# mc-puppeteer - Implementacion

> Documento de construccion. Complementa `00-research-dump.md` (lo que se sabia
> **antes** de escribir codigo) y `01-addendum-hallazgos.md` (lo que se
> descubrio **al** compilar).
>
> Documentos relacionados: `03-testing.md` (como ejecutar la prueba de humo y que cubre).

---

## 1. Que hace

El cliente de Minecraft abre un **servidor HTTP en un hilo aparte** que permite:

- **enviar chat** al servidor de Minecraft conectado,
- **enviar comandos** al servidor de Minecraft conectado,
- **leer el chat** que llega (jugadores, sistema, y lo que escribio el operador),
- **conectarse** a un servidor arbitrario y **desconectarse**,
- **consultar el estado** del cliente (mundo, jugador, FPS, dimension, jugadores).

La restriccion de diseno que gobierna todo: **no impactar el rendimiento de
Minecraft**.

## 2. Arquitectura

```
   source set: main  (sin NINGUNA clase de cliente de Minecraft)
  +--------------------------------------------------------------+
  |  PuenteHttpServer   transporte: routing, auth, CORS        |
  |  PuenteController    logica y validacion                   |
  |  MainThreadBridge       el UNICO cruce de hilos               |
  |  ChatLog                buffer acotado de mensajes           |
  |  PuenteConfig        config JSON                          |
  |  MinecraftBridge        INTERFAZ (no implementada aqui)      |
  +--------------------------------------------------------------+
                            ^ implementa
  +--------------------------------------------------------------+
  |  source set: client                                          |
  |  ClientBridge        llamadas reales a MC 26.3              |
  |  ClientMainThread    MainThreadExecutor sobre Minecraft     |
  |  ChatCapture         eventos de Fabric API                  |
  |  PuenteClient   costura y ciclo de vida                |
  +--------------------------------------------------------------+

  peticiones HTTP --> hilos "mc-puppeteer-http-N" (daemon)
                           |
                           +--> MainThreadBridge --> Minecraft.execute { }
                                                              |
                                    hilo de render ----------+--> ClientPacketListener
                                                                            .sendChat / .sendCommand
```

### Por que esta division

| Preocupacion | Donde vive | Motivo |
|---|---|---|
| Escucha HTTP, routing, limite de tasa | `main` | No necesita el juego. |
| Validacion de entradas | `main` | Testeable sin arrancar Minecraft. |
| Buffer de chat | `main` | Estructura de datos pura. |
| **Llamadas a la API de MC** | `client` | Unico lugar que importa clases de cliente. |
| **Cruce de hilos** | `MainThreadBridge` | Unico punto donde se cruza al hilo principal. |

La regla que sostiene la garantia de rendimiento:

> **Ningun hilo HTTP llama a la API de Minecraft. Todo pasa por
> `MainThreadBridge.callOnMainThread { }`, que agenda con `Minecraft.execute`.**

Asi el juego nunca espera a una peticion, y la peticion si espera al juego
(con timeout configurable). Si el usuario pide algo y su cliente HTTP se
cuelga, el juego no se entera.

## 3. Archivos

### Convencion de nombres

El codigo se llama `puente`. El resto de nombres **no** se renombraron, y es
deliberado, porque son contratos que ya existen fuera del codigo:

| Nombre | Valor | Por que no se cambia |
|---|---|---|
| Paquetes y clases | `com.bonilla.puente.*` | Codigo interno, renombrable sin coste. |
| Id del mod | `mc-puppeteer` | Identidad del mod en Fabric. Aparece en la lista de mods, en los crash reports y en `config/mc-puppeteer.json`. Cambiarlo hace que el mod appears como uno nuevo y deja la config vieja huérfana. |
| Base path HTTP | `/puppeteer` | Ya documentado; cambiarlo rompe clientes sin avisar. |
| Plantilla | `McPuppeteer.kt`, `McPuppeteerDataGenerator.kt` | No son mios. Se dejan como estan para que un `git diff` contra la plantilla siga siendo legible. |

Renombrar el id del mod y el base path son cambios de una linea cada uno si en
algún momento se quieren; hasta entonces, mezclarlos con el renombrado del
codigo habria hecho el commit mas dificil de revisar sin gain real.

### `main` - nucleo, sin dependencia de Minecraft

| Archivo | Responsabilidad |
|---|---|
| `puente/MinecraftBridge.kt` | Interfaz del puente + DTOs (`ClientStatus`, `RemotePlayerInfo`, `CapturedMessage`) y su serializacion explicita a JSON. |
| `puente/MainThreadBridge.kt` | `MainThreadExecutor` (interfaz) + `MainThreadBridge` (dispatcher con `CompletableFuture` y timeout) + `PuenteException`. |
| `puente/ChatLog.kt` | Buffer circular acotado con `snapshot` (copia) y `drain` (consumo). Contabiliza descartes. |
| `puente/PuenteConfig.kt` | Config JSON en `config/mc-puppeteer.json`, valores por defecto, `validate()`, guardado atomico. |
| `puente/PuenteController.kt` | Logica de negocio: validacion de mensaje/comando/direccion, y las llamadas al puente. |
| `puente/PuenteHttpServer.kt` | `com.sun.net.httpserver`, pool de hilos daemon, routing, auth, CORS, limite de tasa, tope de body. |
| `puente/http/Json.kt` | Envoltorio de Gson (ya viene con Minecraft) + `HttpError`. |

### `client` - lo unico que toca Minecraft

| Archivo | Responsabilidad |
|---|---|
| `PuenteClient.kt` | Lee config, valida, genera token si falta, monta el grafo, arranca/detiene el servidor. |
| `ClientBridge.kt` | Implementa `MinecraftBridge` contra las APIs reales de 26.3. |
| `ClientMainThread.kt` | `MainThreadExecutor` sobre `Minecraft.execute` / `isSameThread`. |
| `ChatCapture.kt` | Registra `ClientReceiveMessageEvents` y `ClientSendMessageEvents`. |

## 4. Por que no se implementa con mixins

La captura de chat usa `ClientReceiveMessageEvents.CHAT` / `.GAME` de
`fabric-message-api-v1`, no un mixin sobre el listener de red ni sobre el HUD.

Ventajas:

- **No hay superficie de riesgo**: un `@Inject` con `defaultRequire: 1` sobre
  un metodo de Minecraft rompe el juego entero si upstream cambia la firma. Con
  eventos de API, una subida de version rompe como mucho una linea.
- **No se duplica logica del juego**: el evento ya distingue chat de jugador,
  mensaje de sistema y mensaje superpuesto, con la cadena de formato y el
  perfil del autor ya resueltos.
- Es la via que la propia Fabric API ofrece justamente para esto.

Consecuencia: el proyecto ya **no necesita ningun mixin**, asi que se borraron
`ExampleMixin`, `ExampleClientMixin` y los dos `*.mixins.json` de la plantilla.
Ver `01-addendum-hallazgos.md` §8.

## 5. Garantias de rendimiento

Este es el requisito mas importante, asi que se documenta como tal.

| Mecanismo | Donde | Que garantiza |
|---|---|---|
| Pool de hilos **daemon** propio | `PuenteHttpServer` | Cero trabajo de red en el hilo de render. |
| `Minecraft.execute` para todo efecto | `MainThreadBridge` | El juego nunca se bloquea por una peticion. |
| Captura de chat **O(1) y no bloqueante** | `ChatCapture` + `ChatLog` | El callback de chat (que corre en el hilo de render) solo hace `addLast` y, cada N mensajes, un `removeFirst`. Sin E/S, sin locks compartidos, sin asignaciones grandes. |
| Buffer **acotado** | `ChatLog` | Si nadie consume por HTTP, la memoria no crece: se descartan los mensajes viejos. Tope configurable (`chatBufferSize`, por defecto 256). |
| Sin sondeo por tick | todo | Nada de `ClientTickEvents` ni `END_CLIENT_TICK` recorriendo buffers. Todo es push (eventos) o pull (peticion HTTP). |
| Respuestas `202 Accepted` | endpoints de escritura | El cliente HTTP no espera a que el paquete llegue al servidor de Minecraft. |
| Tope de tamano de body | `readBody` | Un `POST` gigante no puede agotar la memoria. |
| Limite de tasa por IP | `RateLimiter` | 120 peticiones / 10 s por IP. |

**Medicion pendiente (no hecha):** no se ha medido el impacto en FPS con el mod
activo. La afirmacion "no impacta" aqui significa "por construccion no hay
trabajo proporcional al tiempo en el hilo de render mas alla de un `addLast`",
no "medido y por debajo de X nanosegundos". Conviene medirlo antes de distribuir.

## 6. Seguridad

Un endpoint HTTP que ejecuta comandos de Minecraft es **ejecucion remota de
comandos con la cuenta de la victima**. No es un detalle, es la consideracion
dominante de este mod.

Por defecto:

1. **Bind a `127.0.0.1`.** No `0.0.0.0`. Nada sale de la maquina.
2. **Token Bearer obligatorio.** Se generan 32 bytes de `SecureRandom` en Base64
   URL-safe la primera vez, se guardan en `config/mc-puppeteer.json` y se
   imprimen **una sola vez** en el log. Sin token, `401` en todo salvo
   `/health`.
3. **Comparacion en tiempo constante** (`MessageDigest.isEqual`) para no filtrar
   el token por temporizacion.
4. **Limite de tasa** por IP.
5. **Tope de body** y de longitud de mensaje/comando (256 caracteres).
6. **Aviso en el log** si `host` deja de ser loopback.

Lo que un puerto HTTP de este tipo **no** puede evitar, y conviene decir alto:
si alguien cambia `host` a `0.0.0.0`, esa persona puede ejecutar `/op` sobre si
misma, leer todo el chat del servidor y usar la sesion de la victima. No hay
HTTPS, ni lista de IPs permitidas, ni segundo factor. La exposicion a la red
debe ser una decision consciente, con el token como unica barrera.

`/health` es el unico endpoint sin token, y solo devuelve `status`, `uptimeMs` y
la version del mod. Existe para que un supervisor compruebe que el proceso vive.

## 7. API HTTP

Base: `/puppeteer`. Todas las respuestas son JSON.

Formato de exito:

```json
{ "ok": true, "data": { } }
```

Formato de error:

```json
{ "ok": false, "error": { "code": "invalid_port", "message": "puerto fuera de rango (1-65535): 70000" } }
```

Autenticacion: `Authorization: Bearer <token>` en todo excepto `GET /health`.

| Metodo | Ruta | Body | Devuelve | Notas |
|---|---|---|---|---|
| GET | `/puppeteer/` | - | 200 | Indice de endpoints. |
| GET | `/puppeteer/health` | - | 200 | **Sin token.** Vivo, uptime, version. |
| GET | `/puppeteer/status` | - | 200 | Estado completo del cliente. |
| GET | `/puppeteer/debug` | - | 200 | Estado interno del buffer. |
| GET | `/puppeteer/players` | - | 200 | Tab list: nombre, uuid, latencia, nombre visible. |
| GET | `/puppeteer/chat?limit=N` | - | 200 | **Drena** hasta N mensajes pendientes. `N` en 1..1000, por defecto 50. |
| GET | `/puppeteer/chat/history?limit=N` | - | 200 | Copia **sin drenar**, mas lo ultimo escrito a mano. |
| POST | `/puppeteer/chat` | `{"message":"hola"}` | 202 | Envia como chat. |
| POST | `/puppeteer/command` | `{"command":"list"}` | 202 | Envia como comando. **Sin barra.** |
| POST | `/puppeteer/connect` | ver abajo | 202 | Conecta a un servidor. |
| POST | `/puppeteer/disconnect` | - | 202 | Salir al titulo. |

### `/connect` acepta tres formas

```jsonc
{ "address": "servidor.mc:25565" }                      // preferido
{ "address": "servidor.mc" }                            // puerto por defecto 25580
{ "address": "[::1]:25566" }                            // IPv6 entrecorchetada
{ "host": "servidor.mc", "port": 25565, "name": "X" }   // explicito, "name" opcional
```

Un IPv6 sin puerto (`"::1"`, dos o mas `:`) **no** se interpreta como
`host:puerto`. Se distingue por el recuento de dos puntos, que es el mismo
criterio que usa el parser de Minecraft.

### `/chat` vs `/command`: por que estan separados

Son dos endpoints, no uno con un campo ambiguo, porque el envio es ambiguo en
la propia interfaz de Minecraft: el chat que empieza por `/` se convierte en
comando.

- `POST /chat` con `"/list"` devuelve **400 `leading_slash`**, con un mensaje que
  senala explicitamente que use `/command`. No se adivina la intencion.
- `POST /command` con `"/say hola"` devuelve **202** y se normaliza a
  `"say hola"`, porque ahi la barra es inequivoca y rechazarla solo molestaria.

Aun asi se **rechaza** la barra en `/chat` en vez de enviarla como texto: si el
usuario escribe `"/list"` en un endpoint de chat, quiere un comando, y que se
envie como texto plano seria un fallo silencioso.

### Codigos de error

| Codigo | HTTP | Cuando |
|---|---|---|
| `unauthorized` | 401 | Token ausente o incorrecto. |
| `not_found` | 404 | Ruta o endpoint inexistente. |
| `method_not_allowed` | 405 | Metodo incorrecto (lleva cabecera `Allow`). |
| `invalid_json` | 400 | Cuerpo que no es JSON valido. |
| `empty_body` | 400 | Sin cuerpo en un POST que lo requiere. |
| `missing_field` | 400 | Falta un campo obligatorio. |
| `empty_message` / `empty_command` | 400 | El campo existe pero esta en blanco. |
| `leading_slash` | 400 | `/chat` con barra inicial. |
| `message_too_long` / `command_too_long` | 400 | Supera 256 caracteres. |
| `invalid_field` | 400 | El campo no es del tipo esperado. |
| `missing_host` | 400 | `/connect` sin destino. |
| `invalid_address` | 400 | Direccion no parseable. |
| `invalid_port` | 400 | Puerto fuera de 1..65535. |
| `invalid_query` | 400 | Parametro de query fuera de rango o no numerico. |
| `body_too_large` | 413 | Body mayor que `maxBodyBytes` (16 KB por defecto). |
| `rate_limited` | 429 | Mas de 120 peticiones / 10 s desde la misma IP (lleva `Retry-After`). |
| `not_connected` | 409 | Se pidio enviar chat/comando sin conexion. |
| `main_thread_timeout` | 503 | El hilo principal no respondio en `requestTimeoutMs` (5 s). |
| `main_thread_error` | 500 | Excepcion inesperada en el hilo principal. |
| `internal_error` | 500 | Cualquier otro fallo (se registra en el log). |

`not_connected` se decide por `Minecraft.getConnection() == null`, no por
`level == null`: se puede enviar chat en cuanto hay conexion, aunque el mundo
aun no este cargado.

## 8. Configuracion

Ruta: `config/mc-puppeteer.json` (directorio de config de Fabric). Se crea sola
la primera vez con los valores por defecto.

| Campo | Por defecto | Significado |
|---|---|---|
| `enabled` | `true` | Si es `false` no se abre ningun puerto. |
| `host` | `"127.0.0.1"` | Interfaz de escucha. **No cambiar a `0.0.0.0` sin saber lo que implica.** |
| `port` | `25580` | Puerto. Si esta ocupado se avisa por log y el juego sigue arrancando. |
| `requireToken` | `true` | Si se puede llamar sin token. |
| `authToken` | `""` | Token. Se autogenera si esta vacio. |
| `chatBufferSize` | `256` | Mensajes retenidos. |
| `maxBodyBytes` | `16384` | Tope del cuerpo de una peticion. |
| `requestTimeoutMs` | `5000` | Espera maxima del hilo principal. |
| `httpThreads` | `4` | Hilos del pool HTTP. |

Si `validate()` falla, se registra cada problema, se sustituyen los campos
problematicos por su valor por defecto y el mod **arranca igual**. Un error de
configuracion no debe impedir jugar; ademas el mensaje de error dice que
revisar la config y reiniciar.

Ejemplo:

```json
{
  "enabled": true,
  "host": "127.0.0.1",
  "port": 25580,
  "requireToken": true,
  "authToken": "c9T3...==",
  "chatBufferSize": 256,
  "maxBodyBytes": 16384,
  "requestTimeoutMs": 5000,
  "httpThreads": 4
}
```

## 9. Ejemplos

Con el `TOKEN` tomado del log de arranque:

```bash
BASE=http://127.0.0.1:25580/puppeteer
AUTH="Authorization: Bearer $TOKEN"

curl -s $BASE/health                                     # sin token
curl -s -H "$AUTH" $BASE/status
curl -s -H "$AUTH" $BASE/chat?limit=20
curl -s -H "$AUTH" -H "Content-Type: application/json" \
     -d '{"message":"hola desde fuera"}' $BASE/chat
curl -s -H "$AUTH" -H "Content-Type: application/json" \
     -d '{"command":"list"}' $BASE/command
curl -s -H "$AUTH" -H "Content-Type: application/json" \
     -d '{"address":"localhost:25565"}' $BASE/connect
curl -s -X POST -H "$AUTH" $BASE/disconnect
```

## 10. Notas de implementacion que no son evidentes

- **`ArrayDeque` sin importar** es la de Kotlin, no la de Java: `first()` en vez
  de `peekFirst()`. Ver `01-addendum-hallazgos.md` seccion 7.
- **Dos `fun List<T>.toJsonArray()`** con distinto `T` colisionan en la firma
  JVM; se separan con `@JvmName`.
- **Gson se usa a proposito**: ya viene con Minecraft, asi que el nucleo no anade
  ninguna dependencia. Aun asi la serializacion se hace **a mano** con
  `toJson()` en cada DTO en vez de dejar que Gson reflexione sobre las clases
  Kotlin: la reflexion sobre `val` con valor por defecto y sobre properties
  nullable es frágil, y el coste de cuatro DTOs es despreciable.
- **`sendChat` vs `sendCommand`**: `ClientPacketListener.sendCommand` espera el
  comando **sin** barra. Es la causa clasica de bug, por eso se normaliza en la
  capa HTTP (con test) y no en la capa de juego.
- **El apagado** engancha `ClientLifecycleEvents.CLIENT_STOPPING`. Los hilos del
  pool son daemon, asi que no impedirian la salida de la JVM, pero se cierran
  igual para no dejar un puerto abierto durante el teardown.
- **Si el puerto esta ocupado**, se captura el `BindException`, se avisa y el
  juego sigue. Un puerto ocupado no es motivo para no poder jugar.

## 11. Donde la implementacion se desvia del plan inicial

`00-research-dump.md` §6 proponia un diseno concreto. Estas cuatro cosas se
cambiaron al escribir el codigo, y el por que:

### 11.1 `CompletableFuture` con timeout en vez de cola "fire and forget"

El dump proponia encolar la tarea y responder `202` sin esperar a que el hilo
principal la ejecutara.

Se cambio a: el hilo HTTP agenda con `Minecraft.execute` y **espera** el
resultado con timeout (`requestTimeoutMs`), y solo entonces responde.

Motivo: el fire-and-forget del plan obliga a responder `202` **antes** de saber
si la operacion es siquiera posible. Un `POST /command` sin conexion, o a un
jugador silenciado, devolveria `202 Accepted` y el cliente externo creeria que
funciono. Con espera, esos casos devuelven `409 not_connected` de verdad.

El coste para el requisito de rendimiento es **cero**: el que espera es el
hilo HTTP, nunca el de render. El juego no se bloquea en ningun caso; es la
direccion del bloqueo la que esta garantizada, no su ausencia. Ademas el
`202` sigue siendo la respuesta de los endpoints de escritura, porque no se
espera a que el *paquete* llegue al servidor de Minecraft, solo a que el codigo
de juego lo haya enviado.

### 11.2 `synchronized ArrayDeque` en vez de `ArrayBlockingQueue`

El dump proponia `ArrayBlockingQueue` con descarte del mas viejo.

Se cambio a un `ArrayDeque` de Kotlin bajo `synchronized`. Motivo:
`ArrayBlockingQueue` no tiene forma limpia de *drenar hasta N* de una vez
(`drainTo` toma un `Collection` y ademas el buffer seria inaccesible mientras
se drena). La operacion central de la API es precisamente "dame lo pendiente
desde la ultima llamada y consumelo", y un deque con dos secciones criticas de
unas pocas lineas lo hace de forma directa y auditable.

No se eligio una cola bloqueante justamente para que el hilo de render nunca
pueda quedarse esperando: el escritor hace `addLast` y, cada N, un
`removeFirst`. La seccion critica dura lo que esas dos operaciones, sin I/O ni
llamadas a terceros.

### 11.3 Captura por eventos de Fabric API en vez de interceptar paquetes

Ya estaba en el plan, pero se constato que ademas permitio **borrar todos los
mixins de la plantilla** (ver `01-addendum-hallazgos.md` §8), lo que no se habia
anticipado: si el mod no usa ningun mixin, no necesita `*.mixins.json` ni
directorios `src/*/java`.

### 11.4 `environment: "client"` en vez de `"*"`

El dump no lo mencionaba. Ver `01-addendum-hallazgos.md` §9.
