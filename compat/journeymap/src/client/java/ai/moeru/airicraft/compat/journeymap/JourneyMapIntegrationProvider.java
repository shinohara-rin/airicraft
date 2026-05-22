package ai.moeru.airicraft.compat.journeymap;

import ai.moeru.airicraft.agent.integration.map.MapCapabilities;
import ai.moeru.airicraft.agent.integration.map.MapCapabilities.MapCapability;
import ai.moeru.airicraft.agent.integration.map.MapImageCapture;
import ai.moeru.airicraft.agent.integration.map.MapImageRequest;
import ai.moeru.airicraft.agent.integration.map.MapIntegrationProvider;
import ai.moeru.airicraft.agent.integration.map.MapWaypoint;
import ai.moeru.airicraft.agent.integration.map.MapWaypointQuery;
import ai.moeru.airicraft.agent.integration.map.MapWaypointWrite;
import journeymap.api.v2.client.IClientAPI;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class JourneyMapIntegrationProvider implements MapIntegrationProvider {
	public static final String PROVIDER_ID = "journeymap";

	private final IClientAPI jmAPI;

	public JourneyMapIntegrationProvider(IClientAPI jmAPI) {
		this.jmAPI = Objects.requireNonNull(jmAPI, "jmAPI");
	}

	@Override
	public String id() {
		return PROVIDER_ID;
	}

	@Override
	public boolean available() {
		return true;
	}

	@Override
	public MapCapabilities capabilities() {
		return MapCapabilities.of(
			MapCapability.READ_WAYPOINTS,
			MapCapability.WRITE_WAYPOINTS,
			MapCapability.DELETE_WAYPOINTS,
			MapCapability.WORLDMAP_IMAGE
		);
	}

	@Override
	public List<MapWaypoint> listWaypoints(MapWaypointQuery query) {
		return List.of();
	}

	@Override
	public MapWaypoint upsertWaypoint(MapWaypointWrite request) {
		throw new UnsupportedOperationException("JourneyMap waypoint writes are not implemented yet");
	}

	@Override
	public boolean deleteWaypoint(String waypointId) {
		throw new UnsupportedOperationException("JourneyMap waypoint deletion is not implemented yet");
	}

	@Override
	public CompletableFuture<MapImageCapture> captureMap(MapImageRequest request) {
		return CompletableFuture.failedFuture(new UnsupportedOperationException("JourneyMap map capture is not implemented yet"));
	}
}
