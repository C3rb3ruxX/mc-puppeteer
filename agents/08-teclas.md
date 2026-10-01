# 08 · Teclas del panel de control

Leyenda de los atajos de teclado de `scripts/tui.ts`: que tecla hace que, cuales
se pueden usar siempre y cuales solo con la linea de ordenes vacia. Sin capturas:
el aspecto del panel va cambiando, las teclas no.

| Fichero | Que hace |
|---|---|
| [`../scripts/tui.ts`](../scripts/tui.ts) | arranque, argumentos y apagado |
| [`../scripts/tui/panel.ts`](../scripts/tui/panel.ts) | `handleKey`: el unico sitio donde se interpretan las teclas |
| [`../scripts/tui/commands.ts`](../scripts/tui/commands.ts) | las ordenes escribibles y el texto del `help` |
| [`../scripts/tui/view.ts`](../scripts/tui/view.ts) | el recordatorio de atajos de la ultima linea |

## Atajos de una sola tecla

Solo con la linea de ordenes **vacia** (el apartado siguiente explica por que).

| Tecla | Que hace |
|---|---|
| `1` ... `9` | selecciona o deselecciona la instancia N (la de la fila N) |
| `a` | selecciona todas |
| `n` | deselecciona ninguna |
| `r` | refresca ahora: fuerza el ciclo entero sin esperar al temporizador |
| `l` | vuelca las ultimas 15 lineas del log en el feed |
| `f` | recorre el foco del inventario (`focus next`), y al dar la vuelta vuelve a la suma |
| `Q` | sale del panel |

- `1`-`9` cuentan por **posicion** en la tabla, no por puerto. Cada pulsacion
  alterna y escribe `mcN seleccionada` o `mcN deseleccionada` en el feed; si no
  hay esa instancia, no pasa nada (no avisa).
- `Q` va en mayuscula a proposito: asi se puede escribir `quieto` en la linea de
  ordenes sin que se salga.

## Teclas que siempre funcionan

| Tecla | Que hace |
|---|---|
| Intro | ejecuta lo escrito y vacia la linea |
| Retroceso | borra el ultimo caracter de la linea |
| `Ctrl-C` / `Esc` | sale del panel |
| Flechas arriba y abajo | recorren el feed una linea |
| `RePag` / `AvPag` | recorren el feed diez lineas |

`Ctrl-C` se come el proceso, asi que el `handleKey` lo devuelve como `false` y el
bucle principal apaga el panel y sale. No hay `Ctrl-C` real: se pasa por
`stdin.setRawMode(true)`, que es lo unico que funciona igual en Bun y en Node
(`readline` no sirve aqui).

## Por que los atajos de una tecla solo con la linea vacia

`a` y `n` son justo las letras de "todas" y "ninguna". Si funcionaran siempre,
escribir `nan` en una orden no se podria: la `n` se comeria el texto. Lo mismo
con `Q` y con los numeros. Asi que `panel.ts` mira `state.input` antes de
intentarlo, y si no esta vacio cualquier caracter va al texto:

```ts
// scripts/tui/panel.ts
if (state.input === '') {
    if (char === 'Q') return false
    if (/^[1-9]$/.test(char)) { ... }
    // a, n, r, l, f
}
state.input += char
```

## El atajo de `say`

En la linea de ordenes, `!` delante del texto es lo mismo que `say`:

```
!hola           # equivalente a: say hola
```

Sale en el `help` del panel. Sirve para no tener que escribir `say` cada vez.

## Lo que se ve en la propia pantalla

El recordatorio de la zona de ordenes (`SHORTCUTS` en `view.ts`) es una version
corta de esta pagina: los nombres de las ordenes y, al final, `1-9 · Q`. Si se
parte en varias lineas es porque la zona es estrecha, no porque falte nada.