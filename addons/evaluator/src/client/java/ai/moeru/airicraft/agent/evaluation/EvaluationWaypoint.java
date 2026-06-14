package ai.moeru.airicraft.agent.evaluation;

public record EvaluationWaypoint(
	String provider,
	String id,
	String name,
	String dimension,
	int x,
	int y,
	int z
) {
	public EvaluationWaypoint {
		provider = provider == null || provider.isBlank() ? null : provider.trim();
		id = id == null || id.isBlank() ? null : id.trim();
		name = name == null || name.isBlank() ? "Waypoint" : name.trim();
		dimension = dimension == null || dimension.isBlank() ? "minecraft:overworld" : dimension.trim();
	}
}
