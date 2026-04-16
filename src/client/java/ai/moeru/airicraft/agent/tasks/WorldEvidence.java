package ai.moeru.airicraft.agent.tasks;

import java.util.Map;
import java.util.List;

public record WorldEvidence(
	Map<TaskResourceKind, Integer> inventoryCounts,
	Map<String, Integer> itemCounts,
	Map<String, Integer> nearbyBlocks,
	List<CraftingOpportunity> availableCrafts,
	String dimension,
	int x,
	int y,
	int z,
	String equippedItemId,
	long tick
) {
	public WorldEvidence {
		inventoryCounts = inventoryCounts == null ? Map.of() : Map.copyOf(inventoryCounts);
		itemCounts = itemCounts == null ? Map.of() : Map.copyOf(itemCounts);
		nearbyBlocks = nearbyBlocks == null ? Map.of() : Map.copyOf(nearbyBlocks);
		availableCrafts = availableCrafts == null ? List.of() : List.copyOf(availableCrafts);
	}

	public WorldEvidence(
		Map<TaskResourceKind, Integer> inventoryCounts,
		Map<String, Integer> itemCounts,
		Map<String, Integer> nearbyBlocks,
		String dimension,
		int x,
		int y,
		int z,
		String equippedItemId,
		long tick
	) {
		this(inventoryCounts, itemCounts, nearbyBlocks, List.of(), dimension, x, y, z, equippedItemId, tick);
	}

	public WorldEvidence(
		Map<TaskResourceKind, Integer> inventoryCounts,
		Map<String, Integer> nearbyBlocks,
		String dimension,
		int x,
		int y,
		int z,
		String equippedItemId,
		long tick
	) {
		this(inventoryCounts, Map.of(), nearbyBlocks, List.of(), dimension, x, y, z, equippedItemId, tick);
	}
}
