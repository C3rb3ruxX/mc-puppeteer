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
| `GET` | `/inventory` | Que lleva el jugador: solo las ranuras ocupadas. |
| `GET` | `/chat?limit=N` | Lee el chat pendiente y **lo consume**. |
| `GET` | `/chat/history?limit=N` | Copia del historial **sin consumir**. |
| `POST` | `/chat` | Envia un mensaje de chat. |
| `POST` | `/command` | Envia un comando. |
| `POST` | `/connect` | Conecta a un servidor. |
| `POST` | `/disconnect` | Sale al titulo. |
| `POST` | `/respawn` | Reaparece si el personaje esta muerto. |
| `POST` | `/store` | Vuelca el inventario en un cofre. Cuerpo `{"x","y","z"}` opcional. |
| `POST` | `/store/now` | Coloca un cofre donde esta el bot y lo deja. |
| `GET` | `/store` | Estado del volcado en curso, sin encolar nada. |
| `GET` | `/profile` | Identidad con la que se conectara el cliente. |
| `POST` | `/profile` | Cambia el nombre **en caliente** (solo offline). |
| `GET` | `/debug` | Estado interno del buffer de chat. |
| `GET` | `/baritone` | Indice de comandos de Baritone. |
| `GET` | `/baritone/version`, `/proc`, `/eta`, `/modified`, `/paused`, `/wp`, `/gc` | Consultas de Baritone. |
| `GET` | `/baritone/help?q=...`, `/find?block=...` | Consultas con parametro. |
| `POST` | `/baritone/goto`, `/goal`, `/mine`, `/build`, `/follow`, `/thisway` | Acciones de Baritone. |
| `POST` | `/baritone/stop`, `/axis`, `/tunnel`, `/cleararea`, `/explore` | Mas acciones. |
| `POST` | `/baritone/surface`, `/pause`, `/resume`, `/sel`, `/set`, ... | Acciones sin argumentos. |
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
    "minecraftVersion": "1.21.5",
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
    "windowActive": true,
    "dead": false,
    "health": 18.0,
    "maxHealth": 20.0,
    "food": 17,
    "saturation": 4.5,
    "x": 123.456,
    "y": 71.0,
    "z": -45.5,
    "xpLevel": 7,
    "xpProgress": 0.25,
    "dayTime": 265000
  }
}
```

`screen` lleva el identificador de la pantalla de menus abierta (`titleScreen`,
`pauseScreen`, `chatScreen`, ...); con el juego limpio es `null`. `inWorld`
es el campo que de verdad dice si puedes mandar chat o no.

#### Vitales y posicion

Ademas del estado de la sesion, `data` lleva lo que el panel necesita para no
tener que preguntar por otra parte:

| Campo | Tipo | Que es |
|---|---|---|
| `health` | numero | Vida actual, en medios corazones: `20` = 10 corazones. |
| `maxHealth` | numero | Vida maxima. `20` es lo normal; la suben los efectos. |
| `food` | entero | Comida de 0 a 20. A `20` la barra esta llena. |
| `saturation` | numero | Saturacion de 0 a 20: cuanto aguanta sin comer. |
| `x`, `y`, `z` | numero | Posicion del jugador **con decimales**. |
| `xpLevel` | entero | Nivel de experiencia. |
| `xpProgress` | numero | Progreso dentro del nivel, de 0 a 1. |
| `dead` | booleano | `true` si hay jugador y tiene 0 de vida. |
| `dayTime` | entero | Contador de ticks del mundo; la hora del dia es `dayTime % 24000`. |

Todos van como numeros planos, nunca como `null`. Cuando no hay jugador (menu de
titulo, pantalla de conexion) se mandan los valores neutros: `health` `0`,
`maxHealth` `20`, `food` `0`, `saturation` `0`, coordenadas a `0` y experiencia a
`0`. Por eso `inWorld` sigue siendo el campo que hay que mirar antes de fiarse de
los vitales: un `health` de 0 con `inWorld` `false` quiere decir "no hay
jugador", no "esta muerto". Para eso esta `dead`, que si distingue los dos casos.

#### La hora del mundo

`dayTime` es el contador de ticks del mundo (`Level.getDayTime()`) y **no se
reinicia**: cuando un dia llega a `24000`, el siguiente empieza en `24001`, no
en `0`. La hora del dia es el resto de dividir entre los ticks del dia,
`dayTime % 24000`, que es **la unidad cruda del juego** y no una hora de reloj:
`0` es el amanecer, `6000` el mediodia, `12000` el atardecer, `13000` cuando ya
es de noche y `18000` la medianoche. Se manda el contador crudo porque es el
numero tal cual lo lleva el juego; reducido con `% 24000` es justo lo que
contesta `/time query daytime`.

Lo usa el modo switch del panel para saber cuando anochece, aplicando el resto.
Tres avisos:

- En un mundo sin sol (Nether, End) la cuenta **tambien avanza**, asi que una
  hora alta no significa alli que se haya hecho de noche. Para distinguirlo esta
  `dimension`, que ya viaja en el mismo `/status`.
- `0` es "no hay mundo" (menu de titulo), no "es de dia": el amanecer tambien es
  `0`. Por eso no vale con mirar el numero, hace falta `inWorld`.
- Con el mod viejo el campo **no llega** (no es que venga a `0`); el panel lo
  trata como "no hay hora" y no le dispara el guardado a nadie.

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

### `GET /inventory`

Que lleva el jugador encima. Solo lectura y sin efectos, asi que es un `GET`
normal.

```powershell
curl.exe -s "$BASE/inventory" -H $AUTH
```

```json
{
  "ok": true,
  "data": {
    "items": [
      { "id": "minecraft:oak_log", "name": "Tronco de roble", "count": 12, "slot": 4 },
      { "id": "minecraft:bread", "name": "Pan", "count": 5, "slot": 8 },
      { "id": "minecraft:diamond_pickaxe", "name": "Pico de diamante", "count": 1, "slot": 40 }
    ]
  }
}
```

Cada entrada es una ranura **ocupada**:

| Campo | Que es |
|---|---|
| `id` | Identificador del item en el registro: `minecraft:oak_log`. Es el que se usa para pedir cantidades concreteas. |
| `name` | Nombre ya traducido por el idioma del cliente: `Tronco de roble`. |
| `count` | Unidades en esa ranura, no en todo el inventario. |
| `slot` | Indice de la ranura dentro del contenedor del jugador. |

**Solo van las ranuras con algo dentro.** El contenedor tiene 41 ranuras y
mandar 41 objetos en cada consulta seria ruido: casi todos serian vacios. Un
inventario de madera y pico se lee con tres lineas, no con cuarenta.

El reparto de `slot` es el del contenedor del jugador, no el de la pantalla de
inventario:

| Rango | Que es |
|---|---|
| `0`-`8` | Barra rapida (las nueve de abajo). |
| `9`-`35` | Inventario principal (27 ranuras). |
| `36`-`39` | Armadura: botas, leggings, peto y casco, en ese orden. |
| `40` | Mano secundaria (por ejemplo, un escudo). |

Si la ranura esta vacia no aparece, asi que un `slot` que no se ve es que no hay
nada ahi: no se puede deducir que esta vacia por no aparecer, porque tampoco
aparecen las 41 ranuras de referencia.

Con el inventario sin nada dentro la respuesta es `{"ok":true,"data":{"items":[]}}`
(no un error). Sin mundo o sin jugador sale `409 not_connected`: el contenedor
pertenece al `LocalPlayer`, y sin el no hay nada que leer.

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

### `GET /profile` y `POST /profile`

Cambia el nombre con el que el cliente se conecta, **en caliente y sin
reiniciar Minecraft**.

```powershell
# Leer la identidad actual
curl.exe -s "$BASE/profile" -H $AUTH

