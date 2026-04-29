package ai.moeru.airicraft.compat.rei;

import ai.moeru.airicraft.agent.integration.rei.RecipeSearchBackend;
import ai.moeru.airicraft.agent.integration.rei.RecipeSearchMode;
import ai.moeru.airicraft.agent.integration.rei.RecipeSearchRequest;
import ai.moeru.airicraft.agent.tasks.CraftingGridKind;
import ai.moeru.airicraft.agent.tasks.CraftingOpportunity;
import ai.moeru.airicraft.agent.tasks.CraftingOpportunityResolver;
import me.shedaniel.rei.api.client.registry.category.CategoryRegistry;
import me.shedaniel.rei.api.client.registry.display.DisplayCategory;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import me.shedaniel.rei.api.client.registry.entry.EntryRegistry;
import me.shedaniel.rei.api.client.view.ViewSearchBuilder;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.display.Display;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.util.EntryStacks;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

final class ReiRuntimeRecipeSearchBackend implements RecipeSearchBackend {
	private static final int MAX_MATCHED_ITEMS = 8;
	private static final int MAX_RECIPE_VARIANTS = 24;
	private static final String CRAFTING_CATEGORY_ID = "minecraft:plugins/crafting";
	private volatile List<CraftingOpportunity> cachedCraftingOpportunities;

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

	@Override
	public List<CraftingOpportunity> craftingOpportunities() {
		List<CraftingOpportunity> cached = cachedCraftingOpportunities;
		if (cached != null) {
			return cached;
		}
		try {
			List<CraftingOpportunity> resolved = craftingOpportunitiesNow();
			if (!resolved.isEmpty()) {
				cachedCraftingOpportunities = resolved;
			}
			return resolved;
		}
		catch (RuntimeException exception) {
			return List.of();
		}
	}

	private static List<CraftingOpportunity> craftingOpportunitiesNow() {
		DisplayRegistry registry = DisplayRegistry.getInstance();
		Map<String, CraftingOpportunity> opportunities = new LinkedHashMap<>();
		for (Map.Entry<CategoryIdentifier<?>, List<Display>> entry : registry.getAll().entrySet()) {
			if (!CRAFTING_CATEGORY_ID.equals(entry.getKey().getIdentifier().toString())) {
				continue;
			}
			for (Display display : entry.getValue()) {
				if (!registry.isDisplayVisible(display)) {
					continue;
				}
				addDisplayOpportunities(display, opportunities);
			}
		}
		List<EntryStack<?>> itemStacks = EntryRegistry.getInstance().getEntryStacks()
			.filter(stack -> itemStack(stack).isPresent())
			.sorted(Comparator.comparing(ReiRuntimeRecipeSearchBackend::entryId))
			.toList();
		for (EntryStack<?> itemStack : itemStacks) {
			addDisplaysForRole(registry, "output", itemStack, opportunities);
		}
		for (EntryStack<?> itemStack : itemStacks) {
			addDisplaysForRole(registry, "input", itemStack, opportunities);
		}
		return List.copyOf(opportunities.values());
	}

	private static void addDisplaysForRole(
		DisplayRegistry registry,
		String role,
		EntryStack<?> itemStack,
		Map<String, CraftingOpportunity> opportunities
	) {
		try {
			for (Display display : displaysFor(role, itemStack)) {
				if (!isCraftingDisplay(display) || !registry.isDisplayVisible(display)) {
					continue;
				}
				addDisplayOpportunities(display, opportunities);
			}
		}
		catch (RuntimeException ignored) {
			// Some REI entries are synthetic or reload-sensitive. Skip the bad edge, not the whole recipe graph.
		}
	}

	private static void addDisplayOpportunities(Display display, Map<String, CraftingOpportunity> opportunities) {
		try {
			for (CraftingOpportunity opportunity : opportunitiesForDisplay(display)) {
				opportunities.putIfAbsent(opportunity.recipeId(), opportunity);
			}
		}
		catch (RuntimeException ignored) {
			// Ignore malformed or non-item REI displays; other displays remain usable.
		}
	}

	private static boolean isCraftingDisplay(Display display) {
		return display != null && CRAFTING_CATEGORY_ID.equals(display.getCategoryIdentifier().getIdentifier().toString());
	}

