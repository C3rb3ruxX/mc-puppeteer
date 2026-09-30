# Panel de control de instancias

Sustituye a los `curl.exe` de la terminal. Es una pagina web que muestra todas
las instancias de Puente y las controla desde un solo sitio.

```bash
./gradlew runDashboard
```

Y se abre en `http://127.0.0.1:25590`. Se puede cambiar de puerto:

```bash
./gradlew runDashboard --args="--port 25590"
```

No necesita Minecraft: el panel se levanta con el solo hecho de compilar el
proyecto, asi que se puede usar para ver que instancias hay aunque no haya
ninguna arrancada.

Lo unico que si hace falta para poder **arrancar** clientes desde el panel es el
volcado del classpath. Sin el, el panel funciona igual, pero `GET /api/launcher`
devuelve `ready: false` y el boton de arrancar se queda deshabilitado:

```bash
./gradlew build
./gradlew -I scripts/gradle-run-config.init.gradle dumpRunConfig
```

## Por que hay un hub y no CORS

La idea inicial era servir un HTML suelto en disco y que el mod respondiera con
`Access-Control-Allow-Origin`. **Se descarto a proposito.**

La API de Puente va **sin autenticacion por defecto** y puede mandar comandos al
juego, cambiar la identidad del jugador y conectarse a servidores. Con CORS
permisivo, cualquier pagina que se abriera en ese navegador podria hacer
`fetch("http://127.0.0.1:25580/puppeteer/command", ...)` y manejar el bot. Los
navegadores bloquean esas peticiones cross-origin precisamente para evitar el
ataque, asi que activar CORS las desactivaria.

Con la arquitectura actual:

```
navegador  â”€â”€same-originâ”€â”€>  hub (25590)  â”€â”€servidor a servidorâ”€â”€>  Puente (25580, 25581â€¦)
```

El navegador solo habla con el hub, y el hub habla con las instancias por red
local, donde el navegador no participa. **El mod no gana ni una linea de codigo
de red nueva**: no toca `PuenteHttpServer` ni su configuracion.

## Defensas

El hub es un panel de control sin credenciales, asi que esta tratado como lo que
es: una superficie de control. Lo que lo hace aceptable en local:

1. **Solo se enlaza a loopback.** `DashboardServer.start()` lanza excepcion si se
   le pasa cualquier otro host. No hay bandera para desactivarlo.
2. **Se valida la cabecera `Host`.** Es lo que frena el *DNS rebinding*: un sitio
   atacante puede hacer que su dominio resuelva a `127.0.0.1`, y su `fetch`
   llevaria `Host: evil.com`. Sin esta comprobacion, ese sitio podria controlar
    el panel entero, porque no hay token que le pidiera. Solo se acepta que
    `Host` sea loopback, con el puerto realmente enlazado.
3. **Anti-SSRF: solo se hace proxy a loopback.** No se puede usar el hub para
   alcanzar la red interna. Se comprueba en dos sitios: al dar de alta la
   instancia y otra vez justo antes de reenviar, por si el registro se hubiera
   manipulado.
4. **Ninguna cabecera `Access-Control-Allow-*`.** El navegador no puede leer las
   respuestas desde otro origen.

Lo que protege esto es el loopback, no una credencial: el hub escucha solo en
`127.0.0.1` y las instancias tambien. El `requireToken` del mod sigue existiendo
para quien exponga Puente a otra red, pero el panel no lo usa ni lo pide.


## Rutas del hub