# Cambiarla
curl.exe -s -X POST "$BASE/profile" -H $AUTH -H "Content-Type: application/json" `
  -d '{\"name\": \"Tester1\"}'
```

```json
{ "ok": true, "data": { "name": "Tester1", "uuid": "5b30590c-7361-31a2-a5a0-ee3cb0717365", "appliesOnNextConnect": false } }
```

**Solo vale para servidores en modo offline.** En modo online el servidor
autentica por UUID y token, no por nombre: un nombre inventado daria error de
autenticacion. Esta pensado para pruebas y servidores propios.

El UUID se recalcula con el mismo algoritmo que usa el servidor
(`UUID.nameUUIDFromBytes("OfflinePlayer:" + nombre)`, un UUID v3), de forma que
la pareja nombre/UUID nunca queda desincronizada y coincide con la que espera
el servidor.

**Cuando surte efecto.** No reinicia nada, pero el nombre solo se manda en el
paquete de login. Si ya estas dentro de un mundo, sigues siendo el jugador
anterior: el nombre nuevo entra en juego la proxima vez que conectes. Por eso
la respuesta incluye `appliesOnNextConnect`, que es `true` en ese caso. Para
aplicarlo de inmediato:

```powershell
curl.exe -s -X POST "$BASE/disconnect" -H $AUTH
curl.exe -s -X POST "$BASE/profile"  -H $AUTH -H "Content-Type: application/json" -d '{\"name\": \"Tester1\"}'
curl.exe -s -X POST "$BASE/connect"   -H $AUTH -H "Content-Type: application/json" -d '{\"host\": \"localhost\"}'
```

