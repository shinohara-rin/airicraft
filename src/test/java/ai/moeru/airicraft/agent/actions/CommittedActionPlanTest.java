package ai.moeru.airicraft.agent.actions;

import ai.moeru.airicraft.agent.tasks.TaskExecutionState;
import ai.moeru.airicraft.agent.tasks.TaskFailureCode;
import ai.moeru.airicraft.agent.tasks.TaskTerminalEvent;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CommittedActionPlanTest {
	private static final ActionGoal GOAL = ActionGoal.inventoryItem("minecraft:iron_pickaxe", 1);
	private static final ActionResolverContext CONTEXT = new ActionResolverContext("world", "bot", "minecraft:overworld", 100);
	private static final String STEPS = """
		[{"primitive":"craft_item","args":{"itemId":"minecraft:stick","quantity":4}},
		 {"primitive":"mine_block","args":{"blockIds":["minecraft:iron_ore"],"quantity":3}}]
		""";

	@Test
	void adviceStartsNoExecutionAndKeepsControlRevision() throws Exception {
		ActionGraphCoordinator coordinator = new ActionGraphCoordinator(step -> { throw new AssertionError("advice dispatched work"); });
		try {
			long revision = coordinator.revision();
			Map<String, Object> advice = coordinator.recommend(AiricraftPlanningSnapshot.capture(input(100, Map.of("minecraft:iron_pickaxe", 1), null)), GOAL, "context").get(5, TimeUnit.SECONDS);
			assertEquals(true, advice.get("advisory"));
			assertEquals("context", advice.get("planContext"));
			assertTrue(coordinator.list().isEmpty());
			assertEquals(revision, coordinator.revision());
		}
		finally { coordinator.shutdown(); }
	}

	@Test
	void committedStepsRunExactlyOnceWithoutSolverOrSuccessReplanning() {
		List<ActionPlanStep> dispatched = new ArrayList<>();
		ActionGraphExecutionRuntime runtime = new ActionGraphExecutionRuntime(step -> {
			dispatched.add(step);
			return ActionGraphPrimitiveDispatchResult.accepted("task-" + dispatched.size(), Map.of());
		}, command -> { throw new AssertionError("committed execution invoked solver"); });
		runtime.submit(GOAL, Map.of(), CONTEXT, 100);
		runtime.commitRoute(parse(STEPS));
		assertEquals(ActionGraphExecutionState.WAITING_PRIMITIVE, runtime.tick(input(101, Map.of(), null)).state());
		for (int tick = 102; tick < 110; tick++) { runtime.tick(input(tick, Map.of(), null)); }
		assertEquals(1, dispatched.size());
		runtime.tick(input(110, Map.of(), completed("unrelated")));
		assertEquals(1, dispatched.size());
		runtime.tick(input(111, Map.of(), completed("task-1")));
		runtime.tick(input(132, Map.of(), null));
		assertEquals(List.of("craft_item", "mine_block"), dispatched.stream().map(ActionPlanStep::targetId).toList());
		runtime.tick(input(133, Map.of("minecraft:iron_pickaxe", 1), completed("task-2")));
		assertEquals(ActionGraphExecutionState.SUCCEEDED, runtime.tick(input(154, Map.of("minecraft:iron_pickaxe", 1), null)).state());
		assertEquals(0, runtime.snapshot().replanCount());
	}

	@Test
	void lightingRefusalStopsAfterOneDispatchAndEmitsOneReplanNotice() {
		List<ActionPlanStep> dispatched = new ArrayList<>();
		ActionGraphCoordinator coordinator = new ActionGraphCoordinator(step -> {
			dispatched.add(step);
			return ActionGraphPrimitiveDispatchResult.failed(TaskFailureCode.MISSING_ITEM,
				"insufficient_illumination torchCount=0", Map.of("failureReason", "insufficient_illumination"));
		});
		try {
			var started = coordinator.commit(GOAL, parse(STEPS), Map.of(), CONTEXT, 100);
			for (int tick = 101; tick < 180; tick++) { coordinator.tick(input(tick, Map.of(), null), true); }
			var snapshot = coordinator.inspect(started.execution().execution().executionId()).execution();
			assertEquals(ActionGraphExecutionState.REPLAN_REQUIRED, snapshot.state());
			assertEquals(1, dispatched.size());
			assertEquals(0, snapshot.replanCount());
			assertEquals("insufficient_illumination", snapshot.dispatch().get("failureReason"));
			assertFalse(coordinator.hasForeground());
			assertFalse(coordinator.hasNonterminal());
			var notices = coordinator.drainEvents().stream().filter(event -> event.type().equals("action_graph.goal_terminal")).toList();
			assertEquals(1, notices.size());
			assertEquals("REPLAN_REQUIRED", notices.getFirst().payload().get("state"));
			assertEquals(GOAL.normalizedKey(), notices.getFirst().payload().get("goal"));
		}
		finally { coordinator.shutdown(); }
	}

	@Test
	void exhaustedPlanRequestsDecisionInsteadOfClaimingGoalSuccess() {
		ActionGraphExecutionRuntime runtime = new ActionGraphExecutionRuntime(step -> { throw new AssertionError(); });
		runtime.submit(GOAL, Map.of(), CONTEXT, 100);
		runtime.commitRoute(ActionRoute.empty());
		assertEquals(ActionGraphExecutionState.REPLAN_REQUIRED, runtime.tick(input(101, Map.of(), null)).state());
		assertEquals("goal_not_satisfied", runtime.snapshot().failureCode());
	}

	@Test
	void unknownAndMalformedCommandsAreRejectedBeforeAdmission() {
		assertThrows(IllegalArgumentException.class, () -> parse("[{\"primitive\":\"invented_action\",\"args\":{}}]"));
		assertThrows(IllegalArgumentException.class, () -> parse("[{\"primitive\":\"mine_block\",\"args\":{}}]"));
		assertThrows(IllegalArgumentException.class, () -> parse("[{\"primitive\":\"inspect_inventory\",\"args\":{}}]"));
		assertThrows(IllegalArgumentException.class, () -> parse("[{\"primitive\":\"watch\",\"args\":{}}]"));
		assertThrows(IllegalArgumentException.class, () -> parse("[{\"primitive\":\"mine_block\",\"args\":{\"blockIds\":[\"minecraft:iron_ore\"],\"quantity\":0.5}}]"));
	}

	@Test
	void recommendedIronRouteRoundTripsThroughExplicitCommitFormat() {
		var input = new ActionGraphExecutionInput(CONTEXT, Map.of("minecraft:oak_log", 8), Map.of(), true, false, null,
			List.of(), ActionGraphRecipeFixtures.survivalCrafts(), List.of(), ActionGraphRecipeFixtures.survivalSmelts(),
			List.of(), null, Map.of(), BlockAcquisitionTestFixtures.survival(), NearbyBlockAvailability.unknown());
		var advice = new AiricraftPlanAdvisor().recommend(AiricraftPlanningSnapshot.capture(input), GOAL, "context");
		var candidates = new com.google.gson.Gson().toJsonTree(advice.get("candidates")).getAsJsonArray();
		assertFalse(candidates.isEmpty(), advice.toString());
		for (var candidate : candidates) {
			ActionRoute route = CommittedActionPlan.parse(candidate.getAsJsonObject().getAsJsonArray("steps"), CONTEXT);
			assertFalse(route.steps().isEmpty());
			assertTrue(route.steps().stream().anyMatch(step -> step.targetId().equals("smelt_item")));
		}
	}

	@Test
	void cancellationAndResetInvalidateControlRevision() {
		ActionGraphCoordinator coordinator = new ActionGraphCoordinator(step -> ActionGraphPrimitiveDispatchResult.accepted("task", Map.of()));
		try {
			long initial = coordinator.revision();
			var started = coordinator.commit(GOAL, parse(STEPS), Map.of(), CONTEXT, 100);
			assertTrue(coordinator.revision() > initial);
			long committed = coordinator.revision();
			coordinator.cancel(started.execution().execution().executionId(), "user_cancelled", 101);
			assertTrue(coordinator.revision() > committed);
			long cancelled = coordinator.revision();
			coordinator.clear();
			assertTrue(coordinator.revision() > cancelled);
		}
		finally { coordinator.shutdown(); }
	}

	private static ActionRoute parse(String json) { return CommittedActionPlan.parse(JsonParser.parseString(json).getAsJsonArray(), CONTEXT); }
	private static TaskTerminalEvent completed(String taskId) { return new TaskTerminalEvent(taskId, null, TaskExecutionState.COMPLETED, "done", null); }
	private static ActionGraphExecutionInput input(long tick, Map<String, Integer> inventory, TaskTerminalEvent event) {
		return new ActionGraphExecutionInput(new ActionResolverContext("world", "bot", "minecraft:overworld", tick), inventory, Map.of(), true, true,
			event, List.of(), List.of(), List.of(), List.of(), List.of(), null, Map.of(), BlockAcquisitionIndex.empty(), NearbyBlockAvailability.unknown());
	}
}
