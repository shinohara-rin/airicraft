package ai.moeru.airicraft.dataset;

import ai.moeru.airicraft.Airicraft;
import ai.moeru.airicraft.BridgeUnavailableException;
import ai.moeru.airicraft.LetterboxImageScaler;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.dataset.DatasetViewLabeler.LabelData;
import ai.moeru.airicraft.dataset.DatasetViewLabeler.RegionBoundsSpec;
import ai.moeru.airicraft.dataset.DatasetViewLabeler.View;
import ai.moeru.airicraft.dataset.ViewGeometry.Letterbox;
import ai.moeru.airicraft.dataset.ViewGeometry.Projection;
import ai.moeru.airicraft.dataset.ViewGeometry.Vec;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import ai.moeru.airicraft.mixin.client.GameRendererAccessor;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;
import org.joml.Matrix4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPOutputStream;

/**
 * Captures one rendered first-person frame together with exact ground truth:
 * a raycast label grid aligned to the emitted image, the surrounding voxel
 * region with a line-of-sight mask, entities with screen projections, and the
 * camera pose/projection needed to reproduce every label offline.
 *
 * <p>Captures run inside the world render pass via
 * {@link ClientRuntimeController#onFirstPersonFrameRendered()} so labels and
 * pixels describe the same world state. Frame PNG, metadata, and compressed
 * label payloads are written under {@code <gameDir>/airicraft/dataset/} (or a
 * caller-supplied directory) plus one appended index line in captures.jsonl.
 */
public final class DatasetCaptureService {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final int MAX_STRIDE_PX = 64;
	private static final double MAX_REACH = 256.0D;
	private static final int MAX_REGION_RADIUS = 64;
	private static final int MAX_REGION_CELLS = 4_000_000;
	private static final int MAX_RECENT_RESULT_IDS = 8;
	private static final DateTimeFormatter CAPTURE_ID_FORMAT =
		DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

