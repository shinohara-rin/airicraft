package ai.moeru.airicraft.agent.dialogue;

import ai.moeru.airicraft.agent.llm.LlmFailureType;
import ai.moeru.airicraft.agent.llm.PlannerResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DialogueCore {
	static final int DEGRADED_FAILURE_THRESHOLD = 3;
	static final String RESET_COMMAND = "@agent reset";
	static final String DEGRADED_MESSAGE = "I'm having trouble understanding right now. Send '@agent reset' to recover my planner.";
	static final String RESET_MESSAGE = "Planner state reset.";
	static final String PARSE_ERROR_MESSAGE = "I got confused for a moment.";
	static final String TIMEOUT_MESSAGE = "I hit a timeout just now. Please try again.";
	static final String PROVIDER_UNAVAILABLE_MESSAGE = "I can't reach the LLM provider right now. Please try again.";

	private DialogueCore() {
	}

	public static DialogueState initialState() {
		return DialogueState.initial();
	}

	public static boolean isResetCommand(String plainTextMessage) {
		if (plainTextMessage == null) {
			return false;
		}
		return plainTextMessage.stripLeading().equalsIgnoreCase(RESET_COMMAND);
	}

	public static DialogueState markReplyObserved(DialogueState state) {
		return state.withPendingReply(false, null);
	}

	public static DialogueTransition onPlannerSuccess(DialogueState state, PlannerResponse plannerResponse, long tick) {
		DialogueIntentType mappedIntentType = DialogueIntentType.fromWire(plannerResponse.intent().type()).orElse(null);
		ArrayList<DialogueEffect> effects = new ArrayList<>();
		if (mappedIntentType == null) {
			effects.add(DialogueEffect.appendSemanticEvent("planner.unknown_intent", Map.of(
				"type", plannerResponse.intent().type()
			)));
			mappedIntentType = DialogueIntentType.NONE;
		}

		DialogueResponse response = new DialogueResponse(
			plannerResponse.replyText() == null ? "" : plannerResponse.replyText(),
			new DialogueIntent(
				mappedIntentType,
				plannerResponse.intent().goalType(),
				plannerResponse.intent().targetPlayer(),
				plannerResponse.intent().position(),
				plannerResponse.intent().mineSpec(),
				plannerResponse.intent().taskSpec(),
				plannerResponse.intent().taskLedger(),
				plannerResponse.intent().activeJob()
			),
			tick,
			plannerResponse.eventPolicyChanges()
		);
		DialogueState nextState = state
			.withConsecutiveFailureCount(0)
			.withLastResponse(response)
			.withPendingReply(hasVisibleText(response), hasVisibleText(response) ? "planner_success" : null);
		return new DialogueTransition(nextState, List.of(response), List.copyOf(effects));
	}

	public static DialogueTransition onPlannerFailure(
		DialogueState state,
		LlmFailureType failureType,
		String failureMessage,
		boolean directChatTrigger,
		long tick
	) {
		ArrayList<DialogueEffect> effects = new ArrayList<>();
		effects.add(DialogueEffect.appendSemanticEvent(failureEventType(failureType), Map.of(
			"failureType", failureType.name(),
			"message", failureMessage == null ? "" : failureMessage
		)));

		ArrayList<DialogueResponse> visibleResponses = new ArrayList<>();
		if (failureType == LlmFailureType.PARSE_ERROR) {
			visibleResponses.add(new DialogueResponse(
				PARSE_ERROR_MESSAGE,
				new DialogueIntent(DialogueIntentType.ACKNOWLEDGE_FAILURE, null, null),
				tick
			));
		}
		else if (failureType == LlmFailureType.TIMEOUT && directChatTrigger) {
			visibleResponses.add(new DialogueResponse(
				TIMEOUT_MESSAGE,
				new DialogueIntent(DialogueIntentType.ACKNOWLEDGE_FAILURE, null, null),
				tick
			));
		}
		else if (failureType == LlmFailureType.PROVIDER_UNAVAILABLE && directChatTrigger) {
			visibleResponses.add(new DialogueResponse(
				PROVIDER_UNAVAILABLE_MESSAGE,
				new DialogueIntent(DialogueIntentType.ACKNOWLEDGE_FAILURE, null, null),
				tick
			));
		}

		int consecutiveFailureCount = state.consecutiveFailureCount() + 1;
		boolean degraded = state.degraded();
		if (consecutiveFailureCount >= DEGRADED_FAILURE_THRESHOLD && !degraded) {
			degraded = true;
			effects.add(DialogueEffect.appendSemanticEvent("planner.degraded_entered", Map.of(
				"failureType", failureType.name(),
				"consecutiveFailureCount", consecutiveFailureCount
			)));
			visibleResponses.add(new DialogueResponse(
				DEGRADED_MESSAGE,
				new DialogueIntent(DialogueIntentType.ACKNOWLEDGE_FAILURE, null, null),
				tick
			));
		}

		DialogueResponse lastResponse = visibleResponses.isEmpty() ? null : visibleResponses.get(visibleResponses.size() - 1);
		String pendingReplyReason = null;
		if (lastResponse != null && hasVisibleText(lastResponse)) {
			pendingReplyReason = switch (failureType) {
				case PARSE_ERROR -> "parse_error_visible_reply";
				case TIMEOUT -> "timeout_visible_reply";
				case PROVIDER_UNAVAILABLE -> "provider_unavailable_visible_reply";
				default -> null;
			};
		}
		DialogueState nextState = state
			.withLastFailureType(failureType)
			.withLastFailureTick(tick)
			.withConsecutiveFailureCount(consecutiveFailureCount)
			.withDegraded(degraded)
			.withLastResponse(lastResponse)
			.withPendingReply(lastResponse != null && hasVisibleText(lastResponse), pendingReplyReason);
		return new DialogueTransition(nextState, List.copyOf(visibleResponses), List.copyOf(effects));
	}

	public static DialogueTransition onPlannerDegradedBlocked(DialogueState state, String senderName, boolean directChatTrigger, long tick) {
		ArrayList<DialogueEffect> effects = new ArrayList<>();
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("directChat", directChatTrigger);
		payload.put("consecutiveFailureCount", state.consecutiveFailureCount());
		if (state.lastFailureType() != null) {
			payload.put("failureType", state.lastFailureType().name());
		}
		if (senderName != null && !senderName.isBlank()) {
			payload.put("speaker", senderName);
		}
		effects.add(DialogueEffect.appendSemanticEvent("planner.degraded_blocked", payload));

		if (!directChatTrigger) {
			return new DialogueTransition(state, List.of(), List.copyOf(effects));
		}

		DialogueResponse response = new DialogueResponse(
			DEGRADED_MESSAGE,
			new DialogueIntent(DialogueIntentType.ACKNOWLEDGE_FAILURE, null, null),
			tick
		);
		DialogueState nextState = state
			.withLastResponse(response)
			.withPendingReply(true, "planner_degraded_visible_reply");
		return new DialogueTransition(nextState, List.of(response), List.copyOf(effects));
	}

	public static DialogueTransition onReset(DialogueState state, String senderName, long tick) {
		ArrayList<DialogueEffect> effects = new ArrayList<>();
		effects.add(DialogueEffect.appendSemanticEvent("planner.reset_requested", Map.of(
			"player", senderName
		)));
		if (state.degraded()) {
			effects.add(DialogueEffect.appendSemanticEvent("planner.degraded_cleared", Map.of()));
		}

		DialogueResponse response = new DialogueResponse(
			RESET_MESSAGE,
			new DialogueIntent(DialogueIntentType.ACKNOWLEDGE_FAILURE, null, senderName),
			tick
		);
		DialogueState nextState = DialogueState.initial()
			.withLastResponse(response)
			.withPendingReply(true, "reset");
		return new DialogueTransition(nextState, List.of(response), List.copyOf(effects));
	}

	private static boolean hasVisibleText(DialogueResponse response) {
		return response != null && response.text() != null && !response.text().isBlank();
	}

	private static String failureEventType(LlmFailureType failureType) {
		return switch (failureType) {
			case TIMEOUT -> "planner.timeout";
			case PARSE_ERROR -> "planner.parse_error";
			case PROVIDER_ERROR, PROVIDER_UNAVAILABLE -> "planner.provider_error";
		};
	}
}