El nombre se valida con las reglas de Minecraft: `^[A-Za-z0-9_]{1,16}$`.
Un nombre invalido devuelve `400 invalid_player_name` y **no** cambia nada.

Ojo con la distincion: `GET /status` devuelve `playerName`, que es el jugador
**ya conectado**. `/profile` devuelve la identidad con la que se presentara el
cliente, que puede ser distinta.

### `POST /respawn`

Reaparece si el personaje esta muerto.

```powershell
curl.exe -s -X POST "$BASE/respawn" -H $AUTH
```

```json
{ "ok": true, "data": { "respawning": true } }
```

Por debajo llama a `LocalPlayer.respawn()`, que es lo que hace el boton
"Respawn" de la pantalla de muerte: envia el `ServerboundClientCommandPacket`
correspondiente y cierra la pantalla. No hay que replicar nada a mano.

`GET /status` incluye ahora `dead`, asi que un bucle de supervision puede
detectar la muerte sin adivinar:

```powershell
while ($true) {
  $s = Invoke-RestMethod "$BASE/status" -Headers @{ Authorization = "Bearer $TOKEN" }
  if ($s.data.dead) {
    curl.exe -s -X POST "$BASE/respawn" -H $AUTH | Out-Null
  }
  Start-Sleep 2
}
```

**Errores deliberados**, para que un bucle pueda preguntar sin miedo:

| Situacion | Respuesta |
|---|---|
| No hay mundo | `409 not_connected` |
| El jugador sigue vivo | `409 not_dead` |

Repetir el respawn sobre un jugador vivo **no** reenvia nada: se rechaza antes
de tocar la red. Es `POST` y no `GET` porque reaparecer cambia el estado de la
sesion; un refresco de pagina o un prefetch no deberian poder dispararlo.

### `GET /debug`

```powershell
curl.exe -s "$BASE/debug" -H $AUTH
```

```json
{ "ok": true, "data": { "chatBuffered": 12, "chatDropped": 0, "uptimeMs": 8412 } }
```

`chatDropped` distinto de 0 significa que el buffer se lleno (256 por defecto) y
esta perdiendo mensajes. Sube `chatBufferSize` si eso pasa.

### `POST /store`, `POST /store/now` y `GET /store`

Vuelcan el inventario del bot en un cofre, colocandolo si hace falta. Es la
unica parte de la API cuyo trabajo **no** ocurre en la peticion: el endpoint
deja la orden en manos del tick del cliente y responde al instante, asi que hay
que seguir el progreso con `GET /store`.

```powershell
# Al cofre de la config, o al lado del bot si no hay cofre configurado
curl.exe -s -X POST "$BASE/store" -H $AUTH

# A un cofre concreto
curl.exe -s -X POST "$BASE/store" -H $AUTH -H "Content-Type: application/json" -d '{ "x": 10, "y": -60, "z": 4 }'

# Colocar un cofre aqui y dejar de vaciar
curl.exe -s -X POST "$BASE/store/now" -H $AUTH

# Seguir el progreso
curl.exe -s "$BASE/store" -H $AUTH
```

Del `target` solo se admiten numeros: no hay nombres ni alias en el protocolo.
Los que usa el panel (`store cofre`) son un atajo suyo, resuelto antes de la
peticion, y para el mod es un `store` de lo mas normal. Ver `store [x y z]` en
`agents/07-instancias-simultaneas.md`.

Los tres devuelven **la misma forma**, con `state` en minusculas:

```json
{
  "ok": true,
  "data": {
    "state": "storing",
    "target": { "x": 10, "y": -60, "z": 4 },
    "moved": true,
    "placed": true,
    "stored": 0,
    "reason": null
  }
}
```

| Campo | Que es |
|---|---|
| `state` | `idle`, `walking`, `placing`, `opening`, `storing`, `done` o `failed`. |
| `target` | Bloque donde esta (o donde va a estar) el cofre. `null` si aun no se ha decidido. |
| `moved` | `true` si el bot ha tenido que caminar para llegar. |
| `placed` | `true` si el cofre lo ha puesto esta operacion. |
| `stored` | Unidades metidas en el cofre. |
| `reason` | Motivo en espanol. **Solo** si `state` es `failed`; si no, `null`. |

`done` y `failed` son terminales. Al llegar a cualquiera de los dos, el siguiente
`POST /store` empieza una operacion nueva y vuelve a `idle`.

