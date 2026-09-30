# Pruebas

La logica de este mod esta partida en dos, y eso determina como se prueba cada parte:

| Parte | Depende de Minecraft | Como se prueba |
|---|---|---|
| Nucleo HTTP (`main`) | No | Prueba de humo real: se arranca el servidor de verdad y se le pegan peticiones de verdad. |
| Puente (`client`) | Si | No automatizado. Requiere el juego en marcha. |

La prueba de humo cubre todo el nucleo, incluida la garantia central del mod:
**que nada se ejecuta fuera del hilo principal**.

Hay dos bancos, y los dos se ejecutan con `java` directamente:

| Banco | Aserciones | Que necesita |
|---|---|---|
| `PuenteSmokeTest` | 195 | Nada, solo el JDK y el classpath del proyecto |
| `DashboardSmokeTest` | 35 | Nada, levanta un stub HTTP |

230 en total. Ojo con la cuenta: el numero mas alto de la etiqueta no es el
total. En `PuenteSmokeTest` las etiquetas llegan a `[156]`, pero hay 35
aserciones mas con sufijo de letra (`[26a]`, `[27b]`…), asi que son 195.

---

## 1. Como se ejecuta

`tests/PuenteSmokeTest.java` es un programa Java autonomo (sin JUnit, sin
red de pruebas) que:

1. construye un `MinecraftBridge` **falso** que solo registra en que hilo se
   le llamo,
2. levanta un `PuenteHttpServer` real en `127.0.0.1:25599`,
3. le manda 191 peticiones con `java.net.http.HttpClient`,
4. comprueba estado HTTP, codigo de error y contenido,
5. comprueba que el buffer acotado se comporta,
6. comprueba que los comandos de Baritone se traducen y salen por chat,
7. comprueba que el limitador de tasa corta,
8. apaga el servidor.

### Compilar y ejecutar (Linux / macOS)

El nucleo HTTP (`main`) **no toca ninguna clase de Minecraft**, asi que el
classpath minimo son las clases compiladas mas Gson, SLF4J y el stdlib de
Kotlin. No hacen falta los jars de Minecraft ni el de `fabric-loader`:

```bash
./gradlew build

cache="$HOME/.gradle/caches/modules-2/files-2.1"
jar() { find "$cache/$1" -name "$2" ! -name '*sources*' | sort -V | tail -1; }

cp="build/classes/kotlin/main"
cp="$cp:$(jar com.google.code.gson/gson 'gson-*.jar')"
cp="$cp:$(jar org.slf4j/slf4j-api 'slf4j-api-*.jar')"
cp="$cp:$(jar org.jetbrains.kotlin/kotlin-stdlib 'kotlin-stdlib-2*.jar')"

out="${TMPDIR:-/tmp}/puente-smoke-out"
mkdir -p "$out"
javac -nowarn -cp "$cp" -d "$out" \
    agents/tests/PuenteSmokeTest.java agents/tests/BaritoneCommandSignatures.java
java -cp "$out:$cp" PuenteSmokeTest
```

Sale con codigo de salida 1 si algo falla, asi que sirve directamente en CI.

Aparte, `tests/DashboardSmokeTest.java` prueba el panel de instancias (35
aserciones) con un stub que hace de Puente; no necesita el juego, solo su jar en
el classpath, de donde salen los sprites de los items.

### Compilar y ejecutar (Windows / PowerShell)

```powershell
.\gradlew.bat build

$cache = "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1"
$gson   = Get-ChildItem "$cache\com.google.code.gson\gson"  -Recurse -Filter "gson-*.jar"        | Where-Object Name -notmatch 'sources' | Select -First 1
$slf4j  = Get-ChildItem "$cache\org.slf4j\slf4j-api"        -Recurse -Filter "slf4j-api-*.jar"   | Where-Object Name -notmatch 'sources' | Select -First 1
$kotlin = Get-ChildItem "$cache\org.jetbrains.kotlin\kotlin-stdlib" -Recurse -Filter "*.jar"   | Where-Object Name -notmatch 'sources' | Select -First 1
$cp = @("build\classes\kotlin\main", "build\resources\main", $gson.FullName, $slf4j.FullName, $kotlin.FullName) -join ';'

$out = "$env:TEMP\puente-smoke-out"
New-Item -ItemType Directory -Force $out | Out-Null
javac -nowarn -cp $cp -d $out agents\tests\PuenteSmokeTest.java agents\tests\BaritoneCommandSignatures.java
java  -cp "$out;$cp" PuenteSmokeTest
```

