package ai.moeru.airicraft.agent.integration.map;

public record MapWaypoint(
	String providerId,
	String id,
	String name,
	String dimension,
	int x,
	int y,
	int z,
	int color,
	boolean enabled,
	boolean showOnMap,
	boolean showInWorld
) {
}
