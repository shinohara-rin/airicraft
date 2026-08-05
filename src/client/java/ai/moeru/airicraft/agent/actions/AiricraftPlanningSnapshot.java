package ai.moeru.airicraft.agent.actions;

import java.util.List;
import java.util.Objects;

public record AiricraftPlanningSnapshot(
	List<ActionFact> facts,
	BlockAcquisitionIndex blockAcquisitions,
	NearbyBlockAvailability nearbyBlockAvailability,
	ActionResolverContext context
) {
	public AiricraftPlanningSnapshot {
		facts = facts == null ? List.of() : List.copyOf(facts);
		blockAcquisitions = blockAcquisitions == null ? BlockAcquisitionIndex.empty() : blockAcquisitions;
		nearbyBlockAvailability = nearbyBlockAvailability == null ? NearbyBlockAvailability.unknown() : nearbyBlockAvailability;
		context = Objects.requireNonNull(context, "context");
	}
}
