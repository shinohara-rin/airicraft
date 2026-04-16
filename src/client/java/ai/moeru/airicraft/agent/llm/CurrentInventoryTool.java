package ai.moeru.airicraft.agent.llm;

import java.util.concurrent.CompletableFuture;

public interface CurrentInventoryTool {
	CompletableFuture<String> inspectInventory(String prompt);

	CompletableFuture<String> inspectRecipes(String prompt);

	static CurrentInventoryTool disabled() {
		return new CurrentInventoryTool() {
			@Override
			public CompletableFuture<String> inspectInventory(String prompt) {
				return CompletableFuture.completedFuture("INVENTORY_UNAVAILABLE: inventory_tool_disabled");
			}

			@Override
			public CompletableFuture<String> inspectRecipes(String prompt) {
				return CompletableFuture.completedFuture("RECIPES_UNAVAILABLE: inventory_tool_disabled");
			}
		};
	}
}
