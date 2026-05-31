package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.dialogue.DialogueIntent;
import ai.moeru.airicraft.agent.dialogue.DialogueIntentType;
import ai.moeru.airicraft.agent.dialogue.DialogueResponse;
import ai.moeru.airicraft.agent.events.SemanticEvent;
import ai.moeru.airicraft.agent.job.ActiveJob;
import ai.moeru.airicraft.agent.job.ActiveJobProposal;
import ai.moeru.airicraft.agent.job.ActiveJobStatus;
import ai.moeru.airicraft.agent.job.ActiveJobType;
import ai.moeru.airicraft.agent.session.SessionMode;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import ai.moeru.airicraft.agent.tasks.WorldTaskRequest;
import ai.moeru.airicraft.agent.tasks.CollectResourceStepArgs;
import ai.moeru.airicraft.agent.tasks.CraftRecipeStepArgs;
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
import ai.moeru.airicraft.agent.verification.VerificationStatus;
import ai.moeru.airicraft.agent.llm.PlannerToolCall;
import com.google.gson.JsonParser;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
	void directGoalSubmissionCancelsActiveTaskFirst() {
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

		assertEquals(TaskState.CANCELLED, runtime.snapshot().task().state());
		assertEquals(GoalType.NAVIGATE_TO, runtime.activeGoal().orElseThrow().type());
	}

	@Test
	void directJobUpdateCancelsActiveTaskFirst() {
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

		assertEquals(TaskState.CANCELLED, runtime.snapshot().task().state());
		assertEquals(GoalType.NAVIGATE_TO, runtime.activeGoal().orElseThrow().type());
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
	void cancelTaskRecordsCancelledEventImmediately() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);

		runtime.submitTask(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 1), "bridge_debug");
		runtime.cancelTask("user_cancelled");

		assertEquals(TaskState.CANCELLED, runtime.taskSnapshot().state());
		assertTrue(runtime.recentEvents(null).events().stream().anyMatch(event -> "task.cancelled".equals(event.type())));
	}

	@Test
	void mineBlocksVerificationRegistersAndReachesRunningTaskState() {
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

		assertTrue(runtime.verificationScenarioNames().contains("mine_blocks.basic"));
		assertTrue(runtime.startVerification("mine_blocks.basic"));

		for (int tick = 0; tick < 8 && runtime.verificationReport().status() == VerificationStatus.RUNNING; tick++) {
			runtime.onClientTick(null);
		}

		assertEquals(VerificationStatus.PASSED, runtime.verificationReport().status());
		assertEquals("mine_blocks.basic", runtime.verificationReport().scenarioName());
		assertEquals(GoalType.MINE_BLOCKS, runtime.activeGoal().orElseThrow().type());
		assertEquals(TaskExecutionState.RUNNING, runtime.taskExecutionSnapshot().state());
	}

	private static final class FakeWorldTaskExecutor implements WorldTaskExecutor {
		private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();
		private Optional<TaskTerminalEvent> nextTerminalEvent = Optional.empty();
		private Optional<WorldTaskRequest> lastActiveTask = Optional.empty();

		@Override
		public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
			lastActiveTask = activeTask;
			if (!sessionSnapshot.companionActuationAllowed()) {
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
