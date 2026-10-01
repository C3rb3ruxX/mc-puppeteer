# 07 · Instancias simultaneas de Minecraft

Como lanzar N clientes de Minecraft (Fabric) a la vez, cada uno con mc-puppeteer
instalado, su directorio de juego, su puerto HTTP, su config y su Baritone, sin
tocar `~/.minecraft` y sin el `./gradlew runClient` (que solo admite una).

| Fichero | Que hace |
|---|---|
| [`../scripts/run-instances.ts`](../scripts/run-instances.ts) | El lanzador. Bun o Node, cero dependencias. |
| [`../scripts/gradle-run-config.init.gradle`](../scripts/gradle-run-config.init.gradle) | Tarea `dumpRunConfig`: le dice al script como arranca Loom el cliente. |
| [`../scripts/tui.ts`](../scripts/tui.ts) | Panel de control: ver el estado de todas y mandarles la misma orden a varias. |

## Uso

```bash
bun   scripts/run-instances.ts -n 3      # 3 clientes: 25580, 25581, 25582
node scripts/run-instances.ts -n 3      # identico, si no tienes Bun
```

Sin dependencias: solo `node:child_process`, `node:fs`, `node:net`, `node:path`,
`node:crypto` y `fetch`. Bun los trae todos de serie y Node 22+ tambien.

Al arrancar sale una tabla con la URL de cada instancia y un `curl` listo. Ctrl-C
para pararlas todas.

## Opciones

| Opcion | Def. | Que hace |
|---|---|---|
| `-n`, `--instances N` | 2 | Cuantas instancias |
| `-p`, `--port-base P` | 25580 | Primer puerto. **Si esta ocupado usa el siguiente** (25581, 25582...) en vez de fallar |
| `--host H` | 127.0.0.1 | Host del servidor HTTP del mod |
| `-m`, `--memory Xmx` | 2G | Memoria por instancia |
| `-w`, `--width W` / `--height H` | 854x480 | Tamano de ventana |
| `--dir D` | run-instances | Carpeta de instancias |
| `--prefix NAME` | mc | Prefijo: `mc1`, `mc2`... |
| `--username NICK` | el de la instancia | Nombre de jugador |
| `--no-username` | | Que elija el juego un nombre aleatorio (`Player372`) |
| `--java PATH` | el del PATH | Ejecutable de java |
| `--baritone ESPEC` | auto | `auto`, `none`, ruta a un `.jar` o URL |
| `--baritone-version V` | 1.14.0 | Release de Baritone |
| `--token T` | sin token | Exige ese token en todas las instancias |
| `--clean` | | Borra la carpeta de la instancia antes de arrancar |
| `--no-build` | | No ejecuta `./gradlew build` antes |
| `--restart` | | Reinicia las instancias que mueran |
| `--gl auto\|software\|hardware` | auto | `auto` = OpenGL por software si no hay display |
| `--timeout SEG` | 240 | Espera maxima por instancia |
| `--no-wait` | | No espera a `/health` |
| `--refresh` | | Vuelve a pedir el run config a Gradle |
| `--verbose` | | Muestra toda la salida de los clientes, no solo lo relevante |

## Que hace por dentro

1. **`./gradlew build`** (salvo `--no-build`).
2. **Gradle dice como arrancar el cliente.** La tarea `dumpRunConfig` (init script
   de `scripts/`) vuelca a `scripts/.run-config.json` el `mainClass`, el
   `classpath`, los `jvmArgs` y los `programArgs` de la run `client` de Loom:
   `net.fabricmc.devlaunchinjector.Main`, 173 entradas de classpath y
   `-Dfabric.dli.config=... -Dfabric.dli.env=client -Dfabric.dli.main=...`.
   El classpath se escribe en un **argfile** (`@scripts/.cache/classpath-*.txt`),
   igual que hace Loom, porque 173 rutas no caben en la linea de comandos de
   Windows.
