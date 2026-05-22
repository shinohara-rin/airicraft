package ai.moeru.airicraft.compat.journeymap;

import ai.moeru.airicraft.agent.integration.map.MapWaypoint;

final class JourneyMapWaypointMapper {
	private static final String PROVIDER_ID = "journeymap";

	private JourneyMapWaypointMapper() {
	}

	static MapWaypoint toMapWaypoint(
		String guid,
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
		return new MapWaypoint(
			PROVIDER_ID,
			guid,
			name,
			dimension,
			x,
			y,
			z,
			color,
			enabled,
			showOnMap,
			showInWorld
		);
	}
}
