package ai.moeru.airicraft.agent.tasks;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.NetworkRecipeId;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeDisplayEntry;
import net.minecraft.recipe.RecipeFinder;
import net.minecraft.recipe.display.RecipeDisplay;
import net.minecraft.recipe.display.ShapedCraftingRecipeDisplay;
import net.minecraft.recipe.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.recipe.display.SlotDisplay;
import net.minecraft.registry.Registries;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class CraftingOpportunityResolver {
	private static final int PLAYER_GRID_INPUT_COUNT = PlayerScreenHandler.CRAFTING_INPUT_COUNT;
	private static final int WORKBENCH_GRID_INPUT_COUNT = 9;
	static final int MAX_PLACEMENT_VARIANTS_PER_RECIPE = 24;

	private CraftingOpportunityResolver() {
	}

	public static List<CraftingOpportunity> availableCrafts(ClientPlayerEntity player) {
		if (player == null) {
			return List.of();
		}
		Map<String, CraftingOpportunity> opportunities = new LinkedHashMap<>();
		for (ResolvedCraftingOption option : resolvedOptions(player)) {
			opportunities.putIfAbsent(option.opportunity().recipeId(), option.opportunity());
		}
		return List.copyOf(opportunities.values());
	}

	static List<CraftingOpportunity> availableCrafts(List<RecipeResultCollection> collections, RecipeFinder finder) {
		return availableCrafts(collections, finder, Map.of());
	}

	static List<CraftingOpportunity> availableCrafts(List<RecipeResultCollection> collections, RecipeFinder finder, Map<Item, Integer> availableItems) {
		if (collections == null || finder == null) {
			return List.of();
		}
		Map<String, CraftingOpportunity> opportunities = new LinkedHashMap<>();
		for (ResolvedCraftingOption option : resolvedOptions(collections, finder, availableItems)) {
			opportunities.putIfAbsent(option.opportunity().recipeId(), option.opportunity());
		}
		return List.copyOf(opportunities.values());
	}

	static CraftingRecipeResolution resolve(ClientPlayerEntity player, CraftRecipeStepArgs request) {
		if (player == null || request == null) {
			return CraftingRecipeResolution.failure("recipe_not_found");
		}
		return resolve(resolvedOptions(player), request.recipeId(), request.times());
	}

	static CraftingRecipeResolution resolve(List<RecipeResultCollection> collections, RecipeFinder finder, String recipeId, int times) {
		return resolve(collections, finder, Map.of(), recipeId, times);
	}

	static CraftingRecipeResolution resolve(List<RecipeResultCollection> collections, RecipeFinder finder, Map<Item, Integer> availableItems, String recipeId, int times) {
		return resolve(resolvedOptions(collections, finder, availableItems), recipeId, times);
	}

	private static CraftingRecipeResolution resolve(List<ResolvedCraftingOption> options, String recipeId, int times) {
		String requestedRecipeId = normalizeRequestedRecipeId(recipeId);
		if (requestedRecipeId.isBlank() || times <= 0) {
			return CraftingRecipeResolution.failure("recipe_not_found");
		}
		for (ResolvedCraftingOption option : options) {
			if (option.opportunity().recipeId().equals(requestedRecipeId)) {
				return CraftingRecipeResolution.success(
					option.networkRecipeId(),
					option.outputItem(),
					option.opportunity().outputCount(),
					times,
					option.gridKind(),
					option.placements()
				);
			}
		}

		return CraftingRecipeResolution.failure("recipe_not_found");
	}

	static CraftingRecipeResolution resolveCraftingTable(ClientPlayerEntity player) {
		if (player == null) {
			return CraftingRecipeResolution.failure("crafting_table_missing_materials");
		}
		return resolveCraftingTable(player.getRecipeBook().getOrderedResults(), recipeFinder(player), inventoryCounts(player));
	}

	static CraftingRecipeResolution resolveCraftingTable(List<RecipeResultCollection> collections, RecipeFinder finder, Map<Item, Integer> availableItems) {
		for (ResolvedCraftingOption option : resolvedOptions(collections, finder, availableItems)) {
			if (option.gridKind() == CraftingGridKind.PLAYER_2X2 && option.outputItem() == Items.CRAFTING_TABLE) {
				return CraftingRecipeResolution.success(
					option.networkRecipeId(),
					option.outputItem(),
					option.opportunity().outputCount(),
					1,
					option.gridKind(),
					option.placements()
				);
			}
		}
		return CraftingRecipeResolution.failure("crafting_table_missing_materials");
	}

	static boolean fitsPlayerGrid(RecipeDisplay display) {
		return gridKind(display) == CraftingGridKind.PLAYER_2X2;
	}

	static CraftingGridKind gridKind(RecipeDisplay display) {
		if (display instanceof ShapedCraftingRecipeDisplay shaped) {
			return gridKindForShapedRecipe(shaped.width(), shaped.height());
		}
		if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
			return gridKindForIngredientCount(shapeless.ingredients().size());
		}
		return null;
	}

	static CraftingGridKind gridKindForShapedRecipe(int width, int height) {
		if (width <= 2 && height <= 2) {
			return CraftingGridKind.PLAYER_2X2;
		}
		if (width <= 3 && height <= 3) {
			return CraftingGridKind.WORKBENCH_3X3;
		}
		return null;
	}

	static CraftingGridKind gridKindForIngredientCount(int size) {
		if (size <= PLAYER_GRID_INPUT_COUNT) {
			return CraftingGridKind.PLAYER_2X2;
		}
		if (size <= WORKBENCH_GRID_INPUT_COUNT) {
			return CraftingGridKind.WORKBENCH_3X3;
		}
		return null;
	}

	static ItemStack resultStack(RecipeDisplay display) {
		SlotDisplay result = display.result();
		if (result instanceof SlotDisplay.StackSlotDisplay stackDisplay) {
			return stackDisplay.stack();
		}
		if (result instanceof SlotDisplay.ItemSlotDisplay itemDisplay) {
			return itemDisplay.item().value().getDefaultStack();
		}
		return ItemStack.EMPTY;
	}

	private static String normalizeRequestedRecipeId(String recipeId) {
		if (recipeId == null) {
			return "";
		}
		return recipeId.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
	}

	private static RecipeFinder recipeFinder(ClientPlayerEntity player) {
		RecipeFinder finder = new RecipeFinder();
		player.getInventory().populateRecipeFinder(finder);
		return finder;
	}

	private static Map<Item, Integer> inventoryCounts(ClientPlayerEntity player) {
		Map<Item, Integer> counts = new HashMap<>();
		for (int index = 0; index < player.getInventory().size(); index++) {
			ItemStack stack = player.getInventory().getStack(index);
			if (!stack.isEmpty()) {
				counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
			}
		}
		return counts;
	}

	private static List<ResolvedCraftingOption> resolvedOptions(ClientPlayerEntity player) {
		RecipeFinder finder = recipeFinder(player);
		Map<Item, Integer> availableItems = inventoryCounts(player);
		List<ResolvedCraftingOption> options = new ArrayList<>(resolvedOptions(player.getRecipeBook().getOrderedResults(), finder, availableItems));
		options.addAll(resolvedOptions(integratedServerRecipeCollections(), finder, availableItems, true));
		return List.copyOf(options);
	}

	private static List<RecipeResultCollection> integratedServerRecipeCollections() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || !client.isIntegratedServerRunning()) {
			return List.of();
		}
		IntegratedServer server = client.getServer();
		if (server == null) {
			return List.of();
		}
		CompletableFuture<List<RecipeDisplayEntry>> future = new CompletableFuture<>();
		server.executeSync(() -> {
			try {
				List<RecipeEntry<?>> recipes = List.copyOf(server.getRecipeManager().values());
				List<RecipeDisplayEntry> entries = new ArrayList<>();
				for (RecipeEntry<?> recipe : recipes) {
					server.getRecipeManager().forEachRecipeDisplay(recipe.id(), entries::add);
				}
				future.complete(List.copyOf(entries));
			}
			catch (Throwable throwable) {
				future.completeExceptionally(throwable);
			}
		});
		try {
			List<RecipeDisplayEntry> entries = future.get(2L, TimeUnit.SECONDS);
			return entries.isEmpty() ? List.of() : List.of(new RecipeResultCollection(entries));
		}
		catch (Exception exception) {
			return List.of();
		}
	}

	private static List<ResolvedCraftingOption> resolvedOptions(List<RecipeResultCollection> collections, RecipeFinder finder, Map<Item, Integer> availableItems) {
		return resolvedOptions(collections, finder, availableItems, false);
	}

	private static List<ResolvedCraftingOption> resolvedOptions(List<RecipeResultCollection> collections, RecipeFinder finder, Map<Item, Integer> availableItems, boolean manualPlacementOnly) {
		if (collections == null || finder == null) {
			return List.of();
		}
		Map<Item, Integer> safeAvailableItems = availableItems == null ? Map.of() : availableItems;
		List<ResolvedCraftingOption> options = new ArrayList<>();
		for (RecipeResultCollection collection : collections) {
			collection.populateRecipes(finder, display -> gridKind(display) != null);
			for (RecipeDisplayEntry entry : collection.getAllRecipes()) {
				if (!collection.isCraftable(entry.id())) {
					continue;
				}
				ItemStack result = resultStack(entry.display());
				CraftingGridKind gridKind = gridKind(entry.display());
				if (result.isEmpty() || gridKind == null) {
					continue;
				}
				for (List<CraftingIngredientPlacement> placements : concretePlacements(ingredientPlacements(entry, gridKind), safeAvailableItems)) {
					if (placements.isEmpty()) {
						continue;
					}
					String outputItemId = Registries.ITEM.getId(result.getItem()).toString();
					List<String> inputItemIds = placements.stream()
						.map(CraftingIngredientPlacement::itemId)
						.toList();
					CraftingOpportunity opportunity = new CraftingOpportunity(
						recipeId(inputItemIds, outputItemId),
						outputItemId,
						result.getCount(),
						inputItemIds,
						gridKind
					);
					options.add(new ResolvedCraftingOption(manualPlacementOnly ? null : entry.id(), result.getItem(), gridKind, opportunity, placements));
				}
			}
		}
		return List.copyOf(options);
	}

	private static List<IngredientPlacement> ingredientPlacements(RecipeDisplayEntry entry, CraftingGridKind gridKind) {
		if (entry.craftingRequirements().isEmpty()) {
			return List.of();
		}
		List<Ingredient> requirements = entry.craftingRequirements().get();
		if (entry.display() instanceof ShapedCraftingRecipeDisplay shaped) {
			return shapedIngredientPlacements(shaped, gridKind, requirements);
		}
		if (entry.display() instanceof ShapelessCraftingRecipeDisplay) {
			return shapelessIngredientPlacements(gridKind, requirements);
		}
		return List.of();
	}

	private static List<IngredientPlacement> shapedIngredientPlacements(ShapedCraftingRecipeDisplay shaped, CraftingGridKind gridKind, List<Ingredient> requirements) {
		List<IngredientPlacement> placements = new ArrayList<>();
		List<SlotDisplay> slots = shaped.ingredients();
		int requirementIndex = 0;
		int gridWidth = gridWidth(gridKind);
		for (int row = 0; row < shaped.height(); row++) {
			for (int column = 0; column < shaped.width(); column++) {
				int displayIndex = row * shaped.width() + column;
				if (displayIndex >= slots.size() || isEmptySlot(slots.get(displayIndex))) {
					continue;
				}
				if (requirementIndex >= requirements.size()) {
					return List.of();
				}
				Ingredient requirement = requirements.get(requirementIndex++);
				if (!requirement.isEmpty()) {
					placements.add(new IngredientPlacement(row * gridWidth + column, requirement));
				}
			}
		}
		return placements;
	}

	private static List<IngredientPlacement> shapelessIngredientPlacements(CraftingGridKind gridKind, List<Ingredient> requirements) {
		List<IngredientPlacement> placements = new ArrayList<>();
		for (Ingredient requirement : requirements) {
			if (!requirement.isEmpty()) {
				placements.add(new IngredientPlacement(placements.size(), requirement));
			}
		}
		return placements.size() <= gridInputCount(gridKind) ? List.copyOf(placements) : List.of();
	}

	private static boolean isEmptySlot(SlotDisplay display) {
		return display instanceof SlotDisplay.EmptySlotDisplay;
	}

	private static List<List<CraftingIngredientPlacement>> concretePlacements(List<IngredientPlacement> placements, Map<Item, Integer> availableItems) {
		if (placements.isEmpty()) {
			return List.of();
		}
		Map<Item, Integer> safeAvailableItems = availableItems == null ? Map.of() : availableItems;
		List<List<Item>> choicesByPlacement = new ArrayList<>();
		for (IngredientPlacement placement : placements) {
			List<Item> matchingItems = placement.ingredient().getMatchingItems()
				.map(entry -> entry.value())
				.distinct()
				.filter(item -> safeAvailableItems.getOrDefault(item, 0) > 0)
				.sorted(Comparator.comparing(CraftingOpportunityResolver::itemId))
				.toList();
			if (matchingItems.isEmpty()) {
				return List.of();
			}
			choicesByPlacement.add(matchingItems);
		}
		List<List<Item>> itemVariants = boundedCombinations(choicesByPlacement, safeAvailableItems, MAX_PLACEMENT_VARIANTS_PER_RECIPE);
		List<List<CraftingIngredientPlacement>> variants = new ArrayList<>();
		for (List<Item> itemVariant : itemVariants) {
			List<CraftingIngredientPlacement> variant = new ArrayList<>();
			for (int index = 0; index < itemVariant.size(); index++) {
				Item item = itemVariant.get(index);
				variant.add(new CraftingIngredientPlacement(placements.get(index).gridIndex(), item, itemId(item)));
			}
			variants.add(List.copyOf(variant));
		}
		return List.copyOf(variants);
	}

	static <T> List<List<T>> boundedCombinations(List<List<T>> choices, Map<T, Integer> availableItems, int maxVariants) {
		if (choices == null || choices.isEmpty() || maxVariants <= 0) {
			return List.of();
		}
		List<List<T>> variants = new ArrayList<>();
		backtrackBoundedCombinations(choices, availableItems == null ? Map.of() : availableItems, maxVariants, 0, new HashMap<>(), new ArrayList<>(), variants);
		return List.copyOf(variants);
	}

	private static <T> void backtrackBoundedCombinations(
		List<List<T>> choices,
		Map<T, Integer> availableItems,
		int maxVariants,
		int index,
		Map<T, Integer> usedItems,
		List<T> current,
		List<List<T>> variants
	) {
		if (variants.size() >= maxVariants) {
			return;
		}
		if (index >= choices.size()) {
			variants.add(List.copyOf(current));
			return;
		}
		for (T choice : choices.get(index)) {
			if (variants.size() >= maxVariants) {
				break;
			}
			if (availableItems.getOrDefault(choice, 0) <= usedItems.getOrDefault(choice, 0)) {
				continue;
			}
			usedItems.merge(choice, 1, Integer::sum);
			current.add(choice);
			backtrackBoundedCombinations(choices, availableItems, maxVariants, index + 1, usedItems, current, variants);
			current.remove(current.size() - 1);
			int used = usedItems.getOrDefault(choice, 0) - 1;
			if (used <= 0) {
				usedItems.remove(choice);
			}
			else {
				usedItems.put(choice, used);
			}
		}
	}

	static String recipeId(List<String> inputItemIds, String outputItemId) {
		Map<String, Integer> counts = new TreeMap<>();
		for (String inputItemId : inputItemIds) {
			counts.merge(inputItemId, 1, Integer::sum);
		}
		String inputs = counts.entrySet().stream()
			.map(entry -> {
				String segment = CraftingOpportunity.recipeIdSegment(entry.getKey());
				return entry.getValue() == 1 ? segment : segment + "_x" + entry.getValue();
			})
			.filter(segment -> !segment.isBlank())
			.collect(java.util.stream.Collectors.joining("_and_"));
		String output = CraftingOpportunity.recipeIdSegment(outputItemId);
		return normalizeRequestedRecipeId(inputs + "_to_" + output);
	}

	private static String itemId(Item item) {
		Identifier id = Registries.ITEM.getId(item);
		return id == null ? "" : id.toString();
	}

	static int gridInputCount(CraftingGridKind gridKind) {
		return gridKind == CraftingGridKind.WORKBENCH_3X3 ? WORKBENCH_GRID_INPUT_COUNT : PLAYER_GRID_INPUT_COUNT;
	}

	private static int gridWidth(CraftingGridKind gridKind) {
		return gridKind == CraftingGridKind.WORKBENCH_3X3 ? 3 : 2;
	}

	static record CraftingRecipeResolution(
		NetworkRecipeId networkRecipeId,
		Item outputItem,
		int outputCount,
		int requestedTimes,
		CraftingGridKind gridKind,
		List<CraftingIngredientPlacement> placements,
		String failureReason
	) {
		private static CraftingRecipeResolution success(NetworkRecipeId networkRecipeId, Item outputItem, int outputCount, int requestedTimes, CraftingGridKind gridKind, List<CraftingIngredientPlacement> placements) {
			return new CraftingRecipeResolution(networkRecipeId, outputItem, outputCount, requestedTimes, gridKind, List.copyOf(placements), null);
		}

		private static CraftingRecipeResolution failure(String reason) {
			return new CraftingRecipeResolution(null, null, 0, 0, null, List.of(), reason);
		}
	}

	static record CraftingIngredientPlacement(
		int gridIndex,
		Item item,
		String itemId
	) {
		CraftingIngredientPlacement {
			Objects.requireNonNull(item, "item");
			if (gridIndex < 0 || gridIndex >= WORKBENCH_GRID_INPUT_COUNT) {
				throw new IllegalArgumentException("gridIndex outside crafting grid");
			}
			if (itemId == null || itemId.isBlank()) {
				throw new IllegalArgumentException("itemId must not be blank");
			}
		}
	}

	private record IngredientPlacement(int gridIndex, Ingredient ingredient) {
	}

	private record ResolvedCraftingOption(
		NetworkRecipeId networkRecipeId,
		Item outputItem,
		CraftingGridKind gridKind,
		CraftingOpportunity opportunity,
		List<CraftingIngredientPlacement> placements
	) {
	}
}
