package ai.moeru.airicraft.agent.actions;

import ai.moeru.airicraft.agent.tasks.CraftingOpportunity;
import ai.moeru.airicraft.agent.tasks.TaskTerminalEvent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ActionGraphExecutionInput(
	ActionResolverContext context,
	Map<String, Integer> observedInventory,
	boolean worldLoaded,
	boolean actuationAllowed,
	TaskTerminalEvent terminalTaskEvent,
	List<CraftingOpportunity> availableCrafts
) {
	public ActionGraphExecutionInput(
		ActionResolverContext context,
		Map<String, Integer> observedInventory,
		boolean worldLoaded,
		boolean actuationAllowed,
		TaskTerminalEvent terminalTaskEvent
	) {
		this(context, observedInventory, worldLoaded, actuationAllowed, terminalTaskEvent, List.of());
	}

	public ActionGraphExecutionInput {
		context = Objects.requireNonNull(context, "context");
		observedInventory = copyInventory(observedInventory);
		availableCrafts = availableCrafts == null ? List.of() : List.copyOf(availableCrafts);
	}

	private static Map<String, Integer> copyInventory(Map<String, Integer> inventory) {
		if (inventory == null || inventory.isEmpty()) {
			return Map.of();
		}
		LinkedHashMap<String, Integer> copy = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> entry : inventory.entrySet()) {
			if (entry.getKey() != null && !entry.getKey().isBlank() && entry.getValue() != null) {
				copy.put(entry.getKey(), Math.max(0, entry.getValue()));
			}
		}
		return Collections.unmodifiableMap(copy);
	}
}
