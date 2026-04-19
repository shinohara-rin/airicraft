package ai.moeru.airicraft.agent.integration.rei;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public final class ReiRecipeSearchBridge {
	private static final RecipeSearchBackend UNAVAILABLE = new RecipeSearchBackend() {
		@Override
		public boolean available() {
			return false;
		}

		@Override
		public CompletableFuture<String> search(RecipeSearchRequest request) {
			return CompletableFuture.completedFuture("RECIPES_UNAVAILABLE: rei_runtime_unavailable");
		}
	};

	private static final AtomicReference<RecipeSearchBackend> BACKEND = new AtomicReference<>(UNAVAILABLE);

	private ReiRecipeSearchBridge() {
	}

	public static RecipeSearchBackend backend() {
		return BACKEND.get();
	}

	public static void setBackend(RecipeSearchBackend backend) {
		BACKEND.set(backend == null ? UNAVAILABLE : backend);
	}

	public static void clearBackend() {
		BACKEND.set(UNAVAILABLE);
	}
}
