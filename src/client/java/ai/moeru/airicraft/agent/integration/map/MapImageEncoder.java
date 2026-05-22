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
		return new MapImageCapture(
			providerId,
			kind,
			FORMAT,
			image.getWidth(),
			image.getHeight(),
			capturedAtMs,
			writePng(image)
		);
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
