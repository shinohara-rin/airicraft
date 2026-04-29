package ai.moeru.airicraft.agent.actions;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionResolverTest {
	private static final ActionResolverContext CONTEXT = new ActionResolverContext(
		"world-a",
		"bot",
		"minecraft:overworld",
		100
	);

	@Test
	void resolvesBreadFromInventoryWheatWithoutActuating() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:wheat"),
			Map.of("count", 3),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = resolver(facts).resolve(ActionGoal.inventoryItem("minecraft:bread", 1));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(10, result.route().cost());
		assertEquals(List.of("craft_item"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		ActionPlanStep craft = result.route().steps().getFirst();
		assertEquals(ActionStepKind.PRIMITIVE, craft.kind());
		assertEquals("craft_bread", craft.stepId());
		assertEquals("minecraft:bread", craft.args().get("itemId"));
		assertEquals(1, craft.args().get("quantity"));
		assertTrace(result.trace(), "route_selected", "make_bread", "craft_from_inventory_wheat");
		assertTrace(result.trace(), "primitive_planned", "make_bread", "craft_from_inventory_wheat");
	}

	@Test
	void resolvesBreadDeficitWhenSomeBreadAlreadyExists() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:bread"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:wheat"),
			Map.of("count", 5),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = resolver(facts).resolve(ActionGoal.inventoryItem("minecraft:bread", 2));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("craft_item"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		ActionPlanStep craft = result.route().steps().getFirst();
		assertEquals(1, craft.args().get("quantity"));
		assertTrace(result.trace(), "route_selected", "make_bread", "craft_from_inventory_wheat");
	}

	@Test
	void resolvesCraftingFromRecipeFactWithoutItemSpecificActionset() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:bread"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:wheat"),
			Map.of("count", 5),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.craftRecipe("world-a", "bot", "wheat_wheat_wheat_to_bread"),
			Map.of(
				"outputItemId", "minecraft:bread",
				"outputCount", 1,
				"inputCounts", Map.of("minecraft:wheat", 3)
			),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = new ActionResolver(ActionsetIndex.empty(), facts, CONTEXT)
			.resolve(ActionGoal.inventoryItem("minecraft:bread", 2));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("craft_item"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		ActionPlanStep craft = result.route().steps().getFirst();
		assertEquals("recipe_provider", craft.actionId());
		assertEquals("wheat_wheat_wheat_to_bread", craft.args().get("recipeId"));
		assertEquals(1, craft.args().get("quantity"));
		assertTrace(result.trace(), "route_selected", "recipe_provider", "wheat_wheat_wheat_to_bread");
	}

	@Test
	void resolvesNestedCraftingRecipesFromLogToSticks() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:oak_log"),
			Map.of("count", 1),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.craftRecipe("world-a", "bot", "oak_log_to_oak_planks"),
			Map.of(
				"outputItemId", "minecraft:oak_planks",
				"outputCount", 4,
				"inputCounts", Map.of("minecraft:oak_log", 1)
			),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));
		facts.upsert(new ActionFact(
			ActionFactIdentity.craftRecipe("world-a", "bot", "oak_planks_x2_to_stick"),
			Map.of(
				"outputItemId", "minecraft:stick",
				"outputCount", 4,
				"inputCounts", Map.of("minecraft:oak_planks", 2)
			),
			ActionFactProvenance.OBSERVED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = new ActionResolver(ActionsetIndex.empty(), facts, CONTEXT)
			.resolve(ActionGoal.inventoryItem("minecraft:stick", 4));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("craft_item", "craft_item"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		assertEquals("oak_log_to_oak_planks", result.route().steps().get(0).args().get("recipeId"));
		assertEquals("oak_planks_x2_to_stick", result.route().steps().get(1).args().get("recipeId"));
	}

	@Test
	void recursivelyExpandsNeedsBeforeCurrentPrimitiveSteps() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.worldCropGroup("world-a", "minecraft:overworld", "farm-1", "minecraft:wheat"),
			Map.of("matureCount", 3, "totalCount", 9),
			ActionFactProvenance.INFERRED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = resolver(facts).resolve(ActionGoal.inventoryItem("minecraft:bread", 1));

		assertTrue(result.resolved(), () -> result.trace().toString());
		assertEquals(List.of("mine_block", "craft_item"), result.route().steps().stream().map(ActionPlanStep::targetId).toList());
		assertEquals(60, result.route().cost());
		ActionPlanStep harvest = result.route().steps().getFirst();
		assertEquals("harvest_wheat", harvest.stepId());
		assertEquals(List.of("minecraft:wheat"), harvest.args().get("blockIds"));
		assertEquals(3, harvest.args().get("quantity"));
		assertTrace(result.trace(), "route_selected", "make_bread", "obtain_wheat_then_craft");
		assertTrace(result.trace(), "route_selected", "obtain_wheat", "harvest_loaded_mature_wheat");
	}

	@Test
	void expectedFactsDoNotSatisfyGuards() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:wheat"),
			Map.of("count", 3),
			ActionFactProvenance.EXPECTED,
			90,
			ActionFact.NEVER_STALE
		));

		ActionResolveResult result = resolver(facts).resolve(ActionGoal.inventoryItem("minecraft:bread", 1));

		assertFalse(result.resolved());
		assertEquals("no_route", result.failureCode());
		assertTrace(result.trace(), "goal_failed", null, null);
	}

	@Test
	void staleFactsDoNotSatisfyGuards() {
		ActionFactStore facts = new ActionFactStore();
		facts.upsert(new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", "minecraft:wheat"),
			Map.of("count", 3),
			ActionFactProvenance.OBSERVED,
			90,
			100
		));

		ActionResolveResult result = resolver(facts).resolve(ActionGoal.inventoryItem("minecraft:bread", 1));

		assertFalse(result.resolved());
		assertEquals("no_route", result.failureCode());
	}

	@Test
	void returnsNoRouteWhenNoActionsetCanSatisfyGoal() {
		ActionResolveResult result = resolver(new ActionFactStore()).resolve(ActionGoal.inventoryItem("minecraft:diamond", 1));

		assertFalse(result.resolved());
		assertEquals("no_route", result.failureCode());
		assertTrue(result.route().steps().isEmpty());
		assertTrace(result.trace(), "goal_started", null, null);
		assertTrace(result.trace(), "goal_failed", null, null);
	}

	private static ActionResolver resolver(ActionFactStore facts) {
		ActionsetLoadResult load = ActionsetLibraryLoader.defaults().load(Path.of("actionsets"));
		assertTrue(load.valid(), () -> load.diagnostics().toString());
		return new ActionResolver(load.index(), facts, CONTEXT);
	}

	private static void assertTrace(List<ActionTraceEvent> trace, String eventType, String actionId, String alternativeId) {
		long matches = trace.stream()
			.filter(event -> eventType.equals(event.eventType()))
			.filter(event -> actionId == null || actionId.equals(event.actionId()))
			.filter(event -> alternativeId == null || alternativeId.equals(event.alternativeId()))
			.count();
		assertTrue(matches > 0, () -> trace.toString());
	}
}
