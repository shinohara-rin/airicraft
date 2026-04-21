package ai.moeru.airicraft.agent.integration.rei;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReiRecipeSearchBridgeTest {
	@AfterEach
	void resetBackend() {
		ReiRecipeSearchBridge.clearBackend();
	}

	@Test
	void defaultsToUnavailableBackend() {
		assertFalse(ReiRecipeSearchBridge.backend().available());
		assertEquals(
			"RECIPES_UNAVAILABLE: rei_runtime_unavailable",
			ReiRecipeSearchBridge.backend().search(new RecipeSearchRequest("oak", RecipeSearchMode.ALL, 12)).join()
		);
	}

	@Test
	void addonCanInstallAndClearBackend() {
		RecipeSearchBackend backend = new RecipeSearchBackend() {
			@Override
			public boolean available() {
				return true;
			}

			@Override
			public CompletableFuture<String> search(RecipeSearchRequest request) {
				return CompletableFuture.completedFuture("ok:" + request.query());
			}
		};

		ReiRecipeSearchBridge.setBackend(backend);
		assertTrue(ReiRecipeSearchBridge.backend().available());
		assertEquals(
			"ok:oak",
			ReiRecipeSearchBridge.backend().search(new RecipeSearchRequest("oak", RecipeSearchMode.ALL, 12)).join()
		);

		ReiRecipeSearchBridge.clearBackend();
		assertFalse(ReiRecipeSearchBridge.backend().available());
	}
}
