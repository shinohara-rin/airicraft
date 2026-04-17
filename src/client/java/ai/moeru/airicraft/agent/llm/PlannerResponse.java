package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.events.EventPolicyChanges;
import com.google.gson.JsonElement;

public record PlannerResponse(
	String replyText,
	PlannerIntent intent,
	PlannerToolRequest toolRequest,
	EventPolicyChanges eventPolicyChanges,
	PlannerToolCall toolCall,
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

	public PlannerResponse(String replyText, PlannerToolCall toolCall, JsonElement rawAssistantContent) {
		this(replyText, new PlannerIntent(toolCall == null ? "reply_only" : "none", null, null), null, null, toolCall, rawAssistantContent);
	}

	public PlannerResponse {
		replyText = replyText == null ? "" : replyText;
		rawAssistantContent = rawAssistantContent == null || rawAssistantContent.isJsonNull()
			? null
			: rawAssistantContent.deepCopy();
	}
}