#### Donde va el cofre

Para `POST /store`, en este orden:

1. el bloque que venga en el cuerpo (`x`, `y` y `z`, **las tres juntas**: dar solo
   una es `400 missing_field`, y un valor fuera del mundo es `400 invalid_field`);
2. si no hay cuerpo, el `chest` de la configuracion;
3. si tampoco hay, el bloque donde pisa el bot.

`POST /store/now` **ignora las dos primeras reglas**: siempre va al bloque donde
esta el bot, porque su sentido es "coloca un cofre aqui y dejalo". No lee el
cuerpo ni el `chest` de la config, asi que mandar coordenadas no cambia nada
(como en el resto de `POST` sin argumentos, por ejemplo `/baritone/pause`).

Cuidado con el punto 3 y con `/store/now`: un cofre no se puede colocar en el
bloque que pisa el jugador, asi que en la practica sale **al lado** (o encima, si
esta a un salto). El `target` del `202` es el bloque pedido; el del `GET /store`
ya es el bloque real donde ha quedado el cofre.

#### Que necesita

Un cofre (`minecraft:chest`) en la **barra rapida**, ranuras 0 a 8. No vale en
la mochila: si esta en otro sitio, el estado pasa a `failed` y el `reason` dice
en que ranura estaba. Con la barra llena de objetos, uno de los cuales es el
cofre, se coloca bien; para abrirlo se usa una ranura vacia si la hay, y si no
la que hubiera (abrir un cofre con cualquier objeto en la mano tambien funciona).

#### Como avanza

El trabajo son cuatro fases, ejecutadas en el tick del cliente (una por tick, sin
bloquear el juego en ningun momento):

| Fase | Que hace |
|---|---|
| `walking` | Baritone lleva al bot a un punto **a un radio** del cofre. No se camina si ya esta a tiro. |
| `placing` | Coloca el cofre. Se salta si en el destino ya hay uno. |
| `opening` | Abre el menu del cofre con un segundo `useItemOn` y la mano vacia. |
| `storing` | `shift+click` ranura a ranura, dos por tick, y cierra. |

El cofre va a las coordenadas pedidas, pero **`walking` no va a ellas**: se para a
2 o 3 bloques. `#goto x y z` de Baritone pone al bot *en* el bloque que se le
pide, no cerca, asi que apuntando al destino intenta meterse en el hueco del
cofre y, al no caber, coloca un bloque debajo para subirse: ese bloque es justo el
de apoyo que necesita el cofre, y `placing` ya no lo puede poner. A un radio el
bot llega, se para (`#cancel` al entrar en `placing`) y coloca en el destino
exacto, que es el primer sitio que se prueba.

Se prueban los anillos de 2 y 3 bloques, y dentro de cada uno las alturas de su
altura, una mas abajo y una mas arriba, quedandose con el anillo mas cercano que
tenga suelo firme y hueco para ponerse. De cada punto se descarta el que quede a
mas de 4 del centro del bloque de apoyo (las esquinas del anillo de 3 se
quedan, las de 2 no). Si no hay ninguno (un tunel de uno, por ejemplo) se va al
destino como antes y decide `placing`.

No hay plazos de tiempo: cada fase (caminar, colocar, abrir, volcar) dura lo que
haga falta hasta que el paso se completa o falla por un motivo de verdad (sin
mundo, sin cofre, sin sitio, el juego rechaza...). `stored` no se rellena hasta
el final, asi que mientras `state` sea `storing` va a 0: es correcto, no es que
falle.

Un ejemplo de bucle que espera al final:

```powershell
$req = @{ Authorization = "Bearer $TOKEN" }
Invoke-RestMethod -Method Post "$BASE/store" -Headers $req | Out-Null
do {
  Start-Sleep -Milliseconds 700
  $s = (Invoke-RestMethod "$BASE/store" -Headers $req).data
} while ($s.state -in "idle", "walking", "placing", "opening", "storing")
$s | ConvertTo-Json
```

#### Cuando falla, el bot se desconecta

Un `failed` **siempre** va acompanado de una desconexion del mundo (el mismo
camino que `POST /disconnect`). Es deliberado: un bot parado con la orden
incumplida no se distingue de uno sano, y ocupa el sitio del servidor igual. Si
el fallo fue justo no tener el cofre, eso es justo lo que se queria evitar.

Los motivos que salen en `reason` cubren: que no haya mundo o jugador, que el
cofre no este en la barra rapida, que no haya sitio libre con suelo debajo, que el
juego rechace la colocacion o la apertura, y que el cofre se haya quedado fuera
de alcance mas veces de las permitidas. Todos en espanol y sin acentos.

