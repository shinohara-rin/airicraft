package ai.moeru.airicraft;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;

public final class HudMinimapCropper {
	private static final double TOP_RIGHT_MINIMAP_HEIGHT_RATIO = 0.72D;
	private static final int MIN_CROP_SIZE = 64;

	private HudMinimapCropper() {
	}

	public static BufferedImage cropTopRight(BufferedImage source) {
		if (source == null) {
			throw new BridgeUnavailableException("map_capture_failed", "Failed to crop HUD minimap image");
		}
		int size = Math.max(MIN_CROP_SIZE, (int) Math.round(source.getHeight() * TOP_RIGHT_MINIMAP_HEIGHT_RATIO));
		size = Math.min(size, Math.min(source.getWidth(), source.getHeight()));
		int x = Math.max(0, source.getWidth() - size);
		return source.getSubimage(x, 0, size, size);
	}

	public static BufferedImage cropScaledBounds(
		BufferedImage source,
		Rectangle scaledBounds,
		int scaledWidth,
		int scaledHeight,
		boolean circleMask
	) {
		if (source == null || scaledBounds == null || scaledWidth <= 0 || scaledHeight <= 0) {
			return cropTopRight(source);
		}

		double scaleX = (double) source.getWidth() / (double) scaledWidth;
		double scaleY = (double) source.getHeight() / (double) scaledHeight;
		int x = clamp((int) Math.floor(scaledBounds.x * scaleX), 0, source.getWidth() - 1);
		int y = clamp((int) Math.floor(scaledBounds.y * scaleY), 0, source.getHeight() - 1);
		int width = clamp((int) Math.ceil(scaledBounds.width * scaleX), 1, source.getWidth() - x);
		int height = clamp((int) Math.ceil(scaledBounds.height * scaleY), 1, source.getHeight() - y);
		BufferedImage crop = source.getSubimage(x, y, width, height);
		return circleMask ? applyEllipseMask(crop) : crop;
	}

	private static BufferedImage applyEllipseMask(BufferedImage source) {
		BufferedImage masked = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
		double centerX = (source.getWidth() - 1) / 2.0D;
		double centerY = (source.getHeight() - 1) / 2.0D;
		double radiusX = Math.max(1.0D, source.getWidth() / 2.0D);
		double radiusY = Math.max(1.0D, source.getHeight() / 2.0D);
		for (int y = 0; y < source.getHeight(); y++) {
			for (int x = 0; x < source.getWidth(); x++) {
				double normalizedX = (x - centerX) / radiusX;
				double normalizedY = (y - centerY) / radiusY;
				if (normalizedX * normalizedX + normalizedY * normalizedY <= 1.0D) {
					masked.setRGB(x, y, source.getRGB(x, y));
				}
			}
		}
		return masked;
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
