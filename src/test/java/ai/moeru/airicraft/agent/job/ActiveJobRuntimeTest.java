package ai.moeru.airicraft.agent.job;

import ai.moeru.airicraft.agent.dialogue.DialogueIntent;
import ai.moeru.airicraft.agent.dialogue.DialogueIntentType;
import ai.moeru.airicraft.agent.dialogue.DialogueResponse;
import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.tasks.BlockBreakStepArgs;
import ai.moeru.airicraft.agent.tasks.BlockPlacementStepArgs;
import ai.moeru.airicraft.agent.tasks.BlockUseStepArgs;
import ai.moeru.airicraft.agent.tasks.CraftRecipeStepArgs;
import ai.moeru.airicraft.agent.tasks.CollectSmeltedItemsStepArgs;
import ai.moeru.airicraft.agent.tasks.DropItemsStepArgs;
import ai.moeru.airicraft.agent.tasks.EntityInteractionStepArgs;
import ai.moeru.airicraft.agent.tasks.EntitySelector;
import ai.moeru.airicraft.agent.tasks.LedgerStep;
import ai.moeru.airicraft.agent.tasks.LedgerStepKind;
import ai.moeru.airicraft.agent.tasks.LedgerStepPayload;
import ai.moeru.airicraft.agent.tasks.LedgerStepStatus;
import ai.moeru.airicraft.agent.tasks.MissionType;
import ai.moeru.airicraft.agent.tasks.ReturnToSurfaceStepArgs;
import ai.moeru.airicraft.agent.tasks.SmeltItemsStepArgs;
import ai.moeru.airicraft.agent.tasks.SmeltingFuelMode;
import ai.moeru.airicraft.agent.tasks.TaskLedger;
import ai.moeru.airicraft.agent.tasks.WorldTaskRequest;
import ai.moeru.airicraft.agent.tasks.WorldTaskType;
import ai.moeru.airicraft.agent.tasks.TaskExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskExecutionState;
import ai.moeru.airicraft.agent.tasks.TaskResourceKind;
import ai.moeru.airicraft.agent.tasks.TaskSpec;
import ai.moeru.airicraft.agent.tasks.TaskTerminalEvent;
import ai.moeru.airicraft.agent.tasks.TaskTerminationCause;
import ai.moeru.airicraft.agent.tasks.TaskType;
import ai.moeru.airicraft.agent.tasks.WorldEvidence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveJobRuntimeTest {
	@Test
	void minedBlockEventWithoutActiveMineJobIsIgnored() {
		ActiveJobRuntime runtime = new ActiveJobRuntime();

		assertTrue(runtime.recordMinedBlock("minecraft:dirt", 1L).isEmpty());
		assertEquals(ActiveJobStatus.IDLE, runtime.current().status());
	}

	@Test
	void mineBlocksProgressCountsOnlyMatchingBreakEvents() {
		ActiveJobRuntime runtime = new ActiveJobRuntime();
		runtime.applyPlannerResponse(
			new DialogueResponse(
				"Mining dirt.",
				new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.mineBlocks(new GoalMineSpec(List.of("minecraft:dirt"), 2))),
				1L
			),
			0,
			"test",
			1L
		);

		assertTrue(runtime.recordMinedBlock("minecraft:stone", 2L).isEmpty());
		assertEquals(0, runtime.current().collectedCount());
		assertTrue(runtime.recordMinedBlock("minecraft:dirt", 3L).isEmpty());
		assertEquals(1, runtime.current().collectedCount());
		Optional<TaskTerminalEvent> completed = runtime.recordMinedBlock("minecraft:dirt", 4L);

		assertTrue(completed.isPresent());
		assertEquals(TaskExecutionState.COMPLETED, completed.orElseThrow().terminalState());
		assertEquals("Mined requested blocks brokenBlocks=2 requestedBlocks=2", completed.orElseThrow().message());
		assertEquals(ActiveJobStatus.COMPLETED, runtime.current().status());
		assertTrue(runtime.activeTaskRequest().isEmpty());
	}

	@Test
	void mineBlocksIgnoresInventoryIncreaseAndRestartsAfterEarlyBaritoneCompletion() {
		ActiveJobRuntime runtime = new ActiveJobRuntime();
		runtime.applyPlannerResponse(
			new DialogueResponse(
				"Mining dirt.",
				new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.mineBlocks(new GoalMineSpec(List.of("minecraft:dirt"), 3))),
				1L
			),
			0,
			"test",
			1L
		);

		runtime.tick(TaskExecutionSnapshot.idle(), evidence(Map.of("minecraft:dirt", 0), 2L), true, true, 2L);
		WorldTaskRequest firstAttempt = runtime.activeTaskRequest().orElseThrow();
		TaskExecutionSnapshot completedBeforeBreaks = new TaskExecutionSnapshot(
			TaskExecutionState.COMPLETED,
			firstAttempt.taskId(),
			firstAttempt.goal(),
			null,
			"AT_GOAL",
			null,
			TaskTerminationCause.GOAL_REACHED
		);

		runtime.tick(completedBeforeBreaks, evidence(Map.of("minecraft:dirt", 3), 3L), true, true, 3L);
		WorldTaskRequest secondAttempt = runtime.activeTaskRequest().orElseThrow();

		assertEquals(ActiveJobStatus.RUNNING, runtime.current().status());
		assertEquals(0, runtime.current().collectedCount());
		assertTrue(secondAttempt.taskId().endsWith(":mine:2"));
		assertEquals(6, secondAttempt.goal().mineSpec().quantity());
	}

	@Test
	void mineBlocksRestartsAfterPartialBreakCountAndEarlyBaritoneCompletion() {
		ActiveJobRuntime runtime = new ActiveJobRuntime();
		runtime.applyPlannerResponse(
			new DialogueResponse(
				"Mining dirt.",
				new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.mineBlocks(new GoalMineSpec(List.of("minecraft:dirt"), 3))),
				1L
			),
			0,
			"test",
			1L
		);
		runtime.tick(TaskExecutionSnapshot.idle(), evidence(Map.of("minecraft:dirt", 0), 2L), true, true, 2L);
		WorldTaskRequest firstAttempt = runtime.activeTaskRequest().orElseThrow();

		runtime.recordMinedBlock("minecraft:dirt", 3L);
		TaskExecutionSnapshot completedAfterOneBreak = new TaskExecutionSnapshot(
			TaskExecutionState.COMPLETED,
			firstAttempt.taskId(),
			firstAttempt.goal(),
			null,
			"AT_GOAL",
			null,
			TaskTerminationCause.GOAL_REACHED
		);
		runtime.tick(completedAfterOneBreak, evidence(Map.of("minecraft:dirt", 1), 4L), true, true, 4L);
		WorldTaskRequest secondAttempt = runtime.activeTaskRequest().orElseThrow();

		assertEquals(1, runtime.current().collectedCount());
		assertTrue(secondAttempt.taskId().endsWith(":mine:2"));
		assertEquals(3, secondAttempt.goal().mineSpec().quantity());
	}

	@Test
	void ensureBlocksInInventoryUsesAbsoluteInventoryTarget() {
		ActiveJobRuntime runtime = new ActiveJobRuntime();
		GoalMineSpec mineSpec = new GoalMineSpec(List.of("minecraft:dirt"), 3);
		runtime.applyPlannerResponse(
			new DialogueResponse(
				"Ensuring dirt.",
				new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.ensureBlocksInInventory(mineSpec)),
				1L
			),
			0,
			"test",
			1L
		);

		WorldTaskRequest request = runtime.activeTaskRequest().orElseThrow();

		assertEquals(ActiveJobType.ENSURE_BLOCKS_IN_INVENTORY, runtime.current().type());
		assertEquals(GoalType.MINE_BLOCKS, request.goal().type());
		assertEquals(mineSpec, request.goal().mineSpec());
		assertTrue(runtime.recordMinedBlock("minecraft:dirt", 2L).isEmpty());
		assertTrue(runtime.recordMinedBlock("minecraft:stone", 3L).isEmpty());
		assertEquals(1, runtime.current().collectedCount());
		assertNotEquals(ActiveJobStatus.COMPLETED, runtime.current().status());
	}

	@Test
	void ensureBlocksInInventoryCompletesFromMappedDropInventory() {
		ActiveJobRuntime runtime = new ActiveJobRuntime();
		GoalMineSpec mineSpec = new GoalMineSpec(List.of("minecraft:iron_ore"), 3);
		runtime.applyPlannerResponse(
			new DialogueResponse(
				"Ensuring iron.",
				new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.ensureBlocksInInventory(mineSpec)),
				1L
			),
			0,
			"test",
			1L
		);

		runtime.tick(TaskExecutionSnapshot.idle(), evidence(Map.of("minecraft:raw_iron", 3), 2L), true, true, 2L);

		assertEquals(ActiveJobStatus.COMPLETED, runtime.current().status());
		assertTrue(runtime.activeTaskRequest().isEmpty());
	}

	@Test
	void ensureBlocksInInventoryTerminalReportIncludesBrokenBlockCount() {
		ActiveJobRuntime runtime = new ActiveJobRuntime();
		runtime.applyPlannerResponse(
			new DialogueResponse(
				"Ensuring dirt.",
				new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.ensureBlocksInInventory(new GoalMineSpec(List.of("minecraft:dirt"), 3))),
				1L
			),
			0,
			"test",
			1L
		);
		WorldTaskRequest request = runtime.activeTaskRequest().orElseThrow();
		runtime.recordMinedBlock("minecraft:dirt", 2L);

		ActiveJobRuntime.TerminalTaskReport report = runtime.reportTerminalTaskEvent(
			new TaskTerminalEvent(
				request.taskId(),
				request.goal(),
				TaskExecutionState.COMPLETED,
				"Goal reached",
				TaskTerminationCause.GOAL_REACHED
			),
			Optional.of(request)
		);

		assertTrue(report.event().isEmpty());
		assertTrue(report.warning().orElseThrow().contains("inventory_target_not_satisfied"));
		assertTrue(report.warning().orElseThrow().contains("brokenBlocks=1"));
		assertTrue(report.warning().orElseThrow().contains("itemCount=0"));
		assertTrue(report.warning().orElseThrow().contains("requestedItemCount=3"));
	}

	@Test
	void mineBlocksCompletedTerminalMismatchReportsWarningOnly() {
		ActiveJobRuntime runtime = new ActiveJobRuntime();
		runtime.applyPlannerResponse(
			new DialogueResponse(
				"Mining dirt.",
				new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.mineBlocks(new GoalMineSpec(List.of("minecraft:dirt"), 3))),
				1L
			),
			0,
			"test",
			1L
		);
		runtime.tick(TaskExecutionSnapshot.idle(), evidence(Map.of("minecraft:dirt", 0), 2L), true, true, 2L);
		WorldTaskRequest request = runtime.activeTaskRequest().orElseThrow();

		ActiveJobRuntime.TerminalTaskReport report = runtime.reportTerminalTaskEvent(
			new TaskTerminalEvent(
				request.taskId(),
				request.goal(),
				TaskExecutionState.COMPLETED,
				"Goal reached",
				TaskTerminationCause.GOAL_REACHED
			),
			Optional.of(request)
		);

		assertTrue(report.event().isEmpty());
		assertTrue(report.warning().orElseThrow().contains("broken_block_count_mismatch"));
		assertTrue(report.warning().orElseThrow().contains("brokenBlocks=0"));
		assertTrue(report.warning().orElseThrow().contains("requestedBlocks=3"));
	}

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
	void returnToSurfaceActiveJobProjectsWorldTaskRequest() {
		ActiveJobRuntime runtime = new ActiveJobRuntime();
		ReturnToSurfaceStepArgs returnToSurface = new ReturnToSurfaceStepArgs(
			new GoalPosition(12, 70, -8, false),
			"nearest_surface",
			true,
			List.of("minecraft:dirt")
		);

		runtime.applyPlannerResponse(
			new DialogueResponse(
				"Returning to surface.",
				new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.returnToSurface(returnToSurface)),
				1L
			),
			0,
			"test",
			1L
		);

		WorldTaskRequest request = runtime.activeTaskRequest().orElseThrow();

		assertEquals(WorldTaskType.RETURN_TO_SURFACE, request.type());
		assertEquals(returnToSurface, request.returnToSurface());
		assertNull(request.goal());
		assertEquals(ActiveJobType.RETURN_TO_SURFACE, runtime.current().type());
		assertEquals(returnToSurface, runtime.current().returnToSurface());
	}

	@Test
	void blockInteractionActiveJobsProjectWorldTaskRequests() {
		ActiveJobRuntime placeRuntime = new ActiveJobRuntime();
		BlockPlacementStepArgs place = new BlockPlacementStepArgs(
			"minecraft:dirt",
			new GoalPosition(1, 64, 2, true),
			"down",
			"air_or_replaceable"
		);

		placeRuntime.applyPlannerResponse(
			new DialogueResponse(
				"Placing dirt.",
				new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.placeBlock(place)),
				1L
			),
			0,
			"test",
			1L
		);

		WorldTaskRequest placeRequest = placeRuntime.activeTaskRequest().orElseThrow();
		assertEquals(WorldTaskType.PLACE_BLOCK, placeRequest.type());
		assertEquals(place, placeRequest.blockPlacement());
		assertEquals(ActiveJobType.PLACE_BLOCK, placeRuntime.current().type());

		ActiveJobRuntime useRuntime = new ActiveJobRuntime();
		BlockUseStepArgs use = new BlockUseStepArgs(
			"minecraft:wheat_seeds",
			new GoalPosition(1, 65, 2, true),
			"down",
			List.of("minecraft:farmland"),
			"air"
		);

		useRuntime.applyPlannerResponse(
			new DialogueResponse(
				"Planting seeds.",
				new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.useBlock(use)),
				2L
			),
			0,
			"test",
			2L
		);

		WorldTaskRequest useRequest = useRuntime.activeTaskRequest().orElseThrow();
		assertEquals(WorldTaskType.USE_BLOCK, useRequest.type());
		assertEquals(use, useRequest.blockUse());
		assertEquals(ActiveJobType.USE_BLOCK, useRuntime.current().type());
	}

	@Test
	void useBlockLedgerStepProjectsWorldTaskRequest() {
		ActiveJobRuntime runtime = new ActiveJobRuntime();
		BlockUseStepArgs use = new BlockUseStepArgs(
			"minecraft:water_bucket",
			new GoalPosition(-15, 63, -40, true),
			"up",
			List.of(),
			"air"
		);
		TaskLedger ledger = new TaskLedger(
			"water-hole",
			MissionType.CRAFT_ITEM,
			"Place water in the irrigation hole.",
			List.of(new LedgerStep(
				"place-water",
				LedgerStepKind.USE_BLOCK,
				new LedgerStepPayload(null, null, null, null, null, null, null, null, use, null, null, null, null, null),
				List.of(),
				LedgerStepStatus.PENDING,
				List.of(),
				0,
				null
			)),
			"place-water",
			List.of(),
			null,
			null
		);

		runtime.submitMissionLedger(ledger, 0, "test", 1L);

		WorldTaskRequest request = runtime.activeTaskRequest().orElseThrow();
		assertEquals(WorldTaskType.USE_BLOCK, request.type());
		assertEquals(use, request.blockUse());
		assertEquals(ActiveJobType.USE_BLOCK, runtime.current().type());
	}

	@Test
	void batchedBlockInteractionActiveJobsProjectWorldTaskRequests() {
		ActiveJobRuntime placeRuntime = new ActiveJobRuntime();
		BlockPlacementStepArgs place = new BlockPlacementStepArgs(
			"minecraft:dirt",
			List.of(
				new BlockPlacementStepArgs.Target(new GoalPosition(1, 64, 2, true), "down", "air_or_replaceable"),
				new BlockPlacementStepArgs.Target(new GoalPosition(2, 64, 2, true), "north", "air")
			)
		);

		placeRuntime.applyPlannerResponse(
			new DialogueResponse(
				"Placing dirt.",
				new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.placeBlock(place)),
				1L
			),
			0,
			"test",
			1L
		);

		WorldTaskRequest placeRequest = placeRuntime.activeTaskRequest().orElseThrow();
		assertEquals(WorldTaskType.PLACE_BLOCK, placeRequest.type());
		assertEquals(place, placeRequest.blockPlacement());
		assertEquals(2, placeRequest.blockPlacement().targets().size());
		assertEquals(ActiveJobType.PLACE_BLOCK, placeRuntime.current().type());

		ActiveJobRuntime useRuntime = new ActiveJobRuntime();
		BlockUseStepArgs use = new BlockUseStepArgs(
			"minecraft:wheat_seeds",
			List.of(
				new BlockUseStepArgs.Target(new GoalPosition(1, 65, 2, true), "down", List.of("minecraft:farmland"), "air"),
				new BlockUseStepArgs.Target(new GoalPosition(2, 65, 2, true), "down", List.of("minecraft:farmland"), "air")
			)
		);

		useRuntime.applyPlannerResponse(
			new DialogueResponse(
				"Planting seeds.",
				new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.useBlock(use)),
				2L
			),
			0,
			"test",
			2L
		);

		WorldTaskRequest useRequest = useRuntime.activeTaskRequest().orElseThrow();
		assertEquals(WorldTaskType.USE_BLOCK, useRequest.type());
		assertEquals(use, useRequest.blockUse());
		assertEquals(2, useRequest.blockUse().targets().size());
		assertEquals(ActiveJobType.USE_BLOCK, useRuntime.current().type());
	}

	@Test
	void breakBlocksLedgerStepProjectsWorldTaskRequest() {
		ActiveJobRuntime runtime = new ActiveJobRuntime();
		BlockBreakStepArgs blockBreak = new BlockBreakStepArgs(List.of(new BlockBreakStepArgs.Target(
			new GoalPosition(-15, 63, -40, true),
			List.of("minecraft:grass_block")
		)));
		TaskLedger ledger = new TaskLedger(
			"break-center",
			MissionType.CRAFT_ITEM,
			"Break the irrigation hole.",
			List.of(new LedgerStep(
				"break-hole",
				LedgerStepKind.BREAK_BLOCKS,
				new LedgerStepPayload(null, null, null, null, null, null, null, null, null, null, blockBreak, null, null, null),
				List.of(),
				LedgerStepStatus.PENDING,
				List.of(),
				0,
				null
			)),
			"break-hole",
			List.of(),
			null,
			null
		);

		runtime.submitMissionLedger(ledger, 0, "test", 1L);

		WorldTaskRequest request = runtime.activeTaskRequest().orElseThrow();
		assertEquals(WorldTaskType.BREAK_BLOCKS, request.type());
		assertEquals(blockBreak, request.blockBreak());
		assertEquals(ActiveJobType.BREAK_BLOCKS, runtime.current().type());
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

	private static WorldEvidence evidence(Map<String, Integer> itemCounts, long tick) {
		return new WorldEvidence(
			Map.of(),
			itemCounts,
			Map.of(),
			"minecraft:overworld",
			0,
			64,
			0,
			null,
			tick
		);
	}
}
