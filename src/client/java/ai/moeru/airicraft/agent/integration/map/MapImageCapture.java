package ai.moeru.airicraft.agent.integration.map;

public record MapImageCapture(
	String providerId,
	String kind,
	String format,
	int width,
	int height,
	long capturedAtMs,
	byte[] imageBytes
) {
	public MapImageCapture {
		imageBytes = imageBytes == null ? new byte[0] : imageBytes.clone();
	}

	@Override
	public byte[] imageBytes() {
		return imageBytes.clone();
	}
}
