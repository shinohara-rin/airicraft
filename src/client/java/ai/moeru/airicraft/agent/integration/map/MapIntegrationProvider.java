package ai.moeru.airicraft.agent.integration.map;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface MapIntegrationProvider {
	String id();

	boolean available();

	MapCapabilities capabilities();

	List<MapWaypoint> listWaypoints(MapWaypointQuery query);

	MapWaypoint upsertWaypoint(MapWaypointWrite request);

	boolean deleteWaypoint(String waypointId);

	CompletableFuture<MapImageCapture> captureMap(MapImageRequest request);
}
