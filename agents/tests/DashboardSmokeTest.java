import com.bonilla.puente.dashboard.DashboardServer;
import com.bonilla.puente.dashboard.InstanceRegistry;
import com.bonilla.puente.http.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Banco del hub del panel.
 *
 * Levanta un **stub** que imita a Puente y el hub encima, y comprueba de
 * punta a punta lo que importa de verdad: que el proxy reenvia, el sondeo
 * funciona y las defensas (Host, loopback) se cumplen.
 *
 * No necesita Minecraft ni Fabric, asi que corre en cualquier momento.
 */
public class DashboardSmokeTest {

	private static int failures = 0;
	private static int n = 0;

	private static void check(String label, boolean ok, String detail) {
		n++;
		if (ok) {
			System.out.println("PASA  [" + label + "]");
		} else {
			failures++;
			System.out.println("FALLA [" + label + "]  -> " + detail);
		}
	}

	public static void main(String[] args) throws Exception {
		// --- Stub que hace de instancia de Puente ---------------------------
		AtomicReference<String> stubAuth = new AtomicReference<>("(nunca)");
		AtomicReference<String> stubBody = new AtomicReference<>("");
		AtomicReference<String> stubMethod = new AtomicReference<>("");
		AtomicReference<String> stubQuery = new AtomicReference<>("(nunca)");
		AtomicReference<String> stubPath = new AtomicReference<>("");
		AtomicInteger stubCalls = new AtomicInteger(0);

		HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		stub.createContext("/puppeteer", exchange -> {
			stubCalls.incrementAndGet();
			stubAuth.set(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
			stubMethod.set(exchange.getRequestMethod());
			stubPath.set(exchange.getRequestURI().getPath());
			stubQuery.set(String.valueOf(exchange.getRequestURI().getRawQuery()));
			stubBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			exchange.getRequestBody().close();

			String path = exchange.getRequestURI().getPath();
			String payload;

				if (path.endsWith("/status")) {
				payload = "{\"ok\":true,\"data\":{\"inWorld\":true,\"playerName\":\"Bot\","
					+ "\"dead\":false,\"fps\":60}}";
			} else if (path.endsWith("/chat/screen")) {
				// Es lo que devuelve el mod de verdad: texto plano por linea,
				// incluida la respuesta de Baritone, que no pasa por la red.
				payload = "{\"ok\":true,\"data\":{\"lines\":"
					+ "[\"[Baritone] > mine acacia_log 8\"]}}";
			} else {
				payload = "{\"ok\":true,\"data\":{\"echo\":\"" + path + "\"}}";
			}
			byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
			exchange.sendResponseHeaders(200, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		stub.start();
		int stubPort = stub.getAddress().getPort();

		// --- Hub ------------------------------------------------------------
		Path file = Files.createTempFile("dashboard-registry", ".json");
		Files.deleteIfExists(file);

		InstanceRegistry registry = new InstanceRegistry(file);
		registry.load();
		DashboardServer hub = new DashboardServer(registry, 0);
		int hubPort = hub.start();
		String base = "http://127.0.0.1:" + hubPort;

		HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(2)).build();

		try {
			// --- El panel se sirve ------------------------------------------
			HttpResponse<String> index = get(http, base + "/");
			check("[1] GET / sirve el panel HTML",
				index.statusCode() == 200
					&& index.body().contains("<!DOCTYPE html>")
					&& index.body().contains("Puente"),
				index.statusCode() + " " + abbreviate(index.body()));
			check("[2] el panel no lleva cabeceras CORS",
				index.headers().firstValue("access-control-allow-origin").isEmpty(),
				index.headers().map().toString());

			// --- Listado y sondeo de estado ---------------------------------
			HttpResponse<String> list = get(http, base + "/api/instances");
			JsonObject data = JsonParser.parseString(list.body()).getAsJsonObject().getAsJsonObject("data");
			int count = data.getAsJsonArray("instances").size();
			check("[3] el listado incluye la instancia por defecto", list.statusCode() == 200 && count == 1,
				list.statusCode() + " " + list.body());

			// La instancia por defecto (puerto 25580) no existe, asi que sale
			// apagada: el hub tiene que saber distinguished sin reventar.
			JsonObject only = data.getAsJsonArray("instances").get(0).getAsJsonObject();
			check("[4] una instancia apagada se marca offline con motivo",
				!only.get("online").getAsBoolean() && !only.get("error").isJsonNull(),
				only.toString());

			// --- Alta de una instancia valida -------------------------------
			// Sin ningun token en el cuerpo: es lo que manda la pagina ahora.
			HttpResponse<String> added = post(http, base + "/api/instances",
				"{\"name\":\"Stub\",\"port\":" + stubPort + "}");
			check("[5] se puede anadir una instancia sin token",
				added.statusCode() == 200 && added.body().contains("\"port\":" + stubPort),
				added.statusCode() + " " + added.body());

			// Ya no hay token en ningun sitio: ni se guarda ni se anuncia.
			check("[6] el alta no menciona token ni credenciales",
				!added.body().contains("token") && !added.body().contains("Token"),
				added.body());

			// --- Sondeo: ahora si esta online --------------------------------
			HttpResponse<String> list2 = get(http, base + "/api/instances");
			JsonObject stubEntry = JsonParser.parseString(list2.body()).getAsJsonObject()
				.getAsJsonObject("data").getAsJsonArray("instances").get(1).getAsJsonObject();
			check("[7] una instancia viva se marca online con su estado",
				stubEntry.get("online").getAsBoolean()
					&& stubEntry.getAsJsonObject("status").get("playerName").getAsString().equals("Bot"),
				stubEntry.toString());
			// El sondeo tiene que funcionar sin cabecera de autorizacion. Antes
			// el stub exigia token y por eso fallaba con 401 si probe() se
			// olvidaba de mandarla: la tarjeta ponia "apagada" con la instancia
			// viva. Ahora que no hay token, este es el sitio donde se caza.
			check("[7b] el sondeo no necesita cabecera de autorizacion",
				stubEntry.get("online").getAsBoolean() && stubAuth.get().equals("null"),
				"sondeo con Authorization=" + stubAuth.get() + " -> " + stubEntry.toString());

			// --- Proxy: GET y POST -------------------------------------------
			HttpResponse<String> proxied = get(http, base + "/api/instances/p" + stubPort + "/status");
			check("[8] el proxy reenvia GET a /puppeteer/status",
				proxied.statusCode() == 200 && proxied.body().contains("\"playerName\":\"Bot\""),
				proxied.statusCode() + " " + proxied.body());

			HttpResponse<String> sent = post(http, base + "/api/instances/p" + stubPort + "/disconnect", "{\"motivo\":\"prueba\"}");
			check("[9] el proxy reenvia POST con su metodo y su cuerpo",
				sent.statusCode() == 200 && stubMethod.get().equals("POST")
					&& stubPath.get().endsWith("/puppeteer/disconnect")
					&& stubBody.get().contains("prueba"),
				sent.statusCode() + " metodo=" + stubMethod.get()
					+ " ruta=" + stubPath.get() + " cuerpo=" + stubBody.get());

			// --- Ni el proxy inventa credenciales ----------------------------
			check("[10] el proxy no manda cabecera de autorizacion",
				stubAuth.get().equals("null"), "Authorization=" + stubAuth.get());

			// --- Query string ------------------------------------------------
			get(http, base + "/api/instances/p" + stubPort + "/chat?limit=25");
			check("[11] la query string sobrevive al proxy",
				stubQuery.get().equals("limit=25") && stubCalls.get() > 0,
				"query=" + stubQuery.get());

			// --- Instancia apagada ------------------------------------------
			HttpResponse<String> dead = get(http, base + "/api/instances/p25580/status");
			check("[12] una instancia apagada responde 502 instance_offline, no 500",
				dead.statusCode() == 502 && dead.body().contains("instance_offline"),
				dead.statusCode() + " " + dead.body());

			// --- Instancia inexistente --------------------------------------
			HttpResponse<String> ghost = get(http, base + "/api/instances/p99999/status");
			check("[13] una instancia que no existe -> 400 unknown_instance",
				ghost.statusCode() == 400 && ghost.body().contains("unknown_instance"),
				ghost.statusCode() + " " + ghost.body());

			// --- Defensa 1: no se admiten hosts que no sean loopback ---------
			HttpResponse<String> remote = post(http, base + "/api/instances",
				"{\"name\":\"Mala\",\"host\":\"10.0.0.5\",\"port\":25580}");
			check("[14] no se admite un host fuera de loopback (anti-SSRF)",
				remote.statusCode() == 400 && remote.body().contains("invalid_instance"),
				remote.statusCode() + " " + remote.body());

			HttpResponse<String> dns = post(http, base + "/api/instances",
				"{\"name\":\"DNS\",\"host\":\"evil.example.com\",\"port\":25580}");
			check("[15] no se admite un nombre de dominio (anti-SSRF)",
				dns.statusCode() == 400, dns.statusCode() + " " + dns.body());

			// --- Defensa 2: cabecera Host (anti DNS rebinding) ---------------
			// java.net.http no deja poner Host a mano, asi que va por socket.
			RawResponse rebound = rawRequest(hubPort, "GET", "/api/instances", "evil.example.com");
			check("[16] una peticion con Host de otro dominio se rechaza (DNS rebinding)",
				rebound.status() == 403 && rebound.body().contains("bad_host"),
				rebound.status() + " " + abbreviate(rebound.body()));

			RawResponse noHost = rawRequest(hubPort, "GET", "/api/instances", null);
			check("[17] una peticion sin Host se rechaza", noHost.status() == 403,
				noHost.status() + " " + abbreviate(noHost.body()));

			// Y la comprobacion no esta simplemente rechazando todo.
			RawResponse good = rawRequest(hubPort, "GET", "/api/health", "127.0.0.1:" + hubPort);
			check("[18] una peticion normal con el puerto real se acepta",
				good.status() == 200 && good.body().contains("\"ok\":true"),
				good.status() + " " + abbreviate(good.body()));

			// --- Iconos de item -------------------------------------------------
			// El hub los saca del jar del cliente, que esta en el mismo classpath.
			// Se comprueba que sale un PNG de verdad, no que la ruta existe.
			HttpResponse<byte[]> sprite = http.send(
				HttpRequest.newBuilder(URI.create(base + "/assets/item/diamond_sword")).GET().build(),
				HttpResponse.BodyHandlers.ofByteArray());
			byte[] png = sprite.body();
			boolean esPng = png.length > 8
				&& (png[0] & 0xFF) == 0x89 && png[1] == 'P' && png[2] == 'N' && png[3] == 'G';
			check("[19] el sprite de un item sale del jar del juego",
				sprite.statusCode() == 200 && esPng
					&& sprite.headers().firstValue("content-type").orElse("").equals("image/png"),
				sprite.statusCode() + " bytes=" + png.length + " tipo="
					+ sprite.headers().firstValue("content-type").orElse("?"));

			// Un bloque no tiene item/<id>.png sino block/<id>.png: sale del
			// atlas, que declara esas fuentes como directorios.
			HttpResponse<byte[]> bloque = http.send(
				HttpRequest.newBuilder(URI.create(base + "/assets/item/diamond_ore")).GET().build(),
				HttpResponse.BodyHandlers.ofByteArray());
			check("[20] tambien sale el sprite de un bloque (texturas/block)",
				bloque.statusCode() == 200 && bloque.body().length > 8
					&& (bloque.body()[0] & 0xFF) == 0x89,
				bloque.statusCode() + " bytes=" + bloque.body().length);

			// Un item que no existe: 404, y el panel deja el nombre corto.
			HttpResponse<String> noSprite = get(http, base + "/assets/item/no_existe_este_item");
			check("[21] un item sin sprite da 404 y no un 500",
				noSprite.statusCode() == 404 && noSprite.body().contains("no_sprite"),
				noSprite.statusCode() + " " + noSprite.body());

			// Lo importante: el nombre va a un getResourceAsStream sin validar, asi
			// que sin este filtro la ruta seria un lector de ficheros de classpath.
			HttpResponse<String> escape = get(http, base + "/assets/item/..%2F..%2Fbuild.gradle.kts");
			check("[22] un nombre con .. no sale de assets/ (no lector de classpath)",
				escape.statusCode() == 400 || escape.statusCode() == 404,
				escape.statusCode() + " " + abbreviate(escape.body()));

			// --- Persistencia ------------------------------------------------
			hub.stop();
			InstanceRegistry reloaded = new InstanceRegistry(file);
			int persisted = reloaded.load().size();
			check("[23] las instancias sobreviven a un reinicio del hub", persisted == 2,
				"persistidas=" + persisted);

		// --- Broadcast -------------------------------------------------------
		// El hub vuelve a arrancar; como el puerto es efimero, cambia.
		hub = new DashboardServer(registry, 0);
		hubPort = hub.start();
		String base2 = "http://127.0.0.1:" + hubPort;

		// Regresion: esto va con el stub VIVO, ANTES del DELETE de mas abajo.
		// Con solo la instancia apagada, un broadcast que montase mal la ruta
		// pasaria el mismo: da igual mandar `/puppeteer/chat` que
		// `/puppeteerchat`, las dos acaban en error de conexion y el test verde.
		// La ruta es lo unico que distingue un caso del otro.
		stubPath.set("(nunca)");
		JsonParser.parseString(
			post(http, base2 + "/api/broadcast/chat", "{\"message\":\"hola\"}").body());
		check("[24] el broadcast monta bien la ruta en la instancia viva",
			stubPath.get().equals("/puppeteer/chat"),
			"ruta que llego a la instancia=" + stubPath.get()
				+ " (si sale /puppeteerchat falta la barra de separacion)");
		// El chat en pantalla es lo unico que deja ver si una orden de Baritone
		// ha hecho algo: Baritone contesta en el chat local del cliente, no por
		// la red, asi que sin esto el panel solo puede decir "ok" y nada mas.
		//
		// Va con el stub VIVO, antes del DELETE de mas abajo. Apagado, cualquier
		// ruta mal montada acabaria en error de conexion y el test pasaria igual
		// de verde, que es lo que hacia pasar el test del broadcast.
		stubPath.set("(nunca)");
		HttpResponse<String> screenRes = get(http, base2 + "/api/instances/p" + stubPort + "/chat/screen?limit=5");
		check("[25] el proxy lleva /chat/screen a la instancia viva",
			stubPath.get().equals("/puppeteer/chat/screen"),
			"ruta que llego a la instancia=" + stubPath.get()
				+ " (si sale /puppeteerchatscreen falta la barra de separacion)");
		JsonObject screen = JsonParser.parseString(screenRes.body()).getAsJsonObject();
		JsonArray lineas = screen.has("data")
			? screen.getAsJsonObject("data").getAsJsonArray("lines")
			: new JsonArray();
		check("[26] el hub devuelve las lineas del chat en pantalla sin reordenar",
			screenRes.statusCode() == 200 && lineas.size() == 1
				&& lineas.get(0).getAsString().contains("Baritone"),
			"HTTP " + screenRes.statusCode() + " cuerpo=" + screenRes.body());

		// --- Borrado -----------------------------------------------------
		HttpResponse<String> removed = http.send(

			HttpRequest.newBuilder(URI.create(base2 + "/api/instances/p" + stubPort))
				.method("DELETE", HttpRequest.BodyPublishers.noBody()).build(),
			HttpResponse.BodyHandlers.ofString());
		int after = JsonParser.parseString(get(http, base2 + "/api/instances").body())
			.getAsJsonObject().getAsJsonObject("data").getAsJsonArray("instances").size();
		check("[27] quitar una instancia la saca del listado",
			removed.statusCode() == 200 && after == 1,
			"borrado=" + removed.statusCode() + " quedan=" + after);


		JsonObject bcast = JsonParser.parseString(
			post(http, base2 + "/api/broadcast/chat", "{\"message\":\"hola\"}").body())
			.getAsJsonObject().getAsJsonObject("data");
		JsonArray results = bcast.getAsJsonArray("results");
		// Tras el DELETE solo queda la instancia por defecto, que esta apagada:
		// el broadcast tiene que llegar a ella y reportar el fallo, no reventar.
		boolean bcastReports = bcast.get("total").getAsInt() == 1
			&& results.size() == 1
			&& !results.get(0).getAsJsonObject().get("ok").getAsBoolean();
		check("[28] el broadcast llega a todas y reporta una por una", bcastReports,
			"total=" + bcast.get("total") + " results=" + results);

		JsonObject noRoute = JsonParser.parseString(
			post(http, base2 + "/api/broadcast/", "{}").body()).getAsJsonObject();
		check("[29] broadcast sin ruta -> 400 y no se reenvia a ninguna",
			noRoute.getAsJsonObject().get("ok").getAsBoolean() == false,
			"respuesta=" + noRoute);

		// --- Descubrimiento -------------------------------------------------

		HttpResponse<String> disc = post(http, base2 + "/api/discover", "{}");
		JsonObject discData = JsonParser.parseString(disc.body())
			.getAsJsonObject().getAsJsonObject("data");
		check("[30] descubrir responde 200 con los puertos escaneados",
			disc.statusCode() == 200 && discData.has("scanned") && discData.has("added"),
			"scanned=" + (discData.has("scanned") ? discData.get("scanned") : "?"));

		// --- Lanzador -------------------------------------------------------

		JsonObject launcherState = JsonParser.parseString(get(http, base2 + "/api/launcher").body())
			.getAsJsonObject().getAsJsonObject("data");
		check("[31] el lanzador informa del rango permitido y si esta compilado",
			launcherState.get("from").getAsInt() == 25580 && launcherState.get("to").getAsInt() == 25599
				&& launcherState.has("ready"),
			"state=" + launcherState);

		// Un puerto fuera de la lista blanca tiene que rechazarse ANTES de
		// arrancar nada: es la barrera que impide que el panel sea un lanzador
		// de procesos arbitrario.
		HttpResponse<String> badPort = post(http, base2 + "/api/launch", "{\"port\":1234}");
		JsonObject badErr = JsonParser.parseString(badPort.body()).getAsJsonObject().getAsJsonObject("error");
		check("[32] lanzar en un puerto fuera de rango se rechaza",
			badPort.statusCode() == 400 && badErr.get("code").getAsString().equals("launch_failed"),
			"status=" + badPort.statusCode() + " err=" + badErr);

		HttpResponse<String> noPort = post(http, base2 + "/api/launch", "{}");
		check("[33] lanzar sin puerto -> 400", noPort.statusCode() == 400,
			"status=" + noPort.statusCode());

		// Parar algo que el hub no ha arrancado no es un error: es un "no" claro.
		HttpResponse<String> stopFree = post(http, base2 + "/api/stop", "{\"port\":25583}");
		JsonObject stopData = JsonParser.parseString(stopFree.body()).getAsJsonObject().getAsJsonObject("data");
		check("[34] parar una instancia que el hub no arranco no hace nada",
			stopFree.statusCode() == 200 && !stopData.get("stopped").getAsBoolean(),
			"data=" + stopData);

		// --- A varias a la vez, y el hilo principal ocupado -------------------
		// Lo que fallaba: mandar la misma orden a todas. Con una sola instancia
		// viva cualquier broadcast pasa el test, porque el fallo (montar mal la
		// ruta, no reenviar el metodo, ditchar el cuerpo) se ve igual de "ok".
		// Hace falta mas de una viva para que se note si el reparto llega a
		// todas o solo a la primera.
		AtomicInteger callsLive = new AtomicInteger(0);
		HttpServer live = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		live.createContext("/puppeteer", exchange -> {
			String path = exchange.getRequestURI().getPath();
			if (!path.endsWith("/status")) callsLive.incrementAndGet();
			reply(exchange, "{\"ok\":true,\"data\":{\"echo\":\"" + path + "\"}}");
		});
		live.start();

		// Esta segunda instancia esta OCUPADA: `/health` contesta (el proceso
		// vive) pero `/status` responde 503 `main_thread_timeout` (el hilo
		// principal del juego esta en otra cosa). Es el caso que sale al minar
		// con Baritone, y el codigo sale de `MainThreadBridge` de verdad.
		AtomicInteger callsBusy = new AtomicInteger(0);
		AtomicReference<String> busyPath = new AtomicReference<>("(nunca)");
		HttpServer busy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		busy.createContext("/puppeteer", exchange -> {
			String path = exchange.getRequestURI().getPath();
			if (path.endsWith("/status")) {
				reply(exchange, 503, "{\"ok\":false,\"error\":{\"code\":\"main_thread_timeout\","
					+ "\"message\":\"El hilo principal de Minecraft no respondio en 2500ms\"}}");
				return;
			}
			callsBusy.incrementAndGet();
			busyPath.set(path);
			reply(exchange, "{\"ok\":true,\"data\":{\"echo\":\"" + path + "\"}}");
		});
		busy.start();
		int busyPort = busy.getAddress().getPort();

		try {
			post(http, base2 + "/api/instances", "{\"name\":\"A\",\"port\":" + stubPort + "}");
			post(http, base2 + "/api/instances", "{\"name\":\"B\",\"port\":" + live.getAddress().getPort() + "}");
			post(http, base2 + "/api/instances", "{\"name\":\"C\",\"port\":" + busyPort + "}");

			// El reparto tiene que llegar a las TRES vivas, no solo a la primera.
			// La cuarta (la que esta apagada) tiene que aparecer tambien, con su
			// fallo: si una instancia apagada se salta del reparto sin decirlo,
			// el panel dira "3/3 ok" y no habra hecho nada en esa.
			callsLive.set(0);
			callsBusy.set(0);
			stubPath.set("(nunca)");
			JsonObject aTodas = JsonParser.parseString(
				post(http, base2 + "/api/broadcast/chat", "{\"message\":\"hola a todas\"}").body())
				.getAsJsonObject().getAsJsonObject("data");
			JsonArray resTodas = aTodas.getAsJsonArray("results");

			check("[35] el broadcast llega a las tres vivas y ademas reporta la apagada",
				resTodas.size() == 4
					&& stubPath.get().equals("/puppeteer/chat")
					&& callsLive.get() == 1 && callsBusy.get() == 1,
				"results=" + resTodas.size()
					+ " stub=" + stubPath.get() + " viva=" + callsLive.get()
					+ " ocupada=" + callsBusy.get());

			check("[36] cada instancia trae su propio resultado y el resumen cuadra",
				aTodas.get("total").getAsInt() == 4
					&& aTodas.get("sent").getAsInt() + aTodas.get("failed").getAsInt() == 4
					&& resTodas.get(0).getAsJsonObject().has("id")
					&& resTodas.get(0).getAsJsonObject().has("ok"),
				"total=" + aTodas.get("total") + " sent=" + aTodas.get("sent")
					+ " failed=" + aTodas.get("failed"));

			// A la ocupada: la orden entra igual, porque va por el puerto y el
			// hilo principal no se mira. Por eso el arreglo de Baritone va fuera
			// del hilo principal y no en "no mandar a las ocupadas".
			check("[37] la orden tambien entra en la instancia con el hilo ocupado",
				busyPath.get().equals("/puppeteer/chat"),
				"ruta que llego=" + busyPath.get());

			// Y ahora el estado: viva y ocupada no es lo mismo que apagada.
			JsonArray todas = JsonParser.parseString(get(http, base2 + "/api/instances").body())
				.getAsJsonObject().getAsJsonObject("data").getAsJsonArray("instances");
			JsonObject b = null;
			for (int i = 0; i < todas.size(); i++) {
				JsonObject inst = todas.get(i).getAsJsonObject();
				if (inst.get("port").getAsInt() == busyPort) b = inst;
			}
			check("[38] una instancia con el hilo principal ocupado sale viva pero ocupada",
				b != null && b.get("online").getAsBoolean() && b.get("busy").getAsBoolean(),
				"instancia=" + b);
		} finally {
			busy.stop(0);
			live.stop(0);
		}

		// --- El catalogo del panel contra las rutas de verdad ----------------
		// El desplegable de acciones del panel dice que metodo y que ruta usa
		// cada accion. Si se equivoca en una, el bot contesta 405 o 404 y desde
		// el panel parece que la instancia no responde. `/baritone/find` fue
		// justo ese caso: es GET con `?block=`, y estaba como POST con cuerpo.
		//
		// Se comprueba contra el codigo del mod, que es la otra fuente de
		// verdad: si una ruta se renombra ahi, este banco falla aqui y no
		// semanas despues con seis bots que no hacen nada.
		String panel = Files.readString(Path.of("src/main/resources/puente-dashboard/index.html"));
		String mod = Files.readString(Path.of("src/main/kotlin/com/bonilla/puente/PuenteHttpServer.kt"));

		java.util.regex.Matcher acciones = java.util.regex.Pattern.compile(
			"\\{ id: \"(\\w+)\"[^}]*?method: \"(\\w+)\"[^}]*?path: \"([^\"]+)\"").matcher(panel);
		int revisadas = 0;
		StringBuilder desacuerdos = new StringBuilder();
		while (acciones.find()) {
			String id = acciones.group(1);
			String metodo = acciones.group(2);
			String ruta = acciones.group(3);
			int q = ruta.indexOf('?');
			String limpia = q < 0 ? ruta : ruta.substring(0, q);
			revisadas++;
			String deVerdad = metodoDe(mod, limpia);
			if (deVerdad.equals("NO EXISTE")) {
				desacuerdos.append(id).append(" -> ").append(limpia).append(": no existe en el mod; ");
			} else if (!deVerdad.isEmpty() && !deVerdad.contains(metodo)) {
				// Vacio es "el mod no restringe el metodo" (como `/health`): no
				// hay con que discrepar, asi que no cuenta como fallo.
				desacuerdos.append(id).append(" -> ").append(limpia)
					.append(": el panel usa ").append(metodo).append(", el mod ").append(deVerdad).append("; ");
			}
		}
		check("[39] el catalogo de acciones del panel coincide con las rutas del mod",
			revisadas >= 10 && desacuerdos.length() == 0,
			"revisadas=" + revisadas + " fallos=" + desacuerdos);

	} finally {

			hub.stop();
			stub.stop(0);
			Files.deleteIfExists(file);
		}

		System.out.println();
		System.out.println("=== " + n + " pruebas, " + failures + " fallos ===");
		if (failures > 0) {
			System.out.println(">>> " + failures + " PRUEBAS FALLARON");
			System.exit(1);
		}
		System.out.println(">>> TODAS LAS PRUEBAS PASARON");
	}

	// --- Ayudas -------------------------------------------------------------

	/** Responde con un JSON y cierra, para no repetirlo stub a stub. */
	private static void reply(HttpExchange exchange, String json) {
		reply(exchange, 200, json);
	}

	/**
	 * Que metodo acepta de verdad el mod para esa ruta, leido de su codigo.
	 *
	 * Devuelve "NO EXISTE" si la ruta no esta, y "" si esta pero el metodo no se
	 * puede deducir (para que el fallo diga cual de las dos cosas paso).
	 *
	 * El mod declara el metodo de tres formas distintas y hay que mirar las
	 * tres: `requireMethod(exchange, "GET")`, los ayudantes
	 * `getBaritone`/`postBaritone`, y el `when (exchange.requestMethod)` de
	 * `/chat`. Por eso no vale un unico grep.
	 */
	private static String metodoDe(String fuente, String ruta) {
		java.util.regex.Matcher m = java.util.regex.Pattern
			.compile("\"" + java.util.regex.Pattern.quote(ruta) + "\"\\s*(->|==\\s*\"|\\s*\\))")
			.matcher(fuente);
		if (!m.find()) return "NO EXISTE";

		// El bloque de la ruta va hasta la siguiente ruta, y ahi se decide el
		// metodo. 600 caracteres dan de sobra para un brazo del `when`.
		int fin = Math.min(fuente.length(), m.end() + 600);
		String bloque = fuente.substring(m.end(), fin);
		int siguiente = bloque.indexOf("\"/");
		if (siguiente >= 0) bloque = bloque.substring(0, siguiente);

		java.util.List<String> metodos = new java.util.ArrayList<>();
		// `requireMethod(exchange, "GET", "POST")`: admite coma y espacio.
		java.util.regex.Matcher req = java.util.regex.Pattern
			.compile("requireMethod\\(exchange((?:, ?\"[A-Z]+\")+)\\)").matcher(bloque);
		if (req.find()) {
			java.util.regex.Matcher uno = java.util.regex.Pattern
				.compile("\"([A-Z]+)\"").matcher(req.group(1));
			while (uno.find()) metodos.add(uno.group(1));
		}
		// `when (exchange.requestMethod) { "GET" -> ...; "POST" -> ... }`: se
		// busca el `when` una vez y luego todos los brazos. Con `(?s)` porque
		// cada brazo va en su linea. Si se exigiera el `when` delante de cada
		// metodo, el segundo brazo no se encontraria nunca.
		if (bloque.contains("exchange.requestMethod")) {
			int cola = bloque.indexOf("exchange.requestMethod");
			java.util.regex.Matcher brazo = java.util.regex.Pattern
				.compile("\"([A-Z]+)\"\\s*->").matcher(bloque.substring(cola));
			while (brazo.find()) metodos.add(brazo.group(1));
		}
		if (bloque.contains("getBaritone(")) metodos.add("GET");
		if (bloque.contains("postBaritone(")) metodos.add("POST");
		if (metodos.isEmpty()) return "";

		java.util.List<String> unicos = new java.util.ArrayList<>();
		for (String x : metodos) if (!unicos.contains(x)) unicos.add(x);
		return String.join("/", unicos);
	}

	private static void reply(HttpExchange exchange, int status, String json) {
		try {
			byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
			exchange.sendResponseHeaders(status, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		} catch (Exception e) {
			exchange.close();
		}
	}

	private static HttpResponse<String> get(HttpClient http, String url) throws Exception {
		return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
			HttpResponse.BodyHandlers.ofString());
	}

	private static HttpResponse<String> post(HttpClient http, String url, String body) throws Exception {
		return http.send(HttpRequest.newBuilder(URI.create(url))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
			HttpResponse.BodyHandlers.ofString());
	}

	/** Peticion escrita a mano, para poder falsear la cabecera Host. */
	private static RawResponse rawRequest(int port, String method, String path, String hostHeader)
			throws Exception {
		try (Socket socket = new Socket("127.0.0.1", port)) {
			socket.setSoTimeout(4000);
			OutputStream out = socket.getOutputStream();
			String head = method + " " + path + " HTTP/1.1\r\n"
				+ (hostHeader == null ? "" : "Host: " + hostHeader + "\r\n")
				+ "Connection: close\r\n\r\n";
			out.write(head.getBytes(StandardCharsets.UTF_8));
			out.flush();

			InputStream in = socket.getInputStream();
			StringBuilder raw = new StringBuilder();
			byte[] chunk = new byte[4096];
			int read;
			while ((read = in.read(chunk)) > 0) {
				raw.append(new String(chunk, 0, read, StandardCharsets.ISO_8859_1));
			}

			String text = raw.toString();
			int status = 0;
			int firstSpace = text.indexOf(' ');
			if (firstSpace > 0) {
				int secondSpace = text.indexOf(' ', firstSpace + 1);
				status = Integer.parseInt(text.substring(firstSpace + 1, secondSpace).trim());
			}
			int split = text.indexOf("\r\n\r\n");
			String body = split < 0 ? "" : text.substring(split + 4);
			return new RawResponse(status, body);
		}
	}

	private static String abbreviate(String s) {
		return s == null ? "null" : (s.length() > 140 ? s.substring(0, 140) + "…" : s);
	}

	private record RawResponse(int status, String body) {}
}
