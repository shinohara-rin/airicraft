package ai.moeru.airicraft.agent.actions;

import ai.moeru.airicraft.agent.tasks.CraftingOpportunity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionGraphDebugServiceTest {
	@Test
	void inspectListsPrimitiveMetadataAndMethodProviders() {
		ActionGraphDebugService service = new ActionGraphDebugService();

		Map<String, Object> payload = service.inspectActionGraph();

		assertEquals(true, payload.get("available"));
		assertTrue((Integer) payload.get("primitiveCount") >= 10);
		assertEquals(4, payload.get("domainProviderCount"));
		assertTrue(list(payload.get("primitives")).stream()
			.anyMatch(primitive -> "craft_item".equals(map(primitive).get("id"))
				&& "WorldTaskRequest.CRAFT_RECIPE".equals(map(primitive).get("executorBinding"))));
		assertTrue(list(payload.get("domainProviders")).stream()
			.anyMatch(provider -> "recipe_provider".equals(map(provider).get("id"))));
	}

	@Test
	void resolvesInventoryItemGoalFromAssumedInventoryFacts() {
		ActionGraphDebugService service = new ActionGraphDebugService();

		Map<String, Object> payload = service.resolveInventoryItem(new ActionGraphResolveRequest(
			"minecraft:bread",
			1,
			Map.of("minecraft:wheat", 3),
			Map.of(),
			List.of(new CraftingOpportunity("wheat_x3_to_bread", "minecraft:bread", 1, List.of("minecraft:wheat", "minecraft:wheat", "minecraft:wheat"))),
			new ActionResolverContext("world-a", "bot", "minecraft:overworld", 100)
		));

		assertEquals(true, payload.get("available"));
		assertEquals(true, payload.get("resolved"));
		assertEquals(0, map(payload.get("route")).get("cost"));
		assertEquals(1, list(map(payload.get("route")).get("steps")).size());
		assertEquals("craft_item", map(list(map(payload.get("route")).get("steps")).getFirst()).get("targetId"));
		assertEquals(1, map(payload.get("factSourceCounts")).get("assumedInventory"));
		assertTrue(list(payload.get("trace")).stream().anyMatch(event -> "route_selected".equals(map(event).get("eventType"))));
	}

	@Test
	void assumedInventoryOverridesObservedInventoryForDebugging() {
		ActionGraphDebugService service = new ActionGraphDebugService();

		Map<String, Object> payload = service.resolveInventoryItem(new ActionGraphResolveRequest(
			"minecraft:bread",
			1,
			Map.of("minecraft:wheat", 3),
			Map.of("minecraft:wheat", 1),
			List.of(new CraftingOpportunity("wheat_x3_to_bread", "minecraft:bread", 1, List.of("minecraft:wheat", "minecraft:wheat", "minecraft:wheat"))),
			new ActionResolverContext("world-a", "bot", "minecraft:overworld", 100)
		));

		assertEquals(true, payload.get("resolved"));
		assertEquals("craft_item", map(list(map(payload.get("route")).get("steps")).getFirst()).get("targetId"));
	}

	@Test
	void resolvesInventoryItemGoalFromObservedCraftRecipeFacts() {
		ActionGraphDebugService service = new ActionGraphDebugService();

		Map<String, Object> payload = service.resolveInventoryItem(new ActionGraphResolveRequest(
			"minecraft:stick",
			4,
			Map.of(),
			Map.of("minecraft:oak_planks", 2),
			List.of(new CraftingOpportunity(
				"oak_planks_x2_to_stick",
				"minecraft:stick",
				4,
				List.of("minecraft:oak_planks", "minecraft:oak_planks")
			)),
			new ActionResolverContext("world-a", "bot", "minecraft:overworld", 100)
		));

		assertEquals(true, payload.get("resolved"));
		assertEquals(0, map(payload.get("route")).get("cost"));
		assertEquals(1, list(map(payload.get("route")).get("steps")).size());
		assertEquals("recipe_provider", map(list(map(payload.get("route")).get("steps")).getFirst()).get("actionId"));
		assertEquals(1, map(payload.get("factSourceCounts")).get("observedCraftRecipes"));
	}

	@Test
	void returnsNormalFailureWhenNoRecipeFactExists() {
		ActionGraphDebugService service = new ActionGraphDebugService();

		Map<String, Object> payload = service.resolveInventoryItem(new ActionGraphResolveRequest(
			"minecraft:bread",
			1,
			Map.of("minecraft:wheat", 3),
			Map.of(),
			new ActionResolverContext("world-a", "bot", "minecraft:overworld", 100)
		));

		assertEquals(true, payload.get("available"));
		assertEquals(false, payload.get("resolved"));
		assertEquals("no_route", payload.get("failureCode"));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object value) {
		return (Map<String, Object>) value;
	}

	@SuppressWarnings("unchecked")
	private static List<Object> list(Object value) {
		return (List<Object>) value;
	}
}
