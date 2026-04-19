package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerToolRegistryTest {
	@Test
	void routesProviderToolAfterAvailabilityFlipsUnavailable() {
		FlippingProvider provider = new FlippingProvider();
		PlannerToolRegistry registry = PlannerToolRegistry.of(provider);

		assertTrue(toolNames(registry.openAiTools()).contains("search_recipes"));

		provider.available.set(false);
		JsonObject arguments = new JsonObject();
		arguments.addProperty("query", "oak planks");

		assertTrue(registry.isKnownTool("search_recipes"));
		assertTrue(registry.isReadTool("search_recipes"));
		assertDoesNotThrow(() -> registry.validateProviderArguments("search_recipes", arguments));
		assertEquals("RECIPES_UNAVAILABLE: provider_reloading", registry.execute(new PlannerToolCall(
			"call_search",
			"search_recipes",
			arguments,
			null,
			null
		)).join());
	}

	private static List<String> toolNames(List<Map<String, Object>> tools) {
		return tools.stream()
			.map(PlannerToolRegistryTest::toolName)
			.toList();
	}

	private static String toolName(Map<String, Object> tool) {
		@SuppressWarnings("unchecked")
		Map<String, Object> function = (Map<String, Object>) tool.get("function");
		return (String) function.get("name");
	}

	private static final class FlippingProvider implements PlannerToolProvider {
		private final AtomicBoolean available = new AtomicBoolean(true);

		@Override
		public String id() {
			return "flipping_recipe_search";
		}

		@Override
		public boolean available() {
			return available.get();
		}

		@Override
		public List<Map<String, Object>> openAiTools() {
			return List.of(PlannerToolCatalog.toolForProvider(
				"search_recipes",
				"Search recipes through a provider.",
				PlannerToolCatalog.propertiesForProvider(
					PlannerToolCatalog.propForProvider("query", PlannerToolCatalog.stringForProvider("Item query."))
				),
				List.of("query")
			));
		}

		@Override
		public boolean handles(String toolName) {
			return "search_recipes".equals(PlannerToolCatalog.normalizeName(toolName));
		}

		@Override
		public void validateArguments(String toolName, JsonObject arguments) {
			if (arguments == null || !arguments.has("query")) {
				throw new com.google.gson.JsonParseException("missing query");
			}
		}

		@Override
		public CompletableFuture<String> execute(PlannerToolCall toolCall) {
			if (!available()) {
				return CompletableFuture.completedFuture("RECIPES_UNAVAILABLE: provider_reloading");
			}
			return CompletableFuture.completedFuture("Tool result for search_recipes: ok");
		}
	}
}
