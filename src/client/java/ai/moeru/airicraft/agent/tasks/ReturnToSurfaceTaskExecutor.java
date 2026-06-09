package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class ReturnToSurfaceTaskExecutor implements WorldTaskExecutor {
	private static final int NAVIGATION_RADIUS_BLOCKS = 3;
	private static final int MAX_TOWER_BLOCKS = 96;

	private final Supplier<MinecraftClient> clientSupplier;
	private final BaritoneFacade baritoneFacade;

	private WorldTaskRequest appliedTask;
	private boolean terminalEventEmitted;
	private boolean navigationStarted;
	private boolean toweringStarted;
	private int towerStartY;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	public ReturnToSurfaceTaskExecutor(BaritoneFacade baritoneFacade) {
		this(MinecraftClient::getInstance, baritoneFacade);
	}

	ReturnToSurfaceTaskExecutor(Supplier<MinecraftClient> clientSupplier, BaritoneFacade baritoneFacade) {
		this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
		this.baritoneFacade = baritoneFacade;
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
		if (activeTask.isEmpty() || activeTask.get().type() != WorldTaskType.RETURN_TO_SURFACE) {
			reset();
			return Optional.empty();
		}
		WorldTaskRequest request = activeTask.get();
		if (!sameTask(request, appliedTask)) {
			reset();
			appliedTask = request;
		}
		if (sessionSnapshot == null || !sessionSnapshot.companionActuationAllowed()) {
			snapshot = snapshot(TaskExecutionState.PAUSED_BY_SESSION_GATE, request, "session_gate");
			return Optional.empty();
		}

		MinecraftClient client = clientSupplier.get();
		ClientPlayerEntity player = client == null ? null : client.player;
		if (client == null || client.world == null || client.interactionManager == null || player == null) {
			return fail(request, "world_unavailable");
		}
		ReturnToSurfaceStepArgs args = request.returnToSurface();
		if (isSurfaceReached(client, player)) {
			return complete(request, "surface_reached");
		}
		if (args.targetPosition() != null && reachedTarget(player, args.targetPosition())) {
			return handleSurfaceTargetReached(request, client, player, args);
		}
		if (toweringStarted || shouldTowerFirst(args)) {
			return tickTowering(request, client, player, args);
		}
		if (args.targetPosition() == null) {
			return args.useTowering()
				? tickTowering(request, client, player, args)
				: fail(request, "surface_target_unavailable");
		}
		if (baritoneFacade == null || !baritoneFacade.isLoaded()) {
			return args.useTowering()
				? tickTowering(request, client, player, args)
				: fail(request, "baritone_unavailable");
		}
		if (!navigationStarted) {
			baritoneFacade.startNavigateNear(args.targetPosition(), NAVIGATION_RADIUS_BLOCKS);
			navigationStarted = true;
		}
		Optional<String> pathEvent = baritoneFacade.pollPathEvent();
		if (pathEvent.isPresent()) {
			String event = pathEvent.get();
			if ("AT_GOAL".equalsIgnoreCase(event)) {
				return handleSurfaceTargetReached(request, client, player, args);
			}
			if ("CALC_FAILED".equalsIgnoreCase(event) || "CANCELLED".equalsIgnoreCase(event) || "CANCELED".equalsIgnoreCase(event)) {
				return args.useTowering()
					? tickTowering(request, client, player, args)
					: fail(request, "surface_path_" + event.toLowerCase(java.util.Locale.ROOT));
			}
		}
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "navigating_to_surface");
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> handleSurfaceTargetReached(
		WorldTaskRequest request,
		MinecraftClient client,
		ClientPlayerEntity player,
		ReturnToSurfaceStepArgs args
	) {
		return switch (surfaceTargetOutcome(isSurfaceReached(client, player), args.useTowering())) {
			case COMPLETE -> complete(request, "surface_target_reached");
			case TOWER -> tickTowering(request, client, player, args);
			case FAIL -> fail(request, "surface_target_not_surface");
		};
	}

	private Optional<TaskTerminalEvent> tickTowering(
		WorldTaskRequest request,
		MinecraftClient client,
		ClientPlayerEntity player,
		ReturnToSurfaceStepArgs args
	) {
		if (!toweringStarted) {
			cancelNavigationIfStarted();
			toweringStarted = true;
			towerStartY = player.getBlockY();
		}
		if (isSurfaceReached(client, player)) {
			return complete(request, "surface_reached_by_towering");
		}
		if (player.getBlockY() - towerStartY > MAX_TOWER_BLOCKS) {
			return fail(request, "tower_limit_reached");
		}
		Hand hand = selectFillerHand(client, player, args.fillerBlockIds());
		if (hand == null) {
			return fail(request, "missing_filler_block fillerBlockIds=" + args.fillerBlockIds());
		}
		client.options.jumpKey.setPressed(true);
		PlacementAttempt placement = placeUnderFoot(client, player, hand);
		if (placement.accepted()) {
			player.swingHand(hand);
		}
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "towering:" + placement.reason());
		return Optional.empty();
	}

	private boolean shouldTowerFirst(ReturnToSurfaceStepArgs args) {
		return args != null && args.useTowering() && args.targetPosition() == null;
	}

	private static PlacementAttempt placeUnderFoot(MinecraftClient client, ClientPlayerEntity player, Hand hand) {
		BlockPos support = player.getBlockPos().down();
		if (!canPlaceAgainst(client, player, hand, support)) {
			return new PlacementAttempt(false, "support_unavailable");
		}
		BlockHitResult hitResult = new BlockHitResult(
			new Vec3d(support.getX() + 0.5D, support.getY() + 1.0D, support.getZ() + 0.5D),
			Direction.UP,
			support,
			false
		);
		ActionResult result = client.interactionManager.interactBlock(player, hand, hitResult);
		return new PlacementAttempt(result.isAccepted(), result.isAccepted() ? "placed" : "interact_" + result);
	}

	private static boolean canPlaceAgainst(MinecraftClient client, ClientPlayerEntity player, Hand hand, BlockPos support) {
		if (client == null || client.world == null || player == null || support == null) {
			return false;
		}
		BlockPos target = support.up();
		if (!client.world.isChunkLoaded(support) || !client.world.isChunkLoaded(target)) {
			return false;
		}
		BlockState supportState = client.world.getBlockState(support);
		BlockState targetState = client.world.getBlockState(target);
		ItemStack selected = hand == Hand.OFF_HAND ? player.getOffHandStack() : player.getMainHandStack();
		if (!(selected.getItem() instanceof BlockItem blockItem)) {
			return false;
		}
		return supportState.isSideSolidFullSquare(client.world, support, Direction.UP)
			&& (targetState.isAir() || targetState.isReplaceable())
			&& client.world.canPlace(blockItem.getBlock().getDefaultState(), target, ShapeContext.of(player));
	}

	private static Hand selectFillerHand(MinecraftClient client, ClientPlayerEntity player, List<String> fillerBlockIds) {
		if (matchesFiller(player.getOffHandStack(), fillerBlockIds)) {
			return Hand.OFF_HAND;
		}
		return selectHotbarFiller(client, player, fillerBlockIds) ? Hand.MAIN_HAND : null;
	}

	private static boolean selectHotbarFiller(MinecraftClient client, ClientPlayerEntity player, List<String> fillerBlockIds) {
		if (player.currentScreenHandler != player.playerScreenHandler || !player.currentScreenHandler.getCursorStack().isEmpty()) {
			return false;
		}
		ScreenHandler handler = player.currentScreenHandler;
		int sourceSlot = findFillerSlot(handler, fillerBlockIds);
		if (sourceSlot < 0) {
			return false;
		}
		int selectedHotbarSlot = player.getInventory().getSelectedSlot();
		if (sourceSlot >= PlayerScreenHandler.HOTBAR_START && sourceSlot < PlayerScreenHandler.HOTBAR_END) {
			selectAndSyncHotbarSlot(client, player, sourceSlot - PlayerScreenHandler.HOTBAR_START);
			return matchesFiller(player.getInventory().getSelectedStack(), fillerBlockIds);
		}
		client.interactionManager.clickSlot(handler.syncId, sourceSlot, selectedHotbarSlot, SlotActionType.SWAP, player);
		selectAndSyncHotbarSlot(client, player, selectedHotbarSlot);
		return matchesFiller(player.getInventory().getSelectedStack(), fillerBlockIds);
	}

	private static int findFillerSlot(ScreenHandler handler, List<String> fillerBlockIds) {
		if (!(handler instanceof PlayerScreenHandler)) {
			return -1;
		}
		for (int slot = PlayerScreenHandler.HOTBAR_START; slot < PlayerScreenHandler.HOTBAR_END; slot++) {
			if (matchesFiller(handler.getSlot(slot).getStack(), fillerBlockIds)) {
				return slot;
			}
		}
		for (int slot = PlayerScreenHandler.INVENTORY_START; slot < PlayerScreenHandler.HOTBAR_START; slot++) {
			if (matchesFiller(handler.getSlot(slot).getStack(), fillerBlockIds)) {
				return slot;
			}
		}
		return -1;
	}

	private static boolean matchesFiller(ItemStack stack, List<String> fillerBlockIds) {
		if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof BlockItem) || fillerBlockIds == null) {
			return false;
		}
		String itemId = Registries.ITEM.getId(stack.getItem()).toString();
		return fillerBlockIds.stream().anyMatch(itemId::equals);
	}

	private static void selectAndSyncHotbarSlot(MinecraftClient client, ClientPlayerEntity player, int hotbarSlot) {
		player.getInventory().setSelectedSlot(hotbarSlot);
		if (client.getNetworkHandler() != null) {
			client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(hotbarSlot));
		}
	}

	private static boolean reachedTarget(ClientPlayerEntity player, GoalPosition position) {
		if (player == null || position == null) {
			return false;
		}
		BlockPos playerPos = player.getBlockPos();
		return Math.abs(playerPos.getX() - position.x()) <= NAVIGATION_RADIUS_BLOCKS
			&& Math.abs(playerPos.getZ() - position.z()) <= NAVIGATION_RADIUS_BLOCKS
			&& Math.abs(playerPos.getY() - position.y()) <= 2;
	}

	private static boolean isSurfaceReached(MinecraftClient client, ClientPlayerEntity player) {
		return player != null
			&& player.isOnGround()
			&& SurfaceMemory.isSurfaceStandingPosition(client, player.getBlockPos());
	}

	static SurfaceTargetOutcome surfaceTargetOutcome(boolean surfaceReached, boolean useTowering) {
		if (surfaceReached) {
			return SurfaceTargetOutcome.COMPLETE;
		}
		return useTowering ? SurfaceTargetOutcome.TOWER : SurfaceTargetOutcome.FAIL;
	}

	private Optional<TaskTerminalEvent> complete(WorldTaskRequest request, String message) {
		cancelNavigationIfStarted();
		releaseJumpKey();
		snapshot = snapshot(TaskExecutionState.COMPLETED, request, message);
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.COMPLETED, message, TaskTerminationCause.GOAL_REACHED));
	}

	private Optional<TaskTerminalEvent> fail(WorldTaskRequest request, String reason) {
		cancelNavigationIfStarted();
		releaseJumpKey();
		snapshot = snapshot(TaskExecutionState.FAILED, request, reason);
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.FAILED, reason, null));
	}

	private void cancelNavigationIfStarted() {
		if (navigationStarted && baritoneFacade != null && baritoneFacade.isLoaded()) {
			baritoneFacade.cancel();
		}
		navigationStarted = false;
	}

	private void releaseJumpKey() {
		MinecraftClient client = clientSupplier.get();
		if (client != null) {
			client.options.jumpKey.setPressed(false);
		}
	}

	private void reset() {
		cancelNavigationIfStarted();
		releaseJumpKey();
		appliedTask = null;
		terminalEventEmitted = false;
		toweringStarted = false;
		towerStartY = 0;
		snapshot = TaskExecutionSnapshot.idle();
	}

	private static boolean sameTask(WorldTaskRequest left, WorldTaskRequest right) {
		if (left == right) {
			return true;
		}
		if (left == null || right == null || left.type() != WorldTaskType.RETURN_TO_SURFACE || right.type() != WorldTaskType.RETURN_TO_SURFACE) {
			return false;
		}
		return Objects.equals(left.taskId(), right.taskId())
			&& Objects.equals(left.returnToSurface(), right.returnToSurface());
	}

	private static TaskExecutionSnapshot snapshot(TaskExecutionState state, WorldTaskRequest request, String event) {
		return new TaskExecutionSnapshot(state, request.taskId(), null, "ReturnToSurface", event, null, null);
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

	private record PlacementAttempt(boolean accepted, String reason) {
	}

	enum SurfaceTargetOutcome {
		COMPLETE,
		TOWER,
		FAIL
	}
}
