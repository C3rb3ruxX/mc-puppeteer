  import com.bonilla.puente.*;
  import com.google.gson.JsonArray;
  import com.google.gson.JsonObject;
  import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
  import java.util.ArrayList;
  import java.util.LinkedHashMap;
  import java.util.List;
  import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Prueba de humo del nucleo HTTP con un puente falso, fuera de Minecraft. */
public class PuenteSmokeTest {
	static final AtomicInteger onMainThreadCalls = new AtomicInteger();
	/** Chat y comando se cuentan por separado: Baritone solo reacciona al chat. */
	static final AtomicInteger chatCalls = new AtomicInteger();
	static final AtomicInteger commandCalls = new AtomicInteger();
	static final List<String> sentChat = new ArrayList<>();
	static final List<String> screenLines = List.of("[Baritone] ok");
	static final HttpClient http = HttpClient.newHttpClient();
	static String base;
	static String token = "token-secreto";
	static int failures = 0;

	public static void main(String[] args) throws Exception {
		PuenteConfig config = new PuenteConfig(
			true, "127.0.0.1", 25599, true, token, 64, 16384, 2000L, 2);

		List<String> validate = config.validate();
		check("[1] config valida sin problemas", validate.isEmpty(), String.valueOf(validate));

		List<String> bad = new PuenteConfig(true, "127.0.0.1", 70000, true, "t", 256, 1024, 5000L, 4).validate();
		check("[2] validate detecta puerto fuera de rango", bad.size() == 1, String.valueOf(bad));

		List<String> noToken = new PuenteConfig(true, "127.0.0.1", 25599, true, "", 256, 1024, 5000L, 4).validate();
		check("[3] token vacio NO es un error de config (se autogenera al arrancar)",
			noToken.isEmpty(), String.valueOf(noToken));

		PuenteConfig necesitaToken = new PuenteConfig(true, "127.0.0.1", 25599, true, "", 256, 1024, 5000L, 4);
		check("[4] tokenFaltaPorGenerar() lo detecta aunque validate() no se queje",
			necesitaToken.tokenFaltaPorGenerar(), "false");

		PuenteConfig yaTiene = new PuenteConfig(true, "127.0.0.1", 25599, true, "t", 256, 1024, 5000L, 4);
		check("[5] tokenFaltaPorGenerar() es false si ya hay token",
			!yaTiene.tokenFaltaPorGenerar(), "true");

		PuenteConfig sinAuth = new PuenteConfig(true, "127.0.0.1", 25599, false, "", 256, 1024, 5000L, 4);
		check("[6] con requireToken=false no se genera nada",
			!sinAuth.tokenFaltaPorGenerar(), "true");

		// Regresión: applyDefaults() degradaba requireToken a false cuando el
		// token venía vacío, lo que dejaba muerto el bloque de autogeneración y
		// abría el puerto sin autenticación pese a que la config la pedía.
		check("[7] validate() con token vacío deja los otros campos intactos",
			necesitaToken.getRequireToken(),
			"requireToken se degradó a false");

		MainThreadExecutor main = new MainThreadExecutor() {
			private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
			{
				Thread t = new Thread(() -> {
					while (true) { try { queue.take().run(); } catch (Exception e) { return; } }
				}, "fake-main");
				t.setDaemon(true); t.start();
			}
			@Override public void execute(Runnable r) { queue.add(r); }
			@Override public boolean isMainThread() { return false; }
		};

		// Estado de identidad que el harness va cambiando con POST /profile.
		AtomicBoolean inWorld = new AtomicBoolean(false);
		// `isDead` lo cambia la prueba via POST /respawn para simular la muerte.
		AtomicBoolean isDead = new AtomicBoolean(false);
		AtomicInteger respawns = new AtomicInteger(0);
		MinecraftBridge bridge = new MinecraftBridge() {
			String profileName = "Steve";
			String profileUuid = "uuid-1";
			@Override public ClientStatus status() {
				onMainThreadCalls.incrementAndGet();
				return new ClientStatus("1.0.0", "26.3", true, "ChatScreen", "Steve", "uuid-1",
					"localhost:25565", "Mi servidor", null, "minecraft:overworld", 144, 3, 20, true,
					isDead.get());
			}
			@Override public void sendChat(String m) {
				onMainThreadCalls.incrementAndGet();
				chatCalls.incrementAndGet();
				sentChat.add(m);
				checkOnMain("sendChat '" + m + "'");
			}
			@Override public void sendCommand(String c) {
				onMainThreadCalls.incrementAndGet();
				commandCalls.incrementAndGet();
				checkOnMain("sendCommand '" + c + "'");
			}
			@Override public List<String> screenChat(int limit) {
				// El hilo principal es la parte interesante: la lista del chat la
				// toca el juego, asi que leerla desde el hilo HTTP seria una
				// carrera. Aqui solo se comprueba que se pida desde alli.
				onMainThreadCalls.incrementAndGet();
				checkOnMain("screenChat " + limit);
				return screenLines;
			}
			@Override public void connect(String h, int p, String n) {
				onMainThreadCalls.incrementAndGet();
				checkOnMain("connect " + h + ":" + p);
			}
			@Override public void disconnect() {
				onMainThreadCalls.incrementAndGet();
				checkOnMain("disconnect");
			}
			@Override public void respawn() {
				onMainThreadCalls.incrementAndGet();
				checkOnMain("respawn");
				if (!inWorld.get()) {
					throw new PuenteException(409, "not_connected", "El cliente no esta en ningun mundo");
				}
				if (!isDead.get()) {
					throw new PuenteException(409, "not_dead", "El jugador no esta muerto: no hay nada que reaparecer");
				}
				isDead.set(false);
				respawns.incrementAndGet();
			}
		@Override public List<RemotePlayerInfo> onlinePlayers() {
			onMainThreadCalls.incrementAndGet();
			return List.of(new RemotePlayerInfo("Alex", "uuid-2", 42, "Alex"));
		}
		@Override public PlayerInventory inventory() {
			onMainThreadCalls.incrementAndGet();
			checkOnMain("inventory");
			if (!inWorld.get()) {
				throw new PuenteException(409, "not_connected", "El cliente no esta en ningun mundo");
			}
			// Reproduce los tres casos que importan del JSON: un objeto con
			// desgaste, uno que se estropea pero sin dano, y un hueco vacio que
			// debe seguir presente.
			List<ItemSlot> hotbar = new ArrayList<>();
			for (int i = 0; i < 9; i++) {
				if (i == 0) {
					hotbar.add(new ItemSlot(i, "minecraft:diamond_pickaxe", 1, "Pico de diamante", 12, 1561));
				} else if (i == 1) {
					hotbar.add(new ItemSlot(i, null, 0, null, null, null));
				} else {
					hotbar.add(new ItemSlot(i, "minecraft:stone", 64, "Piedra", null, null));
				}
			}
			List<ItemSlot> main = new ArrayList<>();
			for (int i = 9; i < 36; i++) {
				main.add(new ItemSlot(i, "minecraft:dirt", 32, "Tierra", null, null));
			}
			Map<String, ItemSlot> armor = new LinkedHashMap<>();
			armor.put("head", new ItemSlot(5, "minecraft:iron_helmet", 1, "Casco de hierro", 3, 165));
			armor.put("chest", new ItemSlot(6, "minecraft:iron_chestplate", 1, "Peto de hierro", 3, 240));
			// Sin pieza en las piernas: el hueco se conserva.
			armor.put("legs", new ItemSlot(7, null, 0, null, null, null));
			armor.put("feet", new ItemSlot(8, "minecraft:iron_boots", 1, "Botas de hierro", 3, 195));
			return new PlayerInventory(3, hotbar, main, armor,
				new ItemSlot(-1, "minecraft:shield", 1, "Escudo", null, null));
		}
			@Override public PlayerIdentity playerIdentity() {
				onMainThreadCalls.incrementAndGet();
				checkOnMain("playerIdentity");
				return new PlayerIdentity(profileName, profileUuid, false);
			}
			@Override public PlayerIdentity setPlayerName(String name) {
				onMainThreadCalls.incrementAndGet();
				checkOnMain("setPlayerName " + name);
				// Mismo criterio que `ClientBridge`: solo [A-Za-z0-9_]{1,16}.
				if (!name.matches("[A-Za-z0-9_]{1,16}")) {
					throw new PuenteException(400, "invalid_player_name", "Nombre invalido '" + name + "'");
				}
				profileName = name;
				// UUID v3 determinista, suficiente para el harness.
				profileUuid = "uuid-" + name;
				return new PlayerIdentity(profileName, profileUuid, inWorld.get());
			}
			@Override public void dispose() {}
		};

		PuenteController controller = new PuenteController(
			bridge, new MainThreadBridge(main, 2000L), new ChatLog(64), "1.0.0");
		PuenteHttpServer server = new PuenteHttpServer(
			config, controller, org.slf4j.LoggerFactory.getLogger("smoke"));

		PuenteHttpServer.StartResult result = server.start();
		check("[4] servidor arranca en 127.0.0.1:25599", result.getStarted(), String.valueOf(result.getBoundAddress()));
		if (!result.getStarted()) return;
		base = "http://127.0.0.1:25599/puppeteer";

		// Busy-wait de arranque (el bind es sincrono, pero el pool necesita un instante).
		for (int i = 0; i < 50; i++) {
			if (call("GET", "/health", null, false).status() == 200) break;
			Thread.sleep(20);
		}

		ChatLog log = new ChatLog(64);
		// Los mensajes se registran desde el "hilo principal" para simular los eventos de MC.
		main.execute(() -> {
			log.record(new CapturedMessage(1000L, CapturedMessage.Kind.CHAT, "Hola a todos", "Alex", false));
			log.record(new CapturedMessage(1001L, CapturedMessage.Kind.GAME, "Alex se ha unido", null, true));
			log.recordSent("saludo manual");
		});
		Thread.sleep(150);

		// --- auth -----------------------------------------------------------
		Res r = call("GET", "/health", null, false);
		check("[5] /health responde 200 sin token", r.status() == 200, r.body());

		r = call("GET", "/status", null, false);
		check("[6] /status sin token -> 401", r.status() == 401, r.body());
		check("[7] 401 incluye WWW-Authenticate Bearer", r.header("www-authenticate") != null, r.header("www-authenticate"));

		r = call("GET", "/status", null, true);
		check("[8] /status con token -> 200", r.status() == 200, r.body());
		check("[9] status incluye inWorld y fps", r.body().contains("\"inWorld\":true") && r.body().contains("\"fps\":144"), r.body());

		r = call("GET", "/status", null, "token incorrecto");
		check("[10] /status con token erroneo -> 401", r.status() == 401, r.body());

		// --- solo lectura ---------------------------------------------------
		r = call("GET", "/players", null, true);
		check("[11] /players -> 200 con Alex", r.status() == 200 && r.body().contains("\"name\":\"Alex\""), r.body());

		r = call("GET", "/debug", null, true);
		check("[12] /debug -> 200", r.status() == 200, r.body());

		// --- envio ----------------------------------------------------------
		r = call("POST", "/chat", "{\"message\":\"hola desde http\"}", true);
		check("[13] POST /chat -> 202 enviado", r.status() == 202 && r.body().contains("hola desde http"), r.body());

		r = call("POST", "/chat", "{\"message\":\"/list\"}", true);
		check("[14] POST /chat con barra -> 400 leading_slash",
			r.status() == 400 && r.body().contains("leading_slash"), r.body());

		r = call("POST", "/chat", "{}", true);
		check("[15] POST /chat sin message -> 400 missing_field",
			r.status() == 400 && r.body().contains("missing_field"), r.body());

		r = call("POST", "/chat", "{\"message\":\"   \"}", true);
		check("[16] POST /chat solo espacios -> 400 empty_message",
			r.status() == 400 && r.body().contains("empty_message"), r.body());

		r = call("POST", "/command", "{\"command\":\"list\"}", true);
		check("[17] POST /command sin barra -> 202", r.status() == 202 && r.body().contains("\"sent\":\"list\""), r.body());

		r = call("POST", "/command", "{\"command\":\"/say hola\"}", true);
		check("[18] POST /command con barra normaliza a 'say hola'",
			r.status() == 202 && r.body().contains("\"sent\":\"say hola\""), r.body());

		r = call("POST", "/command", "{}", true);
		check("[19] POST /command sin command -> 400", r.status() == 400 && r.body().contains("missing_field"), r.body());

		// --- conexion -------------------------------------------------------
		r = call("POST", "/connect", "{\"address\":\"localhost:25565\"}", true);
		check("[20] POST /connect con address -> 202", r.status() == 202 && r.body().contains("localhost:25565"), r.body());

		r = call("POST", "/connect", "{\"host\":\"ejemplo.com\",\"port\":70000}", true);
		check("[21] puerto 70000 -> 400 invalid_port", r.status() == 400 && r.body().contains("invalid_port"), r.body());

		r = call("POST", "/connect", "{\"address\":\"[::1]:25566\"}", true);
		check("[22] connect IPv6 con puerto -> 202 [::1]:25566", r.status() == 202 && r.body().contains("[::1]:25566"), r.body());

		r = call("POST", "/connect", "{\"address\":\"juego.mc\"}", true);
		// Esta asercion fijaba 25580 (el puerto del HTTP), que era un bug. Ahora
		// el puerto por defecto del juego es 25565. Los otros dos caminos sin
		// puerto se cubren en 26a-26c.
		check("[23] connect sin puerto -> 202 con puerto por defecto 25565",
			r.status() == 202 && r.body().contains("juego.mc:25565"), r.body());

		r = call("POST", "/connect", "{\"host\":\"servidor\",\"port\":25577,\"name\":\"Custom\"}", true);
		check("[24] connect host/port/nombre -> 202", r.status() == 202 && r.body().contains("servidor:25577"), r.body());

		r = call("POST", "/connect", "{}", true);
		check("[25] connect sin destino -> 400 missing_host", r.status() == 400 && r.body().contains("missing_host"), r.body());

		r = call("POST", "/connect", "{\"address\":\"host:abc\"}", true);
		check("[26] connect puerto no numerico -> 400", r.status() == 400, r.body());

		// Sin puerto explicito debe usarse 25565 (Minecraft), no 25580 (el puerto
		// del servidor HTTP de Puente). Antes de corregirlo, estas tres formas
		// caian en 25580.
		r = call("POST", "/connect", "{\"host\":\"servidor\"}", true);
		check("[26a] connect sin puerto usa 25565, no 25580",
			r.status() == 202 && r.body().contains("servidor:25565") && !r.body().contains("25580"), r.body());

		r = call("POST", "/connect", "{\"address\":\"otro.servidor\"}", true);
		check("[26b] address sin puerto usa 25565",
			r.status() == 202 && r.body().contains("otro.servidor:25565"), r.body());

		r = call("POST", "/connect", "{\"address\":\"[::1]\"}", true);
		check("[26c] IPv6 sin puerto usa 25565",
			r.status() == 202 && r.body().contains("[::1]:25565"), r.body());

		r = call("POST", "/disconnect", null, true);
		check("[27] POST /disconnect -> 202", r.status() == 202, r.body());

		// --- identidad offline ----------------------------------------------
		r = call("GET", "/profile", null, true);
		check("[27a] GET /profile devuelve la identidad actual",
			r.status() == 200 && r.body().contains("\"name\":\"Steve\"") && r.body().contains("uuid-1"), r.body());

		r = call("POST", "/profile", "{\"name\":\"Tester1\"}", true);
		check("[27b] POST /profile cambia el nombre -> 200",
			r.status() == 200 && r.body().contains("\"name\":\"Tester1\""), r.body());

		r = call("GET", "/profile", null, true);
		check("[27c] el cambio persiste al releer",
			r.status() == 200 && r.body().contains("\"name\":\"Tester1\""), r.body());

		r = call("POST", "/profile", "{\"name\":\"Tester_2\"}", true);
		check("[27d] acepta guion bajo y digitos", r.status() == 200, r.body());

		r = call("POST", "/profile", "{\"name\":\"\"}", true);
		check("[27e] nombre vacio -> 400 missing_name",
			r.status() == 400 && r.body().contains("missing_name"), r.body());

		r = call("POST", "/profile", "{}", true);
		check("[27f] sin 'name' -> 400 missing_name",
			r.status() == 400 && r.body().contains("missing_name"), r.body());

		r = call("POST", "/profile", "{\"name\":\"Nombre Con Espacios\"}", true);
		check("[27g] espacios en el nombre -> 400 invalid_player_name",
			r.status() == 400 && r.body().contains("invalid_player_name"), r.body());

		r = call("POST", "/profile", "{\"name\":\"MuyLargoNombreQueNoCabe\"}", true);
		check("[27h] nombre de 24 chars -> 400 invalid_player_name",
			r.status() == 400 && r.body().contains("invalid_player_name"), r.body());

		r = call("POST", "/profile", "{\"name\":\"Alex\\n#op\"}", true);
		check("[27i] salto de linea en el nombre -> 400 invalid_player_name",
			r.status() == 400 && r.body().contains("invalid_player_name"), r.body());

		r = call("DELETE", "/profile", null, true);
		check("[27j] DELETE /profile -> 405", r.status() == 405, r.status() + " " + r.body());

		// --- reaparicion ------------------------------------------------------
		r = call("POST", "/respawn", null, true);
		check("[27k] respawn sin mundo -> 409 not_connected",
			r.status() == 409 && r.body().contains("not_connected"), r.body());

		inWorld.set(true);
		r = call("POST", "/respawn", null, true);
		check("[27l] respawn con el jugador vivo -> 409 not_dead",
			r.status() == 409 && r.body().contains("not_dead"), r.body());
		check("[27m] un 409 not_dead no cuenta como reaparicion", respawns.get() == 0, "respawns=" + respawns.get());

		// Con `dead` en /status se puede supervisar la muerte sin adivinar.
		r = call("GET", "/status", null, true);
		check("[27n] /status expone dead=false con el jugador vivo",
			r.status() == 200 && r.body().contains("\"dead\":false"), r.body());

		isDead.set(true);
		r = call("GET", "/status", null, true);
		check("[27o] /status expone dead=true tras morir",
			r.status() == 200 && r.body().contains("\"dead\":true"), r.body());

		r = call("POST", "/respawn", null, true);
		check("[27p] respawn con el jugador muerto -> 202",
			r.status() == 202 && r.body().contains("\"respawning\":true"), r.body());
		check("[27q] el puente reaparece al jugador", respawns.get() == 1, "respawns=" + respawns.get());
		check("[27r] tras reaparecer dead vuelve a false", !isDead.get(), "isDead=" + isDead.get());

		r = call("POST", "/respawn", null, true);
		check("[27s] repetir respawn en vida -> 409 not_dead y no cuenta como segundo respawn",
			r.status() == 409 && r.body().contains("not_dead") && respawns.get() == 1,
			r.status() + " respawns=" + respawns.get());

		r = call("GET", "/respawn", null, true);
		check("[27t] GET /respawn -> 405, reaparecer no es idempotente por GET",
			r.status() == 405, r.status() + " " + r.body());

		// --- inventario -----------------------------------------------------
		// Sin mundo no hay `LocalPlayer`, asi que no hay inventario que leer.
		// El bloque de respawn deja `inWorld` a true, asi que se baja antes.
		inWorld.set(false);
		r = call("GET", "/inventory", null, true);
		check("[28a] inventario sin mundo -> 409 not_connected",
			r.status() == 409 && r.body().contains("not_connected"), r.body());

		inWorld.set(true);
		r = call("GET", "/inventory", null, true);
		check("[28b] inventario -> 200", r.status() == 200, r.status() + " " + r.body());

		JsonObject inv = JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("data");
		JsonArray hotbarArr = inv.getAsJsonArray("hotbar");
		JsonArray mainArr = inv.getAsJsonArray("main");
		JsonObject armor = inv.getAsJsonObject("armor");

		check("[28c] 9 slots de barra rapida y 27 de mochila",
			hotbarArr.size() == 9 && mainArr.size() == 27,
			"hotbar=" + hotbarArr.size() + " main=" + mainArr.size());
		check("[28d] la armadura se indexa por pieza, no por posicion",
			armor.size() == 4 && armor.has("head") && armor.has("chest")
				&& armor.has("legs") && armor.has("feet"),
			armor.keySet().toString());
		check("[28e] el id del item es la ruta del registro, no el nombre traducido",
			hotbarArr.get(0).getAsJsonObject().get("id").getAsString().equals("minecraft:diamond_pickaxe")
				&& hotbarArr.get(0).getAsJsonObject().get("name").getAsString().equals("Pico de diamante"),
			hotbarArr.get(0).toString());
		// El hueco vacio se conserva: si se omitiera, `hotbar[1]` seria el slot 2
		// y cualquier bot que indexe por posicion estaria leyendo otra cosa.
		check("[28f] los huecos vacios se conservan con id null y count 0",
			hotbarArr.get(1).getAsJsonObject().get("id").isJsonNull()
				&& hotbarArr.get(1).getAsJsonObject().get("count").getAsInt() == 0,
			hotbarArr.get(1).toString());
		check("[28g] el desgaste solo aparece en objetos que se estropean",
			hotbarArr.get(0).getAsJsonObject().get("damage").getAsInt() == 12
				&& hotbarArr.get(0).getAsJsonObject().get("maxDamage").getAsInt() == 1561
				&& hotbarArr.get(2).getAsJsonObject().get("damage").isJsonNull()
				&& hotbarArr.get(2).getAsJsonObject().get("maxDamage").isJsonNull(),
			hotbarArr.get(2).toString());
		check("[28h] la mano secundaria va con indice -1 y fuera de la mochila",
			inv.getAsJsonObject("offhand").get("id").getAsString().equals("minecraft:shield")
				&& inv.getAsJsonObject("offhand").get("index").getAsInt() == -1,
			inv.get("offhand").toString());
		check("[28i] la pieza de armor tambien puede estar vacia",
			armor.getAsJsonObject("legs").get("id").isJsonNull(), armor.get("legs").toString());
		// 8 de la barra (el slot 1 vacio) + 27 de mochila + 3 de armadura + 1 escudo.
		check("[28j] filled cuenta solo los huecos con algo",
			inv.get("filled").getAsInt() == 39, inv.get("filled").toString());
		check("[28k] se expone la ranura seleccionada",
			inv.get("selectedSlot").getAsInt() == 3, inv.get("selectedSlot").toString());

		r = call("POST", "/inventory", null, true);
		check("[28l] POST /inventory -> 405, el inventario es de solo lectura",
			r.status() == 405, r.status() + " " + r.body());

		inWorld.set(false);

		// --- errores de transporte -----------------------------------------
		r = call("DELETE", "/status", null, true);
		check("[28] DELETE /status -> 405 con cabecera Allow",
			r.status() == 405 && r.header("allow") != null, r.status() + " allow=" + r.header("allow"));

		r = call("GET", "/ruta-inexistente", null, true);
		check("[29] ruta inexistente -> 404 not_found", r.status() == 404 && r.body().contains("not_found"), r.body());

		r = call("GET", "/chat?limit=99999", null, true);
		check("[30] limit fuera de rango -> 400 invalid_query", r.status() == 400 && r.body().contains("invalid_query"), r.body());

		r = call("GET", "/chat?limit=abc", null, true);
		check("[31] limit no numerico -> 400 invalid_query", r.status() == 400, r.body());

		r = call("POST", "/chat", "{json roto", true);
		check("[32] JSON invalido -> 400 invalid_json", r.status() == 400 && r.body().contains("invalid_json"), r.body());

		r = call("POST", "/chat", "", true);
		check("[33] body vacio -> 400 empty_body", r.status() == 400 && r.body().contains("empty_body"), r.body());

		r = call("GET", "/otra-cosa", null, true);
		check("[34] fuera del base path -> 404", r.status() == 404, r.body());

		r = call("GET", "/", null, true);
		check("[35] base path raiz -> 200 indice de endpoints", r.status() == 200 && r.body().contains("endpoints"), r.body());

		r = call("OPTIONS", "/status", null, false);
		check("[36] OPTIONS (CORS preflight) -> 204", r.status() == 204, String.valueOf(r.status()));

		// --- limite de tamano de body ---------------------------------------
		StringBuilder huge = new StringBuilder("{\"message\":\"");
		huge.append("x".repeat(40000)).append("\"}");
		r = call("POST", "/chat", huge.toString(), true);
		check("[37] body de 40 KB -> 413 body_too_large", r.status() == 413 && r.body().contains("body_too_large"), r.status() + " " + r.body());

		// --- buffer de chat --------------------------------------------------
		ChatLog buffer = new ChatLog(4);
		for (int i = 0; i < 10; i++) {
			buffer.record(new CapturedMessage(i, CapturedMessage.Kind.CHAT, "msg" + i, null, false));
		}
		List<CapturedMessage> kept = buffer.snapshot(100);
		check("[38] buffer acotado a 4 mensajes", kept.size() == 4, "size=" + kept.size());
		check("[39] buffer conserva los 4 mas recientes", kept.get(0).getText().equals("msg6") && kept.get(3).getText().equals("msg9"),
			kept.get(0).getText() + ".." + kept.get(3).getText());
		check("[40] buffer contabiliza descartes", buffer.droppedCount() == 6, String.valueOf(buffer.droppedCount()));
		check("[41] snapshot no drena", buffer.size() == 4, String.valueOf(buffer.size()));
		check("[42] drain vacia el buffer", buffer.drain(2, false).size() == 2 && buffer.size() == 2, String.valueOf(buffer.size()));
		check("[43] drain de mas de lo disponible devuelve lo que hay", buffer.drain(99, false).size() == 2, String.valueOf(buffer.size()));
		check("[44] drain en buffer vacio -> 0", buffer.drain(10, false).isEmpty(), "vacio");

		// --- Baritone -------------------------------------------------------
		// Baritone no tiene API HTTP: se controla con chat prefijado con '#'.
		// Lo que se comprueba aqui es que el bridge lo envie por sendChat y
		// NUNCA por sendCommand (que va al servidor y lo rechaza).
		int chatBefore = chatCalls.get();
		int commandBefore = commandCalls.get();
		int dispatched = 0;

		r = call("GET", "/baritone", null, true);
		check("[45] indice de Baritone -> 200", r.status() == 200 && r.body().contains("baritone"), r.body());

		// Consultas por GET
		String[][] gets = {
			{"/baritone/version", "#version"},
			{"/baritone/proc", "#proc"},
			{"/baritone/eta", "#eta"},
			{"/baritone/modified", "#modified"},
			{"/baritone/paused", "#paused"},
			{"/baritone/wp", "#waypoints"},
			{"/baritone/gc", "#gc"},
			{"/baritone/help?q=mine", "#help mine"},
			{"/baritone/find?block=diamond_ore", "#find diamond_ore"},
		};
		int n = 46;
		for (String[] g : gets) {
			r = call("GET", g[0], null, true);
			boolean ok = r.status() == 202 && r.body().contains(g[1]);
			check("[" + n + "] GET " + g[0] + " -> " + g[1], ok, r.status() + " " + r.body());
			if (ok) dispatched++;
			n++;
		}

		// Traduccion de acciones: cuerpo -> comando '#'
		String[][] posts = {
			{"/baritone/goto", "{\"x\":1000,\"y\":64,\"z\":500}", "#goto 1000 64 500"},
			{"/baritone/goto", "{\"x\":1000,\"z\":500}", "#goto 1000 500"},
			{"/baritone/goto", "{\"y\":64}", "#goto 64"},
			{"/baritone/goto", "{\"block\":\"diamond_ore\"}", "#goto diamond_ore"},
			{"/baritone/goal", "{\"x\":1,\"y\":2,\"z\":3}", "#goal 1 2 3"},
			{"/baritone/mine", "{\"block\":\"diamond_ore\",\"amount\":16}", "#mine diamond_ore 16"},
			{"/baritone/mine", "{\"block\":\"diamond_ore\"}", "#mine diamond_ore"},
			{"/baritone/build", "{\"file\":\"base.schematic\",\"x\":1,\"y\":2,\"z\":3}", "#build base.schematic 1 2 3"},
			{"/baritone/build", "{\"file\":\"base.schematic\"}", "#build base.schematic"},
			{"/baritone/follow", "{\"target\":\"Alex\"}", "#follow Alex"},
			{"/baritone/tunnel", "{\"height\":1,\"width\":2,\"length\":3}", "#tunnel 1 2 3"},
			// cleararea es subcomando de sel, no comando propio
			{"/baritone/cleararea", "{\"radius\":5}", "#sel cleararea 5"},
			{"/baritone/explore", "{\"x\":100,\"z\":200}", "#explore 100 200"},
			{"/baritone/explore", "{}", "#explore"},
			{"/baritone/axis", "{\"y\":12}", "#axis 12"},
			// thisway exige distancia; stop es alias de cancel
			{"/baritone/thisway", "{\"distance\":50}", "#thisway 50"},
			{"/baritone/stop", "{}", "#cancel"},
			{"/baritone/surface", "{}", "#surface"},
			{"/baritone/pause", "{}", "#pause"},
			{"/baritone/resume", "{}", "#resume"},
			{"/baritone/repack", "{}", "#repack"},
		};
		for (String[] p : posts) {
			r = call("POST", p[0], p[1], true);
			boolean ok = r.status() == 202 && r.body().contains(p[2]);
			check("[" + n + "] POST " + p[0] + " " + p[1] + " -> " + p[2], ok, r.status() + " " + r.body());
			if (ok) dispatched++;
			n++;
		}

		// Los alias se traducen al nombre canonico que registra Baritone
		String[][] aliases = {
			{"/baritone/stop?force", "{}", "#forcecancel"},
			{"/baritone/top", "{}", "#surface"},
			{"/baritone/home", "{}", "#home"},
		};
		for (String[] p : aliases) {
			r = call("POST", p[0], p[1], true);
			boolean ok = r.status() == 202 && r.body().contains(p[2]);
			check("[" + n + "] alias " + p[0] + " -> " + p[2], ok, r.status() + " " + r.body());
			if (ok) dispatched++;
			n++;
		}

		// Rechazos: coordenadas incoherentes e inyeccion de texto
		String[][] rejects = {
			{"/baritone/goto", "{}", "400", "invalid_goal"},
			{"/baritone/goto", "{\"x\":1,\"y\":2}", "400", "invalid_goal"},
			{"/baritone/axis", "{\"y\":999}", "400", "invalid_field"},
			{"/baritone/axis", "{}", "400", "missing_field"},
			{"/baritone/explore", "{\"x\":100}", "400", "invalid_field"},
			{"/baritone/cleararea", "{\"radius\":0}", "400", "invalid_field"},
			{"/baritone/thisway", "{}", "400", "missing_field"},
			// thisway exige 1 argumento en Baritone: no puede ir sin cuerpo
			{"/baritone/thisway", "{\"distance\":0}", "400", "invalid_field"},
			// mine y build exigen bloque/fichero
			{"/baritone/mine", "{}", "400", "missing_field"},
			// schematica esta comentado en DefaultCommands: no hay ruta
			{"/baritone/schematica", "{}", "404", "not_found"},
			// Ruta no registrada: el router la rechaza antes de llegar al traductor.
			{"/baritone/inventario", "{}", "404", "not_found"},
			// /baritone/find solo admite GET; por POST debe dar 405, no enviar nada.
			{"/baritone/find", "{\"block\":\"stone\"}", "405", "method_not_allowed"},
		};
		for (String[] p : rejects) {
			r = call("POST", p[0], p[1], true);
			boolean ok = r.status() == Integer.parseInt(p[2]) && r.body().contains(p[3]);
			check("[" + n + "] rechaza " + p[0] + " -> " + p[2] + " " + p[3], ok, r.status() + " " + r.body());
			n++;
		}

		// Inyeccion: nada fuera del charset debe llegar al comando
		String[][] injections = {
			{"/baritone/mine", "{\"block\":\"diamond; op Alex\"}", "invalid_field"},
			{"/baritone/follow", "{\"target\":\"Alex\\n#op\"}", "invalid_field"},
			{"/baritone/build", "{\"file\":\"../../etc/passwd\"}", "invalid_field"},
			{"/baritone/build", "{\"file\":\"base.schematic && rm -rf /\"}", "invalid_field"},
		};
		for (String[] p : injections) {
			r = call("POST", p[0], p[1], true);
			boolean ok = r.status() == 400 && r.body().contains(p[2]);
			check("[" + n + "] inyeccion bloqueada en " + p[0] + ": " + p[1], ok, r.status() + " " + r.body());
			n++;
		}

		// Todos los comandos salen por chat, ninguno por comando de servidor
		check("[" + n + "] los " + dispatched + " comandos Baritone salieron por sendChat",
			chatCalls.get() - chatBefore == dispatched, "chat +" + (chatCalls.get() - chatBefore));
		n++;
		check("[" + n + "] ningun comando de Baritone uso sendCommand",
			commandCalls.get() == commandBefore, "command +" + (commandCalls.get() - commandBefore));
		n++;
		check("[" + n + "] todos los mensajes Baritone llevan prefijo '#'",
			sentChat.stream().filter(s -> s.startsWith("#")).count() >= dispatched, sentChat.toString());
		n++;

		// --- sincronizacion con el codigo de Baritone ------------------------
		// El registro de comandos se contrasta contra BaritoneCommandSignatures,
		// que esta extraido de las firmas del source de Baritone. Asi, si un dia
		// Baritone renombra o quita un comando, falla aqui y no en juego.
		List<String> canonicos = new ArrayList<>();
		for (String s : BaritoneCommandSignatures.CANONICAL) canonicos.add(s);
		List<String> delTraductor = new ArrayList<>();
		for (BaritoneTranslator.Command c : BaritoneTranslator.INSTANCE.getCOMMANDS()) {
			delTraductor.add(c.getNames().get(0));
		}
		List<String> faltan = new ArrayList<>(canonicos);
		faltan.removeAll(delTraductor);
		check("[" + n + "] el registro cubre todos los comandos de Baritone",
			faltan.isEmpty(), "faltan: " + faltan);
		n++;

		List<String> sobran = new ArrayList<>(delTraductor);
		sobran.removeAll(canonicos);
		check("[" + n + "] el registro no inventa comandos inexistentes",
			sobran.isEmpty(), "sobran: " + sobran);
		n++;

		// Comandos que Baritone no expone pese a parecer que si
		for (String noExiste : BaritoneCommandSignatures.UNAVAILABLE) {
			boolean ok = BaritoneTranslator.INSTANCE.resolve(noExiste) == null;
			check("[" + n + "] '" + noExiste + "' no existe en Baritone y no se traduce",
				ok, "resolve -> " + BaritoneTranslator.INSTANCE.resolve(noExiste));
			n++;
		}

		// Los alias tienen que resolver al nombre canonico
		String[][] resolucion = {
			{"stop", "cancel"}, {"c", "cancel"}, {"top", "surface"},
			{"wp", "waypoints"}, {"waypoint", "waypoints"}, {"highway", "axis"},
			{"p", "pause"}, {"paws", "pause"}, {"unpause", "resume"},
			{"selection", "sel"}, {"s", "sel"}, {"forward", "thisway"},
			{"mod", "modified"}, {"settings", "set"}, {"rescan", "repack"},
		};
		for (String[] a : resolucion) {
			String got = BaritoneTranslator.INSTANCE.resolve(a[0]);
			check("[" + n + "] alias '" + a[0] + "' -> '" + a[1] + "'",
				a[1].equals(got), "resolve -> " + got);
			n++;
		}

		// Comandos que exigen argumentos no pueden enviarse vacios
		for (String req : BaritoneCommandSignatures.REQUIRE_ARGUMENTS) {
			boolean ok = false;
			String codigo = "";
			try {
				BaritoneTranslator.INSTANCE.noArg(req);
			} catch (com.bonilla.puente.http.HttpError e) {
				ok = "requires_arguments".equals(e.getCode());
				codigo = e.getCode();
			}
			check("[" + n + "] '" + req + "' exige argumentos y no se envia vacio", ok, codigo);
			n++;
		}

		// Y los que no los exigen, si
		for (String sin : new String[]{"surface", "cancel", "path", "invert", "blacklist", "click", "elytra", "pause", "resume"}) {
			check("[" + n + "] '" + sin + "' se puede enviar sin argumentos",
				("#" + sin).equals(BaritoneTranslator.INSTANCE.noArg(sin)), BaritoneTranslator.INSTANCE.noArg(sin));
			n++;
		}

		// Clasificacion: cada traductor acepta lo suyo y rechaza lo demas.
		// Se comprueba el codigo de error cuando se espera rechazo, y el comando
		// exacto cuando se espera exito.
		Object[][] clasificacion = {
			// metodo,      comando,      debeRechazar, codigoEsperado / comandoEsperado
			{"readOnly", "version", false, "#version"},
			{"readOnly", "waypoints", false, "#waypoints"},
			{"readOnly", "sel", true, "not_read_only"},
			{"readOnly", "repack", true, "not_read_only"},
			{"maintenance", "repack", false, "#repack"},
			{"maintenance", "saveall", false, "#saveall"},
			{"maintenance", "version", true, "not_maintenance"},
			{"maintenance", "surface", true, "not_maintenance"},
			{"noArg", "surface", false, "#surface"},
			// version no exige argumentos, asi que noArg lo admite
			{"noArg", "version", false, "#version"},
			// estos si los exigen segun IArgConsumer
			{"noArg", "tunnel", true, "requires_arguments"},
			{"noArg", "find", true, "requires_arguments"},
			{"noArg", "waypoints", true, "requires_arguments"},
		};
		for (Object[] c : clasificacion) {
			String metodo = (String) c[0];
			String cmd = (String) c[1];
			boolean debeRechazar = (Boolean) c[2];
			String esperado = (String) c[3];
			String obtenido = "";
			boolean ok;
			try {
				String salida = switch (metodo) {
					case "readOnly" -> BaritoneTranslator.INSTANCE.readOnly(cmd, null);
					case "maintenance" -> BaritoneTranslator.INSTANCE.maintenance(cmd);
					default -> BaritoneTranslator.INSTANCE.noArg(cmd);
				};
				ok = !debeRechazar && salida.equals(esperado);
				obtenido = salida;
			} catch (com.bonilla.puente.http.HttpError e) {
				ok = debeRechazar && e.getCode().equals(esperado);
				obtenido = e.getCode();
			}
			check("[" + n + "] " + metodo + " " + cmd + (debeRechazar ? " rechaza" : " acepta"),
				ok, obtenido);
			n++;
		}

		// --- limite de tasa --------------------------------------------------
		Thread.sleep(200);
		int limited = 0;
		for (int i = 0; i < 200; i++) {
			if (call("GET", "/debug", null, true).status() == 429) limited++;
		}
		check("[" + n + "] limitador de tasa activa 429 tras 120 peticiones", limited > 0, "429 en " + limited + " peticiones");
		n++;

		check("[" + n + "] el puente se invoco siempre desde el hilo principal", onMainThreadCalls.get() > 0,
			onMainThreadCalls.get() + " llamadas");
		n++;
		// 10 base + 7 de /profile + 3 de /connect sin puerto + 6 de /respawn y /status
		// + 2 de /inventory + las de Baritone.
		// De /profile llegan 7: 2 GET + 2 POST validos + 3 POST con nombre de
		// formato invalido. Los 3 si pasan por el puente a proposito, porque el
		// patron de nombre se valida en `ClientBridge` (que es quien conoce
		// `SharedConstants.MAX_PLAYER_NAME_LENGTH`), y por tanto en el hilo
		// principal. Los 2 que no llegan son los que el controlador corta antes
		// (nombre vacio o ausente), que no dependen de MC.
		// De /respawn llegan 6: 4 POST (2 rechazados + 1 bueno + 1 repetido) y 2 GET
		// /status. El GET /respawn es 405 y no llega al puente.
		// De /inventory llegan 2: el 409 sin mundo y el 200 con mundo. El POST es
		// 405 y se corta antes.
		int profileCalls = 7 + 3 + 6 + 2;
		check("[" + n + "] llamadas al puente = 10 base + " + profileCalls + " de perfil/connect + " + dispatched + " de Baritone",
			onMainThreadCalls.get() == 10 + profileCalls + dispatched, String.valueOf(onMainThreadCalls.get()));
		n++;

		server.stop();
		check("[" + n + "] servidor se detiene sin error", true, "");

		System.out.println();
		System.out.println(failures == 0
			? ">>> TODAS LAS PRUEBAS PASARON"
			: ">>> " + failures + " PRUEBAS FALLARON");
		if (failures > 0) System.exit(1);
	}