3. **Baritone**: se descarga `baritone-standalone-fabric-1.14.0.jar` (la release
   que declara `minecraft: ["1.21.5"]`) a `scripts/.cache/`, se comprueba el
   sha256 y se copia al `mods/` de cada instancia. La cache evita volver a
   descargar. `--baritone none` lo quita.
4. **Puertos**: `pickPort()` prueba a escuchar en cada puerto y salta al
   siguiente si esta ocupado. Tambien entre instancias del mismo arranque.
5. **Carpetas**: si `run-instances/mc1` ya existe, se usa `run-instances/mc1-2`
   (y `-3`...) en vez de reventar. `--clean` la borra antes.
6. **Config**: escribe `config/mc-puppeteer.json` (puerto propio) y `options.txt`
   con `pauseOnLostFocus:false`, tope de FPS y sonido apagado: imprescindible
   para que una ventana en segundo plano no pause el juego.
7. **Arranque**: un `java` por instancia con `-Xmx`, los `jvmArgs` de Loom, el
   argfile y `--width/--height`. El **directorio de trabajo es el gameDir**: el
   loader usa el CWD cuando no le pasan `--gameDir`, que es lo que hace el
   arranque de desarrollo de Loom (sus `programArgs` van vacios).
8. Espera a que cada una responda `GET /puppeteer/health` (sin token) y luego se
   queda vigilando: cada linea interesante de cada cliente sale por consola con
   el prefijo `[mc1]`, y el log completo de cada uno queda en
   `run-instances/mc1/logs/stdout.log` y en el `logs/latest.log` del juego.

### Por que el gameDir es el directorio de trabajo

`fabric.gameDir` **no existe** en Fabric Loader 0.19.5 (no aparece en el jar).
El juego se arranca con `KnotClient.main(args)` y Loader deduce el directorio
del juego del CWD cuando los argumentos no traen `--gameDir`, que es el caso del
`runClient` de Loom: sus `programArgs` estan vacios. Por eso cada instancia se
lanza con `cwd` = su carpeta y por eso no hace falta pasar nada mas.

## Ficheros que crea

```
run-instances/mc1/
  config/mc-puppeteer.json   puerto propio, requireToken, authToken
  mods/                      baritone-standalone-fabric-1.14.0.jar
  options.txt                pauseOnLostFocus:false, maxFps:60, ...
  baritone/                  cache de Baritone, por instancia y por servidor
  logs/stdout.log            stdout+stderr del cliente
  logs/latest.log            log del juego
  saves/, resourcepacks/, servers.dat, ...
run-instances/.instances.json  registro: nombre, puerto, token y carpeta de cada una
scripts/.cache/              baritone + argfiles
scripts/.run-config.json     lo que dice Gradle
```

Los ultimos tres estan en `.gitignore`.

El registro lo escribe el lanzador al arrancar y lo lee el TUI, que asi no
tiene que adivinar puertos (ademas barre `--scan-from`..`--scan-to` por si
alguien arranco los clientes a mano).

## Panel de control: `scripts/tui.ts`

```bash
bun   scripts/tui.ts          # o: node scripts/tui.ts
```

Muestra el estado de cada instancia y una linea de ordenes. Sin dependencias:
solo `node:fs`, `node:path`, `node:process`, `node:url` y `fetch`. No usa
`readline`: lee el teclado byte a byte (`setRawMode` + eventos `data`), que es
lo unico que funciona igual en Bun y en Node.

El panel tiene cuatro zonas en dos columnas, con el reparto en porcentajes
(`W_LEFT`, `W_RIGHT` y `H_TOP` en `view.ts`): 66% y 30% del ancho util, 60% y 40%
del alto. Asi se adapta a la terminal sin tener que tocar nada:

