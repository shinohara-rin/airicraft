package ai.moeru.airicraft.compat.journeymap;

import ai.moeru.airicraft.BridgeUnavailableException;
import ai.moeru.airicraft.agent.integration.map.MapCapabilities;
import ai.moeru.airicraft.agent.integration.map.MapCapabilities.MapCapability;
import ai.moeru.airicraft.agent.integration.map.MapImageCapture;
import ai.moeru.airicraft.agent.integration.map.MapImageEncoder;
import ai.moeru.airicraft.agent.integration.map.MapImageRequest;
import ai.moeru.airicraft.agent.integration.map.MapIntegrationProvider;
import ai.moeru.airicraft.agent.integration.map.MapWaypoint;
import ai.moeru.airicraft.agent.integration.map.MapWaypointQuery;
import ai.moeru.airicraft.agent.integration.map.MapWaypointWrite;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.display.Context;
import journeymap.api.v2.common.waypoint.Waypoint;
import journeymap.api.v2.common.waypoint.WaypointFactory;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.awt.image.BufferedImage;
import java.time.Instant;
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
		MapImageRequest safeRequest = request == null
			? new MapImageRequest(PROVIDER_ID, "worldmap", null, 8, 0, false)
			: request;
		if (!"worldmap".equalsIgnoreCase(Objects.requireNonNullElse(safeRequest.kind(), ""))) {
			return CompletableFuture.failedFuture(new BridgeUnavailableException("map_kind_unavailable", "JourneyMap worldmap capture is available, but minimap capture is not"));
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.world == null || client.player == null) {
			return CompletableFuture.failedFuture(new BridgeUnavailableException("world_not_loaded", "No world is currently loaded"));
		}
		String currentDimension = client.world.getRegistryKey().getValue().toString();
		if (safeRequest.dimension() != null && !safeRequest.dimension().isBlank() && !currentDimension.equals(safeRequest.dimension())) {
			return CompletableFuture.failedFuture(new BridgeUnavailableException("map_dimension_unavailable", "JourneyMap capture currently requires the active dimension"));
		}

		CompletableFuture<MapImageCapture> future = new CompletableFuture<>();
		ChunkPos centerChunk = client.player.getChunkPos();
		int radiusChunks = Math.max(1, Math.min(16, safeRequest.radiusChunks()));
		int zoom = Math.max(0, Math.min(8, safeRequest.zoom()));
		ChunkPos startChunk = new ChunkPos(centerChunk.x - radiusChunks, centerChunk.z - radiusChunks);
		ChunkPos endChunk = new ChunkPos(centerChunk.x + radiusChunks, centerChunk.z + radiusChunks);
		jmAPI.requestMapTile(
			AIRICRAFT_MOD_ID,
			client.world.getRegistryKey(),
			Context.MapType.Day,
			startChunk,
			endChunk,
			null,
			zoom,
			safeRequest.grid(),
			image -> completeMapCapture(future, image)
		);
		return future;
	}

	private static void completeMapCapture(CompletableFuture<MapImageCapture> future, NativeImage image) {
		if (image == null) {
			future.completeExceptionally(new BridgeUnavailableException("map_unavailable", "JourneyMap map tile is unavailable"));
			return;
		}
		try (image) {
			future.complete(MapImageEncoder.encode(PROVIDER_ID, "worldmap", toBufferedImage(image), Instant.now().toEpochMilli()));
		}
		catch (RuntimeException exception) {
			future.completeExceptionally(exception);
		}
	}

	private static BufferedImage toBufferedImage(NativeImage image) {
		BufferedImage bufferedImage = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
		bufferedImage.setRGB(0, 0, image.getWidth(), image.getHeight(), image.copyPixelsArgb(), 0, image.getWidth());
		return bufferedImage;
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
