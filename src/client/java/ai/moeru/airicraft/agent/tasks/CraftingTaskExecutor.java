package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.NetworkRecipeId;
import net.minecraft.recipe.RecipeDisplayEntry;
import net.minecraft.recipe.RecipeFinder;
import net.minecraft.recipe.ServerRecipeManager;
import net.minecraft.recipe.display.RecipeDisplay;
import net.minecraft.recipe.display.ShapedCraftingRecipeDisplay;
import net.minecraft.recipe.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.recipe.display.SlotDisplay;
import net.minecraft.registry.Registries;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class CraftingTaskExecutor implements WorldTaskExecutor {
	private static final int RESULT_SLOT = PlayerScreenHandler.CRAFTING_RESULT_ID;
	private static final int FIRST_INPUT_SLOT = PlayerScreenHandler.CRAFTING_INPUT_START;
	private static final int INPUT_SLOT_COUNT = PlayerScreenHandler.CRAFTING_INPUT_COUNT;
	private static final int WAIT_TIMEOUT_TICKS = 20;

	private final Supplier<MinecraftClient> clientSupplier;

	private WorldTaskRequest appliedTask;
	private CraftingPlan plan;
	private CraftPhase phase = CraftPhase.IDLE;
	private final CraftingProgressTracker progressTracker = new CraftingProgressTracker();
	private int waitTicks;
	private boolean terminalEventEmitted;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	public CraftingTaskExecutor() {
		this(MinecraftClient::getInstance);
	}

	CraftingTaskExecutor(Supplier<MinecraftClient> clientSupplier) {
		this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
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

		if (progressTracker.targetReached(plan.requestedQuantity())) {
			return complete(request);
		}

		Optional<String> readinessFailure = readinessFailure(client, player, phase == CraftPhase.IDLE);
		if (readinessFailure.isPresent()) {
			return fail(request, readinessFailure.get());
		}

		ScreenHandler handler = player.currentScreenHandler;
		if (phase == CraftPhase.IDLE) {
			client.interactionManager.clickRecipe(handler.syncId, plan.networkRecipeId(), false);
			phase = CraftPhase.WAITING_FOR_RESULT;
			waitTicks = 0;
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "crafting_recipe_selected");
			return Optional.empty();
		}

		if (phase == CraftPhase.WAITING_FOR_RESULT) {
			ItemStack resultStack = handler.getSlot(RESULT_SLOT).getStack();
			if (!resultStack.isEmpty() && resultStack.isOf(plan.outputItem())) {
				progressTracker.beginTake(inventoryCount(player, plan.outputItem()), resultStack.getCount());
				client.interactionManager.clickSlot(handler.syncId, RESULT_SLOT, 0, SlotActionType.QUICK_MOVE, player);
				phase = CraftPhase.WAITING_FOR_TAKE;
				waitTicks = 0;
				snapshot = snapshot(TaskExecutionState.RUNNING, request, "crafting_result_taken");
				return Optional.empty();
			}
			return waitOrFail(request, "crafting_busy");
		}

		if (phase == CraftPhase.WAITING_FOR_TAKE) {
			if (progressTracker.finishTakeIfInventoryIncreased(inventoryCount(player, plan.outputItem()))) {
				phase = CraftPhase.IDLE;
				waitTicks = 0;
				if (progressTracker.targetReached(plan.requestedQuantity())) {
					return complete(request);
				}
				snapshot = snapshot(TaskExecutionState.RUNNING, request, "crafting_next_batch");
				return Optional.empty();
			}
			return waitOrFail(request, "crafting_busy");
		}

		snapshot = snapshot(TaskExecutionState.RUNNING, request, "crafting");
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> waitOrFail(WorldTaskRequest request, String reason) {
		waitTicks++;
		if (waitTicks > WAIT_TIMEOUT_TICKS) {
			return fail(request, reason);
		}
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "crafting_wait");
		return Optional.empty();
	}

	private Optional<CraftingPlan> resolvePlan(ClientPlayerEntity player, CraftRecipeStepArgs request) {
		Identifier requestedId = Identifier.tryParse(request.recipeId());
		if (requestedId == null) {
			return Optional.of(CraftingPlan.failure("recipe_not_found"));
		}

		RecipeFinder finder = new RecipeFinder();
		player.getInventory().populateRecipeFinder(finder);

		boolean matchedUnsupported = false;
		boolean matchedMissingIngredients = false;
		for (RecipeResultCollection collection : player.getRecipeBook().getOrderedResults()) {
			for (RecipeDisplayEntry entry : collection.getAllRecipes()) {
				RecipeDisplay display = entry.display();
				ItemStack result = resultStack(display);
				if (result.isEmpty() || !matchesRequestedRecipe(player, entry, result, requestedId)) {
					continue;
				}
				if (!fitsPlayerGrid(display)) {
					matchedUnsupported = true;
					continue;
				}
				if (!entry.isCraftable(finder)) {
					matchedMissingIngredients = true;
					continue;
				}
				return Optional.of(new CraftingPlan(entry.id(), result.getItem(), result.getCount(), request.quantity(), null));
			}
		}

		if (matchedUnsupported) {
			return Optional.of(CraftingPlan.failure("crafting_table_not_supported"));
		}
		if (matchedMissingIngredients) {
			return Optional.of(CraftingPlan.failure("missing_ingredients"));
		}
		return Optional.empty();
	}

	private static boolean matchesRequestedRecipe(
		ClientPlayerEntity player,
		RecipeDisplayEntry entry,
		ItemStack result,
		Identifier requestedId
	) {
		if (Objects.equals(Registries.ITEM.getId(result.getItem()), requestedId)) {
			return true;
		}
		return exactRecipeId(player, entry.id())
			.map(requestedId::equals)
			.orElse(false);
	}

	private static Optional<Identifier> exactRecipeId(ClientPlayerEntity player, NetworkRecipeId recipeId) {
		if (player.networkHandler == null) {
			return Optional.empty();
		}
		if (!(player.networkHandler.getRecipeManager() instanceof ServerRecipeManager recipeManager)) {
			return Optional.empty();
		}
		try {
			return Optional.of(recipeManager.get(recipeId).parent().id().getValue());
		}
		catch (RuntimeException exception) {
			return Optional.empty();
		}
	}

	private static boolean fitsPlayerGrid(RecipeDisplay display) {
		if (display instanceof ShapedCraftingRecipeDisplay shaped) {
			return shaped.width() <= 2 && shaped.height() <= 2;
		}
		if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
			return shapeless.ingredients().size() <= INPUT_SLOT_COUNT;
		}
		return false;
	}

	private static ItemStack resultStack(RecipeDisplay display) {
		SlotDisplay result = display.result();
		if (result instanceof SlotDisplay.StackSlotDisplay stackDisplay) {
			return stackDisplay.stack();
		}
		if (result instanceof SlotDisplay.ItemSlotDisplay itemDisplay) {
			return itemDisplay.item().value().getDefaultStack();
		}
		return ItemStack.EMPTY;
	}

	private static Optional<String> readinessFailure(MinecraftClient client, ClientPlayerEntity player, boolean requireEmptyGrid) {
		if (player.currentScreenHandler != player.playerScreenHandler) {
			return Optional.of("crafting_busy");
		}
		if (client.currentScreen != null && !(client.currentScreen instanceof InventoryScreen)) {
			return Optional.of("crafting_busy");
		}
		ScreenHandler handler = player.currentScreenHandler;
		if (!handler.getCursorStack().isEmpty()) {
			return Optional.of("crafting_grid_occupied");
		}
		if (!requireEmptyGrid) {
			return Optional.empty();
		}
		for (int index = FIRST_INPUT_SLOT; index < FIRST_INPUT_SLOT + INPUT_SLOT_COUNT; index++) {
			if (!handler.getSlot(index).getStack().isEmpty()) {
				return Optional.of("crafting_grid_occupied");
			}
		}
		return Optional.empty();
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
		snapshot = snapshot(TaskExecutionState.COMPLETED, request, "crafted");
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.COMPLETED, "crafted", null));
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
		appliedTask = null;
		plan = null;
		phase = CraftPhase.IDLE;
		progressTracker.reset();
		waitTicks = 0;
		terminalEventEmitted = false;
		snapshot = TaskExecutionSnapshot.idle();
	}

	private enum CraftPhase {
		IDLE,
		WAITING_FOR_RESULT,
		WAITING_FOR_TAKE
	}

	private record CraftingPlan(
		NetworkRecipeId networkRecipeId,
		Item outputItem,
		int outputCount,
		int requestedQuantity,
		String failureReason
	) {
		private static CraftingPlan failure(String reason) {
			return new CraftingPlan(null, null, 0, 0, reason);
		}
	}
}