| Ruta | Que hace |
|---|---|
| `GET /` | El panel. |
| `GET /api/health` | Vivo, y cuantas instancias hay. |
| `GET /assets/item/{item}` | El sprite de un item, leido del jar del cliente. |
| `GET /api/instances` | Instancias con su estado actual, sondeadas en paralelo. |
| `POST /api/instances` | Dar de alta `{name, host, port}`. Sin token. |
| `DELETE /api/instances/{id}` | Quitar del panel. **No cierra Minecraft.** |
| `POST /api/discover` | Sondea el rango de puertos y da de alta lo que encuentre. |
| `* /api/broadcast/{ruta}` | La misma peticion a todas las instancias, en paralelo. |
| `POST /api/launch` | Arranca un cliente de desarrollo en `{port}`. |
| `POST /api/stop` | Para el cliente que **el hub** arranco en `{port}`. |
| `GET /api/launcher` | Si se puede arrancar, el rango permitido y las que viven. |
| `* /api/instances/{id}/{ruta}` | Proxy a `http://127.0.0.1:{port}/puppeteer/{ruta}`. |

El broadcast reenvia el metodo, la ruta, la query y el cuerpo tal cual, asi que
vale para lo que sea de la API: `POST /api/broadcast/chat` con su cuerpo,
`GET /api/broadcast/status`, `GET /api/broadcast/chat/history?limit=15`.

En `GET /api/instances`, cada instancia lleva tambien `busy`: `true` es que
contesta a `/health` pero no a `/status`, o sea viva con el hilo principal
ocupado (ver *Estado ocupada*, mas abajo). Sin ese dato, una instancia
ocupada salia "apagada".

El proxy acepta cualquier ruta de la API de Puente, asi que `/status`,
`/chat`, `/command`, `/connect`, `/respawn`, `/profile`, `/baritone/mine` y el
resto funcionan sin tocar el hub. Si no hay nada escuchando en el puerto
responde `502 instance_offline`; si la instancia tarda, `504 instance_timeout`.

El registro se guarda en `dashboard-instances.json` junto al proyecto, escrito de
forma atomica (temporal + `move`) porque la pagina puede estar guardando en ese
momento.

## Los iconos del inventario

Cada ranura muestra el sprite del item, no su nombre. Salen de
`GET /assets/item/{item}` y de ahi **del jar del cliente**, que ya esta en el
classpath del hub: no hay carpeta de PNG que mantener ni peticion a un CDN de
terceros, que ademas se enteraria de lo que hay en el inventario.

El nombre del item (`minecraft:diamond_ore`) no suele ser el nombre del fichero
(`textures/block/diamond_ore.png`), asi que se prueban las dos carpetas. No es un
adivino: el atlas de 1.21.5, en `assets/minecraft/atlases/blocks.json`, declara
sus fuentes como directorios, o sea que todo lo que hay en `textures/item/` es un
sprite llamado `<nombre>` y lo mismo en `textures/block/`. Los bloques de textura
plana dan en el segundo, las herramientas y la comida en el primero.

Lo que **no** sale es el item cuyo sprite se compone de varias texturas
(`crafting_table`, `furnace`, `chest`): el sprite no se llama como el item. Esos
dan `404` y la ranura se queda con el nombre corto, igual que antes. Sacarlos
bien haria falta el atlas ya montado, o sea, dentro del juego.

El nombre se valida con `^[a-z0-9_]+(/[a-z0-9_]+)*$` antes de tocar el classpath.
Sin eso, `/assets/item/../../build.gradle.kts` seria un lector de ficheros con
salida a Internet. El banco comprueba que un `..` no sale de `assets/`.

## Arrancar instancias desde el panel

`POST /api/launch` no invoca Gradle: levanta una JVM por instancia con el
classpath que **ya calculo Gradle**. Ese classpath se lee de
`scripts/.run-config.json`, que es el volcado que produce la tarea
`dumpRunConfig` de la rama 1.21.5:

```bash
./gradlew build
./gradlew -I scripts/gradle-run-config.init.gradle dumpRunConfig
```

`run-instances.ts` y la TUI usan ese mismo volcado, asi que el panel y ellos
arrancan el cliente exactamente igual. `dumpRunConfig` escribe `mainClass`, los
`-Dfabric.dli.*`, los argumentos de programa y 189 entradas de classpath.

