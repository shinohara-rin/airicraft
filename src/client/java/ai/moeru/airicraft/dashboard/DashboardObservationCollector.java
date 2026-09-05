package ai.moeru.airicraft.dashboard;

import ai.moeru.airicraft.BridgeUnavailableException;
import ai.moeru.airicraft.FirstPersonScreenshotService;
import ai.moeru.airicraft.agent.EmbodiedAgentRuntime;
import ai.moeru.airicraft.agent.debug.LlmFlightRecord;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Base64;
import java.util.function.Supplier;

public final class DashboardObservationCollector {
	private static final int SNAPSHOT_INTERVAL_TICKS = 5;
	private final DashboardObservationStore store;
	private final FirstPersonScreenshotService screenshotService;
	private final Supplier<DebugDashboardConfig> configSupplier;
	private final Map<Long, String> llmVersions = new HashMap<>();
	private Long eventCursor;
	private Long timelineCursor;
	private long lastSnapshotTick = Long.MIN_VALUE;
	private long lastLlmPollTick = Long.MIN_VALUE;
	private long highestLlmSequence;
	private long lastVisualCaptureTick = Long.MIN_VALUE;
	private volatile boolean visualCapturePending;

	public DashboardObservationCollector(DashboardObservationStore store) {
		this(store, null, DebugDashboardConfig::defaults);
	}

	public DashboardObservationCollector(
		DashboardObservationStore store,
		FirstPersonScreenshotService screenshotService,
		Supplier<DebugDashboardConfig> configSupplier
	) {
		this.store = store;
		this.screenshotService = screenshotService;
		this.configSupplier = configSupplier;
	}

	public void startSession(String reason, EmbodiedAgentRuntime runtime) {
		long tick = runtime == null ? 0L : runtime.tickCount();
		store.startSession(reason, tick, System.currentTimeMillis());
		eventCursor = null;
		timelineCursor = null;
		llmVersions.clear();
		lastSnapshotTick = Long.MIN_VALUE;
		lastLlmPollTick = Long.MIN_VALUE;
		highestLlmSequence = 0L;
		lastVisualCaptureTick = Long.MIN_VALUE;
		visualCapturePending = false;
	}

	public void capture(MinecraftClient client, EmbodiedAgentRuntime runtime) {
		long tick = runtime.tickCount();
		captureEventHistory(runtime);
		captureDebugTimeline(runtime);
		if (lastLlmPollTick == Long.MIN_VALUE || tick - lastLlmPollTick >= SNAPSHOT_INTERVAL_TICKS) {
			captureLlmHistory(runtime, tick);
			lastLlmPollTick = tick;
		}
		if (lastSnapshotTick == Long.MIN_VALUE || tick - lastSnapshotTick >= SNAPSHOT_INTERVAL_TICKS) {
			store.append("runtime_snapshot", tick, System.currentTimeMillis(), runtimeSnapshot(client, runtime));
			lastSnapshotTick = tick;
		}
		captureVisualContext(client, tick);
	}

	private void captureVisualContext(MinecraftClient client, long tick) {
		DebugDashboardConfig config = configSupplier.get();
		if (screenshotService == null
			|| config == null
			|| !config.visualCaptureEnabled()
			|| visualCapturePending
			|| client == null
			|| client.world == null
			|| client.player == null
			|| (lastVisualCaptureTick != Long.MIN_VALUE && tick - lastVisualCaptureTick < config.visualCaptureIntervalTicks())) {
			return;
		}
		try {
			visualCapturePending = true;
			lastVisualCaptureTick = tick;
			screenshotService.requestCapture(client).whenComplete((capture, throwable) -> {
				visualCapturePending = false;
				if (capture == null || throwable != null) {
					return;
				}
				store.append("visual_frame", tick, capture.capturedAtMs(), Map.of(
					"format", capture.format(),
					"width", capture.width(),
					"height", capture.height(),
					"sourceWidth", capture.sourceWidth(),
					"sourceHeight", capture.sourceHeight(),
					"imageBase64", Base64.getEncoder().encodeToString(capture.imageBytes())
				));
			});
		}
		catch (BridgeUnavailableException exception) {
			visualCapturePending = false;
		}
	}

	private void captureEventHistory(EmbodiedAgentRuntime runtime) {
		var result = runtime.recentEvents(eventCursor);
		if (result.truncated()) {
			appendGap(runtime.tickCount(), "semantic_event", eventCursor, result.oldestSeqNo());
		}
		for (var event : result.events()) {
			store.append("semantic_event", event.tick(), event.timestampMs(), event);
		}
		if (result.latestSeqNo() > 0L) {
			eventCursor = result.latestSeqNo();
		}
	}

	private void captureDebugTimeline(EmbodiedAgentRuntime runtime) {
		var result = runtime.debugTimeline(timelineCursor);
		if (result.truncated()) {
			appendGap(runtime.tickCount(), "debug_timeline", timelineCursor, result.oldestEntryId());
		}
		for (var entry : result.entries()) {
			store.append("debug_timeline", entry.tick(), entry.timestampMs(), entry);
		}
		if (result.latestEntryId() > 0L) {
			timelineCursor = result.latestEntryId();
		}
	}

