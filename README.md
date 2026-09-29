# mc-puppeteer

Mod de Fabric para Minecraft 26.3 que abre un **servidor HTTP local en un hilo
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

## Documentacion

La documentacion vive en [`agents/`](agents/), en este orden:

| Fichero | Que contiene |
|---|---|
| [`00-research-dump.md`](agents/00-research-dump.md) | Analisis **previo** a escribir codigo: APIs de MC 26.3 verificadas, arquitectura propuesta, riesgos. |
| [`01-addendum-hallazgos.md`](agents/01-addendum-hallazgos.md) | Lo que se descubrio **al compilar**: `Minecraft` ya no tiene `screen`, `disconnect` cambio de firma, MC 26.3 trae anotaciones de nulabilidad, y demas. |
| [`02-implementacion.md`](agents/02-implementacion.md) | Documento de construccion: arquitectura, ficheros, garantias de rendimiento, seguridad, API completa, tabla de errores, y donde se desvia del plan. |
| [`03-testing.md`](agents/03-testing.md) | Como ejecutar la prueba de humo, que cubre, y que queda sin cubrir. |
| [`tests/PuppeteerSmokeTest.java`](agents/tests/PuppeteerSmokeTest.java) | 48 aserciones sobre el nucleo HTTP, ejecutables sin Minecraft. |

## Compilar

```bash
./gradlew build        # o .\gradlew.bat build en Windows
```

## Advertencia

El puerto HTTP por defecto escucha solo en `127.0.0.1` y exige un token. **No
cambies `host` a `0.0.0.0`**: quien alcance ese puerto podra ejecutar comandos y
enviar chat con tu cuenta de Minecraft, sin HTTPS ni ninguna otra proteccion.
