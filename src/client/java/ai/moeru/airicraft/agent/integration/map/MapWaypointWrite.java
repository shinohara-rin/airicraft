package ai.moeru.airicraft.agent.integration.map;

public record MapWaypointWrite(
	String providerId,
	String id,
	String name,
	String dimension,
	int x,
	int y,
	int z,
	Integer color,
	boolean enabled,
	boolean showOnMap,
	boolean showInWorld
) {
}
