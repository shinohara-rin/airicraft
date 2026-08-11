package ai.moeru.airicraft.wrapper;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpBridgeTransportTest {
	private final String originalUserHome = System.getProperty("user.home");
	private final String originalBridgeStateFile = System.getProperty(BridgeStateFile.PATH_PROPERTY);

	@AfterEach
	void restoreBridgeStatePath() {
		System.setProperty("user.home", originalUserHome);
		if (originalBridgeStateFile == null) {
			System.clearProperty(BridgeStateFile.PATH_PROPERTY);
		}
		else {
			System.setProperty(BridgeStateFile.PATH_PROPERTY, originalBridgeStateFile);
		}
	}

	@Test
	void missingBridgeStateReportsMinecraftUnavailable(@TempDir Path tempDir) {
		System.setProperty(BridgeStateFile.PATH_PROPERTY, tempDir.resolve("missing.json").toString());

		BridgeUnavailableException exception = assertThrows(
			BridgeUnavailableException.class,
			() -> new HttpBridgeTransport().get("/v1/status")
		);

		assertEquals("minecraft_unavailable", exception.code());
	}

	@Test
	void staleBridgeStateIsDeletedOnConnectFailure(@TempDir Path tempDir) throws Exception {
		Path stateFile = writeBridgeState(tempDir, 1);

		BridgeUnavailableException exception = assertThrows(
			BridgeUnavailableException.class,
			() -> new HttpBridgeTransport().get("/v1/worlds")
		);

		assertEquals("minecraft_unavailable", exception.code());
		assertFalse(Files.exists(stateFile));
	}

	@Test
	void getAuthenticatesAndReadsJson(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/status", 0, 200, """
				{"available":true,"worldLoaded":true}
				""");
			writeBridgeState(tempDir, server.port());

			Map<String, Object> payload = new HttpBridgeTransport().get("/v1/status");

			assertEquals(true, payload.get("available"));
			assertEquals("GET", server.lastMethod("/v1/status"));
			assertEquals("Bearer test-token", server.lastAuthorization("/v1/status"));
		}
	}

	@Test
	void postSerializesJsonBody(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/example", 0, 200, "{\"accepted\":true}");
			writeBridgeState(tempDir, server.port());

			Map<String, Object> payload = new HttpBridgeTransport().post(
				"/v1/example",
				Map.of("name", "Sheep", "count", 2)
			);

			assertEquals(true, payload.get("accepted"));
			assertEquals("POST", server.lastMethod("/v1/example"));
			assertTrue(server.lastRequestBody("/v1/example").contains("\"name\":\"Sheep\""));
			assertTrue(server.lastRequestBody("/v1/example").contains("\"count\":2"));
		}
	}

	@Test
	void deleteSendsMethodWithoutBody(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/example", 0, 200, "{\"deleted\":true}");
			writeBridgeState(tempDir, server.port());

			Map<String, Object> payload = new HttpBridgeTransport().delete("/v1/example", null);

			assertEquals(true, payload.get("deleted"));
			assertEquals("DELETE", server.lastMethod("/v1/example"));
			assertEquals("", server.lastRequestBody("/v1/example"));
		}
	}

	@Test
	void bridgeErrorPreservesCodeAndMessage(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/example", 0, 409, """
				{"error":"already_in_world","message":"A world is already loaded"}
				""");
			writeBridgeState(tempDir, server.port());

			BridgeUnavailableException exception = assertThrows(
				BridgeUnavailableException.class,
				() -> new HttpBridgeTransport().post("/v1/example", null)
			);

			assertEquals("already_in_world", exception.code());
			assertEquals("A world is already loaded", exception.getMessage());
		}
	}

	@Test
	void defaultRequestUsesShortTimeout(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/example", 2_500, 200, "{}");
			writeBridgeState(tempDir, server.port());

			BridgeUnavailableException exception = assertThrows(
				BridgeUnavailableException.class,
				() -> new HttpBridgeTransport().get("/v1/example")
			);

			assertEquals("bridge_io_error", exception.code());
			assertTrue(exception.getMessage().contains("timed out"));
		}
	}

	@Test
	void joinRequestUsesLongTimeout(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/worlds/join", 2_500, 200, "{\"started\":true}");
			writeBridgeState(tempDir, server.port());

			Map<String, Object> payload = new HttpBridgeTransport().post(
				"/v1/worlds/join",
				Map.of("worldId", "test-world")
			);

			assertEquals(true, payload.get("started"));
		}
	}

	@Test
	void screenshotRequestUsesLongTimeoutAndDecodesPayload(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/camera/screenshot", 2_500, 200, """
				{
				  "format":"png",
				  "width":854,
				  "height":480,
				  "sourceWidth":1920,
				  "sourceHeight":1080,
				  "capturedAtMs":123456789,
				  "imageBase64":"%s"
				}
				""".formatted(Base64.getEncoder().encodeToString(new byte[]{5, 6, 7})));
			writeBridgeState(tempDir, server.port());

			CapturedImage image = CapturedImage.screenshot(
				new HttpBridgeTransport().post("/v1/camera/screenshot", null)
			);

			assertEquals(1920, image.sourceWidth());
			assertEquals(1080, image.sourceHeight());
			assertArrayEquals(new byte[]{5, 6, 7}, image.bytes());
		}
	}

	@Test
	void visionRequestUsesLongTimeoutAndDecodesPayload(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/vision/describe", 2_500, 200, """
				{"format":"text","capturedAtMs":123,"model":"gpt-4.1-mini","description":"A grassy hill."}
				""");
			writeBridgeState(tempDir, server.port());

			VisionDescriptionResult result = VisionDescriptionResult.fromBridgePayload(
				new HttpBridgeTransport().post("/v1/vision/describe", Map.of("prompt", "Describe the scene."))
			);

			assertEquals("gpt-4.1-mini", result.model());
			assertEquals("A grassy hill.", result.description());
		}
	}

	@Test
	void agentToolRequestUsesLongTimeout(@TempDir Path tempDir) throws Exception {
		try (TestBridgeServer server = TestBridgeServer.start()) {
			server.respondJson("/v1/agent/tools", 2_500, 200, "{\"accepted\":true}");
			writeBridgeState(tempDir, server.port());

			Map<String, Object> payload = new HttpBridgeTransport().post(
				"/v1/agent/tools",
				Map.of("name", "inspect_inventory", "arguments", Map.of(), "timeoutMs", 30_000)
			);

			assertEquals(true, payload.get("accepted"));
		}
	}

	@Test
	void invalidScreenshotPayloadMapsToBridgeIoError() {
		BridgeUnavailableException exception = assertThrows(
			BridgeUnavailableException.class,
			() -> CapturedImage.screenshot(Map.of("format", "png"))
		);

		assertEquals("bridge_io_error", exception.code());
		assertEquals("Bridge returned an invalid screenshot payload", exception.getMessage());
	}

	private static Path writeBridgeState(Path tempDir, int port) throws Exception {
		Path bridgeStatePath = tempDir.resolve("bridge-state.json");
		Files.writeString(bridgeStatePath, """
			{
			  "port": %d,
			  "token": "test-token",
			  "startedAtEpochMillis": 1
			}
			""".formatted(port));
		System.setProperty(BridgeStateFile.PATH_PROPERTY, bridgeStatePath.toString());
		return bridgeStatePath;
	}

	private static final class TestBridgeServer implements AutoCloseable {
		private final HttpServer server;
		private final Map<String, String> lastMethods = new java.util.concurrent.ConcurrentHashMap<>();
		private final Map<String, String> lastRequestBodies = new java.util.concurrent.ConcurrentHashMap<>();
		private final Map<String, String> lastAuthorizations = new java.util.concurrent.ConcurrentHashMap<>();

		private TestBridgeServer(HttpServer server) {
			this.server = server;
		}

		private static TestBridgeServer start() throws IOException {
			HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
			server.setExecutor(Executors.newCachedThreadPool());
			server.start();
			return new TestBridgeServer(server);
		}

		private int port() {
			return server.getAddress().getPort();
		}

		private void respondJson(String path, long delayMillis, int statusCode, String body) {
			server.createContext(path, exchange -> {
				lastMethods.put(path, exchange.getRequestMethod());
				lastRequestBodies.put(path, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
				lastAuthorizations.put(path, exchange.getRequestHeaders().getFirst("Authorization"));
				if (delayMillis > 0) {
					try {
						Thread.sleep(delayMillis);
					}
					catch (InterruptedException exception) {
						Thread.currentThread().interrupt();
					}
				}
				writeResponse(exchange, statusCode, body);
			});
		}

		private String lastMethod(String path) {
			return lastMethods.get(path);
		}

		private String lastRequestBody(String path) {
			return lastRequestBodies.get(path);
		}

		private String lastAuthorization(String path) {
			return lastAuthorizations.get(path);
		}

		@Override
		public void close() {
			server.stop(0);
		}
	}

	private static void writeResponse(HttpExchange exchange, int statusCode, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(statusCode, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}
}
