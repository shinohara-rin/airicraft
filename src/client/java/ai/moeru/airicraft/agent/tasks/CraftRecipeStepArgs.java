package ai.moeru.airicraft.agent.tasks;

public record CraftRecipeStepArgs(
	String recipeId,
	int times
) {
	public CraftRecipeStepArgs {
		if (recipeId == null || recipeId.isBlank()) {
			throw new IllegalArgumentException("recipeId must not be blank");
		}
		if (times <= 0) {
			throw new IllegalArgumentException("times must be positive");
		}
	}
}
