package ai.moeru.airicraft.agent.dialogue;

import ai.moeru.airicraft.agent.events.SemanticEventBuffer;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.llm.CompactionExecutionResult;
import ai.moeru.airicraft.agent.llm.LlmFailureType;
import ai.moeru.airicraft.agent.llm.PlannerConversationDebugSnapshot;
import ai.moeru.airicraft.agent.llm.PlannerExecutionResult;
import ai.moeru.airicraft.agent.llm.PlannerOrchestrator;
import ai.moeru.airicraft.agent.llm.PlannerOrchestratorDebugSnapshot;
import ai.moeru.airicraft.agent.llm.PlannerRequest;
import ai.moeru.airicraft.agent.llm.PlannerRequestSeed;
import ai.moeru.airicraft.agent.llm.PlannerResponse;
import ai.moeru.airicraft.agent.llm.PlannerTrigger;
import ai.moeru.airicraft.agent.llm.PlannerTriggerType;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import ai.moeru.airicraft.agent.tasks.MissionExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskSnapshot;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class DialogueRuntime {
	private final PlannerOrchestrator plannerOrchestrator;
	private final Clock clock;
	private final int maxRecentTurns;
	private final List<DialogueTurn> recentTurns = new ArrayList<>();

	private DialogueState state = DialogueCore.initialState();
	private int queuedTimeoutInjections;
	private boolean pendingTimeoutVisibleReply;

	public DialogueRuntime(PlannerOrchestrator plannerOrchestrator, int maxRecentTurns) {
		this(plannerOrchestrator, maxRecentTurns, Clock.systemDefaultZone());
	}

	public DialogueRuntime(PlannerOrchestrator plannerOrchestrator, int maxRecentTurns, Clock clock) {
		this.plannerOrchestrator = plannerOrchestrator;
		this.clock = clock;
		this.maxRecentTurns = Math.max(1, maxRecentTurns);
	}

	public Optional<DialogueResponse> lastResponse() {
		return Optional.ofNullable(state.lastResponse());
	}

	public boolean hasPendingReply() {
		return state.pendingReply();
	}

	public String pendingReplyReason() {
		return state.pendingReplyReason();
	}

	public void markReplyObserved() {
		state = DialogueCore.markReplyObserved(state);
	}

	public boolean isDegraded() {
		return state.degraded();
	}

	public boolean llmAvailable() {
		return plannerOrchestrator.isConfigured();
	}

	public PlannerOrchestratorDebugSnapshot plannerDebugSnapshot() {
		return plannerOrchestrator.debugSnapshot();
	}

	public PlannerConversationDebugSnapshot plannerConversationDebugSnapshot() {
		return plannerOrchestrator.conversationDebugSnapshot();
	}

	public PlannerConversationDebugSnapshot plannerCanonicalConversationDebugSnapshot() {
		return plannerOrchestrator.canonicalConversationDebugSnapshot();
	}

	public List<String> plannerContextExcerpt() {
		return plannerOrchestrator.contextExcerpt();
	}

	public boolean startDebugCompaction() {
		return plannerOrchestrator.startDebugCompaction();
	}

	public CompactionExecutionResult pollDebugCompaction() {
		return plannerOrchestrator.pollDebugCompaction();
	}

	public long lastFailureTick() {
		return state.lastFailureTick();
	}

	public int consecutiveFailureCount() {
		return state.consecutiveFailureCount();
	}

	public LlmFailureType lastFailureType() {
		return state.lastFailureType();
	}

	public DialogueSnapshot snapshot() {
		return new DialogueSnapshot(
			List.copyOf(recentTurns),
			state.lastResponse(),
			state.pendingReply(),
			state.pendingReplyReason(),
			state.degraded(),
			state.consecutiveFailureCount(),
			state.lastFailureType(),
			state.lastFailureTick()
		);
	}

	public void injectMockResponse(PlannerResponse response) {
		plannerOrchestrator.injectMockResponse(response);
	}

	public void injectTimeout() {
		queuedTimeoutInjections++;
	}

	public boolean handleResetCommand(String senderName, String plainTextMessage, long tick, SemanticEventBuffer eventBuffer) {
		if (!DialogueCore.isResetCommand(plainTextMessage)) {
			return false;
		}

		appendTurn(new DialogueTurn(senderName, plainTextMessage, tick, clock.millis()));
		plannerOrchestrator.reset();
		queuedTimeoutInjections = 0;
		applyTransition(DialogueCore.onReset(state, senderName, tick), tick, eventBuffer);
		return true;
	}

	public void onPlayerChat(
		String senderName,
		String plainTextMessage,
		long tick,
		SessionSnapshot sessionSnapshot,
		String primaryInteractionPlayer,
		Optional<GoalSnapshot> activeGoal,
		TaskSnapshot activeTask,
		MissionExecutionSnapshot missionExecution,
		SemanticEventBuffer eventBuffer
	) {
		long timestampMs = clock.millis();
		appendTurn(new DialogueTurn(senderName, plainTextMessage, tick, timestampMs));
		submitPlannerTrigger(
			PlannerRequest.ofTrigger(
				tick,
				timestampMs,
				sessionSnapshot.mode(),
				primaryInteractionPlayer,
				activeGoal.orElse(null),
				PlannerTriggerType.CHAT,
				senderName,
				plainTextMessage,
				null
			),
			eventBuffer,
			timestampMs,
			true
		);
	}

	public void onContextTrigger(
		PlannerTriggerType triggerType,
		String senderName,
		String plainTextMessage,
		long tick,
		SessionSnapshot sessionSnapshot,
		String primaryInteractionPlayer,
		Optional<GoalSnapshot> activeGoal,
		SemanticEventBuffer eventBuffer
	) {
		long timestampMs = clock.millis();
		submitPlannerTrigger(
			PlannerRequest.ofTrigger(
				tick,
				timestampMs,
				sessionSnapshot.mode(),
				primaryInteractionPlayer,
				activeGoal.orElse(null),
				triggerType,
				senderName,
				plainTextMessage,
				null
			),
			eventBuffer,
			timestampMs,
			triggerType == PlannerTriggerType.CHAT && senderName != null && !"system".equalsIgnoreCase(senderName)
		);
	}

	public void onPlannerTrigger(
		PlannerTrigger trigger,
		SessionSnapshot sessionSnapshot,
		String primaryInteractionPlayer,
		Optional<GoalSnapshot> activeGoal,
		SemanticEventBuffer plannerEventBuffer
	) {
		if (trigger == null) {
			return;
		}
		submitPlannerTrigger(
			PlannerRequest.ofTrigger(
				trigger.tick(),
				trigger.timestampMs(),
				sessionSnapshot.mode(),
				primaryInteractionPlayer,
				activeGoal.orElse(null),
				trigger.type(),
				trigger.speaker(),
				trigger.text(),
				null
			),
			plannerEventBuffer,
			trigger.timestampMs(),
			trigger.type() == PlannerTriggerType.CHAT
				&& trigger.speaker() != null
				&& !"system".equalsIgnoreCase(trigger.speaker())
		);
	}

	public void onPlayerChat(
		String senderName,
		String plainTextMessage,
		long tick,
		SessionSnapshot sessionSnapshot,
		String primaryInteractionPlayer,
		Optional<GoalSnapshot> activeGoal,
		SemanticEventBuffer eventBuffer
	) {
		onPlayerChat(senderName, plainTextMessage, tick, sessionSnapshot, primaryInteractionPlayer, activeGoal, null, null, eventBuffer);
	}

	public void onInternalTaskUpdate(
		String updateMessage,
		long tick,
		SessionSnapshot sessionSnapshot,
		Optional<GoalSnapshot> activeGoal,
		TaskSnapshot activeTask,
		MissionExecutionSnapshot missionExecution,
		SemanticEventBuffer eventBuffer
	) {
		long timestampMs = clock.millis();
		appendTurn(new DialogueTurn("system", updateMessage, tick, timestampMs));
		if (state.degraded() || plannerOrchestrator.hasInFlight() || !plannerOrchestrator.isConfigured()) {
			return;
		}
		plannerOrchestrator.recordEvents(eventBuffer.query(null), timestampMs);
		plannerOrchestrator.submit(new PlannerRequest(
			tick,
			timestampMs,
			sessionSnapshot.mode(),
			null,
			activeGoal.orElse(null),
			activeTask,
			missionExecution,
			"system",
			updateMessage,
			null
		));
		pendingTimeoutVisibleReply = false;
	}

	public void onInternalTaskUpdate(
		String updateMessage,
		long tick,
		SessionSnapshot sessionSnapshot,
		Optional<GoalSnapshot> activeGoal,
		SemanticEventBuffer eventBuffer
	) {
		onInternalTaskUpdate(updateMessage, tick, sessionSnapshot, activeGoal, null, null, eventBuffer);
	}

	public DialogueResponse poll(long tick, SemanticEventBuffer eventBuffer) {
		if (queuedTimeoutInjections > 0 && !plannerOrchestrator.hasInFlight()) {
			queuedTimeoutInjections--;
			applyTransition(DialogueCore.onPlannerFailure(state, LlmFailureType.TIMEOUT, "Injected LLM timeout", pendingTimeoutVisibleReply, tick), tick, eventBuffer);
			pendingTimeoutVisibleReply = false;
			return null;
		}

		PlannerExecutionResult result = plannerOrchestrator.poll();
		if (result == null) {
			return null;
		}

		if (!result.succeeded()) {
			boolean timeoutVisibleReply = pendingTimeoutVisibleReply || isDirectChatRequest(result.request());
			applyTransition(
				DialogueCore.onPlannerFailure(
					state,
					result.failureType(),
					result.failureMessage(),
					timeoutVisibleReply,
					tick
				),
				tick,
				eventBuffer
			);
			pendingTimeoutVisibleReply = false;
			return null;
		}

		DialogueTransition transition = DialogueCore.onPlannerSuccess(state, result.response(), tick);
		applyTransition(transition, tick, eventBuffer);
		pendingTimeoutVisibleReply = false;
		plannerOrchestrator.onAcceptedReplyRecorded();
		return transition.lastVisibleResponse();
	}

	public void resetLlmState(long tick, SemanticEventBuffer eventBuffer) {
		plannerOrchestrator.reset();
		queuedTimeoutInjections = 0;
		pendingTimeoutVisibleReply = false;
		if (state.degraded()) {
			applyEffects(List.of(DialogueEffect.appendSemanticEvent("planner.degraded_cleared", java.util.Map.of())), tick, eventBuffer);
		}
		state = DialogueCore.initialState();
	}

	public void clear() {
		state = DialogueCore.initialState();
		queuedTimeoutInjections = 0;
		pendingTimeoutVisibleReply = false;
		recentTurns.clear();
		plannerOrchestrator.reset();
	}

	public void shutdown() {
		state = DialogueCore.initialState();
		queuedTimeoutInjections = 0;
		pendingTimeoutVisibleReply = false;
		recentTurns.clear();
		plannerOrchestrator.shutdown();
	}

	public static boolean isResetCommand(String plainTextMessage) {
		return DialogueCore.isResetCommand(plainTextMessage);
	}

	private void submitPlannerTrigger(
		PlannerRequest request,
		SemanticEventBuffer eventBuffer,
		long timestampMs,
		boolean timeoutVisibleReply
	) {
		if (state.degraded()) {
			applyTransition(
				DialogueCore.onPlannerDegradedBlocked(state, request.senderName(), timeoutVisibleReply, request.tick()),
				request.tick(),
				eventBuffer
			);
			return;
		}
		Long sinceSeqNo = plannerOrchestrator.lastObservedEventSeqNo();
		plannerOrchestrator.recordEvents(
			eventBuffer.query(sinceSeqNo <= 0L ? null : sinceSeqNo),
			new PlannerRequestSeed(
				request.tick(),
				timestampMs,
				request.sessionMode(),
				request.primaryInteractionPlayer(),
				request.activeGoal()
			)
		);
		pendingTimeoutVisibleReply = timeoutVisibleReply;
		plannerOrchestrator.submit(request);
	}

	private void recordAgentTurn(String text, long tick) {
		DialogueTurn turn = new DialogueTurn(DialogueSpeakerLabels.AGENT, text, tick, clock.millis());
		appendTurn(turn);
		plannerOrchestrator.recordAssistantTurn(turn);
	}

	private void appendTurn(DialogueTurn turn) {
		recentTurns.add(turn);
		while (recentTurns.size() > maxRecentTurns) {
			recentTurns.remove(0);
		}
	}

	private void applyTransition(DialogueTransition transition, long tick, SemanticEventBuffer eventBuffer) {
		state = transition.state();
		applyEffects(transition.effects(), tick, eventBuffer);
		for (DialogueResponse response : transition.visibleResponses()) {
			if (response != null && response.text() != null && !response.text().isBlank()) {
				recordAgentTurn(response.text(), tick);
			}
		}
	}

	private static void applyEffects(List<DialogueEffect> effects, long tick, SemanticEventBuffer eventBuffer) {
		for (DialogueEffect effect : effects) {
			if (effect instanceof DialogueEffect.AppendSemanticEvent appendSemanticEvent) {
				eventBuffer.append(tick, appendSemanticEvent.type(), appendSemanticEvent.payload());
			}
		}
	}

	private static boolean isDirectChatRequest(PlannerRequest request) {
		if (request == null || request.triggerBatch() == null || request.triggerBatch().triggers().isEmpty()) {
			return false;
		}
		return request.triggerBatch().triggers().stream().allMatch(trigger ->
			trigger.type() == PlannerTriggerType.CHAT
				&& trigger.speaker() != null
				&& !"system".equalsIgnoreCase(trigger.speaker())
		);
	}

}