Los comandos usan el `javac`/`java` que haya en el `PATH`, que es el JDK con el
que se lanzo Gradle (Java 21 para Minecraft 1.21.5).

> Requiere el puerto 25599 libre. Esta fuera del 25580 que usa el mod, para no
> chocar con una sesion de juego abierta.

### Banco del panel

`tests/DashboardSmokeTest.java` sigue la misma forma, pero en vez de un
`MinecraftBridge` falso levanta un **stub HTTP** que imita a Puente, y el hub del
panel encima. No necesita ni Minecraft ni el jar deobfuscado.

```powershell
javac -nowarn -cp $cp -d $out agents\tests\DashboardSmokeTest.java
java  -cp "$out;$cp" DashboardSmokeTest
```

Los dos bancos se pueden compilar y ejecutar seguidos. Los puertos son
efimeros, asi que no chocan ni entre si ni con una sesion de juego abierta.

### Fichero de copia

El fichero de este repositorio es la version canonica. Su nombre de clase
coincide con el del fichero (`PuenteSmokeTest`), que es lo que exige Java
para una clase publica; el comando de arriba invoca ese nombre.

Hubo antes una copia en `%TEMP%\opencode\SmokeTest.java` con el nombre corto
`SmokeTest`. Esa variante daba 191/191 igual, pero rompia el comando documentado
en cuanto se copiaba al repositorio, porque `javac` no acepta una clase
publica cuyo nombre no coincida con el del `.java`. Se renombro al integrarla.

---

## 2. Que cubre

### Configuracion (3)
- Config valida no produce problemas.
- Puerto fuera de rango detectado.
- `requireToken` sin token detectado.

### Ciclo de vida del servidor (2)
- Arranca y publica la direccion.
- Se detiene sin excepcion.

### Autenticacion (5)
- `/health` responde **sin** token.
- `/status` sin token devuelve `401`.
- El `401` incluye la cabecera `WWW-Authenticate: Bearer`.
- `/status` con token correcto devuelve `200` y el estado completo.
- Token incorrecto devuelve `401`.

### Lectura (2)
- `/players` devuelve el tab list.
- `/debug` responde.

### Envio de chat (4)
- `{"message":"hola desde http"}` devuelve `202` y el texto enviado.
- `{"message":"/list"}` devuelve `400 leading_slash` (**no** se envia como texto).
- Cuerpo sin `message` devuelve `400 missing_field`.
- `{"message":"   "}` devuelve `400 empty_message`.

### Envio de comando (3)
- `{"command":"list"}` devuelve `202` con `"sent":"list"`.
- `{"command":"/say hola"}` devuelve `202` y se normaliza a `"say hola"`.
- Cuerpo sin `command` devuelve `400`.

### Conexion (10)
- `{"address":"localhost:25565"}` devuelve `202`.
- Puerto 70000 devuelve `400 invalid_port`.
- `{"address":"[::1]:25566"}` devuelve `202` con `"[::1]:25566"` (formato sin ambiguedad).
- `{"address":"juego.mc"}` devuelve `202` con el puerto por defecto **25565**.
- `{"host":..., "port":..., "name":...}` devuelve `202`.
- Sin destino devuelve `400 missing_host`.
- Puerto no numerico devuelve `400`.
- Los tres caminos sin puerto explicito usan 25565, no 25580: `{"host":"servidor"}`,
  `{"address":"otro.servidor"}` y `{"address":"[::1]"}`. Antes caian en 25580, que es
  el puerto del HTTP; estas tres aserciones lo fijan para que no vuelva.
- La asercion 23 tambien fijaba 25580, es decir, **codificaba el bug**. Al
  corregirlo, la prueba fallo: senal de que la prueba vigilaba el valor
  equivocado, no el correcto.

### Inventario (12)
- Sin mundo -> `409 not_connected`.
- 9 huecos de barra rapida y 27 de mochila, siempre todos: un hueco vacio lleva
  `id: null` y `count: 0`, no se omite.
- `id` es la ruta del registro (`minecraft:diamond_pickaxe`) y `name` el texto
  traducido: se comprueban los dos por separado.
- El desgaste (`damage`/`maxDamage`) solo aparece en objetos que se estropean;
  en el resto es `null`, no `0`.
- La armadura viene indexada por pieza (`head`, `chest`, `legs`, `feet`), y una
  pieza puede estar vacia sin desaparecer del mapa.
