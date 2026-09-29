# API de Puente (mc-puppeteer)

Referencia practica para hablar con el mod desde HTTP. Todo lo de aqui se
extrajo del codigo, no de memoria: los codigos de error, los limites y los
nombres de campo son los que el servidor devuelve de verdad.

---

## 1. Antes de empezar: que necesitas tener

| Cosas | Valor |
|---|---|
| Direccion base | `http://127.0.0.1:25580/puppeteer` |
| Autenticacion | **Desactivada por defecto.** Si tu config tiene `requireToken: true`, anade `Authorization: Bearer <token>` |
| Content-Type | `application/json` (obligatorio en todo `POST`) |

Por defecto `requireToken` es `false`, asi que las peticiones van sin cabecera.
Si cambiaste ese valor a `true`, el token se genera en el primer arranque, se
**imprime una sola vez** en el log del cliente y se guarda en el fichero de
configuracion:

```
.minecraft/config/mc-puppeteer.json
```

Copiando ese valor ya puedes hacer todas las peticiones. El unico endpoint sin
token es `GET /health`.

### Variable de entorno para no repetirlo

En PowerShell (la consola de este proyecto, 5.1):

```powershell
$BASE = "http://127.0.0.1:25580/puppeteer"

# Solo si tu config tiene requireToken: true
$AUTH = "Authorization: Bearer pega-aqui-el-token-del-log"
```

Los ejemplos de abajo usan `-H $AUTH`. Si vas sin token (el caso por defecto),
borra ese `-H $AUTH` y ya esta.

> En Windows **no uses `curl` a secas**: es un alias de `Invoke-WebRequest` en
> PowerShell 5.1 y se comporta de forma distinta. Usa `curl.exe`, o mejor
> `Invoke-RestMethod`.

---

## 2. Como se ve una respuesta

Todas las respuestas envuelven el dato. El exito lleva `"ok": true`:

```json
{ "ok": true, "data": { "status": "ok", "uptimeMs": 8412, "modVersion": "1.0.0" } }
```

El error lleva `"ok": false` y siempre trae un `code` estable, pensado para que
lo compares en vez de parsear el mensaje:

```json
{ "ok": false, "error": { "code": "invalid_port", "message": "puerto fuera de rango (1-65535): 70000" } }
```

Un detalle que sorprende: los campos sin valor **salen como `null`, no se
omiten**. El serializador esta configurado con `serializeNulls()`. Si no lo
esperas, un `null` en `screen` es normal, no un error.

---

## 3. Indice de endpoints

| Metodo | Ruta | Que hace |
|---|---|---|
| `GET` | `/health` | Vivo y uptime. **Sin token.** |
| `GET` | `/status` | Estado completo del cliente, mundo, FPS, pantalla. |
| `GET` | `/players` | Jugadores del tab list con su latencia. |
| `GET` | `/chat?limit=N` | Lee el chat pendiente y **lo consume**. |
| `GET` | `/chat/history?limit=N` | Copia del historial **sin consumir**. |
| `POST` | `/chat` | Envia un mensaje de chat. |
| `POST` | `/command` | Envia un comando. |
| `POST` | `/connect` | Conecta a un servidor. |
| `POST` | `/disconnect` | Sale al titulo. |
| `GET` | `/debug` | Estado interno del buffer de chat. |
| `GET` | `/baritone` | Indice de comandos de Baritone. |
| `GET` | `/baritone/version`, `/proc`, `/eta`, `/modified`, `/wp`, `/gc` | Consultas de Baritone. |
| `GET` | `/baritone/help?q=...`, `/find?block=...` | Consultas con parametro. |
| `POST` | `/baritone/goto`, `/goal`, `/mine`, `/build`, `/follow` | Acciones de Baritone. |
| `POST` | `/baritone/stop`, `/axis`, `/tunnel`, `/cleararea`, `/explore` | Mas acciones. |
| `POST` | `/baritone/surface`, `/cancel`, `/invert`, `/come`, `/elytra`, ... | Acciones sin argumentos. |
| `POST` | `/baritone/repack`, `/reloadall`, `/saveall`, `/render` | Mantenimiento de cache. |
| `GET` | `/` | Indice de endpoints. |

---

## 4. Endpoint por endpoint

### `GET /health`

Lo unico sin autenticacion. Pensado para un supervisor que solo quiere saber si
el proceso vive, sin que se exponga nada.

```powershell
curl.exe -s "$BASE/health"
```

