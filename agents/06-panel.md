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
navegador  ──same-origin──>  hub (25590)  ──servidor a servidor──>  Puente (25580, 25581…)
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
4. **El token se guarda, no se reparte.** `GET /api/instances` devuelve
   `hasToken: true/false` pero nunca el token. Lo inyecta el hub al reenviar, y
   la cabecera `Authorization` que llegue del navegador se ignora.
5. **Ninguna cabecera `Access-Control-Allow-*`.** El navegador no puede leer las
   respuestas desde otro origen.
6. **El token de cada instancia sobrevive a los recargos.** Si la pagina vuelve
   a guardar el mismo puerto sin token, se conserva el que habia, para no
   borrarlo sin querer.

## Rutas del hub

| Ruta | Que hace |
|---|---|
| `GET /` | El panel. |
| `GET /api/health` | Vivo, y cuantas instancias hay. |
| `GET /api/instances` | Instancias con su estado actual, sondeadas en paralelo. |
| `POST /api/instances` | Dar de alta `{name, host, port, token?}`. |
| `DELETE /api/instances/{id}` | Quitar del panel. **No cierra Minecraft.** |
| `* /api/instances/{id}/{ruta}` | Proxy a `http://127.0.0.1:{port}/puppeteer/{ruta}`. |

El proxy acepta cualquier ruta de la API de Puente, asi que `/status`,
`/chat`, `/command`, `/connect`, `/respawn`, `/profile`, `/baritone/mine` y el
resto funcionan sin tocar el hub. Si no hay nada escuchando en el puerto
responde `502 instance_offline`; si la instancia tarda, `504 instance_timeout`.

El registro se guarda en `dashboard-instances.json` junto al proyecto, escrito de
forma atomica (temporal + `move`) porque la pagina puede estar guardando en ese
momento.

## Lo que hace el panel

Por cada instancia, una tarjeta con:

- Punto de estado y pastilla: *apagada*, *en menú*, *en juego* o *muerta*.
- Jugador, mundo, dimensión, FPS, jugadores en el mundo y pantalla actual.
- Conectar, desconectar, reaparecer, leer estado y cambiar la identidad.
- Chat y comandos, con registro de lo que se ha enviado y lo que ha pasado.
- Baritone: minar por bloque y cantidad, seguir a un jugador, parar y un
  "Estado" que consulta `version`, `proc`, `eta` y `paused`.
- Inventario, dibujado como en el juego: 9 huecos de barra rapida, 27 de
  mochila, y las cuatro piezas de armadura mas la mano secundaria. La ranura
  seleccionada sale marcada, y los objetos concai dano muestran `actual/max`.

Refresca solo cada 2 segundos. El chat y el inventario solo se piden si la
instancia esta en linea y la casilla esta abierta, para no gastar peticiones en
lo que nadie esta mirando.

Todo el texto que viene del juego (nombres de jugadores, chat, nombres de
objetos) se escapa antes de meterlo en el DOM. El contenido de un mundo
multijugador no es de fiar y no deberia poder inyectar HTML en el panel.

## Pruebas

`agents/tests/DashboardSmokeTest.java` levanta un stub que imita a Puente y el
hub encima, y comprueba de punta a punta el proxy (metodo, cuerpo, query),
el sondeo de estado, la persistencia, el borrado y las defensas. 20
aserciones, sin necesitar Minecraft.

No cubre: que el hub se comporte bien con muchas instancias a la vez, ni
ninguna prueba de navegador (el JavaScript del panel no esta automatizado).
