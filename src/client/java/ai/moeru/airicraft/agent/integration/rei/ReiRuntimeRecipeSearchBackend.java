package ai.moeru.airicraft.agent.integration.rei;

import me.shedaniel.rei.api.client.registry.category.CategoryRegistry;
import me.shedaniel.rei.api.client.registry.display.DisplayCategory;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import me.shedaniel.rei.api.client.registry.entry.EntryRegistry;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.display.Display;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.util.EntryStacks;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

final class ReiRuntimeRecipeSearchBackend implements RecipeSearchBackend {
	private static final int MAX_MATCHED_ITEMS = 8;

	@Override
	public boolean available() {
		return true;
	}

	@Override
	public CompletableFuture<String> search(RecipeSearchRequest request) {
		try {
			return CompletableFuture.completedFuture(searchNow(request));
		}
		catch (RuntimeException exception) {
			return CompletableFuture.completedFuture("RECIPES_UNAVAILABLE: rei_search_failed " + exception.getClass().getSimpleName());
		}
	}

	private String searchNow(RecipeSearchRequest request) {
		RecipeSearchRequest safeRequest = request == null
			? new RecipeSearchRequest("", RecipeSearchMode.ALL, 12)
			: request;
		if (safeRequest.query().isBlank()) {
			return "RECIPES_UNAVAILABLE: missing_query";
		}

		List<EntryStack<?>> matchedItems = matchedItems(safeRequest.query()).stream()
			.limit(MAX_MATCHED_ITEMS)
			.toList();
		if (matchedItems.isEmpty()) {
			return "Tool result for search_recipes: provider=rei, query=\"" + sanitize(safeRequest.query())
				+ "\", mode=" + safeRequest.mode().toolValue()
				+ ", matchedItems=[], results=[], note=No REI item ingredients matched the query. Try a namespaced item id or a more exact item name.";
		}

		LinkedHashMap<String, RecipeSearchResult> results = new LinkedHashMap<>();
		for (EntryStack<?> matchedItem : matchedItems) {
			for (Map.Entry<CategoryIdentifier<?>, List<Display>> categoryDisplays : DisplayRegistry.getInstance().getAll().entrySet()) {
				if (results.size() >= safeRequest.maxResults()) {
					break;
				}
				searchCategory(categoryDisplays.getKey(), categoryDisplays.getValue(), matchedItem, safeRequest, results);
			}
			if (results.size() >= safeRequest.maxResults()) {
				break;
			}
		}

		return "Tool result for search_recipes: provider=rei"
			+ ", query=\"" + sanitize(safeRequest.query()) + "\""
			+ ", mode=" + safeRequest.mode().toolValue()
			+ ", matchedItems=" + matchedItems.stream().map(ReiRuntimeRecipeSearchBackend::entryId).toList()
			+ ", results=" + results.values().stream().map(RecipeSearchResult::compact).toList()
			+ ", note=REI results are recipe-viewer knowledge, not current craftability. Use check_craftables before craft_recipe.";
	}

	private static List<EntryStack<?>> matchedItems(String query) {
		String normalizedQuery = normalize(query);
		return EntryRegistry.getInstance().getEntryStacks()
			.filter(stack -> entryMatches(stack, normalizedQuery))
			.sorted(Comparator.comparing(ReiRuntimeRecipeSearchBackend::entryId))
			.toList();
	}

	private static boolean entryMatches(EntryStack<?> stack, String normalizedQuery) {
		return normalize(entryId(stack)).contains(normalizedQuery)
			|| normalize(entryName(stack)).contains(normalizedQuery);
	}

	private static void searchCategory(
		CategoryIdentifier<?> categoryId,
		List<Display> displays,
		EntryStack<?> matchedItem,
		RecipeSearchRequest request,
		Map<String, RecipeSearchResult> results
	) {
		for (Display display : displays) {
			if (results.size() >= request.maxResults()) {
				return;
			}
			if (request.mode() == RecipeSearchMode.ALL || request.mode() == RecipeSearchMode.OUTPUT) {
				addMatch("output", categoryId, display, matchedItem, results);
			}
			if (request.mode() == RecipeSearchMode.ALL || request.mode() == RecipeSearchMode.INPUT) {
				addMatch("input", categoryId, display, matchedItem, results);
			}
		}
	}

	private static void addMatch(
		String role,
		CategoryIdentifier<?> categoryId,
		Display display,
		EntryStack<?> matchedItem,
		Map<String, RecipeSearchResult> results
	) {
		boolean matches = switch (role) {
			case "output" -> entriesMatch(display.getOutputEntries(), matchedItem);
			case "input" -> entriesMatch(display.getInputEntries(), matchedItem);
			default -> false;
		};
		if (!matches) {
			return;
		}
		String recipeId = display.getDisplayLocation().map(Identifier::toString).orElse("unknown");
		String key = role + "|" + categoryId.getIdentifier() + "|" + recipeId + "|" + entryId(matchedItem);
		results.putIfAbsent(key, new RecipeSearchResult(
			role,
			entryId(matchedItem),
			categoryId.getIdentifier().toString(),
			categoryTitle(categoryId),
			recipeId
		));
	}

	private static boolean entriesMatch(List<EntryIngredient> entries, EntryStack<?> matchedItem) {
		for (EntryIngredient ingredient : entries) {
			for (EntryStack<?> candidate : ingredient) {
				if (EntryStacks.equalsFuzzy(candidate, matchedItem)) {
					return true;
				}
			}
		}
		return false;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static String categoryTitle(CategoryIdentifier<?> categoryId) {
		Optional<CategoryRegistry.CategoryConfiguration<Display>> category = CategoryRegistry.getInstance().tryGet((CategoryIdentifier) categoryId);
		return category
			.map(CategoryRegistry.CategoryConfiguration::getCategory)
			.map(DisplayCategory::getTitle)
			.map(ReiRuntimeRecipeSearchBackend::title)
			.orElse(categoryId.getIdentifier().toString());
	}

	private static String entryId(EntryStack<?> stack) {
		return Optional.ofNullable(stack.getIdentifier())
			.map(Identifier::toString)
			.orElse("unknown");
	}

	private static String entryName(EntryStack<?> stack) {
		return sanitize(stack.asFormatStrippedText().getString());
	}

	private static String normalize(String text) {
		return text == null ? "" : text.trim().toLowerCase(Locale.ROOT).replace('_', ' ');
	}

	private static String sanitize(String text) {
		return text == null ? "" : text.replace('\n', ' ').replace('\r', ' ').strip();
	}

	private static String title(Text text) {
		return sanitize(text == null ? "" : text.getString());
	}

	private record RecipeSearchResult(
		String role,
		String matchedItem,
		String recipeType,
		String category,
		String recipeId
	) {
		private String compact() {
			return "{role=" + role
				+ ", matchedItem=" + matchedItem
				+ ", recipeType=" + recipeType
				+ ", category=\"" + category + "\""
				+ ", recipeId=" + recipeId
				+ "}";
		}
	}
}
