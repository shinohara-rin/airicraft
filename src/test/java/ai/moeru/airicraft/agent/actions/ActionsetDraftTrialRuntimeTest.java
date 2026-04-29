package ai.moeru.airicraft.agent.actions;

import ai.moeru.airicraft.agent.tasks.TaskExecutionState;
import ai.moeru.airicraft.agent.tasks.TaskTerminalEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionsetDraftTrialRuntimeTest {
	private static final ActionResolverContext CONTEXT = new ActionResolverContext(
		"world-a",
		"bot",
		"minecraft:overworld",
		100
	);

	@TempDir
	Path tempDir;

	@Test
	void expectedProducesAloneCannotPassTrial() throws IOException {
		ActionsetAuthoringService service = new ActionsetAuthoringService(tempDir.resolve("actionsets"));
		service.writeDraft("empty_bread", """
			version: 1
			actions:
			  empty_bread:
			    produces:
			      - fact: inventory.item
			        itemId: minecraft:bread
			        countAtLeast: 1
			    alternatives:
			      - id: claim_only
			        steps: []
			""");
		ActionsetDraftTrialRuntime trial = new ActionsetDraftTrialRuntime(service, step ->
			ActionGraphPrimitiveDispatchResult.accepted("task-1", Map.of()));

		ActionsetTrialSnapshot started = trial.start(ActionsetTrialSpec.inventoryItem(
			"empty_bread",
			"minecraft:bread",
			1,
			Map.of(),
			false,
			20
		), CONTEXT, 100);
		ActionsetTrialSnapshot finalSnapshot = trial.tick(input(Map.of(), null, 101));

		assertEquals(ActionsetTrialState.RUNNING, started.state());
		assertEquals(ActionsetTrialState.FAILED, finalSnapshot.state());
		assertFalse(finalSnapshot.passed());
		assertEquals("goal_not_satisfied", finalSnapshot.failureCode());
	}

	@Test
	void foregroundTrialRequiresExplicitMutationAllowance() throws IOException {
		ActionsetAuthoringService service = new ActionsetAuthoringService(tempDir.resolve("actionsets"));
		service.writeDraft("craft_bread", craftBreadDraft());
		ActionsetDraftTrialRuntime trial = new ActionsetDraftTrialRuntime(service, step ->
			ActionGraphPrimitiveDispatchResult.accepted("task-1", Map.of()));

		ActionsetTrialSnapshot snapshot = trial.start(ActionsetTrialSpec.inventoryItem(
			"craft_bread",
			"minecraft:bread",
			1,
			Map.of("minecraft:wheat", 3),
			false,
			20
		), CONTEXT, 100);

		assertEquals(ActionsetTrialState.FAILED, snapshot.state());
		assertEquals("world_mutation_denied", snapshot.failureCode());
	}

	@Test
	void trialPassesAfterPrimitiveTerminalAndObservedGoalFact() throws IOException {
		ActionsetAuthoringService service = new ActionsetAuthoringService(tempDir.resolve("actionsets"));
		service.writeDraft("craft_bread", craftBreadDraft());
		RecordingDispatcher dispatcher = new RecordingDispatcher();
		ActionsetDraftTrialRuntime trial = new ActionsetDraftTrialRuntime(service, dispatcher);

		trial.start(ActionsetTrialSpec.inventoryItem(
			"craft_bread",
			"minecraft:bread",
			1,
			Map.of("minecraft:wheat", 3),
			true,
			20
		), CONTEXT, 100);
		ActionsetTrialSnapshot waiting = trial.tick(input(Map.of("minecraft:wheat", 3), null, 101));
		ActionsetTrialSnapshot passed = trial.tick(input(
			Map.of("minecraft:wheat", 3, "minecraft:bread", 1),
			new TaskTerminalEvent("task-1", null, TaskExecutionState.COMPLETED, "crafted", null),
			102
		));

		assertEquals(ActionsetTrialState.RUNNING, waiting.state());
		assertEquals(ActionsetTrialState.PASSED, passed.state());
		assertTrue(passed.passed());
		assertEquals(1, dispatcher.steps.size());
		assertEquals("craft_bread", dispatcher.steps.getFirst().actionId());
		assertEquals(ActionsetDraftStatus.TRIAL_PASSED, service.draftSummary("craft_bread").status());
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

	private static String craftBreadDraft() {
		return """
			version: 1
			actions:
			  craft_bread:
			    produces:
			      - fact: inventory.item
			        itemId: minecraft:bread
			        countAtLeast: 1
			    alternatives:
			      - id: craft_from_wheat
			        guards:
			          - fact: inventory.item
			            itemId: minecraft:wheat
			            countAtLeast: 3
			        steps:
			          - id: craft_bread
			            primitive: craft_item
			            args:
			              itemId: minecraft:bread
			              quantity: 1
			""";
	}

	private static final class RecordingDispatcher implements ActionGraphPrimitiveDispatcher {
		private final List<ActionPlanStep> steps = new ArrayList<>();

		@Override
		public ActionGraphPrimitiveDispatchResult dispatch(ActionPlanStep step) {
			steps.add(step);
			return ActionGraphPrimitiveDispatchResult.accepted("task-" + steps.size(), Map.of("targetId", step.targetId()));
		}
	}
}
