package ai.moeru.airicraft.agent.tasks;

import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
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

	private CraftingOpportunityResolver() {
	}

	public static List<CraftingOpportunity> availableCrafts(ClientPlayerEntity player) {
		if (player == null) {
			return List.of();
		}
		RecipeFinder finder = recipeFinder(player);
		return availableCrafts(player.getRecipeBook().getOrderedResults(), finder, inventoryCounts(player));
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
					option.placements()
				);
			}
		}

		return CraftingRecipeResolution.failure("recipe_not_found");
	}

	static boolean fitsPlayerGrid(RecipeDisplay display) {
		if (display instanceof ShapedCraftingRecipeDisplay shaped) {
			return shaped.width() <= 2 && shaped.height() <= 2;
		}
		if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
			return shapeless.ingredients().size() <= PLAYER_GRID_INPUT_COUNT;
		}
		return false;
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

	private static List<ResolvedCraftingOption> resolvedOptions(List<RecipeResultCollection> collections, RecipeFinder finder, Map<Item, Integer> availableItems) {
		Map<Item, Integer> safeAvailableItems = availableItems == null ? Map.of() : availableItems;
		List<ResolvedCraftingOption> options = new ArrayList<>();
		for (RecipeResultCollection collection : collections) {
			collection.populateRecipes(finder, CraftingOpportunityResolver::fitsPlayerGrid);
			for (RecipeDisplayEntry entry : collection.getAllRecipes()) {
				if (!collection.isCraftable(entry.id())) {
					continue;
				}
				ItemStack result = resultStack(entry.display());
				if (result.isEmpty() || !fitsPlayerGrid(entry.display())) {
					continue;
				}
				for (List<CraftingIngredientPlacement> placements : concretePlacements(ingredientPlacements(entry), safeAvailableItems)) {
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
						inputItemIds
					);
					options.add(new ResolvedCraftingOption(entry.id(), result.getItem(), opportunity, placements));
				}
			}
		}
		return List.copyOf(options);
	}

	private static List<IngredientPlacement> ingredientPlacements(RecipeDisplayEntry entry) {
		if (entry.craftingRequirements().isEmpty()) {
			return List.of();
		}
		List<Ingredient> requirements = entry.craftingRequirements().get();
		if (entry.display() instanceof ShapedCraftingRecipeDisplay shaped) {
			return shapedIngredientPlacements(shaped, requirements);
		}
		if (entry.display() instanceof ShapelessCraftingRecipeDisplay) {
			return shapelessIngredientPlacements(requirements);
		}
		return List.of();
	}

	private static List<IngredientPlacement> shapedIngredientPlacements(ShapedCraftingRecipeDisplay shaped, List<Ingredient> requirements) {
		List<IngredientPlacement> placements = new ArrayList<>();
		List<SlotDisplay> slots = shaped.ingredients();
		int requirementIndex = 0;
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
					placements.add(new IngredientPlacement(row * 2 + column, requirement));
				}
			}
		}
		return placements;
	}

	private static List<IngredientPlacement> shapelessIngredientPlacements(List<Ingredient> requirements) {
		List<IngredientPlacement> placements = new ArrayList<>();
		for (Ingredient requirement : requirements) {
			if (!requirement.isEmpty()) {
				placements.add(new IngredientPlacement(placements.size(), requirement));
			}
		}
		return placements.size() <= PLAYER_GRID_INPUT_COUNT ? List.copyOf(placements) : List.of();
	}

	private static boolean isEmptySlot(SlotDisplay display) {
		return display instanceof SlotDisplay.EmptySlotDisplay;
	}

	private static List<List<CraftingIngredientPlacement>> concretePlacements(List<IngredientPlacement> placements, Map<Item, Integer> availableItems) {
		if (placements.isEmpty()) {
			return List.of();
		}
		List<List<CraftingIngredientPlacement>> variants = new ArrayList<>();
		backtrackConcretePlacements(placements, availableItems, 0, new HashMap<>(), new ArrayList<>(), variants);
		return variants;
	}

	private static void backtrackConcretePlacements(
		List<IngredientPlacement> placements,
		Map<Item, Integer> availableItems,
		int index,
		Map<Item, Integer> usedItems,
		List<CraftingIngredientPlacement> current,
		List<List<CraftingIngredientPlacement>> variants
	) {
		if (index >= placements.size()) {
			variants.add(List.copyOf(current));
			return;
		}
		IngredientPlacement placement = placements.get(index);
		List<Item> matchingItems = placement.ingredient().getMatchingItems()
			.map(entry -> entry.value())
			.distinct()
			.filter(item -> availableItems.getOrDefault(item, 0) > usedItems.getOrDefault(item, 0))
			.sorted(Comparator.comparing(CraftingOpportunityResolver::itemId))
			.toList();
		for (Item item : matchingItems) {
			usedItems.merge(item, 1, Integer::sum);
			current.add(new CraftingIngredientPlacement(placement.gridIndex(), item, itemId(item)));
			backtrackConcretePlacements(placements, availableItems, index + 1, usedItems, current, variants);
			current.remove(current.size() - 1);
			int used = usedItems.getOrDefault(item, 0) - 1;
			if (used <= 0) {
				usedItems.remove(item);
			}
			else {
				usedItems.put(item, used);
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

	static record CraftingRecipeResolution(
		NetworkRecipeId networkRecipeId,
		Item outputItem,
		int outputCount,
		int requestedTimes,
		List<CraftingIngredientPlacement> placements,
		String failureReason
	) {
		private static CraftingRecipeResolution success(NetworkRecipeId networkRecipeId, Item outputItem, int outputCount, int requestedTimes, List<CraftingIngredientPlacement> placements) {
			return new CraftingRecipeResolution(networkRecipeId, outputItem, outputCount, requestedTimes, List.copyOf(placements), null);
		}

		private static CraftingRecipeResolution failure(String reason) {
			return new CraftingRecipeResolution(null, null, 0, 0, List.of(), reason);
		}
	}

	static record CraftingIngredientPlacement(
		int gridIndex,
		Item item,
		String itemId
	) {
		CraftingIngredientPlacement {
			Objects.requireNonNull(item, "item");
			if (gridIndex < 0 || gridIndex >= PLAYER_GRID_INPUT_COUNT) {
				throw new IllegalArgumentException("gridIndex outside player crafting grid");
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
		CraftingOpportunity opportunity,
		List<CraftingIngredientPlacement> placements
	) {
	}
}
