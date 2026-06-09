package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface WorldFeatureSearchTool {
	CompletableFuture<WorldFeatureSearchService.WorldFeatureSearchResult> findFeaturesDetailed(JsonObject arguments);

	default CompletableFuture<String> findFeatures(JsonObject arguments) {
		return findFeaturesDetailed(arguments).thenApply(WorldFeatureSearchService.WorldFeatureSearchResult::text);
	}

	static WorldFeatureSearchTool textOnly(java.util.function.Function<JsonObject, String> function) {
		return arguments -> CompletableFuture.completedFuture(new WorldFeatureSearchService.WorldFeatureSearchResult(function.apply(arguments), List.of()));
	}
}
