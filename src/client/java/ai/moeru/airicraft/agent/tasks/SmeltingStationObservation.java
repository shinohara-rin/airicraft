package ai.moeru.airicraft.agent.tasks;

import java.util.Objects;

public record SmeltingStationObservation(
	SmeltingStationKey key,
	SmeltingStationKind kind,
	SmeltingSlotSnapshot slots,
	boolean openScreen,
	double distance
) {
	public SmeltingStationObservation {
		kind = Objects.requireNonNull(kind, "kind");
		slots = Objects.requireNonNull(slots, "slots");
		distance = Math.max(0.0D, distance);
	}
}
