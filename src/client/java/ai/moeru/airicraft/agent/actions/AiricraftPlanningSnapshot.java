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
		ActionResolverContext safeContext = Objects.requireNonNull(context, "context");
		long currentTick = safeContext.currentTick();
		context = safeContext;
		facts = facts == null ? List.of() : facts.stream()
			.filter(fact -> fact.provenance() == ActionFactProvenance.OBSERVED
				|| fact.provenance() == ActionFactProvenance.EXECUTOR_REPORTED
				|| fact.provenance() == ActionFactProvenance.INFERRED)
			.filter(fact -> !fact.isStaleAt(currentTick))
			.toList();
		blockAcquisitions = blockAcquisitions == null ? BlockAcquisitionIndex.empty() : blockAcquisitions;
		nearbyBlockAvailability = nearbyBlockAvailability == null ? NearbyBlockAvailability.unknown() : nearbyBlockAvailability;
	}
}