```
mc-puppeteer · 3 instancia(s), 3 viva(s) · cada 2s · 12:12:44
┌ bots ──────────────────────────────────────────────────────────────┬ chat ────────────────────────────────┐
│     inst     puerto uuid pos                   comida             │ instancias: mc1:25580                │
│                                                vida               │ mc2:25581  mc3:25582                 │
│   ● mc1      25580  a509 [-52, 76, -18]        ████████████████████  20 │ escribe /help para la lista de       │
│                                                ████████████████████  20 │ ordenes                            │
│   ● mc2      25581  ---- [0, 0, 0]             ░░░░░░░░░░░░░░░░░░░░   0 │                                    │
│                                                ░░░░░░░░░░░░░░░░░░░░   0 │                                    │
│   ● mc3      25582  19a9 [-46, 75, -19]        ████████████████████  20 │                                    │
│                                                ████████████████████  20 │                                    │
├──────────────────────────────────────────────────────────────────┼────────────────────────────────────┤
│   5 tipo(s), 133 unidad(es)                                      │ >                                  │
│   2 bots · 1 sin datos                                           │                                    │
│   87    Oak Log              ████████████████████████████        │                                    │
│   29    Dirt                 █████████░░░░░░░░░░░░░░░░░░░        │                                    │
│   11    Chest                ████░░░░░░░░░░░░░░░░░░░░░░░░        │                                    │
│    5    Leaf Litter          ██░░░░░░░░░░░░░░░░░░░░░░░░░░        │                                    │
│    1    Oak Sapling          ░░░░░░░░░░░░░░░░░░░░░░░░░░░░        │ /say /cmd /baritone /disperse       │
│                                                                    │ /store /storenow /connect           │
│                                                                    │ /items /focus /every /sel           │
│                                                                    │ /quit · @1,3 · 1-9 · Q              │
└──────────────────────────────────────────────────────────────────┴────────────────────────────────────┘
```

- **Arriba izquierda**: una instancia por fila y **dos lineas por instancia**, con
  los datos en la primera y las barras de **comida y vida** apiladas en la
  segunda. Hay dos lineas de cabecera para que se sepa cual de las dos barras es
  cual. Las columnas se eligen segun el ancho que queda (`visibleColumns` en
  `table.ts`): primero lo basico (instancia, puerto, uuid, posicion y las dos
  barras) y despues `mundo`, `jugadores`, `fps` y `ms`. Si aun asi no caben se van
  quitando las de `DEDUCIBLES` (`dim`, `estado`, `pos`), que son las que se
  deducen de las demas. Las barras son lo ultimo en irse porque no se deducen de
  nada.
- **Arriba derecha**: el `feed`, con el chat de todas mezclado y las respuestas a
  las ordenes. Se lee de `/chat/history` **sin vaciar los buffers** del mod: se
  recuerda la marca de tiempo del ultimo mensaje y solo se pinta lo posterior, asi
  que al abrir el panel no se vuelca el historial anterior. Los mensajes largos se
  **parten en varias lineas** (`wrap` en `ansi.ts`) en vez de cortarse, que
  dejaria el final fuera. Flechas y `RePag`/`AvPag` lo recorren.
- **Abajo izquierda**: el inventario **de todos los bots, sumado**, de mas a
  menos, con una barra escalada al item mas numeroso de lo que se esta mirando.
  Sale de `GET /inventory`, que se pide a todas las instancias vivas en cada
  refresco (en paralelo; son solo las ranuras ocupadas). La segunda linea dice
  cuantos bots se han sumado y, si alguno no ha contestado, cuantos faltan, para
  que una cifra incompleta no parezca la real.
- **Abajo derecha**: el prompt y, debajo del todo, el recordatorio de atajos. Va
  entero y se reparte en varias lineas si la zona es estrecha, porque un
  recordatorio al que le falta el final no recuerda nada.
