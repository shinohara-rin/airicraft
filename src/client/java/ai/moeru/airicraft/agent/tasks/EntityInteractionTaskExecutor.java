package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class EntityInteractionTaskExecutor implements WorldTaskExecutor {
	private static final int TARGET_OUT_OF_RANGE_GRACE_TICKS = 20;
	private static final float ATTACK_READY_THRESHOLD = 0.92F;

	private final Supplier<MinecraftClient> clientSupplier;

	private WorldTaskRequest appliedTask;
	private boolean terminalEventEmitted;
	private int outOfRangeTicks;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	public EntityInteractionTaskExecutor() {
		this(MinecraftClient::getInstance);
	}

	EntityInteractionTaskExecutor(Supplier<MinecraftClient> clientSupplier) {
		this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
		if (activeTask.isEmpty() || !isEntityInteractionTask(activeTask.get().type())) {
			reset();
			return Optional.empty();
		}

		WorldTaskRequest request = activeTask.get();
		if (!sameTask(request, appliedTask)) {
			reset();
			appliedTask = request;
		}

		if (!entityInteractionActuationAllowed(sessionSnapshot)) {
			snapshot = snapshot(TaskExecutionState.PAUSED_BY_SESSION_GATE, request, "session_gate");
			return Optional.empty();
		}

		MinecraftClient client = clientSupplier.get();
		ClientPlayerEntity player = client == null ? null : client.player;
		if (client == null || client.interactionManager == null || client.world == null || player == null) {
			return fail(request, "world_unavailable");
		}
		if (dismissCurrentScreenIfSafe(client, player)) {
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "screen_dismissed");
			return Optional.empty();
		}
		if (player.currentScreenHandler != player.playerScreenHandler || !player.currentScreenHandler.getCursorStack().isEmpty()) {
			return fail(request, "interaction_busy");
		}

		Selection selection = resolveSelection(client, player, request.entityInteraction().selector());
		if (selection.failureReason() != null) {
			return fail(request, selection.failureReason());
		}
		Entity target = selection.entity();
		if (target == null) {
			return fail(request, "target_not_found");
		}
		if (!target.isAlive()) {
			return fail(request, "target_not_alive");
		}
		if (!EntitySelectorResolver.isWithinInteractionRange(
			player.getX(),
			player.getY(),
			player.getZ(),
			target.getX(),
			target.getY(),
			target.getZ()
		)) {
			outOfRangeTicks++;
			if (outOfRangeTicks <= TARGET_OUT_OF_RANGE_GRACE_TICKS) {
				snapshot = snapshot(TaskExecutionState.RUNNING, request, "target_out_of_range");
				return Optional.empty();
			}
			return fail(request, "target_out_of_range");
		}
		outOfRangeTicks = 0;

		return switch (request.type()) {
			case ATTACK_ENTITY -> attackEntity(client, player, request, target);
			case USE_ENTITY -> useEntity(client, player, request, target);
			default -> Optional.empty();
		};
	}

	static boolean entityInteractionActuationAllowed(SessionSnapshot sessionSnapshot) {
		return sessionSnapshot != null && sessionSnapshot.companionActuationAllowed();
	}

	private Optional<TaskTerminalEvent> attackEntity(
		MinecraftClient client,
		ClientPlayerEntity player,
		WorldTaskRequest request,
		Entity target
	) {
		if (player.getAttackCooldownProgress(0.0F) < ATTACK_READY_THRESHOLD) {
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "attack_cooldown");
			return Optional.empty();
		}

		client.interactionManager.attackEntity(player, target);
		player.swingHand(Hand.MAIN_HAND);
		if (!target.isAlive()) {
			return complete(request, "target_died");
		}
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "attack_landed");
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> useEntity(
		MinecraftClient client,
		ClientPlayerEntity player,
		WorldTaskRequest request,
		Entity target
	) {
		Hand hand = resolveInteractionHand(client, player, request.entityInteraction().itemId());
		if (hand == null) {
			return fail(request, "required_item_missing");
		}
		ActionResult result = client.interactionManager.interactEntity(player, target, hand);
		if (!result.isAccepted()) {
			return fail(request, "interaction_failed");
		}
		player.swingHand(hand);
		return complete(request, "interaction_succeeded");
	}

	private static Hand resolveInteractionHand(MinecraftClient client, ClientPlayerEntity player, String itemId) {
		if (itemId == null || itemId.isBlank()) {
			return Hand.MAIN_HAND;
		}
		String offHandItemId = Registries.ITEM.getId(player.getOffHandStack().getItem()).toString();
		if (itemId.equals(offHandItemId) && !player.getOffHandStack().isEmpty()) {
			return Hand.OFF_HAND;
		}

		ScreenHandler handler = player.currentScreenHandler;
		int sourceSlot = findInventorySlot(handler, itemId);
		if (sourceSlot < 0) {
			return null;
		}
		int hotbarIndex = player.getInventory().getSelectedSlot();
		if (sourceSlot >= PlayerScreenHandler.HOTBAR_START && sourceSlot < PlayerScreenHandler.HOTBAR_END) {
			player.getInventory().setSelectedSlot(sourceSlot - PlayerScreenHandler.HOTBAR_START);
			return Hand.MAIN_HAND;
		}
		client.interactionManager.clickSlot(handler.syncId, sourceSlot, hotbarIndex, SlotActionType.SWAP, player);
		player.getInventory().setSelectedSlot(hotbarIndex);
		ItemStack selected = player.getInventory().getSelectedStack();
		if (selected.isEmpty()) {
			return null;
		}
		String selectedItemId = Registries.ITEM.getId(selected.getItem()).toString();
		return itemId.equals(selectedItemId) ? Hand.MAIN_HAND : null;
	}

	private static int findInventorySlot(ScreenHandler handler, String itemId) {
		for (int slot = PlayerScreenHandler.INVENTORY_START; slot < PlayerScreenHandler.HOTBAR_END; slot++) {
			ItemStack stack = handler.getSlot(slot).getStack();
			if (stack.isEmpty()) {
				continue;
			}
			if (itemId.equals(Registries.ITEM.getId(stack.getItem()).toString())) {
				return slot;
			}
		}
		return -1;
	}

	private static Selection resolveSelection(MinecraftClient client, ClientPlayerEntity player, EntitySelector selector) {
		Map<Integer, Entity> entitiesById = new LinkedHashMap<>();
		java.util.ArrayList<EntitySelectorResolver.EntityCandidate> candidates = new java.util.ArrayList<>();
		for (Entity entity : client.world.getEntities()) {
			if (entity == player) {
				continue;
			}
			entitiesById.put(entity.getId(), entity);
			candidates.add(NearbyEntityService.toCandidate(entity));
		}
		EntitySelectorResolver.SelectionResult result = EntitySelectorResolver.select(
			selector,
			candidates,
			player.getX(),
			player.getY(),
			player.getZ()
		);
		if (result.status() != EntitySelectorResolver.SelectionStatus.SELECTED || result.selected() == null) {
			return new Selection(null, selectionFailureReason(result.status()));
		}
		return new Selection(entitiesById.get(result.selected().entityId()), null);
	}

	private static String selectionFailureReason(EntitySelectorResolver.SelectionStatus status) {
		return switch (status) {
			case TARGET_NOT_FOUND -> "target_not_found";
			case TARGET_NOT_NEARBY -> "target_not_nearby";
			case TARGET_NOT_ALIVE -> "target_not_alive";
			case TARGET_AMBIGUOUS -> "target_ambiguous";
			case SELECTED -> null;
		};
	}

	private static boolean dismissCurrentScreenIfSafe(MinecraftClient client, ClientPlayerEntity player) {
		if (!DropItemsTaskExecutor.shouldDismissBusyScreen(currentScreenName(client))) {
			return false;
		}
		if (player.currentScreenHandler != player.playerScreenHandler) {
			return false;
		}
		if (!player.currentScreenHandler.getCursorStack().isEmpty()) {
			return false;
		}
		client.setScreen(null);
		return true;
	}

	private static String currentScreenName(MinecraftClient client) {
		return client.currentScreen == null ? null : client.currentScreen.getClass().getSimpleName();
	}

	private Optional<TaskTerminalEvent> complete(WorldTaskRequest request, String message) {
		snapshot = snapshot(TaskExecutionState.COMPLETED, request, message);
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.COMPLETED, message, null));
	}

	private Optional<TaskTerminalEvent> fail(WorldTaskRequest request, String reason) {
		snapshot = snapshot(TaskExecutionState.FAILED, request, reason);
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.FAILED, reason, null));
	}

	private static TaskExecutionSnapshot snapshot(TaskExecutionState state, WorldTaskRequest request, String event) {
		return new TaskExecutionSnapshot(state, request.taskId(), null, "EntityInteraction", event, null, null);
	}

	private static boolean sameTask(WorldTaskRequest left, WorldTaskRequest right) {
		if (left == right) {
			return true;
		}
		if (left == null || right == null) {
			return false;
		}
		return Objects.equals(left.taskId(), right.taskId())
			&& Objects.equals(left.type(), right.type())
			&& Objects.equals(left.entityInteraction(), right.entityInteraction());
	}

	private static boolean isEntityInteractionTask(WorldTaskType type) {
		return type == WorldTaskType.ATTACK_ENTITY || type == WorldTaskType.USE_ENTITY;
	}

	@Override
	public TaskExecutionSnapshot snapshot() {
		return snapshot;
	}

	@Override
	public void onWorldLeave() {
		reset();
	}

	@Override
	public void shutdown() {
		reset();
	}

	private void reset() {
		appliedTask = null;
		terminalEventEmitted = false;
		outOfRangeTicks = 0;
		snapshot = TaskExecutionSnapshot.idle();
	}

	private record Selection(Entity entity, String failureReason) {
	}
}
