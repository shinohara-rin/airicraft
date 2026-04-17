package ai.moeru.airicraft.agent.llm;

import java.util.concurrent.CompletableFuture;

public interface PlannerActionToolExecutor {
	PlannerActionToolExecutor DISABLED = toolCall ->
		CompletableFuture.completedFuture("TOOL_UNAVAILABLE: action_tool_unavailable");

	CompletableFuture<String> execute(PlannerToolCall toolCall);
}