- **Foco**: `focus <n|nombre>` estrecha el inventario a una sola instancia, que
  es cuando hace falta saber de quien es cada cosa. La tabla marca con `>` la que
  esta enfocada y el titulo pasa a `inventario · mc2`. Sin foco el titulo es
  `inventario · todos`. Se vuelve a la suma con `focus all`, y `f` (o
  `focus next`) recorre las instancias y da la vuelta a la global.
- Verde: responde. **Amarillo**: vive pero el estado no ha llegado (el hilo
  principal del juego esta ocupado; se comprueba con `/health`, que no lo toca).
  **Rojo**: no responde; sus barras salen en `-`.

### Como esta partido el codigo

`scripts/tui.ts` es solo el arranque (argumentos, bucle de refresco, apagado).
Todo lo demas esta en `scripts/tui/`, para editar una cosa sin releer 1600 lineas:

| Modulo | Que hace |
|---|---|
| `ansi.ts` | colores (`frame` es el azul claro de los marcos, no el gris: hay temas que pintan el gris de negro), ancho **visible** (los codigos de color no ocupan columnas), barras y `wrap` |
| `types.ts` | los tipos compartidos |
| `state.ts` | estado, feed y ganchos hacia el arranque |
| `api.ts` | llamadas HTTP al mod, con tiempo limite |
| `targets.ts` | descubrimiento de instancias y destinos (`@1,3`) |
| `poll.ts` | lo que se pregunta en cada refresco (`/status`, chat, inventario) |
| `table.ts` | zona de bots: que columnas hay, cuanto ocupa cada una y las dos lineas de cada instancia |
| `inventory.ts` | zona de inventario: suma los inventarios de todos los bots por tipo de item y las dibuja |
| `view.ts` | el compositor: reparte ancho y alto en las cuatro zonas y pinta |
| `commands.ts` | las ordenes y el `/help` |
| `store.ts` | `store` y `storenow`: manda la orden y va leyendo el estado |
| `chests.ts` | los cofres con nombre de `store <nombre>`, en `scripts/.tui-config.json` |
| `panel.ts` | lo que pasa al escribir o al pulsar una tecla |

Para cambiar el aspecto del panel basta con tocar tres sitios: las constantes de
`view.ts` (`W_LEFT`, `W_RIGHT` y `H_TOP`: como se reparten ancho y alto entre las
cuatro zonas), la tabla `COLUMNS` de `table.ts` (que columnas hay y cuanto ocupa
cada una) y la lista `DEDUCIBLES` de `table.ts` (cuales se van primero cuando el
ancho aprieta).

### Mandar la misma orden a varias

| Como | A quien |
|---|---|
| `say hola` | las seleccionadas (por defecto, todas) |
| `!hola` | atajo de `say` |
| `@2 cmd list` | la instancia 2 |
| `@1,3 say hola` | la 1 y la 3 |
| `@mc2 connect 1.2.3.4:25565` | la que se llama `mc2` |
| `@all ...` | todas, seleccionadas o no |

Ordenes: `say`/`chat`, `cmd`, `connect`, `disconnect`, `respawn`, `profile`,
`status`, `health`, `players`, `items`, `history [n]`, `baritone`, `disperse`,
mas las del panel: `every <seg>`, `scan`, `token <t>`, `sel <n|all|none>`,
`log <n> [mcN]`, `focus <n|nombre|all>`, `store [x y z|<nombre>]`, `storenow`,
`chest [list|<nombre>|<nombre> x y z|<nombre> rm]`, `target`, `clear`, `help`,
`quit`. Con `/` delante o tal cual.

`baritone` usa el endpoint propio (`GET /baritone/version`, `/proc`, `/eta`,
`/modified`, `/paused`, `/wp`, `/gc`) y para todo lo demando manda la orden por
chat con `#`, que es como Baritone la espera y asi admite argumentos libres
(`#goto 100 64 200`).

### `items` y `focus`

`items` tira el inventario de **todas** las seleccionadas al feed, agrupado por
tipo de item y de mas a menos:

