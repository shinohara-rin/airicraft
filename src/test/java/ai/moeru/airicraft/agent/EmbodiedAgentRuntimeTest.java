package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.AiricraftConfig;
import ai.moeru.airicraft.FirstPersonScreenshotService;
import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.dialogue.DialogueIntent;
import ai.moeru.airicraft.agent.dialogue.DialogueIntentType;
import ai.moeru.airicraft.agent.dialogue.DialogueResponse;
import ai.moeru.airicraft.agent.debug.AgentDebugTimelineEntry;
import ai.moeru.airicraft.agent.events.EventRoutingProfile;
import ai.moeru.airicraft.agent.events.SemanticEvent;
import ai.moeru.airicraft.agent.job.ActiveJob;
import ai.moeru.airicraft.agent.job.ActiveJobProposal;
import ai.moeru.airicraft.agent.job.ActiveJobStatus;
import ai.moeru.airicraft.agent.job.ActiveJobType;
import ai.moeru.airicraft.agent.session.SessionMode;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import ai.moeru.airicraft.agent.tasks.WorldTaskRequest;
import ai.moeru.airicraft.agent.tasks.BlockPlacementStepArgs;
import ai.moeru.airicraft.agent.tasks.CollectResourceStepArgs;
import ai.moeru.airicraft.agent.tasks.CraftRecipeStepArgs;
import ai.moeru.airicraft.agent.tasks.CollectSmeltedItemsStepArgs;
import ai.moeru.airicraft.agent.tasks.DropItemsStepArgs;
import ai.moeru.airicraft.agent.tasks.EvidenceKind;
import ai.moeru.airicraft.agent.tasks.EvidenceRequirement;
import ai.moeru.airicraft.agent.tasks.EntityAttackMode;
import ai.moeru.airicraft.agent.tasks.EntityInteractionStepArgs;
import ai.moeru.airicraft.agent.tasks.EntitySelector;
import ai.moeru.airicraft.agent.tasks.FinishStepArgs;
import ai.moeru.airicraft.agent.tasks.LedgerStep;
import ai.moeru.airicraft.agent.tasks.LedgerStepKind;
import ai.moeru.airicraft.agent.tasks.LedgerStepPayload;
import ai.moeru.airicraft.agent.tasks.LedgerStepStatus;
import ai.moeru.airicraft.agent.tasks.MissionType;
import ai.moeru.airicraft.agent.tasks.ReturnToSurfaceStepArgs;
import ai.moeru.airicraft.agent.tasks.SmeltItemsStepArgs;
import ai.moeru.airicraft.agent.tasks.SmeltingFuelMode;
import ai.moeru.airicraft.agent.tasks.SmeltingOption;
import ai.moeru.airicraft.agent.tasks.SmeltingSlotSnapshot;
import ai.moeru.airicraft.agent.tasks.SmeltingStationCandidate;
import ai.moeru.airicraft.agent.tasks.SmeltingStationKey;
import ai.moeru.airicraft.agent.tasks.SmeltingStationKind;
import ai.moeru.airicraft.agent.tasks.SmeltingStationObservation;
import ai.moeru.airicraft.agent.tasks.SmeltingStationSource;
import ai.moeru.airicraft.agent.tasks.SmeltingStationState;
import ai.moeru.airicraft.agent.tasks.TaskLedger;
import ai.moeru.airicraft.agent.tasks.TaskExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskExecutionState;
import ai.moeru.airicraft.agent.tasks.TaskResourceKind;
import ai.moeru.airicraft.agent.tasks.TaskSpec;
import ai.moeru.airicraft.agent.tasks.TaskState;
import ai.moeru.airicraft.agent.tasks.TaskTerminationCause;
import ai.moeru.airicraft.agent.tasks.TaskType;
import ai.moeru.airicraft.agent.tasks.TaskTerminalEvent;
import ai.moeru.airicraft.agent.tasks.WorldTaskExecutor;
import ai.moeru.airicraft.agent.tasks.WorldTaskType;
import ai.moeru.airicraft.agent.tasks.WorldEvidence;
import ai.moeru.airicraft.agent.llm.PlannerToolCall;
import ai.moeru.airicraft.agent.llm.PlannerTrigger;
import ai.moeru.airicraft.agent.llm.PlannerTriggerType;
import com.google.gson.JsonParser;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbodiedAgentRuntimeTest {
	@Test
	void suppressesRecentEchoOfAgentOwnPublicChat() {
		assertTrue(EmbodiedAgentRuntime.isAgentChatEcho(
			"Player918",
			"hello there",
			"Player918",
			"hello there",
			120L,
			100L
		));
	}

	@Test
	void doesNotSuppressDifferentOrStaleChat() {
		assertFalse(EmbodiedAgentRuntime.isAgentChatEcho(
			"Player918",
			"follow me",
			"Player918",
			"hello there",
			120L,
			100L
		));
		assertFalse(EmbodiedAgentRuntime.isAgentChatEcho(
			"Player918",
			"hello there",
			"Player918",
			"hello there",
			200L,
			100L
		));
		assertFalse(EmbodiedAgentRuntime.isAgentChatEcho(
			"magpie",
			"hello there",
			"Player918",
			"hello there",
			120L,
			100L
		));
	}

	@Test
	void detectsLocalControllerMessagesByMatchingClientPlayerName() {
		assertTrue(EmbodiedAgentRuntime.isLocalControllerMessage("Player918", "Player918"));
		assertFalse(EmbodiedAgentRuntime.isLocalControllerMessage("magpie", "Player918"));
		assertFalse(EmbodiedAgentRuntime.isLocalControllerMessage(null, "Player918"));
	}

	@Test
	void idleIdeaSchedulingTreatsTerminalJobsAsIdle() {
		ActiveJob running = new ActiveJob(
			"job-running",
			ActiveJobType.COLLECT_RESOURCE,
			ActiveJobStatus.RUNNING,
			null,
			null,
			null,
			null,
			-1L,
			0,
			0,
			"test",
			null,
			null,
			1L
		);
		ActiveJob completed = new ActiveJob(
			"job-completed",
			ActiveJobType.COLLECT_RESOURCE,
			ActiveJobStatus.COMPLETED,
			null,
			null,
			null,
			null,
			-1L,
			0,
			0,
			"test",
			null,
			null,
			2L
		);

		assertFalse(EmbodiedAgentRuntime.isIdleForIdleIdeaScheduling(running));
		assertTrue(EmbodiedAgentRuntime.isIdleForIdleIdeaScheduling(completed));
		assertTrue(EmbodiedAgentRuntime.isIdleForIdleIdeaScheduling(ActiveJob.idle()));
	}

	@Test
	void fallsBackToLastKnownPlayerHealthWhenObservedHealthAlreadyDropped() {
		float effective = EmbodiedAgentRuntime.effectiveHealthBefore(20.0F, 19.0F, 19.0F);

		assertEquals(20.0F, effective);
	}

	@Test
	void keepsObservedHealthBeforeWhenNoHigherBaselineExists() {
		assertEquals(19.0F, EmbodiedAgentRuntime.effectiveHealthBefore(null, 19.0F, 19.0F));
		assertEquals(19.0F, EmbodiedAgentRuntime.effectiveHealthBefore(18.0F, 19.0F, 19.0F));
	}

	@Test
	void taskExecutorPausesWhenSessionDoesNotAllowActuation() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);

		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.SINGLEPLAYER_LOCAL,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));
		runtime.injectGoalForTests(new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(1, 64, 1, true),
			null,
			10L,
			"test"
		));

		runtime.onClientTick(null);

		assertEquals(TaskExecutionState.PAUSED_BY_SESSION_GATE, runtime.taskExecutionSnapshot().state());
	}

	@Test
	void craftRecipePlannerResponseRoutesWorldTaskRequest() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));
		CraftRecipeStepArgs craftRecipe = new CraftRecipeStepArgs("oak_planks_x2_to_stick", 1);

		runtime.injectDialogueResponseForTests(new DialogueResponse(
			"Crafting sticks.",
			new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.craftRecipe(craftRecipe)),
			20L
		));
		runtime.onClientTick(null);

		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		assertEquals(WorldTaskType.CRAFT_RECIPE, request.type());
		assertEquals(craftRecipe, request.craftRecipe());
		assertEquals(TaskState.RUNNING, runtime.taskSnapshot().state());
	}

	@Test
	void dropItemsPlannerResponseRoutesWorldTaskRequest() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());
		DropItemsStepArgs dropItems = new DropItemsStepArgs("minecraft:oak_log", 2, null);

		runtime.injectDialogueResponseForTests(new DialogueResponse(
			"Dropping logs.",
			new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.dropItems(dropItems)),
			20L
		));
		runtime.onClientTick(null);

		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		assertEquals(WorldTaskType.DROP_ITEMS, request.type());
		assertEquals(dropItems, request.dropItems());
		assertEquals(TaskState.RUNNING, runtime.taskSnapshot().state());
	}

	@Test
	void dropItemsToolRoutesWorldTaskRequest() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));

		String result = runtime.executePlannerToolCallForTests(new PlannerToolCall(
			"call_drop",
			"drop_items",
			JsonParser.parseString("""
				{"itemId":"minecraft:oak_log","quantity":2}
				""").getAsJsonObject(),
			null,
			null
		));
		runtime.onClientTick(null);

		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		assertTrue(result.contains("accepted"));
		assertTrue(result.contains("queued"));
		assertTrue(result.contains("does not mean completed"));
		assertTrue(result.contains("TASK UPDATE"));
		assertEquals(WorldTaskType.DROP_ITEMS, request.type());
		assertEquals(new DropItemsStepArgs("minecraft:oak_log", 2, null), request.dropItems());
	}

	@Test
	void smeltItemsToolRoutesWorldTaskRequest() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());
		runtime.registerSmeltingOptionsForTests(List.of(testSmeltingOption("smelt:iron:nearby-1", 3)));

		String result = runtime.executePlannerToolCallForTests(new PlannerToolCall(
				"call_smelt",
				"smelt_items",
				JsonParser.parseString("""
					{"optionId":"smelt:iron:nearby-1","inputQuantity":3,"fuelMode":"manual","fuelItemId":"minecraft:coal","fuelQuantity":1,"confirmationToken":"confirm-1"}
					""").getAsJsonObject(),
				null,
				null
			));
		assertTrue(result.contains("accepted"), result);
		runtime.onClientTick(null);

		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		assertTrue(result.contains("processId="));
		assertTrue(result.contains("does not mean completed"));
		assertEquals(WorldTaskType.SMELT_ITEMS, request.type());
		assertEquals(new SmeltItemsStepArgs(
			"smelt:iron:nearby-1",
			3,
			SmeltingFuelMode.MANUAL,
			"minecraft:coal",
			1,
			"confirm-1"
		), request.smeltItems());
	}

	@Test
	void mineBlocksToolResultWarnsPlannerToWaitForTaskUpdate() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));

		String result = runtime.executePlannerToolCallForTests(new PlannerToolCall(
				"call_mine",
				"mine_blocks",
				JsonParser.parseString("""
					{"blockIds":["minecraft:dirt"],"quantity":1}
					""").getAsJsonObject(),
				null,
				null
			));
		runtime.onClientTick(null);

		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		assertTrue(result.contains("accepted"));
		assertTrue(result.contains("queued"));
		assertTrue(result.contains("does not mean completed"));
		assertTrue(result.contains("TASK UPDATE"));
		assertEquals(WorldTaskType.MINE, request.type());
		assertEquals(new GoalMineSpec(List.of("minecraft:dirt"), 1), request.goal().mineSpec());
	}

	@Test
	void mineBlocksRejectsInventoryItemIdsBeforeStartingTask() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		String result = runtime.executePlannerToolCallForTests(new PlannerToolCall(
			"call_mine",
			"mine_blocks",
			JsonParser.parseString("""
				{"blockIds":["minecraft:raw_iron"],"quantity":1}
				""").getAsJsonObject(),
			null,
			null
		));
		runtime.onClientTick(null);

		assertTrue(result.contains("TOOL_ERROR: mine_blocks invalid_block_id minecraft:raw_iron"));
		assertTrue(result.contains("item id, not a block id"));
		assertTrue(executor.lastActiveTask.isEmpty());
		assertTrue(runtime.activeGoal().isEmpty());
	}

	@Test
	void mineBlocksIgnoresPickupEventsForCompletion() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		runtime.executePlannerToolCallForTests(new PlannerToolCall(
			"call_mine",
			"mine_blocks",
			JsonParser.parseString("""
				{"blockIds":["minecraft:dirt"],"quantity":3}
				""").getAsJsonObject(),
			null,
			null
		));
		runtime.onClientTick(null);

		runtime.onPlayerPickedUpItem("minecraft:dirt", 3);
		runtime.onPlayerMinedBlock("minecraft:dirt", 0, 64, 0);
		runtime.onPlayerMinedBlock("minecraft:dirt", 1, 64, 0);

		assertTrue(runtime.activeGoal().isPresent());
		assertFalse(runtime.recentEvents(null).events().stream().anyMatch(event -> "task.completed".equals(event.type())));

		runtime.onPlayerMinedBlock("minecraft:dirt", 2, 64, 0);

		assertTrue(runtime.activeGoal().isEmpty());
		assertTrue(runtime.recentEvents(null).events().stream().anyMatch(event -> "task.completed".equals(event.type())));
	}

	@Test
	void mineBlocksEarlyTerminalMismatchWarnsAndKeepsMining() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		runtime.executePlannerToolCallForTests(new PlannerToolCall(
			"call_mine",
			"mine_blocks",
			JsonParser.parseString("""
				{"blockIds":["minecraft:dirt"],"quantity":3}
				""").getAsJsonObject(),
			null,
			null
		));
		runtime.onClientTick(null);
		WorldTaskRequest firstAttempt = executor.lastActiveTask.orElseThrow();
		executor.nextTerminalEvent = Optional.of(new TaskTerminalEvent(
			firstAttempt.taskId(),
			firstAttempt.goal(),
			TaskExecutionState.COMPLETED,
			"Goal reached",
			TaskTerminationCause.GOAL_REACHED
		));

		runtime.onClientTick(null);

		assertFalse(runtime.recentEvents(null).events().stream().anyMatch(event -> "task.completed".equals(event.type())));
		assertTrue(runtime.dialogueSnapshot().recentTurns().stream().anyMatch(turn ->
			"system".equals(turn.speaker())
				&& turn.text().contains("TASK WARNING: mine_blocks broken_block_count_mismatch")
				&& turn.text().contains("brokenBlocks=0")
				&& turn.text().contains("requestedBlocks=3")
		));

		runtime.onClientTick(null);
		WorldTaskRequest secondAttempt = executor.lastActiveTask.orElseThrow();

		assertTrue(runtime.activeGoal().isPresent());
		assertTrue(secondAttempt.taskId().endsWith(":mine:2"));
		assertEquals(new GoalMineSpec(List.of("minecraft:dirt"), 3), secondAttempt.goal().mineSpec());
	}

	@Test
	void ensureBlocksInInventoryToolRoutesAbsoluteMineGoal() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		String result = runtime.executePlannerToolCallForTests(new PlannerToolCall(
				"call_ensure_blocks",
				"ensure_blocks_in_inventory",
				JsonParser.parseString("""
					{"blockIds":["minecraft:dirt"],"quantity":3}
					""").getAsJsonObject(),
				null,
				null
			));
		runtime.onClientTick(null);

		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		assertTrue(result.contains("accepted"));
		assertTrue(result.contains("queued"));
		assertTrue(result.contains("TASK UPDATE"));
		assertEquals(WorldTaskType.MINE, request.type());
		assertEquals(new GoalMineSpec(List.of("minecraft:dirt"), 3), request.goal().mineSpec());
	}

	@Test
	void returnToSurfaceToolRoutesWorldTaskRequestWithDefaultFillerAndTowering() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		String result = runtime.executePlannerToolCallForTests(new PlannerToolCall(
			"call_return",
			"return_to_surface",
			JsonParser.parseString("{}").getAsJsonObject(),
			null,
			null
		));
		runtime.onClientTick(null);

		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		assertTrue(result.contains("accepted"));
		assertTrue(result.contains("queued"));
		assertTrue(result.contains("useTowering=true"));
		assertEquals(WorldTaskType.RETURN_TO_SURFACE, request.type());
		assertTrue(request.returnToSurface().useTowering());
		assertEquals("none", request.returnToSurface().targetKind());
		assertEquals(ReturnToSurfaceStepArgs.DEFAULT_FILLER_BLOCK_IDS, request.returnToSurface().fillerBlockIds());
	}

	@Test
	void returnToSurfaceToolHonorsExplicitToweringFalse() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		String result = runtime.executePlannerToolCallForTests(new PlannerToolCall(
			"call_return",
			"return_to_surface",
			JsonParser.parseString("""
				{"useTowering":false}
				""").getAsJsonObject(),
			null,
			null
		));
		runtime.onClientTick(null);

		assertTrue(result.contains("TOOL_ERROR"));
		assertTrue(result.contains("surface_target_unavailable"));
		assertTrue(executor.lastActiveTask.isEmpty());
	}

	@Test
	void directPlannerJobUpdateDoesNotPreemptRunningReturnToSurface() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		String result = runtime.executePlannerToolCallForTests(new PlannerToolCall(
			"call_return",
			"return_to_surface",
			JsonParser.parseString("{}").getAsJsonObject(),
			null,
			null
		));
		runtime.onClientTick(null);
		assertTrue(result.contains("useTowering=true"));
		assertEquals(WorldTaskType.RETURN_TO_SURFACE, executor.lastActiveTask.orElseThrow().type());

		runtime.injectDialogueResponseForTests(new DialogueResponse(
			"",
			new DialogueIntent(
				DialogueIntentType.JOB_UPDATE,
				ActiveJobProposal.placeBlock(new BlockPlacementStepArgs(
					"minecraft:crafting_table",
					new GoalPosition(1, 64, 2, true),
					"auto",
					"air_or_replaceable"
				))
			),
			1L
		));
		runtime.onClientTick(null);

		assertEquals(ActiveJobType.RETURN_TO_SURFACE, runtime.activeJob().type());
		assertEquals(WorldTaskType.RETURN_TO_SURFACE, executor.lastActiveTask.orElseThrow().type());
	}

	@Test
	void plannerToolDoesNotPreemptRunningTaskExecutionWhenSemanticJobIsIdle() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());
		executor.forcedSnapshot = new TaskExecutionSnapshot(
			TaskExecutionState.RUNNING,
			"return-task",
			null,
			"ReturnToSurface",
			"towering:support_unavailable",
			null,
			null
		);
		runtime.onClientTick(null);
		runtime.recordWorldReadForTests(new BlockPos(1, 64, 2));

		String result = runtime.executePlannerToolCallForTests(new PlannerToolCall(
			"call_place",
			"place_block",
			JsonParser.parseString("""
				{"itemId":"minecraft:crafting_table","targets":[{"targetPosition":{"x":1,"y":64,"z":2,"exactY":true},"placementMode":"air_or_replaceable"}]}
				""").getAsJsonObject(),
			null,
			null
		));

		assertTrue(result.contains("TOOL_ERROR"));
		assertTrue(result.contains("active_task_in_progress"));
		assertTrue(result.contains("taskExecutionState=RUNNING"));
		assertEquals(ActiveJobType.IDLE, runtime.activeJob().type());
	}

	@Test
	void craftRecipeToolDoesNotPreemptRunningTaskExecutionWhenSemanticJobIsIdle() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());
		executor.forcedSnapshot = new TaskExecutionSnapshot(
			TaskExecutionState.RUNNING,
			"return-task",
			null,
			"ReturnToSurface",
			"towering:support_unavailable",
			null,
			null
		);
		runtime.onClientTick(null);

		String result = runtime.executePlannerToolCallFutureForTests(craftRecipeToolCall()).join();

		assertTrue(result.contains("TOOL_ERROR"));
		assertTrue(result.contains("active_task_in_progress"));
		assertTrue(result.contains("taskExecutionState=RUNNING"));
		assertEquals(ActiveJobType.IDLE, runtime.activeJob().type());
	}

	@Test
	void ensureBlocksInInventoryPrimitiveCompletionWarnsUntilInventorySatisfied() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		runtime.executePlannerToolCallForTests(new PlannerToolCall(
			"call_ensure_blocks",
			"ensure_blocks_in_inventory",
			JsonParser.parseString("""
				{"blockIds":["minecraft:dirt"],"quantity":3}
				""").getAsJsonObject(),
			null,
			null
		));
		runtime.onClientTick(null);
		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		runtime.onPlayerMinedBlock("minecraft:dirt", 0, 64, 0);
		executor.nextTerminalEvent = Optional.of(new TaskTerminalEvent(
			request.taskId(),
			request.goal(),
			TaskExecutionState.COMPLETED,
			"Goal reached",
			TaskTerminationCause.GOAL_REACHED
		));

		runtime.onClientTick(null);

		assertTrue(runtime.dialogueSnapshot().recentTurns().stream().anyMatch(turn ->
			"system".equals(turn.speaker())
				&& turn.text().contains("TASK WARNING: ensure_blocks_in_inventory inventory_target_not_satisfied")
				&& turn.text().contains("brokenBlocks=1")
				&& turn.text().contains("itemCount=0")
				&& turn.text().contains("requestedItemCount=3")
		));
		assertFalse(runtime.recentEvents(null).events().stream().anyMatch(event -> "task.completed".equals(event.type())));

		runtime.onClientTick(null);
		WorldTaskRequest secondAttempt = executor.lastActiveTask.orElseThrow();

		assertTrue(runtime.activeGoal().isPresent());
		assertTrue(secondAttempt.taskId().endsWith(":mine:2"), secondAttempt.taskId());
	}

	@Test
	void collectSmeltedItemsToolRoutesWorldTaskRequest() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());
		runtime.registerSmeltingOptionsForTests(List.of(testSmeltingOption("smelt:iron:nearby-1", 3)));
		String startResult = runtime.executePlannerToolCallForTests(new PlannerToolCall(
			"call_smelt",
			"smelt_items",
			JsonParser.parseString("""
				{"optionId":"smelt:iron:nearby-1","inputQuantity":1}
				""").getAsJsonObject(),
			null,
			null
		));
		String processId = extractProcessId(startResult);

		String result = runtime.executePlannerToolCallForTests(new PlannerToolCall(
			"call_collect_smelted",
			"collect_smelted_items",
			JsonParser.parseString("""
				{"processId":"%s","confirmationToken":"confirm-2"}
				""".formatted(processId)).getAsJsonObject(),
			null,
			null
		));
		runtime.onClientTick(null);

		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		assertTrue(result.contains("accepted"));
		assertTrue(result.contains("queued"));
		assertEquals(WorldTaskType.COLLECT_SMELTED_ITEMS, request.type());
		assertEquals(new CollectSmeltedItemsStepArgs(processId, "confirm-2"), request.collectSmeltedItems());
	}

	@Test
	void collectSmeltedItemsWithoutProcessUsesTrackedSmeltingProcess() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());
		runtime.registerSmeltingOptionsForTests(List.of(testSmeltingOption("smelt:iron:nearby-1", 3)));
		String startResult = runtime.executePlannerToolCallForTests(new PlannerToolCall(
			"call_smelt",
			"smelt_items",
			JsonParser.parseString("""
				{"optionId":"smelt:iron:nearby-1","inputQuantity":1}
				""").getAsJsonObject(),
			null,
			null
		));
		String processId = extractProcessId(startResult);

		String result = runtime.executePlannerToolCallForTests(new PlannerToolCall(
			"call_collect_smelted",
			"collect_smelted_items",
			JsonParser.parseString("""
				{}
				""").getAsJsonObject(),
			null,
			null
		));
		runtime.onClientTick(null);

		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		assertTrue(result.contains("accepted"));
		assertTrue(result.contains("processId=" + processId));
		assertEquals(new CollectSmeltedItemsStepArgs(processId, null), request.collectSmeltedItems());
	}

	@Test
	void smeltingOutputReadyEventCreatesSystemPlannerTrigger() {
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(new FakeWorldTaskExecutor());

		PlannerTrigger trigger = runtime.createPlannerTriggerForTests(new SemanticEvent(
			1L,
			20L,
			1000L,
			"smelting.output_ready",
			Map.of(
				"processId", "smelt-process-1",
				"optionId", "smelt:iron:nearby-1",
				"station", "minecraft:overworld@1,64,1",
				"outputItemId", "minecraft:iron_ingot",
				"outputCount", 1,
				"inputQuantity", 1
			)
		), new EventRoutingProfile("smelting.output_ready", true, PlannerTriggerType.SYSTEM, true));

		assertEquals(PlannerTriggerType.SYSTEM, trigger.type());
		assertEquals("runtime", trigger.speaker());
		assertEquals(
			"Smelting output ready: processId=smelt-process-1 output=minecraft:iron_ingotx1 station=minecraft:overworld@1,64,1.",
			trigger.text()
		);
	}

	@Test
	void finishEvaluationSuppressesAutonomousPlannerTriggersUntilNextEvaluationStarts() {
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(new FakeWorldTaskExecutor());

		runtime.finishEvaluation();
		PlannerTrigger suppressed = runtime.createPlannerTriggerForTests(new SemanticEvent(
			1L,
			20L,
			1000L,
			"smelting.output_ready",
			Map.of(
				"processId", "smelt-process-1",
				"station", "minecraft:overworld@1,64,1",
				"outputItemId", "minecraft:iron_ingot",
				"outputCount", 1
			)
		), new EventRoutingProfile("smelting.output_ready", true, PlannerTriggerType.SYSTEM, true));
		PlannerTrigger explicitChat = runtime.createPlannerTriggerForTests(new SemanticEvent(
			2L,
			21L,
			1001L,
			"social.player_addressed_agent",
			Map.of(
				"player", "Player",
				"message", "@agent are you there?"
			)
		), new EventRoutingProfile("social.player_addressed_agent", false, PlannerTriggerType.CHAT, true));

		assertNull(suppressed);
		assertEquals(PlannerTriggerType.CHAT, explicitChat.type());

		runtime.prepareForEvaluation();
		PlannerTrigger resumed = runtime.createPlannerTriggerForTests(new SemanticEvent(
			3L,
			22L,
			1002L,
			"smelting.output_ready",
			Map.of(
				"processId", "smelt-process-2",
				"station", "minecraft:overworld@1,64,1",
				"outputItemId", "minecraft:gold_ingot",
				"outputCount", 1
			)
		), new EventRoutingProfile("smelting.output_ready", true, PlannerTriggerType.SYSTEM, true));

		assertEquals(PlannerTriggerType.SYSTEM, resumed.type());
	}

	@Test
	void attackEntityToolRoutesWorldTaskRequest() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));

		String result = runtime.executePlannerToolCallForTests(new PlannerToolCall(
			"call_attack",
			"attack_entity",
			JsonParser.parseString("""
				{"entityTypeId":"minecraft:sheep","mode":"hit_once"}
				""").getAsJsonObject(),
			null,
			null
		));
		runtime.onClientTick(null);

		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		assertTrue(result.contains("accepted"));
		assertTrue(result.contains("queued"));
		assertTrue(result.contains("does not mean completed"));
		assertTrue(result.contains("TASK UPDATE"));
		assertEquals(WorldTaskType.ATTACK_ENTITY, request.type());
		assertEquals(new EntityInteractionStepArgs(
			new EntitySelector(null, null, "minecraft:sheep"),
			null,
			EntityAttackMode.HIT_ONCE
		), request.entityInteraction());
	}

	@Test
	void attackEntityToolResultUsesShortUuidToken() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));

		String result = runtime.executePlannerToolCallForTests(new PlannerToolCall(
			"call_attack",
			"attack_entity",
			JsonParser.parseString("""
				{"uuid":"12345678-aaaa-4d9d-8b9d-fb24b98cb81d"}
				""").getAsJsonObject(),
			null,
			null
		));
		runtime.onClientTick(null);

		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		assertTrue(result.contains("uuid=12345678"));
		assertFalse(result.contains("uuid=12345678-aaaa-4d9d-8b9d-fb24b98cb81d"));
		assertEquals(new EntityInteractionStepArgs(
			new EntitySelector("12345678-aaaa-4d9d-8b9d-fb24b98cb81d", null, null),
			null
		), request.entityInteraction());
	}

	@Test
	void useEntityToolRoutesWorldTaskRequest() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));

		String result = runtime.executePlannerToolCallForTests(new PlannerToolCall(
			"call_use",
			"use_entity",
			JsonParser.parseString("""
				{"name":"Dinner","itemId":"minecraft:shears"}
				""").getAsJsonObject(),
			null,
			null
		));
		runtime.onClientTick(null);

		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		assertTrue(result.contains("accepted"));
		assertTrue(result.contains("queued"));
		assertTrue(result.contains("does not mean completed"));
		assertTrue(result.contains("TASK UPDATE"));
		assertEquals(WorldTaskType.USE_ENTITY, request.type());
		assertEquals(new EntityInteractionStepArgs(
			new EntitySelector(null, "Dinner", null),
			"minecraft:shears"
		), request.entityInteraction());
	}

	@Test
	void blockModificationToolInspectsInsteadOfQueuingUnreadTarget() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		String result = runtime.executePlannerToolCallForTests(new PlannerToolCall(
			"call_place",
			"place_block",
			JsonParser.parseString("""
				{"itemId":"minecraft:dirt","x":1,"y":64,"z":2}
				""").getAsJsonObject(),
			null,
			null
		));
		runtime.onClientTick(null);

		assertTrue(result.contains("blocked reason=target_not_inspected"));
		assertTrue(result.contains("Runtime converted this request to inspect_world first"));
		assertTrue(result.contains("Call place_block again"));
		assertTrue(executor.lastActiveTask.isEmpty());
	}

	@Test
	void blockModificationToolQueuesAfterFreshWorldRead() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());
		runtime.recordWorldReadForTests(new BlockPos(1, 65, 2));

		CompletableFuture<String> resultFuture = runtime.executePlannerToolCallFutureForTests(new PlannerToolCall(
			"call_use_block",
			"use_block",
			JsonParser.parseString("""
				{"itemId":"minecraft:wheat_seeds","x":1,"y":65,"z":2,"expectedSupportBlockIds":["minecraft:farmland"],"expectedTargetMaterial":"air"}
				""").getAsJsonObject(),
			null,
			null
		));

		assertFalse(resultFuture.isDone());
		runtime.onClientTick(null);

		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		assertEquals(WorldTaskType.USE_BLOCK, request.type());
		assertEquals("minecraft:wheat_seeds", request.blockUse().itemId());
		assertEquals(new GoalPosition(1, 65, 2, true), request.blockUse().targetPosition());
		assertEquals(List.of("minecraft:farmland"), request.blockUse().expectedSupportBlockIds());
		executor.nextTerminalEvent = Optional.of(new TaskTerminalEvent(
			request.taskId(),
			null,
			TaskExecutionState.COMPLETED,
			"used block",
			null
		));
		runtime.onClientTick(null);

		String result = resultFuture.join();
		assertTrue(result.contains("completed"));
		assertTrue(result.contains("state=COMPLETED"));
		assertFalse(result.contains("accepted queued"));
	}

	@Test
	void blockModificationToolFailureWaitsForTerminalFeedback() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());
		runtime.recordWorldReadForTests(new BlockPos(1, 65, 2));

		CompletableFuture<String> resultFuture = runtime.executePlannerToolCallFutureForTests(new PlannerToolCall(
			"call_use_block",
			"use_block",
			JsonParser.parseString("""
				{"itemId":"minecraft:stone_hoe","x":1,"y":65,"z":2,"expectedSupportBlockIds":["minecraft:dirt"],"expectedTargetMaterial":"air"}
				""").getAsJsonObject(),
			null,
			null
		));

		assertFalse(resultFuture.isDone());
		runtime.onClientTick(null);
		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		executor.nextTerminalEvent = Optional.of(new TaskTerminalEvent(
			request.taskId(),
			null,
			TaskExecutionState.FAILED,
			"interaction_failed itemRaycastMatches=false",
			null
		));
		runtime.onClientTick(null);
		String result = resultFuture.join();

		assertTrue(result.contains("failed"));
		assertTrue(result.contains("state=FAILED"));
		assertTrue(result.contains("interaction_failed itemRaycastMatches=false"));
		assertFalse(result.contains("accepted queued"));
		runtime.onClientTick(null);
		assertEquals(TaskState.FAILED, runtime.taskSnapshot().state());
		assertTrue(runtime.dialogueSnapshot().recentTurns().stream().anyMatch(turn ->
			"system".equals(turn.speaker())
				&& turn.text().contains("TASK UPDATE: state=FAILED")
				&& turn.text().contains("activeStepKind=USE_BLOCK")
				&& turn.text().contains("failure=interaction_failed itemRaycastMatches=false")
		));
	}

	@Test
	void batchedBlockModificationToolInspectsInsteadOfQueuingUnreadTarget() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());
		runtime.recordWorldReadForTests(new BlockPos(1, 65, 2));

		String result = runtime.executePlannerToolCallForTests(new PlannerToolCall(
			"call_use_block",
			"use_block",
			JsonParser.parseString("""
				{"itemId":"minecraft:wheat_seeds","targets":[{"x":1,"y":65,"z":2},{"x":2,"y":65,"z":2}]}
				""").getAsJsonObject(),
			null,
			null
		));
		runtime.onClientTick(null);

		assertTrue(result.contains("blocked reason=target_not_inspected"));
		assertTrue(result.contains("Runtime converted this request to inspect_world first"));
		assertTrue(result.contains("Call use_block again"));
		assertTrue(executor.lastActiveTask.isEmpty());
	}

	@Test
	void batchedBlockModificationToolQueuesAfterFreshWorldReads() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());
		runtime.recordWorldReadForTests(new BlockPos(1, 64, 2));
		runtime.recordWorldReadForTests(new BlockPos(2, 64, 2));

		CompletableFuture<String> resultFuture = runtime.executePlannerToolCallFutureForTests(new PlannerToolCall(
			"call_place",
			"place_block",
			JsonParser.parseString("""
				{
				  "itemId":"minecraft:dirt",
				  "facePreference":"down",
				  "targets":[
				    {"x":1,"y":64,"z":2},
				    {"x":2,"y":64,"z":2,"facePreference":"north","requireCurrentTargetMaterial":"air"}
				  ]
				}
				""").getAsJsonObject(),
			null,
			null
		));
		assertFalse(resultFuture.isDone());
		runtime.onClientTick(null);

		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		assertEquals(WorldTaskType.PLACE_BLOCK, request.type());
		assertEquals("minecraft:dirt", request.blockPlacement().itemId());
		assertEquals(2, request.blockPlacement().targets().size());
		assertEquals(new GoalPosition(1, 64, 2, true), request.blockPlacement().targets().get(0).targetPosition());
		assertEquals("down", request.blockPlacement().targets().get(0).facePreference());
		assertEquals(new GoalPosition(2, 64, 2, true), request.blockPlacement().targets().get(1).targetPosition());
		assertEquals("north", request.blockPlacement().targets().get(1).facePreference());
		assertEquals("air", request.blockPlacement().targets().get(1).requiredTargetMaterial());
		executor.nextTerminalEvent = Optional.of(new TaskTerminalEvent(
			request.taskId(),
			null,
			TaskExecutionState.COMPLETED,
			"placed blocks",
			null
		));
		runtime.onClientTick(null);

		String result = resultFuture.join();
		assertTrue(result.contains("completed"));
		assertTrue(result.contains("targets=2"));
		assertFalse(result.contains("accepted queued"));
	}

	@Test
	void breakBlocksToolInspectsInsteadOfQueuingUnreadTarget() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		String result = runtime.executePlannerToolCallForTests(new PlannerToolCall(
			"call_break",
			"break_blocks",
			JsonParser.parseString("""
				{"targets":[{"x":1,"y":64,"z":2,"expectedBlockIds":["minecraft:grass_block"]}]}
				""").getAsJsonObject(),
			null,
			null
		));
		runtime.onClientTick(null);

		assertTrue(result.contains("blocked reason=target_not_inspected"));
		assertTrue(result.contains("Runtime converted this request to inspect_world first"));
		assertTrue(result.contains("Call break_blocks again"));
		assertTrue(executor.lastActiveTask.isEmpty());
	}

	@Test
	void breakBlocksToolQueuesAfterFreshWorldRead() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());
		runtime.recordWorldReadForTests(new BlockPos(1, 64, 2));

		CompletableFuture<String> resultFuture = runtime.executePlannerToolCallFutureForTests(new PlannerToolCall(
			"call_break",
			"break_blocks",
			JsonParser.parseString("""
				{"targets":[{"x":1,"y":64,"z":2,"expectedBlockIds":["minecraft:grass_block","minecraft:dirt"]}]}
				""").getAsJsonObject(),
			null,
			null
		));
		assertFalse(resultFuture.isDone());
		runtime.onClientTick(null);

		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		assertEquals(WorldTaskType.BREAK_BLOCKS, request.type());
		assertEquals(new GoalPosition(1, 64, 2, true), request.blockBreak().targets().getFirst().position());
		assertEquals(List.of("minecraft:grass_block", "minecraft:dirt"), request.blockBreak().targets().getFirst().expectedBlockIds());
		executor.nextTerminalEvent = Optional.of(new TaskTerminalEvent(
			request.taskId(),
			null,
			TaskExecutionState.COMPLETED,
			"broke blocks",
			null
		));
		runtime.onClientTick(null);

		String result = resultFuture.join();
		assertTrue(result.contains("completed"));
		assertFalse(result.contains("accepted queued"));
	}

	@Test
	void manualAttackEntitySubmitRoutesWorldTaskRequest() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));

		runtime.submitAttackEntity(
			new EntityInteractionStepArgs(new EntitySelector(null, null, "minecraft:sheep"), null),
			"bridge_debug"
		);
		runtime.onClientTick(null);

		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		assertEquals(WorldTaskType.ATTACK_ENTITY, request.type());
		assertEquals(new EntityInteractionStepArgs(new EntitySelector(null, null, "minecraft:sheep"), null), request.entityInteraction());
		assertEquals(TaskState.RUNNING, runtime.taskSnapshot().state());
	}

	@Test
	void manualUseEntitySubmitRoutesWorldTaskRequest() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));

		runtime.submitUseEntity(
			new EntityInteractionStepArgs(new EntitySelector(null, "Dinner", null), "minecraft:shears"),
			"bridge_debug"
		);
		runtime.onClientTick(null);

		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		assertEquals(WorldTaskType.USE_ENTITY, request.type());
		assertEquals(new EntityInteractionStepArgs(new EntitySelector(null, "Dinner", null), "minecraft:shears"), request.entityInteraction());
		assertEquals(TaskState.RUNNING, runtime.taskSnapshot().state());
	}

	@Test
	void craftRecipeToolResultWaitsForTerminalFeedback() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		CompletableFuture<String> resultFuture = runtime.executePlannerToolCallFutureForTests(craftRecipeToolCall());

		assertFalse(resultFuture.isDone());
		runtime.onClientTick(null);
		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		executor.nextTerminalEvent = Optional.of(new TaskTerminalEvent(
			request.taskId(),
			null,
			TaskExecutionState.COMPLETED,
			"crafted",
			null
		));
		runtime.onClientTick(null);

		String result = resultFuture.join();
		assertTrue(result.contains("completed"));
		assertTrue(result.contains("state=COMPLETED"));
		assertFalse(result.contains("accepted queued"));
	}

	@Test
	void inventoryTaskUpdateSnapshotFormatsPlannerVisibleInventoryEvidence() {
		String result = EmbodiedAgentRuntime.formatInventorySnapshotForTaskUpdate(new WorldEvidence(
			Map.of(),
			Map.of("minecraft:stone_pickaxe", 1, "minecraft:stick", 4),
			Map.of(),
			"minecraft:overworld",
			0,
			64,
			0,
			"minecraft:stone_pickaxe",
			2,
			List.of("0=minecraft:stickx4", "1=empty", "2=minecraft:stone_pickaxex1"),
			10L
		));

		assertTrue(result.contains("inventorySnapshot={itemCounts={minecraft:stick=4, minecraft:stone_pickaxe=1}"));
		assertTrue(result.contains("selectedHotbarSlot=2"));
		assertTrue(result.contains("equippedItemId=minecraft:stone_pickaxe"));
		assertTrue(result.contains("hotbarItems=[0=minecraft:stickx4, 1=empty, 2=minecraft:stone_pickaxex1]"));
	}

	@Test
	void craftingProgressDoesNotWakePlannerWhileCraftToolResultPending() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		CompletableFuture<String> resultFuture = runtime.executePlannerToolCallFutureForTests(craftRecipeToolCall());
		runtime.onPlayerCraftedItem("minecraft:stick", 4);

		AgentDebugTimelineEntry route = runtime.debugTimeline(null).entries().stream()
			.filter(entry -> "event_pipeline".equals(entry.domain()))
			.filter(entry -> "crafting.item_crafted".equals(entry.payload().get("eventType")))
			.findFirst()
			.orElseThrow();

		assertFalse(resultFuture.isDone());
		assertEquals(true, route.payload().get("emitSemantic"));
		assertEquals(false, route.payload().get("emitTrigger"));
	}

	@Test
	void craftRecipeToolResultTimesOutWhileLeavingTaskRunning() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		CompletableFuture<String> resultFuture = runtime.executePlannerToolCallFutureForTests(craftRecipeToolCall());

		for (int i = 0; i <= EmbodiedAgentRuntime.CRAFT_TOOL_RESULT_TIMEOUT_TICKS; i++) {
			runtime.onClientTick(null);
		}

		String result = resultFuture.join();
		assertTrue(result.contains("pending_timeout"));
		assertFalse(result.contains("accepted queued"));
		assertEquals(TaskState.RUNNING, runtime.taskSnapshot().state());
		assertTrue(executor.lastActiveTask.isPresent());
	}

	@Test
	void craftRecipePrimitiveFailureEmitsFailureReasonToPlanner() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		CompletableFuture<String> resultFuture = runtime.executePlannerToolCallFutureForTests(craftRecipeToolCall());
		runtime.onClientTick(null);
		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();

		executor.nextTerminalEvent = Optional.of(new TaskTerminalEvent(
			request.taskId(),
			null,
			TaskExecutionState.FAILED,
			"missing_ingredients",
			null
		));
		runtime.onClientTick(null);
		String result = resultFuture.join();
		runtime.onClientTick(null);

		assertTrue(result.contains("failed"));
		assertTrue(result.contains("missing_ingredients"));
		assertEquals(TaskState.FAILED, runtime.taskSnapshot().state());
		assertEquals("missing_ingredients", runtime.taskSnapshot().lastFailure());
		assertTrue(runtime.recentEvents(null).events().stream().anyMatch(event -> "task.failed".equals(event.type())));
		assertTrue(runtime.dialogueSnapshot().recentTurns().stream().anyMatch(turn ->
			"system".equals(turn.speaker())
				&& turn.text().contains("TASK UPDATE: state=FAILED")
				&& turn.text().contains("activeStepKind=CRAFT_RECIPE")
				&& turn.text().contains("failure=missing_ingredients")
			));
	}

	@Test
	void directGoalFailureEmitsFailureReasonToPlanner() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		String result = runtime.executePlannerToolCallForTests(new PlannerToolCall(
			"call_nav",
			"navigate_to",
			JsonParser.parseString("""
				{"x":-30,"y":115,"z":-55,"exactY":false}
				""").getAsJsonObject(),
			null,
			null
		));
		assertTrue(result.contains("accepted"));

		runtime.onClientTick(null);
		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		executor.nextTerminalEvent = Optional.of(new TaskTerminalEvent(
			request.taskId(),
			request.goal(),
			TaskExecutionState.FAILED,
			"CALC_FAILED",
			TaskTerminationCause.CALCULATION_FAILED
		));
		runtime.onClientTick(null);

		assertTrue(runtime.recentEvents(null).events().stream().anyMatch(event -> "task.failed".equals(event.type())));
		assertTrue(runtime.dialogueSnapshot().recentTurns().stream().anyMatch(turn ->
			"system".equals(turn.speaker())
				&& turn.text().contains("TASK UPDATE: state=FAILED")
				&& turn.text().contains("goalType=NAVIGATE_TO")
				&& turn.text().contains("message=CALC_FAILED")
				&& turn.text().contains("terminationCause=CALCULATION_FAILED")
		));
	}

	@Test
	void givePlayerToolRejectsMissingNearbyTargetBeforeQueuingTask() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);

		String result = runtime.executePlannerToolCallForTests(new PlannerToolCall(
			"call_give",
			"give_player",
			JsonParser.parseString("""
				{"targetPlayer":"Alice","itemId":"minecraft:oak_log","quantity":2}
				""").getAsJsonObject(),
			null,
			null
		));

		assertTrue(result.contains("target_not_nearby"));
		assertTrue(executor.lastActiveTask.isEmpty());
	}

	@Test
	void givePlayerToolRejectsFarTargetBeforeQueuingTask() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.injectNearbyPlayerForTests("Alice", new Vec3d(5.0D, 0.0D, 0.0D));

		String result = runtime.executePlannerToolCallForTests(new PlannerToolCall(
			"call_give",
			"give_player",
			JsonParser.parseString("""
				{"targetPlayer":"Alice","itemId":"minecraft:oak_log","quantity":2}
				""").getAsJsonObject(),
			null,
			null
		));

		assertTrue(result.contains("target_not_nearby"));
		assertTrue(executor.lastActiveTask.isEmpty());
	}

	@Test
	void givePlayerToolRoutesDropTaskForNearbyTarget() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));
		runtime.injectNearbyPlayerForTests("Alice", new Vec3d(2.0D, 0.0D, 0.0D));

		String result = runtime.executePlannerToolCallForTests(new PlannerToolCall(
			"call_give",
			"give_player",
			JsonParser.parseString("""
				{"targetPlayer":"Alice","itemId":"minecraft:oak_log","quantity":2}
				""").getAsJsonObject(),
			null,
			null
		));
		runtime.onClientTick(null);

		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		assertTrue(result.contains("accepted"));
		assertTrue(result.contains("queued"));
		assertTrue(result.contains("does not mean completed"));
		assertTrue(result.contains("TASK UPDATE"));
		assertEquals(WorldTaskType.DROP_ITEMS, request.type());
		assertEquals(new DropItemsStepArgs("minecraft:oak_log", 2, "Alice"), request.dropItems());
	}

	@Test
	void terminalTaskEventCreatesSemanticEventAndDialogueTaskUpdate() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.MINE_BLOCKS,
			null,
			null,
			new GoalMineSpec(List.of("minecraft:oak_log"), 8),
			20L,
			"test"
		);
		executor.nextTerminalEvent = Optional.of(new TaskTerminalEvent(
			"mine-task",
			goal,
			TaskExecutionState.COMPLETED,
			"Goal reached",
			TaskTerminationCause.GOAL_REACHED
		));

		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));
		runtime.injectGoalForTests(goal);

		runtime.onClientTick(null);

		assertTrue(runtime.recentEvents(null).events().stream().anyMatch(event -> "task.completed".equals(event.type())));
		assertTrue(runtime.dialogueSnapshot().recentTurns().stream().anyMatch(turn ->
			"system".equals(turn.speaker()) && turn.text().contains("TASK UPDATE: state=COMPLETED")
		));
	}

	@Test
	void directGoalTerminalFailureCreatesDialogueTaskUpdate() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(12, 64, -8, true),
			null,
			20L,
			"test"
		);
		executor.nextTerminalEvent = Optional.of(new TaskTerminalEvent(
			"navigate-task",
			goal,
			TaskExecutionState.FAILED,
			"Path calculation failed",
			TaskTerminationCause.CALCULATION_FAILED
		));

		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));
		runtime.injectGoalForTests(goal);

		runtime.onClientTick(null);

		assertTrue(runtime.recentEvents(null).events().stream().anyMatch(event -> "task.failed".equals(event.type())));
		assertTrue(runtime.dialogueSnapshot().recentTurns().stream().anyMatch(turn ->
			"system".equals(turn.speaker())
				&& turn.text().contains("TASK UPDATE: state=FAILED")
				&& turn.text().contains("goalType=NAVIGATE_TO")
				&& turn.text().contains("Path calculation failed")
		));
	}

	@Test
	void submitTaskPlannerResponseThreadsTaskSnapshotIntoRuntimeSnapshot() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));
		runtime.injectDialogueResponseForTests(new DialogueResponse(
			"On it.",
			new DialogueIntent(
				DialogueIntentType.SUBMIT_TASK,
				null,
				null,
				null,
				null,
				new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 4)
			),
			20L
		));

		assertEquals(TaskState.QUEUED, runtime.snapshot().task().state());
		assertEquals(TaskType.COLLECT_RESOURCE, runtime.snapshot().task().spec().type());
		assertTrue(runtime.snapshot().taskExecution().state() == TaskExecutionState.IDLE
			|| runtime.snapshot().taskExecution().state() == TaskExecutionState.RUNNING);
	}

	@Test
	void submitTaskPopulatesCollectResourceDebugProbe() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);

		runtime.submitTask(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 16), "bridge_debug");

		assertTrue(runtime.debugCollectResourceState().active());
		assertEquals("WOOD_LOGS", runtime.debugCollectResourceState().resourceKind());
		assertEquals(16, runtime.debugCollectResourceState().targetQuantity());
		assertFalse(runtime.debugTimeline(null).entries().isEmpty());
	}

	@Test
	void missionUpdatePlannerResponseThreadsLedgerIntoRuntimeSnapshot() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));
		TaskLedger ledger = new TaskLedger(
			"mission-wood-1",
			MissionType.COLLECT_RESOURCE,
			"Collect 4 wood logs",
			List.of(
				new LedgerStep(
					"collect_logs",
					LedgerStepKind.COLLECT_RESOURCE,
					new LedgerStepPayload(
						new CollectResourceStepArgs(TaskResourceKind.WOOD_LOGS, 4, "KEEP"),
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null
					),
					List.of(),
					LedgerStepStatus.ACTIVE,
					List.of(new EvidenceRequirement(EvidenceKind.INVENTORY_DELTA_AT_LEAST, TaskResourceKind.WOOD_LOGS, 4, null, null)),
					2,
					"Collect logs"
				),
				new LedgerStep(
					"finish",
					LedgerStepKind.FINISH,
					new LedgerStepPayload(
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						new FinishStepArgs("Mission complete")
					),
					List.of("collect_logs"),
					LedgerStepStatus.PENDING,
					List.of(new EvidenceRequirement(EvidenceKind.STEP_COMPLETED, null, null, "collect_logs", null)),
					0,
					"Finish"
				)
			),
			"collect_logs",
			List.of(new EvidenceRequirement(EvidenceKind.INVENTORY_DELTA_AT_LEAST, TaskResourceKind.WOOD_LOGS, 4, null, null)),
			"user_request",
			"Keep it simple"
		);
		runtime.injectDialogueResponseForTests(new DialogueResponse(
			"Starting the mission.",
			new DialogueIntent(
				DialogueIntentType.MISSION_UPDATE,
				null,
				null,
				null,
				null,
				null,
				ledger
			),
			20L
		));

		assertEquals(TaskState.QUEUED, runtime.snapshot().task().state());
		assertEquals("mission-wood-1", runtime.snapshot().task().mission().missionId());
		assertEquals("collect_logs", runtime.snapshot().task().activeStepId());
		assertEquals(ledger, runtime.snapshot().task().ledger());
	}

	@Test
	void directGoalSubmissionDoesNotPreemptActiveTask() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));
		runtime.injectDialogueResponseForTests(new DialogueResponse(
			"",
			new DialogueIntent(
				DialogueIntentType.SUBMIT_TASK,
				null,
				null,
				null,
				null,
				new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 4)
			),
			20L
		));

		runtime.injectDialogueResponseForTests(new DialogueResponse(
			"",
			new DialogueIntent(
				DialogueIntentType.SET_GOAL,
				GoalType.NAVIGATE_TO,
				null,
				new GoalPosition(1, 64, 1, true),
				null,
				null
			),
			30L
		));

		assertEquals(TaskState.QUEUED, runtime.snapshot().task().state());
		assertTrue(runtime.activeGoal().isEmpty());
	}

	@Test
	void directJobUpdateDoesNotPreemptActiveTask() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));
		runtime.injectDialogueResponseForTests(new DialogueResponse(
			"",
			new DialogueIntent(
				DialogueIntentType.SUBMIT_TASK,
				null,
				null,
				null,
				null,
				new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 4)
			),
			20L
		));

		runtime.injectDialogueResponseForTests(new DialogueResponse(
			"",
			new DialogueIntent(
				DialogueIntentType.JOB_UPDATE,
				ActiveJobProposal.navigateTo(new GoalPosition(1, 64, 1, true))
			),
			30L
		));

		assertEquals(TaskState.QUEUED, runtime.snapshot().task().state());
		assertTrue(runtime.activeGoal().isEmpty());
	}

	@Test
	void plannerDirectGoalToolDoesNotPreemptActiveCollectResourceTask() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));
		runtime.submitTask(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 1), "planner_tool");
		runtime.onClientTick(null);
		assertEquals(TaskState.WAITING_FOR_PICKUP, runtime.taskSnapshot().state());

		String result = runtime.executePlannerToolCallForTests(new PlannerToolCall(
			"call_nav",
			"navigate_to",
			JsonParser.parseString("""
				{"x":71,"y":70,"z":-299,"exactY":false}
				""").getAsJsonObject(),
			null,
			null
		));

		assertTrue(result.contains("TOOL_ERROR: navigate_to denied"));
		assertTrue(result.contains("active_task_in_progress"));
		String ensureResult = runtime.executePlannerToolCallForTests(new PlannerToolCall(
			"call_ensure",
			"ensure_blocks_in_inventory",
			JsonParser.parseString("""
				{"blockIds":["minecraft:oak_log"],"quantity":3}
				""").getAsJsonObject(),
			null,
			null
		));

		assertTrue(ensureResult.contains("TOOL_ERROR: ensure_blocks_in_inventory denied"));
		assertTrue(ensureResult.contains("active_task_in_progress"));
		String returnResult = runtime.executePlannerToolCallForTests(new PlannerToolCall(
			"call_return",
			"return_to_surface",
			JsonParser.parseString("""
				{"useTowering":true}
				""").getAsJsonObject(),
			null,
			null
		));

		assertTrue(returnResult.contains("TOOL_ERROR: return_to_surface denied"));
		assertTrue(returnResult.contains("active_task_in_progress"));
		assertEquals(TaskState.WAITING_FOR_PICKUP, runtime.taskSnapshot().state());
		assertEquals(TaskType.COLLECT_RESOURCE, runtime.taskSnapshot().spec().type());
		assertEquals(GoalType.MINE_BLOCKS, runtime.activeGoal().orElseThrow().type());
	}

	@Test
	void submitTaskClearsPreviouslyActiveDirectGoal() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.injectGoalForTests(new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(1, 64, 1, true),
			null,
			10L,
			"test"
		));

		runtime.injectDialogueResponseForTests(new DialogueResponse(
			"",
			new DialogueIntent(
				DialogueIntentType.SUBMIT_TASK,
				null,
				null,
				null,
				null,
				new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 4)
			),
			20L
		));

		assertTrue(runtime.activeGoal().isEmpty());
		assertEquals(TaskState.QUEUED, runtime.taskSnapshot().state());
	}

	@Test
	void semanticTaskFailureEmitsTaskEventAndInternalUpdate() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));

		runtime.injectDialogueResponseForTests(new DialogueResponse(
			"",
			new DialogueIntent(
				DialogueIntentType.SUBMIT_TASK,
				null,
				null,
				null,
				null,
				new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 4)
			),
			20L
		));

		GoalSnapshot goal = new GoalSnapshot(
			GoalType.MINE_BLOCKS,
			null,
			null,
			new GoalMineSpec(List.of("minecraft:oak_log"), 4),
			21L,
			"task_runtime"
		);
		executor.nextTerminalEvent = Optional.of(new TaskTerminalEvent(
			"mine-task",
			goal,
			TaskExecutionState.FAILED,
			"Path calculation failed",
			TaskTerminationCause.CALCULATION_FAILED
		));

		for (int tick = 0; tick < 24; tick++) {
			runtime.onClientTick(null);
		}

		assertEquals(TaskState.FAILED, runtime.taskSnapshot().state());
		assertTrue(runtime.recentEvents(null).events().stream().anyMatch(event -> "task.failed".equals(event.type())));
		assertTrue(runtime.dialogueSnapshot().recentTurns().stream().anyMatch(turn ->
			"system".equals(turn.speaker()) && turn.text().contains("TASK UPDATE: state=FAILED")
		));
	}

	@Test
	void collectResourceTargetMissingEmitsBlockedEventAndPlannerTrigger() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));

		runtime.injectDialogueResponseForTests(new DialogueResponse(
			"Got it, collecting 5 logs now.",
			new DialogueIntent(
				DialogueIntentType.JOB_UPDATE,
				ActiveJobProposal.collectResource(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 5))
			),
			20L
		));

		runtime.onClientTick(null);

		SemanticEvent blockedEvent = runtime.recentEvents(null).events().stream()
			.filter(event -> "task.blocked".equals(event.type()))
			.findFirst()
			.orElseThrow();
		Map<String, Object> payload = blockedEvent.payload();
		assertEquals("COLLECT_RESOURCE", payload.get("taskType"));
		assertEquals("WOOD_LOGS", payload.get("resourceKind"));
		assertEquals(5, payload.get("quantity"));
		assertEquals("WAITING_FOR_PICKUP", payload.get("state"));
		assertEquals("target_missing", payload.get("blockedReason"));
		assertEquals(0, payload.get("collected"));
		assertEquals(5, payload.get("remaining"));
		assertEquals("planner_response", payload.get("source"));
		assertEquals("task.blocked", runtime.debugEventPipelineState().lastEventType());
		assertEquals("SYSTEM", runtime.debugEventPipelineState().lastTriggerType());
		assertTrue(runtime.debugEventPipelineState().lastEmitSemantic());
		assertTrue(runtime.debugEventPipelineState().lastEmitTrigger());
	}

	@Test
	void directGoalEventsAreNotSuppressedAfterTaskHasAlreadyTerminated() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));
		runtime.submitTask(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 4), "bridge_debug");
		runtime.cancelTask("user_cancelled");
		runtime.injectGoalForTests(new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(1, 64, 1, true),
			null,
			30L,
			"test"
		));

		runtime.onClientTick(null);

		assertTrue(runtime.recentEvents(null).events().stream().anyMatch(event -> "task.started".equals(event.type())));
	}

	@Test
	void clearGoalEventEmitsWhenGoalAlreadyClearedByTaskRuntime() {
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(new FakeWorldTaskExecutor());
		runtime.injectGoalForTests(new GoalSnapshot(
			GoalType.FOLLOW_PLAYER,
			"Alice",
			null,
			null,
			10L,
			"test"
		));
		runtime.injectGoalForTests(null);

		runtime.injectDialogueResponseForTests(new DialogueResponse(
			"Stopping.",
			new DialogueIntent(DialogueIntentType.CLEAR_GOAL, null, null),
			20L
		));

		SemanticEvent event = runtime.recentEvents(null).events().stream()
			.filter(current -> "planner.goal_cleared".equals(current.type()))
			.findFirst()
			.orElseThrow();
		assertEquals("planner_response", event.payload().get("source"));
	}

	@Test
	void cancelTaskRecordsCancelledEventImmediately() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);

		runtime.submitTask(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 1), "bridge_debug");
		runtime.cancelTask("user_cancelled");

		assertEquals(TaskState.CANCELLED, runtime.taskSnapshot().state());
		assertTrue(runtime.recentEvents(null).events().stream().anyMatch(event -> "task.cancelled".equals(event.type())));
	}

	@Test
	void clearGoalResponseRecordsEventWhenGoalAlreadyClearedByRuntime() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());
		runtime.injectGoalForTests(new GoalSnapshot(
			GoalType.FOLLOW_PLAYER,
			"ObserveAlice",
			null,
			null,
			10L,
			"test"
		));
		assertTrue(runtime.activeGoal().isPresent());

		runtime.cancelTask("target_lost");
		assertTrue(runtime.activeGoal().isEmpty());
		long baselineSeqNo = runtime.latestEventSeqNo();

		runtime.injectDialogueResponseForTests(new DialogueResponse(
			"Stopping.",
			new DialogueIntent(DialogueIntentType.CLEAR_GOAL, null, null),
			20L
		));

		SemanticEvent event = runtime.recentEvents(baselineSeqNo).events().stream()
			.filter(candidate -> "planner.goal_cleared".equals(candidate.type()))
			.findFirst()
			.orElseThrow();
		assertEquals(Boolean.TRUE, event.payload().get("alreadyClear"));
		assertEquals("planner_response", event.payload().get("source"));
	}

	private static final class FakeWorldTaskExecutor implements WorldTaskExecutor {
		private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();
		private TaskExecutionSnapshot forcedSnapshot;
		private Optional<TaskTerminalEvent> nextTerminalEvent = Optional.empty();
		private Optional<WorldTaskRequest> lastActiveTask = Optional.empty();

		@Override
		public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
			lastActiveTask = activeTask;
			if (forcedSnapshot != null) {
				snapshot = forcedSnapshot;
			}
			else if (!sessionSnapshot.companionActuationAllowed()) {
				snapshot = new TaskExecutionSnapshot(
					TaskExecutionState.PAUSED_BY_SESSION_GATE,
					activeTask.map(WorldTaskRequest::taskId).orElse(null),
					activeTask.map(WorldTaskRequest::goal).orElse(null),
					null,
					null,
					null,
					null
				);
			}
			else if (nextTerminalEvent.isPresent()) {
				TaskTerminalEvent terminalEvent = nextTerminalEvent.get();
				snapshot = new TaskExecutionSnapshot(
					terminalEvent.terminalState(),
					terminalEvent.taskId(),
					terminalEvent.goal(),
					null,
					terminalEvent.message(),
					null,
					terminalEvent.terminationCause()
				);
			}
			else {
				snapshot = new TaskExecutionSnapshot(
					activeTask.isPresent() ? TaskExecutionState.RUNNING : TaskExecutionState.IDLE,
					activeTask.map(WorldTaskRequest::taskId).orElse(null),
					activeTask.map(WorldTaskRequest::goal).orElse(null),
					null,
					null,
					null,
					null
				);
			}

			Optional<TaskTerminalEvent> result = nextTerminalEvent;
			nextTerminalEvent = Optional.empty();
			return result;
		}

		@Override
		public TaskExecutionSnapshot snapshot() {
			return snapshot;
		}

		@Override
		public void onWorldLeave() {
			snapshot = TaskExecutionSnapshot.idle();
		}

		@Override
		public void shutdown() {
			snapshot = TaskExecutionSnapshot.idle();
		}
	}

	private static PlannerToolCall craftRecipeToolCall() {
		return new PlannerToolCall(
			"call_craft",
			"craft_recipe",
			JsonParser.parseString("""
				{"recipeId":"oak_planks_x2_to_stick","times":1}
				""").getAsJsonObject(),
			null,
			null
		);
	}

	private static AgentConfig.LlmConfig configuredLlmConfig() {
		AgentConfig.LlmConfig defaults = AgentConfig.LlmConfig.defaults();
		return new AgentConfig.LlmConfig(
			defaults.providerBaseUrl(),
			"test-key",
			"test-model",
			defaults.visionProviderBaseUrl(),
			defaults.visionApiKey(),
			defaults.visionModel(),
			defaults.requestTimeoutMillis(),
			defaults.visionRequestTimeoutMillis(),
			defaults.maxRecentConversationTurns(),
			defaults.plannerCompactionTriggerTokens(),
			defaults.plannerPendingSemanticEventCap(),
			defaults.plannerSessionMaxConcurrentAttempts(),
			defaults.plannerSessionCoalesceStepMillis(),
			defaults.plannerSessionCoalesceMinMillis(),
			defaults.plannerSessionCoalesceMaxMillis(),
			defaults.visionImageDetail(),
			defaults.plannerNativeVisionEnabled(),
			defaults.plannerUseJsonObjectResponseFormat()
		);
	}

	private static SmeltingOption testSmeltingOption(String optionId, int maxInputQuantity) {
		SmeltingStationKey key = new SmeltingStationKey("minecraft:overworld", 1, 64, 1);
		SmeltingStationObservation observation = new SmeltingStationObservation(
			key,
			SmeltingStationKind.FURNACE,
			new SmeltingSlotSnapshot(null, 0, null, 0, null, 0, 0, 200, false),
			false,
			1.0D
		);
		return new SmeltingOption(
			optionId,
			"minecraft:raw_iron",
			"minecraft:iron_ingot",
			1,
			maxInputQuantity,
			200,
			new SmeltingStationCandidate(
				SmeltingStationSource.NEARBY_EXISTING,
				SmeltingStationState.EMPTY,
				SmeltingStationKind.FURNACE,
				key,
				1.0D,
				false
			),
			observation
		);
	}

	private static String extractProcessId(String toolResult) {
		int start = toolResult.indexOf("processId=");
		assertTrue(start >= 0);
		int valueStart = start + "processId=".length();
		int valueEnd = toolResult.indexOf(' ', valueStart);
		return valueEnd < 0 ? toolResult.substring(valueStart) : toolResult.substring(valueStart, valueEnd);
	}

	private static SessionSnapshot loadedRemoteSession() {
		return new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		);
	}
}
