package ai.moeru.airicraft.debug;

import ai.moeru.airicraft.BridgeUnavailableException;
import ai.moeru.airicraft.FirstPersonScreenshotService;
import ai.moeru.airicraft.agent.EmbodiedAgentRuntime;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class ClientTickDebugRuntime {
	private static final int SNAPSHOT_SCHEMA_VERSION = 2;

	private final ClientTickDebugController controller;
	private final FirstPersonScreenshotService screenshotService;
	private final ClientTickTraceRecorder traceRecorder;
	private final ClientTickWorldQueryService worldQueryService = new ClientTickWorldQueryService();
	private final ClientTickEntityQueryService entityQueryService = new ClientTickEntityQueryService();

	public ClientTickDebugRuntime(FirstPersonScreenshotService screenshotService) {
		this(new ClientTickDebugController(), screenshotService);
	}

	ClientTickDebugRuntime(
		ClientTickDebugController controller,
		FirstPersonScreenshotService screenshotService
	) {
		this(controller, screenshotService, new ClientTickTraceRecorder());
	}

	ClientTickDebugRuntime(
		ClientTickDebugController controller,
		FirstPersonScreenshotService screenshotService,
		ClientTickTraceRecorder traceRecorder
	) {
		this.controller = Objects.requireNonNull(controller, "controller");
		this.screenshotService = Objects.requireNonNull(screenshotService, "screenshotService");
		this.traceRecorder = Objects.requireNonNull(traceRecorder, "traceRecorder");
	}

	public CompletableFuture<ClientTickDebugController.ClientTickCapture> pause(MinecraftClient client) {
		requireWorld(client);
		if (traceRecorder.status().active()) {
			throw new BridgeUnavailableException("trace_active", "Stop the client tick trace before pausing client ticks");
		}
		return controller.pause();
	}

	public CompletableFuture<ClientTickDebugController.ClientTickCapture> step(
		MinecraftClient client,
		String debugSessionId,
		long pauseEpoch
	) {
		requireWorld(client);
		return controller.step(debugSessionId, pauseEpoch);
	}

	public void continueRunning(String debugSessionId, long pauseEpoch) {
		controller.continueRunning(debugSessionId, pauseEpoch);
	}

	public boolean allowVanillaTick(boolean vanillaAllowsTick) {
		return controller.allowVanillaTick(vanillaAllowsTick) && !traceRecorder.waitingForFrame();
	}

	public void onClientTickStarted() {
		controller.onClientTickStarted();
	}

	public void onClientTickCompleted(MinecraftClient client, EmbodiedAgentRuntime runtime) {
		Optional<ClientTickDebugController.CaptureIntent> intent = controller.onClientTickCompleted();
		intent.ifPresent(value -> beginCapture(client, runtime, value));
		if (intent.isEmpty()) {
			traceRecorder.activeTrace().ifPresent(trace -> captureTraceTick(client, runtime, trace));
		}
	}

	public void beforeFirstPersonFrame(MinecraftClient client, EmbodiedAgentRuntime runtime) {
		controller.onRenderedFrameBoundary().ifPresent(intent -> beginCapture(client, runtime, intent));
	}

	public ClientTickDebugController.DebugStatus status() {
		return controller.status();
	}

	public ClientTickDebugController.ClientTickSnapshot requireCurrentSnapshot(String snapshotId) {
		return controller.requireCurrentSnapshot(snapshotId);
	}

	public ClientTickTraceRecorder.TraceStatus startTrace(
		MinecraftClient client,
		ClientTickTraceRecorder.TraceConfig config
	) {
		requireWorld(client);
		ClientTickDebugController.DebugStatus debugStatus = controller.status();
		if (debugStatus.phase() != ClientTickDebugController.Phase.RUNNING) {
			throw new BridgeUnavailableException("debug_busy", "Continue normal client ticks before starting a trace");
		}
		return traceRecorder.start(config, debugStatus.clientTickId());
	}

	public ClientTickTraceRecorder.TraceStatus traceStatus() {
		return traceRecorder.status();
	}

	public ClientTickTraceRecorder.TraceStatus stopTrace(String traceId) {
		return traceRecorder.stop(traceId);
	}

	public ClientTickTraceRecorder.TraceRecordPage traceRecords(
		String traceId,
		Long sinceClientTickId,
		int limit
	) {
		return traceRecorder.records(traceId, sinceClientTickId, limit);
	}

	public void reset(String code, String message) {
		boolean frameCaptureActive = controller.status().phase() == ClientTickDebugController.Phase.WAITING_FOR_FRAME;
		controller.reset(code, message);
		traceRecorder.reset();
		if (frameCaptureActive) {
			screenshotService.failActiveCapture(code, message);
		}
	}

	private void captureTraceTick(
		MinecraftClient client,
		EmbodiedAgentRuntime runtime,
		ClientTickTraceRecorder.ActiveTrace trace
	) {
		long clientTickId = controller.status().clientTickId();
		String captureId = trace.traceId() + ":capture:" + clientTickId;
		String snapshotId = trace.traceId() + ":tick:" + clientTickId;
		ClientTickDebugController.ClientTickSnapshot snapshot;
		try {
			snapshot = captureSnapshot(
				client,
				runtime,
				trace.traceId(),
				captureId,
				snapshotId,
				clientTickId
			);
		}
		catch (RuntimeException exception) {
			traceRecorder.record(new ClientTickTraceRecorder.TraceTickRecord(
				trace.traceId(),
				clientTickId,
				System.currentTimeMillis(),
				null,
				null,
				null,
				null,
				null,
				Map.of("capture", traceError(exception))
			));
			return;
		}

		var infos = trace.config().infos();
		ClientTickTraceRecorder.TraceMetadata metadata = infos.contains(ClientTickTraceRecorder.TraceInfo.METADATA)
			? new ClientTickTraceRecorder.TraceMetadata(
				snapshot.schemaVersion(),
				snapshot.dimensionId(),
				snapshot.worldTime(),
				snapshot.timeOfDay(),
				snapshot.plannerGeneration(),
				snapshot.plannerPhase()
			)
			: null;
		ClientTickPlayerSnapshot playerState = infos.contains(ClientTickTraceRecorder.TraceInfo.PLAYER_STATE)
			? snapshot.player()
			: null;
		ClientTickEntityQueryService.EntityQueryResult entities = null;
		Map<String, Object> blocks = null;
		Map<String, ClientTickTraceRecorder.TraceError> errors = new LinkedHashMap<>();
		if (infos.contains(ClientTickTraceRecorder.TraceInfo.ENTITIES)) {
			try {
				entities = entityQueryService.capture(
					client,
					snapshot,
					trace.config().entityQuery().resolve(snapshot.player().position())
				);
			}
			catch (RuntimeException exception) {
				errors.put(ClientTickTraceRecorder.TraceInfo.ENTITIES.wireName(), traceError(exception));
			}
		}
		if (infos.contains(ClientTickTraceRecorder.TraceInfo.BLOCKS)) {
			try {
				int blockCount = Math.toIntExact(trace.config().blockRegion().totalCellCount());
				blocks = worldQueryService.scanBox(client, snapshot, trace.config().blockRegion(), 0L, blockCount);
			}
			catch (RuntimeException exception) {
				errors.put(ClientTickTraceRecorder.TraceInfo.BLOCKS.wireName(), traceError(exception));
			}
		}

		ClientTickTraceRecorder.TraceFrame frame = infos.contains(ClientTickTraceRecorder.TraceInfo.FRAME)
			? ClientTickTraceRecorder.TraceFrame.pending(snapshot.capturedAtMs())
			: null;
		traceRecorder.record(new ClientTickTraceRecorder.TraceTickRecord(
			trace.traceId(),
			clientTickId,
			snapshot.capturedAtMs(),
			metadata,
			playerState,
			entities,
			blocks,
			frame,
			errors
		));
		if (frame != null) {
			requestTraceFrame(client, trace.traceId(), clientTickId);
		}
	}

	private void requestTraceFrame(MinecraftClient client, String traceId, long clientTickId) {
		try {
			screenshotService.requestCapture(client).whenComplete((screenshot, throwable) -> {
				if (throwable != null) {
					BridgeUnavailableException failure = bridgeFailure(throwable);
					traceRecorder.completeFrame(
						traceId,
						clientTickId,
						ClientTickTraceRecorder.TraceFrame.failed(
							failure.code(), failure.getMessage(), System.currentTimeMillis()
						)
					);
					return;
				}
				traceRecorder.completeFrame(
					traceId,
					clientTickId,
					ClientTickTraceRecorder.TraceFrame.captured(
						screenshot.format(),
						screenshot.width(),
						screenshot.height(),
						screenshot.sourceWidth(),
						screenshot.sourceHeight(),
						screenshot.capturedAtMs(),
						screenshot.imageBytes()
					)
				);
			});
		}
		catch (RuntimeException exception) {
			ClientTickTraceRecorder.TraceError error = traceError(exception);
			traceRecorder.completeFrame(
				traceId,
				clientTickId,
				ClientTickTraceRecorder.TraceFrame.failed(
					error.code(), error.message(), System.currentTimeMillis()
				)
			);
		}
	}

	private static ClientTickTraceRecorder.TraceError traceError(RuntimeException exception) {
		if (exception instanceof BridgeUnavailableException bridgeException) {
			return new ClientTickTraceRecorder.TraceError(bridgeException.code(), bridgeException.getMessage());
		}
		String message = Optional.ofNullable(exception.getMessage())
			.filter(value -> !value.isBlank())
			.orElse(exception.getClass().getSimpleName());
		return new ClientTickTraceRecorder.TraceError("trace_capture_failed", message);
	}

	private void beginCapture(
		MinecraftClient client,
		EmbodiedAgentRuntime runtime,
		ClientTickDebugController.CaptureIntent intent
	) {
		ClientTickDebugController.ClientTickSnapshot snapshot = captureSnapshot(client, runtime, intent);
		controller.attachSnapshot(intent, snapshot);
		try {
			screenshotService.requestCapture(client).whenComplete((screenshot, throwable) -> {
				try {
					if (throwable != null) {
						BridgeUnavailableException failure = bridgeFailure(throwable);
						controller.failFrame(intent, failure.code(), failure.getMessage());
						return;
					}
					controller.completeFrame(intent, ClientTickDebugController.ClientTickFrame.captured(
						screenshot.format(),
						screenshot.width(),
						screenshot.height(),
						screenshot.sourceWidth(),
						screenshot.sourceHeight(),
						screenshot.capturedAtMs(),
						screenshot.imageBytes()
					));
				}
				catch (ClientTickDebugController.DebugStateException ignored) {
					// A world leave or reload invalidated the pending capture.
				}
			});
		}
		catch (BridgeUnavailableException exception) {
			controller.failFrame(intent, exception.code(), exception.getMessage());
		}
	}

	private static ClientTickDebugController.ClientTickSnapshot captureSnapshot(
		MinecraftClient client,
		EmbodiedAgentRuntime runtime,
		ClientTickDebugController.CaptureIntent intent
	) {
		return captureSnapshot(
			client,
			runtime,
			intent.debugSessionId(),
			intent.captureId(),
			intent.snapshotId(),
			intent.clientTickId()
		);
	}

	private static ClientTickDebugController.ClientTickSnapshot captureSnapshot(
		MinecraftClient client,
		EmbodiedAgentRuntime runtime,
		String debugSessionId,
		String captureId,
		String snapshotId,
		long clientTickId
	) {
		ClientWorld world = Objects.requireNonNull(client.world, "client.world");
		ClientPlayerEntity player = Objects.requireNonNull(client.player, "client.player");
		var planner = runtime.plannerDebugSnapshot();
		return new ClientTickDebugController.ClientTickSnapshot(
			SNAPSHOT_SCHEMA_VERSION,
			debugSessionId,
			captureId,
			snapshotId,
			clientTickId,
			System.currentTimeMillis(),
			world.getRegistryKey().getValue().toString(),
			world.getTime(),
			world.getTimeOfDay(),
			ClientTickPlayerSnapshotFactory.capture(client, player),
			planner.activeGeneration(),
			planner.currentPhase()
		);
	}

	private static void requireWorld(MinecraftClient client) {
		if (client == null || client.world == null || client.player == null) {
			throw new BridgeUnavailableException("world_not_loaded", "No world is currently loaded");
		}
	}

	private static BridgeUnavailableException bridgeFailure(Throwable throwable) {
		Throwable current = throwable;
		while (current.getCause() != null && current != current.getCause()) {
			if (current instanceof BridgeUnavailableException bridgeUnavailableException) {
				return bridgeUnavailableException;
			}
			current = current.getCause();
		}
		if (current instanceof BridgeUnavailableException bridgeUnavailableException) {
			return bridgeUnavailableException;
		}
		String message = Optional.ofNullable(current.getMessage()).filter(value -> !value.isBlank()).orElse("Failed to capture debug frame");
		return new BridgeUnavailableException("capture_failed", message);
	}
}
