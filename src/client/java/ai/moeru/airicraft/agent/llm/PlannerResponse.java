package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.events.EventPolicyChanges;
import com.google.gson.JsonElement;

import java.util.List;

public record PlannerResponse(
	String replyText,
	PlannerIntent intent,
	PlannerToolRequest toolRequest,
	EventPolicyChanges eventPolicyChanges,
	PlannerToolCall toolCall,
	List<PlannerToolCall> toolCalls,
	JsonElement rawAssistantContent
) {
	public PlannerResponse(String replyText, PlannerIntent intent) {
		this(replyText, intent, null, null, null, null);
	}

	public PlannerResponse(String replyText, PlannerIntent intent, PlannerToolRequest toolRequest) {
		this(replyText, intent, toolRequest, null, null, null);
	}

	public PlannerResponse(String replyText, PlannerIntent intent, PlannerToolRequest toolRequest, EventPolicyChanges eventPolicyChanges) {
		this(replyText, intent, toolRequest, eventPolicyChanges, null, null);
	}

	public PlannerResponse(
		String replyText,
		PlannerIntent intent,
		PlannerToolRequest toolRequest,
		EventPolicyChanges eventPolicyChanges,
		JsonElement rawAssistantContent
	) {
		this(replyText, intent, toolRequest, eventPolicyChanges, null, rawAssistantContent);
	}

	public PlannerResponse(
		String replyText,
		PlannerIntent intent,
		PlannerToolRequest toolRequest,
		EventPolicyChanges eventPolicyChanges,
		PlannerToolCall toolCall,
		JsonElement rawAssistantContent
	) {
		this(
			replyText,
			intent,
			toolRequest,
			eventPolicyChanges,
			toolCall,
			toolCall == null ? List.of() : List.of(toolCall),
			rawAssistantContent
		);
	}

	public PlannerResponse(String replyText, PlannerToolCall toolCall, JsonElement rawAssistantContent) {
		this(replyText, new PlannerIntent(toolCall == null ? "reply_only" : "none", null, null), null, null, toolCall, rawAssistantContent);
	}

	public static PlannerResponse toolCalls(List<PlannerToolCall> toolCalls, JsonElement rawAssistantContent) {
		List<PlannerToolCall> normalizedToolCalls = normalizeToolCalls(toolCalls, null);
		return new PlannerResponse(
			"",
			new PlannerIntent(normalizedToolCalls.isEmpty() ? "reply_only" : "none", null, null),
			null,
			null,
			normalizedToolCalls.isEmpty() ? null : normalizedToolCalls.getFirst(),
			normalizedToolCalls,
			rawAssistantContent
		);
	}

	public PlannerResponse {
		replyText = replyText == null ? "" : replyText;
		toolCalls = normalizeToolCalls(toolCalls, toolCall);
		toolCall = toolCalls.isEmpty() ? null : toolCalls.getFirst();
		rawAssistantContent = rawAssistantContent == null || rawAssistantContent.isJsonNull()
			? null
			: rawAssistantContent.deepCopy();
	}

	private static List<PlannerToolCall> normalizeToolCalls(List<PlannerToolCall> toolCalls, PlannerToolCall toolCall) {
		if (toolCalls == null || toolCalls.isEmpty()) {
			return toolCall == null ? List.of() : List.of(toolCall);
		}
		return toolCalls.stream()
			.filter(call -> call != null)
			.toList();
	}
}
