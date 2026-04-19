package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public final class PlannerToolRegistry {
	private static final PlannerToolRegistry EMPTY = new PlannerToolRegistry(List.of());

	private final List<PlannerToolProvider> providers;

	private PlannerToolRegistry(List<PlannerToolProvider> providers) {
		this.providers = List.copyOf(providers);
	}

	public static PlannerToolRegistry empty() {
		return EMPTY;
	}

	public static PlannerToolRegistry of(PlannerToolProvider... providers) {
		if (providers == null || providers.length == 0) {
			return empty();
		}
		return new PlannerToolRegistry(Arrays.stream(providers)
			.filter(Objects::nonNull)
			.toList());
	}

	public List<Map<String, Object>> openAiTools() {
		ArrayList<Map<String, Object>> tools = new ArrayList<>(PlannerToolCatalog.openAiTools());
		for (PlannerToolProvider provider : providers) {
			if (provider.available()) {
				tools.addAll(provider.openAiTools());
			}
		}
		return List.copyOf(tools);
	}

	public String promptInstructions() {
		return providers.stream()
			.filter(PlannerToolProvider::available)
			.map(PlannerToolProvider::promptInstructions)
			.filter(instruction -> instruction != null && !instruction.isBlank())
			.collect(Collectors.joining("\n"));
	}

	public String availableToolNames() {
		return openAiTools().stream()
			.map(PlannerToolRegistry::toolName)
			.filter(name -> !name.isBlank())
			.collect(Collectors.joining(", "));
	}

	public boolean isKnownTool(String toolName) {
		return PlannerToolCatalog.isKnownTool(toolName) || providerFor(toolName).isPresent();
	}

	public boolean isReadTool(String toolName) {
		return PlannerToolCatalog.isReadTool(toolName) || providerFor(toolName).isPresent();
	}

	public Optional<PlannerToolProvider> providerFor(String toolName) {
		String normalized = PlannerToolCatalog.normalizeName(toolName);
		return providers.stream()
			.filter(provider -> provider.handles(normalized))
			.findFirst();
	}

	public void validateProviderArguments(String toolName, JsonObject arguments) {
		providerFor(toolName)
			.orElseThrow(() -> new com.google.gson.JsonParseException("Unknown planner tool: " + toolName))
			.validateArguments(toolName, arguments);
	}

	public CompletableFuture<String> execute(PlannerToolCall toolCall) {
		return providerFor(toolCall == null ? null : toolCall.name())
			.orElseThrow(() -> new IllegalArgumentException("No provider for tool: " + (toolCall == null ? "null" : toolCall.name())))
			.execute(toolCall);
	}

	private static String toolName(Map<String, Object> tool) {
		Object function = tool == null ? null : tool.get("function");
		if (!(function instanceof Map<?, ?> functionMap)) {
			return "";
		}
		Object name = functionMap.get("name");
		return name instanceof String string ? string : "";
	}
}