	private void captureLlmHistory(EmbodiedAgentRuntime runtime, long tick) {
		long revisitAfter = Math.max(0L, highestLlmSequence - 64L);
		var result = runtime.llmFlightRecords(revisitAfter);
		if (result.truncated()) {
			appendGap(tick, "llm_call", revisitAfter, result.oldestSequenceId());
		}
		for (LlmFlightRecord record : result.records()) {
			highestLlmSequence = Math.max(highestLlmSequence, record.sequenceId());
			String version = record.status() + ':' + record.completedAtMs() + ':' + record.rawResponseBody().length()
				+ ':' + record.failureMessage().length();
			if (!version.equals(llmVersions.put(record.sequenceId(), version))) {
				long capturedAt = record.completedAtMs() > 0L ? record.completedAtMs() : record.requestedAtMs();
				store.append("llm_call", tick, capturedAt, record);
			}
		}
		if (llmVersions.size() > 4096) {
			long keepFrom = Math.max(result.oldestSequenceId(), highestLlmSequence - 512L);
			llmVersions.keySet().removeIf(sequence -> sequence < keepFrom);
		}
	}

	private void appendGap(long tick, String stream, Long requestedAfter, long oldestAvailable) {
		store.append("observation_gap", tick, System.currentTimeMillis(), Map.of(
			"stream", stream,
			"requestedAfter", requestedAfter == null ? 0L : requestedAfter,
			"oldestAvailable", oldestAvailable
		));
	}

	private static Map<String, Object> runtimeSnapshot(MinecraftClient client, EmbodiedAgentRuntime runtime) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("schemaVersion", 1);
		payload.put("agent", runtime.snapshot());
		payload.put("planner", runtime.plannerDebugSnapshot());
		payload.put("dialogue", runtime.dialogueSnapshot());
		payload.put("dialogueState", runtime.debugDialogueState());
		payload.put("conversationSources", runtime.debugConversationSources());
		payload.put("activeGoal", runtime.activeGoal().orElse(null));
		payload.put("activeJob", runtime.activeJob());
		payload.put("task", runtime.taskSnapshot());
		payload.put("taskExecution", runtime.taskExecutionSnapshot());
		payload.put("missionExecution", runtime.missionExecutionSnapshot());
		payload.put("reflex", runtime.survivalReflexSnapshot());
		payload.put("actionGraph", runtime.actionGraphGoalsPayload(true));
		payload.put("behaviorTree", runtime.behaviorTreeSnapshot());
		payload.put("eventPipeline", runtime.debugEventPipelineState());
		payload.put("taskProgressProbe", runtime.debugCollectResourceState());
		payload.put("chatProbe", runtime.debugChatState());
		payload.put("observability", runtime.observabilityDebugSnapshot());
		payload.put("plannerEnabled", runtime.plannerEnabled());
		payload.put("degraded", runtime.isDegraded());
		payload.put("llmAvailable", runtime.llmAvailable());
		payload.put("noLlmActive", runtime.noLlmActive());
		payload.put("visionAvailable", runtime.visionAvailable());
		payload.put("world", worldSnapshot(client));
		return payload;
	}

	private static Map<String, Object> worldSnapshot(MinecraftClient client) {
		Map<String, Object> world = new LinkedHashMap<>();
		world.put("loaded", client != null && client.world != null && client.player != null);
		world.put("screen", client == null || client.currentScreen == null
			? "none"
			: client.currentScreen.getClass().getSimpleName());
		if (client == null || client.world == null || client.player == null) {
			return world;
		}
		var player = client.player;
		world.put("dimension", client.world.getRegistryKey().getValue().toString());
		world.put("time", client.world.getTime());
		world.put("timeOfDay", client.world.getTimeOfDay());
		world.put("raining", client.world.isRaining());
		world.put("thundering", client.world.isThundering());
		Map<String, Object> playerSnapshot = new LinkedHashMap<>();
		playerSnapshot.put("name", player.getName().getString());
		playerSnapshot.put("x", player.getX());
		playerSnapshot.put("y", player.getY());
		playerSnapshot.put("z", player.getZ());
		playerSnapshot.put("yaw", player.getYaw());
		playerSnapshot.put("pitch", player.getPitch());
		playerSnapshot.put("health", player.getHealth());
		playerSnapshot.put("maxHealth", player.getMaxHealth());
		playerSnapshot.put("food", player.getHungerManager().getFoodLevel());
		playerSnapshot.put("air", player.getAir());
		playerSnapshot.put("onGround", player.isOnGround());
		playerSnapshot.put("submerged", player.isSubmergedInWater());
		world.put("player", playerSnapshot);
		Map<String, Integer> inventory = new LinkedHashMap<>();
		for (int slot = 0; slot < player.getInventory().size(); slot++) {
			var stack = player.getInventory().getStack(slot);
			if (!stack.isEmpty()) {
				inventory.merge(Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount(), Integer::sum);
			}
		}
		world.put("inventory", inventory);
		world.put("selectedHotbarSlot", player.getInventory().getSelectedSlot());
		world.put("equippedItem", Registries.ITEM.getId(player.getMainHandStack().getItem()).toString());
		return world;
	}
}
