package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.integration.rei.ReiRecipeSearchBridge;
import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.NetworkRecipeId;
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

public final class CraftingOpportunityResolver {
	private static final int PLAYER_GRID_INPUT_COUNT = PlayerScreenHandler.CRAFTING_INPUT_COUNT;
	private static final int WORKBENCH_GRID_INPUT_COUNT = 9;
	private static final int MAX_CRAFTABLE_CLOSURE_PASSES = 8;
	static final int MAX_PLACEMENT_VARIANTS_PER_RECIPE = 24;

	private CraftingOpportunityResolver() {
	}

	public static List<CraftingOpportunity> availableCrafts(ClientPlayerEntity player) {
		if (player == null) {
			return List.of();
		}
		RecipeFinder finder = recipeFinder(player);
		return availableCrafts(player.getRecipeBook().getOrderedResults(), finder, inventoryCounts(player));
	}

	public static List<CraftingOpportunity> craftableClosure(ClientPlayerEntity player) {
		if (player == null) {
			return List.of();
		}
		Map<Item, Integer> inventory = inventoryCounts(player);
		List<CraftingOpportunity> recipeKnowledge = new ArrayList<>(knownRecipeBookOpportunities(player.getRecipeBook().getOrderedResults()));
		recipeKnowledge.addAll(ReiRecipeSearchBridge.backend().craftingOpportunities());
		return craftableClosureFromKnownRecipes(recipeKnowledge, itemIdCounts(inventory));
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

	static List<CraftingOpportunity> craftableClosure(List<RecipeResultCollection> collections, Map<Item, Integer> initialItems) {
		if (collections == null || collections.isEmpty()) {
			return List.of();
		}
		return craftableClosureFromKnownRecipes(knownRecipeBookOpportunities(collections), itemIdCounts(initialItems));
	}

	static List<CraftingOpportunity> craftableClosureFromKnownRecipes(
		List<CraftingOpportunity> recipeKnowledge,
		Map<String, Integer> initialItems
	) {
		if (recipeKnowledge == null || recipeKnowledge.isEmpty()) {
			return List.of();
		}
		Map<String, Integer> virtualItems = positiveStringCounts(initialItems);
		if (virtualItems.isEmpty()) {
			return List.of();
		}
		Map<String, CraftingOpportunity> opportunities = new LinkedHashMap<>();
		Map<String, Integer> appliedRecipeRuns = new HashMap<>();
		for (int pass = 0; pass < MAX_CRAFTABLE_CLOSURE_PASSES; pass++) {
			boolean changed = false;
			Map<String, Integer> nextItems = new HashMap<>(virtualItems);
			List<CraftingOpportunity> newlyReachable = new ArrayList<>();
			for (CraftingOpportunity opportunity : recipeKnowledge) {
				int craftRuns = craftableRunsForItemIds(opportunity, virtualItems);
				int newCraftRuns = craftRuns - appliedRecipeRuns.getOrDefault(opportunity.recipeId(), 0);
				if (newCraftRuns <= 0) {
					continue;
				}
				if (!opportunities.containsKey(opportunity.recipeId())) {
					newlyReachable.add(opportunity);
					changed = true;
				}
				appliedRecipeRuns.put(opportunity.recipeId(), craftRuns);
				int reachableOutputCount = nextItems.getOrDefault(opportunity.outputItemId(), 0) + opportunity.outputCount() * newCraftRuns;
				int previousOutputCount = nextItems.getOrDefault(opportunity.outputItemId(), 0);
				if (reachableOutputCount > previousOutputCount) {
					nextItems.put(opportunity.outputItemId(), reachableOutputCount);
					changed = true;
				}
			}
			if (!changed) {
				break;
			}
			for (CraftingOpportunity opportunity : newlyReachable) {
				opportunities.putIfAbsent(opportunity.recipeId(), opportunity);
			}
			virtualItems = nextItems;
		}
		return List.copyOf(opportunities.values());
	}

	static CraftingRecipeResolution resolve(ClientPlayerEntity player, CraftRecipeStepArgs request) {
		if (player == null || request == null) {
			return CraftingRecipeResolution.failure("recipe_not_found");
		}
		return resolve(player.getRecipeBook().getOrderedResults(), recipeFinder(player), inventoryCounts(player), request.recipeId(), request.times());
	}

	static CraftingRecipeResolution resolve(List<RecipeResultCollection> collections, RecipeFinder finder, String recipeId, int times) {
		return resolve(collections, finder, Map.of(), recipeId, times);
	}

	static CraftingRecipeResolution resolve(List<RecipeResultCollection> collections, RecipeFinder finder, Map<Item, Integer> availableItems, String recipeId, int times) {
		String requestedRecipeId = normalizeRequestedRecipeId(recipeId);
		if (requestedRecipeId.isBlank() || times <= 0 || collections == null || finder == null) {
			return CraftingRecipeResolution.failure("recipe_not_found");
		}
		for (ResolvedCraftingOption option : resolvedOptions(collections, finder, availableItems)) {
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

	public static CraftingGridKind gridKindForShapedRecipe(int width, int height) {
		if (width <= 2 && height <= 2) {
			return CraftingGridKind.PLAYER_2X2;
		}
		if (width <= 3 && height <= 3) {
			return CraftingGridKind.WORKBENCH_3X3;
		}
		return null;
	}

	public static CraftingGridKind gridKindForIngredientCount(int size) {
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

	private static Map<Item, Integer> positiveItemCounts(Map<Item, Integer> items) {
		if (items == null || items.isEmpty()) {
			return new HashMap<>();
		}
		Map<Item, Integer> counts = new HashMap<>();
		for (Map.Entry<Item, Integer> entry : items.entrySet()) {
			if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0) {
				counts.put(entry.getKey(), entry.getValue());
			}
		}
		return counts;
	}

	private static int craftableRunsForItemIds(CraftingOpportunity opportunity, Map<String, Integer> availableItems) {
		Map<String, Integer> required = new HashMap<>();
		for (String inputItemId : opportunity.inputItemIds()) {
			required.merge(inputItemId, 1, Integer::sum);
		}
		return craftableRuns(required, availableItems);
	}

	private static int craftableRuns(Map<String, Integer> required, Map<String, Integer> available) {
		int runs = Integer.MAX_VALUE;
		for (Map.Entry<String, Integer> entry : required.entrySet()) {
			runs = Math.min(runs, available.getOrDefault(entry.getKey(), 0) / entry.getValue());
		}
		return runs == Integer.MAX_VALUE ? 0 : runs;
	}

	private static Map<String, Integer> positiveStringCounts(Map<String, Integer> items) {
		if (items == null || items.isEmpty()) {
			return new HashMap<>();
		}
		Map<String, Integer> counts = new HashMap<>();
		for (Map.Entry<String, Integer> entry : items.entrySet()) {
			if (entry.getKey() != null && !entry.getKey().isBlank() && entry.getValue() != null && entry.getValue() > 0) {
				counts.put(entry.getKey(), entry.getValue());
			}
		}
		return counts;
	}

	private static Map<String, Integer> itemIdCounts(Map<Item, Integer> items) {
		Map<String, Integer> counts = new HashMap<>();
		for (Map.Entry<Item, Integer> entry : positiveItemCounts(items).entrySet()) {
			String itemId = itemId(entry.getKey());
			if (!itemId.isBlank()) {
				counts.merge(itemId, entry.getValue(), Integer::sum);
			}
		}
		return counts;
	}

	private static List<CraftingOpportunity> knownRecipeBookOpportunities(List<RecipeResultCollection> collections) {
		if (collections == null || collections.isEmpty()) {
			return List.of();
		}
		Map<String, CraftingOpportunity> opportunities = new LinkedHashMap<>();
		for (RecipeResultCollection collection : collections) {
			for (RecipeDisplayEntry entry : collection.getAllRecipes()) {
				for (CraftingOpportunity opportunity : opportunitiesForRecipeDisplay(entry)) {
					opportunities.putIfAbsent(opportunity.recipeId(), opportunity);
				}
			}
		}
		return List.copyOf(opportunities.values());
	}

	private static List<CraftingOpportunity> opportunitiesForRecipeDisplay(RecipeDisplayEntry entry) {
		if (entry == null) {
			return List.of();
		}
		ItemStack result = resultStack(entry.display());
		CraftingGridKind gridKind = gridKind(entry.display());
		if (result.isEmpty() || gridKind == null) {
			return List.of();
		}
		List<IngredientPlacement> placements = ingredientPlacements(entry, gridKind);
		if (placements.isEmpty()) {
			return List.of();
		}
		List<List<String>> inputChoices = new ArrayList<>();
		for (IngredientPlacement placement : placements) {
			List<String> matchingItemIds = placement.ingredient().getMatchingItems()
				.map(entryRef -> itemId(entryRef.value()))
				.distinct()
				.filter(itemId -> !itemId.isBlank())
				.sorted()
				.toList();
			if (matchingItemIds.isEmpty()) {
				return List.of();
			}
			inputChoices.add(matchingItemIds);
		}

		String outputItemId = itemId(result.getItem());
		if (outputItemId.isBlank()) {
			return List.of();
		}
		Map<String, CraftingOpportunity> opportunities = new LinkedHashMap<>();
		for (List<String> inputItemIds : boundedRecipeKnowledgeVariants(inputChoices)) {
			CraftingOpportunity opportunity = new CraftingOpportunity(
				recipeId(inputItemIds, outputItemId),
				outputItemId,
				result.getCount(),
				inputItemIds,
				gridKind
			);
			opportunities.putIfAbsent(opportunity.recipeId(), opportunity);
		}
		return List.copyOf(opportunities.values());
	}

	private static List<List<String>> boundedRecipeKnowledgeVariants(List<List<String>> choices) {
		Map<String, Integer> availableForVariantExpansion = new HashMap<>();
		for (List<String> choiceSet : choices) {
			for (String choice : choiceSet) {
				availableForVariantExpansion.put(choice, choices.size());
			}
		}
		return boundedCombinations(choices, availableForVariantExpansion, MAX_PLACEMENT_VARIANTS_PER_RECIPE);
	}

	private static List<ResolvedCraftingOption> resolvedOptions(List<RecipeResultCollection> collections, RecipeFinder finder, Map<Item, Integer> availableItems) {
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
					options.add(new ResolvedCraftingOption(entry.id(), result.getItem(), gridKind, opportunity, placements));
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

	public static <T> List<List<T>> boundedCombinations(List<List<T>> choices, Map<T, Integer> availableItems, int maxVariants) {
		if (choices == null || choices.isEmpty() || maxVariants <= 0) {
			return List.of();
		}
		List<List<T>> variants = new ArrayList<>();
		Map<T, Integer> safeAvailableItems = availableItems == null ? Map.of() : availableItems;
		addHomogeneousCombinations(choices, safeAvailableItems, maxVariants, variants);
		backtrackBoundedCombinations(choices, safeAvailableItems, maxVariants, 0, new HashMap<>(), new ArrayList<>(), variants);
		return List.copyOf(variants);
	}

	private static <T> void addHomogeneousCombinations(
		List<List<T>> choices,
		Map<T, Integer> availableItems,
		int maxVariants,
		List<List<T>> variants
	) {
		for (T choice : choices.getFirst()) {
			if (variants.size() >= maxVariants) {
				return;
			}
			List<T> variant = new ArrayList<>();
			boolean presentInEverySlot = true;
			for (List<T> slotChoices : choices) {
				if (!slotChoices.contains(choice)) {
					presentInEverySlot = false;
					break;
				}
				variant.add(choice);
			}
			if (!presentInEverySlot || availableItems.getOrDefault(choice, 0) < variant.size()) {
				continue;
			}
			addVariantIfAbsent(variant, maxVariants, variants);
		}
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
			addVariantIfAbsent(current, maxVariants, variants);
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

	private static <T> void addVariantIfAbsent(List<T> variant, int maxVariants, List<List<T>> variants) {
		if (variants.size() >= maxVariants) {
			return;
		}
		List<T> copy = List.copyOf(variant);
		if (!variants.contains(copy)) {
			variants.add(copy);
		}
	}

	public static String recipeId(List<String> inputItemIds, String outputItemId) {
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