- La mano secundaria lleva `index: -1` y queda fuera de la mochila.
- `filled` cuenta solo los huecos con algo, y se expone `selectedSlot`.
- `POST /inventory` -> `405`.

Lo que **no** cubren: que los ids y los nombres sean los de verdad. El fake
inventa `minecraft:diamond_pickaxe`; que `BuiltInRegistries.ITEM.getKey` devuelva
la ruta correcta solo se comprueba dentro del juego.

### Panel de instancias (29)
En `agents/tests/DashboardSmokeTest.java`, con un stub que hace de Puente:

- El panel se sirve como HTML y **sin** cabeceras CORS.
- El listado marca `online` con el estado real de cada instancia, y una instancia
  apagada sale con `error` en vez de romper el listado.
- El proxy reenvia `GET` y `POST` con su metodo, su cuerpo y su query string.
- Ni el sondeo ni el proxy mandan cabecera de autorizacion, y el alta de una
  instancia no acepta ni anuncia ningun token.
- Una instancia apagada da `502 instance_offline`; una que no existe, `400`.
- No se admite dar de alta un host que no sea loopback, ni un dominio (anti-SSRF).
- Una peticion con `Host: evil.com` da `403 bad_host` (DNS rebinding), y una sin
  `Host` tambien. Se comprueba con socket en crudo porque `java.net.http` no deja
  poner esa cabecera a mano, y ademas se verifica que una peticion normal si pasa,
  para que el test no se conforme con un 403 universal.
- El registro sobrevive a un reinicio del hub, y `DELETE` saca la instancia.
- Descubrir responde 200 con los puertos escaneados.
- El lanzador informa del rango permitido y de si el proyecto esta listo; lanzar
  fuera de rango o sin puerto se rechaza, y parar una instancia que el hub no
  arranco no hace nada.

#### Regresion del broadcast

`POST /api/broadcast/chat` devolvia `404 Endpoint desconocido: /puppeteerchat` en
las dos instancias. La cola de la ruta se montaba sin su barra: el hub hacia
`removePrefix("api/broadcast/")`, que quita tambien la `/` final, y `forward()`
concatena a pelo, asi que salia `/puppeteer` + `chat`.

El banco **no lo cazaba** porque el broadcast se probaba cuando ya solo quedaba la
instancia por defecto, que esta apagada. Da igual mandar `/puppeteer/chat` que
`/puppeteerchat`: las dos acaban en error de conexion y el test pasa igual de
verde. Por eso ahora la asercion va **con el stub vivo y antes del `DELETE`**,
comprobando la ruta que llego de verdad (`stubPath`):

```java
stubPath.set("(nunca)");
post(http, base2 + "/api/broadcast/chat", "{\"message\":\"hola\"}");
check("[21] el broadcast monta bien la ruta en la instancia viva",
    stubPath.get().equals("/puppeteer/chat"), ...);
```

La leccion general: un test de proxy que solo mira el codigo HTTP no distingue
una ruta bien montada de una mal montada si el destino no responde. Hay que
mirar **a donde fue la peticion**, no solo que fallo.

Un fallo que solo aparecio al ejecutarlo de verdad, no en el banco: con un
registro en un path relativo sin carpeta, `Files.createDirectories(file.parent)`
lanza NPE porque `parent` es `null`. El banco no lo cazaba porque usaba
`Files.createTempFile`, que si devuelve un path absoluto.

Lo que **no** cubren: el JavaScript del panel (no hay pruebas de navegador) ni
el comportamiento con muchas instancias simultaneas.

Lo del JavaScript se ha tapado a mano, no con pruebas: en esta maquina no hay ni
`node` ni navegador, asi que el `index.html` se reviso con un validador de
equilibrio de llaves que entiende comentarios, comillas, plantillas y regex
(`{`/`}`/`(`/`)` balanceados, 587 lineas de JS), y cruzando cada `data-act` y
cada `id` que el JS busca contra lo que el HTML define. Es un control
estructural, **no** una ejecucion: no caza un `const x = ;` ni un nombre de
propiedad mal escrito. Para eso hay que abrir el panel en un navegador.

### Identidad offline (10) y reaparicion (10)
- `GET /profile` devuelve nombre y UUID actuales.
- `POST /profile {"name":"Tester1"}` cambia el nombre y `GET` lo confirma.
- Acepta digitos y guion bajo (`Tester_2`).
- Nombre vacio o ausente -> `400 missing_name`.
- Con espacios, de 24 caracteres, o con salto de linea -> `400 invalid_player_name`.
- `DELETE /profile` -> `405`.

