package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.CraftingScreenHandler;
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

public final class CraftingTaskExecutor implements WorldTaskExecutor {
	private static final int WAIT_TIMEOUT_TICKS = 20;
	private static final int TABLE_SEARCH_RADIUS = 10;
	private static final double TABLE_INTERACTION_RANGE_SQUARED = 20.25D;

	private final Supplier<MinecraftClient> clientSupplier;
	private final BaritoneFacade baritoneFacade;

	private WorldTaskRequest appliedTask;
	private CraftingPlan plan;
	private CraftingPlan craftingTablePlan;
	private CraftPhase phase = CraftPhase.IDLE;
	private final CraftingProgressTracker progressTracker = new CraftingProgressTracker();
	private final CraftingProgressTracker craftingTableProgressTracker = new CraftingProgressTracker();
	private int waitTicks;
	private boolean terminalEventEmitted;
	private TableTarget tableTarget;
	private BlockPos placedTablePos;
	private boolean navigationStarted;
	private boolean openedWorkbenchForTask;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	public CraftingTaskExecutor() {
		this(MinecraftClient::getInstance, null);
	}

	public CraftingTaskExecutor(BaritoneFacade baritoneFacade) {
		this(MinecraftClient::getInstance, baritoneFacade);
	}

	CraftingTaskExecutor(Supplier<MinecraftClient> clientSupplier) {
		this(clientSupplier, null);
	}

	CraftingTaskExecutor(Supplier<MinecraftClient> clientSupplier, BaritoneFacade baritoneFacade) {
		this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
		this.baritoneFacade = baritoneFacade;
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
		if (activeTask.isEmpty() || activeTask.get().type() != WorldTaskType.CRAFT_RECIPE) {
			reset();
			return Optional.empty();
		}

		WorldTaskRequest request = activeTask.get();
		if (!sameTask(request, appliedTask)) {
			reset();
			appliedTask = request;
		}

		if (!sessionSnapshot.companionActuationAllowed()) {
			snapshot = snapshot(TaskExecutionState.PAUSED_BY_SESSION_GATE, request, "session_gate");
			return Optional.empty();
		}

		MinecraftClient client = clientSupplier.get();
		ClientPlayerEntity player = client == null ? null : client.player;
		if (client == null || client.interactionManager == null || player == null) {
			return fail(request, "crafting_busy");
		}
		if (plan == null) {
			Optional<CraftingPlan> resolved = resolvePlan(player, request.craftRecipe());
			if (resolved.isEmpty()) {
				return fail(request, "recipe_not_found");
			}
			plan = resolved.get();
			if (plan.failureReason() != null) {
				return fail(request, plan.failureReason());
			}
			progressTracker.reset();
		}

		if (progressTracker.targetReached(plan.targetOutputCount())) {
			return complete(request);
		}

		if (plan.gridKind() == CraftingGridKind.WORKBENCH_3X3) {
			WorkbenchReadiness readiness = ensureWorkbenchReady(request, client, player);
			if (readiness.failureReason().isPresent()) {
				return fail(request, readiness.failureReason().get());
			}
			if (!readiness.ready()) {
				return Optional.empty();
			}
		}

		CraftAdvanceResult advanced = advanceCrafting(
			request,
			client,
			player,
			plan,
			CraftingGridSpec.forKind(plan.gridKind()),
			progressTracker,
			CraftPhase.PLACING_INPUTS,
			CraftPhase.WAITING_FOR_RESULT,
			CraftPhase.WAITING_FOR_TAKE
		);
		if (advanced.status() == CraftAdvanceStatus.FAILED) {
			return fail(request, advanced.reason());
		}
		if (advanced.status() == CraftAdvanceStatus.COMPLETED) {
			return complete(request);
		}

		snapshot = snapshot(TaskExecutionState.RUNNING, request, "crafting");
		return Optional.empty();
	}

