package ai.moeru.airicraft.debug;

import ai.moeru.airicraft.BridgeUnavailableException;
import ai.moeru.airicraft.FirstPersonScreenshotService;
import ai.moeru.airicraft.agent.EmbodiedAgentRuntime;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class ClientTickDebugRuntime {
	private static final int SNAPSHOT_SCHEMA_VERSION = 1;

	private final ClientTickDebugController controller;
	private final FirstPersonScreenshotService screenshotService;

	public ClientTickDebugRuntime(FirstPersonScreenshotService screenshotService) {
		this(new ClientTickDebugController(), screenshotService);
	}

	ClientTickDebugRuntime(
		ClientTickDebugController controller,
		FirstPersonScreenshotService screenshotService
	) {
		this.controller = Objects.requireNonNull(controller, "controller");
		this.screenshotService = Objects.requireNonNull(screenshotService, "screenshotService");
	}

	public CompletableFuture<ClientTickDebugController.ClientTickCapture> pause(MinecraftClient client) {
		requireWorld(client);
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
		return controller.allowVanillaTick(vanillaAllowsTick);
	}

	public void onClientTickStarted() {
		controller.onClientTickStarted();
	}

	public void onClientTickCompleted(MinecraftClient client, EmbodiedAgentRuntime runtime) {
		controller.onClientTickCompleted().ifPresent(intent -> beginCapture(client, runtime, intent));
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

	public void reset(String code, String message) {
		boolean frameCaptureActive = controller.status().phase() == ClientTickDebugController.Phase.WAITING_FOR_FRAME;
		controller.reset(code, message);
		if (frameCaptureActive) {
			screenshotService.failActiveCapture(code, message);
		}
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
		ClientWorld world = Objects.requireNonNull(client.world, "client.world");
		ClientPlayerEntity player = Objects.requireNonNull(client.player, "client.player");
		var planner = runtime.plannerDebugSnapshot();
		return new ClientTickDebugController.ClientTickSnapshot(
			SNAPSHOT_SCHEMA_VERSION,
			intent.debugSessionId(),
			intent.captureId(),
			intent.snapshotId(),
			intent.clientTickId(),
			System.currentTimeMillis(),
			world.getRegistryKey().getValue().toString(),
			world.getTime(),
			world.getTimeOfDay(),
			new ClientTickDebugController.PlayerSnapshot(
				player.getX(),
				player.getY(),
				player.getZ(),
				player.getBlockX(),
				player.getBlockY(),
				player.getBlockZ(),
				player.getYaw(),
				player.getPitch(),
				player.getHealth(),
				player.getHungerManager().getFoodLevel(),
				player.getAir()
			),
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
