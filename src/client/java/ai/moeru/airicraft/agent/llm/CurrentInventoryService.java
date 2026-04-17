package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.tasks.CraftingOpportunity;
import ai.moeru.airicraft.agent.tasks.CraftingOpportunityResolver;
import ai.moeru.airicraft.agent.tasks.InventoryItemCounter;
import ai.moeru.airicraft.agent.tasks.InventoryResourceCounter;
import ai.moeru.airicraft.agent.tasks.TaskResourceKind;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class CurrentInventoryService implements CurrentInventoryTool {
	private static final int MAX_RECIPE_RESULTS = 24;

	private final Supplier<MinecraftClient> clientSupplier;
	private final InventoryResourceCounter resourceCounter = new InventoryResourceCounter();
	private final InventoryItemCounter itemCounter = new InventoryItemCounter();

	public CurrentInventoryService(Supplier<MinecraftClient> clientSupplier) {
		this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
	}

	@Override
	public CompletableFuture<String> inspectInventory(String prompt) {
		MinecraftClient client = clientSupplier.get();
		if (client == null || client.world == null || client.player == null) {
			return CompletableFuture.completedFuture("INVENTORY_UNAVAILABLE: world_not_loaded");
		}

		List<ItemStack> stacks = new ArrayList<>();
		for (int slot = 0; slot < client.player.getInventory().size(); slot++) {
			stacks.add(client.player.getInventory().getStack(slot));
		}

		EnumMap<TaskResourceKind, Integer> resourceCounts = new EnumMap<>(TaskResourceKind.class);
		for (TaskResourceKind kind : TaskResourceKind.values()) {
			int count = resourceCounter.count(stacks, kind);
			if (count > 0) {
				resourceCounts.put(kind, count);
			}
		}

		String dimension = client.world.getRegistryKey().getValue().toString();
		String position = client.player.getBlockPos().getX() + "," + client.player.getBlockPos().getY() + "," + client.player.getBlockPos().getZ();
		String equippedItemId = Registries.ITEM.getId(client.player.getMainHandStack().getItem()).toString();
		return CompletableFuture.completedFuture(
			"Tool result for inspect_inventory: "
				+ "dimension=" + dimension
				+ ", position=" + position
				+ ", equippedItemId=" + equippedItemId
				+ ", inventoryCounts=" + resourceCounts
				+ ", itemCounts=" + sortedItemCounts(itemCounter.count(client.player.getInventory()))
		);
	}

	@Override
	public CompletableFuture<String> inspectRecipes(String prompt) {
		MinecraftClient client = clientSupplier.get();
		if (client == null || client.world == null || client.player == null) {
			return CompletableFuture.completedFuture("RECIPES_UNAVAILABLE: world_not_loaded");
		}

		List<CraftingOpportunity> opportunities = CraftingOpportunityResolver.availableCrafts(client.player);
		String recipes = opportunities.isEmpty()
			? "none"
			: opportunities.stream()
				.limit(MAX_RECIPE_RESULTS)
				.map(CraftingOpportunity::compactDescription)
				.collect(Collectors.joining("; ", "Available 2x2 crafts: ", ""));
		String exactItemIds = opportunities.isEmpty()
			? "[]"
			: opportunities.stream()
				.limit(MAX_RECIPE_RESULTS)
				.map(CraftingOpportunity::recipeId)
				.collect(Collectors.joining(", ", "[", "]"));
		return CompletableFuture.completedFuture(
			"Tool result for inspect_recipes: "
				+ "availableCrafts=" + recipes
				+ ", exactRecipeIds=" + exactItemIds
				+ ", note=Use exactRecipeIds for CRAFT_RECIPE.recipeId. times means recipe runs, not output item count. Only 2x2 player-inventory recipes are supported; 3x3 workbench-grid recipes are not supported yet. The item crafting_table is supported when listed."
		);
	}

	private static Map<String, Integer> sortedItemCounts(Map<String, Integer> counts) {
		if (counts == null || counts.isEmpty()) {
			return Map.of();
		}
		return counts.entrySet().stream()
			.sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
			.collect(Collectors.toMap(
				Map.Entry::getKey,
				Map.Entry::getValue,
				(left, right) -> left,
				LinkedHashMap::new
			));
	}
}
