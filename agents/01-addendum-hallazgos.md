# Addendum — hallazgos posteriores a la primera compilación

> Complemento de `00-research-dump.md`. Recoge todo lo que se descubrió
> **al compilar**, es decir, lo que el `javap` inicial no dejó ver porque son
> detalles de nulabilidad o de ubicación de estado.
>
> **Estado tras migrar a 1.21.5** (rama `migrate/1.21.5`): estos hallazgos se
> hicieron compilando sobre Minecraft 26.x, y los puntos 1 a 5 **son al revés en
> 1.21.5**, que es la razón principal de que la migración no haya sido cambiar
> una versión y ya. Cada uno lleva debajo su estado real. Los puntos 6 a 9
> (prueba de humo, Kotlin, mixins, `environment`) no dependen de la versión y
> siguen vigentes. El resumen completo está en `06-migracion-1.21.5.md`.

---

## 1. `Minecraft` ya no tiene campo `screen` (y tampoco getter)

> **En 1.21.5: no aplica.** `Minecraft` vuelve a tener `public Screen screen;` y
> `setScreen(Screen)`. No existen ni `gui.screen()` ni `setScreenAndShow`.

El dump original asumía `mc.screen`. Al compilar con 26.x falló. Verificado con
`javap -p` sobre el **campo y los metodos privados**:

```
private net.minecraft.client.Minecraft instance;
...
public final net.minecraft.client.gui.Gui gui;
```

No existe ningun campo ni ningun `getScreen()`/`screen()` que devuelva `Screen`
en el `Minecraft` de 26.x. En 26.3 ese estado se movio a la clase `Gui`:

```java
// net.minecraft.client.gui.Gui
public net.minecraft.client.gui.screens.Screen screen();   // getter
public void setScreen(net.minecraft.client.gui.screens.Screen);
public net.minecraft.client.gui.screens.Overlay overlay();
public boolean canInterruptScreen();
```

`Minecraft.setScreenAndShow(Screen)` delega aqui. Por tanto:

| Necesidad | API correcta en 26.3 |
|---|---|
| Leer la pantalla actual | `mc.gui.screen()` |
| Ver la superposicion | `mc.gui.overlay()` |
| Cambiar de pantalla | `mc.gui.setScreen(...)` o `mc.setScreenAndShow(...)` |

**Consecuencia para el diseno:** `ConnectScreen.startConnecting` exige la pantalla
actual como `parent`, asi que hay que resolver `mc.gui.screen() ?: TitleScreen()`.
En 1.21.5 se resuelve con `mc.screen ?: TitleScreen()`, porque `Minecraft.screen`
vuelve a ser un campo publico (anotado como nulable, asi que la elvision `?:` es
igualmente necesaria).

## 2. `disconnect` ya no acepta un `Component`

> **En 1.21.5: no aplica, pero con una trampa.** No existe ni `disconnectFromWorld`
> ni un `disconnect` que acepte un `Component`. Los metodos reales son
> `disconnect()`, `disconnect(Screen)` y `disconnect(Screen, boolean)`, y el
> "salir al titulo" hay que componerlo a mano: `leaveWorld()` en `ClientBridge`.

Fallo de compilacion con 26.x: `Minecraft.disconnect(Screen, boolean)` y
`disconnect(Screen, boolean, boolean)`. La variante que recibe texto de motivo es
otra:

```java
public void disconnectFromWorld(net.minecraft.network.chat.Component);
```

Desensamblando con `javap -c`, `disconnectFromWorld` es exactamente el
"salir al titulo" de vanilla:

```
level.disconnect(component)
  -> isLocalServer() ? disconnectWithSavingScreen() : disconnectWithProgressScreen()
  -> new TitleScreen()
  -> setScreen(...)
```

Es el metodo correcto para `POST /disconnect` y para cerrar la sesion antes de
conectar a otro servidor. `disconnect(Screen, boolean)` es de bajo nivel: exige
que le pases tu propia pantalla de destino.

En 1.21.5, `Minecraft.disconnect()` sin argumentos equivale a
`disconnect(new ProgressScreen(true), false)`: cierra la conexion y desmonta el
mundo (esperando, si es integrado, a que el servidor termine de guardar), pero
deja una pantalla de progreso. La secuencia equivalente al boton "Desconectar" del
menu de pausa, y la que usa este mod, es:

```kotlin
mc.getConnection()?.connection?.disconnect(Component.translatable("menu.quitting"))
mc.disconnect()
mc.setScreen(TitleScreen())
```

## 3. `ResourceKey.location()` -> `identifier()`

> **En 1.21.5: no aplica.** Se llama `location()`, no `identifier()`. Con
> mappings de Mojang el tipo de retorno es `ResourceLocation`, no `Identifier`.

En 26.x:
```java
public net.minecraft.resources.Identifier identifier();
public net.minecraft.resources.Identifier registry();
```

Afecta a `level.dimension().location()` en el estado.

## 4. `GameProfile` es un `record` en authlib 10