No hay tope de distancia ni de tiempo: el cofre puede estar en el otro extremo
del mapa y el bot camina hacia el hasta llegar. Si Baritone va despacio, se le
espera; no se corta la operacion por reloj.

#### Lo que no se guarda

Del inventario se vacian las 36 ranuras del inventario y de la barra rapida.
**No** la armadura (36..39) ni la mano secundaria (40): el menu del cofre no
tiene ranuras para ellas, y mandarle un indice que no existe haria que el juego
reventara. Si el bot lleva armadura puesta, esa se queda puesta.

#### `chest` en la configuracion

Cofre de destino por defecto, en `config/mc-puppeteer.json`:

```json
{
  "enabled": true,
  "host": "127.0.0.1",
  "port": 25580,
  "chest": { "x": 10, "y": -60, "z": 4 }
}
```

Es **por instancia** (cada una tiene su propio archivo), y si no esta, `/store`
sin coordenadas usa el sitio donde este el bot. Las coordenadas se validan al
arrancar, no en cada peticion: `x` y `z` tienen que estar en
-30 000 000..30 000 000 e `y` en -2048..2048. Un valor imposible sale por el log
al arrancar en vez de fallar a mitad de un volcado.

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

### Donde aparece la respuesta de Baritone

En el **log del cliente**, no en `/chat/history`. La captura del mod engancha
`ClientReceiveMessageEvents`, que solo ve lo que llega por red; Baritone genera
sus mensajes en local. En una instancia lanzada por
[`07-instancias-simultaneas.md`](07-instancias-simultaneas.md):

```bash
grep baritone run-instances/mc1/logs/latest.log | tail
```

El `note` del indice (`GET /puppeteer/baritone`) dice "la respuesta llega al
chat: leela en /chat"; con Baritone instalado eso no es correcto y queda
pendiente de arreglar en el codigo.

### Requisitos

- Baritone instalado y activo.
- Setting `prefixControl` activado (viene por defecto). Si lo desactivas, los
  comandos con `#` se enviarian al chat publico del servidor.
- Estar conectado a un mundo.

### `mine` y `goto` con bloque: hace falta `PUPPETEER_BARITONE_ASYNC=1`

Verificado con Baritone 1.14.0 sobre Minecraft 1.21.5 (2 instancias del
lanzador, servidor local en modo offline). Los dos comandos cuyo argumento es el
**nombre de un bloque** dejan el hilo principal de Minecraft colgado **para
siempre** si se envian desde el hilo principal, que es lo que hacia el mod por
defecto:

| Endpoint | Comando | Con el modo por defecto |
|---|---|---|
| `POST /baritone/mine {"block":"oak_log"}` | `#mine oak_log` | Hilo principal muerto |
| `POST /baritone/goto {"block":"oak_log"}` | `#goto oak_log` | Hilo principal muerto |

Lo que se ve desde fuera:

1. El propio `POST` no llega a responder: `503 main_thread_timeout` a los 5 s.
2. A partir de ahi, **todo** lo que necesite el hilo principal da `503` en 5 s:
   `/status`, `/chat/history`, `/baritone/*`, `POST /chat`...
3. `GET /health` sigue en `200` en 3 ms: el proceso vive, el juego no.
4. No se recupera solo (comprobado 10 minutos). Solo reiniciando la instancia.

#### Como evitarlo

El mod manda la orden de Baritone desde un hilo propio cuando ve
`PUPPETEER_BARITONE_ASYNC=1`, y **entonces todo funciona**. El lanzador de
instancias ya la pone en todas, asi que con el flujo normal no hay que hacer
nada:

```bash
node scripts/run-instances.ts -n 2     # ya lleva PUPPETEER_BARITONE_ASYNC=1
```

Para una instancia lanzada a mano, o para recuperar el comportamiento antiguo:

```bash
PUPPETEER_BARITONE_ASYNC=1 java ...   # activator el modo
PUPPETEER_BARITONE_ASYNC=0 node scripts/run-instances.ts -n 2   # desactivarlo
```

Comprobado con las dos instancias a la vez: `#mine minecraft:oak_log` en mc1 y
mc2, las dos siguen con `/status` en `200`, Baritone acepta la orden
(`> mine minecraft:oak_log`), crea el proceso de minado
(`Class: baritone.kd`, `Mine BlockOptionalMetaLookup{[BlockOptionalMeta{block=
Block{minecraft:oak_log}...}]`), calcula rutas (`PathNode map size: 35825`,
`Path goes for 40.36 blocks`) y `#cancel` lo para. Sin la variable, el mismo
`#mine` deja cada instancia en `503` permanente.

