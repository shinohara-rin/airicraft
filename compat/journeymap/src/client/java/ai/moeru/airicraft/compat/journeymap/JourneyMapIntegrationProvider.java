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
import journeymap.api.v2.common.waypoint.Waypoint;
import journeymap.api.v2.common.waypoint.WaypointFactory;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.List;
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
			MapCapability.MINIMAP_IMAGE,
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
			? WaypointFactory.createClientWaypoint(
				AIRICRAFT_MOD_ID,
				new BlockPos(request.x(), request.y(), request.z()),
				request.name(),
				request.dimension(),
				true
			)
			: jmAPI.getWaypoint(AIRICRAFT_MOD_ID, id);
		if (waypoint == null) {
			waypoint = WaypointFactory.createClientWaypoint(
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
		String kind = Objects.requireNonNullElse(safeRequest.kind(), "worldmap").toLowerCase(Locale.ROOT);
		if (!"worldmap".equals(kind) && !"minimap".equals(kind)) {
			return CompletableFuture.failedFuture(new BridgeUnavailableException("map_kind_unavailable", "JourneyMap cached map capture supports worldmap and minimap"));
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.world == null || client.player == null) {
			return CompletableFuture.failedFuture(new BridgeUnavailableException("world_not_loaded", "No world is currently loaded"));
		}
		String currentDimension = client.world.getRegistryKey().getValue().toString();
		if (safeRequest.dimension() != null && !safeRequest.dimension().isBlank() && !currentDimension.equals(safeRequest.dimension())) {
			return CompletableFuture.failedFuture(new BridgeUnavailableException("map_dimension_unavailable", "JourneyMap capture currently requires the active dimension"));
		}

		ChunkPos centerChunk = client.player.getChunkPos();
		int radiusChunks = Math.max(0, Math.min(96, safeRequest.radiusChunks()));
		int regionRadius = "minimap".equals(kind) ? 0 : Math.max(0, (radiusChunks + 31) / 32);
		try {
			Path imageDir = journeyMapDimensionDir(client, client.world.getRegistryKey()).resolve("day");
			BufferedImage image = stitchCachedRegionImages(imageDir, regionCoordinateForChunk(centerChunk.x), regionCoordinateForChunk(centerChunk.z), regionRadius);
			return CompletableFuture.completedFuture(MapImageEncoder.encode(PROVIDER_ID, kind, image, System.currentTimeMillis()));
		}
		catch (BridgeUnavailableException exception) {
			return CompletableFuture.failedFuture(exception);
		}
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
			waypoint.isEnabled(),
			waypoint.isEnabled()
		);
	}

	static int regionCoordinateForChunk(int chunkCoordinate) {
		return Math.floorDiv(chunkCoordinate, 32);
	}

	static BufferedImage stitchCachedRegionImages(Path imageDir, int centerRegionX, int centerRegionZ, int regionRadius) {
		if (imageDir == null || !Files.isDirectory(imageDir)) {
			throw new BridgeUnavailableException("map_unavailable", "JourneyMap cached map directory is unavailable");
		}
		int safeRadius = Math.max(0, regionRadius);
		int regionCount = safeRadius * 2 + 1;
		BufferedImage stitched = new BufferedImage(regionCount * 512, regionCount * 512, BufferedImage.TYPE_INT_ARGB);
		boolean foundImage = false;
		Graphics2D graphics = stitched.createGraphics();
		try {
			for (int dz = -safeRadius; dz <= safeRadius; dz++) {
				for (int dx = -safeRadius; dx <= safeRadius; dx++) {
					int regionX = centerRegionX + dx;
					int regionZ = centerRegionZ + dz;
					BufferedImage tile = readRegionImage(imageDir.resolve(regionX + "," + regionZ + ".png"));
					if (tile != null) {
						foundImage = true;
						graphics.drawImage(tile, (dx + safeRadius) * 512, (dz + safeRadius) * 512, null);
					}
				}
			}
		}
		finally {
			graphics.dispose();
		}
		if (!foundImage) {
			throw new BridgeUnavailableException("map_unavailable", "No JourneyMap cached region images are available");
		}
		return stitched;
	}

	private static BufferedImage readRegionImage(Path path) {
		if (!Files.isRegularFile(path)) {
			return null;
		}
		try {
			return ImageIO.read(path.toFile());
		}
		catch (IOException exception) {
			throw new BridgeUnavailableException("map_capture_failed", "Failed to read JourneyMap cached region image");
		}
	}

	private static Path journeyMapDimensionDir(MinecraftClient client, RegistryKey<World> dimension) {
		try {
			Class<?> fileHandler = Class.forName("journeymap.client.io.FileHandler");
			Method getWorldDir = fileHandler.getMethod("getJMWorldDir", MinecraftClient.class);
			File worldDir = (File) getWorldDir.invoke(null, client);
			if (worldDir == null) {
				throw new BridgeUnavailableException("map_unavailable", "JourneyMap world map directory is unavailable");
			}
			Method getDimPath = fileHandler.getMethod("getDimPath", File.class, RegistryKey.class);
			return ((Path) getDimPath.invoke(null, worldDir, dimension)).normalize();
		}
		catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException exception) {
			throw new BridgeUnavailableException("map_unavailable", "JourneyMap cached map path API is unavailable");
		}
		catch (InvocationTargetException exception) {
			throw new BridgeUnavailableException("map_unavailable", "JourneyMap cached map path is unavailable");
		}
	}
}