	private Optional<CraftingPlan> resolvePlan(ClientPlayerEntity player, CraftRecipeStepArgs request) {
		CraftingOpportunityResolver.CraftingRecipeResolution resolved = CraftingOpportunityResolver.resolve(player, request);
		if (resolved.failureReason() != null) {
			return Optional.of(CraftingPlan.failure(resolved.failureReason()));
		}
		return Optional.of(new CraftingPlan(
			resolved.outputItem(),
			resolved.outputCount(),
			resolved.requestedTimes(),
			resolved.outputCount() * resolved.requestedTimes(),
			resolved.gridKind(),
			resolved.placements(),
			null
		));
	}

	private WorkbenchReadiness ensureWorkbenchReady(WorldTaskRequest request, MinecraftClient client, ClientPlayerEntity player) {
		if (player.currentScreenHandler instanceof CraftingScreenHandler) {
			if (phase != CraftPhase.IDLE
				&& phase != CraftPhase.PLACING_INPUTS
				&& phase != CraftPhase.WAITING_FOR_RESULT
				&& phase != CraftPhase.WAITING_FOR_TAKE) {
				phase = CraftPhase.PLACING_INPUTS;
				waitTicks = 0;
			}
			return WorkbenchReadiness.readyState();
		}
		if (player.currentScreenHandler != player.playerScreenHandler) {
			return WorkbenchReadiness.failed("crafting_busy");
		}
		if (client.currentScreen != null && !(client.currentScreen instanceof InventoryScreen)) {
			return WorkbenchReadiness.failed("crafting_busy");
		}
		if (phase == CraftPhase.IDLE) {
			tableTarget = findNearbyCraftingTable(client, player).orElse(null);
			if (tableTarget != null) {
				phase = CraftPhase.NAVIGATING_TO_TABLE;
				waitTicks = 0;
			}
			else if (hasCraftingTableItem(player, player.currentScreenHandler)) {
				phase = CraftPhase.PLACING_TABLE;
				waitTicks = 0;
			}
			else {
				phase = CraftPhase.CRAFTING_TABLE_INPUTS;
				waitTicks = 0;
			}
		}

		if (phase == CraftPhase.CRAFTING_TABLE_INPUTS
			|| phase == CraftPhase.CRAFTING_TABLE_WAITING_FOR_RESULT
			|| phase == CraftPhase.CRAFTING_TABLE_WAITING_FOR_TAKE) {
			if (craftingTablePlan == null) {
				CraftingOpportunityResolver.CraftingRecipeResolution resolved = CraftingOpportunityResolver.resolveCraftingTable(player);
				if (resolved.failureReason() != null) {
					return WorkbenchReadiness.failed(resolved.failureReason());
				}
				craftingTablePlan = new CraftingPlan(
					resolved.outputItem(),
					resolved.outputCount(),
					1,
					resolved.outputCount(),
					resolved.gridKind(),
					resolved.placements(),
					null
				);
				craftingTableProgressTracker.reset();
			}
			CraftAdvanceResult advanced = advanceCrafting(
				request,
				client,
				player,
				craftingTablePlan,
				CraftingGridSpec.PLAYER,
				craftingTableProgressTracker,
				CraftPhase.CRAFTING_TABLE_INPUTS,
				CraftPhase.CRAFTING_TABLE_WAITING_FOR_RESULT,
				CraftPhase.CRAFTING_TABLE_WAITING_FOR_TAKE
			);
			if (advanced.status() == CraftAdvanceStatus.FAILED) {
				return WorkbenchReadiness.failed("crafting_table_missing_materials");
			}
			if (advanced.status() == CraftAdvanceStatus.COMPLETED) {
				phase = CraftPhase.PLACING_TABLE;
				waitTicks = 0;
			}
			return WorkbenchReadiness.notReadyState();
		}

		if (phase == CraftPhase.PLACING_TABLE) {
			if (closeInventoryScreenIfOpen(client, player)) {
				snapshot = snapshot(TaskExecutionState.RUNNING, request, "inventory_screen_dismissed");
				return WorkbenchReadiness.notReadyState();
			}
			if (placeCraftingTable(client, player)) {
				phase = CraftPhase.WAITING_FOR_TABLE_PLACED;
				waitTicks = 0;
				snapshot = snapshot(TaskExecutionState.RUNNING, request, "crafting_table_placed");
				return WorkbenchReadiness.notReadyState();
			}
			return WorkbenchReadiness.failed("crafting_table_place_failed");
		}

		if (phase == CraftPhase.WAITING_FOR_TABLE_PLACED) {
			if (placedTablePos != null && client.world != null && client.world.getBlockState(placedTablePos).isOf(Blocks.CRAFTING_TABLE)) {
				tableTarget = new TableTarget(placedTablePos, standPositionForTable(client, player, placedTablePos).orElse(null));
				phase = CraftPhase.OPENING_TABLE;
				waitTicks = 0;
				return WorkbenchReadiness.notReadyState();
			}
			return waitForWorkbench(request, player, "crafting_table_place_failed");
		}

		if (phase == CraftPhase.NAVIGATING_TO_TABLE) {
			if (closeInventoryScreenIfOpen(client, player)) {
				snapshot = snapshot(TaskExecutionState.RUNNING, request, "inventory_screen_dismissed");
				return WorkbenchReadiness.notReadyState();
			}
			if (tableTarget == null) {
				return WorkbenchReadiness.failed("crafting_table_not_found");
			}
			if (withinInteractionRange(player, tableTarget.tablePos())) {
				phase = CraftPhase.OPENING_TABLE;
				waitTicks = 0;
				return WorkbenchReadiness.notReadyState();
			}
			if (tableTarget.standPosition() == null || baritoneFacade == null || !baritoneFacade.isLoaded()) {
				return fallBackToPortableCraftingTable(request, player);
			}
			if (!navigationStarted) {
				baritoneFacade.startNavigate(tableTarget.standPosition());
				navigationStarted = true;
				snapshot = snapshot(TaskExecutionState.RUNNING, request, "crafting_table_navigation_started");
				return WorkbenchReadiness.notReadyState();
			}
			Optional<String> pathEvent = baritoneFacade.pollPathEvent();
			if (pathEvent.isPresent() && "CALC_FAILED".equalsIgnoreCase(pathEvent.get())) {
				return fallBackToPortableCraftingTable(request, player);
			}
			if (baritoneFacade.navigationGoalReached(tableTarget.standPosition()) || withinInteractionRange(player, tableTarget.tablePos())) {
				phase = CraftPhase.OPENING_TABLE;
				waitTicks = 0;
			}
			else {
				snapshot = snapshot(TaskExecutionState.RUNNING, request, "crafting_table_navigating");
			}
			return WorkbenchReadiness.notReadyState();
		}

		if (phase == CraftPhase.OPENING_TABLE) {
			if (closeInventoryScreenIfOpen(client, player)) {
				snapshot = snapshot(TaskExecutionState.RUNNING, request, "inventory_screen_dismissed");
				return WorkbenchReadiness.notReadyState();
			}
			if (tableTarget == null || tableTarget.tablePos() == null) {
				return WorkbenchReadiness.failed("crafting_table_not_found");
			}
			if (!openCraftingTable(client, player, tableTarget.tablePos())) {
				return fallBackToPortableCraftingTable(request, player);
			}
			phase = CraftPhase.WAITING_FOR_TABLE_SCREEN;
			waitTicks = 0;
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "crafting_table_opened");
			return WorkbenchReadiness.notReadyState();
		}

