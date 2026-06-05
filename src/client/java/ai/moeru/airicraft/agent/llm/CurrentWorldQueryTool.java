package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonObject;

import java.util.concurrent.CompletableFuture;

public interface CurrentWorldQueryTool {
	CompletableFuture<String> inspectWorld(JsonObject arguments);

	static CurrentWorldQueryTool disabled() {
		return arguments -> CompletableFuture.completedFuture("WORLD_UNAVAILABLE: world_query_tool_disabled");
	}
}
