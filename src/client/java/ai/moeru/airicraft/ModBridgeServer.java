package ai.moeru.airicraft;

import ai.moeru.airicraft.agent.EmbodiedAgentRuntime;
import ai.moeru.airicraft.agent.integration.map.MapImageCapture;
import ai.moeru.airicraft.agent.integration.map.MapImageRequest;
import ai.moeru.airicraft.agent.integration.map.MapIntegrationBridge;
import ai.moeru.airicraft.agent.integration.map.MapIntegrationProvider;
import ai.moeru.airicraft.agent.integration.map.MapIntegrationRegistry;
import ai.moeru.airicraft.agent.integration.map.MapWaypoint;
import ai.moeru.airicraft.agent.integration.map.MapWaypointQuery;
import ai.moeru.airicraft.agent.integration.map.MapWaypointWrite;
import ai.moeru.airicraft.agent.verification.VerificationPlayerProbe;
import ai.moeru.airicraft.agent.llm.CurrentViewVisionService;
import ai.moeru.airicraft.agent.llm.LlmBackendException;
import ai.moeru.airicraft.agent.llm.PlannerTrigger;
import ai.moeru.airicraft.agent.session.LanHostingService;
import ai.moeru.airicraft.agent.tasks.TaskResourceKind;
import ai.moeru.airicraft.agent.tasks.TaskLedger;
import ai.moeru.airicraft.agent.tasks.TaskSpec;
import ai.moeru.airicraft.agent.tasks.TaskType;
import ai.moeru.airicraft.agent.tasks.EntitySelectorResolver;
import ai.moeru.airicraft.agent.tasks.EntityAttackMode;
import ai.moeru.airicraft.agent.tasks.EntityInteractionStepArgs;
import ai.moeru.airicraft.agent.tasks.EntitySelector;
import ai.moeru.airicraft.agent.tasks.NearbyEntityService;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class ModBridgeServer {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final long SCREENSHOT_CAPTURE_TIMEOUT_MILLIS = 5_000L;
	private static final long MAP_CAPTURE_TIMEOUT_MILLIS = 5_000L;
	private static final long DEBUG_COMPACTION_DEFAULT_TIMEOUT_MILLIS = 30_000L;
	private static final long DEBUG_COMPACTION_MAX_TIMEOUT_MILLIS = 120_000L;
	private static final long DEBUG_COMPACTION_POLL_INTERVAL_MILLIS = 25L;
	private static final long VERIFICATION_RESPAWN_TIMEOUT_MILLIS = 5_000L;
	private static final long VERIFICATION_RESPAWN_POLL_INTERVAL_MILLIS = 25L;
	private static final List<String> VERIFICATION_CAPABILITIES = List.of(
		"player_state",
		"player_teleport",
		"player_velocity",
		"player_respawn",
		"player_gamemode",
		"command",
		"scenario_run",
		"results"
	);

	private final Supplier<HighlightManager> highlightManagerSupplier;
	private final Supplier<EmbodiedAgentRuntime> agentRuntimeSupplier;
	private final Supplier<FirstPersonScreenshotService> screenshotServiceSupplier;
	private final Supplier<ClientRuntimeController.ReloadResult> reloadSupplier;
	private final SingleplayerWorldService singleplayerWorldService = new SingleplayerWorldService();
	private final SavedServerService savedServerService = new SavedServerService();
	private final PlayerViewService playerViewService = new PlayerViewService();

	private volatile HttpServer server;
	private volatile String token;

	public ModBridgeServer(
		Supplier<HighlightManager> highlightManagerSupplier,
		Supplier<EmbodiedAgentRuntime> agentRuntimeSupplier,
		Supplier<FirstPersonScreenshotService> screenshotServiceSupplier,
		Supplier<ClientRuntimeController.ReloadResult> reloadSupplier
	) {
		this.highlightManagerSupplier = Objects.requireNonNull(highlightManagerSupplier, "highlightManagerSupplier");
		this.agentRuntimeSupplier = Objects.requireNonNull(agentRuntimeSupplier, "agentRuntimeSupplier");
		this.screenshotServiceSupplier = Objects.requireNonNull(screenshotServiceSupplier, "screenshotServiceSupplier");
		this.reloadSupplier = Objects.requireNonNull(reloadSupplier, "reloadSupplier");
	}

	public synchronized void start() {
		if (server != null) {
			return;
		}

		BridgeDiscoveryFile.deleteIfPresent();

		try {
			var httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
			httpServer.setExecutor(Executors.newCachedThreadPool());
			token = generateToken();
			httpServer.createContext("/v1/status", exchange -> handleJson(exchange, this::createStatusResponse));
			httpServer.createContext("/v1/reload", this::handleReload);
			httpServer.createContext("/v1/worlds", this::handleWorlds);
			httpServer.createContext("/v1/worlds/join", this::handleJoinWorld);
			httpServer.createContext("/v1/servers", this::handleServers);
			httpServer.createContext("/v1/servers/join", this::handleJoinServer);
			httpServer.createContext("/v1/focus", exchange -> handleJson(exchange, this::createFocusResponse));
			httpServer.createContext("/v1/player/nearby-entities", exchange -> handleJson(exchange, this::createNearbyEntitiesResponse));
			httpServer.createContext("/v1/world-snapshot", exchange -> handleJson(exchange, () -> createWorldSnapshotResponse(exchange)));
			httpServer.createContext("/v1/camera/screenshot", this::handleCameraScreenshot);
			httpServer.createContext("/v1/vision/describe", this::handleVisionDescribe);
			httpServer.createContext("/v1/map/status", exchange -> handleJson(exchange, this::createMapStatusResponse));
			httpServer.createContext("/v1/map/waypoints", this::handleMapWaypoints);
			httpServer.createContext("/v1/map/image", this::handleMapImage);
			httpServer.createContext("/v1/player/look-at", this::handlePlayerLookAt);
			httpServer.createContext("/v1/player/attack-entity", this::handlePlayerAttackEntity);
			httpServer.createContext("/v1/player/use-entity", this::handlePlayerUseEntity);
			httpServer.createContext("/v1/highlights", this::handleHighlights);
			httpServer.createContext("/v1/agent/status", exchange -> handleJson(exchange, this::createAgentStatusResponse));
			httpServer.createContext("/v1/agent/session", exchange -> handleJson(exchange, this::createAgentSessionResponse));
			httpServer.createContext("/v1/agent/session/open-lan", this::handleAgentOpenLan);
				httpServer.createContext("/v1/agent/events/recent", exchange -> handleJson(exchange, () -> createRecentAgentEventsResponse(exchange)));
				httpServer.createContext("/v1/agent/goals", exchange -> handleJson(exchange, this::createAgentGoalsResponse));
				httpServer.createContext("/v1/agent/tree", exchange -> handleJson(exchange, this::createAgentTreeResponse));
			httpServer.createContext("/v1/agent/dialogue", exchange -> handleJson(exchange, this::createAgentDialogueResponse));
			httpServer.createContext("/v1/agent/context", exchange -> handleJson(exchange, this::createAgentContextResponse));
			httpServer.createContext("/v1/agent/event-policy", exchange -> handleJson(exchange, this::createAgentEventPolicyResponse));
			httpServer.createContext("/v1/agent/event-policy/clear", this::handleAgentEventPolicyClear);
			httpServer.createContext("/v1/agent/tasks", this::handleAgentTasks);
			httpServer.createContext("/v1/agent/ledger", exchange -> handleJson(exchange, this::createAgentLedgerResponse));
			httpServer.createContext("/v1/agent/evidence", exchange -> handleJson(exchange, this::createAgentEvidenceResponse));
			httpServer.createContext("/v1/agent/step-execution", exchange -> handleJson(exchange, this::createAgentStepExecutionResponse));
			httpServer.createContext("/v1/agent/debug/chat", this::handleAgentDebugChat);
			httpServer.createContext("/v1/agent/debug/idle-trigger", this::handleAgentDebugIdleTrigger);
			httpServer.createContext("/v1/agent/debug/compact", this::handleAgentDebugCompact);
			httpServer.createContext("/v1/agent/debug/state", exchange -> handleJson(exchange, this::createAgentDebugStateResponse));
			httpServer.createContext("/v1/agent/debug/timeline", exchange -> handleJson(exchange, () -> createAgentDebugTimelineResponse(exchange)));
				httpServer.createContext("/v1/verification/status", exchange -> handleJson(exchange, this::createVerificationStatusResponse));
				httpServer.createContext("/v1/verification/player", exchange -> handleJson(exchange, this::createVerificationPlayerResponse));
				httpServer.createContext("/v1/verification/player/teleport", this::handleVerificationPlayerTeleport);
				httpServer.createContext("/v1/verification/player/velocity", this::handleVerificationPlayerVelocity);
				httpServer.createContext("/v1/verification/player/respawn", this::handleVerificationPlayerRespawn);
				httpServer.createContext("/v1/verification/player/gamemode", this::handleVerificationPlayerGameMode);
				httpServer.createContext("/v1/verification/command", this::handleVerificationCommand);
				httpServer.createContext("/v1/verification/results", exchange -> handleJson(exchange, this::createVerificationResultsResponse));
			httpServer.createContext("/v1/verification/run", this::handleVerificationRun);
			httpServer.start();

			server = httpServer;

			var state = new BridgeSessionState(httpServer.getAddress().getPort(), token, Instant.now().toEpochMilli());
			BridgeDiscoveryFile.write(state);
			Airicraft.LOGGER.info("Airicraft bridge started on port {}", state.port());
		}
		catch (IOException exception) {
			Airicraft.LOGGER.error("Failed to start Airicraft bridge", exception);
			stop();
		}
	}

	public synchronized void stop() {
		var currentServer = server;
		server = null;
		token = null;

		if (currentServer != null) {
			currentServer.stop(0);
			Airicraft.LOGGER.info("Airicraft bridge stopped");
		}

		BridgeDiscoveryFile.deleteIfPresent();
	}

	private void handleWorlds(HttpExchange exchange) throws IOException {
		handleJson(exchange, () -> {
			try {
				var client = getClient();
				return Map.of(
					"available", true,
					"sessionState", sessionState(client),
					"worlds", singleplayerWorldService.listWorlds()
				);
			}
			catch (SingleplayerWorldService.SingleplayerWorldException exception) {
				throw new BridgeUnavailableException(exception.code(), exception.getMessage());
			}
		});
	}

	private void handleJoinWorld(HttpExchange exchange) throws IOException {
		handleJsonBody(exchange, "POST", JoinWorldRequest.class, request -> {
			if (request == null || request.worldId() == null || request.worldId().isBlank()) {
				throw new BridgeUnavailableException("invalid_request", "Missing worldId");
			}

			try {
				return singleplayerWorldService.joinWorld(request.worldId());
			}
			catch (SingleplayerWorldService.SingleplayerWorldException exception) {
				throw new BridgeUnavailableException(exception.code(), exception.getMessage());
			}
		});
	}

	private void handleServers(HttpExchange exchange) throws IOException {
		handleJson(exchange, () -> {
			try {
				var client = getClient();
				return Map.of(
					"available", true,
					"sessionState", sessionState(client),
					"servers", savedServerService.listServers()
				);
			}
			catch (SavedServerService.SavedServerServiceException exception) {
				throw new BridgeUnavailableException(exception.code(), exception.getMessage());
			}
		});
	}

	private void handleJoinServer(HttpExchange exchange) throws IOException {
		handleJsonBody(exchange, "POST", JoinServerRequest.class, request -> {
			if (request == null || request.serverId() == null || request.serverId().isBlank()) {
				throw new BridgeUnavailableException("invalid_request", "Missing serverId");
			}

			try {
				return savedServerService.joinServer(request.serverId());
			}
			catch (SavedServerService.SavedServerServiceException exception) {
				throw new BridgeUnavailableException(exception.code(), exception.getMessage());
			}
		});
	}

	private void handlePlayerLookAt(HttpExchange exchange) throws IOException {
		handleJsonBody(exchange, "POST", LookAtRequest.class, request -> {
			if (request == null) {
				throw new BridgeUnavailableException("invalid_request", "Missing look-at payload");
			}
			if (!isFinite(request.x()) || !isFinite(request.y()) || !isFinite(request.z())) {
				throw new BridgeUnavailableException("invalid_request", "x, y, and z must be finite numbers");
			}

			try {
				return onClientThread(() -> playerViewService.lookAt(request.x(), request.y(), request.z()));
			}
			catch (PlayerViewService.PlayerViewException exception) {
				throw new BridgeUnavailableException(exception.code(), exception.getMessage());
			}
		});
	}

	private void handlePlayerAttackEntity(HttpExchange exchange) throws IOException {
		handleJsonBody(exchange, "POST", EntityInteractionRequest.class, request -> {
			EntityInteractionStepArgs entityInteraction = parseEntityInteractionRequest(request, false);
			return onClientThread(() -> {
				var client = getClient();
				ensureWorldLoaded(client);
				var task = agentRuntime().submitAttackEntity(entityInteraction, "bridge_player");
				return entityInteractionResponse(task, entityInteraction, true);
			});
		});
	}

	private void handlePlayerUseEntity(HttpExchange exchange) throws IOException {
		handleJsonBody(exchange, "POST", EntityInteractionRequest.class, request -> {
			EntityInteractionStepArgs entityInteraction = parseEntityInteractionRequest(request, true);
			return onClientThread(() -> {
				var client = getClient();
				ensureWorldLoaded(client);
				var task = agentRuntime().submitUseEntity(entityInteraction, "bridge_player");
				return entityInteractionResponse(task, entityInteraction, false);
			});
		});
	}

	private void handleCameraScreenshot(HttpExchange exchange) throws IOException {
		if (!authorize(exchange)) {
			writeJson(exchange, 401, Map.of("error", "unauthorized", "message", "Invalid bridge token"));
			return;
		}

		if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
			writeJson(exchange, 405, Map.of("error", "method_not_allowed"));
			return;
		}

		try {
			CompletableFuture<FirstPersonScreenshotService.CapturedScreenshot> captureFuture = onClientThread(() -> {
				var client = getClient();
				ensureWorldLoaded(client);
				return screenshotService().requestCapture(client);
			});
			writeJson(exchange, 200, cameraScreenshotPayload(awaitCameraScreenshot(captureFuture)));
		}
		catch (BridgeUnavailableException exception) {
			writeJson(exchange, 503, Map.of("error", exception.code(), "message", exception.getMessage()));
		}
		catch (Exception exception) {
			Airicraft.LOGGER.warn("Bridge request failed", exception);
			writeJson(exchange, 500, Map.of("error", "internal_error", "message", exception.getMessage()));
		}
	}

	private void handleVisionDescribe(HttpExchange exchange) throws IOException {
		handleJsonBody(exchange, "POST", VisionDescribeRequest.class, request -> {
			String prompt = request == null ? null : request.prompt();
			CompletableFuture<FirstPersonScreenshotService.CapturedScreenshot> captureFuture = onClientThread(() -> {
				var client = getClient();
				ensureWorldLoaded(client);
				return screenshotService().requestCapture(client);
			});
			FirstPersonScreenshotService.CapturedScreenshot screenshot = awaitCameraScreenshot(captureFuture);
			try {
				return visionDescribePayload(agentRuntime().describeCapturedView(screenshot, prompt));
			}
			catch (LlmBackendException exception) {
				throw visionBridgeException(exception);
			}
		});
	}

	private Object createMapStatusResponse() {
		return mapStatusPayload(MapIntegrationBridge.registry());
	}

	private void handleMapWaypoints(HttpExchange exchange) throws IOException {
		if (!authorize(exchange)) {
			writeJson(exchange, 401, Map.of("error", "unauthorized", "message", "Invalid bridge token"));
			return;
		}

		if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			String providerId = getQuery(exchange, "provider");
			String dimension = getQuery(exchange, "dimension");
			try {
				Map<String, Object> payload = onClientThread(() -> {
					MapIntegrationProvider provider = mapProvider(providerId);
					return mapWaypointsPayload(provider.listWaypoints(new MapWaypointQuery(provider.id(), dimension)));
				});
				writeJson(exchange, 200, payload);
			}
			catch (BridgeUnavailableException exception) {
				writeJson(exchange, 503, Map.of("error", exception.code(), "message", exception.getMessage()));
			}
			return;
		}

		if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
			try (var reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
				MapWaypointRequest request = GSON.fromJson(reader, MapWaypointRequest.class);
				if (request == null || request.name() == null || request.name().isBlank()
					|| request.x() == null || request.y() == null || request.z() == null) {
					throw new BridgeUnavailableException("invalid_request", "Missing waypoint payload");
				}
				Map<String, Object> payload = onClientThread(() -> {
					var client = getClient();
					ensureWorldLoaded(client);
					MapIntegrationProvider provider = mapProvider(request.provider());
					MapWaypoint waypoint = provider.upsertWaypoint(new MapWaypointWrite(
						provider.id(),
						request.id(),
						request.name(),
						request.dimension() == null || request.dimension().isBlank()
							? client.world.getRegistryKey().getValue().toString()
							: request.dimension(),
						request.x(),
						request.y(),
						request.z(),
						request.color(),
						request.enabled() == null || request.enabled(),
						request.showOnMap() == null || request.showOnMap(),
						request.showInWorld() == null || request.showInWorld()
					));
					Map<String, Object> response = new LinkedHashMap<>();
					response.put("waypoint", mapWaypointPayload(waypoint));
					return response;
				});
				writeJson(exchange, 200, payload);
				return;
			}
			catch (JsonSyntaxException exception) {
				writeJson(exchange, 400, Map.of("error", "invalid_json", "message", "Malformed waypoint request"));
				return;
			}
			catch (BridgeUnavailableException exception) {
				writeJson(exchange, 503, Map.of("error", exception.code(), "message", exception.getMessage()));
				return;
			}
		}

		if ("DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
			String providerId = getQuery(exchange, "provider");
			String waypointId = getQuery(exchange, "id");
			if (waypointId == null || waypointId.isBlank()) {
				writeJson(exchange, 400, Map.of("error", "invalid_request", "message", "Missing waypoint id"));
				return;
			}
			try {
				Map<String, Object> payload = onClientThread(() -> {
					MapIntegrationProvider provider = mapProvider(providerId);
					boolean deleted = provider.deleteWaypoint(waypointId);
					if (!deleted) {
						throw new BridgeUnavailableException("map_waypoint_not_found", "Map waypoint not found: " + waypointId);
					}
					return Map.of("deleted", true, "waypointId", waypointId);
				});
				writeJson(exchange, 200, payload);
				return;
			}
			catch (BridgeUnavailableException exception) {
				writeJson(exchange, 503, Map.of("error", exception.code(), "message", exception.getMessage()));
				return;
			}
		}

		writeJson(exchange, 405, Map.of("error", "method_not_allowed"));
	}

	private void handleMapImage(HttpExchange exchange) throws IOException {
		handleJsonBody(exchange, "POST", MapImageRequestBody.class, request -> {
			CompletableFuture<MapImageCapture> captureFuture = onClientThread(() -> {
				MapIntegrationProvider provider = mapProvider(request == null ? null : request.provider());
				return provider.captureMap(new MapImageRequest(
					provider.id(),
					request == null ? "worldmap" : request.kind(),
					request == null ? null : request.dimension(),
					request == null || request.radiusChunks() == null ? 8 : request.radiusChunks(),
					request == null || request.zoom() == null ? 0 : request.zoom(),
					request != null && Boolean.TRUE.equals(request.grid()),
					request == null ? null : request.originX(),
					request == null ? null : request.originZ()
				));
			});
			return mapImagePayload(awaitMapCapture(captureFuture));
		});
	}

	private void handleReload(HttpExchange exchange) throws IOException {
		handleJsonBody(exchange, "POST", Object.class, request -> onClientThread(() -> reloadSupplier.get().toPayload()));
	}

	private void handleHighlights(HttpExchange exchange) throws IOException {
		if (!authorize(exchange)) {
			writeJson(exchange, 401, Map.of("error", "unauthorized", "message", "Invalid bridge token"));
			return;
		}

		if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			var payload = onClientThread(() -> Map.of("highlights", highlightManager().list()));
			writeJson(exchange, 200, payload);
			return;
		}

		if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
			HighlightRequest request;
			try (var reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
				request = GSON.fromJson(reader, HighlightRequest.class);
			}
			catch (JsonSyntaxException exception) {
				writeJson(exchange, 400, Map.of("error", "invalid_json", "message", "Malformed highlight request"));
				return;
			}

			if (request == null) {
				writeJson(exchange, 400, Map.of("error", "invalid_request", "message", "Missing highlight payload"));
				return;
			}

			var highlightId = onClientThread(() -> {
				var client = getClient();
				ensureWorldLoaded(client);
				String kind = highlightKind(request.kind());
				int color = parseColor(request.color());
				Long durationMs = safeDurationMs(request.durationMs());
				if ("region".equals(kind)) {
					return highlightManager().addRegion(
						requiredBlockPos(request.x1(), request.y1(), request.z1(), "x1/y1/z1"),
						requiredBlockPos(request.x2(), request.y2(), request.z2(), "x2/y2/z2"),
						color,
						durationMs,
						request.overlayText()
					);
				}

				return highlightManager().addBlock(
					requiredBlockPos(request.x(), request.y(), request.z(), "x/y/z"),
					color,
					durationMs,
					request.overlayText()
				);
			});

			writeJson(exchange, 200, Map.of("highlightId", highlightId));
			return;
		}

		if ("DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
			String highlightId = getQuery(exchange, "id");
			Map<String, Object> payload = onClientThread(() -> {
				var client = getClient();
				ensureWorldLoaded(client);
				if (highlightId == null || highlightId.isBlank()) {
					int clearedCount = highlightManager().clear();
					return Map.of("cleared", true, "clearedCount", clearedCount);
				}

				boolean cleared = highlightManager().clearById(highlightId);
				if (!cleared) {
					throw new BridgeUnavailableException("highlight_not_found", "Highlight not found: " + highlightId);
				}

				return Map.of("cleared", true, "highlightId", highlightId);
			});
			writeJson(exchange, 200, payload);
			return;
		}

		writeJson(exchange, 405, Map.of("error", "method_not_allowed"));
	}

	private void handleVerificationRun(HttpExchange exchange) throws IOException {
		handleJsonBody(exchange, "POST", VerificationRunRequest.class, request -> {
			if (request == null || request.scenario() == null || request.scenario().isBlank()) {
				throw new BridgeUnavailableException("invalid_request", "Missing scenario");
			}

			return onClientThread(() -> {
				boolean accepted = agentRuntime().startVerification(request.scenario());
				if (!accepted) {
					throw new BridgeUnavailableException("unknown_scenario", "Unknown verification scenario: " + request.scenario());
				}

				return Map.of(
					"accepted", true,
					"scenario", request.scenario(),
					"running", true
				);
			});
		});
	}

	private void handleVerificationPlayerTeleport(HttpExchange exchange) throws IOException {
		handleJsonBody(exchange, "POST", VerificationPlayerTeleportRequest.class, request -> {
			if (request == null || !isFinite(request.x()) || !isFinite(request.y()) || !isFinite(request.z())) {
				throw new BridgeUnavailableException("invalid_request", "x, y, and z must be finite numbers");
			}
			return onClientThread(() -> verificationPlayerActionResponse(
				agentRuntime().verificationTeleportPlayer(request.x(), request.y(), request.z()),
				"teleported",
				true
			));
		});
	}

	private void handleVerificationPlayerVelocity(HttpExchange exchange) throws IOException {
		handleJsonBody(exchange, "POST", VerificationPlayerVelocityRequest.class, request -> {
			if (request == null || !isFinite(request.x()) || !isFinite(request.y()) || !isFinite(request.z())) {
				throw new BridgeUnavailableException("invalid_request", "x, y, and z must be finite numbers");
			}
			return onClientThread(() -> verificationPlayerActionResponse(
				agentRuntime().verificationSetPlayerVelocity(request.x(), request.y(), request.z()),
				"applied",
				true
			));
		});
	}

	private void handleVerificationPlayerGameMode(HttpExchange exchange) throws IOException {
		handleJsonBody(exchange, "POST", VerificationPlayerGameModeRequest.class, request -> {
			if (request == null || request.mode() == null || request.mode().isBlank()) {
				throw new BridgeUnavailableException("invalid_request", "Missing mode");
			}
			return onClientThread(() -> verificationPlayerActionResponse(
				agentRuntime().verificationSetGameMode(request.mode()),
				"changed",
				true
			));
		});
	}

	private void handleVerificationPlayerRespawn(HttpExchange exchange) throws IOException {
		handleJsonBody(exchange, "POST", Object.class, request -> {
			onClientThread(() -> agentRuntime().verificationRequestRespawn());
			long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(VERIFICATION_RESPAWN_TIMEOUT_MILLIS);
			while (System.nanoTime() < deadline) {
				Map<String, Object> response = onClientThread(() -> {
					MinecraftClient client = getClient();
					if (client.currentScreen != null && "DeathScreen".equals(client.currentScreen.getClass().getSimpleName())) {
						return null;
					}
					VerificationPlayerProbe probe = agentRuntime().verificationPlayerProbe();
					LinkedHashMap<String, Object> payload = verificationPlayerActionResponse(probe, "respawned", true);
					payload.put("currentScreen", currentScreenName(client));
					return payload;
				});
				if (response != null) {
					return response;
				}
				try {
					Thread.sleep(VERIFICATION_RESPAWN_POLL_INTERVAL_MILLIS);
				}
				catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new BridgeUnavailableException("bridge_interrupted", "Respawn wait interrupted");
				}
			}
			throw new BridgeUnavailableException("verification_unavailable", "Timed out waiting for player respawn");
		});
	}

	private void handleVerificationCommand(HttpExchange exchange) throws IOException {
		handleJsonBody(exchange, "POST", VerificationCommandRequest.class, request -> {
			if (request == null || request.command() == null || request.command().isBlank()) {
				throw new BridgeUnavailableException("invalid_request", "Missing command");
			}
			return onClientThread(() -> {
				VerificationPlayerProbe probe = agentRuntime().verificationRunCommand(request.command());
				LinkedHashMap<String, Object> response = new LinkedHashMap<>();
				response.put("available", true);
				response.put("sessionMode", agentRuntime().sessionSnapshot().mode().name());
				response.put("worldLoaded", agentRuntime().sessionSnapshot().worldLoaded());
				response.put("executed", true);
				response.put("command", request.command().trim());
				response.putAll(verificationPlayerPayload(probe));
				return response;
			});
		});
	}

	private void handleAgentOpenLan(HttpExchange exchange) throws IOException {
		handleJsonBody(exchange, "POST", Object.class, request -> {
			try {
				return onClientThread(() -> agentRuntime().openLan());
			}
			catch (LanHostingService.LanHostingException exception) {
				throw new BridgeUnavailableException(exception.code(), exception.getMessage());
			}
		});
	}

	private void handleAgentDebugCompact(HttpExchange exchange) throws IOException {
		handleJsonBody(exchange, "POST", DebugCompactRequest.class, request -> {
			boolean wait = request == null || request.waitValue() == null || request.waitValue();
			long timeoutMillis = requestedDebugCompactionTimeoutMillis(request == null ? null : request.timeoutMs());
			boolean started = onClientThread(() -> {
				if (!agentRuntime().llmAvailable()) {
					throw new BridgeUnavailableException("planner_unavailable", "Planner LLM is not configured");
				}
				if (!agentRuntime().startDebugCompaction()) {
					throw new BridgeUnavailableException("planner_busy", "Planner is busy with another request");
				}
				return true;
			});
			if (!wait) {
				return agentDebugCompactPayload(started, false, timeoutMillis);
			}

			long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
			while (System.nanoTime() < deadline) {
				var snapshot = onClientThread(() -> {
					agentRuntime().pollDebugCompaction();
					return agentRuntime().plannerDebugSnapshot();
				});
				if (!snapshot.compactionInFlight() && snapshot.lastCompactionResult() != null) {
					if (!snapshot.lastCompactionResult().succeeded()) {
						throw new BridgeUnavailableException(
							compactionFailureCode(snapshot.lastCompactionResult().failureType()),
							nonEmpty(snapshot.lastCompactionResult().failureMessage(), "Planner compaction failed")
						);
					}
					return agentDebugCompactPayload(true, true, timeoutMillis);
				}
				try {
					Thread.sleep(DEBUG_COMPACTION_POLL_INTERVAL_MILLIS);
				}
				catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new BridgeUnavailableException("bridge_interrupted", "Compaction wait interrupted");
				}
			}
			throw new BridgeUnavailableException("compaction_timeout", "Timed out waiting for planner compaction");
		});
	}

	private void handleAgentEventPolicyClear(HttpExchange exchange) throws IOException {
		handleJsonBody(exchange, "POST", Object.class, request -> {
			return onClientThread(() -> {
				agentRuntime().clearEventPolicy();
				return createAgentEventPolicyPayload();
			});
		});
	}

	private void handleAgentDebugChat(HttpExchange exchange) throws IOException {
		handleJsonBody(exchange, "POST", DebugChatRequest.class, request -> {
			if (request == null || request.message() == null || request.message().isBlank()) {
				throw new BridgeUnavailableException("invalid_request", "Missing message");
			}
			return onClientThread(() -> {
				var client = getClient();
				ensureWorldLoaded(client);
				String senderName = nonEmpty(request.senderName(), defaultDebugSender(client));
				String message = request.message().trim();
				agentRuntime().onChatReceived(senderName, message);

				Map<String, Object> payload = new LinkedHashMap<>();
				payload.put("available", true);
				payload.put("accepted", true);
				payload.put("senderName", senderName);
				payload.put("message", message);
				payload.put("task", agentRuntime().taskSnapshot());
				payload.put("taskExecution", agentRuntime().taskExecutionSnapshot());
				payload.put("lastDialogueResponse", agentRuntime().lastDialogueResponse().orElse(null));
				payload.put("sessionMode", agentRuntime().sessionSnapshot().mode().name());
				return payload;
			});
		});
	}

	private void handleAgentDebugIdleTrigger(HttpExchange exchange) throws IOException {
		handleJsonBody(exchange, "POST", Object.class, request -> onClientThread(() -> {
			var client = getClient();
			ensureWorldLoaded(client);
			PlannerTrigger trigger = agentRuntime().fireIdleIdeaTriggerManually()
				.orElseThrow(() -> new BridgeUnavailableException("idle_trigger_unavailable", "No idle ideas are configured"));

			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("available", true);
			payload.put("accepted", true);
			payload.put("triggerType", trigger.type().promptLabel());
			payload.put("speaker", trigger.speaker());
			payload.put("tick", trigger.tick());
			payload.put("timestampMs", trigger.timestampMs());
			payload.put("task", agentRuntime().taskSnapshot());
			payload.put("taskExecution", agentRuntime().taskExecutionSnapshot());
			payload.put("lastDialogueResponse", agentRuntime().lastDialogueResponse().orElse(null));
			payload.put("sessionMode", agentRuntime().sessionSnapshot().mode().name());
			return payload;
		}));
	}

	private void handleAgentTasks(HttpExchange exchange) throws IOException {
		if (!authorize(exchange)) {
			writeJson(exchange, 401, Map.of("error", "unauthorized", "message", "Invalid bridge token"));
			return;
		}
		String method = exchange.getRequestMethod();
		if ("GET".equalsIgnoreCase(method)) {
			writeJson(exchange, 200, createAgentTasksResponse());
			return;
		}
		if ("DELETE".equalsIgnoreCase(method)) {
			Map<String, Object> response = onClientThread(() -> {
				var task = agentRuntime().cancelTask("bridge_debug_cancel");
				Map<String, Object> payload = new LinkedHashMap<>();
				payload.put("available", true);
				payload.put("cancelled", true);
				payload.put("task", task);
				payload.put("taskExecution", agentRuntime().taskExecutionSnapshot());
				payload.put("missionExecution", agentRuntime().missionExecutionSnapshot());
				return payload;
			});
			writeJson(exchange, 200, response);
			return;
		}
		if (!"POST".equalsIgnoreCase(method)) {
			writeJson(exchange, 405, Map.of("error", "method_not_allowed"));
			return;
		}
		try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
			JsonObject request = GSON.fromJson(reader, JsonObject.class);
			if (request == null) {
				throw new BridgeUnavailableException("invalid_request", "Missing task payload");
			}
			if (isMissionLedgerRequest(request)) {
				TaskLedger ledger = GSON.fromJson(request, TaskLedger.class);
				if (ledger == null || ledger.missionId() == null || ledger.missionType() == null || ledger.steps() == null) {
					throw new BridgeUnavailableException("invalid_request", "Malformed mission ledger payload");
				}
				Map<String, Object> response = onClientThread(() -> {
					var task = agentRuntime().submitMissionLedger(ledger, "bridge_debug_mission");
					Map<String, Object> payload = new LinkedHashMap<>();
					payload.put("available", true);
					payload.put("task", task);
					payload.put("taskExecution", agentRuntime().taskExecutionSnapshot());
					payload.put("missionExecution", agentRuntime().missionExecutionSnapshot());
					return payload;
				});
				writeJson(exchange, 200, response);
				return;
			}
			AgentTaskRequest taskRequest = GSON.fromJson(request, AgentTaskRequest.class);
			if (taskRequest == null || taskRequest.type() == null || taskRequest.resourceKind() == null || taskRequest.quantity() == null) {
				throw new BridgeUnavailableException("invalid_request", "Missing task payload");
			}
			TaskType taskType = parseTaskType(taskRequest.type());
			TaskResourceKind resourceKind = parseTaskResourceKind(taskRequest.resourceKind());
			if (taskRequest.quantity().intValue() <= 0) {
				throw new BridgeUnavailableException("invalid_request", "quantity must be positive");
			}
			Map<String, Object> response = onClientThread(() -> {
				var task = agentRuntime().submitTask(
					new TaskSpec(taskType, resourceKind, taskRequest.quantity().intValue()),
					"bridge_debug"
				);
				Map<String, Object> payload = new LinkedHashMap<>();
				payload.put("available", true);
				payload.put("task", task);
				payload.put("taskExecution", agentRuntime().taskExecutionSnapshot());
				payload.put("missionExecution", agentRuntime().missionExecutionSnapshot());
				return payload;
			});
			writeJson(exchange, 200, response);
		}
		catch (JsonSyntaxException exception) {
			writeJson(exchange, 400, Map.of("error", "invalid_json", "message", "Malformed request payload"));
		}
		catch (BridgeUnavailableException exception) {
			writeJson(exchange, 503, Map.of("error", exception.code(), "message", exception.getMessage()));
		}
	}

	private static boolean isMissionLedgerRequest(JsonObject request) {
		return request.has("missionId") && request.has("missionType") && request.has("steps");
	}

	private EntityInteractionStepArgs parseEntityInteractionRequest(EntityInteractionRequest request, boolean allowItemId) {
		if (request == null) {
			throw new BridgeUnavailableException("invalid_request", "Missing entity interaction payload");
		}
		try {
			return new EntityInteractionStepArgs(
				new EntitySelector(request.uuid(), request.name(), request.entityTypeId()),
				allowItemId ? request.itemId() : null,
				allowItemId ? EntityAttackMode.KILL : EntityAttackMode.fromWireValue(request.mode())
			);
		}
		catch (IllegalArgumentException exception) {
			throw new BridgeUnavailableException("invalid_request", exception.getMessage());
		}
	}

	private Map<String, Object> entityInteractionResponse(ai.moeru.airicraft.agent.tasks.TaskSnapshot task, EntityInteractionStepArgs entityInteraction, boolean includeMode) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("available", true);
		payload.put("accepted", true);
		payload.put("selector", Map.of(
			"uuid", entityInteraction.selector().uuid() == null ? "" : entityInteraction.selector().uuid(),
			"name", entityInteraction.selector().name() == null ? "" : entityInteraction.selector().name(),
			"entityTypeId", entityInteraction.selector().entityTypeId() == null ? "" : entityInteraction.selector().entityTypeId()
		));
		if (entityInteraction.itemId() != null) {
			payload.put("itemId", entityInteraction.itemId());
		}
		if (includeMode) {
			payload.put("mode", entityInteraction.attackMode().wireValue());
		}
		payload.put("task", task);
		payload.put("taskExecution", agentRuntime().taskExecutionSnapshot());
		payload.put("missionExecution", agentRuntime().missionExecutionSnapshot());
		return payload;
	}


	private void handleJson(HttpExchange exchange, Supplier<Object> supplier) throws IOException {
		if (!authorize(exchange)) {
			writeJson(exchange, 401, Map.of("error", "unauthorized", "message", "Invalid bridge token"));
			return;
		}

		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			writeJson(exchange, 405, Map.of("error", "method_not_allowed"));
			return;
		}

		try {
			Object response = supplier.get();
			writeJson(exchange, 200, response);
		}
		catch (BridgeUnavailableException exception) {
			writeJson(exchange, 503, Map.of("error", exception.code(), "message", exception.getMessage()));
		}
		catch (Exception exception) {
			Airicraft.LOGGER.warn("Bridge request failed", exception);
			writeJson(exchange, 500, Map.of("error", "internal_error", "message", exception.getMessage()));
		}
	}

	private <T> void handleJsonBody(
		HttpExchange exchange,
		String method,
		Class<T> requestType,
		java.util.function.Function<T, Object> handler
	) throws IOException {
		if (!authorize(exchange)) {
			writeJson(exchange, 401, Map.of("error", "unauthorized", "message", "Invalid bridge token"));
			return;
		}

		if (!method.equalsIgnoreCase(exchange.getRequestMethod())) {
			writeJson(exchange, 405, Map.of("error", "method_not_allowed"));
			return;
		}

		try (var reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
			T request = GSON.fromJson(reader, requestType);
			Object response = handler.apply(request);
			writeJson(exchange, 200, response);
		}
		catch (JsonSyntaxException exception) {
			writeJson(exchange, 400, Map.of("error", "invalid_json", "message", "Malformed request payload"));
		}
		catch (BridgeUnavailableException exception) {
			writeJson(exchange, 503, Map.of("error", exception.code(), "message", exception.getMessage()));
		}
		catch (Exception exception) {
			Airicraft.LOGGER.warn("Bridge request failed", exception);
			writeJson(exchange, 500, Map.of("error", "internal_error", "message", exception.getMessage()));
		}
	}

	private Object createStatusResponse() {
		return onClientThread(() -> createStatusSnapshot(getClient()));
	}

	private FirstPersonScreenshotService.CapturedScreenshot awaitCameraScreenshot(
		CompletableFuture<FirstPersonScreenshotService.CapturedScreenshot> captureFuture
	) {
		try {
			return captureFuture.get(SCREENSHOT_CAPTURE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException exception) {
			screenshotService().failActiveCapture("capture_timeout", "Screenshot capture timed out");
			throw new BridgeUnavailableException("capture_timeout", "Screenshot capture timed out");
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			screenshotService().failActiveCapture("capture_failed", "Screenshot capture was interrupted");
			throw new BridgeUnavailableException("capture_failed", "Screenshot capture was interrupted");
		}
		catch (ExecutionException exception) {
			if (exception.getCause() instanceof BridgeUnavailableException bridgeUnavailableException) {
				throw bridgeUnavailableException;
			}

			throw new BridgeUnavailableException("capture_failed", "Failed to capture screenshot");
		}
	}

	private MapImageCapture awaitMapCapture(CompletableFuture<MapImageCapture> captureFuture) {
		try {
			return captureFuture.get(MAP_CAPTURE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException exception) {
			throw new BridgeUnavailableException("map_capture_timeout", "Map capture timed out");
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new BridgeUnavailableException("map_capture_failed", "Map capture was interrupted");
		}
		catch (ExecutionException exception) {
			if (exception.getCause() instanceof BridgeUnavailableException bridgeUnavailableException) {
				throw bridgeUnavailableException;
			}
			throw new BridgeUnavailableException("map_capture_failed", "Failed to capture map image");
		}
	}

	private static Map<String, Object> cameraScreenshotPayload(FirstPersonScreenshotService.CapturedScreenshot screenshot) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("format", screenshot.format());
		payload.put("width", screenshot.width());
		payload.put("height", screenshot.height());
		payload.put("sourceWidth", screenshot.sourceWidth());
		payload.put("sourceHeight", screenshot.sourceHeight());
		payload.put("capturedAtMs", screenshot.capturedAtMs());
		payload.put("imageBase64", Base64.getEncoder().encodeToString(screenshot.imageBytes()));
		return payload;
	}

	private static Map<String, Object> mapImagePayload(MapImageCapture capture) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("providerId", capture.providerId());
		payload.put("kind", capture.kind());
		payload.put("format", capture.format());
		payload.put("width", capture.width());
		payload.put("height", capture.height());
		payload.put("capturedAtMs", capture.capturedAtMs());
		payload.put("imageBase64", Base64.getEncoder().encodeToString(capture.imageBytes()));
		return payload;
	}

	static Map<String, Object> mapStatusPayload(MapIntegrationRegistry registry) {
		MapIntegrationRegistry effectiveRegistry = registry == null ? MapIntegrationRegistry.empty() : registry;
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("available", effectiveRegistry.preferred().isPresent());
		payload.put("preferredProvider", effectiveRegistry.preferred().map(MapIntegrationProvider::id).orElse(null));
		payload.put("providers", effectiveRegistry.providers().stream().map(ModBridgeServer::mapProviderPayload).toList());
		return payload;
	}

	private static Map<String, Object> mapProviderPayload(MapIntegrationProvider provider) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("id", provider.id());
		payload.put("available", provider.available());
		payload.put("capabilities", provider.capabilities().values().stream().map(Enum::name).toList());
		return payload;
	}

	static Map<String, Object> mapWaypointsPayload(List<MapWaypoint> waypoints) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("waypoints", waypoints == null ? List.of() : waypoints.stream().map(ModBridgeServer::mapWaypointPayload).toList());
		return payload;
	}

	private static Map<String, Object> mapWaypointPayload(MapWaypoint waypoint) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("providerId", waypoint.providerId());
		payload.put("id", waypoint.id());
		payload.put("name", waypoint.name());
		payload.put("dimension", waypoint.dimension());
		payload.put("x", waypoint.x());
		payload.put("y", waypoint.y());
		payload.put("z", waypoint.z());
		payload.put("color", waypoint.color());
		payload.put("enabled", waypoint.enabled());
		payload.put("showOnMap", waypoint.showOnMap());
		payload.put("showInWorld", waypoint.showInWorld());
		return payload;
	}

	private static Map<String, Object> visionDescribePayload(ai.moeru.airicraft.agent.llm.VisionDescription description) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("format", "text");
		payload.put("capturedAtMs", description.capturedAtMs());
		payload.put("model", description.model());
		payload.put("description", description.text());
		return payload;
	}

	private static BridgeUnavailableException visionBridgeException(LlmBackendException exception) {
		String code = switch (exception.failureType()) {
			case PROVIDER_UNAVAILABLE -> "vision_provider_unavailable";
			case TIMEOUT -> "vision_timeout";
			case PROVIDER_ERROR, PARSE_ERROR -> "vision_failed";
		};
		return new BridgeUnavailableException(code, exception.getMessage());
	}

	private Object createAgentStatusResponse() {
		return onClientThread(() -> {
			var snapshot = agentRuntime().snapshot();
			var plannerSnapshot = agentRuntime().plannerDebugSnapshot();
			Map<String, Object> response = new LinkedHashMap<>();
			response.put("available", true);
			response.put("bridgeAvailable", true);
			response.put("agentAvailable", true);
			response.put("initialized", snapshot.initialized());
			response.put("tickCount", snapshot.tickCount());
			response.put("session", snapshot.session());
			response.put("task", snapshot.task());
			response.put("taskExecution", snapshot.taskExecution());
			response.put("missionExecution", snapshot.missionExecution());
			response.put("activeJob", agentRuntime().activeJob());
			response.put("llmAvailable", agentRuntime().llmAvailable());
			response.put("visionAvailable", agentRuntime().visionAvailable());
			response.put("plannerVisionMode", plannerSnapshot.plannerVisionMode());
			response.put("observability", agentRuntime().observabilityDebugSnapshot());
			response.put("degraded", agentRuntime().isDegraded());
			response.put("plannerJournal", agentRuntime().plannerShellJournal());
			response.put("eventPolicy", eventPolicySummaryPayload());
			response.put("verification", snapshot.verification());
			return response;
		});
	}

	private Object createVerificationResultsResponse() {
		return onClientThread(() -> {
			Map<String, Object> response = new LinkedHashMap<>();
			response.put("available", true);
			response.put("scenarios", agentRuntime().verificationScenarioNames());
			response.put("report", agentRuntime().verificationReport());
			return response;
		});
	}

	private Object createVerificationStatusResponse() {
		return onClientThread(() -> {
			Map<String, Object> response = new LinkedHashMap<>();
			response.put("available", agentRuntime().verificationAvailable());
			response.put("sessionMode", agentRuntime().sessionSnapshot().mode().name());
			response.put("worldLoaded", agentRuntime().sessionSnapshot().worldLoaded());
			response.put("capabilities", VERIFICATION_CAPABILITIES);
			return response;
		});
	}

	private Object createVerificationPlayerResponse() {
		return onClientThread(() -> {
			VerificationPlayerProbe probe = agentRuntime().verificationPlayerProbe();
			LinkedHashMap<String, Object> response = new LinkedHashMap<>();
			response.put("available", true);
			response.put("sessionMode", agentRuntime().sessionSnapshot().mode().name());
			response.put("worldLoaded", agentRuntime().sessionSnapshot().worldLoaded());
			response.putAll(verificationPlayerPayload(probe));
			return response;
		});
	}

	private Object createAgentSessionResponse() {
		return onClientThread(() -> {
			Map<String, Object> response = new LinkedHashMap<>();
			response.put("available", true);
			response.put("session", agentRuntime().sessionSnapshot());
			response.put("lanPublished", agentRuntime().sessionSnapshot().lanPublished());
			response.put("lanPort", agentRuntime().sessionSnapshot().lanPort());
			response.put("primaryInteractionPlayer", agentRuntime().primaryInteractionPlayer().orElse(null));
			response.put("nearbyPlayers", agentRuntime().nearbyPlayers());
			return response;
		});
	}

	private Object createRecentAgentEventsResponse(HttpExchange exchange) {
		long defaultSince = Long.MIN_VALUE;
		long since = getLongQuery(exchange, "since", defaultSince);
		Long sinceSeqNo = since == defaultSince ? null : since;
		return onClientThread(() -> {
			var result = agentRuntime().recentEvents(sinceSeqNo);
			Map<String, Object> response = new LinkedHashMap<>();
			response.put("available", true);
			response.put("oldestSeqNo", result.oldestSeqNo());
			response.put("latestSeqNo", result.latestSeqNo());
			response.put("truncated", result.truncated());
			response.put("events", result.events());
			return response;
		});
	}

	private Object createAgentGoalsResponse() {
		return onClientThread(() -> {
			Map<String, Object> response = new LinkedHashMap<>();
			response.put("available", true);
			response.put("activeGoal", agentRuntime().activeGoal().orElse(null));
			response.put("activeJob", agentRuntime().activeJob());
			response.put("task", agentRuntime().taskSnapshot());
			response.put("taskExecution", agentRuntime().taskExecutionSnapshot());
			response.put("missionExecution", agentRuntime().missionExecutionSnapshot());
			response.put("lastDialogueResponse", agentRuntime().lastDialogueResponse().orElse(null));
			return response;
		});
	}

	private Object createAgentTreeResponse() {
		return onClientThread(() -> {
			Map<String, Object> response = new LinkedHashMap<>();
			response.put("available", true);
			response.put("tree", agentRuntime().behaviorTreeSnapshot());
			return response;
		});
	}

	private Object createAgentDialogueResponse() {
		return onClientThread(() -> {
			Map<String, Object> response = new LinkedHashMap<>();
			response.put("available", true);
			response.put("dialogue", agentRuntime().dialogueSnapshot());
			response.put("conversation", agentRuntime().plannerConversationDebugSnapshot());
			response.put("canonicalConversation", agentRuntime().plannerCanonicalConversationDebugSnapshot());
			response.put("projectedConversation", agentRuntime().plannerProjectedConversationDebugSnapshot());
			response.put("plannerJournal", agentRuntime().plannerShellJournal());
			response.put("lastChatTick", agentRuntime().lastChatTick());
			response.put("lastChatText", agentRuntime().lastChatText());
			return response;
		});
	}

	private Object createAgentContextResponse() {
		return onClientThread(() -> {
			Map<String, Object> response = new LinkedHashMap<>();
			response.put("available", true);
			response.put("planner", agentRuntime().plannerDebugSnapshot());
			response.put("plannerJournal", agentRuntime().plannerShellJournal());
			response.put("contextExcerpt", agentRuntime().plannerContextExcerpt());
			response.put("activeJob", agentRuntime().activeJob());
			response.put("eventPolicy", eventPolicySummaryPayload());
			response.put("task", agentRuntime().taskSnapshot());
			response.put("taskExecution", agentRuntime().taskExecutionSnapshot());
			response.put("missionExecution", agentRuntime().missionExecutionSnapshot());
			return response;
		});
	}

	private Object createAgentDebugStateResponse() {
		return onClientThread(() -> {
			Map<String, Object> response = new LinkedHashMap<>();
			response.put("available", true);
			response.put("planner", agentRuntime().plannerDebugSnapshot());
			response.put("dialogueState", agentRuntime().debugDialogueState());
			response.put("conversation", agentRuntime().plannerConversationDebugSnapshot());
			response.put("canonicalConversation", agentRuntime().plannerCanonicalConversationDebugSnapshot());
			response.put("projectedConversation", agentRuntime().plannerProjectedConversationDebugSnapshot());
			response.put("conversationSources", agentRuntime().debugConversationSources());
			response.put("plannerJournal", agentRuntime().plannerShellJournal());
			response.put("plannerAttempts", agentRuntime().debugPlannerAttempts());
			response.put("taskProgressProbe", agentRuntime().debugCollectResourceState());
			response.put("chatProbe", agentRuntime().debugChatState());
			response.put("eventPipeline", agentRuntime().debugEventPipelineState());
			response.put("timelineTail", agentRuntime().debugTimeline(null).entries());
			return response;
		});
	}

	private Object createAgentDebugTimelineResponse(HttpExchange exchange) {
		long defaultSince = Long.MIN_VALUE;
		long since = getLongQuery(exchange, "since", defaultSince);
		Long sinceEntryId = since == defaultSince ? null : since;
		return onClientThread(() -> {
			var result = agentRuntime().debugTimeline(sinceEntryId);
			Map<String, Object> response = new LinkedHashMap<>();
			response.put("available", true);
			response.put("oldestEntryId", result.oldestEntryId());
			response.put("latestEntryId", result.latestEntryId());
			response.put("truncated", result.truncated());
			response.put("entries", result.entries());
			return response;
		});
	}

	private Object createAgentTasksResponse() {
		return onClientThread(() -> {
			Map<String, Object> response = new LinkedHashMap<>();
			response.put("available", true);
			response.put("activeJob", agentRuntime().activeJob());
			response.put("task", agentRuntime().taskSnapshot());
			response.put("taskExecution", agentRuntime().taskExecutionSnapshot());
			response.put("missionExecution", agentRuntime().missionExecutionSnapshot());
			return response;
		});
	}

	private Object createAgentLedgerResponse() {
		return onClientThread(() -> {
			Map<String, Object> response = new LinkedHashMap<>();
			response.put("available", true);
			response.put("activeJob", agentRuntime().activeJob());
			response.put("mission", agentRuntime().taskSnapshot().mission());
			response.put("ledger", agentRuntime().missionExecutionSnapshot().ledger());
			response.put("lastStepResult", agentRuntime().missionExecutionSnapshot().lastStepResult());
			return response;
		});
	}

	private Object createAgentEvidenceResponse() {
		return onClientThread(() -> {
			Map<String, Object> response = new LinkedHashMap<>();
			response.put("available", true);
			response.put("activeJob", agentRuntime().activeJob());
			response.put("evidence", agentRuntime().missionExecutionSnapshot().evidence());
			response.put("task", agentRuntime().taskSnapshot());
			return response;
		});
	}

	private Object createAgentStepExecutionResponse() {
		return onClientThread(() -> {
			Map<String, Object> response = new LinkedHashMap<>();
			response.put("available", true);
			response.put("activeJob", agentRuntime().activeJob());
			response.put("stepExecution", agentRuntime().missionExecutionSnapshot().lastStepResult());
			response.put("taskExecution", agentRuntime().taskExecutionSnapshot());
			return response;
		});
	}

	private Object createAgentEventPolicyResponse() {
		return onClientThread(this::createAgentEventPolicyPayload);
	}

	private Map<String, Object> createAgentEventPolicyPayload() {
		LinkedHashMap<String, Object> response = new LinkedHashMap<>();
		response.put("available", true);
		response.put("activeRuleCount", agentRuntime().activeEventPolicyRuleCount());
		response.put("recentInterventionCount", agentRuntime().recentEventPolicyInterventionCount());
		response.put("lastDecision", agentRuntime().lastEventPolicyDecision());
		response.put("activeRules", agentRuntime().activeEventPolicyRules());
		response.put("recentInterventions", agentRuntime().recentEventPolicyInterventions());
		return response;
	}

	private Map<String, Object> eventPolicySummaryPayload() {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("activeRuleCount", agentRuntime().activeEventPolicyRuleCount());
		payload.put("recentInterventionCount", agentRuntime().recentEventPolicyInterventionCount());
		if (agentRuntime().lastEventPolicyDecision() != null) {
			payload.put("lastMatchedRuleId", agentRuntime().lastEventPolicyDecision().matchedRuleId());
			payload.put("lastMatchedEffect", agentRuntime().lastEventPolicyDecision().effect().name());
		}
		return payload;
	}

	private Object createFocusResponse() {
		return onClientThread(() -> {
			var client = getClient();
			ensureWorldLoaded(client);
			return Map.of(
				"available", true,
				"worldLoaded", true,
				"focus", describeFocus(client)
			);
		});
	}

	private Object createNearbyEntitiesResponse() {
		return onClientThread(() -> {
			MinecraftClient client = getClient();
			ensureWorldLoaded(client);
			List<NearbyEntityService.NearbyEntitySnapshot> entities = NearbyEntityService.listNearbyEntities(client);
			LinkedHashMap<String, Object> response = new LinkedHashMap<>();
			response.put("available", true);
			response.put("worldLoaded", true);
			response.put("nearbyRadius", EntitySelectorResolver.DEFAULT_NEARBY_RADIUS_BLOCKS);
			response.put("entityCount", entities.size());
			response.put("entities", entities.stream().map(ModBridgeServer::nearbyEntityPayload).toList());
			return response;
		});
	}

	private Object createWorldSnapshotResponse(HttpExchange exchange) {
		int x = getIntQuery(exchange, "x", Integer.MIN_VALUE);
		int y = getIntQuery(exchange, "y", Integer.MIN_VALUE);
		int z = getIntQuery(exchange, "z", Integer.MIN_VALUE);
		int radius = Math.max(0, Math.min(getIntQuery(exchange, "radius", 1), 4));

		return onClientThread(() -> {
			var client = getClient();
			ensureWorldLoaded(client);

			BlockPos center;
			if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE) {
				center = Objects.requireNonNull(client.player).getBlockPos();
			}
			else {
				center = new BlockPos(x, y, z);
			}

			if (!isChunkLoaded(client.world, center)) {
				throw new BridgeUnavailableException("chunk_not_loaded", "Target chunk is not loaded");
			}

			return Map.of(
				"available", true,
				"worldLoaded", true,
				"center", blockPos(center),
				"chunk", Map.of("loaded", true),
				"radius", radius,
				"blocks", collectBlocks(client.world, center, radius)
			);
		});
	}

	private Map<String, Object> createStatusSnapshot(MinecraftClient client) {
		var world = client.world;
		var player = client.player;
		boolean worldLoaded = world != null && player != null;

		Map<String, Object> response = new LinkedHashMap<>();
		response.put("available", true);
		response.put("bridgeAvailable", true);
		response.put("worldLoaded", worldLoaded);
		response.put("sessionState", sessionState(client));
		response.put("currentScreen", currentScreenName(client));
		response.put("canJoinWorldOrServer", !worldLoaded);

		if (!worldLoaded) {
			response.put("state", "world_not_loaded");
			return response;
		}

		response.put("state", "ready");
		response.put("dimension", world.getRegistryKey().getValue().toString());
		response.put("player", Map.of(
			"name", player.getName().getString(),
			"x", player.getX(),
			"y", player.getY(),
			"z", player.getZ(),
			"blockPos", blockPos(player.getBlockPos())
		));
		response.put("focus", describeFocus(client));
		return response;
	}

	private List<Map<String, Object>> collectBlocks(World world, BlockPos center, int radius) {
		List<Map<String, Object>> blocks = new ArrayList<>();
		for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
			for (int y = center.getY() - radius; y <= center.getY() + radius; y++) {
				for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
					var pos = new BlockPos(x, y, z);
					if (!isChunkLoaded(world, pos)) {
						blocks.add(Map.of(
							"pos", blockPos(pos),
							"chunk", Map.of("loaded", false)
						));
						continue;
					}

					BlockState blockState = world.getBlockState(pos);
					blocks.add(Map.of(
						"pos", blockPos(pos),
						"id", Registries.BLOCK.getId(blockState.getBlock()).toString(),
						"chunk", Map.of("loaded", true),
						"state", Map.of(
							"properties", blockProperties(blockState)
						)
					));
				}
			}
		}
		return blocks;
	}

	private Map<String, Object> describeFocus(MinecraftClient client) {
		HitResult hitResult = client.crosshairTarget;
		if (hitResult == null) {
			return Map.of(
				"type", "miss",
				"crosshair", Map.of(
					"hitPos", vector(client.player == null ? Vec3d.ZERO : client.player.getCameraPosVec(1.0F))
				)
			);
		}

		if (hitResult instanceof BlockHitResult blockHit) {
			BlockPos pos = blockHit.getBlockPos();
			World world = Objects.requireNonNull(client.world);
			if (!isChunkLoaded(world, pos)) {
				throw new BridgeUnavailableException("chunk_not_loaded", "Target chunk is not loaded");
			}

			BlockState state = world.getBlockState(pos);
			FluidState fluidState = state.getFluidState();
			return Map.of(
				"type", "block",
				"hitPos", vector(blockHit.getPos()),
				"block", Map.of(
					"id", Registries.BLOCK.getId(state.getBlock()).toString(),
					"pos", blockPos(pos),
					"face", blockHit.getSide().asString(),
					"state", Map.of("properties", blockProperties(state)),
					"isAir", state.isAir(),
					"isReplaceable", state.isReplaceable(),
					"hasBlockEntity", state.hasBlockEntity(),
					"light", Map.of(
						"emitted", state.getLuminance(),
						"local", world.getLightLevel(pos)
					),
					"fluid", Map.of(
						"id", Registries.FLUID.getId(fluidState.getFluid()).toString()
					),
					"chunk", Map.of("loaded", true)
				)
			);
		}

		if (hitResult instanceof EntityHitResult entityHit) {
			Entity entity = entityHit.getEntity();
			Map<String, Object> entityPayload = new LinkedHashMap<>();
			entityPayload.put("id", entity.getId());
			entityPayload.put("uuid", entity.getUuidAsString());
			entityPayload.put("type", Registries.ENTITY_TYPE.getId(entity.getType()).toString());
			entityPayload.put("name", entity.getName().getString());
			entityPayload.put("pos", Map.of(
				"x", entity.getX(),
				"y", entity.getY(),
				"z", entity.getZ()
			));
			entityPayload.put("alive", entity.isAlive());
			if (entity instanceof LivingEntity livingEntity) {
				entityPayload.put("health", livingEntity.getHealth());
				entityPayload.put("maxHealth", livingEntity.getMaxHealth());
			}

			return Map.of(
				"type", "entity",
				"hitPos", vector(entityHit.getPos()),
				"entity", entityPayload
			);
		}

		return Map.of(
			"type", "miss",
			"crosshair", Map.of("hitPos", vector(hitResult.getPos()))
		);
	}

	private static Map<String, Object> nearbyEntityPayload(NearbyEntityService.NearbyEntitySnapshot entity) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("id", entity.entityId());
		payload.put("uuid", entity.uuid());
		payload.put("name", entity.name());
		payload.put("entityTypeId", entity.entityTypeId());
		payload.put("distance", entity.distance());
		payload.put("alive", entity.alive());
		payload.put("isPlayer", entity.isPlayer());
		payload.put("pos", Map.of(
			"x", entity.x(),
			"y", entity.y(),
			"z", entity.z()
		));
		if (entity.health() != null) {
			payload.put("health", entity.health());
		}
		if (entity.maxHealth() != null) {
			payload.put("maxHealth", entity.maxHealth());
		}
		return payload;
	}

	private static Map<String, Object> blockProperties(BlockState state) {
		Map<String, Object> properties = new LinkedHashMap<>();
		for (Property<?> property : state.getProperties()) {
			properties.put(property.getName(), propertyValue(state, property));
		}
		return properties;
	}

	private static <T extends Comparable<T>> Object propertyValue(BlockState state, Property<T> property) {
		return property.name(state.get(property));
	}

	private static Map<String, Integer> blockPos(BlockPos pos) {
		return Map.of("x", pos.getX(), "y", pos.getY(), "z", pos.getZ());
	}

	private static Map<String, Double> vector(Vec3d vec) {
		return Map.of("x", vec.x, "y", vec.y, "z", vec.z);
	}

	private static boolean isChunkLoaded(World world, BlockPos pos) {
		return world.isChunkLoaded(pos);
	}

	private static String currentScreenName(MinecraftClient client) {
		if (client.currentScreen == null) {
			return client.world == null ? "none" : "in_game";
		}
		return client.currentScreen.getClass().getSimpleName();
	}

	private static String sessionState(MinecraftClient client) {
		return client.world != null && client.player != null ? "in_world" : "out_of_world";
	}

	private Object agentDebugCompactPayload(boolean started, boolean completed, long timeoutMillis) {
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("available", true);
		response.put("started", started);
		response.put("completed", completed);
		response.put("timeoutMs", timeoutMillis);
		response.put("planner", agentRuntime().plannerDebugSnapshot());
		response.put("plannerJournal", agentRuntime().plannerShellJournal());
		return response;
	}

	private static long requestedDebugCompactionTimeoutMillis(Integer timeoutMs) {
		if (timeoutMs == null) {
			return DEBUG_COMPACTION_DEFAULT_TIMEOUT_MILLIS;
		}
		if (timeoutMs <= 0) {
			throw new BridgeUnavailableException("invalid_request", "timeoutMs must be positive");
		}
		return Math.min(timeoutMs.longValue(), DEBUG_COMPACTION_MAX_TIMEOUT_MILLIS);
	}

	private static String compactionFailureCode(ai.moeru.airicraft.agent.llm.LlmFailureType failureType) {
		if (failureType == null) {
			return "planner_compaction_failed";
		}
		return switch (failureType) {
			case PROVIDER_UNAVAILABLE -> "planner_unavailable";
			case TIMEOUT -> "planner_timeout";
			case PROVIDER_ERROR -> "planner_provider_error";
			case PARSE_ERROR -> "planner_parse_error";
		};
	}

	private static String nonEmpty(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}

	private boolean authorize(HttpExchange exchange) {
		Headers headers = exchange.getRequestHeaders();
		var authorization = headers.getFirst("Authorization");
		return authorization != null && authorization.equals("Bearer " + token);
	}

	private <T> T onClientThread(Supplier<T> supplier) {
		var client = getClient();
		CompletableFuture<T> future = new CompletableFuture<>();
		client.execute(() -> {
			try {
				future.complete(supplier.get());
			}
			catch (Throwable throwable) {
				future.completeExceptionally(throwable);
			}
		});

		try {
			return future.get(5, TimeUnit.SECONDS);
		}
		catch (ExecutionException exception) {
			if (exception.getCause() instanceof BridgeUnavailableException bridgeUnavailableException) {
				throw bridgeUnavailableException;
			}

			throw new IllegalStateException("Bridge request failed on Minecraft client thread", exception.getCause());
		}
		catch (Exception exception) {
			throw new IllegalStateException("Timed out waiting for Minecraft client thread", exception);
		}
	}

	private MinecraftClient getClient() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) {
			throw new BridgeUnavailableException("minecraft_unavailable", "Minecraft client is not initialized");
		}
		return client;
	}

	private void ensureWorldLoaded(MinecraftClient client) {
		if (client.world == null || client.player == null) {
			throw new BridgeUnavailableException("world_not_loaded", "No world is currently loaded");
		}
	}

	private static int getIntQuery(HttpExchange exchange, String key, int defaultValue) {
		String value = getQuery(exchange, key);
		if (value == null) {
			return defaultValue;
		}

		try {
			return Integer.parseInt(value);
		}
		catch (NumberFormatException ignored) {
			return defaultValue;
		}
	}

	private static long getLongQuery(HttpExchange exchange, String key, long defaultValue) {
		String raw = getQuery(exchange, key);
		if (raw == null || raw.isBlank()) {
			return defaultValue;
		}

		try {
			return Long.parseLong(raw);
		}
		catch (NumberFormatException exception) {
			throw new BridgeUnavailableException("invalid_request", "Query parameter " + key + " must be an integer");
		}
	}

	private static String getQuery(HttpExchange exchange, String key) {
		return parseQuery(exchange.getRequestURI().getRawQuery()).get(key);
	}

	private static Map<String, String> parseQuery(String rawQuery) {
		Map<String, String> query = new HashMap<>();
		if (rawQuery == null || rawQuery.isBlank()) {
			return query;
		}

		for (String part : rawQuery.split("&")) {
			var split = part.split("=", 2);
			if (split.length == 2) {
				query.put(
					URLDecoder.decode(split[0], StandardCharsets.UTF_8),
					URLDecoder.decode(split[1], StandardCharsets.UTF_8)
				);
			}
		}

		return query;
	}

	private static void writeJson(HttpExchange exchange, int statusCode, Object body) throws IOException {
		byte[] response = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(statusCode, response.length);

		try (OutputStream outputStream = exchange.getResponseBody()) {
			outputStream.write(response);
		}
	}

	private static TaskType parseTaskType(String value) {
		try {
			return TaskType.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
		}
		catch (RuntimeException exception) {
			throw new BridgeUnavailableException("invalid_request", "Unknown task type: " + value);
		}
	}

	private static TaskResourceKind parseTaskResourceKind(String value) {
		try {
			return TaskResourceKind.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
		}
		catch (RuntimeException exception) {
			throw new BridgeUnavailableException("invalid_request", "Unknown task resource kind: " + value);
		}
	}

	private static String generateToken() {
		byte[] bytes = new byte[24];
		RANDOM.nextBytes(bytes);
		StringBuilder builder = new StringBuilder(bytes.length * 2);
		for (byte current : bytes) {
			builder.append(String.format("%02x", current));
		}
		return builder.toString();
	}

	private static int parseColor(String color) {
		if (color == null || color.isBlank()) {
			return 0x6000FF00;
		}

		String normalized = color.startsWith("#") ? color.substring(1) : color;
		try {
			if (normalized.length() == 6) {
				return (int) (0x60000000L | Long.parseLong(normalized, 16));
			}
			if (normalized.length() == 8) {
				return (int) Long.parseLong(normalized, 16);
			}
		}
		catch (NumberFormatException ignored) {
		}

		throw new BridgeUnavailableException("invalid_request", "Color must be a 6 or 8 digit hex value");
	}

	private static Long safeDurationMs(Long durationMs) {
		if (durationMs == null) {
			return null;
		}

		if (durationMs <= 0) {
			throw new BridgeUnavailableException("invalid_request", "durationMs must be positive when provided");
		}

		return Math.min(durationMs, (long) Integer.MAX_VALUE);
	}

	private static String highlightKind(String kind) {
		if (kind == null || kind.isBlank()) {
			return "block";
		}
		if ("block".equals(kind) || "region".equals(kind)) {
			return kind;
		}
		throw new BridgeUnavailableException("invalid_request", "kind must be block or region");
	}

	private LinkedHashMap<String, Object> verificationPlayerActionResponse(
		VerificationPlayerProbe probe,
		String resultKey,
		boolean resultValue
	) {
		LinkedHashMap<String, Object> response = new LinkedHashMap<>();
		response.put("available", true);
		response.put("sessionMode", agentRuntime().sessionSnapshot().mode().name());
		response.put("worldLoaded", agentRuntime().sessionSnapshot().worldLoaded());
		response.put(resultKey, resultValue);
		response.putAll(verificationPlayerPayload(probe));
		return response;
	}

	private static Map<String, Object> verificationPlayerPayload(VerificationPlayerProbe probe) {
		return probe == null ? Map.of() : probe.asMap();
	}

	private static boolean isFinite(Double value) {
		return value != null && Double.isFinite(value);
	}

	private static String defaultDebugSender(MinecraftClient client) {
		if (client != null && client.player != null && client.player.getName() != null) {
			return client.player.getName().getString();
		}
		if (client != null && client.getSession() != null && client.getSession().getUsername() != null) {
			return client.getSession().getUsername();
		}
		throw new BridgeUnavailableException("minecraft_unavailable", "Minecraft session is not initialized");
	}

	private static BlockPos requiredBlockPos(Integer x, Integer y, Integer z, String fields) {
		if (x == null || y == null || z == null) {
			throw new BridgeUnavailableException("invalid_request", "Missing coordinates: " + fields);
		}
		return new BlockPos(x, y, z);
	}

	private HighlightManager highlightManager() {
		return Objects.requireNonNull(highlightManagerSupplier.get(), "highlightManager");
	}

	private EmbodiedAgentRuntime agentRuntime() {
		return Objects.requireNonNull(agentRuntimeSupplier.get(), "agentRuntime");
	}

	private MapIntegrationProvider mapProvider(String providerId) {
		MapIntegrationRegistry registry = MapIntegrationBridge.registry();
		if (providerId != null && !providerId.isBlank()) {
			return registry.provider(providerId)
				.filter(MapIntegrationProvider::available)
				.orElseThrow(() -> new BridgeUnavailableException("map_provider_unavailable", "Map provider unavailable: " + providerId));
		}
		return registry.preferred()
			.orElseThrow(() -> new BridgeUnavailableException("map_provider_unavailable", "No map provider is available"));
	}

	private FirstPersonScreenshotService screenshotService() {
		return Objects.requireNonNull(screenshotServiceSupplier.get(), "screenshotService");
	}

	private record HighlightRequest(
		String kind,
		Integer x,
		Integer y,
		Integer z,
		Integer x1,
		Integer y1,
		Integer z1,
		Integer x2,
		Integer y2,
		Integer z2,
		String color,
		Long durationMs,
		String overlayText
	) {
	}

	private record JoinWorldRequest(String worldId) {
	}

	private record JoinServerRequest(String serverId) {
	}

	private record LookAtRequest(Double x, Double y, Double z) {
	}

	private record EntityInteractionRequest(String uuid, String name, String entityTypeId, String itemId, String mode) {
	}

	private record VerificationRunRequest(String scenario) {
	}

	private record VerificationPlayerTeleportRequest(Double x, Double y, Double z) {
	}

	private record VerificationPlayerVelocityRequest(Double x, Double y, Double z) {
	}

	private record VerificationPlayerGameModeRequest(String mode) {
	}

	private record VerificationCommandRequest(String command) {
	}

	private record VisionDescribeRequest(String prompt) {
	}

	private record MapWaypointRequest(
		String provider,
		String id,
		String name,
		String dimension,
		Integer x,
		Integer y,
		Integer z,
		Integer color,
		Boolean enabled,
		Boolean showOnMap,
		Boolean showInWorld
	) {
	}

	private record MapImageRequestBody(
		String provider,
		String kind,
		String dimension,
		Integer radiusChunks,
		Integer zoom,
		Boolean grid,
		Integer originX,
		Integer originZ
	) {
	}

	private record AgentTaskRequest(String type, String resourceKind, Integer quantity) {
	}

	private record DebugChatRequest(String senderName, String message) {
	}

	private static final class DebugCompactRequest {
		private Boolean wait;
		private Integer timeoutMs;

		private Boolean waitValue() {
			return wait;
		}

		private Integer timeoutMs() {
			return timeoutMs;
		}
	}

}