```json
{
  "ok": true,
  "data": { "status": "ok", "uptimeMs": 8412, "modVersion": "1.0.0" }
}
```

### `GET /status`

Instantanea del cliente.

```powershell
curl.exe -s "$BASE/status" -H $AUTH
```

```json
{
  "ok": true,
  "data": {
    "modVersion": "1.0.0",
    "minecraftVersion": "26.3",
    "inWorld": true,
    "screen": null,
    "playerName": "Steve",
    "playerUuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
    "serverAddress": "localhost:25566",
    "serverName": "Mi servidor",
    "worldName": "world",
    "dimension": "minecraft:overworld",
    "fps": 142,
    "playerCount": 3,
    "maxPlayers": 20,
    "windowActive": true
  }
}
```

`screen` lleva el identificador de la pantalla de menus abierta (`titleScreen`,
`pauseScreen`, `chatScreen`, ...); con el juego limpio es `null`. `inWorld`
es el campo que de verdad dice si puedes mandar chat o no.

### `GET /players`

```powershell
curl.exe -s "$BASE/players" -H $AUTH
```

```json
{
  "ok": true,
  "data": {
    "players": [
      { "name": "Steve", "uuid": "069a...", "latencyMs": 42, "displayName": "Steve" }
    ]
  }
}
```

### `GET /chat?limit=N`

**Consume** los mensajes: lo que leas, no lo volveras a ver.

```powershell
curl.exe -s "$BASE/chat?limit=50" -H $AUTH
```

```json
{
  "ok": true,
  "data": {
    "drained": true,
    "messages": [
      {
        "epochMillis": 1759051234567,
        "kind": "chat",
        "text": "Hola a todos",
        "sender": "Alex",
        "overlay": false
      }
    ]
  }
}
```

`kind` es uno de `chat`, `game`, `system` o `sent`. `overlay` marca los mensajes
superpuestos (tipo la barra de vida del jefe). `limit` va de 1 a 1000, por
defecto 50.

### `GET /chat/history?limit=N`

Lo mismo pero **sin consumir**, mas lo ultimo que has enviado tu. Si vas a
consultar en bucle, usa esta y no `/chat`.

```powershell
curl.exe -s "$BASE/chat/history?limit=50" -H $AUTH
```

```json
{
  "ok": true,
  "data": {
    "messages": [ { "epochMillis": 1759051234567, "kind": "chat", "text": "Hola", "sender": "Alex", "overlay": false } ],
    "recentlySent": [ "hola", "list" ]
  }
}
```

### `POST /chat`

```powershell
curl.exe -s -X POST "$BASE/chat" -H $AUTH -H "Content-Type: application/json" -d '{ "message": "hola" }'
```

```json
{ "ok": true, "data": { "sent": "hola" } }
```

Maximo 256 caracteres. Si mandas un mensaje que empieza por `/` recibes
`400 leading_slash`: aqui no se admiten comandos, para que un error sea visible
y no se mande un comando creyendo que es chat.

### `POST /command`

El comando **se manda sin barra**. Si la mandas con barra, el servidor la quita
y sigue adelante, asi que las dos formas funcionan, pero lo que vuelve en
`data.sent` es siempre sin barra.

```powershell
curl.exe -s -X POST "$BASE/command" -H $AUTH -H "Content-Type: application/json" -d '{ "command": "list" }'
```

```json
{ "ok": true, "data": { "sent": "list" } }
```

### `POST /connect`

Tres formas, de mas comoda a mas estricta:

```powershell
# 1. address (recomendada)
curl.exe -s -X POST "$BASE/connect" -H $AUTH -H "Content-Type: application/json" -d '{ "address": "localhost:25566" }'

# 2. host + port
curl.exe -s -X POST "$BASE/connect" -H $AUTH -H "Content-Type: application/json" -d '{ "host": "localhost", "port": 25566 }'

# 3. anadir nombre de servidor
curl.exe -s -X POST "$BASE/connect" -H $AUTH -H "Content-Type: application/json" -d '{ "address": "localhost:25566", "name": "Mi servidor" }'
```

```json
{ "ok": true, "data": { "connecting": "localhost:25566" } }
```

Si no das puerto, el servidor aplica el 25580, que es el puerto **de este
propio servidor HTTP** y no el 25565 de Minecraft. Es un bug conocido; mira la
seccion 9. Mientras exista, **manda siempre el puerto explicito**.

Las IPv6 van entre corchetes y se devuelven igual: `[::1]:25566`.

