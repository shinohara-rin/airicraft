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
import com.google.gson.JsonObject;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class PlannerOrchestrator {
	private static final String VISUAL_TOOL_NAME = "take_a_look";
	private static final String INVENTORY_TOOL_NAME = "inspect_inventory";
	private static final String CRAFTABLES_TOOL_NAME = "check_craftables";
	private static final String NEARBY_ENTITIES_TOOL_NAME = "inspect_nearby_entities";
	private static final String INVENTORY_BOOTSTRAP_TOOL_CALL_ID = "bootstrap_inspect_inventory";
	private static final String INVENTORY_BOOTSTRAP_PROMPT = "startup inventory context";
	private static final String NATIVE_TOOL_RESULT_TEXT = "Tool result for take_a_look: current first-person view attached.";
	private static final int MAX_TOOL_CALLS_PER_TOOL_PLAN = 20;
	private static final int SESSION_MAX_ATTEMPTS = 2;
	private static final long SESSION_RETRY_BACKOFF_MS = 250L;
	private static final int SESSION_COALESCE_STEP_MS = 10;
	private static final int SESSION_COALESCE_MIN_MS = 10;
	private static final int SESSION_COALESCE_MAX_MS = 100;
	private static final int DEFAULT_SESSION_MAX_CONCURRENT_ATTEMPTS = 3;
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
	private final PlannerActionToolExecutor actionToolExecutor;
	private final PlannerToolNarrationSink narrationSink;
	private final PlannerToolRegistry toolRegistry;

	private final Map<Long, List<RecordedToolExchange>> toolExchangesByGeneration = new HashMap<>();

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
	private boolean inventoryBootstrapPending = true;

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
				CurrentInventoryTool.disabled(),
				visionMode,
				imageDetail,
				DEFAULT_SESSION_MAX_CONCURRENT_ATTEMPTS,
			SESSION_COALESCE_STEP_MS,
			SESSION_COALESCE_MIN_MS,
			SESSION_COALESCE_MAX_MS,
			Clock.systemDefaultZone(),
				NoopObservability.INSTANCE,
				PlannerLifecycleListener.NO_OP,
				new AgentDebugRecorder(),
				PlannerActionToolExecutor.DISABLED,
				PlannerToolNarrationSink.NO_OP
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
				CurrentInventoryTool.disabled(),
				visionMode,
				imageDetail,
				plannerSessionMaxConcurrentAttempts,
			SESSION_COALESCE_STEP_MS,
			SESSION_COALESCE_MIN_MS,
			SESSION_COALESCE_MAX_MS,
			Clock.systemDefaultZone(),
				NoopObservability.INSTANCE,
				PlannerLifecycleListener.NO_OP,
				new AgentDebugRecorder(),
				PlannerActionToolExecutor.DISABLED,
				PlannerToolNarrationSink.NO_OP
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
				CurrentInventoryTool.disabled(),
				visionMode,
				imageDetail,
				plannerSessionMaxConcurrentAttempts,
			plannerSessionCoalesceStepMillis,
			plannerSessionCoalesceMinMillis,
			plannerSessionCoalesceMaxMillis,
			Clock.systemDefaultZone(),
				NoopObservability.INSTANCE,
				PlannerLifecycleListener.NO_OP,
				new AgentDebugRecorder(),
				PlannerActionToolExecutor.DISABLED,
				PlannerToolNarrationSink.NO_OP
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
				CurrentInventoryTool.disabled(),
				visionMode,
				imageDetail,
				plannerSessionMaxConcurrentAttempts,
			plannerSessionCoalesceStepMillis,
			plannerSessionCoalesceMinMillis,
			plannerSessionCoalesceMaxMillis,
				Clock.systemDefaultZone(),
				observability,
				PlannerLifecycleListener.NO_OP,
				new AgentDebugRecorder(),
				PlannerActionToolExecutor.DISABLED,
				PlannerToolNarrationSink.NO_OP
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
				CurrentInventoryTool.disabled(),
				visionMode,
				imageDetail,
				plannerSessionMaxConcurrentAttempts,
			plannerSessionCoalesceStepMillis,
			plannerSessionCoalesceMinMillis,
			plannerSessionCoalesceMaxMillis,
				Clock.systemDefaultZone(),
				observability,
				lifecycleListener,
				new AgentDebugRecorder(),
				PlannerActionToolExecutor.DISABLED,
				PlannerToolNarrationSink.NO_OP
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
				CurrentInventoryTool.disabled(),
				visionMode,
				imageDetail,
				plannerSessionMaxConcurrentAttempts,
			plannerSessionCoalesceStepMillis,
			plannerSessionCoalesceMinMillis,
			plannerSessionCoalesceMaxMillis,
			Clock.systemDefaultZone(),
			observability,
			lifecycleListener,
			debugRecorder,
			PlannerActionToolExecutor.DISABLED,
			PlannerToolNarrationSink.NO_OP
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
		AgentDebugRecorder debugRecorder,
		PlannerActionToolExecutor actionToolExecutor,
		PlannerToolNarrationSink narrationSink
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
			debugRecorder,
			actionToolExecutor,
			narrationSink
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
				new AgentDebugRecorder(),
				PlannerActionToolExecutor.DISABLED,
				PlannerToolNarrationSink.NO_OP
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
			clock,
			observability,
			lifecycleListener,
			debugRecorder,
			PlannerActionToolExecutor.DISABLED,
			PlannerToolNarrationSink.NO_OP
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
		AgentDebugRecorder debugRecorder,
		PlannerActionToolExecutor actionToolExecutor,
		PlannerToolNarrationSink narrationSink
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
				debugRecorder,
				actionToolExecutor,
				narrationSink
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
				debugRecorder,
				PlannerActionToolExecutor.DISABLED,
				PlannerToolNarrationSink.NO_OP
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
			AgentDebugRecorder debugRecorder,
			PlannerActionToolExecutor actionToolExecutor,
			PlannerToolNarrationSink narrationSink
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
			clock,
			observability,
			lifecycleListener,
			debugRecorder,
			actionToolExecutor,
			narrationSink,
			PlannerToolRegistry.empty()
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
			Clock clock,
			AgentObservability observability,
			PlannerLifecycleListener lifecycleListener,
			AgentDebugRecorder debugRecorder,
			PlannerActionToolExecutor actionToolExecutor,
			PlannerToolNarrationSink narrationSink,
			PlannerToolRegistry toolRegistry
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
		this.actionToolExecutor = Objects.requireNonNull(actionToolExecutor, "actionToolExecutor");
		this.narrationSink = Objects.requireNonNull(narrationSink, "narrationSink");
		this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
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
		return lastSubmittedConversation;
	}

	public PlannerConversationDebugSnapshot projectedConversationDebugSnapshot() {
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
			return pollCompaction();
		}

		if (pendingToolExecution != null) {
			PlannerExecutionResult toolContinuation = pollToolContinuation();
			if (toolContinuation != null) {
				return toolContinuation;
			}
		}

		PlannerExecutionResult plannerResult = sessionCoordinator.poll();
		if (plannerResult == null) {
			pollCoalescedQueue();
			return null;
		}
		if (!plannerResult.succeeded()) {
			return finishFailedPlannerResult(plannerResult);
		}

		return finishSuccessfulPlannerResult(plannerResult);
	}

	private PlannerExecutionResult pollCompaction() {
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

	private PlannerExecutionResult pollToolContinuation() {
		return continueAfterTool();
	}

	private void pollCoalescedQueue() {
		if (coalescePending) {
			startQueuedWorkIfPossible();
		}
	}

	private PlannerExecutionResult finishFailedPlannerResult(PlannerExecutionResult plannerResult) {
		appendFailureCard(plannerResult);
		debugRecorder.recordPlannerCompletion(plannerResult);
		observability.recordFailure(turnContext, plannerResult.failureType().name(), plannerResult.failureMessage(), null);
		lifecycleListener.onPlannerExecutionFailed(plannerResult);
		dropRecordedToolExchanges(plannerResult.generation());
		sessionCoordinator.finishGeneration(plannerResult.generation(), true);
		endTurnSpan();
		return plannerResult;
	}

	private PlannerExecutionResult finishSuccessfulPlannerResult(PlannerExecutionResult plannerResult) {
		contextAggregator.recordUsage(plannerResult.usage());
		lifecycleListener.onPlannerExecutionSucceeded(plannerResult);
		List<PlannerToolCall> toolCalls = effectiveToolCalls(plannerResult.response());
		if (toolCalls.isEmpty()) {
			acceptPlannerReply(plannerResult);
			return plannerResult;
		}

		PlannerExecutionResult toolRequestFailure = validateToolRequests(plannerResult, toolCalls);
		if (toolRequestFailure != null) {
			return toolRequestFailure;
		}
		startToolExecution(plannerResult, toolCalls);
		return null;
	}

	private void acceptPlannerReply(PlannerExecutionResult plannerResult) {
		appendAssistantOutcomeCard(plannerResult);
		appendOperationCards(plannerResult);
		debugRecorder.recordPlannerCompletion(plannerResult);
		acceptGeneration(plannerResult);
	}

	private PlannerExecutionResult validateToolRequests(PlannerExecutionResult plannerResult, List<PlannerToolCall> toolCalls) {
		if (
			plannerResult.phase() == PlannerSessionPhase.TOOL_FOLLOW_UP
				&& completedToolCallCount(plannerResult.generation()) + toolCalls.size() > MAX_TOOL_CALLS_PER_TOOL_PLAN
		) {
			return rejectToolRequest(plannerResult, "Planner requested too many tools for one goal");
		}
		boolean nativeToolCalls = !plannerResult.response().toolCalls().isEmpty();
		if (!nativeToolCalls && !hasToolCompatibleIntent(plannerResult.response())) {
			String toolIntentType = toolIntentType(plannerResult.response());
			Airicraft.LOGGER.warn(
				"Planner returned invalid legacy tool response intentType={} toolCallName={} replyText={}",
				toolIntentType,
				toolCallSummary(toolCalls),
				summarizeForLog(plannerResult.response().replyText())
			);
			return rejectToolRequest(plannerResult, "Tool requests cannot set goal intents");
		}
		for (PlannerToolCall toolCall : toolCalls) {
			if (!isValidToolCall(toolCall)) {
				Airicraft.LOGGER.warn(
					"Planner returned invalid tool call name={} narration={}",
					toolCall.name(),
					summarizeForLog(toolCall.narration())
				);
				return rejectToolRequest(plannerResult, "Planner requested an invalid tool");
			}
		}
		if (toolCalls.size() > 1 && !isBatchSafeTextToolCalls(toolCalls)) {
			Airicraft.LOGGER.warn("Planner returned unsupported tool batch names={}", toolCallSummary(toolCalls));
			return rejectToolRequest(plannerResult, "Planner requested an unsupported tool batch");
		}

		String toolIntentType = toolIntentType(plannerResult.response());
		if (!nativeToolCalls && !"none".equals(toolIntentType)) {
			Airicraft.LOGGER.info(
				"Planner returned legacy tool request with non-none intent; ignoring intentType={} toolCallName={}",
				toolIntentType,
				toolCallSummary(toolCalls)
			);
		}
		if (plannerResult.response().replyText() != null && !plannerResult.response().replyText().isBlank()) {
			Airicraft.LOGGER.info(
				"Planner returned tool call with stray replyText; ignoring text={} toolCallName={}",
				summarizeForLog(plannerResult.response().replyText()),
				toolCallSummary(toolCalls)
			);
		}
		return null;
	}

	private PlannerExecutionResult rejectToolRequest(PlannerExecutionResult plannerResult, String message) {
		PlannerExecutionResult failure = parseFailure(plannerResult, message);
		appendFailureCard(failure);
		debugRecorder.recordPlannerCompletion(failure);
		dropRecordedToolExchanges(plannerResult.generation());
		sessionCoordinator.finishGeneration(plannerResult.generation(), true);
		return failure;
	}

	private void startToolExecution(PlannerExecutionResult plannerResult, List<PlannerToolCall> toolCalls) {
		for (PlannerToolCall toolCall : toolCalls) {
			narrationSink.onToolNarration(toolCall);
		}
		appendToolRequestCard(plannerResult, toolCalls);
		debugRecorder.recordPlannerCompletion(plannerResult);
		for (PlannerToolCall toolCall : toolCalls) {
			lifecycleListener.onToolRequested(plannerResult.generation(), toolCall);
		}
		sessionCoordinator.markToolWait(plannerResult.generation());
		pendingToolExecution = new PendingToolExecution(
			plannerResult.generation(),
			toolCallSummary(toolCalls),
			requestPlannerTools(toolCalls),
			plannerResult.response().rawAssistantContent(),
			toolCalls
		);
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
		clearRuntimeState("reset");
	}

	public void shutdown() {
		cancelPendingTool();
		sessionCoordinator.shutdown();
		compactionService.shutdown();
		clearRuntimeState("shutdown");
	}

	private void clearRuntimeState(String reason) {
		contextAggregator.clear();
		toolExchangesByGeneration.clear();
		pendingSubmitRequest = null;
		lastCompactionResult = null;
		awaitingAcceptedReplyRecord = false;
		pendingAcceptedAssistantRawContent = null;
		inventoryBootstrapPending = true;
		lastSubmittedConversation = PlannerConversationDebugSnapshot.empty();
		lastVisibleConversation = PlannerConversationDebugSnapshot.empty();
		debugRecorder.recordConversationSources(lastSubmittedConversation, lastVisibleConversation);
		clearCoalesceState();
		endTurnSpan();
		lifecycleListener.onReset(reason);
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
			return startCompactionIfIdle();
		}
		if (!hasRealTrigger && hasOverflowFlush) {
			return startOverflowFlushIfIdle();
		}

		return startTriggeredPlannerTurn();
	}

	private boolean startCompactionIfIdle() {
		if (sessionCoordinator.hasInFlight() || pendingToolExecution != null || compactionService.hasInFlight()) {
			return true;
		}
		return compactionService.submit(contextAggregator.buildCompactionConversation());
	}

	private boolean startOverflowFlushIfIdle() {
		if (sessionCoordinator.hasInFlight() || pendingToolExecution != null || compactionService.hasInFlight()) {
			return true;
		}
		PlannerContextSnapshot overflowSnapshot = contextAggregator.freezeOverflowFlushSnapshot();
		if (overflowSnapshot == null) {
			return true;
		}
		sessionCoordinator.submit(overflowSnapshot, currentTurnContext());
		return true;
	}

	private boolean startTriggeredPlannerTurn() {
		if (coalescePending && clock.millis() < coalesceReadyAtMs) {
			return true;
		}
		dropSupersededGenerationBeforeTriggeredSubmit();
		PlannerContextSnapshot snapshot = contextAggregator.freezePlannerSnapshot(pendingSubmitRequest);
		if (snapshot == null) {
			return true;
		}
		snapshot = withInventoryBootstrapIfAvailable(snapshot);
		sessionCoordinator.submit(snapshot, currentTurnContext());
		return true;
	}

	private void dropSupersededGenerationBeforeTriggeredSubmit() {
		if (coalescePending) {
			contextAggregator.dropSupersededGeneration(coalesceSupersededSnapshot);
			clearCoalesceState();
			return;
		}
		contextAggregator.dropSupersededGeneration(sessionCoordinator.contextSnapshotFor(sessionCoordinator.activeGeneration()));
	}

	private PlannerContextSnapshot withInventoryBootstrapIfAvailable(PlannerContextSnapshot snapshot) {
		if (!inventoryBootstrapPending || snapshot == null || snapshot.mode() != PlannerSnapshotMode.TRIGGERED) {
			return snapshot;
		}
		if (!isWorldLoadedSession(snapshot.request().sessionMode())) {
			return snapshot;
		}
		CompletableFuture<String> future;
		try {
			future = inventoryTool.inspectInventory(INVENTORY_BOOTSTRAP_PROMPT);
		}
		catch (RuntimeException exception) {
			Airicraft.LOGGER.warn("Inventory bootstrap request failed before submission", exception);
			return snapshot;
		}
		if (future == null || !future.isDone()) {
			return snapshot;
		}
		String toolResult;
		try {
			toolResult = future.join();
		}
		catch (RuntimeException exception) {
			Airicraft.LOGGER.warn("Inventory bootstrap request failed", exception);
			return snapshot;
		}
		inventoryBootstrapPending = false;
		return new PlannerContextSnapshot(
			snapshot.request(),
			snapshot.mode(),
			snapshot.triggerBatch(),
			withInventoryBootstrap(snapshot.plannerConversation(), toolResult),
			snapshot.includedSemanticEventSeqNoUpperBound(),
			snapshot.includedSemanticGapVersion(),
			snapshot.renderedAmbientContext(),
			snapshot.renderedTimeContextAtMs()
		);
	}

	private static boolean isWorldLoadedSession(SessionMode mode) {
		return mode == SessionMode.SINGLEPLAYER_LOCAL
			|| mode == SessionMode.SINGLEPLAYER_LAN_HOST
			|| mode == SessionMode.REMOTE_MULTIPLAYER;
	}

	private static LlmConversation withInventoryBootstrap(LlmConversation conversation, String toolResult) {
		ArrayList<LlmChatMessage> messages = new ArrayList<>();
		List<LlmChatMessage> existingMessages = conversation == null ? List.of() : conversation.messages();
		if (!existingMessages.isEmpty()) {
			messages.add(existingMessages.get(0));
		}
		messages.add(LlmChatMessage.assistantToolCall("", inventoryBootstrapToolCall()));
		messages.add(LlmChatMessage.tool(INVENTORY_BOOTSTRAP_TOOL_CALL_ID, inventoryBootstrapToolResultContent(toolResult)));
		if (existingMessages.size() > 1) {
			messages.addAll(existingMessages.subList(1, existingMessages.size()));
		}
		return LlmConversation.of(messages);
	}

	private static PlannerToolCall inventoryBootstrapToolCall() {
		JsonObject arguments = new JsonObject();
		arguments.addProperty("prompt", INVENTORY_BOOTSTRAP_PROMPT);
		return new PlannerToolCall(
			INVENTORY_BOOTSTRAP_TOOL_CALL_ID,
			INVENTORY_TOOL_NAME,
			arguments,
			null,
			null
		);
	}

	private static String inventoryBootstrapToolResultContent(String toolResult) {
		if (toolResult == null || toolResult.isBlank()) {
			return "Tool result for inspect_inventory: none";
		}
		return toolResult;
	}

	private void acceptGeneration(PlannerExecutionResult acceptedResult) {
		PlannerContextSnapshot snapshot = sessionCoordinator.contextSnapshotFor(acceptedResult.generation());
		if (snapshot != null) {
			contextAggregator.commitAcceptedTriggerBatch(snapshot);
		}
		commitRecordedToolExchanges(acceptedResult.generation());
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

		List<ToolCallExecutionResult> toolResults;
		try {
			toolResults = toolExecution.future().join();
		}
		catch (CompletionException exception) {
			toolResults = List.of(new ToolCallExecutionResult(
				toolExecution.toolCalls().isEmpty() ? null : toolExecution.toolCalls().getFirst(),
				fallbackToolFailureText(toolExecution.toolCalls()),
				null
			));
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

		String combinedToolResultText = combinedToolResultText(toolResults);
		PlannerRequest followUpRequest = snapshot.request().withToolResult(combinedToolResultText);
		PlannerContextSnapshot followUpSnapshot = withRecordedToolExchanges(
			snapshot,
			recordedToolExchanges(toolExecution.generation())
		);
		recordToolExchange(
			toolExecution.generation(),
			snapshot,
			toolExecution.assistantRawContent(),
			toolExecution.toolCalls(),
			toolResults
		);
		sessionCoordinator.submitToolFollowUp(
			toolExecution.generation(),
			followUpRequest,
			appendToolFollowUpConversation(followUpSnapshot, toolExecution.toolCalls(), toolResults)
		);
		for (ToolCallExecutionResult result : toolResults) {
			lifecycleListener.onToolCompleted(toolExecution.generation(), result.toolResultText(), result.imageAttachment() != null);
		}
		appendToolFollowUpCard(toolExecution);
		return null;
	}

	private LlmConversation appendToolFollowUpConversation(
		PlannerContextSnapshot snapshot,
		List<PlannerToolCall> toolCalls,
		List<ToolCallExecutionResult> toolResults
	) {
		if (toolCalls.size() == 1 && !toolResults.isEmpty() && toolResults.getFirst().imageAttachment() != null) {
			return contextAggregator.buildPlannerFollowUpConversation(
				snapshot,
				toolCalls.getFirst(),
				toolResults.getFirst().toolResultText(),
				toolResults.getFirst().imageAttachment()
			);
		}
		return contextAggregator.buildPlannerFollowUpConversation(snapshot, toolCalls, toolResultTexts(toolResults));
	}

	private CompletableFuture<ToolExecutionOutcome> requestPlannerTool(PlannerToolCall toolCall) {
		return switch (normalizedToolName(toolCall)) {
			case VISUAL_TOOL_NAME -> requestVisionTool(toolCall);
			case INVENTORY_TOOL_NAME -> inventoryTool.inspectInventory(toolPrompt(toolCall)).thenApply(TextToolExecutionOutcome::new);
			case CRAFTABLES_TOOL_NAME -> inventoryTool.checkCraftables(toolPrompt(toolCall)).thenApply(TextToolExecutionOutcome::new);
			case NEARBY_ENTITIES_TOOL_NAME -> inventoryTool.inspectNearbyEntities(toolPrompt(toolCall)).thenApply(TextToolExecutionOutcome::new);
			default -> {
				CompletableFuture<ToolExecutionOutcome> providerToolFuture = toolRegistry.providerFor(toolCall.name())
					.map(provider -> provider.executeResult(toolCall).thenCompose(result -> providerToolOutcome(toolCall, result)))
					.orElse(null);
				yield providerToolFuture == null
					? actionToolExecutor.execute(toolCall).<ToolExecutionOutcome>thenApply(TextToolExecutionOutcome::new)
					: providerToolFuture;
			}
			};
	}

	private CompletableFuture<List<ToolCallExecutionResult>> requestPlannerTools(List<PlannerToolCall> toolCalls) {
		CompletableFuture<List<ToolCallExecutionResult>> chain = CompletableFuture.completedFuture(List.of());
		boolean batch = toolCalls.size() > 1;
		for (PlannerToolCall toolCall : toolCalls) {
			chain = chain.thenCompose(previousResults ->
				requestPlannerTool(toolCall).thenApply(outcome -> appendToolResult(previousResults, toolCall, outcome, batch)));
		}
		return chain;
	}

	private static List<ToolCallExecutionResult> appendToolResult(
		List<ToolCallExecutionResult> previousResults,
		PlannerToolCall toolCall,
		ToolExecutionOutcome outcome,
		boolean batch
	) {
		ArrayList<ToolCallExecutionResult> updated = new ArrayList<>(previousResults);
		if (batch && outcome.imageAttachment() != null) {
			updated.add(new ToolCallExecutionResult(
				toolCall,
				"TOOL_UNAVAILABLE: image_tool_batch_unsupported",
				null
			));
			return List.copyOf(updated);
		}
		updated.add(new ToolCallExecutionResult(toolCall, outcome.toolResultText(), outcome.imageAttachment()));
		return List.copyOf(updated);
	}

	private CompletableFuture<ToolExecutionOutcome> providerToolOutcome(PlannerToolCall toolCall, PlannerProviderToolResult result) {
		if (result.imageAttachment() == null) {
			return CompletableFuture.completedFuture(new TextToolExecutionOutcome(result.text()));
		}
		if (visionMode == PlannerVisionMode.NATIVE_TOOL_IMAGE) {
			return CompletableFuture.completedFuture(new ImageToolExecutionOutcome(result.text(), result.imageAttachment()));
		}
		if (!visionTool.isConfigured()) {
			return CompletableFuture.completedFuture(new TextToolExecutionOutcome(
				result.text() + "\nVISION_UNAVAILABLE: vision_provider_unavailable"
			));
		}

		return visionTool.requestDescription(result.imageAttachment(), providerImagePrompt(toolCall, result.text()))
			.<ToolExecutionOutcome>handle((description, throwable) -> {
				if (throwable == null) {
					return new TextToolExecutionOutcome(result.text() + "\nVision summary: " + description.text());
				}
				String code = visionFailureCode(throwable);
				Airicraft.LOGGER.warn("Provider image tool vision summary failed tool={} code={}", toolCall.name(), code, throwable);
				return new TextToolExecutionOutcome(result.text() + "\nVISION_UNAVAILABLE: " + code);
			});
	}

	private static String providerImagePrompt(PlannerToolCall toolCall, String toolResultText) {
		String normalizedName = normalizedToolName(toolCall);
		if ("take_map_look".equals(normalizedName)) {
			return "Describe this Minecraft map image for a planner that cannot see images. "
				+ "Mention the player marker or center point if visible, whether the minimap or worldmap appears off-center, "
				+ "nearby terrain, water, structures, waypoints, and useful directions. "
				+ "Tool metadata: " + (toolResultText == null || toolResultText.isBlank() ? "none" : toolResultText);
		}
		return "Describe this tool image for a planner that cannot see images. "
			+ "Call out visible state, labels, markers, spatial relationships, and anything actionable. "
			+ "Tool metadata: " + (toolResultText == null || toolResultText.isBlank() ? "none" : toolResultText);
	}

	private CompletableFuture<ToolExecutionOutcome> requestVisionTool(PlannerToolCall toolCall) {
		Context parentContext = currentTurnContext();
		try (Scope scope = parentContext.makeCurrent()) {
			if (visionMode == PlannerVisionMode.EXTERNAL_SUMMARY) {
				if (!visionTool.isConfigured()) {
					return CompletableFuture.completedFuture(new TextToolExecutionOutcome("VISION_UNAVAILABLE: vision_provider_unavailable"));
				}

				return requestCapture(toolCall)
					.handle((captureResult, throwable) -> {
						if (throwable != null) {
							String code = visionFailureCode(throwable);
							Airicraft.LOGGER.warn("Vision tool capture failed code={}", code, throwable);
							return CompletableFuture.<ToolExecutionOutcome>completedFuture(new TextToolExecutionOutcome("VISION_UNAVAILABLE: " + code));
						}
							return visionTool.requestDescription(captureResult.screenshot(), toolPrompt(toolCall))
								.<ToolExecutionOutcome>handle((description, throwable2) -> {
								if (throwable2 == null) {
									return new TextToolExecutionOutcome(appendCaptureMetadata(description.text(), captureResult.metadataLines()));
								}
								String code = visionFailureCode(throwable2);
								Airicraft.LOGGER.warn("Vision tool failed code={}", code, throwable2);
								return new TextToolExecutionOutcome(appendCaptureMetadata("VISION_UNAVAILABLE: " + code, captureResult.metadataLines()));
							});
					})
					.thenCompose(future -> future);
			}

			return requestCapture(toolCall).handle((captureResult, throwable) -> {
				if (throwable == null) {
					return new ImageToolExecutionOutcome(
						nativeToolResultText(captureResult.metadataLines()),
						new LlmImageAttachment(mimeType(captureResult.screenshot()), captureResult.screenshot().imageBytes(), imageDetail)
					);
				}
				String code = visionFailureCode(throwable);
				Airicraft.LOGGER.warn("Vision tool capture failed code={}", code, throwable);
				return new TextToolExecutionOutcome("VISION_UNAVAILABLE: " + code);
			});
		}
	}

	private CompletableFuture<ViewCaptureResult> requestCapture(PlannerToolCall toolCall) {
		captureInFlight = true;
		try {
			return visionTool.requestCapture(viewCaptureRequest(toolCall)).whenComplete((capture, throwable) -> captureInFlight = false);
		}
		catch (RuntimeException exception) {
			captureInFlight = false;
			return CompletableFuture.failedFuture(exception);
		}
	}

	private static ViewCaptureRequest viewCaptureRequest(PlannerToolCall toolCall) {
		JsonObject arguments = toolCall == null ? null : toolCall.arguments();
		if (arguments == null) {
			return ViewCaptureRequest.current();
		}
		String direction = stringArgument(arguments, "direction");
		if (direction != null) {
			return ViewCaptureRequest.direction(direction);
		}
		String targetPlayer = stringArgument(arguments, "targetPlayer");
		if (targetPlayer != null) {
			return ViewCaptureRequest.player(targetPlayer);
		}
		if (arguments.has("x") && arguments.has("y") && arguments.has("z")) {
			return ViewCaptureRequest.block(
				arguments.get("x").getAsInt(),
				arguments.get("y").getAsInt(),
				arguments.get("z").getAsInt()
			);
		}
		return ViewCaptureRequest.current();
	}

	private static String nativeToolResultText(List<String> metadataLines) {
		return appendCaptureMetadata(NATIVE_TOOL_RESULT_TEXT, metadataLines);
	}

	private static String appendCaptureMetadata(String text, List<String> metadataLines) {
		if (metadataLines == null || metadataLines.isEmpty()) {
			return text;
		}
		return text + "\n" + String.join("\n", metadataLines);
	}

	private static String stringArgument(JsonObject object, String key) {
		if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
			return null;
		}
		try {
			String value = object.get(key).getAsString();
			return value == null || value.isBlank() ? null : value.trim();
		}
		catch (RuntimeException exception) {
			return null;
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

	private void recordToolExchange(
		long generation,
		PlannerContextSnapshot snapshot,
		JsonElement assistantRawContent,
		List<PlannerToolCall> toolCalls,
		List<ToolCallExecutionResult> toolResults
	) {
		if ((assistantRawContent == null && (toolCalls == null || toolCalls.isEmpty())) || snapshot == null) {
			return;
		}
		toolExchangesByGeneration
			.computeIfAbsent(generation, key -> new ArrayList<>())
			.add(new RecordedToolExchange(
				assistantRawContent,
				toolCalls,
				toolResultTexts(toolResults),
				snapshot.request().tick(),
				snapshot.request().timestampMs()
			));
	}

	private void commitRecordedToolExchanges(long generation) {
		List<RecordedToolExchange> exchanges = toolExchangesByGeneration.remove(generation);
		if (exchanges == null) {
			return;
		}
		for (RecordedToolExchange exchange : exchanges) {
			if (!exchange.toolCalls().isEmpty()) {
				contextAggregator.recordAcceptedToolExchange(
					exchange.toolCalls(),
					exchange.toolResultTexts(),
					exchange.tick(),
					exchange.timestampMs()
				);
			}
			else {
				contextAggregator.recordAcceptedToolExchange(
					exchange.assistantRawContent(),
					combinedToolResultText(exchange.toolResultTexts()),
					exchange.tick(),
					exchange.timestampMs()
				);
			}
		}
	}

	private List<RecordedToolExchange> recordedToolExchanges(long generation) {
		return List.copyOf(toolExchangesByGeneration.getOrDefault(generation, List.of()));
	}

	private PlannerContextSnapshot withRecordedToolExchanges(
		PlannerContextSnapshot snapshot,
		List<RecordedToolExchange> exchanges
	) {
		if (snapshot == null || exchanges == null || exchanges.isEmpty()) {
			return snapshot;
		}
		return new PlannerContextSnapshot(
			snapshot.request(),
			snapshot.mode(),
			snapshot.triggerBatch(),
			appendRecordedToolExchanges(snapshot.plannerConversation(), exchanges),
			snapshot.includedSemanticEventSeqNoUpperBound(),
			snapshot.includedSemanticGapVersion(),
			snapshot.renderedAmbientContext(),
			snapshot.renderedTimeContextAtMs()
		);
	}

	private LlmConversation appendRecordedToolExchanges(
		LlmConversation conversation,
		List<RecordedToolExchange> exchanges
	) {
		LlmConversation updated = conversation;
		for (RecordedToolExchange exchange : exchanges) {
			if (!exchange.toolCalls().isEmpty()) {
				updated = updated.withAppended(LlmChatMessage.assistantToolCalls("", exchange.toolCalls()));
				for (int index = 0; index < exchange.toolCalls().size(); index++) {
					PlannerToolCall toolCall = exchange.toolCalls().get(index);
					String toolResultText = index >= exchange.toolResultTexts().size() ? null : exchange.toolResultTexts().get(index);
					updated = updated.withAppended(LlmChatMessage.tool(toolCall.id(), recordedToolResultContent(toolResultText)));
				}
			}
			else if (exchange.assistantRawContent() != null) {
				updated = updated
					.withAppended(LlmChatMessage.assistant(
						OpenAiCompatibleMessageContent.extractVisibleText(exchange.assistantRawContent()),
						exchange.assistantRawContent()
					))
					.withAppended(LlmChatMessage.user(
						"Tool result: " + recordedToolResultContent(combinedToolResultText(exchange.toolResultTexts())),
						LlmMessageKind.TOOL_RESULT
					));
			}
		}
		return updated;
	}

	private static String recordedToolResultContent(String toolResultText) {
		if (toolResultText == null || toolResultText.isBlank()) {
			return "Tool result: none";
		}
		return toolResultText;
	}

	private static List<String> toolResultTexts(List<ToolCallExecutionResult> toolResults) {
		if (toolResults == null || toolResults.isEmpty()) {
			return List.of();
		}
		return toolResults.stream()
			.map(ToolCallExecutionResult::toolResultText)
			.toList();
	}

	private static String combinedToolResultText(List<?> toolResults) {
		if (toolResults == null || toolResults.isEmpty()) {
			return "Tool result: none";
		}
		ArrayList<String> texts = new ArrayList<>();
		for (Object toolResult : toolResults) {
			if (toolResult instanceof ToolCallExecutionResult executionResult) {
				texts.add(recordedToolResultContent(executionResult.toolResultText()));
			}
			else if (toolResult instanceof String text) {
				texts.add(recordedToolResultContent(text));
			}
		}
		return texts.isEmpty() ? "Tool result: none" : String.join("\n", texts);
	}

	private static String fallbackToolFailureText(List<PlannerToolCall> toolCalls) {
		if (toolCalls != null && toolCalls.size() == 1 && VISUAL_TOOL_NAME.equals(normalizedToolName(toolCalls.getFirst()))) {
			return "VISION_UNAVAILABLE: vision_failed";
		}
		return "TOOL_UNAVAILABLE: tool_failed";
	}

	private int completedToolCallCount(long generation) {
		return toolExchangesByGeneration.getOrDefault(generation, List.of()).stream()
			.mapToInt(exchange -> exchange.toolCalls().isEmpty() ? 1 : exchange.toolCalls().size())
			.sum();
	}

	private void dropRecordedToolExchanges(long generation) {
		toolExchangesByGeneration.remove(generation);
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

	private List<PlannerToolCall> effectiveToolCalls(PlannerResponse response) {
		if (response == null) {
			return List.of();
		}
		if (!response.toolCalls().isEmpty()) {
			return response.toolCalls();
		}
		PlannerToolCall legacyToolCall = legacyToolCall(response.toolRequest());
		return legacyToolCall == null ? List.of() : List.of(legacyToolCall);
	}

	private boolean isValidToolCall(PlannerToolCall toolCall) {
		String name = normalizedToolName(toolCall);
		if (!toolRegistry.isKnownTool(name)) {
			return false;
		}
		return switch (name) {
			case VISUAL_TOOL_NAME -> visionMode == PlannerVisionMode.NATIVE_TOOL_IMAGE || !toolPrompt(toolCall).isBlank();
			default -> true;
			};
	}

	private boolean isBatchSafeTextToolCalls(List<PlannerToolCall> toolCalls) {
		return toolCalls.stream().allMatch(toolCall -> {
			String name = normalizedToolName(toolCall);
			return !VISUAL_TOOL_NAME.equals(name) && !"take_map_look".equals(name) && toolRegistry.isReadTool(name);
		});
	}

	private static String normalizedToolType(PlannerToolRequest toolRequest) {
		return toolRequest == null || toolRequest.type() == null ? "" : toolRequest.type().toLowerCase(Locale.ROOT);
	}

	private static String normalizedToolName(PlannerToolCall toolCall) {
		return toolCall == null ? "" : PlannerToolCatalog.normalizeName(toolCall.name());
	}

	private static PlannerToolCall legacyToolCall(PlannerToolRequest toolRequest) {
		String name = normalizedToolType(toolRequest);
		if (name.isBlank()) {
			return null;
		}
		JsonObject arguments = new JsonObject();
		if (toolRequest.prompt() != null && !toolRequest.prompt().isBlank()) {
			arguments.addProperty("prompt", toolRequest.prompt());
		}
		return new PlannerToolCall("legacy_" + name, name, arguments, null, null);
	}

	private static String toolPrompt(PlannerToolCall toolCall) {
		if (toolCall == null || toolCall.arguments() == null || !toolCall.arguments().has("prompt")) {
			return "";
		}
		try {
			return toolCall.arguments().get("prompt").getAsString();
		}
		catch (RuntimeException exception) {
			return "";
		}
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

	private void appendToolRequestCard(PlannerExecutionResult result, List<PlannerToolCall> toolCalls) {
		if (result == null || toolCalls == null || toolCalls.isEmpty()) {
			return;
		}
		appendOperationCard(
			result.generation(),
			result.phase().name(),
			result.attempt(),
			toolCallSummary(toolCalls)
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

		default LlmImageAttachment imageAttachment() {
			return null;
		}
	}

	private record TextToolExecutionOutcome(String toolResultText) implements ToolExecutionOutcome {
	}

	private record ImageToolExecutionOutcome(String toolResultText, LlmImageAttachment imageAttachment) implements ToolExecutionOutcome {
	}

	private record PendingToolExecution(
		long generation,
		String toolSummary,
		CompletableFuture<List<ToolCallExecutionResult>> future,
		JsonElement assistantRawContent,
		List<PlannerToolCall> toolCalls
	) {
		PendingToolExecution {
			toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
		}
	}

	private record ToolCallExecutionResult(
		PlannerToolCall toolCall,
		String toolResultText,
		LlmImageAttachment imageAttachment
	) {
		ToolCallExecutionResult {
			toolResultText = toolResultText == null || toolResultText.isBlank() ? "Tool result: none" : toolResultText;
		}
	}

	private record RecordedToolExchange(
		JsonElement assistantRawContent,
		List<PlannerToolCall> toolCalls,
		List<String> toolResultTexts,
		long tick,
		long timestampMs
	) {
		RecordedToolExchange {
			toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
			toolResultTexts = toolResultTexts == null ? List.of() : List.copyOf(toolResultTexts);
		}
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

	private static String toolCallSummary(PlannerToolCall toolCall) {
		if (toolCall == null || toolCall.name() == null || toolCall.name().isBlank()) {
			return null;
		}
		StringBuilder summary = new StringBuilder("Tool call: ").append(toolCall.name());
		if (toolCall.narration() != null && !toolCall.narration().isBlank()) {
			summary.append(" | narration: ").append(toolCall.narration());
		}
		String prompt = toolPrompt(toolCall);
		if (!prompt.isBlank()) {
			summary.append(" | ").append(prompt);
		}
		return summary.toString();
	}

	private static String toolCallSummary(List<PlannerToolCall> toolCalls) {
		if (toolCalls == null || toolCalls.isEmpty()) {
			return null;
		}
		if (toolCalls.size() == 1) {
			return toolCallSummary(toolCalls.getFirst());
		}
		ArrayList<String> summaries = new ArrayList<>();
		for (PlannerToolCall toolCall : toolCalls) {
			String summary = toolCallSummary(toolCall);
			if (summary != null && !summary.isBlank()) {
				summaries.add(summary);
			}
		}
		return summaries.isEmpty() ? null : "Tool calls: " + String.join("; ", summaries);
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