	private static List<CraftingOpportunity> opportunitiesForDisplay(Display display) {
		Optional<ReiItemStack> output = firstItemStack(display.getOutputEntries());
		if (output.isEmpty()) {
			return List.of();
		}
		List<List<String>> inputChoices = new ArrayList<>();
		for (EntryIngredient ingredient : display.getInputEntries()) {
			List<String> choices = itemStackChoices(ingredient);
			if (!choices.isEmpty()) {
				inputChoices.add(choices);
			}
		}
		if (inputChoices.isEmpty()) {
			return List.of();
		}
		CraftingGridKind gridKind = gridKind(display, inputChoices.size());
		if (gridKind == null) {
			return List.of();
		}

		Map<String, CraftingOpportunity> opportunities = new LinkedHashMap<>();
		for (List<String> inputItemIds : boundedRecipeVariants(inputChoices)) {
			CraftingOpportunity opportunity = new CraftingOpportunity(
				CraftingOpportunityResolver.recipeId(inputItemIds, output.get().itemId()),
				output.get().itemId(),
				output.get().count(),
				inputItemIds,
				gridKind
			);
			opportunities.putIfAbsent(opportunity.recipeId(), opportunity);
		}
		return List.copyOf(opportunities.values());
	}

	private static List<String> itemStackChoices(EntryIngredient ingredient) {
		if (ingredient == null || ingredient.isEmpty()) {
			return List.of();
		}
		return ingredient.stream()
			.map(ReiRuntimeRecipeSearchBackend::itemStack)
			.flatMap(Optional::stream)
			.map(ReiItemStack::itemId)
			.distinct()
			.sorted()
			.toList();
	}

	private static Optional<ReiItemStack> firstItemStack(List<EntryIngredient> ingredients) {
		if (ingredients == null) {
			return Optional.empty();
		}
		for (EntryIngredient ingredient : ingredients) {
			if (ingredient == null) {
				continue;
			}
			for (EntryStack<?> stack : ingredient) {
				Optional<ReiItemStack> itemStack = itemStack(stack);
				if (itemStack.isPresent()) {
					return itemStack;
				}
			}
		}
		return Optional.empty();
	}

	private static Optional<ReiItemStack> itemStack(EntryStack<?> stack) {
		if (stack == null) {
			return Optional.empty();
		}
		String itemId = entryId(stack);
		if (itemId.isBlank() || "unknown".equals(itemId)) {
			return Optional.empty();
		}
		int count = itemStackCount(stack).orElse(1);
		return Optional.of(new ReiItemStack(itemId, Math.max(1, count)));
	}

	private static Optional<Integer> itemStackCount(EntryStack<?> stack) {
		Object value = stack.getValue();
		if (value instanceof ItemStack itemStack && !itemStack.isEmpty()) {
			return Optional.of(itemStack.getCount());
		}
		try {
			EntryStack<ItemStack> cheatStack = stack.cheatsAs();
			if (cheatStack != null && cheatStack.getValue() instanceof ItemStack itemStack && !itemStack.isEmpty()) {
				return Optional.of(itemStack.getCount());
			}
		}
		catch (RuntimeException ignored) {
			return Optional.empty();
		}
		return Optional.empty();
	}

	private static CraftingGridKind gridKind(Display display, int inputCount) {
		if (display == null) {
			return null;
		}
		boolean shapeless = reflectiveBoolean(display, "isShapeless").orElse(false);
		if (shapeless) {
			return CraftingOpportunityResolver.gridKindForIngredientCount(inputCount);
		}
		Optional<Integer> width = reflectiveInt(display, "getWidth");
		Optional<Integer> height = reflectiveInt(display, "getHeight");
		if (width.isPresent() && height.isPresent()) {
			return CraftingOpportunityResolver.gridKindForShapedRecipe(width.get(), height.get());
		}
		return CraftingOpportunityResolver.gridKindForIngredientCount(inputCount);
	}

	private static Optional<Integer> reflectiveInt(Display display, String methodName) {
		try {
			Method method = display.getClass().getMethod(methodName);
			method.setAccessible(true);
			Object value = method.invoke(display);
			return value instanceof Number number ? Optional.of(number.intValue()) : Optional.empty();
		}
		catch (NoSuchMethodException | IllegalAccessException | ClassCastException | SecurityException exception) {
			return Optional.empty();
		}
		catch (InvocationTargetException exception) {
			return Optional.empty();
		}
	}

