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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class CraftingOpportunityResolver {
	private static final int PLAYER_GRID_INPUT_COUNT = PlayerScreenHandler.CRAFTING_INPUT_COUNT;

	private CraftingOpportunityResolver() {
	}

	public static List<CraftingOpportunity> availableCrafts(ClientPlayerEntity player) {
		if (player == null) {
			return List.of();
		}
		RecipeFinder finder = recipeFinder(player);
		return availableCrafts(player.getRecipeBook().getOrderedResults(), finder);
	}

	static List<CraftingOpportunity> availableCrafts(List<RecipeResultCollection> collections, RecipeFinder finder) {
		if (collections == null || finder == null) {
			return List.of();
		}
		Map<String, CraftingOpportunity> opportunities = new LinkedHashMap<>();
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
				String itemId = Registries.ITEM.getId(result.getItem()).toString();
				opportunities.putIfAbsent(
					itemId,
					new CraftingOpportunity(itemId, result.getCount(), ingredientSummary(entry))
				);
			}
		}
		return List.copyOf(opportunities.values());
	}

	static CraftingRecipeResolution resolve(ClientPlayerEntity player, CraftRecipeStepArgs request) {
		if (player == null || request == null) {
			return CraftingRecipeResolution.failure("recipe_not_found");
		}
		return resolve(player.getRecipeBook().getOrderedResults(), recipeFinder(player), request.itemId(), request.quantity());
	}

	static CraftingRecipeResolution resolve(List<RecipeResultCollection> collections, RecipeFinder finder, String itemId, int quantity) {
		Identifier requestedId = Identifier.tryParse(itemId);
		if (requestedId == null || collections == null || finder == null) {
			return CraftingRecipeResolution.failure("recipe_not_found");
		}

		boolean matchedUnsupported = false;
		boolean matchedMissingIngredients = false;
		for (RecipeResultCollection collection : collections) {
			collection.populateRecipes(finder, CraftingOpportunityResolver::fitsPlayerGrid);
			for (RecipeDisplayEntry entry : collection.getAllRecipes()) {
				ItemStack result = resultStack(entry.display());
				if (result.isEmpty() || !Objects.equals(Registries.ITEM.getId(result.getItem()), requestedId)) {
					continue;
				}
				if (!fitsPlayerGrid(entry.display())) {
					matchedUnsupported = true;
					continue;
				}
				if (!collection.isCraftable(entry.id())) {
					matchedMissingIngredients = true;
					continue;
				}
				return CraftingRecipeResolution.success(entry.id(), result.getItem(), result.getCount(), quantity);
			}
		}

		if (matchedMissingIngredients) {
			return CraftingRecipeResolution.failure("missing_ingredients");
		}
		if (matchedUnsupported) {
			return CraftingRecipeResolution.failure("crafting_table_not_supported");
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

	private static RecipeFinder recipeFinder(ClientPlayerEntity player) {
		RecipeFinder finder = new RecipeFinder();
		player.getInventory().populateRecipeFinder(finder);
		return finder;
	}

	private static String ingredientSummary(RecipeDisplayEntry entry) {
		if (entry.craftingRequirements().isEmpty()) {
			return "";
		}
		return entry.craftingRequirements().get().stream()
			.map(CraftingOpportunityResolver::describeIngredient)
			.collect(Collectors.joining(" + "));
	}

	private static String describeIngredient(Ingredient ingredient) {
		String options = ingredient.getMatchingItems()
			.limit(3)
			.map(item -> Registries.ITEM.getId(item.value()).toString())
			.collect(Collectors.joining("|"));
		return options.isBlank() ? "unknown" : options;
	}

	static record CraftingRecipeResolution(
		NetworkRecipeId networkRecipeId,
		Item outputItem,
		int outputCount,
		int requestedQuantity,
		String failureReason
	) {
		private static CraftingRecipeResolution success(NetworkRecipeId networkRecipeId, Item outputItem, int outputCount, int requestedQuantity) {
			return new CraftingRecipeResolution(networkRecipeId, outputItem, outputCount, requestedQuantity, null);
		}

		private static CraftingRecipeResolution failure(String reason) {
			return new CraftingRecipeResolution(null, null, 0, 0, reason);
		}
	}
}