```
> items
  mc1    7 tipo(s), 343 unidad(es)
    Tronco de roble          ████████████████   126
    Adoquín                  ████████████░░░░    95
    Tierra                   █████░░░░░░░░░░░    40
```

Los nombres son los que trae el juego ya traducidos (`stack.hoverName.string`), no
el id. Si el mod no expone `/inventory`, el panel lo dice en vez de fallar en
silencio: sale `sin inventario` con el motivo debajo en el panel de la derecha.

`focus <n|nombre>` deja de sumar y mira el inventario de una sola instancia (el
titulo pasa a `inventario · mc2` y la tabla la marca con `>`); `focus all` vuelve
a la suma de todas. `focus next` rota y al dar la vuelta entera regresa a la
global, que es tambien lo que hace la tecla `f`. Los inventarios se piden a todos
los bots en cada ciclo, asi que cambiar de vista no cuesta nada.

### `disperse <x> <y> <z> <radio>`

Reparte las instancias seleccionadas dentro de un radio de bloques alrededor de
un punto, mandando a cada una un `#goto` a su sitio. Es un `baritone goto` por
instancia, con el reparto calculado aqui:

```
@1,2,3 disperse 0 64 0 30
  mc1    ok   {"sent":"#goto 0 64 0"}
  mc2    ok   {"sent":"#goto -16 64 14"}
  mc3    ok   {"sent":"#goto 3 64 -30"}
disperse -> 3 instancia(s) en 30 bloques alrededor de 0 64 0
```

- El reparto es una **espiral de angulo aureo** dentro del disco: la primera
  instancia cae en el centro exacto y las demas se van separando hasta el radio.
  Es determinista, asi que la misma orden lleva siempre a las mismas instancias
  al mismo sitio (al azar se amontonan).
- Todas van a la altura `y` que se pasa; el radio se aplica en el plano XZ.
- Van al endpoint propio `POST /baritone/goto` (que valida las coordenadas), no
  por `/chat`.
- Antes de mandar nada comprueba que los cuatro valores son enteros, que el radio
  no es negativo, que la altura esta entre -64 y 320 y que centro mas radio no se
  sale del mundo (30 000 000), que son los mismos limites que impone el mod. Si
  algo falla, avisa y no manda nada.
- Funciona con el prefijo de destino como cualquier otra orden: sin prefijo, a las
  seleccionadas; `@all`, a todas.

### `store [x y z | <nombre>]` y `storenow`

`store` vuelca el inventario de las seleccionadas en un cofre; `storenow`
coloca un cofre donde este cada bot y lo deja, sin volcar nada.

```
> @1,2 storenow
  mc1    aceptado (202); esperando...
  mc1    colocando el cofre
  mc1    hecho: 0 unidad(es) al cofre

> store cofre
store: 'cofre' = 10 -60 4
  mc2    aceptado (202); esperando...
  mc2    caminando al cofre
  mc2   abriendo el cofre
  mc2    vaciando el inventario
  mc2    hecho: 431 unidad(es) al cofre
```

- No es una llamada y se acabó: el mod la ejecuta durante varios ticks y
  devuelve **202** al momento. El panel va leyendo `GET /store` y escribe una
  linea **cada vez que el estado cambia** (`caminando`, `colocando`, `abriendo`,
  `vaciando`, `hecho`, `fallo`), no en cada consulta.
- El cofre es el de las coordenadas que se pasen; sin coordenadas, el de la
  config de cada instancia (`chest` en su `config/mc-puppeteer.json`); sin eso,
  donde este el bot.
