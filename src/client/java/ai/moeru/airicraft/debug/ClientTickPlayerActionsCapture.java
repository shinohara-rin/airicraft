package ai.moeru.airicraft.debug;

import ai.moeru.airicraft.mixin.client.ClientPlayerInteractionManagerAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class ClientTickPlayerActionsCapture {
	private String captureId;
	private boolean initialized;
	private Map<String, Boolean> previousActions = Map.of();
	private ClientTickPlayerActionsSnapshot.BreakProgress previousBreakProgress;

	ClientTickPlayerActionsSnapshot capture(MinecraftClient client, String nextCaptureId) {
		if (!Objects.equals(captureId, nextCaptureId)) {
			captureId = nextCaptureId;
			initialized = false;
			previousActions = Map.of();
			previousBreakProgress = null;
		}

		Map<String, Boolean> currentActions = actions(client);
		ClientTickPlayerActionEvents.EventBatch events = ClientTickPlayerActionEvents.take();
		Set<String> startedInteractions = events.startedActions();
		for (String action : startedInteractions) {
			currentActions.putIfAbsent(action, false);
		}
		ClientTickPlayerActionsSnapshot.BreakProgress breakProgress = events.breakProgress();
		if (breakProgress == null) {
			breakProgress = breakProgress(client);
		}
		boolean breakStarted = initialized
			&& breakProgress != null
			&& (previousBreakProgress == null || !breakProgress.position().equals(previousBreakProgress.position()));
		if (breakProgress != null) {
			breakProgress = new ClientTickPlayerActionsSnapshot.BreakProgress(
				breakProgress.position(),
				breakProgress.progress(),
				breakProgress.stage(),
				breakStarted
			);
		}

		List<ClientTickPlayerActionsSnapshot.ActionState> actions = new ArrayList<>();
		for (Map.Entry<String, Boolean> entry : currentActions.entrySet()) {
			actions.add(new ClientTickPlayerActionsSnapshot.ActionState(
				entry.getKey(),
				entry.getValue(),
				startedInteractions.contains(entry.getKey())
					|| (entry.getKey().equals("attack") && breakStarted)
					|| (initialized && entry.getValue() && !previousActions.getOrDefault(entry.getKey(), false))
			));
		}

		initialized = true;
		previousActions = Map.copyOf(currentActions);
		previousBreakProgress = breakProgress;
		return new ClientTickPlayerActionsSnapshot(actions, breakProgress);
	}

	private static Map<String, Boolean> actions(MinecraftClient client) {
		Map<String, Boolean> actions = new LinkedHashMap<>();
		if (client == null || client.options == null) {
			return actions;
		}
		add(actions, "attack", client.options.attackKey);
		add(actions, "use", client.options.useKey);
		add(actions, "pick_item", client.options.pickItemKey);
		add(actions, "drop_item", client.options.dropKey);
		add(actions, "swap_hands", client.options.swapHandsKey);
		for (int index = 0; index < client.options.hotbarKeys.length; index++) {
			add(actions, "hotbar_" + (index + 1), client.options.hotbarKeys[index]);
		}
		return actions;
	}

	private static void add(Map<String, Boolean> actions, String action, KeyBinding keyBinding) {
		actions.put(action, keyBinding != null && keyBinding.isPressed());
	}

	private static ClientTickPlayerActionsSnapshot.BreakProgress breakProgress(MinecraftClient client) {
		if (client == null || !(client.interactionManager instanceof ClientPlayerInteractionManagerAccessor accessor)) {
			return null;
		}
		BlockPos position = accessor.airicraft$currentBreakingPos();
		if (position == null) {
			return null;
		}
		float progress = accessor.airicraft$currentBreakingProgress();
		if (!accessor.airicraft$breakingBlock() && progress <= 0.0F) {
			return null;
		}
		int stage = Math.clamp((int) Math.floor(progress * 10.0F), 0, 9);
		return new ClientTickPlayerActionsSnapshot.BreakProgress(
			new ClientTickPlayerActionsSnapshot.Position(position.getX(), position.getY(), position.getZ()),
			progress,
			stage,
			false
		);
	}
}
