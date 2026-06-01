package ai.moeru.airicraft.agent.tasks;

public record SmeltItemsStepArgs(
	String optionId,
	int inputQuantity,
	SmeltingFuelMode fuelMode,
	String fuelItemId,
	int fuelQuantity,
	String confirmationToken
) {
	public SmeltItemsStepArgs {
		optionId = optionId == null ? null : optionId.trim();
		fuelMode = fuelMode == null ? SmeltingFuelMode.AUTO : fuelMode;
		fuelItemId = fuelItemId == null || fuelItemId.isBlank() ? null : fuelItemId.trim();
		confirmationToken = confirmationToken == null || confirmationToken.isBlank() ? null : confirmationToken.trim();
		if (optionId == null || optionId.isBlank()) {
			throw new IllegalArgumentException("optionId must not be blank");
		}
		if (inputQuantity <= 0) {
			throw new IllegalArgumentException("inputQuantity must be positive");
		}
		if (fuelQuantity < 0) {
			throw new IllegalArgumentException("fuelQuantity must be non-negative");
		}
		if (fuelMode == SmeltingFuelMode.MANUAL && fuelItemId == null) {
			throw new IllegalArgumentException("fuelItemId is required for manual fuel");
		}
		if (fuelMode == SmeltingFuelMode.MANUAL && fuelQuantity <= 0) {
			throw new IllegalArgumentException("fuelQuantity must be positive for manual fuel");
		}
	}
}
