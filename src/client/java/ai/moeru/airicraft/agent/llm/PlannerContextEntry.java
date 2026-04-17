package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.semantic.SemanticContextUpdate;
import com.google.gson.JsonElement;

import java.util.Objects;

public record PlannerContextEntry(
	PlannerContextEntryType type,
	String speaker,
	String text,
	long tick,
		long timestampMs,
		SemanticContextUpdate semanticUpdate,
		JsonElement rawAssistantContent,
		PlannerToolCall toolCall
	) {
	public PlannerContextEntry(
		PlannerContextEntryType type,
		String speaker,
		String text,
		long tick,
		long timestampMs
	) {
			this(type, speaker, text, tick, timestampMs, null, null, null);
	}

	public PlannerContextEntry {
		type = Objects.requireNonNull(type, "type");
		text = Objects.requireNonNull(text, "text");
		rawAssistantContent = rawAssistantContent == null || rawAssistantContent.isJsonNull()
			? null
			: rawAssistantContent.deepCopy();
	}

	public static PlannerContextEntry semanticNotice(SemanticContextUpdate update) {
		Objects.requireNonNull(update, "update");
		return new PlannerContextEntry(
			PlannerContextEntryType.NOTICE,
			null,
			update.text(),
			update.tick(),
				update.timestampMs(),
				update,
				null,
				null
			);
	}

	public static PlannerContextEntry toolRequest(JsonElement assistantRawContent, long tick, long timestampMs) {
		Objects.requireNonNull(assistantRawContent, "assistantRawContent");
		String visibleText = OpenAiCompatibleMessageContent.extractVisibleText(assistantRawContent);
		return new PlannerContextEntry(
			PlannerContextEntryType.TOOL_REQUEST,
			null,
			visibleText == null ? "" : visibleText,
			tick,
				timestampMs,
				null,
				assistantRawContent,
				null
			);
	}

	public static PlannerContextEntry toolRequest(PlannerToolCall toolCall, long tick, long timestampMs) {
		Objects.requireNonNull(toolCall, "toolCall");
		return new PlannerContextEntry(
			PlannerContextEntryType.TOOL_REQUEST,
			null,
			"",
			tick,
			timestampMs,
			null,
			null,
			toolCall
		);
	}

	public static PlannerContextEntry toolResult(String toolResultText, long tick, long timestampMs) {
		return toolResult(toolResultText, null, tick, timestampMs);
	}

	public static PlannerContextEntry toolResult(String toolResultText, PlannerToolCall toolCall, long tick, long timestampMs) {
		String body = toolResultText == null || toolResultText.isBlank() ? "none" : toolResultText;
		return new PlannerContextEntry(
			PlannerContextEntryType.TOOL_RESULT,
			null,
			"Tool result: " + body,
			tick,
			timestampMs,
			null,
			null,
			toolCall
		);
	}
}
