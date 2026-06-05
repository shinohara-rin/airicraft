package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface CurrentWorldQueryTool {
	CompletableFuture<String> inspectWorld(JsonObject arguments);

	default CompletableFuture<CurrentWorldQueryService.WorldQueryResult> inspectWorldDetailed(JsonObject arguments) {
		return inspectWorld(arguments).thenApply(text -> new CurrentWorldQueryService.WorldQueryResult(text, List.of()));
	}

	static CurrentWorldQueryTool disabled() {
		return arguments -> CompletableFuture.completedFuture("WORLD_UNAVAILABLE: world_query_tool_disabled");
	}
}
