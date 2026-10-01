import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;

/**
 * Comprueba contra la clase real de Minecraft el mapeo de ranuras de
 * `ChestStash.ranuraEnElMenuDelCofre`.
 *
 * Por que existe esta prueba: el bug viejo restaba 27 a **todas** las ranuras
 * del inventario, incluida la mochila (9..35). Eso mandaba el clic a las
 * ranuras del propio cofre, asi que no se guardaba ninguna de las 27 de la
 * mochila y, si el cofre ya tenia cosas, se sacaban de vuelta. La funcion del
 * mod tiene que dar, para cada ranura del inventario, la ranura del `ChestMenu`
 * en la que el juego la ha colocado de verdad.
 *
 * Ese mapeo verdadero no se codifica a mano: se construye un `ChestMenu` real
 * con un `SimpleContainer`, se recorren sus `slots` y se apunta en que ranura
 * del menu cae cada ranura del inventario. Luego se llama a la funcion del mod
 * por reflexion y se comparan una a una. Se prueban las 36 ranuras con un
 * cofre de 3 y de 6 filas, que son los dos tamanos que admite el juego.
 *
 * Necesita el classpath del cliente (Minecraft), no basta con las clases de
 * `main`: se compila y se lanza con `agents/tests/client-cp.init.gradle` y los
 * comandos de `agents/03-testing.md`.
 */
public class SlotMap {
	static Method real;
	static Object obj;

	public static void main(String[] args) throws Exception {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();

		Class<?> stash = Class.forName("com.bonilla.puente.client.ChestStash");
		Field instancia = stash.getField("INSTANCE");
		obj = instancia.get(null);
		real = stash.getDeclaredMethod("ranuraEnElMenuDelCofre", int.class, int.class);
		real.setAccessible(true);

		boolean todo = true;
		for (int filas : new int[] { 3, 6 }) {
			todo &= prueba(filas);
		}

		System.out.println();
		System.out.println(todo
			? "TODO EN VERDE: la funcion del mod coincide con el menu real"
			: "FALLOS: la funcion del mod no coincide con el menu real");
		if (!todo) System.exit(1);
	}

	/** Devuelve false si algo no cuadra. */
	static boolean prueba(int filas) throws Exception {
		int chestSize = filas * 9;
		Inventory inv = new Inventory(null, null);
		SimpleContainer chest = new SimpleContainer(chestSize);
		ChestMenu menu = new ChestMenu(null, 0, inv, chest, filas);

		// Mapeo verdadero: ranura del inventario -> ranura del menu.
		int[] verdadero = new int[36];
		java.util.Arrays.fill(verdadero, -1);
		for (int i = 0; i < menu.slots.size(); i++) {
			Slot slot = menu.getSlot(i);
			if (slot.container == inv) {
				int s = slot.getContainerSlot();
				if (s >= 0 && s < 36) verdadero[s] = i;
			}
		}

		System.out.println();
		System.out.println("cofre de " + filas + " filas (" + chestSize + " ranuras), menu de "
			+ menu.slots.size() + " ranuras");

		boolean ok = true;
		for (int s = 0; s < 36; s++) {
			int mod = (Integer) real.invoke(obj, s, chestSize);
			// El inventario real no se puede rellenar sin un jugador, pero la
			// estructura de ranuras no depende de que haya items.
			if (verdadero[s] == -1 || mod != verdadero[s]) {
				ok = false;
				System.out.printf("  MAL ranura %2d del inventario: menu real %3d, mod %3d%n",
					s, verdadero[s], mod);
			}
		}
		// Y que la funcion devuelva un numero distinto por ranura (sin repetir).
		java.util.Set<Integer> vistos = new java.util.HashSet<>();
		for (int s = 0; s < 36; s++) vistos.add((Integer) real.invoke(obj, s, chestSize));
		if (vistos.size() != 36) {
			ok = false;
			System.out.println("  MAL: dos ranuras del inventario caen en la misma del menu");
		}

		System.out.println(ok ? "  ok las 36 ranuras" : "  FALLA");
		return ok;
	}
}