- **En vez de coordenadas se puede escribir un nombre** (`store cofre`), que el
  panel traduce antes de llamar al mod. Para el mod es indistinguible de
  `store 10 -60 4`: los nombres viven solo en el panel. Se dan de alta con
  `chest`, que escribe `scripts/.tui-config.json`:
  ```
  > chest cofre 10 -60 4
  cofre cofre = 10 -60 4   (store cofre)
  > chest mina 100 -12 -33
  cofre mina = 100 -12 -33   (store mina)
  > chest
  cofres con nombre, para  store <nombre>  (.../scripts/.tui-config.json):
    cofre        10 -60 4
    mina         100 -12 -33
  > chest mina rm
  cofre mina: borrado
  ```
  `chest <nombre>` a secas muestra uno, `chests` y `cofre` valen igual que
  `chest`, y con `--chests F` se apunta a otro fichero. Un nombre no guardado
  no se pasa como texto al mod: sale el aviso con la lista de los que si hay.
  El fichero se puede editar a mano, pero se reescribe entero en cada `chest`,
  asi que lo que se edite a mano y no este en el panel se pierde. Si el JSON
  esta roto o una entrada no trae tres enteros, se avisa y se sigue con las
  demas; y **`chest` no escribe encima de un fichero que no se ha podido
  leer**, que seria dejar al usuario sin sus cofres y sin aviso de por que.
- **Camina con Baritone** si el cofre esta a mas de 4 bloques, asi que el
  `store` de verdad necesita `PUPPETEER_BARITONE_ASYNC=1` (el lanzador ya la
  pone). No hay tope de distancia; el tiempo de espera sale de lo lejos que este
  el cofre (250 ms por bloque, de 120 s a 720 s).
- Si en el destino no hay cofre, **coloca uno**, y para eso el bot necesita
  tener un cofre **en la barra rapida** (ranuras 0-8, no solo en el inventario:
  solo se puede colocar con la ranura seleccionada, y un cofre mas alla no se
  saca). Si no puede (no tiene, no esta en la barra rapida, no se puede colocar,
  no se abre, o se agota la espera), **se desconecta** y el motivo sale en el
  feed: un bot atascado en un servidor de farm estorba mas que uno que se va.
- Vuelca las **36 ranuras del inventario**. La armadura (ranuras 36-39) y la mano
  secundaria (40) **no se pueden guardar**: en vanilla no caben en un cofre, y
  mandar esas ranuras al menu revienta. El bot se los queda puestos. Para
  desatarse hay que hacerlo a mano con `/item replace`.
- El panel espera 150 s para `store` y 30 s para `storenow`. Si se agotan, avisa
  y deja de preguntar, pero **el mod sigue a lo suyo**: no se cancela nada.

### Teclas

Con la linea **vacia**: `1`-`9` seleccionan, `a` todas, `n` ninguna,
`r` refresca, `l` ultimas lineas del log, `f` recorre el foco del inventario (y
vuelve a la suma de todos al dar la vuelta), `Q` sale. `Ctrl-C` o `Esc` salen
siempre. Flechas y `RePag`/`AvPag` recorren el feed. Escribiendo texto, todas las
teclas van al texto (por eso `q` no sale: `Q` si, para poder mandar `quieto` por
chat).

Un detalle que no es evidente: los atajos de una tecla **solo** funcionan con la
linea vacia. Si no, estarian pisando el texto que se esta escribiendo, y `n` o
`a` son justo las teclas que se usan al escribir. Por lo mismo, `r` y `f` no se
pueden mandar como atajo y hay que escribirlos (`/refresh` no existe: `r` a secas
con la linea vacia, o `baritone r` si lo que se quiere es lo otro).

### Modo script

Sin panel, util en cron o desde otro script:

```bash
node scripts/tui.ts --once --connect 1.2.3.4:25565   # manda y sale
node scripts/tui.ts --every 10 -c "say revision"     # repite cada 10 s
node scripts/tui.ts --every 2                         # sin TTY: como `watch`
```

Opciones: `--registry F`, `--scan-from N`, `--scan-to N`, `-e/--every SEG`,
`--token T`, `--host H`, `--dir D`, `-c/--command TXT`, `--once`, `--no-color`,
`-h/--help`.

### Verificado

