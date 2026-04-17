package ai.moeru.airicraft.agent.dialogue;

import ai.moeru.airicraft.agent.AgentConfig;
import ai.moeru.airicraft.agent.events.SemanticEventBuffer;
import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.job.ActiveJobProposal;
import ai.moeru.airicraft.agent.llm.CurrentViewVisionTool;
import ai.moeru.airicraft.agent.llm.OpenAiCompatibleLlmBackend;
import ai.moeru.airicraft.agent.llm.OpenAiCompatibleChatClient;
import ai.moeru.airicraft.agent.llm.PlannerCompactionService;
import ai.moeru.airicraft.agent.llm.PlannerConversationDebugKind;
import ai.moeru.airicraft.agent.llm.PlannerContextAggregator;
import ai.moeru.airicraft.agent.llm.PlannerExecutor;
import ai.moeru.airicraft.agent.llm.PlannerIntent;
import ai.moeru.airicraft.agent.llm.PlannerOrchestrator;
import ai.moeru.airicraft.agent.llm.PlannerResponse;
import ai.moeru.airicraft.agent.llm.PlannerTriggerType;
import ai.moeru.airicraft.agent.llm.PlannerVisionMode;
import ai.moeru.airicraft.agent.tasks.CollectResourceStepArgs;
import ai.moeru.airicraft.agent.tasks.CraftingOpportunity;
import ai.moeru.airicraft.agent.tasks.EvidenceKind;
import ai.moeru.airicraft.agent.tasks.EvidenceRequirement;
import ai.moeru.airicraft.agent.tasks.FinishStepArgs;
import ai.moeru.airicraft.agent.tasks.LedgerStep;
import ai.moeru.airicraft.agent.tasks.LedgerStepKind;
import ai.moeru.airicraft.agent.tasks.LedgerStepPayload;
import ai.moeru.airicraft.agent.tasks.LedgerStepStatus;
import ai.moeru.airicraft.agent.tasks.MissionExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.MissionSpec;
import ai.moeru.airicraft.agent.tasks.MissionType;
import ai.moeru.airicraft.agent.tasks.StepExecutionResult;
import ai.moeru.airicraft.agent.tasks.TaskExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskLedger;
import ai.moeru.airicraft.agent.tasks.TaskOwnership;
import ai.moeru.airicraft.agent.tasks.TaskProgressSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskResourceKind;
import ai.moeru.airicraft.agent.tasks.TaskSpec;
import ai.moeru.airicraft.agent.tasks.TaskSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskState;
import ai.moeru.airicraft.agent.tasks.TaskStep;
import ai.moeru.airicraft.agent.tasks.TaskType;
import ai.moeru.airicraft.agent.tasks.WorldEvidence;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueRuntimeTest {
	@Test
	void mockPlannerResponseProducesDialogueResponse() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);
		backend.injectMockResponse(new PlannerResponse(
			"Sure, I'll follow you!",
			new PlannerIntent("set_goal", GoalType.FOLLOW_PLAYER, "Alice")
		));

		runtime.onPlayerChat("Alice", "@agent follow me", 10L, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
		DialogueResponse response = awaitResponse(runtime, eventBuffer, Duration.ofSeconds(1));

		assertEquals("Sure, I'll follow you!", response.text());
		assertEquals(DialogueIntentType.SET_GOAL, response.intent().type());
		assertFalse(runtime.isDegraded());
		runtime.shutdown();
	}

	@Test
	void structuredPlannerIntentSurvivesDialogueRuntimeMapping() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);
		backend.injectMockResponse(new PlannerResponse(
			"Heading there.",
			new PlannerIntent(
				"set_goal",
				GoalType.NAVIGATE_TO,
				null,
				new GoalPosition(12, 64, -8, true),
				new GoalMineSpec(List.of("minecraft:oak_log"), 16)
			)
		));

		runtime.onPlayerChat("Alice", "@agent head there", 10L, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
		DialogueResponse response = awaitResponse(runtime, eventBuffer, Duration.ofSeconds(1));

		assertEquals("Heading there.", response.text());
		assertEquals(DialogueIntentType.SET_GOAL, response.intent().type());
		assertEquals(GoalType.NAVIGATE_TO, response.intent().goalType());
		assertEquals(new GoalPosition(12, 64, -8, true), response.intent().position());
		assertEquals(new GoalMineSpec(List.of("minecraft:oak_log"), 16), response.intent().mineSpec());
		runtime.shutdown();
	}

	@Test
	void submitTaskPlannerIntentSurvivesDialogueRuntimeMapping() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);
		backend.injectMockResponse(new PlannerResponse(
			"On it.",
			new PlannerIntent(
				"submit_task",
				null,
				null,
				null,
				null,
				new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 16)
			)
		));

		runtime.onPlayerChat("Alice", "@agent get wood", 10L, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
		DialogueResponse response = awaitResponse(runtime, eventBuffer, Duration.ofSeconds(1));

		assertEquals("On it.", response.text());
		assertEquals(DialogueIntentType.SUBMIT_TASK, response.intent().type());
		assertEquals(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 16), response.intent().taskSpec());
		runtime.shutdown();
	}

	@Test
	void jobUpdatePlannerIntentSurvivesDialogueRuntimeMapping() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);
		ActiveJobProposal proposal = ActiveJobProposal.collectResource(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 16));
		backend.injectMockResponse(new PlannerResponse(
			"On it.",
			new PlannerIntent("job_update", proposal)
		));

		runtime.onPlayerChat("Alice", "@agent get wood", 10L, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
		DialogueResponse response = awaitResponse(runtime, eventBuffer, Duration.ofSeconds(1));

		assertEquals("On it.", response.text());
		assertEquals(DialogueIntentType.JOB_UPDATE, response.intent().type());
		assertEquals(proposal, response.intent().activeJob());
		runtime.shutdown();
	}

	@Test
	void cancelTaskPlannerIntentSurvivesDialogueRuntimeMapping() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);
		backend.injectMockResponse(new PlannerResponse(
			"Stopping the task.",
			new PlannerIntent(
				"cancel_task",
				null,
				null,
				null,
				null,
				null
			)
		));

		runtime.onPlayerChat("Alice", "@agent stop the task", 10L, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
		DialogueResponse response = awaitResponse(runtime, eventBuffer, Duration.ofSeconds(1));

		assertEquals("Stopping the task.", response.text());
		assertEquals(DialogueIntentType.CANCEL_TASK, response.intent().type());
		assertEquals(null, response.intent().taskSpec());
		runtime.shutdown();
	}

	@Test
	void missionUpdatePlannerIntentSurvivesDialogueRuntimeMapping() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);
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
		backend.injectMockResponse(new PlannerResponse(
			"Starting the mission.",
			new PlannerIntent(
				"mission_update",
				null,
				null,
				null,
				null,
				null,
				ledger
			)
		));

		runtime.onPlayerChat("Alice", "@agent get wood", 10L, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
		DialogueResponse response = awaitResponse(runtime, eventBuffer, Duration.ofSeconds(1));

		assertEquals("Starting the mission.", response.text());
		assertEquals(DialogueIntentType.MISSION_UPDATE, response.intent().type());
		assertEquals(ledger, response.intent().taskLedger());
		runtime.shutdown();
	}

	@Test
	void threeTimeoutsEnterDegradedAndResetCommandClearsIt() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);

		for (long tick = 1L; tick <= 3L; tick++) {
			backend.injectTimeout();
			runtime.onPlayerChat("Alice", "@agent follow me", tick, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
			awaitFailureProcessed(runtime, eventBuffer, tick, Duration.ofSeconds(1));
		}

		assertTrue(runtime.isDegraded());
		assertTrue(eventBuffer.containsType("planner.degraded_entered"));
		assertTrue(runtime.lastResponse().orElseThrow().text().contains("@agent reset"));

		assertTrue(runtime.handleResetCommand("Alice", "@agent reset", 50L, eventBuffer));
		assertFalse(runtime.isDegraded());
		assertTrue(eventBuffer.containsType("planner.degraded_cleared"));
		assertTrue(eventBuffer.containsType("planner.reset_requested"));
		assertEquals("Planner state reset.", runtime.lastResponse().orElseThrow().text());
		runtime.shutdown();
	}

	@Test
	void degradedDirectChatEmitsBlockedEventAndVisibleResetReminder() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);

		for (long tick = 1L; tick <= 3L; tick++) {
			backend.injectTimeout();
			runtime.onPlayerChat("Alice", "@agent follow me", tick, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
			awaitFailureProcessed(runtime, eventBuffer, tick, Duration.ofSeconds(1));
		}

		long sinceSeqNo = eventBuffer.latestSeqNo();
		runtime.markReplyObserved();
		runtime.onPlayerChat("Alice", "@agent are you alive?", 50L, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);

		assertTrue(eventBuffer.containsTypeSince(sinceSeqNo, "planner.degraded_blocked"));
		assertTrue(runtime.lastResponse().orElseThrow().text().contains("@agent reset"));
		assertEquals("planner_degraded_visible_reply", runtime.pendingReplyReason());
		runtime.shutdown();
	}

	@Test
	void timeoutEmitsFreshVisibleReplyForDirectChat() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);
		backend.injectMockResponse(new PlannerResponse(
			"Still working on it.",
			new PlannerIntent("reply_only", null, null)
		));

		runtime.onPlayerChat("Alice", "@agent status", 10L, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
		DialogueResponse response = awaitResponse(runtime, eventBuffer, Duration.ofSeconds(1));
		assertEquals("Still working on it.", response.text());
		runtime.markReplyObserved();

		backend.injectTimeout();
		runtime.onPlayerChat("Alice", "@agent status?", 11L, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
		awaitFailureProcessed(runtime, eventBuffer, 1L, Duration.ofSeconds(1));

		assertFalse("failure_reused_last_response".equals(runtime.pendingReplyReason()));
		assertFalse(runtime.lastResponse().filter(last -> "Still working on it.".equals(last.text())).isPresent());
		runtime.shutdown();
	}

	@Test
	void timeoutDuringBackgroundTaskUpdateStaysSilent() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);

		backend.injectTimeout();
		runtime.onContextTrigger(
			PlannerTriggerType.SYSTEM,
			"server",
			"Background update",
			12L,
			SessionSnapshot.initial(),
			null,
			Optional.empty(),
			eventBuffer
		);
		awaitFailureProcessed(runtime, eventBuffer, 1L, Duration.ofSeconds(1));

		assertFalse(runtime.hasPendingReply());
		assertTrue(runtime.lastResponse().isEmpty());
		runtime.shutdown();
	}

	@Test
	void plannerConversationDebugSnapshotShowsAcceptedNativeVisionReply() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		DialogueRuntime runtime = newDialogueRuntime(
			backend,
			new CurrentViewVisionTool() {
				@Override
				public boolean isConfigured() {
					return true;
				}

				@Override
				public CompletableFuture<ai.moeru.airicraft.FirstPersonScreenshotService.CapturedScreenshot> requestCapture() {
					return CompletableFuture.completedFuture(
						new ai.moeru.airicraft.FirstPersonScreenshotService.CapturedScreenshot("png", 854, 480, 1920, 1080, 1L, new byte[]{1, 2, 3})
					);
				}

				@Override
				public CompletableFuture<ai.moeru.airicraft.agent.llm.VisionDescription> requestDescription(
					ai.moeru.airicraft.FirstPersonScreenshotService.CapturedScreenshot screenshot,
					String prompt
				) {
					return CompletableFuture.failedFuture(new AssertionError("Native tool image flow should not request external description"));
				}
			},
			PlannerVisionMode.NATIVE_TOOL_IMAGE
		);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);
		backend.injectMockResponse(new PlannerResponse(
			"",
			new PlannerIntent("none", null, null),
			new ai.moeru.airicraft.agent.llm.PlannerToolRequest("take_a_look", null)
		));
		backend.injectMockResponse(new PlannerResponse(
			"I see snow.",
			new PlannerIntent("reply_only", null, null)
		));

		runtime.onPlayerChat("Alice", "@agent take a look", 10L, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
		DialogueResponse response = awaitResponse(runtime, eventBuffer, Duration.ofSeconds(1));

		assertEquals("I see snow.", response.text());
		assertTrue(runtime.plannerConversationDebugSnapshot().messages().stream().anyMatch(message ->
			message.kind() == PlannerConversationDebugKind.ASSISTANT_TURN && message.text().contains("I see snow.")
		));
		assertFalse(runtime.plannerCanonicalConversationDebugSnapshot().messages().stream().anyMatch(message ->
			message.kind() == PlannerConversationDebugKind.ASSISTANT_TURN && message.text().contains("I see snow.")
		));
		runtime.shutdown();
	}

	@Test
	void playerChatPlannerRequestIncludesMissionEvidence() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);
		backend.injectMockResponse(new PlannerResponse(
			"I can craft jungle planks.",
			new PlannerIntent("reply_only", null, null)
		));
		MissionSpec mission = new MissionSpec("mission-craft", MissionType.CRAFT_ITEM, "Craft from inventory");
		MissionExecutionSnapshot missionExecution = new MissionExecutionSnapshot(
			mission,
			null,
			null,
			new WorldEvidence(
				java.util.Map.of(),
				java.util.Map.of("minecraft:jungle_log", 7),
				java.util.Map.of(),
				List.of(new CraftingOpportunity("jungle_log_to_jungle_planks", "minecraft:jungle_planks", 4, List.of("minecraft:jungle_log"))),
				"minecraft:overworld",
				0,
				64,
				0,
				null,
				10L
			),
			StepExecutionResult.idle(),
			TaskExecutionSnapshot.idle()
		);
		TaskSnapshot activeTask = new TaskSnapshot(
			TaskState.RUNNING,
			mission,
			null,
			null,
			new TaskProgressSnapshot(0, 0),
			TaskStep.NONE,
			TaskOwnership.NONE,
			"test",
			null,
			null,
			null,
			StepExecutionResult.idle(),
			10L
		);

		runtime.onPlayerChat(
			"Alice",
			"@agent what can you craft?",
			10L,
			SessionSnapshot.initial(),
			"Alice",
			Optional.empty(),
			activeTask,
			missionExecution,
			eventBuffer
		);
		awaitResponse(runtime, eventBuffer, Duration.ofSeconds(1));

		assertTrue(runtime.plannerConversationDebugSnapshot().messages().stream().anyMatch(message ->
			message.text().contains("[From {1*jungle_log} to 4*jungle_planks]: jungle_log_to_jungle_planks")
		));
		runtime.shutdown();
	}

	private static DialogueResponse awaitResponse(DialogueRuntime runtime, SemanticEventBuffer eventBuffer, Duration timeout) {
		Instant deadline = Instant.now().plus(timeout);
		long pollTick = 100L;
		while (Instant.now().isBefore(deadline)) {
			DialogueResponse response = runtime.poll(pollTick++, eventBuffer);
			if (response != null) {
				return response;
			}
			sleepBriefly();
		}
		throw new AssertionError("Timed out waiting for dialogue response");
	}

	private static void awaitFailureProcessed(DialogueRuntime runtime, SemanticEventBuffer eventBuffer, long tick, Duration timeout) {
		Instant deadline = Instant.now().plus(timeout);
		long pollTick = tick + 100L;
		while (Instant.now().isBefore(deadline)) {
			runtime.poll(pollTick++, eventBuffer);
			if (runtime.consecutiveFailureCount() >= tick) {
				return;
			}
			sleepBriefly();
		}
		throw new AssertionError("Timed out waiting for planner failure");
	}

	private static void sleepBriefly() {
		try {
			Thread.sleep(10L);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Interrupted while waiting", exception);
		}
	}

	private static DialogueRuntime newDialogueRuntime(OpenAiCompatibleLlmBackend backend) {
		return newDialogueRuntime(backend, CurrentViewVisionTool.disabled(), PlannerVisionMode.EXTERNAL_SUMMARY);
	}

	private static DialogueRuntime newDialogueRuntime(
		OpenAiCompatibleLlmBackend backend,
		CurrentViewVisionTool visionTool,
		PlannerVisionMode visionMode
	) {
		AgentConfig.LlmConfig config = AgentConfig.LlmConfig.defaults();
		Clock clock = Clock.systemDefaultZone();
		PlannerOrchestrator orchestrator = new PlannerOrchestrator(
			new PlannerExecutor(backend),
			new PlannerCompactionService(new OpenAiCompatibleChatClient(config)),
			new PlannerContextAggregator(
				clock,
				config.plannerCompactionTriggerTokens(),
				config.plannerPendingSemanticEventCap(),
				visionMode
			),
			visionTool,
			visionMode,
			config.visionImageDetail()
		);
		return new DialogueRuntime(orchestrator, 8, clock);
	}
}
