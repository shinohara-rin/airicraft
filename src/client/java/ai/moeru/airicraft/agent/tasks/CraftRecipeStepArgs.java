package ai.moeru.airicraft.agent.tasks;

public record CraftRecipeStepArgs(
	String itemId,
	int quantity
) {
	public CraftRecipeStepArgs {
		if (itemId == null || itemId.isBlank()) {
			throw new IllegalArgumentException("itemId must not be blank");
		}
		if (quantity <= 0) {
			throw new IllegalArgumentException("quantity must be positive");
		}
	}

	public String recipeId() {
		return itemId;
	}
}
