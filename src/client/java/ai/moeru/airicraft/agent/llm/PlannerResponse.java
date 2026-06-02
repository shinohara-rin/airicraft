package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.events.EventPolicyChanges;
import com.google.gson.JsonElement;

import java.util.List;

public record PlannerResponse(
	String replyText,
	PlannerIntent intent,
	PlannerToolRequest toolRequest,
	EventPolicyChanges eventPolicyChanges,
	List<PlannerToolCall> toolCalls,
	JsonElement rawAssistantContent
) {
	public PlannerResponse(String replyText, PlannerIntent intent) {
		this(replyText, intent, null, null, List.of(), null);
	}

	public PlannerResponse(String replyText, PlannerIntent intent, PlannerToolRequest toolRequest) {
		this(replyText, intent, toolRequest, null, List.of(), null);
	}

	public PlannerResponse(String replyText, PlannerIntent intent, PlannerToolRequest toolRequest, EventPolicyChanges eventPolicyChanges) {
		this(replyText, intent, toolRequest, eventPolicyChanges, List.of(), null);
	}

	public PlannerResponse(
		String replyText,
		PlannerIntent intent,
		PlannerToolRequest toolRequest,
		EventPolicyChanges eventPolicyChanges,
		PlannerToolCall toolCall,
		JsonElement rawAssistantContent
	) {
		this(replyText, intent, toolRequest, eventPolicyChanges, toolCall == null ? List.of() : List.of(toolCall), rawAssistantContent);
	}

	public PlannerResponse(
		String replyText,
		PlannerIntent intent,
		PlannerToolRequest toolRequest,
		EventPolicyChanges eventPolicyChanges,
		JsonElement rawAssistantContent
	) {
		this(replyText, intent, toolRequest, eventPolicyChanges, List.of(), rawAssistantContent);
	}

	public PlannerResponse(String replyText, PlannerToolCall toolCall, JsonElement rawAssistantContent) {
		this(replyText, toolCall == null ? List.of() : List.of(toolCall), rawAssistantContent);
	}

	public PlannerResponse(String replyText, List<PlannerToolCall> toolCalls, JsonElement rawAssistantContent) {
		this(replyText, new PlannerIntent(toolCalls == null || toolCalls.isEmpty() ? "reply_only" : "none", null, null), null, null, toolCalls, rawAssistantContent);
	}

	public PlannerResponse {
		replyText = replyText == null ? "" : replyText;
		toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
		rawAssistantContent = rawAssistantContent == null || rawAssistantContent.isJsonNull()
			? null
			: rawAssistantContent.deepCopy();
	}

	public PlannerToolCall toolCall() {
		return toolCalls.isEmpty() ? null : toolCalls.getFirst();
	}
}
