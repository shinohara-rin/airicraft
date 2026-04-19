package ai.moeru.airicraft.agent.integration.rei;

import java.util.concurrent.CompletableFuture;

public interface RecipeSearchBackend {
	boolean available();

	CompletableFuture<String> search(RecipeSearchRequest request);
}