Lo que se paga: Baritone se ejecuta fuera del hilo principal y por eso
registra una vez por orden affected

```
baritone.az: java.lang.IllegalStateException: BlockStateInterface must be
constructed on the main thread
```

Baritone la captura y continua (el minado arranca igual), asi que es ruido en
el log, no un fallo. Si alguna vez la orden no llegara a ejecutarse, el mod
escribe `No se pudo enviar la orden de Baritone '<orden>'` en el log del juego,
porque el `202` se responde antes de que Baritone ejecute.

#### Por que se cuelga

Volcado de hilos (`jcmd <pid> Thread.print`) del caso colgado:

```
"Render thread" ... waiting on condition
	at java.util.concurrent.CompletableFuture.join
	at baritone.api.utils.BlockOptionalMeta$a.registryAccess
	- locked <0x...> (a java.lang.Class for baritone.api.utils.BlockOptionalMeta)
	...
	at com.bonilla.puente.PuenteController.baritone(PuenteController.kt:140)
	at com.bonilla.puente.client.ClientBridge.sendChat(ClientBridge.kt:103)
```

Baritone ejecuta el comando **dentro del envio del chat**, en el hilo principal,
y ahi inicializa `BlockOptionalMeta`. Ese init hace `join()` de un
`CompletableFuture<RegistryAccess>` que el propio Baritone creo pidiéndole al
servidor el registro dinamico `BLOCK` (`baritone.api.utils.BlockOptionalMeta$a`
es su `MinecraftClientContext`: se fabrica con `Unsafe.allocateInstance` y su
`registryAccess()` es ese `join()`). Ese futuro **si** se completa, pero lo
completa el hilo principal: al esperarlo desde el propio hilo principal se
auto-bloquea, y como el `join()` se hace con el lock de la clase ya tomado, no
hay vuelta. Por eso basta con dejar el hilo principal libre (mandando la orden
desde otro hilo) para que el futuro llegue a completarse.

No es culpa de mc-puppeteer (el modulo solo hace `sendChat`) ni de que las
instancias compartan cache: cada una tiene su propio
`run-instances/mcN/baritone/<servidor>/<dimension>/cache`.

#### El agujero que quedaba: `POST /chat`

Lo anterior solo cubria `POST /baritone/*`. `POST /chat` con
`{"message":"#mine ..."}` es la misma orden de Baritone pero entra por otra
puerta, y esa puerta **no miraba la variable**: siempre iba por
`MainThreadBridge`, o sea por el hilo principal. Por eso el cuelgue seguia
apareciendo con `PUPPETEER_BARITONE_ASYNC=1` puesta, y por eso seguia
apareciendo justo cuando se usaba el panel: `scripts/tui.ts` solo manda al
endpoint propio un puñado de consultas (`BARITONE_QUERIES`); **todo lo demas, y
`mine` entre ello, va por `/chat`** para poder pasar argumentos libres
(`#goto 100 64 200`).

Volcado del caso colgado por `/chat` (3 instancias, `mine` a las tres por el
panel; mc2 y mc3 muertos, mc1 viva porque ya tenia `BlockOptionalMeta`
inicializado de una prueba anterior por el endpoint propio):

```
"Render thread" ... waiting on condition
	at java.util.concurrent.CompletableFuture.join
	at baritone.api.utils.BlockOptionalMeta$a.registryAccess
	- locked <0x...> (a java.lang.Class for baritone.api.utils.BlockOptionalMeta)
	...
	at com.bonilla.puente.client.ClientBridge.sendChat(ClientBridge.kt:103)
	at com.bonilla.puente.PuenteController.sendChat$lambda$0(PuenteController.kt:54)
	at com.bonilla.puente.MainThreadBridge.callOnMainThread(MainThreadBridge.kt:43)
```

La linea `PuenteController.sendChat` (y no `PuenteController.baritone`) es la
pista: la orden entro por `/chat`. **`POST /chat` con un mensaje que empieza por
`#` ahora respeta la variable** y sale por el hilo propio, igual que
`/baritone/*`. Con eso las dos puertas estan cubiertas.

Efecto secundario que conviene conocer: cuando el hilo principal ya esta
colgado, **toda** la API de esa instancia parece congelada, no solo lo que toque
a Baritone. El pool HTTP son 4 hilos (`httpThreads`) y cada peticion que espera
al hilo principal los ocupa 5 s (`requestTimeoutMs`), asi que a los cuatro
llamadas el servidor deja de responder *a todo*, `/health` incluido. Por eso, si
una instancia ya esta colgada, mandar `mine` tampoco funciona: no es que `mine`
la colgara, es que la peticion se queda en la cola.