Con 3 instancias conectadas al servidor de `127.0.0.1:25565`:

- La tabla se refresca sola cada 2 s con pantalla, mundo, dimension, jugadores,
  FPS y latencia de `/status`.
- `say prueba final` a las 3: mc2 y mc3 respondieron `202 {"sent":...}` y el
  eco `<mc2> prueba final` / `<mc3> prueba final` llego al feed de las otras
  (cada una ve la red, por eso sale repetido).
- `2` deselecciono mc2 y `@1,3 cmd list` solo fue a mc1 y mc3.
- `every 1` cambio el intervalo en caliente; `Q` salio limpio.
- `--once -c '@2 say cuidado'` -> `1 instancia(s)`, solo mc2.
- `@all baritone version` -> `{"sent":"#version"}` en mc2 y mc3.
- Sin registro (copiando el script fuera del repo) discovery por barrido de
  puertos: losinio como `:25580`, `:25581`, `:25582`.

## Verificado en una sesion real

Con un servidor Minecraft escuchando en `127.0.0.1:25565`:

- 3 instancias a la vez, cada una en su carpeta, puertos 25580/25581/25582.
- En el arranque automatico, con 25580 ocupado por otros clientes, se produjo
  `mc1 -> 25581`, `mc2 -> 25582`: el salto de puerto funciona.
- `Setting user: mc1 / mc2 / mc3`: cada una entra con su nombre y su UUID offline
  propio.
- `Loading 58 mods: - baritone 1.14.0` en las tres, y Baritone creo su cache en
  `run-instances/mc1/baritone/127.0.0.1:25565/minecraft/overworld_384/cache`.
- `GET /status` sin cabecera `Authorization` responde 200 en las tres
  (por defecto van sin token).
- `POST /connect {"address":"127.0.0.1:25565"}` en mc1: `202`, y a los 12 s
  `inWorld:true`, `playerName:"mc1"`,
  `playerUuid:"4ef3fdf3-0f03-35cf-a7db-aa7fc2ebfcf6"`, `dimension:"minecraft:overworld"`.
  **El camino `/connect` de 1.21.5 (tres llamadas encadenadas) funciona.**
- `POST /chat {"message":"hola desde mc1"}` y el eco `<mc1> hola desde mc1` en
  `/chat/history`, junto al mensaje del servidor `mc1 was slain by Zombie`: envio
  y captura de chat funcionan. El historial de mc2 sigue vacio (aislamiento).
- El cliente murio de un zombi: `POST /respawn` -> `dead:false`. **El paquete de
  reaparicion tambien funciona.**
- `POST /disconnect` -> `screen:"TitleScreen"`, `inWorld:false`.
- Ctrl-C (SIGTERM) -> `Parando 3 instancia(s)...` y ningun proceso java vivo.

## Hallazgo: las respuestas de Baritone no salen por `/chat`

`GET /puppeteer/baritone/version` envia `#version` por el chat (correcto), pero
la respuesta de Baritone **no** aparece en `/chat/history`. La captura del mod
engancha `ClientReceiveMessageEvents`, que solo ve lo que llega por red; Baritone
genera su respuesta en local (`[CHAT] [Baritone] Null version` en el log del
juego). Es decir: las respuestas de Baritone hay que leerlas en
`run-instances/mc1/logs/latest.log`, no en la API. El indice `/baritone` y las
envias (`/baritone/goto`, `/mine`...) funcionan igual.

## Hallazgo: `mine` cuelga el hilo principal (y como se arregla)

La hipotesis de que compartir la cache de Baritone hiciera que las instancias se
congelaran **queda descartada**: cada una tiene la suya, con su directorio de
mundo propio y separado.

```
run-instances/mc1-9/baritone/127.0.0.1:25565/minecraft/overworld_384/cache/
run-instances/mc2-9/baritone/127.0.0.1:25565/minecraft/overworld_384/cache/
```

