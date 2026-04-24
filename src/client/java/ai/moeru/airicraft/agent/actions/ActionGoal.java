package ai.moeru.airicraft.agent.actions;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ActionGoal(
	ActionFactType factType,
	Map<String, String> keys,
	Map<String, Integer> minimums
) {
	public ActionGoal {
		factType = Objects.requireNonNull(factType, "factType");
		keys = copyStringMap(keys);
		minimums = copyIntegerMap(minimums);
	}

	public static ActionGoal inventoryItem(String itemId, int countAtLeast) {
		return new ActionGoal(
			ActionFactType.INVENTORY_ITEM,
			Map.of("itemId", itemId),
			Map.of("countAtLeast", countAtLeast)
		);
	}

	public int minimum(String key, int fallback) {
		return minimums.getOrDefault(key, fallback);
	}

	public String normalizedKey() {
		StringBuilder builder = new StringBuilder(factType.id());
		keys.forEach((key, value) -> builder.append('|').append(key).append('=').append(value));
		minimums.forEach((key, value) -> builder.append('|').append(key).append(">=").append(value));
		return builder.toString();
	}

	private static Map<String, String> copyStringMap(Map<String, String> input) {
		if (input == null || input.isEmpty()) {
			return Map.of();
		}
		return Collections.unmodifiableMap(new LinkedHashMap<>(input));
	}

	private static Map<String, Integer> copyIntegerMap(Map<String, Integer> input) {
		if (input == null || input.isEmpty()) {
			return Map.of();
		}
		return Collections.unmodifiableMap(new LinkedHashMap<>(input));
	}
}
