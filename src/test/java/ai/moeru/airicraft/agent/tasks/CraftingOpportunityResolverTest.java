package ai.moeru.airicraft.agent.tasks;

import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.recipe.RecipeFinder;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CraftingOpportunityResolverTest {
	@Test
	void compactDescriptionIncludesExactOutputAndCraftableFlag() {
		assertEquals(
			"[From {1*oak_log} to 4*oak_planks]: oak_log_to_oak_planks",
			new CraftingOpportunity("oak_log_to_oak_planks", "minecraft:oak_planks", 4, List.of("minecraft:oak_log")).compactDescription()
		);
	}

	@Test
	void emptyRecipeBookHasNoAvailableCrafts() {
		assertEquals(
			List.of(),
			CraftingOpportunityResolver.availableCrafts(List.of(RecipeResultCollection.EMPTY), new RecipeFinder())
		);
	}

	@Test
	void recipeIdMapsConcreteInputOutputPair() {
		assertEquals(
			List.of("birch_log_to_birch_planks", "birch_wood_to_birch_planks"),
			List.of(
				CraftingOpportunityResolver.recipeId(List.of("minecraft:birch_log"), "minecraft:birch_planks"),
				CraftingOpportunityResolver.recipeId(List.of("minecraft:birch_wood"), "minecraft:birch_planks")
			)
		);
	}

	@Test
	void recipeIdUsesTimesIndependentOfOutputCount() {
		assertEquals("oak_planks_x2_to_stick", CraftingOpportunityResolver.recipeId(List.of("minecraft:oak_planks", "minecraft:oak_planks"), "minecraft:stick"));
	}

	@Test
	void recipeIdCanonicalizesShapelessInputOrder() {
		assertEquals(
			CraftingOpportunityResolver.recipeId(List.of("minecraft:birch_planks", "minecraft:oak_planks"), "minecraft:stick"),
			CraftingOpportunityResolver.recipeId(List.of("minecraft:oak_planks", "minecraft:birch_planks"), "minecraft:stick")
		);
	}

	@Test
	void invalidRequestedItemIdReturnsRecipeNotFound() {
		assertEquals(
			"recipe_not_found",
			CraftingOpportunityResolver.resolve(List.of(RecipeResultCollection.EMPTY), new RecipeFinder(), "not an id", 1).failureReason()
		);
	}

	@Test
	void gridKindDistinguishesPlayerAndWorkbenchRecipes() {
		assertEquals(
			CraftingGridKind.PLAYER_2X2,
			CraftingOpportunityResolver.gridKindForShapedRecipe(2, 2)
		);
		assertEquals(
			CraftingGridKind.WORKBENCH_3X3,
			CraftingOpportunityResolver.gridKindForShapedRecipe(3, 3)
		);
		assertEquals(
			CraftingGridKind.PLAYER_2X2,
			CraftingOpportunityResolver.gridKindForIngredientCount(4)
		);
		assertEquals(
			CraftingGridKind.WORKBENCH_3X3,
			CraftingOpportunityResolver.gridKindForIngredientCount(9)
		);
	}

	@Test
	void boundedCombinationsStopsAtRecipeVariantLimit() {
		List<List<String>> choices = Collections.nCopies(9, List.of("acacia", "birch", "cherry", "jungle", "oak"));
		Map<String, Integer> availableItems = Map.of(
			"acacia", 9,
			"birch", 9,
			"cherry", 9,
			"jungle", 9,
			"oak", 9
		);

		assertEquals(
			CraftingOpportunityResolver.MAX_PLACEMENT_VARIANTS_PER_RECIPE,
			CraftingOpportunityResolver.boundedCombinations(
				choices,
				availableItems,
				CraftingOpportunityResolver.MAX_PLACEMENT_VARIANTS_PER_RECIPE
			).size()
		);
	}
}
