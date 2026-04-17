package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.dialogue.DialogueTurn;
import ai.moeru.airicraft.agent.events.SemanticEvent;
import ai.moeru.airicraft.agent.events.SemanticEventQueryResult;
import ai.moeru.airicraft.agent.semantic.SemanticContextProjectionResult;
import ai.moeru.airicraft.agent.semantic.SemanticContextProjector;
import ai.moeru.airicraft.agent.semantic.SemanticContextUpdate;
import com.google.gson.JsonElement;

import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class PlannerContextAggregator {
	private static final int DEFAULT_PENDING_SEMANTIC_EVENT_CAP = 128;
	private static final String OVERFLOW_FLUSH_INSTRUCTION = "Pending semantic context reached capacity. Review the context updates above and respond once if any reply or action is needed.";

	private final Clock clock;
	private final ZoneId zoneId;
	private final int compactionTriggerTokens;
	private final int pendingSemanticEventCap;
	private final PlannerVisionMode visionMode;
	private final SemanticContextProjector semanticContextProjector = new SemanticContextProjector();

	private PlannerContextState state = PlannerContextState.initial();
	private PlannerContextSnapshot lastFrozenSnapshot;
	private PlannerRequestSeed latestRequestSeed;
	private boolean overflowFlushPending;

	public PlannerContextAggregator(Clock clock, int compactionTriggerTokens, PlannerVisionMode visionMode) {
		this(clock, compactionTriggerTokens, DEFAULT_PENDING_SEMANTIC_EVENT_CAP, visionMode);
	}

	public PlannerContextAggregator(Clock clock, int compactionTriggerTokens, int pendingSemanticEventCap, PlannerVisionMode visionMode) {
		this.clock = Objects.requireNonNull(clock, "clock");
		this.zoneId = clock.getZone();
		this.compactionTriggerTokens = compactionTriggerTokens;
		this.pendingSemanticEventCap = Math.max(1, pendingSemanticEventCap);
		this.visionMode = Objects.requireNonNull(visionMode, "visionMode");
	}

	public boolean compactionPending() {
		return state.compactionPending();
	}

	public boolean hasQueuedTriggers() {
		return !state.queuedTriggers().isEmpty();
	}

	public int queuedTriggerCount() {
		return state.queuedTriggers().size();
	}

	public boolean hasPendingOverflowFlush() {
		return overflowFlushPending;
	}

	public LlmUsageSnapshot lastObservedUsage() {
		return state.lastObservedUsage();
	}

	public long lastObservedEventSeqNo() {
		return state.lastObservedEventSeqNo();
	}

	public PlannerContextDebugSnapshot debugSnapshot() {
		SemanticContextProjectionResult projection = pendingSemanticProjection(clock.millis());
		return new PlannerContextDebugSnapshot(
			compactionTriggerTokens,
			state.compactionPending(),
			state.acceptedHistoryTape().size(),
			state.pendingSemanticEvents().size(),
			projection.updates().size(),
			lastFrozenSnapshot == null ? 0 : lastFrozenSnapshot.plannerConversation().messages().size(),
			state.queuedTriggers().size(),
			state.lastObservedEventSeqNo(),
			state.lastAcceptedTimeContextAtMs(),
			state.pendingSemanticGapVersion() != 0L,
			overflowFlushPending,
			state.lastObservedUsage(),
			state.lastAcceptedAmbientContext(),
			state.activeCheckpoint()
		);
	}

	public void recordObservedEvents(SemanticEventQueryResult queryResult, PlannerRequestSeed requestSeed) {
		recordPlannerRequestSeed(requestSeed);
		state = PlannerContextReducer.recordObservedEvents(state, queryResult);
		recomputeOverflowFlushPending();
	}

	public void recordObservedEvents(SemanticEventQueryResult queryResult) {
		recordObservedEvents(queryResult, null);
	}

	public void recordPlannerRequestSeed(PlannerRequestSeed requestSeed) {
		if (requestSeed != null) {
			latestRequestSeed = requestSeed;
		}
	}

	public void cancelPendingOverflowFlush() {
		overflowFlushPending = false;
	}

	public void enqueueTrigger(PlannerTrigger trigger) {
		Objects.requireNonNull(trigger, "trigger");
		state = PlannerContextReducer.enqueueTrigger(state, trigger.withSeqNo(state.nextTriggerSeqNo()));
	}

	public PlannerContextSnapshot freezePlannerSnapshot(PlannerRequest request) {
		Objects.requireNonNull(request, "request");
		recordPlannerRequestSeed(PlannerRequestSeed.fromRequest(request));
		if (state.queuedTriggers().isEmpty()) {
			return null;
		}

		long nowMs = request.timestampMs();
		PlannerAmbientContext ambientContext = PlannerAmbientContext.fromRequest(request);
		long renderedTimeContextAtMs = PlannerContextPolicy.shouldInjectTimeBeacon(state.lastAcceptedTimeContextAtMs(), nowMs)
			? nowMs
			: -1L;
		List<LlmChatMessage> snapshotNotices = renderSnapshotNotices(request, ambientContext, renderedTimeContextAtMs);

		PlannerTriggerBatch triggerBatch = PlannerTriggerBatch.of(state.queuedTriggers());
		PlannerRequest combinedRequest = request.withTriggerBatch(triggerBatch);
		PlannerContextSnapshot snapshot = new PlannerContextSnapshot(
			combinedRequest,
			PlannerSnapshotMode.TRIGGERED,
			triggerBatch,
			composeConversation(nowMs, snapshotNotices, triggerBatch.toTerminalMessage()),
			state.pendingSemanticEvents().isEmpty() ? 0L : state.pendingSemanticEvents().getLast().seqNo(),
			state.pendingSemanticGapVersion(),
			ambientContext,
			renderedTimeContextAtMs
		);
		lastFrozenSnapshot = snapshot;
		return snapshot;
	}

	public PlannerContextSnapshot freezeOverflowFlushSnapshot() {
		if (!overflowFlushPending || latestRequestSeed == null || !state.queuedTriggers().isEmpty() || state.pendingSemanticEvents().isEmpty()) {
			return null;
		}

		PlannerRequest request = latestRequestSeed.toPlannerRequest();
		long nowMs = request.timestampMs();
		PlannerAmbientContext ambientContext = PlannerAmbientContext.fromRequest(request);
		long renderedTimeContextAtMs = PlannerContextPolicy.shouldInjectTimeBeacon(state.lastAcceptedTimeContextAtMs(), nowMs)
			? nowMs
			: -1L;
		List<LlmChatMessage> snapshotNotices = renderSnapshotNotices(request, ambientContext, renderedTimeContextAtMs);
		PlannerContextSnapshot snapshot = new PlannerContextSnapshot(
			request,
			PlannerSnapshotMode.OVERFLOW_FLUSH,
			PlannerTriggerBatch.of(List.of()),
			composeConversation(nowMs, snapshotNotices, LlmChatMessage.user(OVERFLOW_FLUSH_INSTRUCTION, LlmMessageKind.TASK)),
			state.pendingSemanticEvents().getLast().seqNo(),
			state.pendingSemanticGapVersion(),
			ambientContext,
			renderedTimeContextAtMs
		);
		lastFrozenSnapshot = snapshot;
		overflowFlushPending = false;
		return snapshot;
	}

	public void commitAcceptedTriggerBatch(PlannerContextSnapshot snapshot) {
		if (snapshot == null) {
			return;
		}
		state = PlannerContextReducer.commitAcceptedSnapshot(state, snapshot);
		lastFrozenSnapshot = null;
		recomputeOverflowFlushPending();
	}

	public void dropSupersededGeneration(PlannerContextSnapshot snapshot) {
		if (snapshot == null) {
			return;
		}
		if (lastFrozenSnapshot != null && lastFrozenSnapshot.equals(snapshot)) {
			lastFrozenSnapshot = null;
		}
	}

	public LlmConversation buildPlannerConversation(PlannerRequest request) {
		Objects.requireNonNull(request, "request");
		recordPlannerRequestSeed(PlannerRequestSeed.fromRequest(request));
		if (request.triggerBatch() != null) {
			for (PlannerTrigger trigger : request.triggerBatch().triggers()) {
				enqueueTrigger(trigger);
			}
		}
		PlannerContextSnapshot snapshot = freezePlannerSnapshot(request);
		return snapshot == null ? composeConversation(request.timestampMs(), List.of(), null) : snapshot.plannerConversation();
	}

	public LlmConversation buildPlannerFollowUpConversation(PlannerContextSnapshot snapshot, JsonElement priorAssistantRawContent, String toolResult) {
		if (snapshot == null) {
			throw new IllegalStateException("No planner context snapshot");
		}
		LlmConversation conversation = snapshot.plannerConversation();
		if (priorAssistantRawContent != null) {
			conversation = conversation.withAppended(LlmChatMessage.assistant(
				OpenAiCompatibleMessageContent.extractVisibleText(priorAssistantRawContent),
				priorAssistantRawContent
			));
		}
		return conversation.withAppended(LlmChatMessage.user(
			"Tool result: " + (toolResult == null || toolResult.isBlank() ? "none" : toolResult),
			LlmMessageKind.TOOL_RESULT
		));
	}

	public LlmConversation buildPlannerFollowUpConversation(
		PlannerContextSnapshot snapshot,
		JsonElement priorAssistantRawContent,
		String toolResult,
		LlmImageAttachment imageAttachment
	) {
		if (snapshot == null) {
			throw new IllegalStateException("No planner context snapshot");
		}
		LlmConversation conversation = snapshot.plannerConversation();
		if (priorAssistantRawContent != null) {
			conversation = conversation.withAppended(LlmChatMessage.assistant(
				OpenAiCompatibleMessageContent.extractVisibleText(priorAssistantRawContent),
				priorAssistantRawContent
			));
		}
		return conversation.withAppended(
			LlmChatMessage.userWithImage(
				toolResult == null || toolResult.isBlank() ? "Tool result: image attached." : toolResult,
				LlmMessageKind.TOOL_RESULT,
				imageAttachment
			)
		);
	}

	public LlmConversation buildPlannerFollowUpConversation(String toolResult) {
		if (lastFrozenSnapshot == null) {
			throw new IllegalStateException("No frozen planner conversation");
		}
		return buildPlannerFollowUpConversation(lastFrozenSnapshot, null, toolResult);
	}

	public LlmConversation buildPlannerFollowUpConversation(String toolResult, LlmImageAttachment imageAttachment) {
		if (lastFrozenSnapshot == null) {
			throw new IllegalStateException("No frozen planner conversation");
		}
		return buildPlannerFollowUpConversation(lastFrozenSnapshot, null, toolResult, imageAttachment);
	}

	public LlmConversation buildCompactionConversation() {
		return composeConversation(
			clock.millis(),
			List.of(),
			LlmChatMessage.user(PlannerPromptPolicy.compactionInstruction(), LlmMessageKind.TASK)
		);
	}

	public void recordAgentTurn(DialogueTurn turn, JsonElement rawAssistantContent) {
		Objects.requireNonNull(turn, "turn");
		state = PlannerContextReducer.recordAcceptedAssistantTurn(state, turn, rawAssistantContent);
	}

	public void recordAcceptedToolExchange(JsonElement assistantRawContent, String toolResultText, long tick, long timestampMs) {
		if (assistantRawContent == null) {
			return;
		}
		state = PlannerContextReducer.recordAcceptedToolExchange(state, assistantRawContent, toolResultText, tick, timestampMs);
	}

	public void recordAgentTurn(DialogueTurn turn) {
		recordAgentTurn(turn, null);
	}

	public void recordUsage(LlmUsageSnapshot usage) {
		state = PlannerContextReducer.updateUsage(state, usage, compactionTriggerTokens);
	}

	public void recordObservedUsage(LlmUsageSnapshot usage) {
		state = PlannerContextReducer.updateObservedUsage(state, usage, state.compactionPending());
	}

	public void applyCheckpoint(CompactionCheckpoint checkpoint) {
		state = PlannerContextReducer.clearCompactionPending(state, checkpoint, clock.millis());
		lastFrozenSnapshot = null;
	}

	public void onCompactionFailure() {
		lastFrozenSnapshot = null;
	}

	public void clear() {
		state = PlannerContextState.initial();
		lastFrozenSnapshot = null;
		latestRequestSeed = null;
		overflowFlushPending = false;
	}

	private SemanticEventQueryResult pendingSemanticQueryResult() {
		List<SemanticEvent> events = state.pendingSemanticEvents();
		long oldestSeqNo = events.isEmpty() ? 0L : events.getFirst().seqNo();
		long latestSeqNo = events.isEmpty() ? state.lastObservedEventSeqNo() : events.getLast().seqNo();
		return new SemanticEventQueryResult(
			oldestSeqNo,
			latestSeqNo,
			state.pendingSemanticGapVersion() != 0L,
			List.copyOf(events)
		);
	}

	private SemanticContextProjectionResult pendingSemanticProjection(long anchorTimeMs) {
		return semanticContextProjector.project(pendingSemanticQueryResult(), anchorTimeMs);
	}

	private List<LlmChatMessage> renderSnapshotNotices(
		PlannerRequest request,
		PlannerAmbientContext ambientContext,
		long renderedTimeContextAtMs
	) {
		long anchorTimeMs = request.timestampMs();
		ArrayList<LlmChatMessage> messages = new ArrayList<>();
		if (renderedTimeContextAtMs >= 0L) {
			messages.add(ContextMessageRenderer.renderEntry(new PlannerContextEntry(
				PlannerContextEntryType.NOTICE,
				null,
				PlannerContextPolicy.timeBeaconText(renderedTimeContextAtMs, zoneId),
				-1L,
				renderedTimeContextAtMs
			), anchorTimeMs));
		}
		for (PlannerContextEntry entry : PlannerAmbientContextRenderer.renderChanges(
			state.lastAcceptedAmbientContext(),
			ambientContext,
			request.tick(),
			anchorTimeMs
		)) {
			messages.add(ContextMessageRenderer.renderEntry(entry, anchorTimeMs));
		}
		for (SemanticContextUpdate update : pendingSemanticProjection(anchorTimeMs).updates()) {
			messages.add(ContextMessageRenderer.renderEntry(PlannerContextEntry.semanticNotice(update), anchorTimeMs));
		}
		return List.copyOf(messages);
	}

	private LlmConversation composeConversation(
		long anchorTimeMs,
		List<LlmChatMessage> snapshotNotices,
		LlmChatMessage terminalMessage
	) {
		ArrayList<LlmChatMessage> messages = new ArrayList<>();
		messages.add(LlmChatMessage.system(PlannerPromptPolicy.systemPrompt(visionMode)));
		if (state.activeCheckpoint() != null) {
			messages.add(LlmChatMessage.user(state.activeCheckpoint().renderMessage(), LlmMessageKind.CHECKPOINT));
		}
		messages.addAll(renderAcceptedHistory(anchorTimeMs));
		messages.addAll(snapshotNotices);
		if (terminalMessage != null) {
			messages.add(terminalMessage);
		}
		return LlmConversation.of(messages);
	}

	private List<LlmChatMessage> renderAcceptedHistory(long anchorTimeMs) {
		ArrayList<LlmChatMessage> messages = new ArrayList<>();
		for (PlannerContextEntry entry : state.acceptedHistoryTape()) {
			messages.add(renderAcceptedHistoryEntry(entry, anchorTimeMs));
		}
		return List.copyOf(messages);
	}

	private static LlmChatMessage renderAcceptedHistoryEntry(PlannerContextEntry entry, long anchorTimeMs) {
		return switch (entry.type()) {
			case USER_TURN -> LlmChatMessage.user(entry.text(), LlmMessageKind.USER_TURN);
			case ASSISTANT_TURN -> LlmChatMessage.assistant(entry.text(), entry.rawAssistantContent());
			case TOOL_REQUEST -> LlmChatMessage.assistant(entry.text(), entry.rawAssistantContent());
			case TOOL_RESULT -> LlmChatMessage.user(entry.text(), LlmMessageKind.TOOL_RESULT);
			case NOTICE -> ContextMessageRenderer.renderEntry(entry, anchorTimeMs);
		};
	}

	private void recomputeOverflowFlushPending() {
		overflowFlushPending = state.pendingSemanticEvents().size() >= pendingSemanticEventCap;
	}
}
