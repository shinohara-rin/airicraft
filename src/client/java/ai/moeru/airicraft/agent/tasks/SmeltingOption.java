package ai.moeru.airicraft.agent.tasks;

import java.util.Objects;

public record SmeltingOption(
	String optionId,
	String inputItemId,
	String outputItemId,
	int outputCount,
	int maxInputQuantity,
	int cookTimeTicks,
	SmeltingStationCandidate stationCandidate,
	SmeltingStationObservation stationObservation
) {
	public SmeltingOption {
		optionId = optionId == null ? null : optionId.trim();
		inputItemId = inputItemId == null ? null : inputItemId.trim();
		outputItemId = outputItemId == null ? null : outputItemId.trim();
		if (optionId == null || optionId.isBlank()) {
			throw new IllegalArgumentException("optionId must not be blank");
		}
		if (inputItemId == null || inputItemId.isBlank()) {
			throw new IllegalArgumentException("inputItemId must not be blank");
		}
		if (outputItemId == null || outputItemId.isBlank()) {
			throw new IllegalArgumentException("outputItemId must not be blank");
		}
		outputCount = Math.max(1, outputCount);
		maxInputQuantity = Math.max(1, maxInputQuantity);
		cookTimeTicks = Math.max(1, cookTimeTicks);
		stationCandidate = Objects.requireNonNull(stationCandidate, "stationCandidate");
		stationObservation = Objects.requireNonNull(stationObservation, "stationObservation");
	}
}