### `POST /disconnect`

```powershell
curl.exe -s -X POST "$BASE/disconnect" -H $AUTH
```

```json
{ "ok": true, "data": { "disconnected": true } }
```

### `GET /debug`

```powershell
curl.exe -s "$BASE/debug" -H $AUTH
```

```json
{ "ok": true, "data": { "chatBuffered": 12, "chatDropped": 0, "uptimeMs": 8412 } }
```

`chatDropped` distinto de 0 significa que el buffer se lleno (256 por defecto) y
esta perdiendo mensajes. Sube `chatBufferSize` si eso pasa.

---

## 5. Baritone

Baritone **no tiene API HTTP**. Se controla escribiendo en el chat con prefijo
`#`. Puente traduce peticiones JSON a esos comandos.

### Por que van en `/chat` y no en `/command`

Es la diferencia que mas confunde, asi que va explicita: los comandos de
Baritone son **mensajes de chat**, no comandos de servidor.

| Endpoint | Como sale | Lo ve Baritone |
|---|---|---|
| `POST /chat {"message":"#goto 1 2 3"}` | paquete de chat | **Si** |
| `POST /command {"command":"#goto 1 2 3"}` | paquete de comando | **No**, va al servidor y lo rechaza |

Los endpoints `/baritone/*` usan internamente el camino de chat, asi que no
tienen ese problema.

### Requisitos

- Baritone instalado y activo.
- Setting `prefixControl` activado (viene por defecto). Si lo desactivas, los
  comandos con `#` se enviarian al chat publico del servidor.
- Estar conectado a un mundo.

### Consultas (`GET`)

```powershell
curl.exe -s "$BASE/baritone/version"
curl.exe -s "$BASE/baritone/eta"
curl.exe -s "$BASE/baritone/help?q=mine"
curl.exe -s "$BASE/baritone/find?block=diamond_ore"
```

```json
{ "ok": true, "data": { "sent": "#find diamond_ore" } }
```

Disponibles sin parametro: `version`, `proc`, `eta`, `modified`, `wp`, `gc`.
Con parametro: `help?q=`, `find?block=`.

### Acciones (`POST`)

```powershell
curl.exe -s -X POST "$BASE/baritone/goto" -H $AUTH -d '{"x":1000,"y":64,"z":500}'
curl.exe -s -X POST "$BASE/baritone/mine" -H $AUTH -d '{"block":"diamond_ore","amount":16}'
curl.exe -s -X POST "$BASE/baritone/build" -H $AUTH -d '{"file":"base.schematic"}'
curl.exe -s -X POST "$BASE/baritone/follow" -H $AUTH -d '{"target":"Alex"}'
curl.exe -s -X POST "$BASE/baritone/stop?force"
```

`goto` admite las tres formas que entiende Baritone:

| Cuerpo | Comando |
|---|---|
| `{"x":1000,"y":64,"z":500}` | `#goto 1000 64 500` |
| `{"x":1000,"z":500}` | `#goto 1000 500` |
| `{"y":64}` | `#goto 64` |
| `{"block":"diamond_ore"}` | `#goto diamond_ore` |

Mezclas parciales (`{"x":1,"y":2}`) dan `400 invalid_goal` en vez de dejar que
Baritone lo interprete de otra forma.

Sin argumentos: `surface`, `top`, `invert`, `come`, `blacklist`, `elytra`,
`farm`, `cancel`, `path`, `thisway`.
Mantenimiento: `repack`, `reloadall`, `saveall`, `render`.

### La respuesta de Baritone va al chat

Este es el punto que mas sorprende. Puente devuelve `202` con el comando
enviado, pero **lo que Baritone responde llega como chat**, no como cuerpo de
la respuesta HTTP. Para leerlo:

```powershell
curl.exe -s -X POST "$BASE/baritone/mine" -H $AUTH -d '{"block":"diamond_ore"}'
Start-Sleep -Seconds 2
curl.exe -s "$BASE/chat?limit=20"
```

Alternativa inmediata, sin esperar: `POST /chat` con el prefijo a mano, p. ej.
`{"message":"#mine diamond_ore"}`. Es lo mismo que hace `/baritone/mine`.

### Validacion

Los parametros se validan antes de construir el comando, para que un cliente
HTTP no pueda inyectar texto en un comando de Baritone:

