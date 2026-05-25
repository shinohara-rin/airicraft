package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.dialogue.DialogueSpeakerLabels;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PlannerPromptPolicy {
	private static final String SYSTEM_PROMPT_TEMPLATE = "/prompts/planner-system.md";
	private static final String COMPACTION_PROMPT_TEMPLATE = "/prompts/planner-compaction.md";
	private static final String SYSTEM_PROMPT_TEMPLATE_TEXT = readTemplate(SYSTEM_PROMPT_TEMPLATE);
	private static final String COMPACTION_PROMPT_TEMPLATE_TEXT = readTemplate(COMPACTION_PROMPT_TEMPLATE);

	private PlannerPromptPolicy() {
	}

	public static String systemPrompt(PlannerVisionMode visionMode) {
		return systemPrompt(visionMode, PlannerToolRegistry.empty(), List.of());
	}

	public static String systemPrompt(PlannerVisionMode visionMode, PlannerToolRegistry toolRegistry) {
		return systemPrompt(visionMode, toolRegistry, List.of());
	}

	public static String systemPrompt(PlannerVisionMode visionMode, PlannerToolRegistry toolRegistry, List<String> commonsenseRules) {
		PlannerToolRegistry effectiveToolRegistry = toolRegistry == null ? PlannerToolRegistry.empty() : toolRegistry;
		String visionInstruction = switch (visionMode) {
			case EXTERNAL_SUMMARY -> "If you need visual information, call take_a_look with a short prompt describing what the separate vision model should inspect.";
			case NATIVE_TOOL_IMAGE -> "If you need visual information, call take_a_look.";
		};
		LinkedHashMap<String, String> placeholders = new LinkedHashMap<>();
		placeholders.put("available_tool_line", availableToolLine(effectiveToolRegistry));
		placeholders.put("vision_instruction", visionInstruction);
		placeholders.put("provider_tool_instructions", effectiveToolRegistry.promptInstructions());
		placeholders.put("same_client_admin", DialogueSpeakerLabels.SAME_CLIENT_ADMIN);
		placeholders.put("commonsense_rules", renderCommonsenseRules(commonsenseRules));
		return renderTemplate(SYSTEM_PROMPT_TEMPLATE, SYSTEM_PROMPT_TEMPLATE_TEXT, placeholders);
	}

	private static String renderCommonsenseRules(List<String> rules) {
		if (rules == null || rules.isEmpty()) {
			return "";
		}
		StringBuilder builder = new StringBuilder("Minecraft commonsense rules (apply on every turn):\n");
		for (String rule : rules) {
			if (rule == null || rule.isBlank()) {
				continue;
			}
			builder.append("- ").append(rule.strip()).append('\n');
		}
		int trailingNewline = builder.length() - 1;
		if (trailingNewline >= 0 && builder.charAt(trailingNewline) == '\n') {
			builder.deleteCharAt(trailingNewline);
		}
		return builder.toString();
	}

	private static String availableToolLine(PlannerToolRegistry toolRegistry) {
		return "Available tools: " + toolRegistry.availableToolNames() + ".";
	}

	public static String compactionInstruction() {
		return COMPACTION_PROMPT_TEMPLATE_TEXT;
	}

	private static String renderTemplate(String resourcePath, String template, Map<String, String> values) {
		String rendered = template;
		for (Map.Entry<String, String> entry : values.entrySet()) {
			rendered = rendered.replace("{{" + entry.getKey() + "}}", entry.getValue());
		}
		if (rendered.contains("{{")) {
			throw new IllegalStateException("Unresolved prompt placeholder in " + resourcePath);
		}
		return rendered;
	}

	private static String readTemplate(String resourcePath) {
		try (InputStream stream = PlannerPromptPolicy.class.getResourceAsStream(resourcePath)) {
			if (stream == null) {
				throw new IllegalStateException("Missing embedded planner prompt template: " + resourcePath);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException exception) {
			throw new UncheckedIOException("Failed to read planner prompt template: " + resourcePath, exception);
		}
	}
}
