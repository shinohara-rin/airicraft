package ai.moeru.airicraft.agent.tasks;

import java.util.Objects;

public record SmeltingStationCandidate(
	SmeltingStationSource source,
	SmeltingStationState state,
	SmeltingStationKind kind,
	SmeltingStationKey key,
	double distance,
	boolean confirmationRequired
) {
	public SmeltingStationCandidate {
		source = Objects.requireNonNull(source, "source");
		state = Objects.requireNonNull(state, "state");
		kind = Objects.requireNonNull(kind, "kind");
		distance = Math.max(0.0D, distance);
	}
}
