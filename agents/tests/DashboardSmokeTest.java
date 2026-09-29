import com.bonilla.puente.dashboard.DashboardServer;
import com.bonilla.puente.dashboard.InstanceRegistry;
import com.bonilla.puente.http.Json;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
 *punta a punta lo que importa de verdad: que el proxy reenvia, que el token
 * llega, y sobre todo las defensas (Host, loopback y fuga del token).
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
			HttpResponse<String> added = post(http, base + "/api/instances",
				"{\"name\":\"Stub\",\"port\":" + stubPort + ",\"token\":\"secreto-123\"}");
			check("[5] se puede anadir una instancia",
				added.statusCode() == 200 && added.body().contains("\"port\":" + stubPort),
				added.statusCode() + " " + added.body());

			// El token se guarda, pero no se devuelve al navegador.
			check("[6] el token se guarda y no se filtra en el listado",
				added.body().contains("\"hasToken\":true") && !added.body().contains("secreto-123"),
				added.body());

			// --- Sondeo: ahora si esta online --------------------------------
			HttpResponse<String> list2 = get(http, base + "/api/instances");
			JsonObject stubEntry = JsonParser.parseString(list2.body()).getAsJsonObject()
				.getAsJsonObject("data").getAsJsonArray("instances").get(1).getAsJsonObject();
			check("[7] una instancia viva se marca online con su estado",
				stubEntry.get("online").getAsBoolean()
					&& stubEntry.getAsJsonObject("status").get("playerName").getAsString().equals("Bot"),
				stubEntry.toString());

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

			// --- El token se inyecta, no lo manda el navegador ---------------
			check("[10] el hub anade el Authorization del token guardado",
				stubAuth.get().equals("Bearer secreto-123"), "Authorization=" + stubAuth.get());

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

			// --- Persistencia ------------------------------------------------
			hub.stop();
			InstanceRegistry reloaded = new InstanceRegistry(file);
			int persisted = reloaded.load().size();
			check("[19] las instancias sobreviven a un reinicio del hub", persisted == 2,
				"persistidas=" + persisted);

			// --- Borrado -----------------------------------------------------
			// El hub vuelve a arrancar; como el puerto es efimero, cambia.
			hub = new DashboardServer(registry, 0);
			hubPort = hub.start();
			String base2 = "http://127.0.0.1:" + hubPort;

			HttpResponse<String> removed = http.send(
				HttpRequest.newBuilder(URI.create(base2 + "/api/instances/p" + stubPort))
					.method("DELETE", HttpRequest.BodyPublishers.noBody()).build(),
				HttpResponse.BodyHandlers.ofString());
			int after = JsonParser.parseString(get(http, base2 + "/api/instances").body())
				.getAsJsonObject().getAsJsonObject("data").getAsJsonArray("instances").size();
			check("[20] quitar una instancia la saca del listado",
				removed.statusCode() == 200 && after == 1,
				"borrado=" + removed.statusCode() + " quedan=" + after);

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
