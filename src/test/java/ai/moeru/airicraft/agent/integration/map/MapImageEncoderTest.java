package ai.moeru.airicraft.agent.integration.map;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapImageEncoderTest {
	@Test
	void encodesBufferedImageAsPngCapture() {
		BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				image.setRGB(x, y, 0xff00ff00);
			}
		}

		MapImageCapture capture = MapImageEncoder.encode("journeymap", "worldmap", image, 100L);

		assertEquals("png", capture.format());
		assertEquals(4, capture.width());
		assertTrue(capture.imageBytes().length > 8);
	}

	@Test
	void trimsFullyTransparentOuterBorderBeforeEncoding() throws IOException {
		BufferedImage image = new BufferedImage(6, 5, BufferedImage.TYPE_INT_ARGB);
		image.setRGB(2, 1, 0xff00ff00);
		image.setRGB(3, 1, 0xff00aa00);
		image.setRGB(2, 2, 0xff006600);
		image.setRGB(3, 2, 0xff003300);

		MapImageCapture capture = MapImageEncoder.encode("journeymap", "minimap", image, 100L);
		BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(capture.imageBytes()));

		assertEquals(2, capture.width());
		assertEquals(2, capture.height());
		assertEquals(2, decoded.getWidth());
		assertEquals(2, decoded.getHeight());
		assertEquals(0xff00ff00, decoded.getRGB(0, 0));
		assertEquals(0xff003300, decoded.getRGB(1, 1));
	}
}
