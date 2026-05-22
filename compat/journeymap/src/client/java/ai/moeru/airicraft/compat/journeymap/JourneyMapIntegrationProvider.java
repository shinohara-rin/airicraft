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
import journeymap.api.v2.common.waypoint.Waypoint;
import journeymap.api.v2.common.waypoint.WaypointFactory;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class JourneyMapIntegrationProvider implements MapIntegrationProvider {
	public static final String PROVIDER_ID = "journeymap";
	private static final String AIRICRAFT_MOD_ID = "airicraft";
	private static final int DEFAULT_WAYPOINT_COLOR = 0x33aaff;

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
		String dimension = query == null ? null : query.dimension();
		return jmAPI.getAllWaypoints().stream()
			.filter(waypoint -> dimension == null || dimension.isBlank() || dimension.equals(waypoint.getPrimaryDimension()))
			.map(JourneyMapIntegrationProvider::toMapWaypoint)
			.toList();
	}

	@Override
	public MapWaypoint upsertWaypoint(MapWaypointWrite request) {
		Objects.requireNonNull(request, "request");
		String id = request.id();
		Waypoint waypoint = id == null || id.isBlank()
			? WaypointFactory.createWaypoint(
				AIRICRAFT_MOD_ID,
				new BlockPos(request.x(), request.y(), request.z()),
				request.name(),
				request.dimension(),
				true
			)
			: jmAPI.getWaypoint(AIRICRAFT_MOD_ID, id);
		if (waypoint == null) {
			waypoint = WaypointFactory.createWaypoint(
				AIRICRAFT_MOD_ID,
				new BlockPos(request.x(), request.y(), request.z()),
				request.name(),
				request.dimension(),
				true
			);
		}
		waypoint.setName(request.name());
		waypoint.setPos(request.x(), request.y(), request.z());
		waypoint.setPrimaryDimension(request.dimension());
		waypoint.setColor(request.color() == null ? DEFAULT_WAYPOINT_COLOR : request.color());
		waypoint.setEnabled(request.enabled());
		waypoint.setShowOnMap(request.showOnMap());
		waypoint.setShowInWorld(request.showInWorld());
		jmAPI.addWaypoint(AIRICRAFT_MOD_ID, waypoint);
		return toMapWaypoint(waypoint);
	}

	@Override
	public boolean deleteWaypoint(String waypointId) {
		if (waypointId == null || waypointId.isBlank()) {
			return false;
		}
		Waypoint waypoint = jmAPI.getWaypoint(AIRICRAFT_MOD_ID, waypointId);
		if (waypoint == null) {
			return false;
		}
		jmAPI.removeWaypoint(AIRICRAFT_MOD_ID, waypoint);
		return true;
	}

	@Override
	public CompletableFuture<MapImageCapture> captureMap(MapImageRequest request) {
		return CompletableFuture.failedFuture(new UnsupportedOperationException("JourneyMap map capture is not implemented yet"));
	}

	static MapWaypoint toMapWaypoint(Waypoint waypoint) {
		Objects.requireNonNull(waypoint, "waypoint");
		return JourneyMapWaypointMapper.toMapWaypoint(
			waypoint.getGuid(),
			waypoint.getName(),
			waypoint.getPrimaryDimension(),
			waypoint.getX(),
			waypoint.getY(),
			waypoint.getZ(),
			waypoint.getColor(),
			waypoint.isEnabled(),
			waypoint.showOnMap(),
			waypoint.showInWorld()
		);
	}
}
