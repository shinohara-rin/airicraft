package ai.moeru.airicraft;

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
}