### Por que no se usa `build/loom-cache/argFiles/runClient`

Porque es un fichero **caducado**. Lo escribe loom solo cuando corre la tarea
del cliente, asi que tras un `build` a secas se queda con el classpath de la
version anterior. En este caso arranco 1.21.5 con 189 entradas de Fabric API
`0.161.0+26.3` y el cliente moria con `Incompatible mods found!` antes de abrir
la ventana. El volcado, en cambio, lo pide Gradle en el momento.

El classpath va a `scripts/.cache/classpath.txt` y se pasa con `@fichero`:
Windows no aguanta 189 entradas en la linea de comandos.

### Donde queda el directorio de juego

Es la propia carpeta de la instancia, `run-instances/p<port>`, sin ningun `run`
en medio. Fabric deduce el directorio de juego del directorio de trabajo del
proceso, y el panel lo pone ahi. Alli aparecen `mods/`, `logs/` y `config/`, y
por eso la config del mod va en `run-instances/p<port>/config/mc-puppeteer.json`.

Cada `launch` siembra antes de arrancar:

- Los mods sueltos de `run/mods` (Baritone), que si no solo llegan por classpath
  en la instancia principal.
- La config con su puerto, `requireToken: false` y `authToken` vacio. Asi el
  panel no pide ni guarda credenciales, y una instancia que antes si las exigia
  deja de pedirlas al volver a arrancarse desde aqui.

### El mod que hace falta es la variante *api*

Baritone no se controla por API: el mod le manda un mensaje de chat con prefijo
`#` y Baritone contesta por el mismo chat. Ese reparto lo hace el mixin
`MixinClientPlayNetHandler`, que **solo esta en la variante *api***. Con la
*standalone* el mod no puede hablar con el.

En 1.21.5 no hay release oficial de Baritone (el repo upstream llega a
`v1.20.0`, que es de MC 1.20). El jar que hay en `run/mods` es
`baritone-api-fabric-1.21.5.jar` de `smorbes/baritone` (mod id `baritone-meteor`,
`depends.minecraft: 1.21.5`), con la misma API de siempre. Si se cambia de
proyecto o de version hay que volver a elegir un jar, porque la
`standalone` de 1.20 no carga en 1.21.5 y el juego aborta.

Como el jar no esta en ningun repositorio de Maven y su nombre no lleva
version dentro, la tarea `ensureBaritone` lo deja siempre en su sitio antes de
cualquier prueba:

```powershell
.\gradlew.bat ensureBaritone
```

Se ejecuta sola con `build` y con las de prueba. Si el jar no esta o no cuadra el
SHA-256, lo borra y lo vuelve a bajar; si ya esta bien, no toca nada. El checksum
va en el propio codigo (`BARITONE_SHA256`), asi que cambiar de version es cambiar
esa constante, no buscar el fichero a mano. Comprobado tanto con el jar ya
presente como borrandolo y dejandolo que lo descargue.

### `Lo que contesta el juego`

Este bloque no es cosmetico. `/baritone/mine` devuelve `202 ok` en cuanto el
mensaje sale del navegador: eso significa que el texto llego al cliente, **no**
que Baritone lo entendiera. Y como Baritone contesta en el chat local del juego,
sin salir por la red, ninguna ruta `/chat` lo recogia. Una orden rechazada
dejaba un panel en verde y ni una pista de que hubiera pasado.

Por eso el panel tiene su propio boton, que lee `GET /chat/screen?limit=N`, y lo
rele solo despues de cada accion de Baritone. Lo que sale ahi es lo que veria
una persona, con sus errores: `#mine 32 acacia_log` funciona y
`#mine acacia_block 32` responde `Error at argument #2:
Expected ForBlockOptionalMeta`, porque Baritone espera la cantidad antes del
bloque y `acacia_block` tampoco es un bloque valido en 1.21.5 (el tronco es
`acacia_log`). Los dos fallos juntos son el motivo de que el ejemplo de un fallo
no valga para distinguir "orden mal escrita" de "bloque que no existe".

