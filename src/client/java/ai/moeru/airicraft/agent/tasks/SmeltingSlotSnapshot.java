package ai.moeru.airicraft.agent.tasks;

import java.util.Objects;

public record SmeltingSlotSnapshot(
	String inputItemId,
	int inputCount,
	String fuelItemId,
	int fuelCount,
	String outputItemId,
	int outputCount,
	int cookTime,
	int cookTimeTotal,
	boolean burning
) {
	public SmeltingSlotSnapshot {
		inputItemId = normalizeItemId(inputItemId);
		fuelItemId = normalizeItemId(fuelItemId);
		outputItemId = normalizeItemId(outputItemId);
		inputCount = Math.max(0, inputCount);
		fuelCount = Math.max(0, fuelCount);
		outputCount = Math.max(0, outputCount);
		cookTime = Math.max(0, cookTime);
		cookTimeTotal = Math.max(0, cookTimeTotal);
	}

	public boolean empty() {
		return inputCount <= 0 && fuelCount <= 0 && outputCount <= 0;
	}

	public String fingerprint() {
		return String.join("|",
			Objects.toString(inputItemId, ""),
			Integer.toString(inputCount),
			Objects.toString(fuelItemId, ""),
			Integer.toString(fuelCount),
			Objects.toString(outputItemId, ""),
			Integer.toString(outputCount),
			Integer.toString(cookTime),
			Integer.toString(cookTimeTotal),
			Boolean.toString(burning)
		);
	}

	private static String normalizeItemId(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