	private final Object lock = new Object();
	private final DatasetViewLabeler labeler = new DatasetViewLabeler();
	private final ExecutorService writerExecutor = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "airicraft-dataset-writer");
		thread.setDaemon(true);
		return thread;
	});

	private CaptureJob activeJob;
	private long captureCounter;
	private long completedCaptures;
	private String lastCaptureId;
	private String lastCaptureDirectory;
	private final Deque<String> recentCaptureIds = new ArrayDeque<>();

	public CompletableFuture<CaptureResult> requestCapture(
		MinecraftClient client,
		CaptureOptions options,
		CameraController cameraController
	) {
		if (client == null || client.world == null || client.player == null) {
			throw new BridgeUnavailableException("world_not_loaded", "No world is currently loaded");
		}
		if (!client.options.getPerspective().isFirstPerson()) {
			throw new BridgeUnavailableException("perspective_not_first_person", "Dataset captures require first-person view");
		}
		options.validate();

		CaptureJob job;
		synchronized (lock) {
			if (activeJob != null) {
				throw new BridgeUnavailableException("capture_in_progress", "A dataset capture is already in progress");
			}
			job = new CaptureJob(options, new CompletableFuture<>());
			activeJob = job;
		}

		snapCamera(client, options, cameraController);
		return job.future();
	}

	private void snapCamera(MinecraftClient client, CaptureOptions options, CameraController cameraController) {
		ClientPlayerEntity player = client.player;
		if (player == null || (options.yaw() == null && options.pitch() == null && options.lookAt() == null)) {
			return;
		}
		float yaw = options.yaw() == null ? player.getYaw() : options.yaw().floatValue();
		float pitch = options.pitch() == null ? player.getPitch() : options.pitch().floatValue();
		if (options.lookAt() != null) {
			var rotation = CameraController.lookRotation(
				player.getEyePos(),
				new Vec3d(options.lookAt()[0], options.lookAt()[1], options.lookAt()[2])
			);
			if (rotation.isPresent()) {
				yaw = rotation.get().yaw();
				pitch = rotation.get().pitch();
			}
		}
		cameraController.clear();
		player.setYaw(yaw);
		player.setPitch(pitch);
		player.setHeadYaw(yaw);
		player.lastYaw = yaw;
		player.lastPitch = pitch;
		player.lastHeadYaw = yaw;
	}

	public void onWorldRendered(MinecraftClient client, RenderTickCounter tickCounter) {
		CaptureJob job;
		synchronized (lock) {
			if (activeJob == null || activeJob.phase() != CapturePhase.PENDING) {
				return;
			}
			activeJob = activeJob.withPhase(CapturePhase.CAPTURING);
			job = activeJob;
		}

		try {
			renderCapture(client, job, tickCounter);
		}
		catch (Throwable throwable) {
			Airicraft.LOGGER.warn("Failed to capture dataset frame", throwable);
			fail(job, new BridgeUnavailableException("capture_failed", "Failed to capture dataset frame"));
		}
	}

	private void renderCapture(MinecraftClient client, CaptureJob job, RenderTickCounter tickCounter) {
		Camera camera = client.gameRenderer.getCamera();
		if (camera == null || !camera.isReady()) {
			throw new BridgeUnavailableException("camera_unavailable", "The render camera is not available this frame");
		}
		// Recompute the same effective FOV renderWorld used and rebuild the matrix.
		float tickDelta = tickCounter.getTickProgress(true);
		float fov = ((GameRendererAccessor) client.gameRenderer).airicraft$invokeGetFov(camera, tickDelta, true);
		Matrix4f projectionMatrix = client.gameRenderer.getBasicProjectionMatrix(fov);
		// JOML m<column><row>: the perspective -1 sits at math M[3][2] = m23().
		if (Math.abs(projectionMatrix.m23() + 1.0F) > 0.001F) {
			throw new BridgeUnavailableException("projection_unavailable", "World projection matrix is not perspective this frame");
		}

		CaptureOptions options = job.options();
		int sourceWidth = client.getFramebuffer().textureWidth;
		int sourceHeight = client.getFramebuffer().textureHeight;
		View view = new View(
			vec(camera.getPos()),
			ViewGeometry.cameraBasis(camera.getYaw(), camera.getPitch()),
			// Projection uses math (row,col) convention: m02/m12 are JOML m20/m21.
			new Projection(
				projectionMatrix.m00(),
				projectionMatrix.m11(),
				projectionMatrix.m20(),
				projectionMatrix.m21()
			),
			ViewGeometry.letterbox(sourceWidth, sourceHeight, ViewGeometry.OUTPUT_WIDTH, ViewGeometry.OUTPUT_HEIGHT),
			sourceWidth,
			sourceHeight,
			ViewGeometry.OUTPUT_WIDTH,
			ViewGeometry.OUTPUT_HEIGHT,
			fov
		);

		ScreenshotRecorder.takeScreenshot(client.getFramebuffer(), image -> completeCapture(client, job, view, projectionMatrix, image));
	}

	private void completeCapture(MinecraftClient client, CaptureJob job, View view, Matrix4f projectionMatrix, NativeImage image) {
		try (image) {
			synchronized (lock) {
				if (activeJob != job || job.phase() != CapturePhase.CAPTURING) {
					return;
				}
				activeJob = job.withPhase(CapturePhase.WRITING);
			}

			CaptureOptions options = job.options();
			LabelData labels = labeler.label(
				client,
				view,
				options.stridePx(),
				options.reach(),
				options.includeRegion()
					? new RegionBoundsSpec(options.regionRadius(), options.regionBelow(), options.regionAbove())
					: null,
				options.includeEntities()
			);
			byte[] png = encodePng(image);
			long capturedAtMs = Instant.now().toEpochMilli();
			String captureId = nextCaptureId(capturedAtMs);
			writerExecutor.execute(() -> {
				try {
					CaptureResult result = writeCapture(client, job, view, projectionMatrix, labels, png, captureId, capturedAtMs);
					finish(job, result);
					job.future().complete(result);
				}
				catch (Throwable throwable) {
					Airicraft.LOGGER.warn("Failed to write dataset capture", throwable);
					fail(job, new BridgeUnavailableException("capture_failed", "Failed to write dataset capture"));
				}
			});
		}
		catch (BridgeUnavailableException exception) {
			fail(job, exception);
		}
		catch (Exception exception) {
			Airicraft.LOGGER.warn("Failed to label dataset frame", exception);
			fail(job, new BridgeUnavailableException("capture_failed", "Failed to label dataset frame"));
		}
	}

	private CaptureResult writeCapture(
		MinecraftClient client,
		CaptureJob job,
		View view,
		Matrix4f projectionMatrix,
		LabelData labels,
		byte[] png,
		String captureId,
		long capturedAtMs
	) throws IOException {
		CaptureOptions options = job.options();
		Path root = options.resolvedDatasetDir();
		Path directory = root.resolve(captureId);
		Files.createDirectories(directory);

		Map<String, String> files = new LinkedHashMap<>();
		Path framePath = directory.resolve("frame.png");
		Files.write(framePath, png);
		files.put("frame", framePath.toString());

		Path labelsPath = directory.resolve("labels.json.gz");
		writeJsonGz(labelsPath, labelsPayload(view, options, labels));
		files.put("labels", labelsPath.toString());

		if (options.includeRegion()) {
			Path regionPath = directory.resolve("region.json.gz");
			writeJsonGz(regionPath, Map.of("cells", labels.region()));
			files.put("region", regionPath.toString());
		}

		if (options.includeEntities()) {
			Path entitiesPath = directory.resolve("entities.json");
			writeJson(entitiesPath, Map.of("entities", labels.entities()));
			files.put("entities", entitiesPath.toString());
		}

		Map<String, Object> stats = stats(labels);
		Path metaPath = directory.resolve("meta.json");
		Map<String, Object> meta = metaPayload(client, job, view, projectionMatrix, captureId, capturedAtMs, labels, stats);
		writeJson(metaPath, meta);
		files.put("meta", metaPath.toString());

		appendIndex(root, captureId, directory, options.label(), capturedAtMs, stats, files);
		return new CaptureResult(captureId, directory.toString(), Map.copyOf(files), stats, capturedAtMs);
	}

	private Map<String, Object> metaPayload(
		MinecraftClient client,
		CaptureJob job,
		View view,
		Matrix4f projectionMatrix,
		String captureId,
		long capturedAtMs,
		LabelData labels,
		Map<String, Object> stats
	) {
		CaptureOptions options = job.options();
		ClientPlayerEntity player = client.player;
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("formatVersion", 1);
		meta.put("captureId", captureId);
		meta.put("label", options.label());
		meta.put("capturedAtMs", capturedAtMs);
		meta.put("dimensionId", client.world.getRegistryKey().getValue().toString());
		meta.put("worldTime", client.world.getTime());
		meta.put("timeOfDay", client.world.getTimeOfDay());
		meta.put("moonPhase", client.world.getMoonPhase());
		meta.put("raining", client.world.isRaining());
		meta.put("thundering", client.world.isThundering());
		BlockPos eyeBlock = BlockPos.ofFloored(view.cameraPos().x(), view.cameraPos().y(), view.cameraPos().z());
		meta.put("biome", client.world.getBiomeAccess().getBiome(eyeBlock).getIdAsString());
		meta.put("skyLight", client.world.getLightLevel(LightType.SKY, eyeBlock));
		meta.put("blockLight", client.world.getLightLevel(LightType.BLOCK, eyeBlock));
		meta.put("lightLevel", client.world.getLightLevel(eyeBlock));
		meta.put("playerPos", Map.of("x", player.getX(), "y", player.getY(), "z", player.getZ()));
		meta.put("playerYaw", (double) player.getYaw());
		meta.put("playerPitch", (double) player.getPitch());
		meta.put("effectiveFov", (double) view.effectiveFov());
		Vec cameraPos = view.cameraPos();
		meta.put("camera", Map.of(
			"x", cameraPos.x(), "y", cameraPos.y(), "z", cameraPos.z(),
			"yaw", (double) client.gameRenderer.getCamera().getYaw(),
			"pitch", (double) client.gameRenderer.getCamera().getPitch()
		));
		// True row-major: element M[r][c] is JOML accessor m<c><r>.
		meta.put("projectionMatrixRowMajor", List.of(
			List.of((double) projectionMatrix.m00(), (double) projectionMatrix.m10(), (double) projectionMatrix.m20(), (double) projectionMatrix.m30()),
			List.of((double) projectionMatrix.m01(), (double) projectionMatrix.m11(), (double) projectionMatrix.m21(), (double) projectionMatrix.m31()),
			List.of((double) projectionMatrix.m02(), (double) projectionMatrix.m12(), (double) projectionMatrix.m22(), (double) projectionMatrix.m32()),
			List.of((double) projectionMatrix.m03(), (double) projectionMatrix.m13(), (double) projectionMatrix.m23(), (double) projectionMatrix.m33())
		));
		meta.put("configuredFov", client.options.getFov().getValue());
		meta.put("fovYDegrees", view.projection().fovYDegrees());
		meta.put("image", Map.of(
			"width", view.outputWidth(),
			"height", view.outputHeight(),
			"sourceWidth", view.sourceWidth(),
			"sourceHeight", view.sourceHeight()
		));
		Letterbox letterbox = view.letterbox();
		meta.put("letterbox", Map.of(
			"scale", letterbox.scale(),
			"offsetX", letterbox.offsetX(),
			"offsetY", letterbox.offsetY(),
			"scaledWidth", letterbox.scaledWidth(),
			"scaledHeight", letterbox.scaledHeight()
		));
		Map<String, Object> labelsMeta = new LinkedHashMap<>();
		labelsMeta.put("stridePx", options.stridePx());
		labelsMeta.put("reach", options.reach());
		labelsMeta.put("cellCols", labels.cellCols());
		labelsMeta.put("cellRows", labels.cellRows());
		meta.put("labels", labelsMeta);
		if (options.includeRegion()) {
			var bounds = DatasetViewLabeler.regionBounds(
				new RegionBoundsSpec(options.regionRadius(), options.regionBelow(), options.regionAbove()),
				view.cameraPos()
			);
			meta.put("region", Map.of(
				"min", Map.of("x", bounds.minX(), "y", bounds.minY(), "z", bounds.minZ()),
				"max", Map.of("x", bounds.maxX(), "y", bounds.maxY(), "z", bounds.maxZ()),
				"cellCount", bounds.cellCount(),
				"indexOrder", "x_then_y_then_z"
			));
		}
		meta.put("stats", stats);
		return meta;
	}

	private Map<String, Object> labelsPayload(View view, CaptureOptions options, LabelData labels) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("formatVersion", 1);
		payload.put("imageWidth", view.outputWidth());
		payload.put("imageHeight", view.outputHeight());
		payload.put("stridePx", options.stridePx());
		payload.put("reach", options.reach());
		payload.put("cellCols", labels.cellCols());
		payload.put("cellRows", labels.cellRows());
		payload.put("cells", labels.cells());
		return payload;
	}

	private Map<String, Object> stats(LabelData labels) {
		int blockCells = 0;
		int entityCells = 0;
		int skyCells = 0;
		int paddingCells = 0;
		for (var cell : labels.cells()) {
			switch (cell.kind()) {
				case "block" -> blockCells++;
				case "entity" -> entityCells++;
				case "sky" -> skyCells++;
				case "padding" -> paddingCells++;
				default -> { }
			}
		}
		int viewVisibleRegionCells = 0;
		for (var cell : labels.region()) {
			if (cell.viewVisible()) {
				viewVisibleRegionCells++;
			}
		}
		Map<String, Object> stats = new LinkedHashMap<>();
		stats.put("cells", labels.cells().size());
		stats.put("blockCells", blockCells);
		stats.put("entityCells", entityCells);
		stats.put("skyCells", skyCells);
		stats.put("paddingCells", paddingCells);
		stats.put("regionCells", labels.region().size());
		stats.put("viewVisibleRegionCells", viewVisibleRegionCells);
		stats.put("entities", labels.entities().size());
		return stats;
	}

	private void appendIndex(
		Path root,
		String captureId,
		Path directory,
		String label,
		long capturedAtMs,
		Map<String, Object> stats,
		Map<String, String> files
	) throws IOException {
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("captureId", captureId);
		entry.put("label", label);
		entry.put("capturedAtMs", capturedAtMs);
		entry.put("directory", directory.toString());
		entry.put("stats", stats);
		entry.put("files", files);
		String line = GSON.toJson(entry) + "\n";
		Files.writeString(root.resolve("captures.jsonl"), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}

	private String nextCaptureId(long capturedAtMs) {
		synchronized (lock) {
			return "cap-" + CAPTURE_ID_FORMAT.format(Instant.ofEpochMilli(capturedAtMs)) + "-" + (++captureCounter);
		}
	}

	private void finish(CaptureJob job, CaptureResult result) {
		synchronized (lock) {
			if (activeJob != null && activeJob.future() == job.future()) {
				activeJob = null;
			}
			completedCaptures++;
			lastCaptureId = result.captureId();
			lastCaptureDirectory = result.directory();
			recentCaptureIds.addLast(result.captureId());
			while (recentCaptureIds.size() > MAX_RECENT_RESULT_IDS) {
				recentCaptureIds.removeFirst();
			}
		}
	}

	public void failActiveCapture(String code, String message) {
		CaptureJob job;
		synchronized (lock) {
			job = activeJob;
			activeJob = null;
		}
		if (job != null) {
			job.future().completeExceptionally(new BridgeUnavailableException(code, message));
		}
	}

	private void fail(CaptureJob job, RuntimeException exception) {
		synchronized (lock) {
			if (activeJob != null && activeJob.future() == job.future()) {
				activeJob = null;
			}
		}
		job.future().completeExceptionally(exception);
	}

	public Map<String, Object> status() {
		synchronized (lock) {
			Map<String, Object> status = new LinkedHashMap<>();
			status.put("available", true);
			status.put("captureInProgress", activeJob != null);
			status.put("completedCaptures", completedCaptures);
			status.put("lastCaptureId", lastCaptureId);
			status.put("lastCaptureDirectory", lastCaptureDirectory);
			status.put("recentCaptureIds", List.copyOf(recentCaptureIds));
			status.put("defaultDatasetDir", defaultDatasetDir().toString());
			return status;
		}
	}

	public void shutdown() {
		writerExecutor.shutdown();
	}

	static Path defaultDatasetDir() {
		return FabricLoader.getInstance().getGameDir().resolve("airicraft").resolve("dataset");
	}

	private static Vec vec(Vec3d value) {
		return new Vec(value.x, value.y, value.z);
	}

	private static byte[] encodePng(NativeImage image) {
		BufferedImage sourceImage = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
		sourceImage.setRGB(0, 0, image.getWidth(), image.getHeight(), image.copyPixelsArgb(), 0, image.getWidth());
		BufferedImage scaledImage = LetterboxImageScaler.scaleToCanvas(
			sourceImage, ViewGeometry.OUTPUT_WIDTH, ViewGeometry.OUTPUT_HEIGHT
		);
		try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
			if (!ImageIO.write(scaledImage, "png", outputStream)) {
				throw new IOException("No PNG writer is available");
			}
			return outputStream.toByteArray();
		}
		catch (IOException exception) {
			throw new BridgeUnavailableException("capture_failed", "Failed to encode screenshot");
		}
	}

	private static void writeJson(Path path, Object payload) throws IOException {
		try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
			GSON.toJson(payload, writer);
		}
	}

	private static void writeJsonGz(Path path, Object payload) throws IOException {
		try (OutputStream outputStream = new GZIPOutputStream(Files.newOutputStream(path));
			 Writer writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)) {
			GSON.toJson(payload, writer);
		}
	}

	private enum CapturePhase {
		PENDING,
		CAPTURING,
		WRITING
	}

	private record CaptureJob(CapturePhase phase, CaptureOptions options, CompletableFuture<CaptureResult> future) {
		private CaptureJob(CaptureOptions options, CompletableFuture<CaptureResult> future) {
			this(CapturePhase.PENDING, options, future);
		}

		private CaptureJob withPhase(CapturePhase nextPhase) {
			return new CaptureJob(nextPhase, options, future);
		}
	}

	public record CaptureOptions(
		String label,
		Double yaw,
		Double pitch,
		double[] lookAt,
		int stridePx,
		double reach,
		int regionRadius,
		int regionBelow,
		int regionAbove,
		boolean includeRegion,
		boolean includeEntities,
		String outputDir
	) {
		public static CaptureOptions defaults() {
			return new CaptureOptions(null, null, null, null, 8, 96.0D, 32, 8, 24, true, true, null);
		}

		public void validate() {
			if (stridePx < 1 || stridePx > MAX_STRIDE_PX) {
				throw new BridgeUnavailableException("invalid_request", "stridePx must be between 1 and " + MAX_STRIDE_PX);
			}
			if (reach <= 0.0D || reach > MAX_REACH) {
				throw new BridgeUnavailableException("invalid_request", "reach must be between 0 and " + MAX_REACH);
			}
			if (regionRadius < 0 || regionRadius > MAX_REGION_RADIUS) {
				throw new BridgeUnavailableException("invalid_request", "regionRadius must be between 0 and " + MAX_REGION_RADIUS);
			}
			if (regionBelow < 0 || regionAbove < 0) {
				throw new BridgeUnavailableException("invalid_request", "region bounds must be non-negative");
			}
			if (yaw != null && (yaw < -180.0D || yaw > 180.0D)) {
				throw new BridgeUnavailableException("invalid_request", "yaw must be between -180 and 180");
			}
			if (pitch != null && (pitch < -90.0D || pitch > 90.0D)) {
				throw new BridgeUnavailableException("invalid_request", "pitch must be between -90 and 90");
			}
			if (lookAt != null && lookAt.length != 3) {
				throw new BridgeUnavailableException("invalid_request", "lookAt must contain exactly 3 coordinates");
			}
			if (includeRegion) {
				long cells = (2L * regionRadius + 1L) * (2L * regionRadius + 1L) * ((long) regionBelow + regionAbove + 1L);
				if (cells > MAX_REGION_CELLS) {
					throw new BridgeUnavailableException("region_too_large", "Requested region exceeds " + MAX_REGION_CELLS + " cells");
				}
			}
		}

		Path resolvedDatasetDir() {
			if (outputDir == null || outputDir.isBlank()) {
				return defaultDatasetDir();
			}
			Path requested = Path.of(outputDir);
			return requested.isAbsolute()
				? requested
				: FabricLoader.getInstance().getGameDir().resolve(requested).normalize();
		}
	}

	public record CaptureResult(
		String captureId,
		String directory,
		Map<String, String> files,
		Map<String, Object> stats,
		long capturedAtMs
	) {
	}
}
