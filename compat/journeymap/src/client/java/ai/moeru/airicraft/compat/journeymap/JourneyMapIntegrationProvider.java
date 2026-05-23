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
import net.minecraft.world.World;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class JourneyMapIntegrationProvider implements MapIntegrationProvider {
	public static final String PROVIDER_ID = "journeymap";
	private static final String AIRICRAFT_MOD_ID = "airicraft";
	private static final int DEFAULT_WAYPOINT_COLOR = 0x33aaff;
	private static final int JOURNEYMAP_REGION_PIXELS = 512;
	private static final int MAX_WORLDMAP_REGION_RADIUS = 2;
	private static final int MINIMAP_IMAGE_SIZE = 512;

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

		return captureCachedMap(client, kind, safeRequest);
	}

	private CompletableFuture<MapImageCapture> captureCachedMap(MinecraftClient client, String kind, MapImageRequest safeRequest) {
		if (safeRequest.hasPartialOrigin()) {
			return CompletableFuture.failedFuture(new BridgeUnavailableException("invalid_request", "Map image origin requires both originX and originZ"));
		}
		int radiusChunks = Math.max(0, Math.min(96, safeRequest.radiusChunks()));
		int outputSize = "minimap".equals(kind)
			? MINIMAP_IMAGE_SIZE
			: (Math.min(MAX_WORLDMAP_REGION_RADIUS, Math.max(0, (radiusChunks + 31) / 32)) * 2 + 1) * JOURNEYMAP_REGION_PIXELS;
		try {
			Path imageDir = journeyMapDimensionDir(client, client.world.getRegistryKey()).resolve("day");
			BlockPos playerBlock = client.player.getBlockPos();
			int originBlockX = safeRequest.originX() == null ? playerBlock.getX() : safeRequest.originX();
			int originBlockZ = safeRequest.originZ() == null ? playerBlock.getZ() : safeRequest.originZ();
			String dimension = client.world.getRegistryKey().getValue().toString();
			BufferedImage image = composeCenteredMapImage(
				imageDir,
				originBlockX,
				originBlockZ,
				outputSize,
				playerBlock.getX(),
				playerBlock.getZ(),
				client.player.getYaw(),
				listWaypoints(new MapWaypointQuery(PROVIDER_ID, dimension))
			);
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
			visibilityFlag(waypoint, "showOnMap", waypoint.isEnabled()),
			visibilityFlag(waypoint, "showInWorld", waypoint.isEnabled())
		);
	}

	static boolean visibilityFlag(Object waypoint, String methodName, boolean fallback) {
		if (waypoint == null || methodName == null || methodName.isBlank()) {
			return fallback;
		}
		try {
			Method method = waypoint.getClass().getMethod(methodName);
			if (!method.canAccess(waypoint)) {
				method.setAccessible(true);
			}
			Object value = method.invoke(waypoint);
			return value instanceof Boolean flag ? flag : fallback;
		}
		catch (ReflectiveOperationException | SecurityException exception) {
			return fallback;
		}
	}

	static int regionCoordinateForChunk(int chunkCoordinate) {
		return Math.floorDiv(chunkCoordinate, 32);
	}

	static BufferedImage composeCenteredMapImage(Path imageDir, int playerBlockX, int playerBlockZ, int outputSize, boolean drawMarker, float yawDegrees) {
		return composeCenteredMapImage(
			imageDir,
			playerBlockX,
			playerBlockZ,
			outputSize,
			drawMarker ? playerBlockX : null,
			drawMarker ? playerBlockZ : null,
			drawMarker ? yawDegrees : null,
			List.of()
		);
	}

	static BufferedImage composeCenteredMapImage(
		Path imageDir,
		int originBlockX,
		int originBlockZ,
		int outputSize,
		Integer playerBlockX,
		Integer playerBlockZ,
		Float yawDegrees,
		List<MapWaypoint> waypoints
	) {
		if (imageDir == null || !Files.isDirectory(imageDir)) {
			throw new BridgeUnavailableException("map_unavailable", "JourneyMap cached map directory is unavailable");
		}
		int safeOutputSize = Math.max(1, outputSize);
		int center = safeOutputSize / 2;
		int minWorldX = originBlockX - center;
		int minWorldZ = originBlockZ - center;
		int maxWorldX = minWorldX + safeOutputSize - 1;
		int maxWorldZ = minWorldZ + safeOutputSize - 1;
		BufferedImage output = new BufferedImage(safeOutputSize, safeOutputSize, BufferedImage.TYPE_INT_ARGB);
		ImageBounds contentBounds = new ImageBounds();

		boolean foundImage = false;
		Graphics2D graphics = output.createGraphics();
		try {
			for (int regionZ = Math.floorDiv(minWorldZ, JOURNEYMAP_REGION_PIXELS);
				 regionZ <= Math.floorDiv(maxWorldZ, JOURNEYMAP_REGION_PIXELS);
				 regionZ++) {
				for (int regionX = Math.floorDiv(minWorldX, JOURNEYMAP_REGION_PIXELS);
					 regionX <= Math.floorDiv(maxWorldX, JOURNEYMAP_REGION_PIXELS);
					 regionX++) {
					BufferedImage tile = readRegionImage(imageDir.resolve(regionX + "," + regionZ + ".png"));
					if (tile == null) {
						continue;
					}
					foundImage = drawIntersectingTile(
						graphics,
						tile,
						regionX * JOURNEYMAP_REGION_PIXELS,
						regionZ * JOURNEYMAP_REGION_PIXELS,
						minWorldX,
						minWorldZ,
						maxWorldX,
						maxWorldZ,
						contentBounds
					) || foundImage;
				}
			}
		}
		finally {
			graphics.dispose();
		}

		if (!foundImage) {
			throw new BridgeUnavailableException("map_unavailable", "No JourneyMap cached region images are available");
		}
		drawWaypointMarkers(output, minWorldX, minWorldZ, maxWorldX, maxWorldZ, waypoints, contentBounds);
		if (playerBlockX != null && playerBlockZ != null && yawDegrees != null
			&& playerBlockX >= minWorldX && playerBlockX <= maxWorldX
			&& playerBlockZ >= minWorldZ && playerBlockZ <= maxWorldZ) {
			drawPlayerMarker(output, playerBlockX - minWorldX, playerBlockZ - minWorldZ, yawDegrees, contentBounds);
		}
		return cropToBounds(output, contentBounds);
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

	private static boolean drawIntersectingTile(
		Graphics2D graphics,
		BufferedImage tile,
		int tileWorldX,
		int tileWorldZ,
		int outputMinWorldX,
		int outputMinWorldZ,
		int outputMaxWorldX,
		int outputMaxWorldZ,
		ImageBounds contentBounds
	) {
		int intersectionMinWorldX = Math.max(outputMinWorldX, tileWorldX);
		int intersectionMinWorldZ = Math.max(outputMinWorldZ, tileWorldZ);
		int intersectionMaxWorldX = Math.min(outputMaxWorldX, tileWorldX + tile.getWidth() - 1);
		int intersectionMaxWorldZ = Math.min(outputMaxWorldZ, tileWorldZ + tile.getHeight() - 1);
		if (intersectionMinWorldX > intersectionMaxWorldX || intersectionMinWorldZ > intersectionMaxWorldZ) {
			return false;
		}

		int destinationX1 = intersectionMinWorldX - outputMinWorldX;
		int destinationY1 = intersectionMinWorldZ - outputMinWorldZ;
		int sourceX1 = intersectionMinWorldX - tileWorldX;
		int sourceY1 = intersectionMinWorldZ - tileWorldZ;
		int sourceX2 = intersectionMaxWorldX - tileWorldX + 1;
		int sourceY2 = intersectionMaxWorldZ - tileWorldZ + 1;
		ImageBounds sourceBounds = nonTransparentBounds(tile, sourceX1, sourceY1, sourceX2, sourceY2);
		if (sourceBounds.isEmpty()) {
			return false;
		}
		int visibleDestinationX1 = destinationX1 + sourceBounds.minX() - sourceX1;
		int visibleDestinationY1 = destinationY1 + sourceBounds.minY() - sourceY1;
		int visibleDestinationX2 = visibleDestinationX1 + sourceBounds.width();
		int visibleDestinationY2 = visibleDestinationY1 + sourceBounds.height();

		graphics.drawImage(
			tile,
			visibleDestinationX1,
			visibleDestinationY1,
			visibleDestinationX2,
			visibleDestinationY2,
			sourceBounds.minX(),
			sourceBounds.minY(),
			sourceBounds.maxX() + 1,
			sourceBounds.maxY() + 1,
			null
		);
		contentBounds.include(visibleDestinationX1, visibleDestinationY1, visibleDestinationX2 - 1, visibleDestinationY2 - 1);
		return true;
	}

	private static void drawWaypointMarkers(
		BufferedImage image,
		int outputMinWorldX,
		int outputMinWorldZ,
		int outputMaxWorldX,
		int outputMaxWorldZ,
		List<MapWaypoint> waypoints,
		ImageBounds contentBounds
	) {
		if (waypoints == null || waypoints.isEmpty()) {
			return;
		}
		for (MapWaypoint waypoint : waypoints) {
			if (waypoint == null || !waypoint.enabled() || !waypoint.showOnMap()) {
				continue;
			}
			if (waypoint.x() < outputMinWorldX || waypoint.x() > outputMaxWorldX
				|| waypoint.z() < outputMinWorldZ || waypoint.z() > outputMaxWorldZ) {
				continue;
			}
			drawWaypointMarker(image, waypoint.x() - outputMinWorldX, waypoint.z() - outputMinWorldZ, waypoint, contentBounds);
		}
	}

	private static void drawWaypointMarker(BufferedImage image, int centerX, int centerY, MapWaypoint waypoint, ImageBounds contentBounds) {
		int color = 0xff000000 | (waypoint.color() & 0x00ffffff);
		Graphics2D graphics = image.createGraphics();
		try {
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			graphics.setStroke(new BasicStroke(2.0F));
			graphics.setColor(Color.WHITE);
			graphics.fillOval(centerX - 6, centerY - 6, 12, 12);
			graphics.setColor(Color.BLACK);
			graphics.drawOval(centerX - 6, centerY - 6, 12, 12);
			graphics.setColor(new Color(color, true));
			graphics.fillOval(centerX - 4, centerY - 4, 8, 8);
			contentBounds.include(centerX - 7, centerY - 7, centerX + 7, centerY + 7);
			drawWaypointLabel(graphics, image, centerX, centerY, waypoint.name(), contentBounds);
		}
		finally {
			graphics.dispose();
		}
		if (isInside(image, centerX, centerY)) {
			image.setRGB(centerX, centerY, color);
		}
	}

	private static void drawWaypointLabel(Graphics2D graphics, BufferedImage image, int centerX, int centerY, String name, ImageBounds contentBounds) {
		if (image.getWidth() < 96 || image.getHeight() < 32 || name == null || name.isBlank()) {
			return;
		}
		String label = name.length() > 24 ? name.substring(0, 21) + "..." : name;
		var metrics = graphics.getFontMetrics();
		int width = metrics.stringWidth(label);
		if (width > image.getWidth() - 2) {
			return;
		}
		int labelX = Math.max(1, Math.min(image.getWidth() - width - 1, centerX + 9));
		int labelY = Math.max(metrics.getAscent() + 1, Math.min(image.getHeight() - metrics.getDescent() - 1, centerY - 7));
		graphics.setColor(new Color(0xaa000000, true));
		graphics.drawString(label, labelX + 1, labelY + 1);
		graphics.setColor(Color.WHITE);
		graphics.drawString(label, labelX, labelY);
		contentBounds.include(labelX, labelY - metrics.getAscent(), labelX + width + 1, labelY + metrics.getDescent() + 1);
	}

	private static void drawPlayerMarker(BufferedImage image, int centerX, int centerY, float yawDegrees, ImageBounds contentBounds) {
		Graphics2D graphics = image.createGraphics();
		try {
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			graphics.setStroke(new BasicStroke(2.0F));
			double headingRadians = Math.toRadians(yawDegrees);
			int headingX = centerX + (int) Math.round(-Math.sin(headingRadians) * 14.0D);
			int headingY = centerY + (int) Math.round(Math.cos(headingRadians) * 14.0D);
			graphics.setColor(Color.WHITE);
			graphics.drawLine(centerX, centerY, headingX, headingY);
			graphics.fillOval(centerX - 6, centerY - 6, 12, 12);
			graphics.setColor(Color.BLACK);
			graphics.drawOval(centerX - 6, centerY - 6, 12, 12);
			graphics.setColor(new Color(0xffff2d2d, true));
			graphics.drawLine(centerX, centerY, headingX, headingY);
			graphics.fillOval(centerX - 4, centerY - 4, 8, 8);
			graphics.drawLine(centerX - 10, centerY, centerX - 7, centerY);
			graphics.drawLine(centerX + 7, centerY, centerX + 10, centerY);
			graphics.drawLine(centerX, centerY - 10, centerX, centerY - 7);
			graphics.drawLine(centerX, centerY + 7, centerX, centerY + 10);
		}
		finally {
			graphics.dispose();
		}
		if (isInside(image, centerX, centerY)) {
			image.setRGB(centerX, centerY, 0xffff2d2d);
		}
		contentBounds.include(centerX - 16, centerY - 16, centerX + 16, centerY + 16);
	}

	private static boolean isInside(BufferedImage image, int x, int y) {
		return x >= 0 && y >= 0 && x < image.getWidth() && y < image.getHeight();
	}

	private static ImageBounds nonTransparentBounds(BufferedImage image, int minX, int minY, int maxXExclusive, int maxYExclusive) {
		ImageBounds bounds = new ImageBounds();
		for (int y = minY; y < maxYExclusive; y++) {
			for (int x = minX; x < maxXExclusive; x++) {
				if (((image.getRGB(x, y) >>> 24) & 0xff) != 0) {
					bounds.include(x, y, x, y);
				}
			}
		}
		return bounds;
	}

	private static BufferedImage cropToBounds(BufferedImage image, ImageBounds bounds) {
		if (bounds.isEmpty()) {
			return image;
		}
		int minX = Math.max(0, bounds.minX());
		int minY = Math.max(0, bounds.minY());
		int maxX = Math.min(image.getWidth() - 1, bounds.maxX());
		int maxY = Math.min(image.getHeight() - 1, bounds.maxY());
		if (minX == 0 && minY == 0 && maxX == image.getWidth() - 1 && maxY == image.getHeight() - 1) {
			return image;
		}
		BufferedImage cropped = new BufferedImage(maxX - minX + 1, maxY - minY + 1, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = cropped.createGraphics();
		try {
			graphics.drawImage(image, 0, 0, cropped.getWidth(), cropped.getHeight(), minX, minY, maxX + 1, maxY + 1, null);
		}
		finally {
			graphics.dispose();
		}
		return cropped;
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

	private static final class ImageBounds {
		private int minX = Integer.MAX_VALUE;
		private int minY = Integer.MAX_VALUE;
		private int maxX = Integer.MIN_VALUE;
		private int maxY = Integer.MIN_VALUE;

		private void include(int includedMinX, int includedMinY, int includedMaxX, int includedMaxY) {
			minX = Math.min(minX, includedMinX);
			minY = Math.min(minY, includedMinY);
			maxX = Math.max(maxX, includedMaxX);
			maxY = Math.max(maxY, includedMaxY);
		}

		private boolean isEmpty() {
			return minX > maxX || minY > maxY;
		}

		private int minX() {
			return minX;
		}

		private int minY() {
			return minY;
		}

		private int maxX() {
			return maxX;
		}

		private int maxY() {
			return maxY;
		}

		private int width() {
			return maxX - minX + 1;
		}

		private int height() {
			return maxY - minY + 1;
		}
	}
}
