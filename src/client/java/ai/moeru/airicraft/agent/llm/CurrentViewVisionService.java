package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.BridgeUnavailableException;
import ai.moeru.airicraft.FirstPersonScreenshotService;
import ai.moeru.airicraft.agent.control.LookController;
import ai.moeru.airicraft.agent.observability.AgentObservability;
import ai.moeru.airicraft.agent.observability.NoopObservability;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public final class CurrentViewVisionService implements CurrentViewVisionTool {
	public static final String DEFAULT_DESCRIBE_PROMPT =
		"Describe the current Minecraft first-person view in one short paragraph. " +
			"Mention terrain, nearby landmarks, hazards, structures, and whether the scene feels indoors or outdoors.";

	private final FirstPersonScreenshotService screenshotService;
	private final VisionBackend visionBackend;
	private final Supplier<MinecraftClient> clientSupplier;
	private final ExecutorService executorService;
	private final AgentObservability observability;
	private final LookController lookController = new LookController();

	public CurrentViewVisionService(
		FirstPersonScreenshotService screenshotService,
		VisionBackend visionBackend,
		Supplier<MinecraftClient> clientSupplier
	) {
		this(screenshotService, visionBackend, clientSupplier, NoopObservability.INSTANCE);
	}

	public CurrentViewVisionService(
		FirstPersonScreenshotService screenshotService,
		VisionBackend visionBackend,
		Supplier<MinecraftClient> clientSupplier,
		AgentObservability observability
	) {
		this.screenshotService = Objects.requireNonNull(screenshotService, "screenshotService");
		this.visionBackend = Objects.requireNonNull(visionBackend, "visionBackend");
		this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
		this.observability = Objects.requireNonNull(observability, "observability");
		this.executorService = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "airicraft-vision");
			thread.setDaemon(true);
			return thread;
		});
	}

	@Override
	public boolean isConfigured() {
		return visionBackend.isConfigured();
	}

	@Override
	public CompletableFuture<FirstPersonScreenshotService.CapturedScreenshot> requestCapture() {
		return requestCapture(ViewCaptureRequest.current()).thenApply(ViewCaptureResult::screenshot);
	}

	@Override
	public CompletableFuture<ViewCaptureResult> requestCapture(ViewCaptureRequest request) {
		MinecraftClient client = clientSupplier.get();
		if (client == null || client.world == null || client.player == null) {
			return CompletableFuture.failedFuture(
				new BridgeUnavailableException("world_not_loaded", "No world is currently loaded")
			);
		}

		try {
			Context captureContext = observability.startChildSpan(
				AgentObservability.TOOL_CAPTURE_SPAN_NAME,
				Context.current()
			);
			CompletableFuture<ViewCaptureResult> captureFuture = new CompletableFuture<>();
			Runnable captureTask = () -> {
				try {
					List<String> metadataLines = prepareCaptureTarget(client, request);
					screenshotService.requestCapture(client).whenComplete((capture, throwable) -> {
						if (throwable == null) {
							captureFuture.complete(new ViewCaptureResult(capture, metadataLines));
						}
						else {
							captureFuture.completeExceptionally(throwable);
						}
					});
				}
				catch (RuntimeException exception) {
					captureFuture.completeExceptionally(exception);
				}
			};
			if (client.isOnThread()) {
				captureTask.run();
			}
			else {
				client.execute(captureTask);
			}
			return captureFuture.whenComplete((captureResult, throwable) -> {
				try {
					if (captureResult != null && captureResult.screenshot() != null) {
						observability.recordImageCapture(captureContext, captureResult.screenshot());
					}
					else if (throwable != null) {
						observability.recordFailure(
							captureContext,
							LlmFailureType.PROVIDER_ERROR.name(),
							"Vision capture failed",
							Throwable.class.isAssignableFrom(throwable.getClass()) ? throwable : new RuntimeException(throwable)
						);
					}
				}
				finally {
					observability.endSpan(captureContext);
				}
			});
		}
		catch (RuntimeException exception) {
			observability.recordFailure(Context.current(), LlmFailureType.PROVIDER_ERROR.name(), "Vision capture failed", exception);
			return CompletableFuture.failedFuture(exception);
		}
	}

	private List<String> prepareCaptureTarget(MinecraftClient client, ViewCaptureRequest request) {
		if (request == null || request.isCurrent()) {
			return List.of();
		}
		if (client.world == null || client.player == null) {
			throw new BridgeUnavailableException("world_not_loaded", "No world is currently loaded");
		}

		return switch (request.targetType()) {
			case CURRENT -> List.of();
			case DIRECTION -> {
				faceDirection(client.player, request.direction());
				yield List.of("lookTarget=direction direction=" + request.direction());
			}
			case BLOCK -> {
				BlockPos targetPos = new BlockPos(request.x(), request.y(), request.z());
				Vec3d targetCenter = Vec3d.ofCenter(targetPos);
				lookController.lookAt(client, targetCenter, 360.0F, 180.0F);
				List<String> metadata = new ArrayList<>();
				metadata.add("lookTarget=block x=" + targetPos.getX() + " y=" + targetPos.getY() + " z=" + targetPos.getZ());
				blockLineOfSightWarning(client, client.player, targetPos, targetCenter).ifPresent(metadata::add);
				yield metadata;
			}
			case PLAYER -> {
				AbstractClientPlayerEntity target = findPlayer(client, request.targetPlayer());
				lookController.lookAt(client, target.getBoundingBox().getCenter(), 360.0F, 180.0F);
				yield List.of("lookTarget=player targetPlayer=" + target.getName().getString());
			}
		};
	}

	private static void faceDirection(ClientPlayerEntity player, String direction) {
		float yaw = switch (direction == null ? "" : direction) {
			case "north" -> 180.0F;
			case "northeast" -> -135.0F;
			case "east" -> -90.0F;
			case "southeast" -> -45.0F;
			case "south" -> 0.0F;
			case "southwest" -> 45.0F;
			case "west" -> 90.0F;
			case "northwest" -> 135.0F;
			default -> throw new BridgeUnavailableException("invalid_request", "Unsupported direction: " + direction);
		};
		applyRotation(player, yaw, 0.0F);
	}

	private static java.util.Optional<String> blockLineOfSightWarning(
		MinecraftClient client,
		ClientPlayerEntity player,
		BlockPos targetPos,
		Vec3d targetCenter
	) {
		if (!client.world.isChunkLoaded(targetPos)) {
			return java.util.Optional.of("LOOK_WARNING: target_block_los_unknown reason=target_chunk_not_loaded");
		}
		Vec3d start = player.getEyePos();
		BlockHitResult hitResult = client.world.raycast(new RaycastContext(
			start,
			targetCenter,
			RaycastContext.ShapeType.VISUAL,
			RaycastContext.FluidHandling.NONE,
			player
		));
		if (hitResult.getType() == HitResult.Type.BLOCK && !hitResult.getBlockPos().equals(targetPos)) {
			BlockPos blockerPos = hitResult.getBlockPos();
			if (!client.world.isChunkLoaded(blockerPos)) {
				return java.util.Optional.of("LOOK_WARNING: target_block_los_unknown reason=blocking_chunk_not_loaded");
			}
			BlockState blockerState = client.world.getBlockState(blockerPos);
			if (!blockerState.isAir() && !blockerState.isTransparent()) {
				String blockId = Registries.BLOCK.getId(blockerState.getBlock()).toString();
				return java.util.Optional.of(
					"LOOK_WARNING: target_block_los_blocked blockingBlockId=" + blockId
						+ " blockingPos=" + blockerPos.getX() + "," + blockerPos.getY() + "," + blockerPos.getZ()
				);
			}
		}
		return java.util.Optional.empty();
	}

	private static AbstractClientPlayerEntity findPlayer(MinecraftClient client, String targetPlayer) {
		if (targetPlayer == null || targetPlayer.isBlank()) {
			throw new BridgeUnavailableException("invalid_request", "targetPlayer must be non-empty");
		}
		for (AbstractClientPlayerEntity player : client.world.getPlayers()) {
			if (player == client.player) {
				continue;
			}
			if (player.getName().getString().equals(targetPlayer)) {
				return player;
			}
		}
		throw new BridgeUnavailableException("player_not_found", "No loaded player named " + targetPlayer);
	}

	private static void applyRotation(ClientPlayerEntity player, float yaw, float pitch) {
		pitch = MathHelper.clamp(pitch, -90.0F, 90.0F);
		player.setAngles(yaw, pitch);
		player.setYaw(yaw);
		player.setPitch(pitch);
		player.setHeadYaw(yaw);
		player.setBodyYaw(yaw);
		player.lastYaw = yaw;
		player.lastPitch = pitch;
		player.renderYaw = yaw;
		player.lastRenderYaw = yaw;
		player.renderPitch = pitch;
		player.lastRenderPitch = pitch;
	}

	@Override
	public CompletableFuture<VisionDescription> requestDescription(FirstPersonScreenshotService.CapturedScreenshot screenshot, String prompt) {
		if (!isConfigured()) {
			return CompletableFuture.failedFuture(
				new LlmBackendException(LlmFailureType.PROVIDER_UNAVAILABLE, "Vision provider is not configured")
			);
		}

		try {
			Context parentContext = Context.current();
			return CompletableFuture.supplyAsync(() -> {
				Context describeContext = observability.startChildSpan(
					AgentObservability.VISION_DESCRIBE_SPAN_NAME,
					parentContext
				);
				try (Scope scope = describeContext.makeCurrent()) {
					return describeWithinCurrentSpan(screenshot, prompt);
				}
				catch (LlmBackendException exception) {
					throw new CompletionException(exception);
				}
				finally {
					observability.endSpan(describeContext);
				}
			}, executorService);
		}
		catch (RuntimeException exception) {
			return CompletableFuture.failedFuture(exception);
		}
	}

	@Override
	public CompletableFuture<VisionDescription> requestDescription(String prompt) {
		return CurrentViewVisionTool.super.requestDescription(prompt);
	}

	public VisionDescription describe(FirstPersonScreenshotService.CapturedScreenshot screenshot, String prompt) throws LlmBackendException {
		Context describeContext = observability.startChildSpan(
			AgentObservability.VISION_DESCRIBE_SPAN_NAME,
			Context.current()
		);
		try (Scope scope = describeContext.makeCurrent()) {
			return describeWithinCurrentSpan(screenshot, prompt);
		}
		finally {
			observability.endSpan(describeContext);
		}
	}

	private VisionDescription describeWithinCurrentSpan(FirstPersonScreenshotService.CapturedScreenshot screenshot, String prompt) throws LlmBackendException {
		Objects.requireNonNull(screenshot, "screenshot");
		return visionBackend.describe(new VisionRequest(
			normalizePrompt(prompt),
			"image/png",
			screenshot.imageBytes(),
			screenshot.capturedAtMs()
		));
	}

	public void shutdown() {
		executorService.shutdownNow();
	}

	private static String normalizePrompt(String prompt) {
		if (prompt == null || prompt.isBlank()) {
			return DEFAULT_DESCRIBE_PROMPT;
		}
		return prompt;
	}
}
