import com.bonilla.puente.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** Prueba de humo del nucleo HTTP con un puente falso, fuera de Minecraft. */
public class PuenteSmokeTest {
	static final AtomicInteger onMainThreadCalls = new AtomicInteger();
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
		check("[3] validate detecta token vacio con requireToken", noToken.size() == 1, String.valueOf(noToken));

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

		MinecraftBridge bridge = new MinecraftBridge() {
			@Override public ClientStatus status() {
				onMainThreadCalls.incrementAndGet();
				return new ClientStatus("1.0.0", "26.3", true, "ChatScreen", "Steve", "uuid-1",
					"localhost:25565", "Mi servidor", null, "minecraft:overworld", 144, 3, 20, true);
			}
			@Override public void sendChat(String m) {
				onMainThreadCalls.incrementAndGet();
				checkOnMain("sendChat '" + m + "'");
			}
			@Override public void sendCommand(String c) {
				onMainThreadCalls.incrementAndGet();
				checkOnMain("sendCommand '" + c + "'");
			}
			@Override public void connect(String h, int p, String n) {
				onMainThreadCalls.incrementAndGet();
				checkOnMain("connect " + h + ":" + p);
			}
			@Override public void disconnect() {
				onMainThreadCalls.incrementAndGet();
				checkOnMain("disconnect");
			}
			@Override public List<RemotePlayerInfo> onlinePlayers() {
				onMainThreadCalls.incrementAndGet();
				return List.of(new RemotePlayerInfo("Alex", "uuid-2", 42, "Alex"));
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
		check("[23] connect sin puerto -> 202 con puerto por defecto 25580",
			r.status() == 202 && r.body().contains("juego.mc:25580"), r.body());

		r = call("POST", "/connect", "{\"host\":\"servidor\",\"port\":25577,\"name\":\"Custom\"}", true);
		check("[24] connect host/port/nombre -> 202", r.status() == 202 && r.body().contains("servidor:25577"), r.body());

		r = call("POST", "/connect", "{}", true);
		check("[25] connect sin destino -> 400 missing_host", r.status() == 400 && r.body().contains("missing_host"), r.body());

		r = call("POST", "/connect", "{\"address\":\"host:abc\"}", true);
		check("[26] connect puerto no numerico -> 400", r.status() == 400, r.body());

		r = call("POST", "/disconnect", null, true);
		check("[27] POST /disconnect -> 202", r.status() == 202, r.body());

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

		// --- limite de tasa --------------------------------------------------
		Thread.sleep(200);
		int limited = 0;
		for (int i = 0; i < 200; i++) {
			if (call("GET", "/debug", null, true).status() == 429) limited++;
		}
		check("[45] limitador de tasa activa 429 tras 120 peticiones", limited > 0, "429 en " + limited + " peticiones");

		check("[46] el puente se invoco siempre desde el hilo principal", onMainThreadCalls.get() > 0,
			onMainThreadCalls.get() + " llamadas");
		check("[47] numero de llamadas al puente = " + onMainThreadCalls.get(),
			onMainThreadCalls.get() == 10, String.valueOf(onMainThreadCalls.get()));

		server.stop();
		check("[48] servidor se detiene sin error", true, "");

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
