package ai.moeru.airicraft.agent.actions;

public record ActionResolverContext(
	String worldId,
	String actorId,
	String dimension,
	long currentTick
) {
	public ActionResolverContext {
		if (worldId == null || worldId.isBlank()) {
			throw new IllegalArgumentException("worldId is required");
		}
		if (actorId == null || actorId.isBlank()) {
			throw new IllegalArgumentException("actorId is required");
		}
		if (dimension == null || dimension.isBlank()) {
			throw new IllegalArgumentException("dimension is required");
		}
	}
}
