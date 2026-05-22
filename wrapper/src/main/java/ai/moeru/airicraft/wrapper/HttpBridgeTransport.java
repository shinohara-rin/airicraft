package ai.moeru.airicraft.wrapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

final class HttpBridgeTransport implements MinecraftTransport {
	private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(2);
	private static final Duration JOIN_REQUEST_TIMEOUT = Duration.ofSeconds(15);
	private static final Duration RELOAD_REQUEST_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration SCREENSHOT_REQUEST_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration VISION_REQUEST_TIMEOUT = Duration.ofSeconds(20);
	private static final Duration DEBUG_COMPACTION_REQUEST_TIMEOUT = Duration.ofSeconds(45);
	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
	private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private final HttpClient httpClient = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(1))
		.build();

	@Override
	public Map<String, Object> getStatus() {
		try {
			return get("/v1/status");
		}
		catch (BridgeUnavailableException exception) {
			return Map.of(
				"available", false,
				"bridgeAvailable", false,
				"worldLoaded", false,
				"sessionState", "minecraft_unavailable",
				"state", "minecraft_unavailable",
				"message", "Minecraft bridge is not active"
			);
		}
	}

	@Override
	public Map<String, Object> reload() {
		return send("POST", "/v1/reload", null);
	}

	@Override
	public Map<String, Object> getFocus() {
		return get("/v1/focus");
	}

	@Override
	public Map<String, Object> getWorldSnapshot(Integer x, Integer y, Integer z, int radius) {
		StringBuilder path = new StringBuilder("/v1/world-snapshot?radius=").append(radius);
		if (x != null && y != null && z != null) {
			path.append("&x=").append(x).append("&y=").append(y).append("&z=").append(z);
		}
		return get(path.toString());
	}

	@Override
	public Map<String, Object> listNearbyEntities() {
		return get("/v1/player/nearby-entities");
	}

	@Override
	public CapturedImage captureScreenshot() {
		Map<String, Object> payload = send("POST", "/v1/camera/screenshot", null);
		try {
			return new CapturedImage(
				Base64.getDecoder().decode(requiredString(payload, "imageBase64")),
				requiredString(payload, "format"),
				requiredInt(payload, "width"),
				requiredInt(payload, "height"),
				requiredInt(payload, "sourceWidth"),
				requiredInt(payload, "sourceHeight"),
				requiredLong(payload, "capturedAtMs")
			);
		}
		catch (IllegalArgumentException exception) {
			throw new BridgeUnavailableException("bridge_io_error", "Bridge returned an invalid screenshot payload");
		}
	}

	@Override
	public VisionDescriptionResult describeVision(String prompt) {
		Map<String, Object> payload = send(
			"POST",
			"/v1/vision/describe",
			prompt == null || prompt.isBlank() ? null : Map.of("prompt", prompt)
		);
		return new VisionDescriptionResult(
			requiredString(payload, "format"),
			requiredLong(payload, "capturedAtMs"),
			requiredString(payload, "model"),
			requiredString(payload, "description")
		);
	}

	@Override
	public Map<String, Object> mapStatus() {
		return get("/v1/map/status");
	}

	@Override
	public Map<String, Object> listMapWaypoints(String providerId, String dimension) {
		StringBuilder path = new StringBuilder("/v1/map/waypoints");
		String separator = "?";
		if (providerId != null && !providerId.isBlank()) {
			path.append(separator).append("provider=").append(URLEncoder.encode(providerId, java.nio.charset.StandardCharsets.UTF_8));
			separator = "&";
		}
		if (dimension != null && !dimension.isBlank()) {
			path.append(separator).append("dimension=").append(URLEncoder.encode(dimension, java.nio.charset.StandardCharsets.UTF_8));
		}
		return get(path.toString());
	}

	@Override
	public Map<String, Object> setMapWaypoint(Map<String, Object> request) {
		return send("POST", "/v1/map/waypoints", request);
	}

	@Override
	public Map<String, Object> deleteMapWaypoint(String waypointId) {
		return send("DELETE", "/v1/map/waypoints?id=" + URLEncoder.encode(waypointId, java.nio.charset.StandardCharsets.UTF_8), null);
	}

	@Override
	public CapturedImage captureMapImage(Map<String, Object> request) {
		Map<String, Object> payload = send("POST", "/v1/map/image", request);
		try {
			int width = requiredInt(payload, "width");
			int height = requiredInt(payload, "height");
			return new CapturedImage(
				Base64.getDecoder().decode(requiredString(payload, "imageBase64")),
				requiredString(payload, "format"),
				width,
				height,
				width,
				height,
				requiredLong(payload, "capturedAtMs")
			);
		}
		catch (IllegalArgumentException exception) {
			throw new BridgeUnavailableException("bridge_io_error", "Bridge returned an invalid map image payload");
		}
	}

	@Override
	public Map<String, Object> listWorlds() {
		return get("/v1/worlds");
	}

	@Override
	public Map<String, Object> joinWorld(String worldId) {
		return send("POST", "/v1/worlds/join", Map.of("worldId", worldId));
	}

	@Override
	public Map<String, Object> listServers() {
		return get("/v1/servers");
	}

	@Override
	public Map<String, Object> joinServer(String serverId) {
		return send("POST", "/v1/servers/join", Map.of("serverId", serverId));
	}

	@Override
	public Map<String, Object> lookAt(double x, double y, double z) {
		return send("POST", "/v1/player/look-at", Map.of(
			"x", x,
			"y", y,
			"z", z
		));
	}

	@Override
	public Map<String, Object> attackEntity(String uuid, String name, String entityTypeId, String mode) {
		return send("POST", "/v1/player/attack-entity", entitySelectorBody(uuid, name, entityTypeId, null, mode));
	}

	@Override
	public Map<String, Object> useEntity(String uuid, String name, String entityTypeId, String itemId) {
		return send("POST", "/v1/player/use-entity", entitySelectorBody(uuid, name, entityTypeId, itemId, null));
	}

	@Override
	public Map<String, Object> createBlockHighlight(int x, int y, int z, String color, Long durationMs, String overlayText) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("kind", "block");
		body.put("x", x);
		body.put("y", y);
		body.put("z", z);
		if (color != null) {
			body.put("color", color);
		}
		if (durationMs != null) {
			body.put("durationMs", durationMs);
		}
		if (overlayText != null && !overlayText.isBlank()) {
			body.put("overlayText", overlayText);
		}
		return send("POST", "/v1/highlights", body);
	}

	@Override
	public Map<String, Object> createRegionHighlight(
		int x1,
		int y1,
		int z1,
		int x2,
		int y2,
		int z2,
		String color,
		Long durationMs,
		String overlayText
	) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("kind", "region");
		body.put("x1", x1);
		body.put("y1", y1);
		body.put("z1", z1);
		body.put("x2", x2);
		body.put("y2", y2);
		body.put("z2", z2);
		if (color != null) {
			body.put("color", color);
		}
		if (durationMs != null) {
			body.put("durationMs", durationMs);
		}
		if (overlayText != null && !overlayText.isBlank()) {
			body.put("overlayText", overlayText);
		}
		return send("POST", "/v1/highlights", body);
	}

	@Override
	public Map<String, Object> listHighlights() {
		return get("/v1/highlights");
	}

	@Override
	public Map<String, Object> clearHighlight(String highlightId) {
		return send("DELETE", "/v1/highlights?id=" + URLEncoder.encode(highlightId, java.nio.charset.StandardCharsets.UTF_8), null);
	}

	@Override
	public Map<String, Object> clearHighlights() {
		return send("DELETE", "/v1/highlights", null);
	}

	@Override
	public Map<String, Object> getAgentStatus() {
		return get("/v1/agent/status");
	}

	@Override
	public Map<String, Object> getAgentSession() {
		return get("/v1/agent/session");
	}

	@Override
	public Map<String, Object> openAgentSessionLan() {
		return send("POST", "/v1/agent/session/open-lan", null);
	}

	@Override
	public Map<String, Object> getAgentGoals() {
		return get("/v1/agent/goals");
	}

	@Override
	public Map<String, Object> getAgentTasks() {
		return get("/v1/agent/tasks");
	}

	@Override
	public Map<String, Object> getAgentLedger() {
		return get("/v1/agent/ledger");
	}

	@Override
	public Map<String, Object> getAgentEvidence() {
		return get("/v1/agent/evidence");
	}

	@Override
	public Map<String, Object> getAgentStepExecution() {
		return get("/v1/agent/step-execution");
	}

	@Override
	public Map<String, Object> submitAgentTask(Map<String, Object> taskPayload) {
		return send("POST", "/v1/agent/tasks", taskPayload);
	}

	@Override
	public Map<String, Object> submitAgentMission(Map<String, Object> missionPayload) {
		return send("POST", "/v1/agent/tasks", missionPayload);
	}

	@Override
	public Map<String, Object> cancelAgentTask() {
		return send("DELETE", "/v1/agent/tasks", null);
	}

	@Override
	public Map<String, Object> getAgentTree() {
		return get("/v1/agent/tree");
	}

	@Override
	public Map<String, Object> getAgentDialogue() {
		return get("/v1/agent/dialogue");
	}

	@Override
	public Map<String, Object> getAgentDebugState() {
		return get("/v1/agent/debug/state");
	}

	@Override
	public Map<String, Object> listAgentDebugTimeline(Long sinceEntryId) {
		if (sinceEntryId == null) {
			return get("/v1/agent/debug/timeline");
		}
		return get("/v1/agent/debug/timeline?since=" + sinceEntryId.longValue());
	}

	@Override
	public Map<String, Object> sendAgentDebugChat(String message) {
		return send("POST", "/v1/agent/debug/chat", Map.of("message", message));
	}

	@Override
	public Map<String, Object> getAgentContext() {
		return get("/v1/agent/context");
	}

	@Override
	public Map<String, Object> listRecentAgentEvents(Long sinceSeqNo) {
		if (sinceSeqNo == null) {
			return get("/v1/agent/events/recent");
		}
		return get("/v1/agent/events/recent?since=" + sinceSeqNo.longValue());
	}

	@Override
	public Map<String, Object> triggerAgentCompaction(boolean wait, Integer timeoutMs) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("wait", wait);
		if (timeoutMs != null) {
			body.put("timeoutMs", timeoutMs);
		}
		return send("POST", "/v1/agent/debug/compact", body);
	}

	@Override
	public Map<String, Object> getAgentEventPolicy() {
		return get("/v1/agent/event-policy");
	}

	@Override
	public Map<String, Object> clearAgentEventPolicy() {
		return send("POST", "/v1/agent/event-policy/clear", null);
	}

	@Override
	public Map<String, Object> getVerificationStatus() {
		return get("/v1/verification/status");
	}

	@Override
	public Map<String, Object> getVerificationPlayerState() {
		return get("/v1/verification/player");
	}

	@Override
	public Map<String, Object> teleportVerificationPlayer(double x, double y, double z) {
		return send("POST", "/v1/verification/player/teleport", Map.of(
			"x", x,
			"y", y,
			"z", z
		));
	}

	@Override
	public Map<String, Object> setVerificationPlayerVelocity(double x, double y, double z) {
		return send("POST", "/v1/verification/player/velocity", Map.of(
			"x", x,
			"y", y,
			"z", z
		));
	}

	@Override
	public Map<String, Object> respawnVerificationPlayer() {
		return send("POST", "/v1/verification/player/respawn", null);
	}

	@Override
	public Map<String, Object> setVerificationPlayerGameMode(String mode) {
		return send("POST", "/v1/verification/player/gamemode", Map.of("mode", mode));
	}

	@Override
	public Map<String, Object> runVerificationCommand(String command) {
		return send("POST", "/v1/verification/command", Map.of("command", command));
	}

	@Override
	public Map<String, Object> runVerificationScenario(String scenario) {
		return send("POST", "/v1/verification/run", Map.of("scenario", scenario));
	}

	private static Map<String, Object> entitySelectorBody(String uuid, String name, String entityTypeId, String itemId, String mode) {
		LinkedHashMap<String, Object> body = new LinkedHashMap<>();
		if (uuid != null && !uuid.isBlank()) {
			body.put("uuid", uuid);
		}
		if (name != null && !name.isBlank()) {
			body.put("name", name);
		}
		if (entityTypeId != null && !entityTypeId.isBlank()) {
			body.put("entityTypeId", entityTypeId);
		}
		if (itemId != null && !itemId.isBlank()) {
			body.put("itemId", itemId);
		}
		if (mode != null && !mode.isBlank()) {
			body.put("mode", mode);
		}
		return body;
	}

	@Override
	public Map<String, Object> getVerificationResults() {
		return get("/v1/verification/results");
	}

	private Map<String, Object> get(String path) {
		return send("GET", path, null);
	}

	private Map<String, Object> send(String method, String path, Object body) {
		BridgeStateFile.BridgeState state = BridgeStateFile.read()
			.orElseThrow(() -> new BridgeUnavailableException("minecraft_unavailable", "Minecraft bridge is not active"));

		HttpRequest.Builder builder = HttpRequest.newBuilder()
			.uri(URI.create("http://127.0.0.1:" + state.port() + path))
			.timeout(requestTimeout(path))
			.header("Authorization", "Bearer " + state.token())
			.header("Accept", "application/json");

		try {
			if (body == null) {
				builder.method(method, HttpRequest.BodyPublishers.noBody());
			}
			else {
				builder.method(method, HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(body)))
					.header("Content-Type", "application/json");
			}

			HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
			Map<String, Object> payload = response.body() == null || response.body().isBlank()
				? new LinkedHashMap<>()
				: OBJECT_MAPPER.readValue(response.body(), MAP_TYPE);

			if (response.statusCode() >= 400) {
				String code = String.valueOf(payload.getOrDefault("error", "bridge_error"));
				String message = String.valueOf(payload.getOrDefault("message", "Bridge request failed"));
				throw new BridgeUnavailableException(code, message);
			}

			return payload;
		}
		catch (ConnectException exception) {
			BridgeStateFile.deleteIfPresent();
			throw new BridgeUnavailableException("minecraft_unavailable", "Minecraft bridge is not reachable");
		}
		catch (IOException exception) {
			throw new BridgeUnavailableException("bridge_io_error", nonEmpty(exception.getMessage(), "Bridge IO error"));
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new BridgeUnavailableException("bridge_interrupted", "Bridge request interrupted");
		}
	}

	private static String nonEmpty(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}

	private static Duration requestTimeout(String path) {
		return switch (path) {
			case "/v1/reload" -> RELOAD_REQUEST_TIMEOUT;
			case "/v1/camera/screenshot" -> SCREENSHOT_REQUEST_TIMEOUT;
			case "/v1/map/image" -> SCREENSHOT_REQUEST_TIMEOUT;
			case "/v1/vision/describe" -> VISION_REQUEST_TIMEOUT;
			case "/v1/worlds/join", "/v1/servers/join" -> JOIN_REQUEST_TIMEOUT;
			case "/v1/agent/debug/compact" -> DEBUG_COMPACTION_REQUEST_TIMEOUT;
			default -> DEFAULT_REQUEST_TIMEOUT;
		};
	}

	private static String requiredString(Map<String, Object> payload, String key) {
		Object value = payload.get(key);
		if (value instanceof String text && !text.isBlank()) {
			return text;
		}
		throw new IllegalArgumentException("Missing string field: " + key);
	}

	private static int requiredInt(Map<String, Object> payload, String key) {
		Object value = payload.get(key);
		if (value instanceof Number number) {
			return number.intValue();
		}
		throw new IllegalArgumentException("Missing integer field: " + key);
	}

	private static long requiredLong(Map<String, Object> payload, String key) {
		Object value = payload.get(key);
		if (value instanceof Number number) {
			return number.longValue();
		}
		throw new IllegalArgumentException("Missing integer field: " + key);
	}
}
