package ai.moeru.airicraft.agent.actions;

import ai.moeru.airicraft.agent.tasks.TaskExecutionState;
import ai.moeru.airicraft.agent.tasks.TaskTerminalEvent;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionGraphExecutionRuntimeTest {
	private static final ActionResolverContext CONTEXT = new ActionResolverContext(
		"world-a",
		"bot",
		"minecraft:overworld",
		100
	);

	@Test
	void dispatchesFirstPrimitiveAndCompletesAfterObservedGoalFact() {
		RecordingDispatcher dispatcher = new RecordingDispatcher();
		ActionGraphExecutionRuntime runtime = new ActionGraphExecutionRuntime(defaultIndex(), dispatcher);
		runtime.submit(ActionGoal.inventoryItem("minecraft:bread", 1), Map.of("minecraft:wheat", 3), CONTEXT, 100);

		ActionGraphExecutionSnapshot dispatched = runtime.tick(input(Map.of("minecraft:wheat", 3), null, 101));

		assertEquals(ActionGraphExecutionState.WAITING_PRIMITIVE, dispatched.state());
		assertEquals(1, dispatcher.dispatchedSteps.size());
		assertEquals("craft_item", dispatcher.dispatchedSteps.getFirst().targetId());
		assertEquals("task-1", dispatched.activeTaskId());
		assertEquals(1, dispatched.stepAttempt());

		ActionGraphExecutionSnapshot completed = runtime.tick(input(
			Map.of("minecraft:wheat", 3, "minecraft:bread", 1),
			new TaskTerminalEvent("task-1", null, TaskExecutionState.COMPLETED, "crafted", null),
			102
		));

		assertEquals(ActionGraphExecutionState.SUCCEEDED, completed.state());
		assertEquals(1, completed.cursor());
		assertEquals("", completed.failureCode());
		assertTrace(completed.trace(), "primitive_terminal");
		assertTrace(completed.trace(), "execution_succeeded");
	}

	@Test
	void zeroStepRouteSucceedsOnlyAfterGoalFactIsObserved() {
		RecordingDispatcher dispatcher = new RecordingDispatcher();
		ActionGraphExecutionRuntime runtime = new ActionGraphExecutionRuntime(defaultIndex(), dispatcher);
		runtime.submit(ActionGoal.inventoryItem("minecraft:bread", 1), Map.of(), CONTEXT, 100);

		ActionGraphExecutionSnapshot noRoute = runtime.tick(input(Map.of(), null, 101));

		assertEquals(ActionGraphExecutionState.FAILED, noRoute.state());
		assertEquals("no_route", noRoute.failureCode());

		runtime.submit(ActionGoal.inventoryItem("minecraft:bread", 1), Map.of(), CONTEXT, 102);
		ActionGraphExecutionSnapshot satisfied = runtime.tick(input(Map.of("minecraft:bread", 1), null, 103));

		assertEquals(ActionGraphExecutionState.SUCCEEDED, satisfied.state());
		assertTrue(dispatcher.dispatchedSteps.isEmpty());
		assertTrace(satisfied.trace(), "step_skipped");
	}

	@Test
	void transientPrimitiveFailureRetriesThenFailsWithoutDispatchChurn() {
		RecordingDispatcher dispatcher = new RecordingDispatcher();
		ActionGraphExecutionRuntime runtime = new ActionGraphExecutionRuntime(defaultIndex(), dispatcher);
		runtime.submit(ActionGoal.inventoryItem("minecraft:bread", 1), Map.of("minecraft:wheat", 3), CONTEXT, 100);

		runtime.tick(input(Map.of("minecraft:wheat", 3), null, 101));
		runtime.tick(input(Map.of("minecraft:wheat", 3), failed("task-1", "path_timeout"), 102));
		runtime.tick(input(Map.of("minecraft:wheat", 3), null, 103));
		runtime.tick(input(Map.of("minecraft:wheat", 3), failed("task-2", "path_timeout"), 104));
		runtime.tick(input(Map.of("minecraft:wheat", 3), null, 105));
		ActionGraphExecutionSnapshot failed = runtime.tick(input(Map.of("minecraft:wheat", 3), failed("task-3", "path_timeout"), 106));
		ActionGraphExecutionSnapshot stable = runtime.tick(input(Map.of("minecraft:wheat", 3), null, 107));

		assertEquals(ActionGraphExecutionState.FAILED, failed.state());
		assertEquals("budget_exceeded", failed.failureCode());
		assertEquals(3, dispatcher.dispatchedSteps.size());
		assertEquals(3, stable.recoveryHistory().size());
		assertEquals(3, dispatcher.dispatchedSteps.size());
		assertTrace(failed.trace(), "recovery_selected");
		assertTrace(failed.trace(), "execution_failed");
	}

	@Test
	void watchStepSuspendsUntilObservedFactFulfillsGoal() {
		RecordingDispatcher dispatcher = new RecordingDispatcher();
		ActionGraphExecutionRuntime runtime = new ActionGraphExecutionRuntime(watchIndex(), dispatcher);
		runtime.submit(ActionGoal.inventoryItem("minecraft:bread", 1), Map.of(), CONTEXT, 100);

		ActionGraphExecutionSnapshot watching = runtime.tick(input(Map.of(), null, 101));

		assertEquals(ActionGraphExecutionState.WATCHING, watching.state());
		assertEquals(1, watching.watchCount());
		assertTrue(watching.pendingWatch().contains("wait_for_bread"));
		assertTrue(dispatcher.dispatchedSteps.isEmpty());

		ActionGraphExecutionSnapshot fulfilled = runtime.tick(input(Map.of("minecraft:bread", 1), null, 102));

		assertEquals(ActionGraphExecutionState.SUCCEEDED, fulfilled.state());
		assertEquals(1, fulfilled.cursor());
		assertTrace(fulfilled.trace(), "watch_fulfilled");
	}

	private static ActionGraphExecutionInput input(Map<String, Integer> observedInventory, TaskTerminalEvent terminalEvent, long tick) {
		return new ActionGraphExecutionInput(
			new ActionResolverContext(CONTEXT.worldId(), CONTEXT.actorId(), CONTEXT.dimension(), tick),
			observedInventory,
			true,
			true,
			terminalEvent
		);
	}

	private static TaskTerminalEvent failed(String taskId, String message) {
		return new TaskTerminalEvent(taskId, null, TaskExecutionState.FAILED, message, null);
	}

	private static ActionsetIndex defaultIndex() {
		ActionsetLoadResult load = ActionsetLibraryLoader.defaults().load(Path.of("actionsets"));
		assertTrue(load.valid(), () -> load.diagnostics().toString());
		return load.index();
	}

	private static ActionsetIndex watchIndex() {
		Map<String, Object> action = new LinkedHashMap<>();
		action.put("produces", List.of(Map.of(
			"fact", "inventory.item",
			"itemId", "minecraft:bread",
			"countAtLeast", 1
		)));
		action.put("alternatives", List.of(Map.of(
			"id", "wait_until_bread_exists",
			"cost", 1,
			"steps", List.of(Map.of(
				"id", "wait_for_bread",
				"watch", Map.of(
					"fact", "inventory.item",
					"itemId", "minecraft:bread",
					"countAtLeast", 1,
					"timeoutTicks", 20
				)
			))
		)));
		return new ActionsetIndex(Map.of(
			"wait_for_bread_action",
			new ActionsetEntry("wait_for_bread_action", ActionsetNamespace.BUILTIN, "test.yml", null, action)
		));
	}

	private static void assertTrace(List<ActionTraceEvent> trace, String eventType) {
		assertTrue(trace.stream().anyMatch(event -> eventType.equals(event.eventType())), () -> trace.toString());
	}

	private static final class RecordingDispatcher implements ActionGraphPrimitiveDispatcher {
		private final List<ActionPlanStep> dispatchedSteps = new ArrayList<>();

		@Override
		public ActionGraphPrimitiveDispatchResult dispatch(ActionPlanStep step) {
			dispatchedSteps.add(step);
			return ActionGraphPrimitiveDispatchResult.accepted(
				"task-" + dispatchedSteps.size(),
				Map.of("targetId", step.targetId())
			);
		}
	}
}