> **En 1.21.5: no aplica.** MC 1.21.5 usa authlib 6.x, donde `GameProfile` sigue
> siendo una clase normal con `getName()` / `getId()`. Kotlin los expone como
> propiedades sinteticas, asi que el codigo usa `profile.name` y `profile.id`.

En 26.x (authlib 10):

```java
public final class GameProfile extends java.lang.Record {
  public java.util.UUID id();
  public java.lang.String name();
  public PropertyMap properties();
}
```

Ya **no** existen `getId()` / `getName()`. Kotlin tampoco los expone como
propiedad sintetica (solo sintetiza propiedades para getters `getX()`), asi que
hay que llamar a `profile.id()` y `profile.name()` de forma explicita.

Igual aplica a los records del propio Minecraft, por ejemplo
`ServerStatus$Players.online()` y `.max()`.

## 5. MC 26.3 trae anotaciones de nulabilidad

> **En 1.21.5: no aplica.** Los jars de 1.21.5 llegan a Kotlin como tipos de
> plataforma, asi que `mc.screen`, `mc.player`, `mc.level` y demas admiten `?:` o
> `!!` sin problema, y `ConnectScreen.startConnecting` acepta `null` en el
> `TransferState` final. Es el comportamiento de siempre en 1.21.x.

En 26.x, Kotlin resolvio varios parametros de MC como **no nulos**, no como tipos
de plataforma. Casos reales que hubo que corregir:

- `ConnectScreen.startConnecting(Screen, ...)` — `Screen` no nulo.
- `Minecraft.getInstance()` — no nulo (`instance` se asigna en el propio
  constructor, antes de que Fabric invoque los entrypoints de cliente, asi que
  no hace falta ninguna guarda).
- `PlayerInfo.getProfile()` — no nulo.

Consecuencia general: **no se puede pasar `null` a un metodo de MC esperando que
funcione**. Hay que resolver el valor o lanzar excepcion.

## 6. Correcciones que salio la prueba de humo

Las ejecuto `agents/tests/PuenteSmokeTest.java` (187 aserciones: las 48 de la
primera tanda mas las que se fueron añadeendo con cada endpoint nuevo). Dos
fallos eran bugs reales del nucleo, no de la prueba:

### 6.1 Codigos de error inconsistentes

`requireString` rechazaba tambien los valores de solo espacios, asi que un
`{"message":"   "}` devolvia `invalid_field` en vez de `empty_message`, y el
controlador nunca llegaba a validar el dominio. Ahora `requireString` solo
comprueba que el campo **exista** y devuelve el valor recortado; el "esta
vacio" lo decide quien conoce el dominio y puede usar su codigo correcto.

### 6.2 Direcciones IPv6 ambiguas en las respuestas

`"[::1]:25566"` se parseaba bien (host `::1`, puerto `25566`), pero la respuesta
devolvia `"::1:25566"`, que es imposible de leer: no dice donde acaba el host.
Se entrecorcheta igual que hace la interfaz de Minecraft:

```
{"connecting":"[::1]:25566"}
```

## 7. Kotlin: `ArrayDeque` sin importar es la de Kotlin, no la de Java

`java.util.ArrayDeque` tiene `peekFirst()`; `kotlin.collections.ArrayDeque`
**no** — usa `first()`. Sin `import java.util.ArrayDeque`, Kotlin resuelve a la
suya. Afecta al limitador de tasa de HTTP.

Ademas, dos funciones de extension sobre `List<T>` con distinto `T` se
erasurean a la misma firma JVM y colisionan (`CONFLICTING_JVM_DECLARATIONS`).
Se resuelven con `@JvmName`.

## 8. Mixins de la plantilla: eliminados

`ExampleMixin` (inyecta en `MinecraftServer.loadLevel`) y `ExampleClientMixin`
(inyecta en `Minecraft.run`) eran plantilla de `fabric-example-mod`. Este mod
**no necesita ningun mixin**: la captura de chat va por eventos de Fabric API
(`ClientReceiveMessageEvents`), que existen justamente para evitar interceptar
paquetes a mano.

Se borraron ambos ficheros, los dos `*.mixins.json` y la seccion `mixins` de
`fabric.mod.json`, ademas de los directorios `src/*/java` que quedaron vacios.
Se paso de golpe el `mixins.json` vacio a que el mod arrastre un mixin
inactivo, y se evita la superficie de riesgo que implica un `@Inject` con
`defaultRequire: 1` sobre un metodo del juego.

Si en el futuro hace falta un mixin (por ejemplo un accessor), se vuelve a
anadir el fichero y la entrada; el esqueleto esta en la documentacion de Fabric.

## 9. `environment` cambiado a `client`

Era `"*"`. El nucleo HTTP (`main`) es independiente del juego, pero el puente
(`client`) solo tiene sentido en el cliente, y la superficie de red de un mod
de servidor seria un riesgo de seguridad incoherente. Ademas
`ConnectScreen`, `ClientPacketListener` y `LocalPlayer` son clases exclusive de
cliente: declararlo como servidor solo permitiria que se instalara en un servidor
donde fallaria en runtime.