	private static Optional<Boolean> reflectiveBoolean(Display display, String methodName) {
		try {
			Method method = display.getClass().getMethod(methodName);
			method.setAccessible(true);
			Object value = method.invoke(display);
			return value instanceof Boolean bool ? Optional.of(bool) : Optional.empty();
		}
		catch (NoSuchMethodException | IllegalAccessException | ClassCastException | SecurityException exception) {
			return Optional.empty();
		}
		catch (InvocationTargetException exception) {
			return Optional.empty();
		}
	}

	private static List<List<String>> boundedRecipeVariants(List<List<String>> choices) {
		Map<String, Integer> availableForVariantExpansion = new HashMap<>();
		for (List<String> choiceSet : choices) {
			for (String choice : choiceSet) {
				availableForVariantExpansion.put(choice, choices.size());
			}
		}
		return CraftingOpportunityResolver.boundedCombinations(choices, availableForVariantExpansion, MAX_RECIPE_VARIANTS);
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
			if (safeRequest.mode() == RecipeSearchMode.ALL || safeRequest.mode() == RecipeSearchMode.OUTPUT) {
				searchRole("output", matchedItem, safeRequest, results);
			}
			if (results.size() < safeRequest.maxResults()
				&& (safeRequest.mode() == RecipeSearchMode.ALL || safeRequest.mode() == RecipeSearchMode.INPUT)) {
				searchRole("input", matchedItem, safeRequest, results);
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

	private static void searchRole(
		String role,
		EntryStack<?> matchedItem,
		RecipeSearchRequest request,
		Map<String, RecipeSearchResult> results
	) {
		if (results.size() >= request.maxResults()) {
			return;
		}
		DisplayRegistry displayRegistry = DisplayRegistry.getInstance();
		for (Display display : displaysFor(role, matchedItem)) {
			if (results.size() >= request.maxResults()) {
				return;
			}
			if (displayRegistry.isDisplayVisible(display)) {
				addMatch(role, display.getCategoryIdentifier(), display, matchedItem, results);
			}
		}
	}

	private static Iterable<Display> displaysFor(String role, EntryStack<?> matchedItem) {
		return cachedDisplaysFor(role, matchedItem).orElseGet(() -> viewSearchDisplaysFor(role, matchedItem));
	}

	private static Optional<Iterable<Display>> cachedDisplaysFor(String role, EntryStack<?> matchedItem) {
		try {
			Object registry = DisplayRegistry.getInstance();
			Method cacheMethod = registry.getClass().getMethod("cache");
			cacheMethod.setAccessible(true);
			Object cache = cacheMethod.invoke(registry);
			String methodName = switch (role) {
				case "output" -> "getAllDisplaysByOutputs";
				case "input" -> "getAllDisplaysByInputs";
				default -> throw new IllegalArgumentException("unsupported REI display role: " + role);
			};
			Method lookupMethod = cache.getClass().getMethod(methodName, List.class);
			lookupMethod.setAccessible(true);
			Object displays = lookupMethod.invoke(cache, List.of(matchedItem));
			if (!(displays instanceof Iterable<?> iterable)) {
				return Optional.empty();
			}
			@SuppressWarnings("unchecked")
			Iterable<Display> typedDisplays = (Iterable<Display>) iterable;
			return Optional.of(typedDisplays);
		}
		catch (NoSuchMethodException | IllegalAccessException | ClassCastException | SecurityException exception) {
			return Optional.empty();
		}
		catch (InvocationTargetException exception) {
			throw new IllegalStateException("rei_cache_lookup_failed", exception.getCause());
		}
	}

	private static Iterable<Display> viewSearchDisplaysFor(String role, EntryStack<?> matchedItem) {
		ViewSearchBuilder builder = ViewSearchBuilder.builder().mergingDisplays(false);
		switch (role) {
			case "output" -> builder.addRecipesFor(matchedItem);
			case "input" -> builder.addUsagesFor(matchedItem);
			default -> throw new IllegalArgumentException("unsupported REI display role: " + role);
		}
		return builder.streamDisplays()
			.map(displaySpec -> displaySpec.provideInternalDisplay())
			.toList();
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

	private record ReiItemStack(String itemId, int count) {
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
