package ai.moeru.airicraft.agent.actions;

import ai.moeru.actionplan.CandidateRoute;
import ai.moeru.actionplan.MethodKey;
import ai.moeru.actionplan.PlanAdvice;
import ai.moeru.actionplan.PlanCommand;
import ai.moeru.actionplan.PlanningFailure;
import ai.moeru.actionplan.PlanningStats;
import ai.moeru.actionplan.ProviderId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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

	@Test
	void snapshotDropsStaleAndExpectedFactsBeforeAdvice() {
		ActionFact stale = inventoryFact("minecraft:bread", 1, ActionFactProvenance.OBSERVED, 100);
		ActionFact expected = inventoryFact("minecraft:bread", 1, ActionFactProvenance.EXPECTED, ActionFact.NEVER_STALE);

		ActionResolveResult result = planner().adviseAndCommit(
			snapshot(List.of(stale, expected)),
			ActionGoal.inventoryItem("minecraft:bread", 1),
			Set.of()
		);

		assertFalse(result.resolved());
		assertEquals("no_route", result.failureCode());
	}

	@Test
	void observedGoalFactProducesAnEmptyCommittedRoute() {
		ActionFact observed = inventoryFact("minecraft:bread", 1, ActionFactProvenance.OBSERVED, ActionFact.NEVER_STALE);

		ActionResolveResult result = planner().adviseAndCommit(
			snapshot(List.of(observed)),
			ActionGoal.inventoryItem("minecraft:bread", 1),
			Set.of()
		);

		assertTrue(result.resolved());
		assertTrue(result.route().steps().isEmpty());
	}

	@Test
	void failedMiningMethodBlocksItsExactBlockAndToolChoice() {
		AiricraftPlanningSnapshot snapshot = new AiricraftPlanningSnapshot(
			List.of(inventoryFact("minecraft:stone_pickaxe", 1, ActionFactProvenance.OBSERVED, ActionFact.NEVER_STALE)),
			BlockAcquisitionTestFixtures.survival(),
			NearbyBlockAvailability.unknown(),
			CONTEXT
		);
		ActionGoal goal = ActionGoal.inventoryItem("minecraft:raw_iron", 1);
		ActionResolveResult first = planner().adviseAndCommit(snapshot, goal, Set.of());

		assertTrue(first.resolved());
		MethodKey failedKey = first.route().steps().getLast().methodKey();
		ActionResolveResult replanned = planner().adviseAndCommit(snapshot, goal, Set.of(failedKey));

		if (replanned.resolved()) {
			assertFalse(replanned.route().steps().getLast().methodKey().equals(failedKey));
		}
		else {
			assertEquals("no_route", replanned.failureCode());
		}
	}

	@Test
	void recursiveSmeltingAdviceKeepsItsWatchSpecification() {
		List<ActionFact> facts = List.of(
			inventoryFact("minecraft:raw_iron", 3, ActionFactProvenance.OBSERVED, ActionFact.NEVER_STALE),
			inventoryFact("minecraft:coal", 1, ActionFactProvenance.OBSERVED, ActionFact.NEVER_STALE),
			inventoryFact("minecraft:furnace", 1, ActionFactProvenance.OBSERVED, ActionFact.NEVER_STALE),
			inventoryFact("minecraft:stick", 2, ActionFactProvenance.OBSERVED, ActionFact.NEVER_STALE),
			new ActionFact(
				ActionFactIdentity.smeltRecipe("world-a", "bot", "raw_iron_to_iron_ingot"),
				Map.of(
					"inputItemId", "minecraft:raw_iron",
					"outputItemId", "minecraft:iron_ingot",
					"outputCount", 1,
					"maxInputQuantity", 3,
					"cookTimeTicks", 200
				),
				ActionFactProvenance.OBSERVED,
				90,
				ActionFact.NEVER_STALE
			),
			new ActionFact(
				ActionFactIdentity.craftRecipe("world-a", "bot", "iron_pickaxe"),
				Map.of(
					"outputItemId", "minecraft:iron_pickaxe",
					"outputCount", 1,
					"inputItemIds", List.of(
						"minecraft:iron_ingot", "minecraft:iron_ingot", "minecraft:iron_ingot",
						"minecraft:stick", "minecraft:stick"
					),
					"inputCounts", Map.of("minecraft:iron_ingot", 3, "minecraft:stick", 2),
					"gridKind", "CRAFTING_TABLE"
				),
				ActionFactProvenance.OBSERVED,
				90,
				ActionFact.NEVER_STALE
			)
		);

		ActionResolveResult result = planner().adviseAndCommit(
			snapshot(facts),
			ActionGoal.inventoryItem("minecraft:iron_pickaxe", 1),
			Set.of()
		);

		assertTrue(result.resolved(), () -> result.trace().toString());
		ActionPlanStep watch = result.route().steps().stream()
			.filter(step -> step.kind() == ActionStepKind.WATCH)
			.findFirst()
			.orElseThrow();
		assertEquals(ActionFactType.SMELTING_PROCESS, watch.watchSpec().condition().factType());
		assertEquals("raw_iron_to_iron_ingot", watch.watchSpec().condition().queryKeys().get("optionId"));
	}

	@Test
	void autoCommitRejectsUnknownCommandTypes() {
		MethodKey methodKey = new MethodKey(new ProviderId("test_provider"), "bad_command");
		CandidateRoute route = new CandidateRoute(
			methodKey,
			List.of(new PlanCommand(
				"bad_step",
				"not_registered",
				Map.of(
					"airicraftKind", ActionStepKind.PRIMITIVE.name(),
					"actionId", "test_provider",
					"alternativeId", "bad_command"
				),
				methodKey
			)),
			1,
			0
		);
		PlanAdvice advice = new PlanAdvice(
			List.of(route), Optional.of(route), PlanningFailure.none(), new PlanningStats(1, 0, 20_000), List.of()
		);
		AutoCommittingRoutePlanner planner = new AutoCommittingRoutePlanner(
			new AiricraftPlanAdvisor(problem -> advice)
		);

		ActionResolveResult result = planner.adviseAndCommit(
			snapshot(List.of()), ActionGoal.inventoryItem("minecraft:bread", 1), Set.of()
		);

		assertFalse(result.resolved());
		assertEquals("unknown_command_type", result.failureCode());
	}

	@Test
	void autoCommitRejectsUnsupportedGoalKinds() {
		ActionGoal goal = new ActionGoal(
			ActionFactType.WORLD_BLOCK,
			Map.of("blockId", "minecraft:stone"),
			Map.of("countAtLeast", 1)
		);

		ActionResolveResult result = planner().adviseAndCommit(snapshot(List.of()), goal, Set.of());

		assertFalse(result.resolved());
		assertEquals("unsupported_goal", result.failureCode());
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

	private static ActionFact inventoryFact(
		String itemId,
		int count,
		ActionFactProvenance provenance,
		long staleAfterTick
	) {
		return new ActionFact(
			ActionFactIdentity.inventoryItem("world-a", "bot", itemId),
			Map.of("count", count),
			provenance,
			90,
			staleAfterTick
		);
	}
}
