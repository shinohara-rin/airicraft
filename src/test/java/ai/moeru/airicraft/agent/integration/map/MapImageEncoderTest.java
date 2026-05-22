package ai.moeru.airicraft.agent.integration.map;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapImageEncoderTest {
	@Test
	void encodesBufferedImageAsPngCapture() {
		BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
		image.setRGB(0, 0, 0xff00ff00);

		MapImageCapture capture = MapImageEncoder.encode("journeymap", "worldmap", image, 100L);

		assertEquals("png", capture.format());
		assertEquals(4, capture.width());
		assertTrue(capture.imageBytes().length > 8);
	}
}
