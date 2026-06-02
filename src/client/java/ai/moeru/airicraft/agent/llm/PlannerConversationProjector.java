package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class PlannerConversationProjector {
	private final int messageLimit;

	public PlannerConversationProjector(int messageLimit) {
		this.messageLimit = Math.max(1, messageLimit);
	}

	public PlannerConversationDebugSnapshot submittedSnapshot(PlannerTurnJournal journal) {
		PlannerTurnEvent submission = latestSubmission(journal);
		if (submission == null) {
			return PlannerConversationDebugSnapshot.empty();
		}
		return PlannerConversationDebugSnapshot.fromConversation(
			submission.generation(),
			phase(submission.phase()),
			submission.attempt(),
			submission.conversation()
		);
	}

	public PlannerConversationDebugSnapshot projectedSnapshot(PlannerTurnJournal journal) {
		Objects.requireNonNull(journal, "journal");
		List<PlannerTurnEvent> events = journal.snapshot();
		PlannerTurnEvent latestSubmission = latestSubmission(events);
		if (latestSubmission == null) {
			return PlannerConversationDebugSnapshot.empty();
		}

		PlannerConversationDebugSnapshot submitted = PlannerConversationDebugSnapshot.fromConversation(
			latestSubmission.generation(),
			phase(latestSubmission.phase()),
			latestSubmission.attempt(),
			latestSubmission.conversation()
		);
		ArrayList<PlannerConversationDebugMessage> messages = new ArrayList<>();
		for (PlannerTurnEvent event : events) {
			if (event.id() >= latestSubmission.id()) {
				break;
			}
			if (!isVisiblePersistentCard(event.debugMessage()) || journal.isSuperseded(event.generation())) {
				continue;
			}
			messages.add(event.debugMessage());
		}
		messages.addAll(submitted.messages());
		for (PlannerTurnEvent event : events) {
			if (event.id() <= latestSubmission.id() || event.generation() != latestSubmission.generation()) {
				continue;
			}
			if (!Objects.equals(event.phase(), latestSubmission.phase()) || event.attempt() != latestSubmission.attempt()) {
				continue;
			}
			if (event.debugMessage() != null && !journal.isSuperseded(event.generation())) {
				messages.add(event.debugMessage());
			}
		}
		return new PlannerConversationDebugSnapshot(
			latestSubmission.generation(),
			latestSubmission.phase(),
			latestSubmission.attempt(),
			trim(messages)
		);
	}

	public List<String> contextExcerpt(PlannerTurnJournal journal) {
		PlannerConversationDebugSnapshot snapshot = projectedSnapshot(journal);
		if (snapshot.isEmpty()) {
			return List.of();
		}
		ArrayList<String> excerpt = new ArrayList<>();
		for (PlannerConversationDebugMessage message : snapshot.messages()) {
			if (message.kind() != PlannerConversationDebugKind.NOTICE && message.kind() != PlannerConversationDebugKind.CHECKPOINT) {
				continue;
			}
			if (message.text() != null && !message.text().isBlank()) {
				excerpt.add(message.text());
			}
		}
		return List.copyOf(excerpt);
	}

	public LlmConversation appendToolExchanges(LlmConversation conversation, List<PlannerTurnEvent> exchanges) {
		LlmConversation updated = conversation;
		for (PlannerTurnEvent exchange : exchanges == null ? List.<PlannerTurnEvent>of() : exchanges) {
			if (exchange.toolCall() != null) {
				updated = updated
					.withAppended(LlmChatMessage.assistantToolCall("", exchange.toolCall()))
					.withAppended(LlmChatMessage.tool(exchange.toolCall().id(), toolResultContent(exchange.toolResultText())));
			}
			else if (exchange.assistantRawContent() != null) {
				updated = updated
					.withAppended(LlmChatMessage.assistant(
						OpenAiCompatibleMessageContent.extractVisibleText(exchange.assistantRawContent()),
						exchange.assistantRawContent()
					))
					.withAppended(LlmChatMessage.user(
						"Tool result: " + toolResultContent(exchange.toolResultText()),
						LlmMessageKind.TOOL_RESULT
					));
			}
		}
		return updated;
	}

	private PlannerTurnEvent latestSubmission(PlannerTurnJournal journal) {
		return latestSubmission(journal == null ? List.of() : journal.snapshot());
	}

	private static PlannerTurnEvent latestSubmission(List<PlannerTurnEvent> events) {
		for (int index = events.size() - 1; index >= 0; index--) {
			PlannerTurnEvent event = events.get(index);
			if (event.kind() == PlannerTurnEvent.Kind.SUBMISSION) {
				return event;
			}
		}
		return null;
	}

	private static boolean isVisiblePersistentCard(PlannerConversationDebugMessage message) {
		if (message == null) {
			return false;
		}
		return switch (message.kind()) {
			case ASSISTANT_TURN, TOOL_RESULT, TASK, FAILURE -> true;
			case SYSTEM, CHECKPOINT, NOTICE, USER_TURN -> false;
		};
	}

	private List<PlannerConversationDebugMessage> trim(List<PlannerConversationDebugMessage> messages) {
		if (messages == null || messages.isEmpty()) {
			return List.of();
		}
		ArrayList<PlannerConversationDebugMessage> trimmed = new ArrayList<>(messages);
		while (trimmed.size() > messageLimit) {
			int removableIndex = firstNonPersistentIndex(trimmed);
			trimmed.remove(removableIndex >= 0 ? removableIndex : 0);
		}
		return List.copyOf(trimmed);
	}

	private static int firstNonPersistentIndex(List<PlannerConversationDebugMessage> messages) {
		for (int index = 0; index < messages.size(); index++) {
			if (!isVisiblePersistentCard(messages.get(index))) {
				return index;
			}
		}
		return -1;
	}

	private static PlannerSessionPhase phase(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return PlannerSessionPhase.valueOf(value);
		}
		catch (IllegalArgumentException exception) {
			return null;
		}
	}

	private static String toolResultContent(String toolResultText) {
		if (toolResultText == null || toolResultText.isBlank()) {
			return "Tool result: none";
		}
		return toolResultText;
	}
}
