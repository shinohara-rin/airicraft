package ai.moeru.airicraft.agent.actions;

import ai.moeru.airicraft.agent.job.ActiveJobType;
import ai.moeru.airicraft.agent.tasks.CraftingGridKind;
import ai.moeru.airicraft.agent.tasks.CraftingOpportunity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionGraphPrimitiveMapperTest {
	@Test
	void mapsCraftItemOutputToAvailableRecipe() {
		ActionPlanStep step = primitive("craft_item", Map.of(
			"itemId", "minecraft:bread",
			"quantity", 2
		));

		ActionGraphPrimitiveDispatch dispatch = ActionGraphPrimitiveMapper.map(step, List.of(
			new CraftingOpportunity(
				"wheat_wheat_wheat_to_bread",
				"minecraft:bread",
				1,
				List.of("minecraft:wheat", "minecraft:wheat", "minecraft:wheat"),
				CraftingGridKind.PLAYER_2X2
			)
		));

		assertTrue(dispatch.dispatchable());
		assertEquals(ActiveJobType.CRAFT_RECIPE, dispatch.proposal().type());
		assertEquals("wheat_wheat_wheat_to_bread", dispatch.proposal().craftRecipe().recipeId());
		assertEquals(2, dispatch.proposal().craftRecipe().times());
		assertEquals("craft_item", dispatch.selectedStep().targetId());
	}

	@Test
	void mapsMineBlockToMineGoal() {
		ActionGraphPrimitiveDispatch dispatch = ActionGraphPrimitiveMapper.map(primitive("mine_block", Map.of(
			"blockIds", List.of("minecraft:wheat"),
			"quantity", 3
		)), List.of());

		assertTrue(dispatch.dispatchable());
		assertEquals(ActiveJobType.MINE_BLOCKS, dispatch.proposal().type());
		assertEquals(List.of("minecraft:wheat"), dispatch.proposal().mineSpec().blockIds());
		assertEquals(3, dispatch.proposal().mineSpec().quantity());
	}

	@Test
	void mapsPathfindToNavigateGoal() {
		ActionGraphPrimitiveDispatch dispatch = ActionGraphPrimitiveMapper.map(primitive("pathfind_to", Map.of(
			"x", 1,
			"y", 64,
			"z", -2,
			"exactY", true
		)), List.of());

		assertTrue(dispatch.dispatchable());
		assertEquals(ActiveJobType.NAVIGATE_TO, dispatch.proposal().type());
		assertEquals(1, dispatch.proposal().position().x());
		assertEquals(64, dispatch.proposal().position().y());
		assertEquals(-2, dispatch.proposal().position().z());
		assertTrue(dispatch.proposal().position().exactY());
	}

	@Test
	void rejectsCraftItemWhenNoMatchingRecipeIsAvailable() {
		ActionGraphPrimitiveDispatch dispatch = ActionGraphPrimitiveMapper.map(primitive("craft_item", Map.of(
			"itemId", "minecraft:bread",
			"quantity", 1
		)), List.of());

		assertFalse(dispatch.dispatchable());
		assertEquals("recipe_not_found", dispatch.failureCode());
	}

	private static ActionPlanStep primitive(String primitiveId, Map<String, Object> args) {
		return new ActionPlanStep(
			ActionStepKind.PRIMITIVE,
			"test_action",
			"test_alternative",
			"test_step",
			primitiveId,
			args
		);
	}
}
