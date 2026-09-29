# Pruebas

La logica de este mod esta partida en dos, y eso determina como se prueba cada parte:

| Parte | Depende de Minecraft | Como se prueba |
|---|---|---|
| Nucleo HTTP (`main`) | No | Prueba de humo real: se arranca el servidor de verdad y se le pegan peticiones de verdad. |
| Puente (`client`) | Si | No automatizado. Requiere el juego en marcha. |

La prueba de humo cubre todo el nucleo, incluida la garantia central del mod:
**que nada se ejecuta fuera del hilo principal**.

---

## 1. Como se ejecuta

`tests/PuenteSmokeTest.java` es un programa Java autonomo (sin JUnit, sin
red de pruebas) que:

1. construye un `MinecraftBridge` **falso** que solo registra en que hilo se
   le llamo,
2. levanta un `PuenteHttpServer` real en `127.0.0.1:25599`,
3. le manda 156 peticiones con `java.net.http.HttpClient`,
4. comprueba estado HTTP, codigo de error y contenido,
5. comprueba que el buffer acotado se comporta,
6. comprueba que los comandos de Baritone se traducen y salen por chat,
7. comprueba que el limitador de tasa corta,
8. apaga el servidor.

### Compilar y ejecutar (Windows / PowerShell)

```powershell
.\gradlew.bat build

$tmp   = "C:\Users\cerbe\AppData\Local\Temp\opencode"
$gson  = Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\com.google.code.gson\gson" -Recurse -Filter "gson-*.jar" | Where-Object Name -notmatch 'sources' | Select -First 1
$slf4j = Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\org.slf4j" -Recurse -Filter "*.jar" | Where-Object Name -notmatch 'sources' | Select -Expand FullName
$kotlin= Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\org.jetbrains.kotlin\kotlin-stdlib" -Recurse -Filter "*.jar" | Where-Object Name -notmatch 'sources' | Select -First 1
$loader= Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\net.fabricmc\fabric-loader" -Recurse -Filter "*.jar" | Where-Object Name -notmatch 'sources' | Select -First 1
$loomy = "$env:USERPROFILE\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft"
$cp = @(
  "build\classes\kotlin\main"
  $gson.FullName
  $loader.FullName
  "$loomy\minecraft-clientonly-deobf\26.3\minecraft-clientonly-deobf-26.3.jar"
  "$loomy\minecraft-common-deobf\26.3\minecraft-common-deobf-26.3.jar"
  $kotlin.FullName
) + $slf4j
$full = ($cp -join ';')

& "C:\Program Files\Java\jdk-25.0.2\bin\javac.exe" -nowarn -cp $full -d "$tmp\out" agents\tests\PuenteSmokeTest.java agents\tests\BaritoneCommandSignatures.java
& "C:\Program Files\Java\jdk-25.0.2\bin\java.exe"  -cp "$tmp\out;$full" PuenteSmokeTest
```

Sale con codigo de salida 1 si algo falla, asi que sirve directamente en CI.

> Requiere el puerto 25599 libre. Esta fuera del 25580 que usa el mod, para no
> chocar con una sesion de juego abierta.

### Fichero de copia

El fichero de este repositorio es la version canonica. Su nombre de clase
coincide con el del fichero (`PuenteSmokeTest`), que es lo que exige Java
para una clase publica; el comando de arriba invoca ese nombre.

Hubo antes una copia en `%TEMP%\opencode\SmokeTest.java` con el nombre corto
`SmokeTest`. Esa variante daba 156/156 igual, pero rompia el comando documentado
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

### Conexion (7)
- `{"address":"localhost:25565"}` devuelve `202`.
- Puerto 70000 devuelve `400 invalid_port`.
- `{"address":"[::1]:25566"}` devuelve `202` con `"[::1]:25566"` (formato sin ambiguedad).
- `{"address":"juego.mc"}` devuelve `202` con el puerto por defecto 25580.
- `{"host":..., "port":..., "name":...}` devuelve `202`.
- Sin destino devuelve `400 missing_host`.
- Puerto no numerico devuelve `400`.

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

`ClientBridge.connect()` hace `disconnectFromWorld(...)` y acto seguido
`ConnectScreen.startConnecting(...)` en el mismo tick. Es la secuencia que usa
el propio juego, pero **en transiciones de mundo hay estados intermedios**
(carga de mundo, `LocalPlayer` aun no creado, recursos descargados) en los que
llamar a `sendChat` o consultar `status` puede dar un resultado raro.

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
