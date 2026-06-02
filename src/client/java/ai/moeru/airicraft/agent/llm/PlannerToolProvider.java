package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface PlannerToolProvider {
	String id();

	default boolean available() {
		return true;
	}

	List<Map<String, Object>> openAiTools();

	default String promptInstructions() {
		return "";
	}

	boolean handles(String toolName);

	default boolean isReadTool(String toolName) {
		return true;
	}

	default void validateArguments(String toolName, JsonObject arguments) {
	}

	CompletableFuture<String> execute(PlannerToolCall toolCall);

	default CompletableFuture<PlannerProviderToolResult> executeResult(PlannerToolCall toolCall) {
		return execute(toolCall).thenApply(PlannerProviderToolResult::text);
	}
}
