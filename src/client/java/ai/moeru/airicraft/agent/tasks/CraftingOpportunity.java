package ai.moeru.airicraft.agent.tasks;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

public record CraftingOpportunity(
	String recipeId,
	String outputItemId,
	int outputCount,
	List<String> inputItemIds
) {
	public CraftingOpportunity {
		if (recipeId == null || recipeId.isBlank()) {
			throw new IllegalArgumentException("recipeId must not be blank");
		}
		if (outputItemId == null || outputItemId.isBlank()) {
			throw new IllegalArgumentException("outputItemId must not be blank");
		}
		if (outputCount <= 0) {
			throw new IllegalArgumentException("outputCount must be positive");
		}
		if (inputItemIds == null || inputItemIds.isEmpty()) {
			throw new IllegalArgumentException("inputItemIds must not be empty");
		}
		for (String inputItemId : inputItemIds) {
			if (inputItemId == null || inputItemId.isBlank()) {
				throw new IllegalArgumentException("inputItemIds must not contain blank ids");
			}
		}
		inputItemIds = List.copyOf(inputItemIds);
	}

	public String compactDescription() {
		return "[From {" + formatInputSummary(inputItemIds) + "} to " + outputCount + "*" + displayItemId(outputItemId) + "]: " + recipeId;
	}

	static String displayItemId(String itemId) {
		if (itemId == null) {
			return "";
		}
		return itemId.startsWith("minecraft:") ? itemId.substring("minecraft:".length()) : itemId;
	}

	static String recipeIdSegment(String itemId) {
		String display = displayItemId(itemId).toLowerCase(java.util.Locale.ROOT);
		return display.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
	}

	private static String formatInputSummary(List<String> inputItemIds) {
		Map<String, Integer> counts = new TreeMap<>();
		for (String itemId : inputItemIds) {
			counts.merge(itemId, 1, Integer::sum);
		}
		return counts.entrySet().stream()
			.map(entry -> entry.getValue() + "*" + displayItemId(entry.getKey()))
			.collect(Collectors.joining(","));
	}
}
