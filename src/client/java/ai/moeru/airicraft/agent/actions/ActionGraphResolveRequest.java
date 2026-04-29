package ai.moeru.airicraft.agent.actions;

import ai.moeru.airicraft.agent.tasks.CraftingOpportunity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ActionGraphResolveRequest(
	String itemId,
	int quantity,
	Map<String, Integer> assumedInventory,
	Map<String, Integer> observedInventory,
	List<CraftingOpportunity> availableCrafts,
	ActionResolverContext context
) {
	public ActionGraphResolveRequest(
		String itemId,
		int quantity,
		Map<String, Integer> assumedInventory,
		Map<String, Integer> observedInventory,
		ActionResolverContext context
	) {
		this(itemId, quantity, assumedInventory, observedInventory, List.of(), context);
	}

	public ActionGraphResolveRequest {
		if (itemId == null || itemId.isBlank()) {
			throw new IllegalArgumentException("itemId is required");
		}
		if (quantity < 1) {
			throw new IllegalArgumentException("quantity must be positive");
		}
		assumedInventory = copyInventory(assumedInventory);
		observedInventory = copyInventory(observedInventory);
		availableCrafts = availableCrafts == null ? List.of() : List.copyOf(availableCrafts);
		context = Objects.requireNonNull(context, "context");
	}

	private static Map<String, Integer> copyInventory(Map<String, Integer> input) {
		if (input == null || input.isEmpty()) {
			return Map.of();
		}
		LinkedHashMap<String, Integer> copy = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> entry : input.entrySet()) {
			if (entry.getKey() == null || entry.getKey().isBlank()) {
				continue;
			}
			if (entry.getValue() == null || entry.getValue() < 1) {
				continue;
			}
			copy.put(entry.getKey(), entry.getValue());
		}
		return Collections.unmodifiableMap(copy);
	}
}