#### Lo que funciona (verificado en la misma sesion, con la variable puesta)

| Endpoint | Comando | Resultado |
|---|---|---|
| `POST /baritone/mine` | `#mine <bloque>` | Minando de verdad |
| `POST /baritone/mine` con `amount` | `#mine 2 dirt` | Minando de verdad |
| `POST /chat` con `{"message":"#mine dirt"}` | `#mine dirt` | Minando de verdad (es la ruta del panel) |
| `POST /baritone/goto` con `x`/`y`/`z` | `#goto 100 64 100` | Pathing real: `PathNode map size: 30558`, `72948 nodes per second` |
| `GET /baritone/version` | `#version` | `Null version (normal en dev)` |
| `GET /baritone/proc` | `#proc` | `Class: baritone.kd` con el minado activo |
| `GET /baritone/eta`, `modified`, `paused` | idem | OK |
| `GET /baritone/wp` | `#waypoints` | OK |
| `GET /baritone/help?q=mine` | `#help mine` | OK |
| `GET /baritone/find?block=diamond_ore` | `#find diamond_ore` | No cuelga, pero responde `No positions known, are you sure the blocks are cached?` porque la cache no llega a cargarse |
| `POST /baritone/top` | `#surface` | OK |
| `POST /baritone/sethome` | `#sethome` | OK |
| `POST /baritone/stop` | `#cancel` | OK |

Lo de la cache tiene el mismo origen: Baritone decide que el mundo es una replay
(`World seems to be a replay. Not loading Baritone cache.`) porque su chequeo
`Minecraft.method_1558().method_52811()` da true en este entorno, asi que
`#find` no tiene posiciones de donde mirar.

Lo que queda por determinar: si el modo asincrono tambien hace falta en un
Fabric de produccion, o si ahi el futuro se completa solo por el orden de
inicializacion de Baritone.

### Consultas (`GET`)

```powershell
curl.exe -s "$BASE/baritone/version"
curl.exe -s "$BASE/baritone/eta"
curl.exe -s "$BASE/baritone/paused"
curl.exe -s "$BASE/baritone/help?q=mine"
curl.exe -s "$BASE/baritone/find?block=diamond_ore"
```

```json
{ "ok": true, "data": { "sent": "#find diamond_ore" } }
```

Disponibles sin parametro: `version`, `proc`, `eta`, `modified`, `paused`, `wp`,
`gc`. Con parametro: `help?q=`, `find?block=`.

### Acciones (`POST`)

```powershell
curl.exe -s -X POST "$BASE/baritone/goto" -H $AUTH -d '{"x":1000,"y":64,"z":500}'
curl.exe -s -X POST "$BASE/baritone/mine" -H $AUTH -d '{"block":"diamond_ore","amount":16}'   # necesita PUPPETEER_BARITONE_ASYNC=1
curl.exe -s -X POST "$BASE/baritone/build" -H $AUTH -d '{"file":"base.schematic"}'
curl.exe -s -X POST "$BASE/baritone/follow" -H $AUTH -d '{"target":"Alex"}'
curl.exe -s -X POST "$BASE/baritone/cleararea" -H $AUTH -d '{"radius":5}'
curl.exe -s -X POST "$BASE/baritone/stop?force"
curl.exe -s -X POST "$BASE/baritone/pause"
curl.exe -s -X POST "$BASE/baritone/thisway" -H $AUTH -d '{"distance":50}'
```

Los que llevan nombre de bloque (`mine`, `goto {"block":...}`) cuelgan la
instancia si el mod va con el modo por defecto; con `PUPPETEER_BARITONE_ASYNC=1`
funcionan (ver el apartado de arriba).

`goto` admite las tres formas que entiende Baritone:

| Cuerpo | Comando |
|---|---|
| `{"x":1000,"y":64,"z":500}` | `#goto 1000 64 500` |
| `{"x":1000,"z":500}` | `#goto 1000 500` |
| `{"y":64}` | `#goto 64` |
| `{"block":"diamond_ore"}` | `#goto diamond_ore` |

Mezclas parciales (`{"x":1,"y":2}`) dan `400 invalid_goal` en vez de dejar que
Baritone lo interprete de otra forma.

Sin argumentos: `surface`, `cancel`, `path`, `invert`, `come`, `blacklist`,
`elytra`, `click`, `pause`, `resume`, `farm`, `sel`, `set`, `reset`,
`waypoints`, `sethome`, `home`, `explorefilter`, `pickup`.
Mantenimiento: `repack`, `reloadall`, `saveall`, `render`.

