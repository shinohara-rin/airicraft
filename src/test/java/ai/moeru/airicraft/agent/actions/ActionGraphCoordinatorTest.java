package ai.moeru.airicraft.agent.actions;

import ai.moeru.airicraft.agent.tasks.TaskExecutionState;
import ai.moeru.airicraft.agent.tasks.TaskTerminalEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionGraphCoordinatorTest {
	private static final String WORLD = "world-a";
	private static final String DIMENSION = "minecraft:overworld";
	private static final String ACTOR = "bot";

	@Test
	@Disabled("The removed YAML crop watch no longer exists")
	void suspendedWheatReleasesForegroundAndResumesAfterSecondGoal() {
		RecordingDispatcher dispatcher = new RecordingDispatcher();
		ActionGraphCoordinator coordinator = new ActionGraphCoordinator(defaultIndex(), dispatcher);
		ActionGraphStartResult bread = coordinator.submit(ActionGoal.inventoryItem("minecraft:bread", 1), Map.of(), context(100), 100);

		coordinator.tick(input(101, Map.of(), Map.of(), null, List.of(crop("farm-1", 0, 3, 101))), true);

		assertEquals(ActionGraphAdmission.STARTED, bread.admission());
		assertFalse(coordinator.hasForeground());
		assertEquals(ActionGraphResidency.SUSPENDED, coordinator.inspect(bread.execution().execution().executionId()).residency());
		ActionGraphWatchSnapshot pinned = coordinator.pendingWatches().getFirst();
		assertEquals("farm-1", pinned.spec().condition().queryKeys().get("siteId"));
		assertEquals("farm-1", pinned.spec().sourceFactIdentity().keys().get("siteId"));
		assertEquals(1L, coordinator.drainEvents().stream()
			.filter(event -> "action_graph.goal_suspended".equals(event.type()))
			.count());

		coordinator.tick(input(102, Map.of(), Map.of(), null, List.of(crop("farm-1", 0, 3, 102))), true);
		assertTrue(coordinator.drainEvents().stream()
			.noneMatch(event -> "action_graph.goal_suspended".equals(event.type())));

		ActionGraphStartResult logs = coordinator.submit(ActionGoal.resourceCollection("WOOD_LOGS", 1), Map.of(), context(102), 102);
		assertEquals(ActionGraphAdmission.STARTED, logs.admission());
		assertNotEquals(bread.execution().execution().executionId(), logs.execution().execution().executionId());

		coordinator.tick(input(103, Map.of(), Map.of(), null, List.of(
			crop("farm-1", 0, 3, 103),
			crop("farm-2", 3, 3, 103)
		)), true);
		assertEquals(ActionGraphResidency.SUSPENDED, coordinator.inspect(bread.execution().execution().executionId()).residency());
		assertEquals(1, dispatcher.steps.size());

		coordinator.tick(input(104, Map.of(), Map.of(), null, List.of(crop("farm-1", 3, 3, 104))), true);
		assertEquals(ActionGraphResidency.RUNNABLE, coordinator.inspect(bread.execution().execution().executionId()).residency());
		assertEquals(logs.execution().execution().executionId(), coordinator.foregroundExecutionId());

		coordinator.tick(input(
			105,
			Map.of(),
			Map.of("WOOD_LOGS", 1),
			new TaskTerminalEvent("task-1", null, TaskExecutionState.COMPLETED, "collected", null),
			List.of(crop("farm-1", 3, 3, 105))
		), true);

		assertEquals(bread.execution().execution().executionId(), coordinator.foregroundExecutionId());
		assertEquals(ActionGraphResidency.FOREGROUND, coordinator.inspect(bread.execution().execution().executionId()).residency());
		assertEquals("harvest_wheat", dispatcher.steps.get(1).stepId());
	}

	@Test
	void identicalGoalIsIdempotentAndDifferentForegroundGoalIsBusy() {
		ActionGraphCoordinator coordinator = new ActionGraphCoordinator(ActionsetIndex.empty(), step ->
			ActionGraphPrimitiveDispatchResult.accepted("task", Map.of()));
		ActionGoal firstGoal = ActionGoal.resourceCollection("WOOD_LOGS", 1);

		ActionGraphStartResult first = coordinator.submit(firstGoal, Map.of(), context(100), 100);
		ActionGraphStartResult identical = coordinator.submit(firstGoal, Map.of(), context(101), 101);
		ActionGraphStartResult different = coordinator.submit(ActionGoal.resourceCollection("COBBLESTONE", 1), Map.of(), context(102), 102);

		assertEquals(ActionGraphAdmission.STARTED, first.admission());
		assertEquals(ActionGraphAdmission.EXISTING, identical.admission());
		assertEquals(first.execution().execution().executionId(), identical.execution().execution().executionId());
		assertEquals(ActionGraphAdmission.BUSY, different.admission());
		assertEquals("foreground_busy", different.failureCode());
	}

	@Test
	void terminalFailureEventCarriesCapabilityReasonExactlyOnce() {
		ActionGraphCoordinator coordinator = new ActionGraphCoordinator(ActionsetIndex.empty(), step -> {
			throw new AssertionError("unknown acquisition must not dispatch");
		}, true);
		ActionGraphStartResult started = coordinator.submit(
			ActionGoal.inventoryItem("minecraft:seagrass", 20),
			Map.of(),
			context(100),
			100
		);

		coordinator.tick(input(101, Map.of(), Map.of(), null, List.of()), true);

		List<ActionGraphCoordinatorEvent> terminal = coordinator.drainEvents().stream()
			.filter(event -> "action_graph.goal_terminal".equals(event.type()))
			.toList();
		assertEquals(1, terminal.size());
		assertEquals(started.execution().execution().executionId(), terminal.getFirst().executionId());
		assertEquals("FAILED", terminal.getFirst().payload().get("state"));
		assertEquals("no_route", terminal.getFirst().payload().get("failureCode"));

		coordinator.tick(input(102, Map.of(), Map.of(), null, List.of()), true);
		assertTrue(coordinator.drainEvents().stream()
			.noneMatch(event -> "action_graph.goal_terminal".equals(event.type())));
	}

	@Test
	@Disabled("The removed YAML watch fixtures no longer exist")
	void fulfilledWatchesResumeInFulfillmentThenCreationOrder() {
		ActionGraphCoordinator coordinator = new ActionGraphCoordinator(twoWatchIndex(), step -> {
			throw new AssertionError("passive watches must not dispatch");
		}, true);
		ActionGraphStartResult bread = coordinator.submit(ActionGoal.inventoryItem("minecraft:bread", 1), Map.of(), context(100), 100);
		coordinator.tick(input(101, Map.of(), Map.of(), null, List.of()), true);
		ActionGraphStartResult stick = coordinator.submit(ActionGoal.inventoryItem("minecraft:stick", 1), Map.of(), context(102), 102);
		coordinator.tick(input(103, Map.of(), Map.of(), null, List.of()), true);
		coordinator.drainEvents();

		coordinator.tick(input(104, Map.of("minecraft:bread", 1, "minecraft:stick", 1), Map.of(), null, List.of()), true);

		List<String> resumed = coordinator.drainEvents().stream()
			.filter(event -> "action_graph.goal_resumed".equals(event.type()))
			.map(ActionGraphCoordinatorEvent::executionId)
			.toList();
		assertEquals(List.of(
			bread.execution().execution().executionId(),
			stick.execution().execution().executionId()
		), resumed);
	}

	@Test
	@Disabled("The removed YAML crop watch no longer exists")
	void ineligibleAreaPausesTimeoutAndPassiveReflexPollingNeverDispatches() {
		RecordingDispatcher dispatcher = new RecordingDispatcher();
		ActionGraphCoordinator coordinator = new ActionGraphCoordinator(defaultIndex(), dispatcher);
		ActionGraphStartResult bread = coordinator.submit(ActionGoal.inventoryItem("minecraft:bread", 1), Map.of(), context(100), 100);
		coordinator.tick(input(101, Map.of(), Map.of(), null, List.of(crop("farm-1", 0, 3, 101))), true);
		ActionGraphWatchSnapshot watch = coordinator.pendingWatches().getFirst();

		coordinator.tick(inputWithProgress(1000, List.of(crop("farm-1", 0, 3, 1000)), watch.watchId(), ActionWatchProgressObservation.paused("outside_simulation_distance")), false);
		ActionGraphWatchSnapshot paused = coordinator.pendingWatches().getFirst();
		assertEquals(0L, paused.consumedEligibleTicks());
		assertFalse(paused.progressEligible());
		assertTrue(dispatcher.steps.isEmpty());

		coordinator.tick(inputWithProgress(1005, List.of(crop("farm-1", 0, 3, 1005)), watch.watchId(), ActionWatchProgressObservation.paused("outside_simulation_distance")), false);
		coordinator.tick(inputWithProgress(1010, List.of(crop("farm-1", 0, 3, 1010)), watch.watchId(), ActionWatchProgressObservation.active()), false);
		ActionGraphWatchSnapshot resumed = coordinator.pendingWatches().getFirst();
		assertEquals(5L, resumed.consumedEligibleTicks());
		List<ActionTraceEvent> progressTrace = coordinator.inspect(bread.execution().execution().executionId()).execution().trace().stream()
			.filter(event -> event.eventType().startsWith("watch_progress_"))
			.toList();
		assertEquals(List.of("watch_progress_paused", "watch_progress_resumed"), progressTrace.stream().map(ActionTraceEvent::eventType).toList());

		coordinator.tick(inputWithProgress(1020, List.of(crop("farm-1", 3, 3, 1020)), watch.watchId(), ActionWatchProgressObservation.paused("outside_simulation_distance")), false);
		assertEquals(ActionGraphResidency.RUNNABLE, coordinator.inspect(bread.execution().execution().executionId()).residency());
		assertFalse(coordinator.hasForeground());
		assertTrue(dispatcher.steps.isEmpty());
		ActionGraphStartResult busy = coordinator.submit(ActionGoal.resourceCollection("DIRT", 1), Map.of(), context(1021), 1021);
		assertEquals(ActionGraphAdmission.BUSY, busy.admission());
		assertEquals("foreground_busy", busy.failureCode());
	}

	@Test
	@Disabled("The removed YAML watch fixtures no longer exist")
	void noIdCancellationRequiresIdWhenMultipleGoalsAreSuspended() {
		ActionGraphCoordinator coordinator = new ActionGraphCoordinator(twoWatchIndex(), step -> {
			throw new AssertionError("watch route must not dispatch");
		}, true);
		ActionGraphStartResult bread = coordinator.submit(ActionGoal.inventoryItem("minecraft:bread", 1), Map.of(), context(100), 100);
		coordinator.tick(input(101, Map.of(), Map.of(), null, List.of()), true);
		ActionGraphStartResult stick = coordinator.submit(ActionGoal.inventoryItem("minecraft:stick", 1), Map.of(), context(102), 102);
		coordinator.tick(input(103, Map.of(), Map.of(), null, List.of()), true);

		try {
			coordinator.cancelSelected("ambiguous", 104);
			throw new AssertionError("expected execution_id_required");
		}
		catch (IllegalArgumentException exception) {
			assertEquals("execution_id_required", exception.getMessage());
		}

		ActionGraphExecutionView cancelled = coordinator.cancel(bread.execution().execution().executionId(), "selected", 105);
		assertEquals(ActionGraphResidency.TERMINAL, cancelled.residency());
		assertEquals(ActionGraphResidency.SUSPENDED, coordinator.inspect(stick.execution().execution().executionId()).residency());
	}

	@Test
	@Disabled("The removed YAML watch fixtures no longer exist")
	void capacityLimitsLiveGoalsAndTerminalHistoryKeepsLatestThirtyTwo() {
		ActionsetIndex watches = manyWatchIndex(ActionGraphCoordinator.MAX_NONTERMINAL_EXECUTIONS + 1);
		ActionGraphCoordinator coordinator = new ActionGraphCoordinator(watches, step -> {
			throw new AssertionError("watch route must not dispatch");
		}, true);
		for (int index = 0; index < ActionGraphCoordinator.MAX_NONTERMINAL_EXECUTIONS; index++) {
			ActionGraphStartResult started = coordinator.submit(ActionGoal.inventoryItem("minecraft:queued_" + index, 1), Map.of(), context(index * 2L), index * 2L);
			assertEquals(ActionGraphAdmission.STARTED, started.admission());
			coordinator.tick(input(index * 2L + 1L, Map.of(), Map.of(), null, List.of()), true);
		}
		ActionGraphStartResult rejected = coordinator.submit(
			ActionGoal.inventoryItem("minecraft:queued_" + ActionGraphCoordinator.MAX_NONTERMINAL_EXECUTIONS, 1),
			Map.of(),
			context(100),
			100
		);
		assertEquals(ActionGraphAdmission.BUSY, rejected.admission());
		assertEquals("capacity_reached", rejected.failureCode());

		ActionGraphCoordinator terminalCoordinator = new ActionGraphCoordinator(ActionsetIndex.empty(), step -> {
			throw new AssertionError("already-satisfied goals must not dispatch");
		});
		for (int index = 0; index < ActionGraphCoordinator.MAX_TERMINAL_EXECUTIONS + 3; index++) {
			String itemId = "minecraft:terminal_" + index;
			terminalCoordinator.submit(ActionGoal.inventoryItem(itemId, 1), Map.of(), context(index * 2L), index * 2L);
			terminalCoordinator.tick(input(index * 2L + 1L, Map.of(itemId, 1), Map.of(), null, List.of()), true);
		}
		assertEquals(ActionGraphCoordinator.MAX_TERMINAL_EXECUTIONS, terminalCoordinator.list().size());
		assertTrue(terminalCoordinator.list().stream().noneMatch(view -> view.execution().goal().keys().containsValue("minecraft:terminal_0")));
		assertTrue(terminalCoordinator.list().stream().anyMatch(view -> view.execution().goal().keys().containsValue("minecraft:terminal_34")));
	}

	private static ActionGraphExecutionInput input(
		long tick,
		Map<String, Integer> inventory,
		Map<String, Integer> resources,
		TaskTerminalEvent terminal,
		List<ActionFact> facts
	) {
		return new ActionGraphExecutionInput(
			context(tick), inventory, resources, true, true, terminal,
			List.of(), ActionGraphRecipeFixtures.survivalCrafts(), List.of(), ActionGraphRecipeFixtures.survivalSmelts(), facts,
			new ActionGraphAgentPosition(WORLD, DIMENSION, 0, 64, 0), Map.of()
		);
	}

	private static ActionGraphExecutionInput inputWithProgress(
		long tick,
		List<ActionFact> facts,
		String watchId,
		ActionWatchProgressObservation observation
	) {
		return new ActionGraphExecutionInput(
			context(tick), Map.of(), Map.of(), true, true, null,
			List.of(), ActionGraphRecipeFixtures.survivalCrafts(), List.of(), ActionGraphRecipeFixtures.survivalSmelts(), facts,
			new ActionGraphAgentPosition(WORLD, DIMENSION, 1000, 64, 1000), Map.of(watchId, observation)
		);
	}

	private static ActionResolverContext context(long tick) {
		return new ActionResolverContext(WORLD, ACTOR, DIMENSION, tick);
	}

	private static ActionFact crop(String siteId, int mature, int total, long tick) {
		return new ActionFact(
			ActionFactIdentity.worldCropGroup(WORLD, DIMENSION, siteId, "minecraft:wheat"),
			Map.of(
				"matureCount", mature,
				"totalCount", total,
				"origin", Map.of("x", "farm-1".equals(siteId) ? 0 : 160, "y", 64, "z", 0)
			),
			ActionFactProvenance.OBSERVED,
			tick,
			ActionFact.NEVER_STALE
		);
	}

	private static ActionsetIndex defaultIndex() {
		ActionsetLoadResult load = ActionsetLibraryLoader.defaults().load(Path.of("actionsets"));
		assertTrue(load.valid(), () -> load.diagnostics().toString());
		return load.index();
	}

	private static ActionsetIndex twoWatchIndex() {
		LinkedHashMap<String, ActionsetEntry> entries = new LinkedHashMap<>();
		entries.put("wait_bread", watchEntry("wait_bread", "minecraft:bread"));
		entries.put("wait_stick", watchEntry("wait_stick", "minecraft:stick"));
		return new ActionsetIndex(entries);
	}

	private static ActionsetIndex manyWatchIndex(int count) {
		LinkedHashMap<String, ActionsetEntry> entries = new LinkedHashMap<>();
		for (int index = 0; index < count; index++) {
			String itemId = "minecraft:queued_" + index;
			entries.put("wait_" + index, watchEntry("wait_" + index, itemId));
		}
		return new ActionsetIndex(entries);
	}

	private static ActionsetEntry watchEntry(String actionId, String itemId) {
		Map<String, Object> action = Map.of(
			"produces", List.of(Map.of("fact", "inventory.item", "itemId", itemId, "countAtLeast", 1)),
			"alternatives", List.of(Map.of(
				"id", "wait",
				"cost", 1,
				"steps", List.of(Map.of(
					"id", "wait_for_" + itemId.substring(itemId.indexOf(':') + 1),
					"watch", Map.of("fact", "inventory.item", "itemId", itemId, "countAtLeast", 1, "timeoutTicks", 20)
				))
			))
		);
		return new ActionsetEntry(actionId, ActionsetNamespace.BUILTIN, "test.yml", null, action);
	}

	private static final class RecordingDispatcher implements ActionGraphPrimitiveDispatcher {
		private final List<ActionPlanStep> steps = new ArrayList<>();

		@Override
		public ActionGraphPrimitiveDispatchResult dispatch(ActionPlanStep step) {
			steps.add(step);
			return ActionGraphPrimitiveDispatchResult.accepted("task-" + steps.size(), Map.of());
		}
	}
}
