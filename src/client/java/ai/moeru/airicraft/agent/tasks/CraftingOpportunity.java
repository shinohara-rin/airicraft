package ai.moeru.airicraft.agent.tasks;

public record CraftingOpportunity(
	String itemId,
	int outputCount,
	String ingredientSummary
) {
	public CraftingOpportunity {
		if (itemId == null || itemId.isBlank()) {
			throw new IllegalArgumentException("itemId must not be blank");
		}
		if (outputCount <= 0) {
			throw new IllegalArgumentException("outputCount must be positive");
		}
		ingredientSummary = ingredientSummary == null ? "" : ingredientSummary;
	}

	public String compactDescription() {
		return itemId + " output=" + outputCount + " craftableNow=true";
	}
}