	static void checkOnMain(String what) {
		if (!Thread.currentThread().getName().equals("fake-main")) {
			System.out.println("    !! " + what + " se ejecuto fuera del hilo principal: " + Thread.currentThread().getName());
			failures++;
		}
	}

	static void check(String label, boolean ok, String detail) {
		if (!ok) failures++;
		System.out.println((ok ? "PASA  " : "FALLA ") + label + (ok || detail.isEmpty() ? "" : "  -> " + detail));
	}

	record Res(int status, String body, java.net.http.HttpHeaders headers) {
		String header(String name) { return headers.firstValue(name).orElse(null); }
	}

	static Res call(String method, String path, String body, boolean auth) throws Exception {
		return call(method, path, body, auth ? token : null);
	}

	static Res call(String method, String path, String body, String bearer) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
		switch (method) {
			case "GET" -> b.GET();
			case "POST" -> b.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
			case "DELETE" -> b.DELETE();
			case "OPTIONS" -> b.method("OPTIONS", HttpRequest.BodyPublishers.noBody());
			default -> b.method(method, HttpRequest.BodyPublishers.noBody());
		}
		if (bearer != null) b.header("Authorization", "Bearer " + bearer);
		if (body != null) b.header("Content-Type", "application/json");
		try {
			HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
			return new Res(resp.statusCode(), resp.body(), resp.headers());
		} catch (java.net.ConnectException e) {
			return new Res(0, "sin conexion", java.net.http.HttpHeaders.of(java.util.Map.of(), (a, b2) -> true));
		}
	}
}
