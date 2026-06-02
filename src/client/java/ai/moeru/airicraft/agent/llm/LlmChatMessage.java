package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonElement;

import java.util.List;
import java.util.Objects;

public record LlmChatMessage(
	String role,
	String content,
	LlmMessageKind kind,
	LlmImageAttachment imageAttachment,
	JsonElement rawContentOverride,
	List<PlannerToolCall> toolCalls,
	String toolCallId
) {
	public LlmChatMessage(String role, String content, LlmMessageKind kind, LlmImageAttachment imageAttachment) {
		this(role, content, kind, imageAttachment, null, List.of(), null);
	}

	public LlmChatMessage(String role, String content, LlmMessageKind kind, LlmImageAttachment imageAttachment, JsonElement rawContentOverride) {
		this(role, content, kind, imageAttachment, rawContentOverride, List.of(), null);
	}

	public LlmChatMessage {
		role = Objects.requireNonNull(role, "role");
		content = content == null ? "" : content;
		kind = Objects.requireNonNull(kind, "kind");
		rawContentOverride = rawContentOverride == null || rawContentOverride.isJsonNull()
			? null
			: rawContentOverride.deepCopy();
		toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
		toolCallId = toolCallId == null || toolCallId.isBlank() ? null : toolCallId;
	}

	public static LlmChatMessage system(String content) {
		return new LlmChatMessage("system", content, LlmMessageKind.SYSTEM, null);
	}

	public static LlmChatMessage user(String content, LlmMessageKind kind) {
		return new LlmChatMessage("user", content, kind, null);
	}

	public static LlmChatMessage userWithImage(String content, LlmMessageKind kind, LlmImageAttachment imageAttachment) {
		return new LlmChatMessage("user", content, kind, Objects.requireNonNull(imageAttachment, "imageAttachment"));
	}

	public static LlmChatMessage assistant(String content) {
		return new LlmChatMessage("assistant", content, LlmMessageKind.ASSISTANT_TURN, null);
	}

	public static LlmChatMessage assistant(String content, JsonElement rawContentOverride) {
		return new LlmChatMessage("assistant", content, LlmMessageKind.ASSISTANT_TURN, null, rawContentOverride);
	}

	public static LlmChatMessage assistantToolCall(String content, PlannerToolCall toolCall) {
		return new LlmChatMessage("assistant", content, LlmMessageKind.ASSISTANT_TURN, null, null, List.of(Objects.requireNonNull(toolCall, "toolCall")), null);
	}

	public static LlmChatMessage assistantToolCalls(String content, List<PlannerToolCall> toolCalls) {
		return new LlmChatMessage("assistant", content, LlmMessageKind.ASSISTANT_TURN, null, null, Objects.requireNonNull(toolCalls, "toolCalls"), null);
	}

	public static LlmChatMessage tool(String toolCallId, String content) {
		return new LlmChatMessage("tool", content, LlmMessageKind.TOOL_RESULT, null, null, List.of(), toolCallId);
	}

	public boolean hasImageAttachment() {
		return imageAttachment != null;
	}

	public boolean hasToolCalls() {
		return !toolCalls.isEmpty();
	}
}
