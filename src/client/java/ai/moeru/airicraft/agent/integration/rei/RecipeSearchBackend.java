package ai.moeru.airicraft.agent.integration.rei;

import ai.moeru.airicraft.agent.tasks.CraftingOpportunity;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface RecipeSearchBackend {
	boolean available();

	CompletableFuture<String> search(RecipeSearchRequest request);

	default List<CraftingOpportunity> craftingOpportunities() {
		return List.of();
	}
}
