package ai.moeru.airicraft.agent.integration.map;

import ai.moeru.airicraft.BridgeUnavailableException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public final class MapImageEncoder {
	private static final String FORMAT = "png";

	private MapImageEncoder() {
	}

	public static MapImageCapture encode(String providerId, String kind, BufferedImage image, long capturedAtMs) {
		if (image == null) {
			throw new BridgeUnavailableException("map_capture_failed", "Failed to encode map image");
		}
		BufferedImage encodedImage = trimTransparentBorder(image);
		return new MapImageCapture(
			providerId,
			kind,
			FORMAT,
			encodedImage.getWidth(),
			encodedImage.getHeight(),
			capturedAtMs,
			writePng(encodedImage)
		);
	}

	private static BufferedImage trimTransparentBorder(BufferedImage image) {
		int width = image.getWidth();
		int height = image.getHeight();
		int minX = width;
		int minY = height;
		int maxX = -1;
		int maxY = -1;

		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				if (((image.getRGB(x, y) >>> 24) & 0xff) != 0) {
					minX = Math.min(minX, x);
					minY = Math.min(minY, y);
					maxX = Math.max(maxX, x);
					maxY = Math.max(maxY, y);
				}
			}
		}

		if (maxX < minX || maxY < minY) {
			return image;
		}
		if (minX == 0 && minY == 0 && maxX == width - 1 && maxY == height - 1) {
			return image;
		}
		return image.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
	}

	private static byte[] writePng(BufferedImage image) {
		try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
			if (!ImageIO.write(image, FORMAT, outputStream)) {
				throw new IOException("No PNG writer is available");
			}
			return outputStream.toByteArray();
		}
		catch (IOException exception) {
			throw new BridgeUnavailableException("map_capture_failed", "Failed to encode map image");
		}
	}
}