- Bloques: `[A-Za-z0-9_:.-]{1,64}`
- Jugadores: `[A-Za-z0-9_]{1,16}`
- Schematicos: `[A-Za-z0-9_-]{1,64}\.schematic`
- Coordenadas: -30 000 000 a 30 000 000 (altura: -64 a 320)
- `amount` de `mine`: 1 a 4096. Opcional: sin el, Baritone mina hasta agotar.
- `tunnel`/`cleararea`: 1 a 64

Un valor fuera de estos patrones da `400 invalid_field`, y el error **no**
incluye el valor recibido.

### Sobre `GET` con efecto

Los `GET /baritone/*` envian un mensaje de chat, asi que **tienen efecto**. Es
deliberado, porque Baritone es una interfaz de comandos de una sola llamada y
no tiene equivalente idempotente. Es aceptable en loopback, pero no pongas un
crawler delante.

---

## 6. Que significa realmente el 202

`POST /chat`, `/command`, `/connect`, `/disconnect` y los `/baritone/*`
devuelven **202**, no 200.

No es un "encolado y ya veras". Cada uno de esos llama a la API de Minecraft
**de forma sincrona** a traves del hilo principal y espera a que termine, con
un timeout de 5 s por defecto. Cuando lees el 202, la accion ya se ha ejecutado
en el juego. El hilo HTTP es el que espera; el juego nunca se bloquea.

Si el hilo principal no contesta a tiempo, el error es:

```json
{ "ok": false, "error": { "code": "main_thread_timeout", "message": "El hilo principal de Minecraft no respondio en 5000ms" } }
```

con HTTP **503**. Suele significar que el juego esta colgado o cargando.

---

## 7. Codigos de error

| HTTP | `code` | Cuando |
|---|---|---|
| 400 | `missing_field` | Falta un campo obligatorio (`message`, `command`). |
| 400 | `invalid_json` | El cuerpo no es JSON valido, o no es un objeto. |
| 400 | `empty_body` | Cuerpo vacio en un `POST`. |
| 400 | `invalid_field` | El campo existe pero no es del tipo esperado. |
| 400 | `invalid_query` | `limit` no es entero, o esta fuera de 1..1000. |
| 400 | `empty_message` | `message` es solo espacios. |
| 400 | `message_too_long` | Supera los 256 caracteres. |
| 400 | `leading_slash` | Mandaste `/algo` a `/chat`. Usa `/command`. |
| 400 | `empty_command` | `command` es solo espacios. |
| 400 | `command_too_long` | Supera los 256 caracteres. |
| 400 | `missing_host` | No diste `host` ni `address`. |
| 400 | `invalid_port` | Puerto fuera de 1..65535, o no numerico. |
| 400 | `invalid_host` | Host con espacios, `/`, o demasiado largo. |
| 400 | `invalid_address` | `address` mal formada, o que Minecraft rechaza. |
| 401 | `unauthorized` | Falta el token, o no es correcto. |
| 404 | `not_found` | Ruta o endpoint inexistente. |
| 405 | `method_not_allowed` | Metodo no valido. Viene con cabecera `Allow`. |
| 409 | `not_connected` | Se pidio algo que exige estar en un mundo. |
| 413 | `body_too_large` | El cuerpo pasa de 16384 bytes. |
| 429 | `rate_limited` | Mas de 120 peticiones en 10 s. Cabecera `Retry-After: 5`. |
| 500 | `main_thread_error` | El hilo principal lanzo una excepcion. |
| 500 | `internal_error` | Error no controlado. |
| 503 | `main_thread_timeout` | El hilo principal no respondio a tiempo. |

---

## 8. Ejemplos completos

### PowerShell: chatear y ver quien hay

```powershell
$BASE  = "http://127.0.0.1:25580/puppeteer"
$TOKEN = "pega-aqui-el-token-del-log"
$AUTH  = "Authorization: Bearer $TOKEN"
$JSON  = "Content-Type: application/json"

Invoke-RestMethod "$BASE/status" -Headers @{ Authorization = "Bearer $TOKEN" } | ConvertTo-Json -Depth 5

Invoke-RestMethod "$BASE/command" -Method Post -Headers @{ Authorization = "Bearer $TOKEN" } `
  -ContentType "application/json" -Body '{ "command": "list" }'

Start-Sleep -Milliseconds 500
Invoke-RestMethod "$BASE/chat?limit=20" -Headers @{ Authorization = "Bearer $TOKEN" } | ConvertTo-Json -Depth 5
```

### Node: leer el chat en bucle sin perder mensajes

Usa `/chat/history`, no `/chat`, para no consumir lo que todavia no has leido.

```javascript
const BASE = "http://127.0.0.1:25580/puppeteer";
const TOKEN = "pega-aqui-el-token-del-log";
const headers = { Authorization: `Bearer ${TOKEN}` };

