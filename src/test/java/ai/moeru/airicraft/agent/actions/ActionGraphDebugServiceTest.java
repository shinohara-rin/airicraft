package ai.moeru.airicraft.agent.actions;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionGraphDebugServiceTest {
	@Test
	void inspectListsPrimitiveMetadataAndIndexedActionsets() {
		ActionGraphDebugService service = new ActionGraphDebugService(Path.of("actionsets"));

		Map<String, Object> payload = service.inspectActionGraph();

		assertEquals(true, payload.get("available"));
		assertEquals(true, payload.get("actionsetValid"));
		assertTrue((Integer) payload.get("primitiveCount") >= 10);
		assertTrue((Integer) payload.get("actionsetCount") >= 2);
		assertTrue(list(payload.get("primitives")).stream()
			.anyMatch(primitive -> "craft_item".equals(map(primitive).get("id"))
				&& "WorldTaskRequest.CRAFT_RECIPE".equals(map(primitive).get("executorBinding"))));
		assertTrue(list(payload.get("actionsets")).stream()
			.anyMatch(actionset -> "make_bread".equals(map(actionset).get("actionId"))
				&& "builtin".equals(map(actionset).get("namespace"))));
	}

	@Test
	void resolvesInventoryItemGoalFromAssumedInventoryFacts() {
		ActionGraphDebugService service = new ActionGraphDebugService(Path.of("actionsets"));

		Map<String, Object> payload = service.resolveInventoryItem(new ActionGraphResolveRequest(
			"minecraft:bread",
			1,
			Map.of("minecraft:wheat", 3),
			Map.of(),
			new ActionResolverContext("world-a", "bot", "minecraft:overworld", 100)
		));

		assertEquals(true, payload.get("available"));
		assertEquals(true, payload.get("resolved"));
		assertEquals(10, map(payload.get("route")).get("cost"));
		assertEquals(1, list(map(payload.get("route")).get("steps")).size());
		assertEquals("craft_item", map(list(map(payload.get("route")).get("steps")).getFirst()).get("targetId"));
		assertEquals(1, map(payload.get("factSourceCounts")).get("assumedInventory"));
		assertTrue(list(payload.get("trace")).stream().anyMatch(event -> "route_selected".equals(map(event).get("eventType"))));
	}

	@Test
	void assumedInventoryOverridesObservedInventoryForDebugging() {
		ActionGraphDebugService service = new ActionGraphDebugService(Path.of("actionsets"));

		Map<String, Object> payload = service.resolveInventoryItem(new ActionGraphResolveRequest(
			"minecraft:bread",
			1,
			Map.of("minecraft:wheat", 3),
			Map.of("minecraft:wheat", 1),
			new ActionResolverContext("world-a", "bot", "minecraft:overworld", 100)
		));

		assertEquals(true, payload.get("resolved"));
		assertEquals("craft_item", map(list(map(payload.get("route")).get("steps")).getFirst()).get("targetId"));
	}

	@Test
	void returnsValidationDiagnosticsWhenActionsetsCannotLoad() {
		ActionGraphDebugService service = new ActionGraphDebugService(Path.of("missing-actionsets"));

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