Las tres validaciones de formato **si** llegan al puente, porque el patron se
comprueba en `ClientBridge` (que es quien conoce `SharedConstants.MAX_PLAYER_NAME_LENGTH`)
y por tanto en el hilo principal. Las dos de `missing_name` no llegan: el
controlador las corta antes, por no depender de MC.

Lo que **no** cubren, y no se puede con este banco:
- Que la escritura sobre el campo `private final` funcione de verdad. No se
  puede instanciar `Minecraft` sin arrancar el juego; se verifico aparte
  contra el jar real que hay un unico campo de tipo `net.minecraft.client.User`,
  que `setAccessible` responde y que escribir un `final` no estatico con `Field.set`
  funciona en Java 21 (el runtime de 1.21.5).
- Que el UUID que calculamos sea el que espera el servidor. Verificado aparte
  contra `UUIDUtil.createOfflinePlayerUUID`, que coincide con
  `nameUUIDFromBytes("OfflinePlayer:" + nombre)`.
- Que al conectar de verdad el servidor acepte la identidad. Requiere juego.

Reaparicion (10): sin mundo -> `409 not_connected`; con el jugador vivo ->
`409 not_dead` y sin contar como reaparicion; `/status` expone `dead` en `false`
y en `true`; muerto -> `202`; tras reaparecer `dead` vuelve a `false`; repetir en
vida -> `409` sin contar como segundo; `GET /respawn` -> `405`.

Lo que **no** cubren: que el servidor acepte el paquete de reaparicion. El banco
comprueba el contrato HTTP y que se llame al puente, no que `LocalPlayer.respawn()`
llegue a hacer reaparecer de verdad. Eso necesita morir en juego.

### Desconexion (1)
- `POST /disconnect` devuelve `202`.

### Errores de transporte (9)
- Metodo incorrecto -> `405` con cabecera `Allow`.
- Ruta inexistente dentro del base path -> `404`.
- Ruta fuera del base path -> `404` (no interfiere con otros handlers).
- `limit` fuera de rango -> `400 invalid_query`.
- `limit` no numerico -> `400`.
- JSON malformado -> `400 invalid_json`.
- Body vacio -> `400 empty_body`.
- Body de 40 KB -> `413 body_too_large` (el tope por defecto son 16 KB).
- `OPTIONS` (preflight CORS) -> `204`.

### Buffer de chat (7)
- Buffer de capacidad 4 descarta lo que exceda.
- Conserva los 4 mas recientes.
- Contabiliza los descartes.
- `snapshot` **no** drena.
- `drain(N)` consume exactamente N.
- `drain` de mas de lo disponible devuelve lo que hay.
- `drain` sobre buffer vacio devuelve lista vacia.

### Baritone (76)
Baritone se controla por chat con prefijo `#`, no por API HTTP. Lo que se
comprueba es que el bridge lo envie por `sendChat` y **nunca** por
`sendCommand` (que va al servidor y lo rechaza).

- El indice `GET /baritone` responde `200` y expone los alias.
- Consultas: `version`, `proc`, `eta`, `modified`, `paused`, `wp`, `gc` se
  traducen a su comando. `wp` emite `#waypoints`, no `#wp`.
- Consultas con parametro: `help?q=mine`, `find?block=diamond_ore`.
- Traduccion de 21 acciones POST, con las 4 formas de `goto`.
- `cleararea` se traduce a `#sel cleararea N`, que es como lo registra Baritone
  (es subcomando de `sel`, no un comando propio).
- `stop` se traduce a `#cancel`; `stop?force` a `#forcecancel`, que es distinto.
- `top` se traduce a `#surface`; `home` y `sethome` a sus nombres canonicos.
- Rechazos: coordenadas parciales -> `invalid_goal`, `axis` sin `y` ->
  `missing_field`, `axis` fuera de rango -> `invalid_field`, `thisway` sin
  distancia -> `missing_field`, `mine` sin bloque -> `missing_field`,
  `schematica` -> `404` (esta comentado en Baritone), ruta inexistente -> `404`,
  metodo incorrecto -> `405`.
- Inyeccion bloqueada en 4 casos (`"diamond; op Alex"`, `"Alex\n#op"`,
  `"../../etc/passwd"`, `"base.schematic && rm -rf /"`).
