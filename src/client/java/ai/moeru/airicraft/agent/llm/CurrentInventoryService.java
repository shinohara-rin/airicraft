package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.tasks.CraftingOpportunity;
import ai.moeru.airicraft.agent.tasks.CraftingOpportunityResolver;
import ai.moeru.airicraft.agent.tasks.CraftingGridKind;
import ai.moeru.airicraft.agent.tasks.InventoryItemCounter;
import ai.moeru.airicraft.agent.tasks.InventoryResourceCounter;
import ai.moeru.airicraft.agent.tasks.TaskResourceKind;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

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
	public CompletableFuture<String> checkCraftables(String prompt) {
		MinecraftClient client = clientSupplier.get();
		if (client == null || client.world == null || client.player == null) {
			return CompletableFuture.completedFuture("CRAFTABLES_UNAVAILABLE: world_not_loaded");
		}

		List<CraftingOpportunity> opportunities = CraftingOpportunityResolver.availableCrafts(client.player);
		Map<String, Integer> itemCounts = itemCounter.count(client.player.getInventory());
		CraftingTableAccess tableAccess = craftingTableAccess(client, itemCounts);
		List<CraftingOpportunity> craftableNow = opportunities.stream()
			.filter(opportunity -> opportunity.gridKind() == CraftingGridKind.PLAYER_2X2 || tableAccess == CraftingTableAccess.OPEN)
			.limit(MAX_RECIPE_RESULTS)
			.toList();
		List<CraftingOpportunity> craftableWithSetup = opportunities.stream()
			.filter(opportunity -> opportunity.gridKind() == CraftingGridKind.WORKBENCH_3X3 && tableAccess != CraftingTableAccess.OPEN && tableAccess != CraftingTableAccess.MISSING)
			.limit(MAX_RECIPE_RESULTS)
			.toList();
		List<CraftingOpportunity> blockedByTable = opportunities.stream()
			.filter(opportunity -> opportunity.gridKind() == CraftingGridKind.WORKBENCH_3X3 && tableAccess == CraftingTableAccess.MISSING)
			.limit(MAX_RECIPE_RESULTS)
			.toList();
		List<CraftingOpportunity> executable = new ArrayList<>();
		executable.addAll(craftableNow);
		executable.addAll(craftableWithSetup);
		String exactItemIds = executable.isEmpty()
			? "[]"
			: executable.stream()
				.map(CraftingOpportunity::recipeId)
				.collect(Collectors.joining(", ", "[", "]"));
		return CompletableFuture.completedFuture(
			"Tool result for check_craftables: "
				+ "craftableNow=" + formatCrafts(craftableNow)
				+ ", craftableWithSetup=" + formatCrafts(craftableWithSetup)
				+ ", blocked=" + formatCrafts(blockedByTable)
				+ ", exactRecipeIds=" + exactItemIds
				+ ", craftingTableAccess=" + tableAccess.name().toLowerCase(java.util.Locale.ROOT)
				+ ", note=Use exactRecipeIds for CRAFT_RECIPE.recipeId. times means recipe runs, not output item count. 3x3 workbench recipes can automatically navigate to a nearby crafting table within 10 blocks, place one from inventory, or craft one from planks."
		);
	}

	private static String formatCrafts(List<CraftingOpportunity> opportunities) {
		if (opportunities == null || opportunities.isEmpty()) {
			return "none";
		}
		return opportunities.stream()
			.map(CraftingOpportunity::compactDescription)
			.collect(Collectors.joining("; ", "[", "]"));
	}

	private static CraftingTableAccess craftingTableAccess(MinecraftClient client, Map<String, Integer> itemCounts) {
		if (client.player.currentScreenHandler instanceof net.minecraft.screen.CraftingScreenHandler) {
			return CraftingTableAccess.OPEN;
		}
		if (hasNearbyCraftingTable(client)) {
			return CraftingTableAccess.NEARBY;
		}
		if (itemCounts.getOrDefault(Registries.ITEM.getId(Items.CRAFTING_TABLE).toString(), 0) > 0) {
			return CraftingTableAccess.IN_INVENTORY;
		}
		if (plankCount(itemCounts) >= 4) {
			return CraftingTableAccess.CAN_CRAFT;
		}
		return CraftingTableAccess.MISSING;
	}

	private static boolean hasNearbyCraftingTable(MinecraftClient client) {
		BlockPos origin = client.player.getBlockPos();
		for (int dx = -10; dx <= 10; dx++) {
			for (int dy = -4; dy <= 4; dy++) {
				for (int dz = -10; dz <= 10; dz++) {
					BlockPos pos = origin.add(dx, dy, dz);
					if (origin.getSquaredDistance(pos) > 100.0D || !client.world.isChunkLoaded(pos)) {
						continue;
					}
					if (client.world.getBlockState(pos).isOf(Blocks.CRAFTING_TABLE)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static int plankCount(Map<String, Integer> itemCounts) {
		int count = 0;
		for (Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
			if (entry.getKey().endsWith("_planks")) {
				count += entry.getValue();
			}
		}
		return count;
	}

	private enum CraftingTableAccess {
		OPEN,
		NEARBY,
		IN_INVENTORY,
		CAN_CRAFT,
		MISSING
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
