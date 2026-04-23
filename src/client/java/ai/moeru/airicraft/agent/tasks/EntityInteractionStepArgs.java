package ai.moeru.airicraft.agent.tasks;

public record EntityInteractionStepArgs(
	EntitySelector selector,
	String itemId
) {
	public EntityInteractionStepArgs {
		selector = java.util.Objects.requireNonNull(selector, "selector");
		itemId = itemId == null || itemId.isBlank() ? null : itemId.trim();
	}
}
