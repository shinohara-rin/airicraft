package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.AbstractFurnaceScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class SmeltingTaskExecutor implements WorldTaskExecutor {
	private static final double INTERACTION_RANGE_SQUARED = 20.25D;

	private final Supplier<MinecraftClient> clientSupplier;
	private final SmeltingProcessManager processManager;
	private final BaritoneFacade baritoneFacade;

	private WorldTaskRequest appliedTask;
	private boolean terminalEventEmitted;
	private boolean navigationStarted;
	private boolean openedStationForTask;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	public SmeltingTaskExecutor() {
		this(MinecraftClient::getInstance, new SmeltingProcessManager(), null);
	}

	public SmeltingTaskExecutor(SmeltingProcessManager processManager) {
		this(MinecraftClient::getInstance, processManager, null);
	}

	public SmeltingTaskExecutor(SmeltingProcessManager processManager, BaritoneFacade baritoneFacade) {
		this(MinecraftClient::getInstance, processManager, baritoneFacade);
	}

	SmeltingTaskExecutor(Supplier<MinecraftClient> clientSupplier, SmeltingProcessManager processManager, BaritoneFacade baritoneFacade) {
		this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
		this.processManager = Objects.requireNonNull(processManager, "processManager");
		this.baritoneFacade = baritoneFacade;
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
		if (activeTask.isEmpty() || !isSmeltingType(activeTask.get().type())) {
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
		return request.type() == WorldTaskType.SMELT_ITEMS
			? tickSmeltItems(request, client, player)
			: tickCollectSmeltedItems(request, client, player);
	}

	private Optional<TaskTerminalEvent> tickSmeltItems(WorldTaskRequest request, MinecraftClient client, ClientPlayerEntity player) {
		SmeltingOption option = processManager.registeredOption(request.smeltItems().optionId());
		if (option == null) {
			return fail(request, "option_not_found");
		}
		if (option.stationCandidate().source() == SmeltingStationSource.OPEN_SCREEN
			&& player.currentScreenHandler instanceof AbstractFurnaceScreenHandler handler) {
			return insertSmeltingInputs(request, client, player, handler, option);
		}
		BlockPos stationPos = stationPos(option.stationObservation().key());
		if (stationPos == null) {
			return fail(request, "station_unavailable");
		}
		if (!ensureStationReady(request, client, player, option, stationPos)) {
			return Optional.empty();
		}
		if (!(player.currentScreenHandler instanceof AbstractFurnaceScreenHandler handler)) {
			return fail(request, "furnace_screen_not_open");
		}
		if (!handler.getCursorStack().isEmpty()) {
			return fail(request, "cursor_not_empty");
		}
		option = processManager.registeredOption(request.smeltItems().optionId());
		if (option == null) {
			return fail(request, "option_not_found");
		}
		return insertSmeltingInputs(request, client, player, handler, option);
	}

	private Optional<TaskTerminalEvent> insertSmeltingInputs(
		WorldTaskRequest request,
		MinecraftClient client,
		ClientPlayerEntity player,
		AbstractFurnaceScreenHandler handler,
		SmeltingOption option
	) {
		SmeltItemsStepArgs args = request.smeltItems();
		if (!moveItemsToSlot(client, player, handler, option.inputItemId(), 0, args.inputQuantity())) {
			return fail(request, "insufficient_input");
		}
		FuelSelection fuel = fuelSelection(client, player, handler, option, args).orElse(null);
		if (fuel == null) {
			return fail(request, "insufficient_fuel");
		}
		if (fuel.quantity() > 0 && !moveItemsToSlot(client, player, handler, fuel.itemId(), 1, fuel.quantity())) {
			return fail(request, "insufficient_fuel");
		}
		processManager.updateProcessFingerprint(
			option.optionId(),
			option.stationObservation().key(),
			screenSlotSnapshot(handler)
		);
		return complete(request, "smelting_started");
	}

	private Optional<TaskTerminalEvent> tickCollectSmeltedItems(WorldTaskRequest request, MinecraftClient client, ClientPlayerEntity player) {
		CollectSmeltedItemsStepArgs args = request.collectSmeltedItems();
		SmeltingStationKey key = args.processId() == null
			? processManager.confirmedCollectionStationKey(args.confirmationToken())
			: processManager.processStationKey(args.processId());
		BlockPos stationPos = stationPos(key);
		if (stationPos == null) {
			return fail(request, "station_unavailable");
		}
		if (!ensureExistingStationOpen(request, client, player, stationPos)) {
			return Optional.empty();
		}
		if (!(player.currentScreenHandler instanceof AbstractFurnaceScreenHandler handler)) {
			return fail(request, "furnace_screen_not_open");
		}
		if (handler.getSlot(2).getStack().isEmpty()) {
			return fail(request, "output_not_ready");
		}
		client.interactionManager.clickSlot(handler.syncId, 2, 0, SlotActionType.QUICK_MOVE, player);
		if (args.processId() != null) {
			processManager.cancel(args.processId());
		}
		return complete(request, "smelting_collected");
	}

	private boolean ensureStationReady(WorldTaskRequest request, MinecraftClient client, ClientPlayerEntity player, SmeltingOption option, BlockPos stationPos) {
		if (option.stationCandidate().source() == SmeltingStationSource.PLACE_FROM_INVENTORY && !isFurnaceBlock(client, stationPos)) {
			if (!canPlaceAt(client, stationPos)) {
				Optional<BlockPos> fallback = chooseFurnacePlacement(client, player);
				if (fallback.isPresent()) {
					BlockPos fallbackPos = fallback.get();
					SmeltingStationKey oldKey = option.stationObservation().key();
					SmeltingStationKey newKey = new SmeltingStationKey(oldKey.dimensionId(), fallbackPos.getX(), fallbackPos.getY(), fallbackPos.getZ());
					processManager.relocatePlacementProcess(option.optionId(), oldKey, newKey, player.squaredDistanceTo(Vec3d.ofCenter(fallbackPos)));
					stationPos = fallbackPos;
				}
				else {
					snapshot = snapshot(TaskExecutionState.FAILED, request, "furnace_placement_blocked");
					return false;
				}
			}
			if (!withinInteractionRange(player, stationPos)) {
				return navigateOrFail(request, stationPos);
			}
			PlacementAttempt placement = placeFurnace(client, player, stationPos);
			if (!placement.placed()) {
				snapshot = snapshot(TaskExecutionState.RUNNING, request, "placing_furnace:" + placement.reason());
				return false;
			}
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "waiting_for_furnace");
			return false;
		}
		return ensureExistingStationOpen(request, client, player, stationPos);
	}

	private boolean ensureExistingStationOpen(WorldTaskRequest request, MinecraftClient client, ClientPlayerEntity player, BlockPos stationPos) {
		if (player.currentScreenHandler instanceof AbstractFurnaceScreenHandler) {
			return true;
		}
		if (!isFurnaceBlock(client, stationPos)) {
			snapshot = snapshot(TaskExecutionState.FAILED, request, "station_unavailable");
			return false;
		}
		if (!withinInteractionRange(player, stationPos)) {
			return navigateOrFail(request, stationPos);
		}
		BlockHitResult hitResult = new BlockHitResult(Vec3d.ofCenter(stationPos), Direction.UP, stationPos, false);
		ActionResult result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hitResult);
		if (result.isAccepted()) {
			player.swingHand(Hand.MAIN_HAND);
			openedStationForTask = true;
		}
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "opening_furnace");
		return false;
	}

	private boolean navigateOrFail(WorldTaskRequest request, BlockPos stationPos) {
		if (baritoneFacade == null || !baritoneFacade.isLoaded()) {
			snapshot = snapshot(TaskExecutionState.FAILED, request, "station_out_of_range");
			return false;
		}
		if (!navigationStarted) {
			baritoneFacade.startNavigateNear(new GoalPosition(stationPos.getX(), stationPos.getY(), stationPos.getZ(), false), 3);
			navigationStarted = true;
		}
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "navigating_to_furnace");
		return false;
	}

	private Optional<FuelSelection> fuelSelection(
		MinecraftClient client,
		ClientPlayerEntity player,
		ScreenHandler handler,
		SmeltingOption option,
		SmeltItemsStepArgs args
	) {
		if (args.fuelMode() == SmeltingFuelMode.MANUAL) {
			return Optional.of(new FuelSelection(args.fuelItemId(), args.fuelQuantity()));
		}
		if (handler.getSlot(1).getStack().getCount() > 0 || handler.getSlot(1).getStack().isEmpty() && furnaceBurning(handler)) {
			return Optional.of(new FuelSelection(null, 0));
		}
		int requiredFuelTicks = args.inputQuantity() * option.cookTimeTicks();
		FuelSelection best = null;
		for (int slot = 3; slot < handler.slots.size(); slot++) {
			ItemStack stack = handler.getSlot(slot).getStack();
			if (stack.isEmpty() || !client.world.getFuelRegistry().isFuel(stack)) {
				continue;
			}
			int fuelTicks = client.world.getFuelRegistry().getFuelTicks(stack);
			int needed = fuelItemsNeeded(requiredFuelTicks, fuelTicks);
			if (needed > 0 && stack.getCount() >= needed) {
				String itemId = itemId(stack);
				if (best == null || needed < best.quantity()) {
					best = new FuelSelection(itemId, needed);
				}
			}
		}
		return Optional.ofNullable(best);
	}

	static int fuelItemsNeeded(int requiredFuelTicks, int fuelTicksPerItem) {
		if (requiredFuelTicks <= 0) {
			return 0;
		}
		if (fuelTicksPerItem <= 0) {
			return Integer.MAX_VALUE;
		}
		return (requiredFuelTicks + fuelTicksPerItem - 1) / fuelTicksPerItem;
	}

	private static boolean furnaceBurning(ScreenHandler handler) {
		return handler instanceof AbstractFurnaceScreenHandler furnaceHandler && furnaceHandler.isBurning();
	}

	private static boolean moveItemsToSlot(MinecraftClient client, ClientPlayerEntity player, ScreenHandler handler, String itemId, int targetSlot, int quantity) {
		int remaining = quantity;
		while (remaining > 0) {
			int sourceSlot = findSourceSlot(handler, itemId);
			if (sourceSlot < 0) {
				return false;
			}
			ItemStack sourceStack = handler.getSlot(sourceSlot).getStack();
			int moved = moveFromSourceToTarget(client, player, handler, sourceSlot, targetSlot, Math.min(remaining, sourceStack.getCount()));
			if (moved <= 0) {
				return false;
			}
			remaining -= moved;
		}
		return true;
	}

	private static int moveFromSourceToTarget(
		MinecraftClient client,
		ClientPlayerEntity player,
		ScreenHandler handler,
		int sourceSlot,
		int targetSlot,
		int maxQuantity
	) {
		if (!handler.getCursorStack().isEmpty()) {
			return 0;
		}
		ItemStack targetStack = handler.getSlot(targetSlot).getStack();
		ItemStack sourceStack = handler.getSlot(sourceSlot).getStack();
		int targetCountBefore = targetStack.isEmpty() ? 0 : targetStack.getCount();
		if (sourceStack.isEmpty()) {
			return 0;
		}
		if (!targetStack.isEmpty() && !ItemStack.areItemsAndComponentsEqual(targetStack, sourceStack)) {
			return 0;
		}
		int targetSpace = targetStack.isEmpty()
			? Math.min(sourceStack.getMaxCount(), 64)
			: Math.max(0, targetStack.getMaxCount() - targetStack.getCount());
		int toMove = Math.min(maxQuantity, targetSpace);
		if (toMove <= 0) {
			return 0;
		}
		client.interactionManager.clickSlot(handler.syncId, sourceSlot, 0, SlotActionType.PICKUP, player);
		for (int index = 0; index < toMove; index++) {
			client.interactionManager.clickSlot(handler.syncId, targetSlot, 1, SlotActionType.PICKUP, player);
		}
		if (!handler.getCursorStack().isEmpty()) {
			client.interactionManager.clickSlot(handler.syncId, sourceSlot, 0, SlotActionType.PICKUP, player);
		}
		ItemStack targetAfter = handler.getSlot(targetSlot).getStack();
		int targetCountAfter = targetAfter.isEmpty() ? 0 : targetAfter.getCount();
		return Math.max(0, targetCountAfter - targetCountBefore);
	}

	private static int findSourceSlot(ScreenHandler handler, String itemId) {
		for (int slot = 3; slot < handler.slots.size(); slot++) {
			ItemStack stack = handler.getSlot(slot).getStack();
			if (!stack.isEmpty() && itemId.equals(itemId(stack))) {
				return slot;
			}
		}
		return -1;
	}

	private static boolean isFurnaceBlock(MinecraftClient client, BlockPos pos) {
		if (client == null || client.world == null || pos == null || !client.world.isChunkLoaded(pos)) {
			return false;
		}
		BlockState state = client.world.getBlockState(pos);
		return state.isOf(Blocks.FURNACE) || state.isOf(Blocks.BLAST_FURNACE) || state.isOf(Blocks.SMOKER);
	}

	private static PlacementAttempt placeFurnace(MinecraftClient client, ClientPlayerEntity player, BlockPos pos) {
		if (player.currentScreenHandler != player.playerScreenHandler || !player.currentScreenHandler.getCursorStack().isEmpty()) {
			return new PlacementAttempt(false, "inventory_not_ready");
		}
		Hand hand = selectFurnacePlacementHand(client, player);
		if (hand == null) {
			return new PlacementAttempt(false, "furnace_not_selectable");
		}
		BlockPos support = pos.down();
		BlockHitResult hitResult = new BlockHitResult(
			new Vec3d(support.getX() + 0.5D, support.getY() + 1.0D, support.getZ() + 0.5D),
			Direction.UP,
			support,
			false
		);
		ActionResult result = client.interactionManager.interactBlock(player, hand, hitResult);
		if (result.isAccepted()) {
			player.swingHand(hand);
		}
		return new PlacementAttempt(result.isAccepted(), result.isAccepted() ? "accepted" : "interact_" + result);
	}

	private static Hand selectFurnacePlacementHand(MinecraftClient client, ClientPlayerEntity player) {
		if (player.getOffHandStack().isOf(Items.FURNACE)) {
			return Hand.OFF_HAND;
		}
		return selectHotbarItem(client, player, Items.FURNACE) ? Hand.MAIN_HAND : null;
	}

	private static boolean selectHotbarItem(MinecraftClient client, ClientPlayerEntity player, Item item) {
		ScreenHandler handler = player.currentScreenHandler;
		int sourceSlot = findInventorySlot(handler, item);
		if (sourceSlot < 0) {
			return false;
		}
		int selectedHotbarSlot = player.getInventory().getSelectedSlot();
		if (sourceSlot >= PlayerScreenHandler.HOTBAR_START && sourceSlot < PlayerScreenHandler.HOTBAR_END) {
			player.getInventory().setSelectedSlot(sourceSlot - PlayerScreenHandler.HOTBAR_START);
			return true;
		}
		client.interactionManager.clickSlot(handler.syncId, sourceSlot, selectedHotbarSlot, SlotActionType.SWAP, player);
		player.getInventory().setSelectedSlot(selectedHotbarSlot);
		ItemStack selected = player.getInventory().getSelectedStack();
		return !selected.isEmpty() && selected.isOf(item);
	}

	private static int findInventorySlot(ScreenHandler handler, Item item) {
		if (!(handler instanceof PlayerScreenHandler)) {
			return -1;
		}
		ItemStack offhand = handler.getSlot(PlayerScreenHandler.OFFHAND_ID).getStack();
		if (!offhand.isEmpty() && offhand.isOf(item)) {
			return PlayerScreenHandler.OFFHAND_ID;
		}
		for (int slot = PlayerScreenHandler.INVENTORY_START; slot < PlayerScreenHandler.HOTBAR_END; slot++) {
			ItemStack stack = handler.getSlot(slot).getStack();
			if (!stack.isEmpty() && stack.isOf(item)) {
				return slot;
			}
		}
		return -1;
	}

	private static Optional<BlockPos> chooseFurnacePlacement(MinecraftClient client, ClientPlayerEntity player) {
		BlockPos origin = player.getBlockPos();
		for (Direction direction : Direction.Type.HORIZONTAL) {
			BlockPos candidate = origin.offset(direction);
			if (canPlaceAt(client, candidate)) {
				return Optional.of(candidate.toImmutable());
			}
		}
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				BlockPos candidate = origin.add(dx, 0, dz);
				if (!candidate.equals(origin) && canPlaceAt(client, candidate)) {
					return Optional.of(candidate.toImmutable());
				}
			}
		}
		return Optional.empty();
	}

	private static boolean canPlaceAt(MinecraftClient client, BlockPos pos) {
		if (client == null || client.world == null || !client.world.isChunkLoaded(pos) || !client.world.isChunkLoaded(pos.down())) {
			return false;
		}
		BlockState target = client.world.getBlockState(pos);
		BlockState support = client.world.getBlockState(pos.down());
		return (target.isAir() || target.isReplaceable())
			&& support.isSideSolidFullSquare(client.world, pos.down(), Direction.UP);
	}

	private static boolean withinInteractionRange(ClientPlayerEntity player, BlockPos pos) {
		return player.squaredDistanceTo(Vec3d.ofCenter(pos)) <= INTERACTION_RANGE_SQUARED;
	}

	private static BlockPos stationPos(SmeltingStationKey key) {
		if (key == null || key.dimensionId().contains("#open_screen")) {
			return null;
		}
		return new BlockPos(key.x(), key.y(), key.z());
	}

	private Optional<TaskTerminalEvent> complete(WorldTaskRequest request, String message) {
		cancelNavigationIfStarted();
		closeOpenedStationIfSafe();
		snapshot = snapshot(TaskExecutionState.COMPLETED, request, message);
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.COMPLETED, message, TaskTerminationCause.GOAL_REACHED));
	}

	private Optional<TaskTerminalEvent> fail(WorldTaskRequest request, String reason) {
		cancelNavigationIfStarted();
		closeOpenedStationIfSafe();
		snapshot = snapshot(TaskExecutionState.FAILED, request, reason);
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.FAILED, reason, null));
	}

	private void closeOpenedStationIfSafe() {
		if (!openedStationForTask) {
			return;
		}
		MinecraftClient client = clientSupplier.get();
		ClientPlayerEntity player = client == null ? null : client.player;
		if (player != null
			&& player.currentScreenHandler instanceof AbstractFurnaceScreenHandler
			&& player.currentScreenHandler.getCursorStack().isEmpty()) {
			player.closeHandledScreen();
		}
		openedStationForTask = false;
	}

	private void cancelNavigationIfStarted() {
		if (navigationStarted && baritoneFacade != null && baritoneFacade.isLoaded()) {
			baritoneFacade.cancel();
		}
		navigationStarted = false;
	}

	private static boolean isSmeltingType(WorldTaskType type) {
		return type == WorldTaskType.SMELT_ITEMS || type == WorldTaskType.COLLECT_SMELTED_ITEMS;
	}

	private static boolean sameTask(WorldTaskRequest left, WorldTaskRequest right) {
		if (left == right) {
			return true;
		}
		if (left == null || right == null || left.type() != right.type()) {
			return false;
		}
		return Objects.equals(left.taskId(), right.taskId())
			&& Objects.equals(left.smeltItems(), right.smeltItems())
			&& Objects.equals(left.collectSmeltedItems(), right.collectSmeltedItems());
	}

	private static TaskExecutionSnapshot snapshot(TaskExecutionState state, WorldTaskRequest request, String event) {
		return new TaskExecutionSnapshot(state, request.taskId(), null, "Smelting", event, null, null);
	}

	private static String itemId(ItemStack stack) {
		return stack == null || stack.isEmpty() ? null : Registries.ITEM.getId(stack.getItem()).toString();
	}

	private static SmeltingSlotSnapshot screenSlotSnapshot(AbstractFurnaceScreenHandler handler) {
		return new SmeltingSlotSnapshot(
			itemId(handler.getSlot(0).getStack()),
			handler.getSlot(0).getStack().getCount(),
			itemId(handler.getSlot(1).getStack()),
			handler.getSlot(1).getStack().getCount(),
			itemId(handler.getSlot(2).getStack()),
			handler.getSlot(2).getStack().getCount(),
			0,
			200,
			handler.isBurning()
		);
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
		cancelNavigationIfStarted();
		closeOpenedStationIfSafe();
		appliedTask = null;
		terminalEventEmitted = false;
		openedStationForTask = false;
		snapshot = TaskExecutionSnapshot.idle();
	}

	private record FuelSelection(String itemId, int quantity) {
	}

	private record PlacementAttempt(boolean placed, String reason) {
	}
}