### Nombres que no son los que parecen

Esto se verifico contra las firmas del codigo fuente de Baritone, no contra su
documentacion. Varios nombres intuitivos no existen como comandos:

| Endpoint | Comando real | Por que |
|---|---|---|
| `POST /baritone/stop` | `#cancel` | No hay `StopCommand`; `stop` es alias de `cancel` |
| `POST /baritone/cleararea` | `#sel cleararea N` | No hay `ClearAreaCommand`; es subcomando de `sel` |
| `GET /baritone/modified` | `#modified` | No hay `ModifiedCommand`; es alias de `set` |
| `GET /baritone/wp` | `#waypoints` | `wp` es alias |
| `POST /baritone/top` | `#surface` | `top` es alias |

Y hay comandos que no estaban cubiertos: `pause`, `resume`, `paused`, `set`,
`reset`, `sel`, `click`, `pickup`, `explorefilter`, `waypoints`, `sethome`,
`home`, `litematica`.

`schematica` **no** esta disponible: esta comentado en `DefaultCommands.java`,
por lo que solo se puede construir con `build`.

Dos comandos exigen argumento y por eso no admiten `POST` sin cuerpo:
`thisway` (`requireExactly(1)`) y `axis` (pide la altura). Mandarlos vacios da
`400 requires_arguments` o `400 missing_field`, en vez de dejar que Baritone
responda con un error.

### La respuesta de Baritone va al chat

Este es el punto que mas sorprende. Puente devuelve `202` con el comando
enviado, pero **lo que Baritone responde llega como chat**, no como cuerpo de
la respuesta HTTP. Para leerlo:

```powershell
curl.exe -s -X POST "$BASE/baritone/goto" -H $AUTH -d '{"x":1000,"y":64,"z":500}'
Start-Sleep -Seconds 2
curl.exe -s "$BASE/chat?limit=20"
```

Alternativa inmediata, sin esperar: `POST /chat` con el prefijo a mano, p. ej.
`{"message":"#goto 1000 64 500"}`. Es lo mismo que hace `/baritone/goto`. Con
`#mine` solo funciona con `PUPPETEER_BARITONE_ASYNC=1`: sin esa variable no
llega a responder porque la instancia se queda colgada antes (ver el apartado de
los comandos con bloque).

### Validacion

Los parametros se validan antes de construir el comando, para que un cliente
HTTP no pueda inyectar texto en un comando de Baritone:

- Bloques: `[A-Za-z0-9_:.-]{1,64}`
- Jugadores: `[A-Za-z0-9_]{1,16}`
- Schematicos: `[A-Za-z0-9_-]{1,64}\.schematic`
- Coordenadas: -30 000 000 a 30 000 000 (altura: -64 a 320)
- `amount` de `mine`: 1 a 4096. Opcional: sin el, Baritone mina hasta agotar.
  Ojo con la firma de Baritone, que es `#mine [<cantidad>] <bloque>`: la
  cantidad va **primera** (`{"block":"diamond_ore","amount":16}` se traduce a
  `#mine 16 diamond_ore`). Al reves, Baritone contesta
  `Error at argument #2: Expected ...` y no mina nada.
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

**Excepcion: `/store` y `/store/now`.** Su `202` no quiere decir que la accion
este hecha, sino que el trabajo se ha encolado para el siguiente tick del
cliente. Por eso no tienen timeout de hilo principal y hay que preguntar despues
a `GET /store` (ver la seccion 4).

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

## 10. Corregido: el puerto por defecto de `/connect`

Antes, `POST /connect` sin puerto explicito intentaba conectarse a **25580**
(puerto del servidor HTTP de Puente) en vez de **25565** (el de Minecraft).

La causa era que `PuenteController` reutilizaba `PuenteConfig.DEFAULT_PORT` como
si fuera el puerto por defecto del juego. Ahora el controlador tiene su propia
constante:

```kotlin
// PuenteConfig.kt
const val DEFAULT_PORT = 25580          // puerto del servidor HTTP

// PuenteController.kt
const val DEFAULT_MINECRAFT_PORT = 25565 // puerto del juego
var port = body.optInt("port") ?: DEFAULT_MINECRAFT_PORT
```

Afectaba a los tres caminos sin puerto: `{"host": "servidor"}`,
`{"address": "servidor"}` y una IPv6 sin `:puerto`. Ahora los tres usan 25565.

La prueba de humo lo fija con la asercion
`POST /connect con {"host":"servidor"} -> 25565`, de modo que un regreso al
comportamiento anterior la haria fallar.