- Los N comandos salieron por `sendChat`, ninguno por `sendCommand`.
- Todos los mensajes llevan prefijo `#`.

#### Sincronizacion con el codigo de Baritone

`BaritoneCommandSignatures.java` guarda los nombres y alias reales, extraidos de
las firmas del source. La prueba los contrasta con `BaritoneTranslator.COMMANDS`:

- El registro cubre todos los comandos, sin faltantes.
- El registro no inventa comandos inexistentes.
- `schematica` y `cleararea` no resuelven a nada.
- 15 alias resuelven al nombre canonico correcto (`stop`->`cancel`, `top`->
  `surface`, `wp`->`waypoints`, `p`->`pause`, `s`->`sel`...).
- Los 13 comandos que exigen argumentos no se pueden enviar vacios.
- Los que no los exigen, si.
- Clasificacion: `readOnly`, `maintenance` y `noArg` aceptan lo suyo y
  rechazan lo demas con el codigo correcto.

Esta comprobacion se valido a proposito: renombrando `elytra` a algo
inexistente, la prueba falla en ambos sentidos (`faltan: [elytra]`,
`sobran: [elytraFALSO]`). Un test que siempre pasa no serviria de nada.

### Limite de tasa (1)
- Tras superar 120 peticiones en la ventana, aparecen `429`.

### Hilos (2)
- El puente se invoco **siempre** en el hilo principal simulado (si no, la
  prueba falla desde dentro del propio callback).
- El numero de llamadas al puente es exactamente el esperado, lo que tambien
  detecta invocaciones duplicadas o perdidas.

---

## 3. Lo que NO cubre, y por que

| Hueco | Por que |
|---|---|
| `ClientBridge` (envio real de chat/comando/conexion) | Requiere un cliente de Minecraft conectado a un servidor. Solo se valida en compilacion y en juego. |
| Que Baritone este realmente escuchando | La prueba comprueba que se llama a `sendChat`, no que Baritone intercepte el mensaje. Eso solo se ve con el mod instalado. |
| Que los comandos generados los entienda Baritone | Se contratan los **nombres** contra el source, pero no la semantica de cada argumento. Un `goto x y z` mal construido pasaria el test. |
| `ChatCapture` (eventos de Fabric) | Igual: los eventos solo se disparan con un mundo real cargado. |
| Comportamiento con el juego en pausa, sin conexion, o en medio de una transicion de mundo | Es el escenario mas delicado de todos (ver abajo). |
| Medicion de impacto en FPS | No medido. Ver `02-implementacion.md` §5. |
| HTTPS / lista de IPs permitidas | No implementado a proposito. Ver `02-implementacion.md` §6. |

### El hueco que mas me preocupa

`ClientBridge.connect()` cierra la sesion actual (`leaveWorld`) y acto seguido
llama a `ConnectScreen.startConnecting(...)` en el mismo tick. Es la secuencia
que usa el propio juego, pero **en transiciones de mundo hay estados
intermedios** (carga de mundo, `LocalPlayer` aun no creado, recursos
descargados) en los que llamar a `sendChat` o consultar `status` puede dar un
resultado raro.

No es un fallo conocido, es una zona sin verificar. Lo primero que haria falta
es un bot de pruebas que:

1. lance el cliente,
2. se conecte a un servidor local de prueba,
3. mande chat y comandos en bucle,
4. fuerce reconexiones y desconexiones repetidas,
5. compruebe que ninguna peticion devuelve `500` ni cuelga el juego.

Eso es trabajo de integracion, no de unit tests, y es el siguiente paso natural.

---

## 4. Por que no esta en `src/test`

Es una decision consciente, no un descuido:

- `src/test` no existe todavia en el build y anadir JUnit cambia
  `build.gradle.kts`.
- La prueba se ejecuta **fuera** de Minecraft, contra las clases compiladas
  directamente. Integrarla en Gradle exigiria exponer el classpath de
  `main` + Gson + SLF4J + el `fabric-loader` de forma estable, que es justo lo
  que `main` ya resuelve por su cuenta al no depender de clases de cliente.
- Mantenerla aqui, junto a la documentacion, deja claro que cubre el nucleo y
  no el juego.

Si se quiere integrar al build, el camino es moverla a `src/test/kotlin` como
tests JUnit y anadir `testImplementation` de JUnit 5. El nucleo es
testeable tal cual: no tiene dependencias de Minecraft.
