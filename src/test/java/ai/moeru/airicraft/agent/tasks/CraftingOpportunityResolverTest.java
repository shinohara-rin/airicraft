package ai.moeru.airicraft.agent.tasks;

import net.minecraft.client.gui.screen.recipebook.RecipeResultCollection;
import net.minecraft.recipe.RecipeFinder;
import org.junit.jupiter.api.Test;

import java.util.List;

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
}
