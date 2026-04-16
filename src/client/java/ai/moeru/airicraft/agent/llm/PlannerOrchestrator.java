package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.Airicraft;
import ai.moeru.airicraft.BridgeUnavailableException;
import ai.moeru.airicraft.FirstPersonScreenshotService;
import ai.moeru.airicraft.agent.debug.AgentDebugRecorder;
import ai.moeru.airicraft.agent.observability.AgentObservability;
import ai.moeru.airicraft.agent.observability.NoopObservability;
import ai.moeru.airicraft.agent.dialogue.DialogueTurn;
import ai.moeru.airicraft.agent.events.EventPolicyChanges;
import ai.moeru.airicraft.agent.events.EventPolicyMatch;
import ai.moeru.airicraft.agent.events.EventPolicyRuleUpsert;
import ai.moeru.airicraft.agent.events.SemanticEventQueryResult;
import ai.moeru.airicraft.agent.session.SessionMode;
import com.google.gson.JsonElement;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class PlannerOrchestrator {
	private static final String VISUAL_TOOL_NAME = "take_a_look";
	private static final String INVENTORY_TOOL_NAME = "inspect_inventory";
	private static final String RECIPES_TOOL_NAME = "inspect_recipes";
	private static final String NATIVE_TOOL_RESULT_TEXT = "Tool result for take_a_look: current first-person view attached.";
	private static final int SESSION_MAX_ATTEMPTS = 2;
	private static final long SESSION_RETRY_BACKOFF_MS = 250L;
	private static final int SESSION_COALESCE_STEP_MS = 10;
	private static final int SESSION_COALESCE_MIN_MS = 10;
	private static final int SESSION_COALESCE_MAX_MS = 100;
	private static final int CONVERSATION_HISTORY_CARD_LIMIT = 48;

	private final PlannerExecutor plannerExecutor;
	private final PlannerCompactionService compactionService;
	private final PlannerContextAggregator contextAggregator;
	private final PlannerSessionCoordinator sessionCoordinator;
	private final CurrentViewVisionTool visionTool;
	private final CurrentInventoryTool inventoryTool;
	private final PlannerVisionMode visionMode;
	private final String imageDetail;
	private final Clock clock;
	private final long coalesceStepMs;
	private final long coalesceMinMs;
	private final long coalesceMaxMs;
	private final AgentObservability observability;
	private final PlannerLifecycleListener lifecycleListener;
	private final AgentDebugRecorder debugRecorder;

	private PlannerRequest pendingSubmitRequest;
	private PendingToolExecution pendingToolExecution;
	private CompactionExecutionResult lastCompactionResult;
	private boolean awaitingAcceptedReplyRecord;
	private JsonElement pendingAcceptedAssistantRawContent;
	private volatile boolean captureInFlight;
	private boolean coalescePending;
	private long coalesceReadyAtMs = -1L;
	private long coalesceWindowMs;
	private PlannerContextSnapshot coalesceSupersededSnapshot;
	private PlannerConversationDebugSnapshot lastSubmittedConversation = PlannerConversationDebugSnapshot.empty();
	private PlannerConversationDebugSnapshot lastVisibleConversation = PlannerConversationDebugSnapshot.empty();
	private Context turnContext;

	public PlannerOrchestrator(
		PlannerExecutor plannerExecutor,
		PlannerCompactionService compactionService,
		PlannerContextAggregator contextAggregator,
		CurrentViewVisionTool visionTool,
		PlannerVisionMode visionMode,
		String imageDetail
	) {
		this(
			plannerExecutor,
			compactionService,
			contextAggregator,
			visionTool,
			visionMode,
			imageDetail,
			3,
			SESSION_COALESCE_STEP_MS,
			SESSION_COALESCE_MIN_MS,
			SESSION_COALESCE_MAX_MS,
			Clock.systemDefaultZone(),
			NoopObservability.INSTANCE,
			PlannerLifecycleListener.NO_OP,
			new AgentDebugRecorder()
		);
	}

	public PlannerOrchestrator(
		PlannerExecutor plannerExecutor,
		PlannerCompactionService compactionService,
		PlannerContextAggregator contextAggregator,
		CurrentViewVisionTool visionTool,
		PlannerVisionMode visionMode,
		String imageDetail,
		int plannerSessionMaxConcurrentAttempts
	) {
		this(
			plannerExecutor,
			compactionService,
			contextAggregator,
			visionTool,
			visionMode,
			imageDetail,
			plannerSessionMaxConcurrentAttempts,
			SESSION_COALESCE_STEP_MS,
			SESSION_COALESCE_MIN_MS,
			SESSION_COALESCE_MAX_MS,
			Clock.systemDefaultZone(),
			NoopObservability.INSTANCE,
			PlannerLifecycleListener.NO_OP,
			new AgentDebugRecorder()
		);
	}

	public PlannerOrchestrator(
		PlannerExecutor plannerExecutor,
		PlannerCompactionService compactionService,
		PlannerContextAggregator contextAggregator,
		CurrentViewVisionTool visionTool,
		PlannerVisionMode visionMode,
		String imageDetail,
		int plannerSessionMaxConcurrentAttempts,
		int plannerSessionCoalesceStepMillis,
		int plannerSessionCoalesceMinMillis,
		int plannerSessionCoalesceMaxMillis
	) {
		this(
			plannerExecutor,
			compactionService,
			contextAggregator,
			visionTool,
			visionMode,
			imageDetail,
			plannerSessionMaxConcurrentAttempts,
			plannerSessionCoalesceStepMillis,
			plannerSessionCoalesceMinMillis,
			plannerSessionCoalesceMaxMillis,
			Clock.systemDefaultZone(),
			NoopObservability.INSTANCE,
			PlannerLifecycleListener.NO_OP,
			new AgentDebugRecorder()
		);
	}

	public PlannerOrchestrator(
		PlannerExecutor plannerExecutor,
		PlannerCompactionService compactionService,
		PlannerContextAggregator contextAggregator,
		CurrentViewVisionTool visionTool,
		PlannerVisionMode visionMode,
		String imageDetail,
		int plannerSessionMaxConcurrentAttempts,
		int plannerSessionCoalesceStepMillis,
		int plannerSessionCoalesceMinMillis,
		int plannerSessionCoalesceMaxMillis,
		AgentObservability observability
	) {
		this(
			plannerExecutor,
			compactionService,
			contextAggregator,
			visionTool,
			visionMode,
			imageDetail,
			plannerSessionMaxConcurrentAttempts,
			plannerSessionCoalesceStepMillis,
			plannerSessionCoalesceMinMillis,
			plannerSessionCoalesceMaxMillis,
			Clock.systemDefaultZone(),
			observability,
			PlannerLifecycleListener.NO_OP,
			new AgentDebugRecorder()
		);
	}

	public PlannerOrchestrator(
		PlannerExecutor plannerExecutor,
		PlannerCompactionService compactionService,
		PlannerContextAggregator contextAggregator,
		CurrentViewVisionTool visionTool,
		PlannerVisionMode visionMode,
		String imageDetail,
		int plannerSessionMaxConcurrentAttempts,
		int plannerSessionCoalesceStepMillis,
		int plannerSessionCoalesceMinMillis,
		int plannerSessionCoalesceMaxMillis,
		AgentObservability observability,
		PlannerLifecycleListener lifecycleListener
	) {
		this(
			plannerExecutor,
			compactionService,
			contextAggregator,
			visionTool,
			visionMode,
			imageDetail,
			plannerSessionMaxConcurrentAttempts,
			plannerSessionCoalesceStepMillis,
			plannerSessionCoalesceMinMillis,
			plannerSessionCoalesceMaxMillis,
			Clock.systemDefaultZone(),
			observability,
			lifecycleListener,
			new AgentDebugRecorder()
		);
	}

	public PlannerOrchestrator(
		PlannerExecutor plannerExecutor,
		PlannerCompactionService compactionService,
		PlannerContextAggregator contextAggregator,
		CurrentViewVisionTool visionTool,
		PlannerVisionMode visionMode,
		String imageDetail,
		int plannerSessionMaxConcurrentAttempts,
		int plannerSessionCoalesceStepMillis,
		int plannerSessionCoalesceMinMillis,
		int plannerSessionCoalesceMaxMillis,
		AgentObservability observability,
		PlannerLifecycleListener lifecycleListener,
		AgentDebugRecorder debugRecorder
	) {
		this(
			plannerExecutor,
			compactionService,
			contextAggregator,
			visionTool,
			visionMode,
			imageDetail,
			plannerSessionMaxConcurrentAttempts,
			plannerSessionCoalesceStepMillis,
			plannerSessionCoalesceMinMillis,
			plannerSessionCoalesceMaxMillis,
			Clock.systemDefaultZone(),
			observability,
			lifecycleListener,
			debugRecorder
		);
	}

	PlannerOrchestrator(
		PlannerExecutor plannerExecutor,
		PlannerCompactionService compactionService,
		PlannerContextAggregator contextAggregator,
		CurrentViewVisionTool visionTool,
		PlannerVisionMode visionMode,
		String imageDetail,
		int plannerSessionMaxConcurrentAttempts,
		int plannerSessionCoalesceStepMillis,
		int plannerSessionCoalesceMinMillis,
		int plannerSessionCoalesceMaxMillis,
		Clock clock,
		AgentObservability observability,
		PlannerLifecycleListener lifecycleListener
	) {
		this(
			plannerExecutor,
			compactionService,
			contextAggregator,
			visionTool,
			visionMode,
			imageDetail,
			plannerSessionMaxConcurrentAttempts,
			plannerSessionCoalesceStepMillis,
			plannerSessionCoalesceMinMillis,
			plannerSessionCoalesceMaxMillis,
			clock,
			observability,
			lifecycleListener,
			new AgentDebugRecorder()
		);
	}

	PlannerOrchestrator(
		PlannerExecutor plannerExecutor,
		PlannerCompactionService compactionService,
		PlannerContextAggregator contextAggregator,
		CurrentViewVisionTool visionTool,
		PlannerVisionMode visionMode,
		String imageDetail,
		int plannerSessionMaxConcurrentAttempts,
		int plannerSessionCoalesceStepMillis,
		int plannerSessionCoalesceMinMillis,
		int plannerSessionCoalesceMaxMillis,
		Clock clock,
		AgentObservability observability,
		PlannerLifecycleListener lifecycleListener,
		AgentDebugRecorder debugRecorder
	) {
		this(
			plannerExecutor,
			compactionService,
			contextAggregator,
			visionTool,
			CurrentInventoryTool.disabled(),
			visionMode,
			imageDetail,
			plannerSessionMaxConcurrentAttempts,
			plannerSessionCoalesceStepMillis,
			plannerSessionCoalesceMinMillis,
			plannerSessionCoalesceMaxMillis,
			clock,
			observability,
			lifecycleListener,
			debugRecorder
		);
	}

	public PlannerOrchestrator(
		PlannerExecutor plannerExecutor,
		PlannerCompactionService compactionService,
		PlannerContextAggregator contextAggregator,
		CurrentViewVisionTool visionTool,
		CurrentInventoryTool inventoryTool,
		PlannerVisionMode visionMode,
		String imageDetail,
		int plannerSessionMaxConcurrentAttempts,
		int plannerSessionCoalesceStepMillis,
		int plannerSessionCoalesceMinMillis,
		int plannerSessionCoalesceMaxMillis,
		AgentObservability observability,
		PlannerLifecycleListener lifecycleListener,
		AgentDebugRecorder debugRecorder
	) {
		this(
			plannerExecutor,
			compactionService,
			contextAggregator,
			visionTool,
			inventoryTool,
			visionMode,
			imageDetail,
			plannerSessionMaxConcurrentAttempts,
			plannerSessionCoalesceStepMillis,
			plannerSessionCoalesceMinMillis,
			plannerSessionCoalesceMaxMillis,
			Clock.systemDefaultZone(),
			observability,
			lifecycleListener,
			debugRecorder
		);
	}

	PlannerOrchestrator(
		PlannerExecutor plannerExecutor,
		PlannerCompactionService compactionService,
		PlannerContextAggregator contextAggregator,
		CurrentViewVisionTool visionTool,
		CurrentInventoryTool inventoryTool,
		PlannerVisionMode visionMode,
		String imageDetail,
		int plannerSessionMaxConcurrentAttempts,
		int plannerSessionCoalesceStepMillis,
		int plannerSessionCoalesceMinMillis,
		int plannerSessionCoalesceMaxMillis,
		Clock clock,
		AgentObservability observability,
		PlannerLifecycleListener lifecycleListener,
		AgentDebugRecorder debugRecorder
	) {
		this.plannerExecutor = Objects.requireNonNull(plannerExecutor, "plannerExecutor");
		this.compactionService = Objects.requireNonNull(compactionService, "compactionService");
		this.contextAggregator = Objects.requireNonNull(contextAggregator, "contextAggregator");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.sessionCoordinator = new PlannerSessionCoordinator(
			plannerExecutor,
			this.clock,
			plannerSessionMaxConcurrentAttempts,
			SESSION_MAX_ATTEMPTS,
			SESSION_RETRY_BACKOFF_MS,
			this::recordSubmittedConversation
		);
		this.visionTool = Objects.requireNonNull(visionTool, "visionTool");
		this.inventoryTool = Objects.requireNonNull(inventoryTool, "inventoryTool");
		this.visionMode = Objects.requireNonNull(visionMode, "visionMode");
		this.imageDetail = Objects.requireNonNull(imageDetail, "imageDetail");
		this.coalesceStepMs = Math.max(0L, plannerSessionCoalesceStepMillis);
		this.coalesceMinMs = Math.max(0L, plannerSessionCoalesceMinMillis);
		this.coalesceMaxMs = Math.max(this.coalesceMinMs, plannerSessionCoalesceMaxMillis);
		this.observability = Objects.requireNonNull(observability, "observability");
		this.lifecycleListener = Objects.requireNonNull(lifecycleListener, "lifecycleListener");
		this.debugRecorder = Objects.requireNonNull(debugRecorder, "debugRecorder");
	}

	public boolean isConfigured() {
		return plannerExecutor.isConfigured();
	}

	public boolean hasInFlight() {
		return contextAggregator.hasPendingOverflowFlush()
			|| coalescePending
			|| sessionCoordinator.hasInFlight()
			|| compactionService.hasInFlight()
			|| pendingToolExecution != null;
	}

	public PlannerOrchestratorDebugSnapshot debugSnapshot() {
		PlannerSessionSnapshot activeSession = sessionCoordinator.activeSnapshot();
		PlannerSessionPhase currentPhase = activeSession == null ? null : activeSession.phase();
		return new PlannerOrchestratorDebugSnapshot(
			isConfigured(),
			visionMode.wireValue(),
			hasInFlight(),
			sessionCoordinator.activeAttemptCount() > 0,
			compactionService.hasInFlight(),
			captureInFlight,
			pendingToolExecution != null,
			currentPhase == PlannerSessionPhase.TOOL_WAIT || currentPhase == PlannerSessionPhase.TOOL_FOLLOW_UP,
			activeSession == null ? pendingSubmitRequest : activeSession.request(),
			lastCompactionResult,
			contextAggregator.debugSnapshot(),
			sessionCoordinator.activeGeneration(),
			currentPhase == null ? null : currentPhase.name(),
			sessionCoordinator.activeAttemptCount(),
			sessionCoordinator.pendingNewestGeneration(),
			sessionCoordinator.supersededCount(),
			activeSession != null && activeSession.retryPending(),
			activeSession == null ? -1L : activeSession.retryReadyAtMs(),
			coalescePending,
			coalesceReadyAtMs,
			coalesceWindowMs
		);
	}

	public PlannerConversationDebugSnapshot conversationDebugSnapshot() {
		return displayConversationDebugSnapshot();
	}

	public PlannerConversationDebugSnapshot canonicalConversationDebugSnapshot() {
		return lastSubmittedConversation;
	}

	public List<String> contextExcerpt() {
		if (lastVisibleConversation.isEmpty()) {
			return List.of();
		}

		ArrayList<String> excerpt = new ArrayList<>();
		for (PlannerConversationDebugMessage message : lastVisibleConversation.messages()) {
			if (message.kind() != PlannerConversationDebugKind.NOTICE && message.kind() != PlannerConversationDebugKind.CHECKPOINT) {
				continue;
			}
			if (message.text() != null && !message.text().isBlank()) {
				excerpt.add(message.text());
			}
		}
		return List.copyOf(excerpt);
	}

	public long lastObservedEventSeqNo() {
		return contextAggregator.lastObservedEventSeqNo();
	}

	public boolean submit(PlannerRequest request) {
		Objects.requireNonNull(request, "request");
		if (request.triggerBatch() == null || request.triggerBatch().isEmpty()) {
			return false;
		}
		lifecycleListener.onPlannerTurnSubmitted(request);
		contextAggregator.recordPlannerRequestSeed(PlannerRequestSeed.fromRequest(request));
		contextAggregator.cancelPendingOverflowFlush();
		turnContext = observability.startTurnSpan(request, buildTurnId(request));
		for (PlannerTrigger trigger : request.triggerBatch().triggers()) {
			contextAggregator.enqueueTrigger(trigger);
		}
		pendingSubmitRequest = request;
		if (compactionService.hasInFlight()) {
			return true;
		}
		sessionCoordinator.drainCompletedResults();
		if (awaitingAcceptedReplyRecord) {
			return true;
		}
		if (sessionCoordinator.hasReplaceableActiveSession()) {
			if (sessionCoordinator.hasReadyResultForActiveSession()) {
				return true;
			}
			coalesceSupersededSnapshot = sessionCoordinator.supersedeActiveSessionIfReplaceable();
			armCoalesceWindow();
			return coalesceWindowMs > 0L || startQueuedWorkIfPossible();
		}
		if (coalescePending) {
			armCoalesceWindow();
			return coalesceWindowMs > 0L || startQueuedWorkIfPossible();
		}
		return startQueuedWorkIfPossible();
	}

	public PlannerExecutionResult poll() {
		if (compactionService.hasInFlight()) {
			CompactionExecutionResult compactionResult = compactionService.poll();
			if (compactionResult == null) {
				return null;
			}
			completeCompaction(compactionResult);
			if (compactionResult.succeeded() && pendingSubmitRequest != null && contextAggregator.hasQueuedTriggers()) {
				startQueuedWorkIfPossible();
			}
			return null;
		}

		if (pendingToolExecution != null) {
			PlannerExecutionResult toolContinuation = continueAfterTool();
			if (toolContinuation != null) {
				return toolContinuation;
			}
		}

		PlannerExecutionResult plannerResult = sessionCoordinator.poll();
		if (plannerResult == null) {
			if (coalescePending) {
				startQueuedWorkIfPossible();
			}
			return null;
		}
		if (!plannerResult.succeeded()) {
			appendFailureCard(plannerResult);
			debugRecorder.recordPlannerCompletion(plannerResult);
			observability.recordFailure(turnContext, plannerResult.failureType().name(), plannerResult.failureMessage(), null);
			lifecycleListener.onPlannerExecutionFailed(plannerResult);
			sessionCoordinator.finishGeneration(plannerResult.generation(), true);
			endTurnSpan();
			return plannerResult;
		}

		contextAggregator.recordUsage(plannerResult.usage());
		lifecycleListener.onPlannerExecutionSucceeded(plannerResult);
		PlannerToolRequest toolRequest = plannerResult.response().toolRequest();
		if (toolRequest == null) {
			appendAssistantOutcomeCard(plannerResult);
			appendOperationCards(plannerResult);
			debugRecorder.recordPlannerCompletion(plannerResult);
			acceptGeneration(plannerResult);
			return plannerResult;
		}
		if (plannerResult.phase() == PlannerSessionPhase.TOOL_FOLLOW_UP) {
			// TODO: Support bounded multi-tool plans so the planner can request inventory and recipes in one goal.
			PlannerExecutionResult failure = parseFailure(plannerResult, "Planner requested a tool more than once");
			appendFailureCard(failure);
			debugRecorder.recordPlannerCompletion(failure);
			sessionCoordinator.finishGeneration(plannerResult.generation(), true);
			return failure;
		}

		String toolIntentType = toolIntentType(plannerResult.response());
		if (!hasToolCompatibleIntent(plannerResult.response())) {
			Airicraft.LOGGER.warn(
				"Planner returned invalid tool response intentType={} toolRequestType={} toolPrompt={} replyText={}",
				toolIntentType,
				toolRequest.type(),
				summarizeForLog(toolRequest.prompt()),
				summarizeForLog(plannerResult.response().replyText())
			);
			PlannerExecutionResult failure = parseFailure(plannerResult, "Tool requests cannot set goal intents");
			appendFailureCard(failure);
			debugRecorder.recordPlannerCompletion(failure);
			sessionCoordinator.finishGeneration(plannerResult.generation(), true);
			return failure;
		}
		if (!isValidToolRequest(toolRequest)) {
			Airicraft.LOGGER.warn(
				"Planner returned invalid tool request type={} prompt={}",
				toolRequest.type(),
				summarizeForLog(toolRequest.prompt())
			);
			PlannerExecutionResult failure = parseFailure(plannerResult, "Planner requested an invalid tool");
			appendFailureCard(failure);
			debugRecorder.recordPlannerCompletion(failure);
			sessionCoordinator.finishGeneration(plannerResult.generation(), true);
			return failure;
		}
		if (!"none".equals(toolIntentType)) {
			Airicraft.LOGGER.info(
				"Planner returned tool request with non-none intent; ignoring intentType={} toolRequestType={}",
				toolIntentType,
				toolRequest.type()
			);
		}
		if (plannerResult.response().replyText() != null && !plannerResult.response().replyText().isBlank()) {
			Airicraft.LOGGER.info(
				"Planner returned tool request with stray replyText; ignoring text={} toolRequestType={}",
				summarizeForLog(plannerResult.response().replyText()),
				toolRequest.type()
			);
		}

		appendToolRequestCard(plannerResult);
		debugRecorder.recordPlannerCompletion(plannerResult);
		lifecycleListener.onToolRequested(plannerResult.generation(), toolRequest);
		sessionCoordinator.markToolWait(plannerResult.generation());
		pendingToolExecution = new PendingToolExecution(
			plannerResult.generation(),
			toolRequestSummary(toolRequest),
			requestPlannerTool(toolRequest),
			plannerResult.response().rawAssistantContent()
		);
		return null;
	}

	public void injectMockResponse(PlannerResponse response) {
		plannerExecutor.injectMockResponse(response);
	}

	public void injectTimeout() {
		plannerExecutor.injectTimeout();
	}

	public void recordAssistantTurn(DialogueTurn turn) {
		contextAggregator.recordAgentTurn(turn, pendingAcceptedAssistantRawContent);
		pendingAcceptedAssistantRawContent = null;
	}

	public void onAcceptedReplyRecorded() {
		awaitingAcceptedReplyRecord = false;
		clearCoalesceState();
		if (
			(pendingSubmitRequest != null && contextAggregator.hasQueuedTriggers())
				|| contextAggregator.hasPendingOverflowFlush()
		) {
			startQueuedWorkIfPossible();
		}
	}

	public void recordEvents(SemanticEventQueryResult queryResult, long anchorTimeMs) {
		recordEvents(queryResult, new PlannerRequestSeed(anchorTimeMs / 50L, anchorTimeMs, SessionMode.OUT_OF_WORLD, null, null));
	}

	public void recordEvents(SemanticEventQueryResult queryResult, PlannerRequestSeed requestSeed) {
		contextAggregator.recordObservedEvents(queryResult, requestSeed);
		if (contextAggregator.hasPendingOverflowFlush()) {
			startQueuedWorkIfPossible();
		}
	}

	public boolean startDebugCompaction() {
		if (!isConfigured() || hasInFlight()) {
			return false;
		}
		lastCompactionResult = null;
		return compactionService.submit(contextAggregator.buildCompactionConversation());
	}

	public CompactionExecutionResult pollDebugCompaction() {
		if (!compactionService.hasInFlight()) {
			return lastCompactionResult;
		}
		CompactionExecutionResult compactionResult = compactionService.poll();
		if (compactionResult == null) {
			return null;
		}
		completeCompaction(compactionResult);
		return compactionResult;
	}

	public void reset() {
		cancelPendingTool();
		sessionCoordinator.reset();
		compactionService.reset();
		contextAggregator.clear();
		pendingSubmitRequest = null;
		lastCompactionResult = null;
		awaitingAcceptedReplyRecord = false;
		pendingAcceptedAssistantRawContent = null;
		lastSubmittedConversation = PlannerConversationDebugSnapshot.empty();
		lastVisibleConversation = PlannerConversationDebugSnapshot.empty();
		debugRecorder.recordConversationSources(lastSubmittedConversation, lastVisibleConversation);
		clearCoalesceState();
		endTurnSpan();
		lifecycleListener.onReset("reset");
	}

	public void shutdown() {
		cancelPendingTool();
		sessionCoordinator.shutdown();
		compactionService.shutdown();
		contextAggregator.clear();
		pendingSubmitRequest = null;
		lastCompactionResult = null;
		awaitingAcceptedReplyRecord = false;
		pendingAcceptedAssistantRawContent = null;
		lastSubmittedConversation = PlannerConversationDebugSnapshot.empty();
		lastVisibleConversation = PlannerConversationDebugSnapshot.empty();
		debugRecorder.recordConversationSources(lastSubmittedConversation, lastVisibleConversation);
		clearCoalesceState();
		endTurnSpan();
		lifecycleListener.onReset("shutdown");
	}

	private boolean startQueuedWorkIfPossible() {
		boolean hasRealTrigger = pendingSubmitRequest != null && contextAggregator.hasQueuedTriggers();
		boolean hasOverflowFlush = contextAggregator.hasPendingOverflowFlush();
		if (!hasRealTrigger && !hasOverflowFlush) {
			clearCoalesceState();
			return true;
		}
		if (awaitingAcceptedReplyRecord) {
			return true;
		}
		if (hasRealTrigger && hasOverflowFlush) {
			contextAggregator.cancelPendingOverflowFlush();
			hasOverflowFlush = false;
		}
		if (hasRealTrigger && contextAggregator.compactionPending()) {
			if (sessionCoordinator.hasInFlight() || pendingToolExecution != null) {
				return true;
			}
			if (compactionService.hasInFlight()) {
				return true;
			}
			return compactionService.submit(contextAggregator.buildCompactionConversation());
		}
		if (!hasRealTrigger && hasOverflowFlush) {
			if (sessionCoordinator.hasInFlight() || pendingToolExecution != null || compactionService.hasInFlight()) {
				return true;
			}
			PlannerContextSnapshot overflowSnapshot = contextAggregator.freezeOverflowFlushSnapshot();
			if (overflowSnapshot == null) {
				return true;
			}
			sessionCoordinator.submit(overflowSnapshot);
			return true;
		}

		if (coalescePending && clock.millis() < coalesceReadyAtMs) {
			return true;
		}
		if (coalescePending) {
			contextAggregator.dropSupersededGeneration(coalesceSupersededSnapshot);
			clearCoalesceState();
		}
		else {
			contextAggregator.dropSupersededGeneration(sessionCoordinator.contextSnapshotFor(sessionCoordinator.activeGeneration()));
		}
		PlannerContextSnapshot snapshot = contextAggregator.freezePlannerSnapshot(pendingSubmitRequest);
		if (snapshot == null) {
			return true;
		}
		sessionCoordinator.submit(snapshot);
		return true;
	}

	private void acceptGeneration(PlannerExecutionResult acceptedResult) {
		PlannerContextSnapshot snapshot = sessionCoordinator.contextSnapshotFor(acceptedResult.generation());
		if (snapshot != null) {
			contextAggregator.commitAcceptedTriggerBatch(snapshot);
		}
		sessionCoordinator.finishGeneration(acceptedResult.generation(), false);
		boolean hasVisibleReply = acceptedResult.response() != null
			&& acceptedResult.response().replyText() != null
			&& !acceptedResult.response().replyText().isBlank();
		pendingAcceptedAssistantRawContent = hasVisibleReply && acceptedResult.response() != null
			? acceptedResult.response().rawAssistantContent()
			: null;
		if (!contextAggregator.hasQueuedTriggers()) {
			pendingSubmitRequest = null;
			awaitingAcceptedReplyRecord = hasVisibleReply;
			clearCoalesceState();
			endTurnSpan();
			if (!awaitingAcceptedReplyRecord && contextAggregator.hasPendingOverflowFlush()) {
				startQueuedWorkIfPossible();
			}
		}
		else if (!hasVisibleReply) {
			awaitingAcceptedReplyRecord = false;
			startQueuedWorkIfPossible();
		}
		else {
			awaitingAcceptedReplyRecord = true;
		}
	}

	private PlannerExecutionResult continueAfterTool() {
		PendingToolExecution toolExecution = pendingToolExecution;
		if (toolExecution == null || !toolExecution.future().isDone()) {
			return null;
		}

		ToolExecutionOutcome toolOutcome;
		try {
			toolOutcome = toolExecution.future().join();
		}
		catch (CompletionException exception) {
			toolOutcome = new TextToolExecutionOutcome("VISION_UNAVAILABLE: vision_failed");
			Airicraft.LOGGER.warn("Planner tool future failed generation={}", toolExecution.generation(), exception);
		}
		finally {
			pendingToolExecution = null;
			captureInFlight = false;
		}

		PlannerContextSnapshot snapshot = sessionCoordinator.contextSnapshotFor(toolExecution.generation());
		if (snapshot == null) {
			return null;
		}

		PlannerRequest followUpRequest = snapshot.request().withToolResult(toolOutcome.toolResultText());
		sessionCoordinator.submitToolFollowUp(
			toolExecution.generation(),
			followUpRequest,
			toolOutcome.appendFollowUp(contextAggregator, snapshot, toolExecution.assistantRawContent())
		);
		lifecycleListener.onToolCompleted(toolExecution.generation(), toolOutcome.toolResultText(), toolOutcome instanceof ImageToolExecutionOutcome);
		appendToolFollowUpCard(toolExecution);
		return null;
	}

	private CompletableFuture<ToolExecutionOutcome> requestPlannerTool(PlannerToolRequest toolRequest) {
		return switch (normalizedToolType(toolRequest)) {
			case VISUAL_TOOL_NAME -> requestVisionTool(toolRequest);
			case INVENTORY_TOOL_NAME -> inventoryTool.inspectInventory(toolRequest.prompt()).thenApply(TextToolExecutionOutcome::new);
			case RECIPES_TOOL_NAME -> inventoryTool.inspectRecipes(toolRequest.prompt()).thenApply(TextToolExecutionOutcome::new);
			default -> CompletableFuture.completedFuture(new TextToolExecutionOutcome("TOOL_UNAVAILABLE: invalid_tool"));
		};
	}

	private CompletableFuture<ToolExecutionOutcome> requestVisionTool(PlannerToolRequest toolRequest) {
		Context parentContext = currentTurnContext();
		try (Scope scope = parentContext.makeCurrent()) {
			if (visionMode == PlannerVisionMode.EXTERNAL_SUMMARY) {
				if (!visionTool.isConfigured()) {
					return CompletableFuture.completedFuture(new TextToolExecutionOutcome("VISION_UNAVAILABLE: vision_provider_unavailable"));
				}

				return requestCapture()
					.handle((capture, throwable) -> {
						if (throwable != null) {
							String code = visionFailureCode(throwable);
							Airicraft.LOGGER.warn("Vision tool capture failed code={}", code, throwable);
							return CompletableFuture.<ToolExecutionOutcome>completedFuture(new TextToolExecutionOutcome("VISION_UNAVAILABLE: " + code));
						}
						return visionTool.requestDescription(capture, toolRequest.prompt())
							.<ToolExecutionOutcome>handle((description, throwable2) -> {
								if (throwable2 == null) {
									return new TextToolExecutionOutcome(description.text());
								}
								String code = visionFailureCode(throwable2);
								Airicraft.LOGGER.warn("Vision tool failed code={}", code, throwable2);
								return new TextToolExecutionOutcome("VISION_UNAVAILABLE: " + code);
							});
					})
					.thenCompose(future -> future);
			}

			return requestCapture().handle((capture, throwable) -> {
				if (throwable == null) {
					return new ImageToolExecutionOutcome(
						NATIVE_TOOL_RESULT_TEXT,
						new LlmImageAttachment(mimeType(capture), capture.imageBytes(), imageDetail)
					);
				}
				String code = visionFailureCode(throwable);
				Airicraft.LOGGER.warn("Vision tool capture failed code={}", code, throwable);
				return new TextToolExecutionOutcome("VISION_UNAVAILABLE: " + code);
			});
		}
	}

	private CompletableFuture<FirstPersonScreenshotService.CapturedScreenshot> requestCapture() {
		captureInFlight = true;
		try {
			return visionTool.requestCapture().whenComplete((capture, throwable) -> captureInFlight = false);
		}
		catch (RuntimeException exception) {
			captureInFlight = false;
			return CompletableFuture.failedFuture(exception);
		}
	}

	private void completeCompaction(CompactionExecutionResult compactionResult) {
		lastCompactionResult = compactionResult;
		lifecycleListener.onCompactionCompleted(compactionResult);
		if (compactionResult.succeeded()) {
			contextAggregator.recordObservedUsage(compactionResult.usage());
			contextAggregator.applyCheckpoint(compactionResult.checkpoint());
			return;
		}
		Airicraft.LOGGER.warn("Planner compaction failed message={}", summarizeForLog(compactionResult.failureMessage()));
		contextAggregator.onCompactionFailure();
	}

	private void cancelPendingTool() {
		if (pendingToolExecution != null) {
			pendingToolExecution.future().cancel(true);
			pendingToolExecution = null;
		}
		captureInFlight = false;
	}

	private void armCoalesceWindow() {
		coalescePending = true;
		coalesceWindowMs = computeCoalesceWindowMs(contextAggregator.queuedTriggerCount());
		coalesceReadyAtMs = clock.millis() + coalesceWindowMs;
	}

	private long computeCoalesceWindowMs(int queuedTriggerCount) {
		if (queuedTriggerCount <= 1) {
			return 0L;
		}
		long windowMs = (long) (queuedTriggerCount - 1) * coalesceStepMs;
		return Math.max(coalesceMinMs, Math.min(coalesceMaxMs, windowMs));
	}

	private void clearCoalesceState() {
		coalescePending = false;
		coalesceReadyAtMs = -1L;
		coalesceWindowMs = 0L;
		coalesceSupersededSnapshot = null;
	}

	private static String mimeType(FirstPersonScreenshotService.CapturedScreenshot capture) {
		return "image/" + capture.format().toLowerCase(Locale.ROOT);
	}

	private void recordSubmittedConversation(
		long generation,
		int attempt,
		PlannerSessionPhase phase,
		PlannerRequest request,
		LlmConversation conversation
	) {
		lifecycleListener.onConversationSubmitted(generation, attempt, phase, request, conversation);
		PlannerConversationDebugSnapshot submitted = PlannerConversationDebugSnapshot.fromConversation(generation, phase, attempt, conversation);
		lastSubmittedConversation = submitted;
		debugRecorder.recordPlannerSubmission(generation, attempt, phase, clock.millis(), conversation);
		if (lastVisibleConversation == null || lastVisibleConversation.isEmpty()) {
			lastVisibleConversation = submitted;
			debugRecorder.recordConversationSources(lastSubmittedConversation, lastVisibleConversation);
			return;
		}
		ArrayList<PlannerConversationDebugMessage> merged = new ArrayList<>(persistentConversationHistory(lastVisibleConversation));
		merged.addAll(submitted.messages());
		lastVisibleConversation = new PlannerConversationDebugSnapshot(
			generation,
			phase == null ? "UNKNOWN" : phase.name(),
			attempt,
			trimConversationMessages(merged)
		);
		debugRecorder.recordConversationSources(lastSubmittedConversation, lastVisibleConversation);
	}

	private boolean isValidToolRequest(PlannerToolRequest toolRequest) {
		return switch (normalizedToolType(toolRequest)) {
			case VISUAL_TOOL_NAME -> visionMode == PlannerVisionMode.NATIVE_TOOL_IMAGE
				|| (toolRequest.prompt() != null && !toolRequest.prompt().isBlank());
			case INVENTORY_TOOL_NAME, RECIPES_TOOL_NAME -> true;
			default -> false;
		};
	}

	private static String normalizedToolType(PlannerToolRequest toolRequest) {
		return toolRequest == null || toolRequest.type() == null ? "" : toolRequest.type().toLowerCase(Locale.ROOT);
	}

	private static boolean hasToolCompatibleIntent(PlannerResponse response) {
		return switch (toolIntentType(response)) {
			case "none", "reply_only", "ask_clarification", "acknowledge_failure", "mission_update", "job_update" -> true;
			default -> false;
		};
	}

	private static String toolIntentType(PlannerResponse response) {
		PlannerIntent intent = response.intent();
		return intent == null || intent.type() == null ? "none" : intent.type();
	}

	private PlannerExecutionResult parseFailure(PlannerExecutionResult baseResult, String message) {
		return new PlannerExecutionResult(
			baseResult.request(),
			null,
			LlmUsageSnapshot.unknown(),
			LlmFailureType.PARSE_ERROR,
			message,
			baseResult.generation(),
			baseResult.attempt(),
			baseResult.phase(),
			false
		);
	}

	private static String buildTurnId(PlannerRequest request) {
		if (request == null) {
			return "session:none";
		}
		String sender = request.senderName() == null || request.senderName().isBlank() ? "unknown" : request.senderName();
		String mode = request.sessionMode() == null ? "unknown_mode" : request.sessionMode().name();
		return "session:" + mode + ":sender=" + sender + ":tick=" + request.tick();
	}

	private Context currentTurnContext() {
		return turnContext == null ? Context.current() : turnContext;
	}

	private void endTurnSpan() {
		if (turnContext != null) {
			observability.endSpan(turnContext);
			turnContext = null;
		}
	}

	private void appendAssistantOutcomeCard(PlannerExecutionResult result) {
		if (result == null || result.response() == null) {
			return;
		}
		String text = assistantOutcomeText(result.response());
		if (text == null || text.isBlank()) {
			return;
		}
		appendConversationCard(new PlannerConversationDebugMessage(
			"assistant",
			PlannerConversationDebugKind.ASSISTANT_TURN,
			text,
			result.generation(),
			result.phase().name(),
			result.attempt(),
			false
		));
	}

	private void appendToolRequestCard(PlannerExecutionResult result) {
		if (result == null || result.response() == null || result.response().toolRequest() == null) {
			return;
		}
		appendOperationCard(
			result.generation(),
			result.phase().name(),
			result.attempt(),
			toolRequestSummary(result.response().toolRequest())
		);
	}

	private void appendToolFollowUpCard(PendingToolExecution toolExecution) {
		if (toolExecution == null || toolExecution.toolSummary() == null || toolExecution.toolSummary().isBlank()) {
			return;
		}
		PlannerSessionSnapshot activeSnapshot = sessionCoordinator.activeSnapshot();
		String phase = activeSnapshot == null || activeSnapshot.phase() == null ? PlannerSessionPhase.TOOL_FOLLOW_UP.name() : activeSnapshot.phase().name();
		int attempt = activeSnapshot == null ? 0 : activeSnapshot.attemptCount();
		appendOperationCard(toolExecution.generation(), phase, attempt, toolExecution.toolSummary());
	}

	private void appendOperationCards(PlannerExecutionResult result) {
		if (result == null || result.response() == null) {
			return;
		}
		PlannerResponse response = result.response();
		String intentSummary = intentOperationSummary(response.intent());
		if (intentSummary != null) {
			appendOperationCard(result.generation(), result.phase().name(), result.attempt(), intentSummary);
		}
		for (String policySummary : eventPolicyOperationSummaries(response.eventPolicyChanges())) {
			appendOperationCard(result.generation(), result.phase().name(), result.attempt(), policySummary);
		}
	}

	private void appendOperationCard(long generation, String phase, int attempt, String text) {
		if (text == null || text.isBlank()) {
			return;
		}
		appendConversationCard(new PlannerConversationDebugMessage(
			"assistant",
			PlannerConversationDebugKind.TASK,
			text,
			generation,
			phase,
			attempt,
			false
		));
	}

	private void appendFailureCard(PlannerExecutionResult result) {
		if (result == null || result.failureType() == null) {
			return;
		}
		String text = result.failureType().name() + ": " + (result.failureMessage() == null || result.failureMessage().isBlank()
			? "Planner execution failed"
			: result.failureMessage());
		appendConversationCard(new PlannerConversationDebugMessage(
			"system",
			PlannerConversationDebugKind.FAILURE,
			text,
			result.generation(),
			result.phase() == null ? "UNKNOWN" : result.phase().name(),
			result.attempt(),
			false
		));
	}

	private void appendConversationCard(PlannerConversationDebugMessage message) {
		if (message == null) {
			return;
		}
		if (lastVisibleConversation == null || lastVisibleConversation.isEmpty()) {
			lastVisibleConversation = new PlannerConversationDebugSnapshot(
				message.generation(),
				message.phase(),
				message.attempt(),
				java.util.List.of(message)
			);
			debugRecorder.recordConversationSources(lastSubmittedConversation, lastVisibleConversation);
			return;
		}
		lastVisibleConversation = new PlannerConversationDebugSnapshot(
			lastVisibleConversation.generation(),
			lastVisibleConversation.phase(),
			lastVisibleConversation.attempt(),
			trimConversationMessages(new ArrayList<>(lastVisibleConversation.withAppended(message).messages()))
		);
		debugRecorder.recordConversationSources(lastSubmittedConversation, lastVisibleConversation);
	}

	private static List<PlannerConversationDebugMessage> persistentConversationHistory(PlannerConversationDebugSnapshot snapshot) {
		if (snapshot == null || snapshot.isEmpty()) {
			return List.of();
		}
		ArrayList<PlannerConversationDebugMessage> history = new ArrayList<>();
		for (PlannerConversationDebugMessage message : snapshot.messages()) {
			if (isPersistentConversationCard(message.kind())) {
				history.add(message);
			}
		}
		return List.copyOf(history);
	}

	private static boolean isPersistentConversationCard(PlannerConversationDebugKind kind) {
		if (kind == null) {
			return false;
		}
		return switch (kind) {
			case ASSISTANT_TURN, TOOL_RESULT, TASK, FAILURE -> true;
			case SYSTEM, CHECKPOINT, NOTICE, USER_TURN -> false;
		};
	}

	private PlannerConversationDebugSnapshot displayConversationDebugSnapshot() {
		if (lastSubmittedConversation == null || lastSubmittedConversation.isEmpty()) {
			return lastVisibleConversation;
		}
		if (lastVisibleConversation == null || lastVisibleConversation.isEmpty()) {
			return lastSubmittedConversation;
		}
		ArrayList<PlannerConversationDebugMessage> messages = new ArrayList<>();
		for (PlannerConversationDebugMessage message : persistentConversationHistory(lastVisibleConversation)) {
			if (!sameConversationWindow(message, lastSubmittedConversation)) {
				messages.add(message);
			}
		}
		messages.addAll(lastSubmittedConversation.messages());
		ArrayList<PlannerConversationDebugMessage> remainingSubmitted = new ArrayList<>(lastSubmittedConversation.messages());
		for (PlannerConversationDebugMessage message : lastVisibleConversation.messages()) {
			if (!sameConversationWindow(message, lastSubmittedConversation)) {
				continue;
			}
			if (!remainingSubmitted.isEmpty() && sameConversationMessage(message, remainingSubmitted.get(0))) {
				remainingSubmitted.remove(0);
				continue;
			}
			messages.add(message);
		}
		return new PlannerConversationDebugSnapshot(
			lastSubmittedConversation.generation(),
			lastSubmittedConversation.phase(),
			lastSubmittedConversation.attempt(),
			trimConversationMessages(messages)
		);
	}

	private static boolean sameConversationWindow(PlannerConversationDebugMessage message, PlannerConversationDebugSnapshot snapshot) {
		return message != null
			&& snapshot != null
			&& message.generation() == snapshot.generation()
			&& Objects.equals(message.phase(), snapshot.phase())
			&& message.attempt() == snapshot.attempt();
	}

	private static boolean sameConversationMessage(PlannerConversationDebugMessage left, PlannerConversationDebugMessage right) {
		return left != null
			&& right != null
			&& Objects.equals(left.role(), right.role())
			&& left.kind() == right.kind()
			&& Objects.equals(left.text(), right.text())
			&& left.generation() == right.generation()
			&& Objects.equals(left.phase(), right.phase())
			&& left.attempt() == right.attempt()
			&& left.hasImageAttachment() == right.hasImageAttachment();
	}

	private static List<PlannerConversationDebugMessage> trimConversationMessages(List<PlannerConversationDebugMessage> messages) {
		if (messages == null || messages.isEmpty()) {
			return List.of();
		}
		ArrayList<PlannerConversationDebugMessage> trimmed = new ArrayList<>(messages);
		while (trimmed.size() > CONVERSATION_HISTORY_CARD_LIMIT) {
			int removableIndex = firstNonPersistentIndex(trimmed);
			trimmed.remove(removableIndex >= 0 ? removableIndex : 0);
		}
		return List.copyOf(trimmed);
	}

	private static int firstNonPersistentIndex(List<PlannerConversationDebugMessage> messages) {
		for (int index = 0; index < messages.size(); index++) {
			if (!isPersistentConversationCard(messages.get(index).kind())) {
				return index;
			}
		}
		return -1;
	}

	private sealed interface ToolExecutionOutcome permits TextToolExecutionOutcome, ImageToolExecutionOutcome {
		String toolResultText();

		LlmConversation appendFollowUp(PlannerContextAggregator contextAggregator, PlannerContextSnapshot snapshot, JsonElement assistantRawContent);
	}

	private record TextToolExecutionOutcome(String toolResultText) implements ToolExecutionOutcome {
		@Override
		public LlmConversation appendFollowUp(
			PlannerContextAggregator contextAggregator,
			PlannerContextSnapshot snapshot,
			JsonElement assistantRawContent
		) {
			return contextAggregator.buildPlannerFollowUpConversation(snapshot, assistantRawContent, toolResultText);
		}
	}

	private record ImageToolExecutionOutcome(String toolResultText, LlmImageAttachment imageAttachment) implements ToolExecutionOutcome {
		@Override
		public LlmConversation appendFollowUp(
			PlannerContextAggregator contextAggregator,
			PlannerContextSnapshot snapshot,
			JsonElement assistantRawContent
		) {
			return contextAggregator.buildPlannerFollowUpConversation(snapshot, assistantRawContent, toolResultText, imageAttachment);
		}
	}

	private record PendingToolExecution(
		long generation,
		String toolSummary,
		CompletableFuture<ToolExecutionOutcome> future,
		JsonElement assistantRawContent
	) {
	}

	private static String visionFailureCode(Throwable throwable) {
		Throwable cause = throwable instanceof CompletionException completionException && completionException.getCause() != null
			? completionException.getCause()
			: throwable;
		if (cause instanceof BridgeUnavailableException bridgeUnavailableException) {
			return bridgeUnavailableException.code();
		}
		if (cause instanceof LlmBackendException backendException) {
			return switch (backendException.failureType()) {
				case PROVIDER_UNAVAILABLE -> "vision_provider_unavailable";
				case TIMEOUT -> "vision_timeout";
				case PROVIDER_ERROR, PARSE_ERROR -> "vision_failed";
			};
		}
		return "vision_failed";
	}

	private static String summarizeForLog(String text) {
		return OpenAiCompatibleChatClient.summarizeForLog(text);
	}

	private static String assistantOutcomeText(PlannerResponse response) {
		if (response == null) {
			return null;
		}
		if (response.replyText() != null && !response.replyText().isBlank()) {
			return response.replyText();
		}
		PlannerIntent intent = response.intent();
		if (intent == null || intent.type() == null || intent.type().isBlank()) {
			return null;
		}
		return switch (intent.type()) {
			case "ask_clarification" -> "Asked for clarification.";
			case "acknowledge_failure" -> "Acknowledged failure.";
			case "set_goal", "clear_goal", "reply_only", "none" -> null;
			default -> "Applied intent: " + intent.type() + ".";
		};
	}

	private static String toolRequestSummary(PlannerToolRequest toolRequest) {
		if (toolRequest == null || toolRequest.type() == null || toolRequest.type().isBlank()) {
			return null;
		}
		return "Tool call: " + toolRequest.type()
			+ (toolRequest.prompt() == null || toolRequest.prompt().isBlank() ? "" : " | " + toolRequest.prompt());
	}

	private static String intentOperationSummary(PlannerIntent intent) {
		if (intent == null || intent.type() == null || intent.type().isBlank()) {
			return null;
		}
		return switch (intent.type()) {
			case "set_goal" -> {
				if (intent.goalType() != null && intent.targetPlayer() != null && !intent.targetPlayer().isBlank()) {
					yield "Goal call: " + intent.goalType().name() + " -> " + intent.targetPlayer() + ".";
				}
				if (intent.goalType() != null) {
					yield "Goal call: " + intent.goalType().name() + ".";
				}
				yield "Goal call: set_goal.";
			}
			case "clear_goal" -> "Goal call: clear current goal.";
			case "job_update" -> intent.activeJob() == null ? "Job call: job_update." : "Job call: " + intent.activeJob().type().name() + ".";
			default -> null;
		};
	}

	private static List<String> eventPolicyOperationSummaries(EventPolicyChanges changes) {
		if (changes == null) {
			return List.of();
		}
		ArrayList<String> summaries = new ArrayList<>();
		if (changes.clearAll()) {
			summaries.add("Event filter: clear all rules.");
		}
		for (String ruleId : changes.removeRuleIds()) {
			if (ruleId != null && !ruleId.isBlank()) {
				summaries.add("Event filter: remove " + ruleId + ".");
			}
		}
		for (EventPolicyRuleUpsert upsert : changes.upserts()) {
			String summary = eventPolicyUpsertSummary(upsert);
			if (summary != null) {
				summaries.add(summary);
			}
		}
		return List.copyOf(summaries);
	}

	private static String eventPolicyUpsertSummary(EventPolicyRuleUpsert upsert) {
		if (upsert == null) {
			return null;
		}
		StringBuilder builder = new StringBuilder("Event filter: upsert");
		if (upsert.ruleId() != null && !upsert.ruleId().isBlank()) {
			builder.append(' ').append(upsert.ruleId());
		}
		if (upsert.effect() != null && !upsert.effect().isBlank()) {
			builder.append(" -> ").append(upsert.effect().trim().toUpperCase(Locale.ROOT));
		}
		String matchSummary = eventPolicyMatchSummary(upsert.match());
		if (matchSummary != null) {
			builder.append(" on ").append(matchSummary);
		}
		return builder.append('.').toString();
	}

	private static String eventPolicyMatchSummary(EventPolicyMatch match) {
		if (match == null || !match.isValid()) {
			return null;
		}
		ArrayList<String> filters = new ArrayList<>();
		appendMatchFilter(filters, "player", match.player());
		appendMatchFilter(filters, "speaker", match.speaker());
		appendMatchFilter(filters, "actor", match.actor());
		appendMatchFilter(filters, "itemId", match.itemId());
		appendMatchFilter(filters, "damageTypeId", match.damageTypeId());
		appendMatchFilter(filters, "attackerName", match.attackerName());
		if (filters.isEmpty()) {
			return match.eventType();
		}
		return match.eventType() + " [" + String.join(", ", filters) + "]";
	}

	private static void appendMatchFilter(List<String> filters, String key, String value) {
		if (value != null && !value.isBlank()) {
			filters.add(key + "=" + value);
		}
	}
}