No es un mixin nuevo. El modulo lee por reflexion la lista de la clase
`ChatComponent` del vanilla, que ya existe en el juego, y por eso no se toca
`baritone` en el classpath ni se rompe con la primera actualizacion.

### limites

- Solo puertos de `25580` a `25599`, comprobados en el servidor. El navegador no
  elige ni comando, ni ruta, ni PID.
- `POST /api/stop` solo para lo que el hub arranco. Parar un puerto que el hub no
  toco responde `{"stopped": false}` y no hace nada.
- `GET /api/launcher` dice `ready: false` si falta el volcado, y el boton de
  arrancar se queda deshabilitado.

## Lo que hace el panel

Por cada instancia, una tarjeta con:

- Punto de estado y pastilla: *apagada*, *en menÃº*, *en juego* o *muerta*.
- Jugador, mundo, dimensiÃ³n, FPS, jugadores en el mundo y pantalla actual.
- Conectar, desconectar, reaparecer, leer estado y cambiar la identidad.
- Chat y comandos, con registro de lo que se ha enviado y lo que ha pasado.
- Baritone: minar por bloque y cantidad, seguir a un jugador, parar y un
  "Estado" que consulta `version`, `proc`, `eta` y `paused`.
- "Lo que contesta el juego": el chat en pantalla del cliente, que es donde
  Baritone responde. Se relee solo tras cada orden, para que un rechazo se vea
  al momento y no un minuto mas tarde.
- Inventario, dibujado como en el juego: 9 huecos de barra rapida, 27 de
  mochila, y las cuatro piezas de armadura mas la mano secundaria. La ranura
  seleccionada sale marcada, y los objetos concai dano muestran `actual/max`.
- Parar, si la arranco el panel, y quitarla del panel.

Encima de las tarjetas hay cuatro fichas de resumen (cuantas instancias hay,
cuantas en linea, cuantas en juego y el FPS medio) y un aviso flotante con el
resultado de cada accion, para no tener que ir a mirar el registro. La tarjeta
toma un color de acento segun su estado: verde en juego, ambar en el menu, rojo
muerta, gris apagada. El FPS lleva una barrita, porque un numero suelto no dice
si va bien o si va justo.

### Acciones a varias

La barra de arriba manda la misma orden a mas de una instancia, y son las acciones
que mas fallaban. Antes eran dos campos: la ruta y el JSON del cuerpo, escritos a
mano. Ahi un error de dedo sale como un `400` de la instancia, no como "has
escrito mal el formulario", y con seis instancias a la vez es imposible ver cual
fallo.

Ahora hay un desplegable con el catalogo de acciones (`say`, `cmd`, `connect`,
`disconnect`, `respawn`, `profile`, `baritone`, `mine`, `find`, `bstop`, `status`,
`health`, `players`, `history`, `screen`), un unico campo para el argumento, y
otro desplegable para el destino: todas, o las marcadas en la casilla de cada
tarjeta. El cuerpo lo arma el panel, que es quien sabe que `/chat` espera
`{"message":...}` y que Baritone solo lee ordenes con `#` delante: escribirlo a
mano era inventarse la API en cada pulsacion.

- A "todas" va en una peticion a `POST /api/broadcast/{ruta}`, que el hub reparte
  en paralelo y devuelve un resultado por instancia. A "las seleccionadas" son
  varias peticiones al proxy, en paralelo desde el navegador.
- El resultado de cada una se escribe en **su** tarjeta, no solo en el aviso
  flotante: cuando se manda a diez, la unica manera de saber cual fallo es
  leerlas una a una.
- La orden entra igual en las instancias con el hilo principal ocupado. Por eso
  el reparto no las excluye, y el arreglo de Baritone va fuera del hilo
  principal (ver `PUPPETEER_BARITONE_ASYNC` en `agents/05-api.md`).

