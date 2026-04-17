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
			"minecraft:oak_planks output=4 ingredients=minecraft:oak_log craftableNow=true",
			new CraftingOpportunity("minecraft:oak_planks", 4, "minecraft:oak_log").compactDescription()
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
	void invalidRequestedItemIdReturnsRecipeNotFound() {
		assertEquals(
			"recipe_not_found",
			CraftingOpportunityResolver.resolve(List.of(RecipeResultCollection.EMPTY), new RecipeFinder(), "not an id", 1).failureReason()
		);
	}
}
