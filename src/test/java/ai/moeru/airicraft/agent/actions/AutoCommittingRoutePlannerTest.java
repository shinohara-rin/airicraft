package ai.moeru.airicraft.agent.actions;

import ai.moeru.actionplan.MethodKey;
import ai.moeru.actionplan.ProviderId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoCommittingRoutePlannerTest {
	private static final ActionResolverContext CONTEXT = new ActionResolverContext(
		"world-a", "bot", "minecraft:overworld", 100
	);

	@Test
	void wheatHasNoCropAcquisitionAfterYamlRemoval() {
		ActionResolveResult result = planner().adviseAndCommit(
			snapshot(List.of()),
			ActionGoal.inventoryItem("minecraft:wheat", 1),
			Set.of()
		);

		assertFalse(result.resolved());
		assertEquals("no_route", result.failureCode());
	}

	@Test
	void failedResourceMethodBlocksTheExactMethodKey() {
		MethodKey key = new MethodKey(new ProviderId("resource_provider"), "WOOD_LOGS");
		ActionResolveResult result = planner().adviseAndCommit(
			snapshot(List.of()),
			ActionGoal.resourceCollection("WOOD_LOGS", 1),
			Set.of(key)
		);

		assertFalse(result.resolved());
		assertTrue(result.trace().stream().anyMatch(event -> "goal_failed".equals(event.eventType())));
	}

	private static AutoCommittingRoutePlanner planner() {
		return new AutoCommittingRoutePlanner();
	}

	private static AiricraftPlanningSnapshot snapshot(List<ActionFact> facts) {
		return new AiricraftPlanningSnapshot(
			facts,
			BlockAcquisitionIndex.empty(),
			NearbyBlockAvailability.unknown(),
			CONTEXT
		);
	}
}
