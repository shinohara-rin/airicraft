package ai.moeru.airicraft;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.concurrent.CompletableFuture;

public final class HudScreenshotService {
	private final Object lock = new Object();

	private CaptureJob activeJob;

	public CompletableFuture<BufferedImage> requestTopRightMinimapCapture(MinecraftClient client) {
		return requestMinimapCapture(client, null, false);
	}

	public CompletableFuture<BufferedImage> requestMinimapCapture(MinecraftClient client, Rectangle scaledBounds, boolean circleMask) {
		if (client == null || client.world == null || client.player == null) {
			throw new BridgeUnavailableException("world_not_loaded", "No world is currently loaded");
		}

		synchronized (lock) {
			if (activeJob != null) {
				throw new BridgeUnavailableException("capture_in_progress", "A HUD screenshot capture is already in progress");
			}
			activeJob = new CaptureJob(
				new CompletableFuture<>(),
				scaledBounds,
				client.getWindow().getScaledWidth(),
				client.getWindow().getScaledHeight(),
				circleMask
			);
			return activeJob.future();
		}
	}

	public void onFrameRendered(MinecraftClient client) {
		CaptureJob job;
		synchronized (lock) {
			if (activeJob == null || activeJob.phase() != CapturePhase.PENDING) {
				return;
			}
			activeJob = activeJob.withPhase(CapturePhase.CAPTURING);
			job = activeJob;
		}

		try {
			ScreenshotRecorder.takeScreenshot(client.getFramebuffer(), image -> completeCapture(job, image));
		}
		catch (Throwable throwable) {
			fail(job, new BridgeUnavailableException("map_capture_failed", "Failed to capture HUD screenshot"));
			Airicraft.LOGGER.warn("Failed to capture HUD screenshot", throwable);
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

	private void completeCapture(CaptureJob job, NativeImage image) {
		try (image) {
			synchronized (lock) {
				if (activeJob != job || job.phase() != CapturePhase.CAPTURING) {
					return;
				}
				activeJob = job.withPhase(CapturePhase.ENCODING);
			}

			BufferedImage croppedImage = HudMinimapCropper.cropScaledBounds(
				toBufferedImage(image),
				job.scaledBounds(),
				job.scaledWidth(),
				job.scaledHeight(),
				job.circleMask()
			);
			synchronized (lock) {
				if (activeJob != null && activeJob.future() == job.future()) {
					activeJob = null;
				}
			}
			job.future().complete(croppedImage);
		}
		catch (BridgeUnavailableException exception) {
			fail(job, exception);
		}
		catch (Exception exception) {
			Airicraft.LOGGER.warn("Failed to encode HUD screenshot", exception);
			fail(job, new BridgeUnavailableException("map_capture_failed", "Failed to encode HUD screenshot"));
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

	private static BufferedImage toBufferedImage(NativeImage image) {
		BufferedImage sourceImage = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
		sourceImage.setRGB(0, 0, image.getWidth(), image.getHeight(), image.copyPixelsArgb(), 0, image.getWidth());
		return sourceImage;
	}

	private enum CapturePhase {
		PENDING,
		CAPTURING,
		ENCODING
	}

	private record CaptureJob(
		CapturePhase phase,
		CompletableFuture<BufferedImage> future,
		Rectangle scaledBounds,
		int scaledWidth,
		int scaledHeight,
		boolean circleMask
	) {
		private CaptureJob(
			CompletableFuture<BufferedImage> future,
			Rectangle scaledBounds,
			int scaledWidth,
			int scaledHeight,
			boolean circleMask
		) {
			this(CapturePhase.PENDING, future, copy(scaledBounds), scaledWidth, scaledHeight, circleMask);
		}

		private CaptureJob withPhase(CapturePhase nextPhase) {
			return new CaptureJob(nextPhase, future, copy(scaledBounds), scaledWidth, scaledHeight, circleMask);
		}

		private static Rectangle copy(Rectangle rectangle) {
			return rectangle == null ? null : new Rectangle(rectangle);
		}
	}
}
