package ai.moeru.airicraft.agent.job;

import ai.moeru.airicraft.agent.dialogue.DialogueIntent;
import ai.moeru.airicraft.agent.dialogue.DialogueIntentType;
import ai.moeru.airicraft.agent.dialogue.DialogueResponse;
import ai.moeru.airicraft.agent.tasks.CraftRecipeStepArgs;
import ai.moeru.airicraft.agent.tasks.CollectSmeltedItemsStepArgs;
import ai.moeru.airicraft.agent.tasks.DropItemsStepArgs;
import ai.moeru.airicraft.agent.tasks.EntityInteractionStepArgs;
import ai.moeru.airicraft.agent.tasks.EntitySelector;
import ai.moeru.airicraft.agent.tasks.SmeltItemsStepArgs;
import ai.moeru.airicraft.agent.tasks.SmeltingFuelMode;
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
	void clearGoalDoesNotCancelCompletedCollectJob() {
		ActiveJobRuntime runtime = new ActiveJobRuntime();
		runtime.submitTask(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 5), 3, "test", 1L);

		runtime.tick(TaskExecutionSnapshot.idle(), evidence(8, 2L), true, true, 2L);
		assertEquals(ActiveJobStatus.COMPLETED, runtime.current().status());

		runtime.applyPlannerResponse(
			new DialogueResponse(
				"Done.",
				new DialogueIntent(DialogueIntentType.CLEAR_GOAL, null, null),
				3L
			),
			8,
			"planner_response",
			3L
		);

		assertEquals(ActiveJobStatus.COMPLETED, runtime.current().status());
		assertNull(runtime.current().lastError());
	}

	@Test
	void craftRecipeActiveJobProjectsWorldTaskRequest() {
		ActiveJobRuntime runtime = new ActiveJobRuntime();
		CraftRecipeStepArgs craftRecipe = new CraftRecipeStepArgs("oak_planks_x2_to_stick", 1);

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

	@Test
	void dropItemsActiveJobProjectsWorldTaskRequest() {
		ActiveJobRuntime runtime = new ActiveJobRuntime();
		DropItemsStepArgs dropItems = new DropItemsStepArgs("minecraft:oak_log", 2, "Alice");

		runtime.applyPlannerResponse(
			new DialogueResponse(
				"Dropping logs.",
				new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.dropItems(dropItems)),
				1L
			),
			0,
			"test",
			1L
		);

		WorldTaskRequest request = runtime.activeTaskRequest().orElseThrow();

		assertEquals(WorldTaskType.DROP_ITEMS, request.type());
		assertEquals(dropItems, request.dropItems());
		assertNull(request.goal());
		assertEquals(ActiveJobType.DROP_ITEMS, runtime.current().type());
		assertEquals(dropItems, runtime.current().dropItems());
	}

	@Test
	void smeltItemsActiveJobProjectsWorldTaskRequest() {
		ActiveJobRuntime runtime = new ActiveJobRuntime();
		SmeltItemsStepArgs smeltItems = new SmeltItemsStepArgs(
			"smelt:iron:nearby-1",
			3,
			SmeltingFuelMode.MANUAL,
			"minecraft:coal",
			1,
			"confirm-1"
		);

		runtime.applyPlannerResponse(
			new DialogueResponse(
				"Starting iron smelting.",
				new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.smeltItems(smeltItems)),
				1L
			),
			0,
			"test",
			1L
		);

		WorldTaskRequest request = runtime.activeTaskRequest().orElseThrow();

		assertEquals(WorldTaskType.SMELT_ITEMS, request.type());
		assertEquals(smeltItems, request.smeltItems());
		assertNull(request.goal());
		assertEquals(ActiveJobType.SMELT_ITEMS, runtime.current().type());
		assertEquals(smeltItems, runtime.current().smeltItems());
	}

	@Test
	void collectSmeltedItemsActiveJobProjectsWorldTaskRequest() {
		ActiveJobRuntime runtime = new ActiveJobRuntime();
		CollectSmeltedItemsStepArgs collect = new CollectSmeltedItemsStepArgs("smelt-process-1", "confirm-2");

		runtime.applyPlannerResponse(
			new DialogueResponse(
				"Collecting furnace output.",
				new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.collectSmeltedItems(collect)),
				1L
			),
			0,
			"test",
			1L
		);

		WorldTaskRequest request = runtime.activeTaskRequest().orElseThrow();

		assertEquals(WorldTaskType.COLLECT_SMELTED_ITEMS, request.type());
		assertEquals(collect, request.collectSmeltedItems());
		assertNull(request.goal());
		assertEquals(ActiveJobType.COLLECT_SMELTED_ITEMS, runtime.current().type());
		assertEquals(collect, runtime.current().collectSmeltedItems());
	}

	@Test
	void attackEntityActiveJobProjectsWorldTaskRequest() {
		ActiveJobRuntime runtime = new ActiveJobRuntime();
		EntityInteractionStepArgs attack = new EntityInteractionStepArgs(
			new EntitySelector(null, null, "minecraft:sheep"),
			null
		);

		runtime.applyPlannerResponse(
			new DialogueResponse(
				"Attack the sheep.",
				new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.attackEntity(attack)),
				1L
			),
			0,
			"test",
			1L
		);

		WorldTaskRequest request = runtime.activeTaskRequest().orElseThrow();

		assertEquals(WorldTaskType.ATTACK_ENTITY, request.type());
		assertEquals(attack, request.entityInteraction());
		assertNull(request.goal());
		assertEquals(ActiveJobType.ATTACK_ENTITY, runtime.current().type());
		assertEquals(attack, runtime.current().entityInteraction());
	}

	@Test
	void dropItemsPrimitiveJobIgnoresCompanionSessionGate() {
		ActiveJobRuntime runtime = new ActiveJobRuntime();
		DropItemsStepArgs dropItems = new DropItemsStepArgs("minecraft:oak_log", 2, null);
		runtime.applyPlannerResponse(
			new DialogueResponse(
				"Dropping logs.",
				new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.dropItems(dropItems)),
				1L
			),
			0,
			"test",
			1L
		);
		WorldTaskRequest request = runtime.activeTaskRequest().orElseThrow();
		TaskExecutionSnapshot runningDrop = new TaskExecutionSnapshot(
			TaskExecutionState.RUNNING,
			request.taskId(),
			null,
			null,
			"inventory_screen_dismissed",
			null,
			null
		);

		runtime.tick(runningDrop, evidence(4, 2L), false, false, 2L);

		assertEquals(ActiveJobStatus.RUNNING, runtime.current().status());
		assertNull(runtime.current().blockedReason());
		assertEquals("inventory_screen_dismissed", runtime.missionExecutionSnapshot().primitiveExecution().lastPathEvent());
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