### Estado *ocupada*

Una instancia viva con el hilo principal ocupado sale en ambar y marcada como
ocupada, no apagada. `/status` entra en el hilo del juego, asi que no contesta
cuando el juego esta ocupado (Baritone minando, por ejemplo: el mod devuelve
`503 main_thread_timeout`), pero `/health` si, porque no toca ese hilo. Si
`/status` falla y `/health` contesta, el proceso esta vivo y hay que esperar, no
reiniciar. Confundir las dos cosas era la razon de que una instancia trabajando
apareciera como apagada y se pulsara "Arrancar" encima.

Refresca solo cada 2 segundos. El chat y el inventario solo se piden si la
instancia esta en linea y la casilla esta abierta, para no gastar peticiones en
lo que nadie esta mirando.

Todo el texto que viene del juego (nombres de jugadores, chat, nombres de
objetos) se escapa antes de meterlo en el DOM. El contenido de un mundo
multijugador no es de fiar y no deberia poder inyectar HTML en el panel.

## Pruebas

`agents/tests/DashboardSmokeTest.java` levanta un stub que imita a Puente y el
hub encima, y comprueba de punta a punta el proxy (metodo, cuerpo, query),
el sondeo de estado, la persistencia, el borrado, el lanzador, los sprites de los
items y las defensas. 40 aserciones, sin necesitar Minecraft (el jar del cliente
si tiene que estar en el classpath, que es de donde salen los sprites).

Tres de esas son las que importan para las acciones a varias, y estan al final
porque necesitan mas de una instancia viva montada a la vez: `[35]` el reparto
llega a las tres vivas (y ademas reporta la apagada, sin saltarsela), `[36]` cada
una trae su propio resultado y el resumen cuadra, y `[37]`-`[38]` la orden entra
igual en la instancia con el hilo principal ocupado y esa instancia sale
marcada como ocupada, no apagada. Con una sola instancia viva, todos esos fallos
pasan el test: el error sale igual de "ok" cuando la ruta va mal montada.

La `[39]` es la que mas ha valido: el desplegable de acciones del panel se
comprueba contra las rutas de verdad de `PuenteHttpServer.kt`, leyendo del codigo
si cada ruta acepta GET, POST o los dos. Lo pilla todo: una accion con el metodo
al reves (`/baritone/find` es GET con `?block=`, no POST con cuerpo) o con una
ruta que ya no existe. Antes eso se descubre cuando el bot no hace nada, y no hay
forma de saber si fue la orden o la instancia.

No cubre: ninguna prueba de navegador. El JavaScript se valida parseando el bloque
`<script>` entero con esbuild, que detecta errores de sintaxis reales pero no lo
ejecuta: que el panel se comporte bien al escribir en un campo, eso se ha probado
a mano.

Lo que si se ha probado a mano, con clientes de verdad:

- Dos instancias de 1.21.5 a la vez, las dos en linea y con el mod escuchando en
  su puerto.
- Descubrimiento con estado real: version de Minecraft, mundo, dimension y FPS.
- Que Baritone recibe los comandos: el log del cliente enseÃ±a
  `[CHAT] [Baritone] > version` y su respuesta.
- Que `GET /chat/screen` devuelve la respuesta de Baritone, incluido su
  `Error at argument #2` al pedir un bloque inexistente en 1.21.5.
- Que el reparto a varias llega a todas las vivas y no solo a la primera, y que
  una instancia con el hilo principal ocupado se marca ocupada, no apagada.
- Parar las dos y comprobar que los puertos quedan libres.
- Que lanzar fuera de rango da `400`, y que parar algo que el hub no arranco no
  hace nada.
- Que los sprites salen del jar: `GET /assets/item/diamond_sword` devuelve un PNG
  de 16x16 de verdad, y `..` no sale de `assets/`.
