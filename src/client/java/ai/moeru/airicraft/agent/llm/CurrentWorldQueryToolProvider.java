package ai.moeru.airicraft.agent.llm;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class CurrentWorldQueryToolProvider implements PlannerToolProvider {
	private final CurrentWorldQueryTool worldQueryTool;

	public CurrentWorldQueryToolProvider(CurrentWorldQueryTool worldQueryTool) {
		this.worldQueryTool = Objects.requireNonNull(worldQueryTool, "worldQueryTool");
	}

	@Override
	public String id() {
		return "world_query";
	}

	@Override
	public List<Map<String, Object>> openAiTools() {
		return List.of();
	}

	@Override
	public boolean handles(String toolName) {
		return PlannerToolCatalog.INSPECT_WORLD.equals(PlannerToolCatalog.normalizeName(toolName));
	}

	@Override
	public CompletableFuture<String> execute(PlannerToolCall toolCall) {
		return worldQueryTool.inspectWorld(toolCall == null ? null : toolCall.arguments());
	}
}
