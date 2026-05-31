package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.dialogue.DialogueSpeakerLabels;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class PlannerPromptPolicy {
	private static final String SYSTEM_PROMPT_TEMPLATE = "/prompts/planner-system.md";
	private static final String COMPACTION_PROMPT_TEMPLATE = "/prompts/planner-compaction.md";
	private static final String SYSTEM_PROMPT_TEMPLATE_TEXT = readTemplate(SYSTEM_PROMPT_TEMPLATE);
	private static final String COMPACTION_PROMPT_TEMPLATE_TEXT = readTemplate(COMPACTION_PROMPT_TEMPLATE);

	private PlannerPromptPolicy() {
	}

	public static String systemPrompt(PlannerVisionMode visionMode) {
		return systemPrompt(visionMode, PlannerToolRegistry.empty());
	}

	public static String systemPrompt(PlannerVisionMode visionMode, PlannerToolRegistry toolRegistry) {
		PlannerToolRegistry effectiveToolRegistry = toolRegistry == null ? PlannerToolRegistry.empty() : toolRegistry;
		String visionInstruction = switch (visionMode) {
			case EXTERNAL_SUMMARY -> "If you need visual information, call take_a_look with a short prompt describing what the separate vision model should inspect.";
			case NATIVE_TOOL_IMAGE -> "If you need visual information, call take_a_look.";
		};
		return renderTemplate(SYSTEM_PROMPT_TEMPLATE, SYSTEM_PROMPT_TEMPLATE_TEXT, Map.of(
			"available_tool_line", availableToolLine(effectiveToolRegistry, visionMode),
			"vision_instruction", visionInstruction,
			"provider_tool_instructions", effectiveToolRegistry.promptInstructions(),
			"same_client_admin", DialogueSpeakerLabels.SAME_CLIENT_ADMIN
		));
	}

	private static String availableToolLine(PlannerToolRegistry toolRegistry, PlannerVisionMode visionMode) {
		return "Available tools: " + toolRegistry.availableToolNames(visionMode) + ".";
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
