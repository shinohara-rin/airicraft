package ai.moeru.airicraft.agent.shell;

import ai.moeru.airicraft.agent.llm.CompactionExecutionResult;
import ai.moeru.airicraft.agent.llm.LlmConversation;
import ai.moeru.airicraft.agent.llm.PlannerExecutionResult;
import ai.moeru.airicraft.agent.llm.PlannerLifecycleListener;
import ai.moeru.airicraft.agent.llm.PlannerRequest;
import ai.moeru.airicraft.agent.llm.PlannerSessionPhase;
import ai.moeru.airicraft.agent.llm.PlannerToolCall;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class PlannerShellJournal implements PlannerLifecycleListener {
	private final int maxEntries;
	private final Clock clock;
	private final ArrayDeque<PlannerShellEvent> entries = new ArrayDeque<>();

	public PlannerShellJournal(int maxEntries, Clock clock) {
		this.maxEntries = Math.max(1, maxEntries);
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	@Override
	public synchronized void onPlannerTurnSubmitted(PlannerRequest request) {
		append("turn_submitted", 0L, 0, null, summarizeRequest(request));
	}

	@Override
	public synchronized void onConversationSubmitted(
		long generation,
		int attempt,
		PlannerSessionPhase phase,
		PlannerRequest request,
		LlmConversation conversation
	) {
		append("conversation_submitted", generation, attempt, phase, "messages=" + (conversation == null ? 0 : conversation.messages().size()));
	}

	@Override
	public synchronized void onPlannerExecutionSucceeded(PlannerExecutionResult result) {
		append(
			"planner_succeeded",
			result == null ? 0L : result.generation(),
			result == null ? 0 : result.attempt(),
			result == null ? null : result.phase(),
			result == null || result.response() == null ? "no_response" : summarizeReply(result.response().replyText())
		);
	}

	@Override
	public synchronized void onPlannerExecutionFailed(PlannerExecutionResult result) {
		append(
			"planner_failed",
			result == null ? 0L : result.generation(),
			result == null ? 0 : result.attempt(),
			result == null ? null : result.phase(),
			result == null ? "unknown_failure" : String.valueOf(result.failureType())
		);
	}

	@Override
	public synchronized void onToolRequested(long generation, PlannerToolCall toolCall) {
		append(
			"tool_requested",
			generation,
			0,
			PlannerSessionPhase.TOOL_WAIT,
			toolCall == null ? "unknown_tool" : toolCall.name()
		);
	}

	@Override
	public synchronized void onToolCompleted(long generation, String toolResult, boolean imageAttached) {
		append(
			"tool_completed",
			generation,
			0,
			PlannerSessionPhase.TOOL_FOLLOW_UP,
			(imageAttached ? "image:" : "text:") + summarizeReply(toolResult)
		);
	}

	@Override
	public synchronized void onCompactionCompleted(CompactionExecutionResult result) {
		append(
			result != null && result.succeeded() ? "compaction_succeeded" : "compaction_failed",
			0L,
			0,
			null,
			result == null ? "unknown" : result.succeeded() ? "checkpoint_updated" : String.valueOf(result.failureType())
		);
	}

	@Override
	public synchronized void onReset(String reason) {
		append("planner_reset", 0L, 0, null, reason == null ? "reset" : reason);
	}

	public synchronized List<PlannerShellEvent> snapshot() {
		return List.copyOf(new ArrayList<>(entries));
	}

	private void append(String kind, long generation, int attempt, PlannerSessionPhase phase, String detail) {
		entries.addLast(new PlannerShellEvent(
			clock.millis(),
			kind,
			generation,
			attempt,
			phase == null ? null : phase.name(),
			detail
		));
		while (entries.size() > maxEntries) {
			entries.removeFirst();
		}
	}

	private static String summarizeRequest(PlannerRequest request) {
		if (request == null) {
			return "request:none";
		}
		String sender = request.senderName() == null || request.senderName().isBlank() ? "unknown" : request.senderName();
		return "sender=" + sender + " message=" + summarizeReply(request.message());
	}

	private static String summarizeReply(String value) {
		if (value == null || value.isBlank()) {
			return "";
		}
		String normalized = value.replace('\n', ' ').trim();
		return normalized.length() <= 96 ? normalized : normalized.substring(0, 93) + "...";
	}
}
