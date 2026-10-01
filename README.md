# mc-puppeteer

Mod de Fabric para Minecraft 1.21.5 que abre un **servidor HTTP local en un hilo
aparte** para controlar el cliente de Minecraft por HTTP: enviar chat y
comandos, leer el chat que llega, consultar el estado del cliente, y conectar o
desconectar de servidores.

La restriccion de diseno principal es que la API de Minecraft **nunca** se toca
desde un hilo ajeno al principal, para que la red no afecte a los FPS.

## API

Base `http://127.0.0.1:25580/puppeteer`, autenticacion
`Authorization: Bearer <token>` (el token se imprime en el log al arrancar).

| Metodo | Ruta | Que hace |
|---|---|---|
| GET | `/health` | Vivo. Sin token. |
| GET | `/status` | Mundo, jugador, FPS, dimension, pantalla actual. |
| GET | `/players` | Tab list con latencia. |
| GET | `/chat?limit=N` | Drena hasta N mensajes de chat pendientes. |
| GET | `/chat/history?limit=N` | Ultimos mensajes sin drenar. |
| POST | `/chat` | `{"message":"hola"}` |
| POST | `/command` | `{"command":"list"}` (sin barra) |
| POST | `/connect` | `{"address":"servidor.mc:25565"}` |
| POST | `/disconnect` | Salir al titulo. |

Configuracion en `config/mc-puppeteer.json`, creada sola la primera vez.

**Para integrar de verdad, mira [`05-api.md`](agents/05-api.md)**: tiene las
peticiones listas para copiar y pegar, los cuerpos exactos, la tabla completa de
errores y los ejemplos en PowerShell, Node y Python.

## Documentacion

La documentacion vive en [`agents/`](agents/), en este orden:

| Fichero | Que contiene |
|---|---|
| [`00-research-dump.md`](agents/00-research-dump.md) | Analisis **previo** a escribir codigo: con que nombres hay que llamar a Minecraft, arquitectura propuesta, riesgos. |
| [`01-addendum-hallazgos.md`](agents/01-addendum-hallazgos.md) | Lo que se descubrio **al compilar**: firmas que no salen de la documentacion, y que con 1.21.5 son al reves de como estaban en 26.3. |
| [`02-implementacion.md`](agents/02-implementacion.md) | Documento de construccion: arquitectura, ficheros, garantias de rendimiento, seguridad, API completa, tabla de errores, y donde se desvia del plan. |
| [`03-testing.md`](agents/03-testing.md) | Como ejecutar la prueba de humo, que cubre, y que queda sin cubrir. |
| [`05-api.md`](agents/05-api.md) | Referencia de la API con ejemplos listos para copiar y pegar, codigos de error y un bug conocido. |
| [`06-migracion-1.21.5.md`](agents/06-migracion-1.21.5.md) | La bajada de 26.3 a 1.21.5: versiones, cambios de build, tabla de firmas que cambiaron y como se verifico todo. |
| [`07-instancias-simultaneas.md`](agents/07-instancias-simultaneas.md) | Lanzar N clientes de Minecraft a la vez, cada uno con su puerto, config, directorio y Baritone. |
| [`tests/PuenteSmokeTest.java`](agents/tests/PuenteSmokeTest.java) | 188 aserciones sobre el nucleo HTTP, ejecutables sin Minecraft. |

## Compilar

Requiere **Java 21** (el de Minecraft 1.21.5).

```bash
./gradlew build        # o .\gradlew.bat build en Windows
```

## Varios clientes a la vez

Para levantar N clientes con el mod cada uno en su directorio, puerto y config
(Baritone incluido, sin tocar `~/.minecraft`):

```bash
bun   scripts/run-instances.ts -n 3
node scripts/run-instances.ts -n 3
```

Si el puerto 25580 esta ocupado, el siguiente cliente usa el 25581. Para
ver el estado de todos y mandar ordenes a la vez: `scripts/tui.ts`. Detalle en
[`07-instancias-simultaneas.md`](agents/07-instancias-simultaneas.md).

## Advertencia

El puerto HTTP por defecto escucha solo en `127.0.0.1` y exige un token. **No
cambies `host` a `0.0.0.0`**: quien alcance ese puerto podra ejecutar comandos y
enviar chat con tu cuenta de Minecraft, sin HTTPS ni ninguna otra proteccion.
