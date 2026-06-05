package ai.moeru.airicraft.agent.llm;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class CurrentWorldQueryToolProvider implements PlannerToolProvider {
	private final CurrentWorldQueryTool worldQueryTool;
	private final Consumer<CurrentWorldQueryService.WorldQueryResult> resultObserver;

	public CurrentWorldQueryToolProvider(CurrentWorldQueryService worldQueryTool) {
		this(worldQueryTool, ignored -> {
		});
	}

	public CurrentWorldQueryToolProvider(CurrentWorldQueryTool worldQueryTool) {
		this(worldQueryTool, ignored -> {
		});
	}

	public CurrentWorldQueryToolProvider(
		CurrentWorldQueryTool worldQueryTool,
		Consumer<CurrentWorldQueryService.WorldQueryResult> resultObserver
	) {
		this.worldQueryTool = Objects.requireNonNull(worldQueryTool, "worldQueryTool");
		this.resultObserver = Objects.requireNonNull(resultObserver, "resultObserver");
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
		return worldQueryTool.inspectWorldDetailed(toolCall == null ? null : toolCall.arguments())
			.thenApply(result -> {
				resultObserver.accept(result);
				return result.text();
			});
	}
}