const post = (ruta, cuerpo) =>
  fetch(`${BASE}${ruta}`, {
    method: "POST",
    headers: { ...headers, "Content-Type": "application/json" },
    body: JSON.stringify(cuerpo),
  }).then((r) => r.json());

const get = (ruta) =>
  fetch(`${BASE}${ruta}`, { headers }).then((r) => r.json());

const visto = new Set();

setInterval(async () => {
  const { data } = await get("/chat/history?limit=100");
  for (const m of data.messages) {
    if (visto.has(m.epochMillis + m.text)) continue;
    visto.add(m.epochMillis + m.text);
    console.log(`[${m.kind}] ${m.sender ?? "-"}: ${m.text}`);
  }
}, 1000);
```

### Python: conectar y monitorizar

```python
import time, requests

BASE = "http://127.0.0.1:25580/puppeteer"
H = {"Authorization": "Bearer PEGA_AQUI_EL_TOKEN"}

requests.post(f"{BASE}/connect", headers=H, json={"address": "localhost:25566"}).raise_for_status()

while True:
    estado = requests.get(f"{BASE}/status", headers=H).json()["data"]
    if estado["inWorld"]:
        for m in requests.get(f"{BASE}/chat?limit=20", headers=H).json()["data"]["messages"]:
            print(f'[{m["kind"]}] {m["sender"] or "-"}: {m["text"]}')
    else:
        print("no esta en un mundo")
    time.sleep(1)
```

---

## 9. Trampas que conviene conocer de antemano

1. **`/chat` consume mensajes.** Si lo usas en un bucle y pierdes la respuesta,
   esos mensajes ya no estan. Para consultar sin riesgo, `/chat/history`.
2. **El rate limit es de 120 peticiones cada 10 s**, por IP, y aplica tambien a
   `GET /health`. Si te pasas, el 429 trae `Retry-After: 5`.
3. **Un 202 en `/connect` no significa que la conexion se haya completado**,
   solo que el juego ya recibio la orden. Comprueba `/status` y mira `inWorld`
   y `serverAddress` para confirmar.
4. **Los `null` son normales.** El serializador los escribe.
5. **Los mensajes se capturan desde que el mod arranca.** Si entraste en un
   mundo antes de arrancar, no hay historico de ese rato.
6. **Cambiar `host` a algo que no sea `127.0.0.1` abre el puerto a la red.**
   Cualquiera que llegue a el puede mandar comandos y chat con tu cuenta de
   Minecraft activa. El mod avisa por log cuando detecta esa situacion.
7. **Sin `requireToken` no hay barrera de entrada.** Con `host` en `127.0.0.1`
   solo llegan procesos de esta maquina, lo cual suele bastar. Si abres el
   puerto a la red, pon `requireToken: true` antes.

---

## 10. Bug conocido: el puerto por defecto de `/connect`

`POST /connect` sin puerto explicito intenta conectarse a **25580**, cuando
Minecraft usa **25565**.

La causa es que `PuenteController` reutiliza `PuenteConfig.DEFAULT_PORT` (que es
el puerto del servidor HTTP, 25580) como si fuera el puerto por defecto del
juego:

```kotlin
// PuenteConfig.kt
const val DEFAULT_PORT = 25580          // puerto del servidor HTTP

// PuenteController.kt
var port = body.optInt("port") ?: PuenteConfig.DEFAULT_PORT   // <- para el juego
if (lastColon < 0) return trimmed to PuenteConfig.DEFAULT_PORT
if (trimmed.count { it == ':' } > 1) return trimmed to PuenteConfig.DEFAULT_PORT
```

Afecta a los tres caminos sin puerto: `{"host": "servidor"}`,
`{"address": "servidor"}` y una IPv6 sin `:puerto`. Con `{"host": "x", "port": 25565}`
o `{"address": "x:25565"}` funciona bien, porque el puerto llega explicito.

La prueba de humo fija ese valor como correcto (asercion 23), asi que hoy el
comportamiento esta "verde" siendo incorrecto. Un cambio de una linea: un
`DEFAULT_GAME_PORT = 25565` propio del controlador, en vez de reutilizar el del
HTTP.

**Workaround mientras tanto: manda siempre `host` y `port` separados, o
`address` con `:puerto`.**
