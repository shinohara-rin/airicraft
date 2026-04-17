package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public record PlannerToolCall(
	String id,
	String name,
	JsonObject arguments,
	String narration,
	JsonElement rawToolCall
) {
	public PlannerToolCall {
		id = id == null || id.isBlank() ? "call_planner_tool" : id;
		name = name == null ? "" : name;
		arguments = arguments == null ? new JsonObject() : arguments.deepCopy();
		narration = narration == null || narration.isBlank() ? null : narration.trim();
		rawToolCall = rawToolCall == null || rawToolCall.isJsonNull() ? null : rawToolCall.deepCopy();
	}
}
