package ai.moeru.airicraft;

import org.junit.jupiter.api.Test;

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
}