Lo que congela de verdad es `POST /baritone/mine` (y `POST /baritone/goto` con
`{"block":...}`) cuando la orden sale del hilo principal: ese hilo se queda
esperando un `CompletableFuture` de Baritone para siempre, ya con el lock de la
clase tomado. Detalle completo, volcado de hilos y matriz de comandos en
[`05-api.md`](05-api.md) (apartado "`mine` y `goto` con bloque").

**Se arregla con una variable de entorno**, porque el futuro si llega a
completarse: lo completa el hilo principal, asi que basta con no ocuparlo. El
lanzador la pone solo en todas las instancias, no hay que hacer nada:

```bash
node scripts/run-instances.ts -n 2     # ya lleva PUPPETEER_BARITONE_ASYNC=1
```

Con `PUPPETEER_BARITONE_ASYNC=0` en el entorno (o en el `env` de
`scripts/.run-config.json`) se recupera el comportamiento antiguo, que es el que
cuelga la instancia.

**Ojo con el panel**: `mine` (y todo lo que no sea una consulta simple) no va al
endpoint `/baritone/mine` sino a `POST /chat`, que es otra puerta. Las dos
respetan la variable, pero si alguna vez se cuelga una instancia veras que ya no
responde ni `/health`: el pool HTTP son 4 hilos y cada peticion que espera al
hilo principal los ocupa 5 s, asi que a los cuatro el servidor deja de
atender cualquier peticion de esa instancia.

Verificado con las dos instancias minando a la vez: `#mine minecraft:oak_log` en
mc1 y mc2, las dos siguen con `/status` en `200`, Baritone crea el proceso de
minado en las dos (`Mine BlockOptionalMetaLookup{[BlockOptionalMeta{block=Block{
minecraft:oak_log}...}]`) y calcula rutas (`PathNode map size: 35825`, `Path
goes for 40.36 blocks`). `#cancel` las para. Sin la variable, el mismo `#mine`
deja cada instancia en `503` permanente.

Como se ve desde el panel cuando **no** lleva la variable:

| Endpoint | Instancia sana | Instancia colgada |
|---|---|---|
| `GET /puppeteer/health` | `200` en ~3 ms | `200` en ~3 ms |
| `GET /puppeteer/status` | `200` | `503` a los 5 s |

La fila se queda **amarilla** (el proceso vive, el estado no llega) y `log <n>`
muestra que el ultimo log del juego es el del propio `#mine`. La otra instancia
no se entera, asi que se puede seguir trabajando con ella mientras la colgada
hay que reiniciarla. `scripts/tui.ts` avisa en el feed antes de mandar `#mine` o
`#goto <bloque>` (los manda igualmente).

El precio del modo asincrono es una excepcion en el log del juego por cada orden
con bloque, que Baritone captura y de la que se recovers:

```
baritone.az: java.lang.IllegalStateException: BlockStateInterface must be
constructed on the main thread
```

Lo demas (`#goto x y z`, `#surface`, `#sethome`, `#cancel`, las consultas)
funciona con y sin la variable.

## Notas

- **Presupuesto**: 2G por instancia es lo que pide Minecraft de serie. Con 6
  instancias o mas, bajarlo (`-m 1500M`) o repartir nucleos: cada una corre su
  propio hilo de render.
- **Sin display** (servidor, SSH, CI): `--gl software` (o `auto`, que lo activa
  solo si no hay `DISPLAY`/`WAYLAND_DISPLAY`) pone `LIBGL_ALWAYS_SOFTWARE=1`.
- **Tokens**: por defecto las instancias van **sin token**, que es lo comodo
  para uso local. `--token T` si quieres `requireToken` con un valor fijo.
- El `checksums.txt` oficial de Baritone v1.14.0 no corresponde al jar que hay
  colgado en la release (se subio 18 s despues y con otro hash), asi que el
  script ancla el hash del binario real, que es el que el juego carga bien.
