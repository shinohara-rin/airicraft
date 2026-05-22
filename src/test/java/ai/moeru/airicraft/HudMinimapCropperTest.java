package ai.moeru.airicraft;

import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HudMinimapCropperTest {
	@Test
	void cropsTopRightMinimapRegionFromFramebuffer() {
		BufferedImage source = new BufferedImage(400, 240, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < source.getHeight(); y++) {
			for (int x = 0; x < source.getWidth(); x++) {
				source.setRGB(x, y, 0xff111111);
			}
		}
		source.setRGB(390, 10, 0xffff0000);

		BufferedImage crop = HudMinimapCropper.cropTopRight(source);

		assertEquals(173, crop.getWidth());
		assertEquals(173, crop.getHeight());
		assertEquals(0xffff0000, crop.getRGB(crop.getWidth() - 10, 10));
	}

	@Test
	void cropsScaledBoundsAndMasksCircularCorners() {
		BufferedImage source = new BufferedImage(400, 240, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < source.getHeight(); y++) {
			for (int x = 0; x < source.getWidth(); x++) {
				source.setRGB(x, y, 0xff112233);
			}
		}
		source.setRGB(240, 80, 0xffff0000);
		source.setRGB(280, 120, 0xff00ff00);

		BufferedImage crop = HudMinimapCropper.cropScaledBounds(source, new Rectangle(100, 10, 80, 80), 200, 120, true);

		assertEquals(160, crop.getWidth());
		assertEquals(160, crop.getHeight());
		assertEquals(0, (crop.getRGB(0, 0) >>> 24) & 0xff);
		assertEquals(0xffff0000, crop.getRGB(40, 60));
		assertEquals(0xff00ff00, crop.getRGB(80, 100));
	}
}
