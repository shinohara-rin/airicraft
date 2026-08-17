package ai.moeru.airicraft.agent.actions;

import ai.moeru.airicraft.agent.tasks.CraftingOpportunity;
import ai.moeru.airicraft.agent.tasks.SmeltingOption;
import ai.moeru.airicraft.agent.tasks.SmeltingRecipeKnowledge;
import ai.moeru.airicraft.agent.tasks.TaskTerminalEvent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ActionGraphExecutionInput(
	ActionResolverContext context,
	Map<String, Integer> observedInventory,
	Map<String, Integer> observedResources,
	boolean worldLoaded,
	boolean actuationAllowed,
	TaskTerminalEvent terminalTaskEvent,
	List<CraftingOpportunity> availableCrafts,
	List<CraftingOpportunity> knownCrafts,
	List<SmeltingOption> availableSmelts,
	List<SmeltingRecipeKnowledge> knownSmelts,
	List<ActionFact> observedFacts,
	ActionGraphAgentPosition agentPosition,
	Map<String, ActionWatchProgressObservation> watchProgress,
	BlockAcquisitionIndex blockAcquisitions,
	NearbyBlockAvailability nearbyBlockAvailability
) {
	public ActionGraphExecutionInput {
		context = Objects.requireNonNull(context, "context");
		observedInventory = copyInventory(observedInventory);
		observedResources = copyInventory(observedResources);
		availableCrafts = availableCrafts == null ? List.of() : List.copyOf(availableCrafts);
		knownCrafts = knownCrafts == null ? List.of() : List.copyOf(knownCrafts);
		availableSmelts = availableSmelts == null ? List.of() : List.copyOf(availableSmelts);
		knownSmelts = knownSmelts == null ? List.of() : List.copyOf(knownSmelts);
		observedFacts = observedFacts == null ? List.of() : List.copyOf(observedFacts);
		blockAcquisitions = blockAcquisitions == null ? BlockAcquisitionIndex.empty() : blockAcquisitions;
		nearbyBlockAvailability = nearbyBlockAvailability == null ? NearbyBlockAvailability.unknown() : nearbyBlockAvailability;
		watchProgress = watchProgress == null || watchProgress.isEmpty()
			? Map.of()
			: Collections.unmodifiableMap(new LinkedHashMap<>(watchProgress));
	}

	public ActionGraphExecutionInput withoutTerminalTaskEvent() {
		if (terminalTaskEvent == null) {
			return this;
		}
		return new ActionGraphExecutionInput(
			context,
			observedInventory,
			observedResources,
			worldLoaded,
			actuationAllowed,
			null,
			availableCrafts,
			knownCrafts,
			availableSmelts,
			knownSmelts,
			observedFacts,
			agentPosition,
			watchProgress,
			blockAcquisitions,
			nearbyBlockAvailability
		);
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