		if (phase == CraftPhase.WAITING_FOR_TABLE_SCREEN) {
			if (player.currentScreenHandler instanceof CraftingScreenHandler) {
				phase = CraftPhase.PLACING_INPUTS;
				waitTicks = 0;
				return WorkbenchReadiness.readyState();
			}
			return waitForWorkbench(request, player, "crafting_table_open_failed");
		}

		return WorkbenchReadiness.notReadyState();
	}

	private WorkbenchReadiness waitForWorkbench(WorldTaskRequest request, ClientPlayerEntity player, String reason) {
		waitTicks++;
		if (waitTicks > WAIT_TIMEOUT_TICKS) {
			if ("crafting_table_open_failed".equals(reason)) {
				return fallBackToPortableCraftingTable(request, player);
			}
			return WorkbenchReadiness.failed(reason);
		}
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "crafting_table_wait");
		return WorkbenchReadiness.notReadyState();
	}

	private WorkbenchReadiness fallBackToPortableCraftingTable(WorldTaskRequest request, ClientPlayerEntity player) {
		cancelNavigationIfStarted();
		tableTarget = null;
		placedTablePos = null;
		waitTicks = 0;
		if (hasCraftingTableItem(player, player.currentScreenHandler)) {
			phase = CraftPhase.PLACING_TABLE;
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "crafting_table_fallback_to_inventory");
			return WorkbenchReadiness.notReadyState();
		}
		phase = CraftPhase.CRAFTING_TABLE_INPUTS;
		craftingTablePlan = null;
		craftingTableProgressTracker.reset();
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "crafting_table_fallback_to_planks");
		return WorkbenchReadiness.notReadyState();
	}

	private CraftAdvanceResult advanceCrafting(
		WorldTaskRequest request,
		MinecraftClient client,
		ClientPlayerEntity player,
		CraftingPlan plan,
		CraftingGridSpec gridSpec,
		CraftingProgressTracker tracker,
		CraftPhase placingPhase,
		CraftPhase waitingForResultPhase,
		CraftPhase waitingForTakePhase
	) {
		boolean requireEmptyGrid = phase == CraftPhase.IDLE || phase == placingPhase;
		Optional<String> readinessFailure = readinessFailure(client, player, gridSpec, requireEmptyGrid);
		if (readinessFailure.isPresent()) {
			return CraftAdvanceResult.failed(readinessFailure.get());
		}

		ScreenHandler handler = player.currentScreenHandler;
		if (phase == CraftPhase.IDLE || phase == placingPhase) {
			if (!placeRecipeInputs(client, player, plan, gridSpec)) {
				return CraftAdvanceResult.failed("missing_ingredients");
			}
			phase = waitingForResultPhase;
			waitTicks = 0;
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "crafting_inputs_placed");
			return CraftAdvanceResult.running();
		}

		if (phase == waitingForResultPhase) {
			ItemStack resultStack = handler.getSlot(gridSpec.resultSlot()).getStack();
			if (!resultStack.isEmpty() && resultStack.isOf(plan.outputItem())) {
				tracker.beginTake(inventoryCount(player, plan.outputItem()), resultStack.getCount());
				client.interactionManager.clickSlot(handler.syncId, gridSpec.resultSlot(), 0, SlotActionType.QUICK_MOVE, player);
				phase = waitingForTakePhase;
				waitTicks = 0;
				snapshot = snapshot(TaskExecutionState.RUNNING, request, "crafting_result_taken");
				return CraftAdvanceResult.running();
			}
			return waitCraftAdvance(request, "crafting_busy");
		}

		if (phase == waitingForTakePhase) {
			if (tracker.finishTakeIfInventoryIncreased(inventoryCount(player, plan.outputItem()))) {
				phase = placingPhase;
				waitTicks = 0;
				if (tracker.targetReached(plan.targetOutputCount())) {
					return CraftAdvanceResult.completed();
				}
				snapshot = snapshot(TaskExecutionState.RUNNING, request, "crafting_next_batch");
				return CraftAdvanceResult.running();
			}
			return waitCraftAdvance(request, "crafting_busy");
		}

		return CraftAdvanceResult.running();
	}

	private CraftAdvanceResult waitCraftAdvance(WorldTaskRequest request, String reason) {
		waitTicks++;
		if (waitTicks > WAIT_TIMEOUT_TICKS) {
			return CraftAdvanceResult.failed(reason);
		}
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "crafting_wait");
		return CraftAdvanceResult.running();
	}

	private static boolean placeRecipeInputs(MinecraftClient client, ClientPlayerEntity player, CraftingPlan plan, CraftingGridSpec gridSpec) {
		ScreenHandler handler = player.currentScreenHandler;
		for (CraftingOpportunityResolver.CraftingIngredientPlacement placement : plan.placements()) {
			int targetSlot = gridSpec.firstInputSlot() + placement.gridIndex();
			if (!handler.getSlot(targetSlot).getStack().isEmpty()) {
				return false;
			}
			if (!moveSingleItemToCraftingSlot(client, player, handler, gridSpec, targetSlot, placement.item())) {
				return false;
			}
		}
		return handler.getCursorStack().isEmpty();
	}

	private static boolean moveSingleItemToCraftingSlot(MinecraftClient client, ClientPlayerEntity player, ScreenHandler handler, CraftingGridSpec gridSpec, int targetSlot, Item item) {
		int sourceSlot = findInventorySlot(handler, gridSpec, item);
		if (sourceSlot < 0) {
			return false;
		}
		client.interactionManager.clickSlot(handler.syncId, sourceSlot, 0, SlotActionType.PICKUP, player);
		client.interactionManager.clickSlot(handler.syncId, targetSlot, 1, SlotActionType.PICKUP, player);
		if (!handler.getCursorStack().isEmpty()) {
			client.interactionManager.clickSlot(handler.syncId, sourceSlot, 0, SlotActionType.PICKUP, player);
		}
		return handler.getCursorStack().isEmpty()
			&& !handler.getSlot(targetSlot).getStack().isEmpty()
			&& handler.getSlot(targetSlot).getStack().isOf(item);
	}

	private static int findInventorySlot(ScreenHandler handler, CraftingGridSpec gridSpec, Item item) {
		for (int slot = gridSpec.inventoryStart(); slot < gridSpec.hotbarEnd(); slot++) {
			ItemStack stack = handler.getSlot(slot).getStack();
			if (!stack.isEmpty() && stack.isOf(item)) {
				return slot;
			}
		}
		return -1;
	}

	private static Optional<String> readinessFailure(MinecraftClient client, ClientPlayerEntity player, CraftingGridSpec gridSpec, boolean requireEmptyGrid) {
		if (!gridSpec.matches(player.currentScreenHandler)) {
			return Optional.of("crafting_busy");
		}
		if (gridSpec.kind() == CraftingGridKind.PLAYER_2X2 && client.currentScreen != null && !(client.currentScreen instanceof InventoryScreen)) {
			return Optional.of("crafting_busy");
		}
		ScreenHandler handler = player.currentScreenHandler;
		if (!handler.getCursorStack().isEmpty()) {
			return Optional.of("crafting_grid_occupied");
		}
		if (!requireEmptyGrid) {
			return Optional.empty();
		}
		for (int index = gridSpec.firstInputSlot(); index < gridSpec.firstInputSlot() + gridSpec.inputSlotCount(); index++) {
			if (!handler.getSlot(index).getStack().isEmpty()) {
				return Optional.of("crafting_grid_occupied");
			}
		}
		return Optional.empty();
	}

	private static Optional<TableTarget> findNearbyCraftingTable(MinecraftClient client, ClientPlayerEntity player) {
		if (client.world == null) {
			return Optional.empty();
		}
		BlockPos origin = player.getBlockPos();
		TableTarget best = null;
		double bestDistance = Double.MAX_VALUE;
		for (int dx = -TABLE_SEARCH_RADIUS; dx <= TABLE_SEARCH_RADIUS; dx++) {
			for (int dy = -4; dy <= 4; dy++) {
				for (int dz = -TABLE_SEARCH_RADIUS; dz <= TABLE_SEARCH_RADIUS; dz++) {
					BlockPos pos = origin.add(dx, dy, dz);
					double distance = origin.getSquaredDistance(pos);
					if (distance > TABLE_SEARCH_RADIUS * TABLE_SEARCH_RADIUS || distance >= bestDistance || !client.world.isChunkLoaded(pos)) {
						continue;
					}
					if (!client.world.getBlockState(pos).isOf(Blocks.CRAFTING_TABLE)) {
						continue;
					}
					GoalPosition standPosition = standPositionForTable(client, player, pos).orElse(null);
					if (standPosition == null && !withinInteractionRange(player, pos)) {
						continue;
					}
					best = new TableTarget(pos.toImmutable(), standPosition);
					bestDistance = distance;
				}
			}
		}
		return Optional.ofNullable(best);
	}

	private static Optional<GoalPosition> standPositionForTable(MinecraftClient client, ClientPlayerEntity player, BlockPos tablePos) {
		if (client.world == null) {
			return Optional.empty();
		}
		BlockPos current = player.getBlockPos();
		GoalPosition best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Direction direction : Direction.Type.HORIZONTAL) {
			BlockPos candidate = tablePos.offset(direction);
			if (!isStandable(client, candidate)) {
				continue;
			}
			double distance = current.getSquaredDistance(candidate);
			if (distance < bestDistance) {
				best = new GoalPosition(candidate.getX(), candidate.getY(), candidate.getZ(), false);
				bestDistance = distance;
			}
		}
		return Optional.ofNullable(best);
	}

	private static boolean isStandable(MinecraftClient client, BlockPos pos) {
		if (client.world == null || !client.world.isChunkLoaded(pos) || !client.world.isChunkLoaded(pos.up())) {
			return false;
		}
		BlockState feet = client.world.getBlockState(pos);
		BlockState head = client.world.getBlockState(pos.up());
		BlockState floor = client.world.getBlockState(pos.down());
		return (feet.isAir() || feet.isReplaceable())
			&& (head.isAir() || head.isReplaceable())
			&& floor.isSideSolidFullSquare(client.world, pos.down(), Direction.UP);
	}

	private static boolean withinInteractionRange(ClientPlayerEntity player, BlockPos pos) {
		return player.squaredDistanceTo(Vec3d.ofCenter(pos)) <= TABLE_INTERACTION_RANGE_SQUARED;
	}

	private static boolean closeInventoryScreenIfOpen(MinecraftClient client, ClientPlayerEntity player) {
		if (client.currentScreen instanceof InventoryScreen
			&& player.currentScreenHandler == player.playerScreenHandler
			&& player.currentScreenHandler.getCursorStack().isEmpty()) {
			client.setScreen(null);
			return true;
		}
		return false;
	}

	private boolean placeCraftingTable(MinecraftClient client, ClientPlayerEntity player) {
		if (client.world == null || player.currentScreenHandler != player.playerScreenHandler || !player.currentScreenHandler.getCursorStack().isEmpty()) {
			return false;
		}
		placedTablePos = chooseCraftingTablePlacement(client, player).orElse(null);
		if (placedTablePos == null) {
			return false;
		}
		if (!selectHotbarItem(client, player, Items.CRAFTING_TABLE)) {
			return false;
		}
		BlockPos support = placedTablePos.down();
		BlockHitResult hitResult = new BlockHitResult(
			new Vec3d(support.getX() + 0.5D, support.getY() + 1.0D, support.getZ() + 0.5D),
			Direction.UP,
			support,
			false
		);
		ActionResult result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hitResult);
		if (result.isAccepted()) {
			player.swingHand(Hand.MAIN_HAND);
		}
		return result.isAccepted();
	}

	private static Optional<BlockPos> chooseCraftingTablePlacement(MinecraftClient client, ClientPlayerEntity player) {
		BlockPos origin = player.getBlockPos();
		for (Direction direction : Direction.Type.HORIZONTAL) {
			BlockPos candidate = origin.offset(direction);
			if (canPlaceCraftingTableAt(client, candidate)) {
				return Optional.of(candidate.toImmutable());
			}
		}
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				BlockPos candidate = origin.add(dx, 0, dz);
				if (!candidate.equals(origin) && canPlaceCraftingTableAt(client, candidate)) {
					return Optional.of(candidate.toImmutable());
				}
			}
		}
		return Optional.empty();
	}

	private static boolean canPlaceCraftingTableAt(MinecraftClient client, BlockPos pos) {
		if (client.world == null || !client.world.isChunkLoaded(pos) || !client.world.isChunkLoaded(pos.down())) {
			return false;
		}
		BlockState target = client.world.getBlockState(pos);
		BlockState support = client.world.getBlockState(pos.down());
		return (target.isAir() || target.isReplaceable())
			&& support.isSideSolidFullSquare(client.world, pos.down(), Direction.UP);
	}

	private static boolean selectHotbarItem(MinecraftClient client, ClientPlayerEntity player, Item item) {
		ScreenHandler handler = player.currentScreenHandler;
		int sourceSlot = findInventorySlot(handler, CraftingGridSpec.PLAYER, item);
		if (sourceSlot < 0) {
			return false;
		}
		int selectedHotbarSlot = player.getInventory().getSelectedSlot();
		if (sourceSlot >= CraftingGridSpec.PLAYER.hotbarStart() && sourceSlot < CraftingGridSpec.PLAYER.hotbarEnd()) {
			player.getInventory().setSelectedSlot(sourceSlot - CraftingGridSpec.PLAYER.hotbarStart());
			return true;
		}
		client.interactionManager.clickSlot(handler.syncId, sourceSlot, selectedHotbarSlot, SlotActionType.SWAP, player);
		player.getInventory().setSelectedSlot(selectedHotbarSlot);
		ItemStack selected = player.getInventory().getSelectedStack();
		return !selected.isEmpty() && selected.isOf(item);
	}

	private static boolean hasCraftingTableItem(ClientPlayerEntity player, ScreenHandler handler) {
		return handler != null && findInventorySlot(handler, CraftingGridSpec.PLAYER, Items.CRAFTING_TABLE) >= 0;
	}

	private boolean openCraftingTable(MinecraftClient client, ClientPlayerEntity player, BlockPos pos) {
		if (client.world == null || !client.world.isChunkLoaded(pos) || !client.world.getBlockState(pos).isOf(Blocks.CRAFTING_TABLE)) {
			return false;
		}
		if (!withinInteractionRange(player, pos)) {
			return false;
		}
		BlockHitResult hitResult = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
		ActionResult result = client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hitResult);
		if (result.isAccepted()) {
			player.swingHand(Hand.MAIN_HAND);
			openedWorkbenchForTask = true;
		}
		return result.isAccepted();
	}

	private static int inventoryCount(ClientPlayerEntity player, Item item) {
		int count = 0;
		for (int index = 0; index < player.getInventory().size(); index++) {
			ItemStack stack = player.getInventory().getStack(index);
			if (stack.isOf(item)) {
				count += stack.getCount();
			}
		}
		return count;
	}

	private Optional<TaskTerminalEvent> complete(WorldTaskRequest request) {
		cancelNavigationIfStarted();
		closeOwnedWorkbenchIfSafe();
		snapshot = snapshot(TaskExecutionState.COMPLETED, request, "crafted");
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.COMPLETED, "crafted", null));
	}

	private void closeOwnedWorkbenchIfSafe() {
		if (!openedWorkbenchForTask) {
			return;
		}
		MinecraftClient client = clientSupplier.get();
		ClientPlayerEntity player = client == null ? null : client.player;
		if (player != null
			&& player.currentScreenHandler instanceof CraftingScreenHandler
			&& player.currentScreenHandler.getCursorStack().isEmpty()) {
			player.closeHandledScreen();
		}
		openedWorkbenchForTask = false;
	}

	private Optional<TaskTerminalEvent> fail(WorldTaskRequest request, String reason) {
		cancelNavigationIfStarted();
		closeOwnedWorkbenchIfSafe();
		snapshot = snapshot(TaskExecutionState.FAILED, request, reason);
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.FAILED, reason, null));
	}

	private static TaskExecutionSnapshot snapshot(TaskExecutionState state, WorldTaskRequest request, String event) {
		return new TaskExecutionSnapshot(state, request.taskId(), null, "Crafting", event, null, null);
	}

	private static boolean sameTask(WorldTaskRequest left, WorldTaskRequest right) {
		if (left == right) {
			return true;
		}
		if (left == null || right == null) {
			return false;
		}
		return Objects.equals(left.taskId(), right.taskId())
			&& Objects.equals(left.craftRecipe(), right.craftRecipe());
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
		closeOwnedWorkbenchIfSafe();
		appliedTask = null;
		plan = null;
		craftingTablePlan = null;
		phase = CraftPhase.IDLE;
		progressTracker.reset();
		craftingTableProgressTracker.reset();
		waitTicks = 0;
		terminalEventEmitted = false;
		tableTarget = null;
		placedTablePos = null;
		navigationStarted = false;
		openedWorkbenchForTask = false;
		snapshot = TaskExecutionSnapshot.idle();
	}

	private void cancelNavigationIfStarted() {
		if (navigationStarted && baritoneFacade != null && baritoneFacade.isLoaded()) {
			baritoneFacade.cancel();
		}
		navigationStarted = false;
	}

	private enum CraftPhase {
		IDLE,
		CRAFTING_TABLE_INPUTS,
		CRAFTING_TABLE_WAITING_FOR_RESULT,
		CRAFTING_TABLE_WAITING_FOR_TAKE,
		NAVIGATING_TO_TABLE,
		PLACING_TABLE,
		WAITING_FOR_TABLE_PLACED,
		OPENING_TABLE,
		WAITING_FOR_TABLE_SCREEN,
		PLACING_INPUTS,
		WAITING_FOR_RESULT,
		WAITING_FOR_TAKE
	}

	private record CraftingPlan(
		Item outputItem,
		int outputCount,
		int requestedTimes,
		int targetOutputCount,
		CraftingGridKind gridKind,
		java.util.List<CraftingOpportunityResolver.CraftingIngredientPlacement> placements,
		String failureReason
	) {
		private static CraftingPlan failure(String reason) {
			return new CraftingPlan(null, 0, 0, 0, null, java.util.List.of(), reason);
		}
	}

	private record WorkbenchReadiness(boolean ready, Optional<String> failureReason) {
		private static WorkbenchReadiness readyState() {
			return new WorkbenchReadiness(true, Optional.empty());
		}

		private static WorkbenchReadiness notReadyState() {
			return new WorkbenchReadiness(false, Optional.empty());
		}

		private static WorkbenchReadiness failed(String reason) {
			return new WorkbenchReadiness(false, Optional.of(reason));
		}
	}

	private enum CraftAdvanceStatus {
		RUNNING,
		COMPLETED,
		FAILED
	}

	private record CraftAdvanceResult(CraftAdvanceStatus status, String reason) {
		private static CraftAdvanceResult running() {
			return new CraftAdvanceResult(CraftAdvanceStatus.RUNNING, null);
		}

		private static CraftAdvanceResult completed() {
			return new CraftAdvanceResult(CraftAdvanceStatus.COMPLETED, null);
		}

		private static CraftAdvanceResult failed(String reason) {
			return new CraftAdvanceResult(CraftAdvanceStatus.FAILED, reason);
		}
	}

	private record TableTarget(BlockPos tablePos, GoalPosition standPosition) {
	}

	private record CraftingGridSpec(
		CraftingGridKind kind,
		int resultSlot,
		int firstInputSlot,
		int inputSlotCount,
		int inventoryStart,
		int hotbarStart,
		int hotbarEnd
	) {
		private static final CraftingGridSpec PLAYER = new CraftingGridSpec(
			CraftingGridKind.PLAYER_2X2,
			PlayerScreenHandler.CRAFTING_RESULT_ID,
			PlayerScreenHandler.CRAFTING_INPUT_START,
			PlayerScreenHandler.CRAFTING_INPUT_COUNT,
			PlayerScreenHandler.INVENTORY_START,
			PlayerScreenHandler.HOTBAR_START,
			PlayerScreenHandler.HOTBAR_END
		);
		private static final CraftingGridSpec WORKBENCH = new CraftingGridSpec(
			CraftingGridKind.WORKBENCH_3X3,
			CraftingScreenHandler.RESULT_ID,
			1,
			9,
			10,
			37,
			46
		);

		private static CraftingGridSpec forKind(CraftingGridKind kind) {
			return kind == CraftingGridKind.WORKBENCH_3X3 ? WORKBENCH : PLAYER;
		}

		private boolean matches(ScreenHandler handler) {
			if (kind == CraftingGridKind.WORKBENCH_3X3) {
				return handler instanceof CraftingScreenHandler;
			}
			return handler instanceof PlayerScreenHandler;
		}
	}
}
