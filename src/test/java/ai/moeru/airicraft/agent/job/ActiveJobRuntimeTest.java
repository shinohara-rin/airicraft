package ai.moeru.airicraft.agent.job;

import ai.moeru.airicraft.agent.dialogue.DialogueIntent;
import ai.moeru.airicraft.agent.dialogue.DialogueIntentType;
import ai.moeru.airicraft.agent.dialogue.DialogueResponse;
import ai.moeru.airicraft.agent.tasks.CraftRecipeStepArgs;
import ai.moeru.airicraft.agent.tasks.WorldTaskRequest;
import ai.moeru.airicraft.agent.tasks.WorldTaskType;
import ai.moeru.airicraft.agent.tasks.TaskExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskExecutionState;
import ai.moeru.airicraft.agent.tasks.TaskResourceKind;
import ai.moeru.airicraft.agent.tasks.TaskSpec;
import ai.moeru.airicraft.agent.tasks.TaskTerminationCause;
import ai.moeru.airicraft.agent.tasks.TaskType;
import ai.moeru.airicraft.agent.tasks.WorldEvidence;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveJobRuntimeTest {
	@Test
	void collectProgressDoesNotReplaceRunningPrimitiveMineTask() {
		ActiveJobRuntime runtime = new ActiveJobRuntime();
		runtime.submitTask(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 16), 4, "test", 1L);

		runtime.tick(TaskExecutionSnapshot.idle(), evidence(4, 2L), true, true, 2L);
		WorldTaskRequest firstAttempt = runtime.activeTaskRequest().orElseThrow();
		TaskExecutionSnapshot running = new TaskExecutionSnapshot(
			TaskExecutionState.RUNNING,
			firstAttempt.taskId(),
			firstAttempt.goal(),
			null,
			null,
			null,
			null
		);

		runtime.tick(running, evidence(5, 3L), true, true, 3L);
		WorldTaskRequest afterPickup = runtime.activeTaskRequest().orElseThrow();

		assertEquals(firstAttempt.taskId(), afterPickup.taskId());
		assertEquals(20, afterPickup.goal().mineSpec().quantity());
		assertEquals(TaskExecutionState.RUNNING, runtime.missionExecutionSnapshot().primitiveExecution().state());
		assertEquals(1, runtime.taskSnapshot().progress().collected());
		assertEquals(15, runtime.taskSnapshot().progress().remaining());
	}

	@Test
	void collectShortfallStartsNextPrimitiveAttemptAfterCompletion() {
		ActiveJobRuntime runtime = new ActiveJobRuntime();
		runtime.submitTask(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 16), 4, "test", 1L);

		runtime.tick(TaskExecutionSnapshot.idle(), evidence(4, 2L), true, true, 2L);
		WorldTaskRequest firstAttempt = runtime.activeTaskRequest().orElseThrow();
		TaskExecutionSnapshot completed = new TaskExecutionSnapshot(
			TaskExecutionState.COMPLETED,
			firstAttempt.taskId(),
			firstAttempt.goal(),
			null,
			"AT_GOAL",
			null,
			TaskTerminationCause.GOAL_REACHED
		);

		runtime.tick(completed, evidence(5, 3L), true, true, 3L);
		WorldTaskRequest secondAttempt = runtime.activeTaskRequest().orElseThrow();

		assertNotEquals(firstAttempt.taskId(), secondAttempt.taskId());
		assertTrue(secondAttempt.taskId().endsWith(":mine:2"));
		assertEquals(20, secondAttempt.goal().mineSpec().quantity());
	}

	@Test
	void collectAttemptUsesAbsoluteInventoryTargetWhenBaselineAlreadyHasLogs() {
		ActiveJobRuntime runtime = new ActiveJobRuntime();
		runtime.submitTask(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 5), 5, "test", 1L);

		runtime.tick(TaskExecutionSnapshot.idle(), evidence(5, 2L), true, true, 2L);
		WorldTaskRequest attempt = runtime.activeTaskRequest().orElseThrow();

		assertEquals(10, attempt.goal().mineSpec().quantity());
	}

	@Test
	void craftRecipeActiveJobProjectsWorldTaskRequest() {
		ActiveJobRuntime runtime = new ActiveJobRuntime();
		CraftRecipeStepArgs craftRecipe = new CraftRecipeStepArgs("minecraft:stick", 4);

		runtime.applyPlannerResponse(
			new DialogueResponse(
				"Crafting sticks.",
				new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.craftRecipe(craftRecipe)),
				1L
			),
			0,
			"test",
			1L
		);

		WorldTaskRequest request = runtime.activeTaskRequest().orElseThrow();

		assertEquals(WorldTaskType.CRAFT_RECIPE, request.type());
		assertEquals(craftRecipe, request.craftRecipe());
		assertNull(request.goal());
		assertEquals(ActiveJobType.CRAFT_RECIPE, runtime.current().type());
		assertEquals(craftRecipe, runtime.current().craftRecipe());
	}

	private static WorldEvidence evidence(int woodLogs, long tick) {
		return new WorldEvidence(
			Map.of(TaskResourceKind.WOOD_LOGS, woodLogs),
			Map.of("minecraft:oak_log", 4),
			"minecraft:overworld",
			0,
			64,
			0,
			null,
			tick
		);
	}
}
