package ai.moeru.airicraft.agent.tasks;

public record EntityInteractionStepArgs(
	EntitySelector selector,
	String itemId,
	EntityAttackMode attackMode
) {
	public EntityInteractionStepArgs(EntitySelector selector, String itemId) {
		this(selector, itemId, EntityAttackMode.KILL);
	}

	public EntityInteractionStepArgs {
		selector = java.util.Objects.requireNonNull(selector, "selector");
		itemId = itemId == null || itemId.isBlank() ? null : itemId.trim();
		attackMode = attackMode == null ? EntityAttackMode.KILL : attackMode;
	}
}
