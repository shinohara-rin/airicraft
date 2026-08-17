package ai.moeru.airicraft.wrapper;

import java.util.Base64;
import java.util.Map;

record CapturedImage(
	byte[] bytes,
	String format,
	int width,
	int height,
	int sourceWidth,
	int sourceHeight,
	long capturedAtMs
) {
	CapturedImage {
		bytes = bytes.clone();
	}

	@Override
	public byte[] bytes() {
		return bytes.clone();
	}

	static CapturedImage screenshot(Map<String, Object> payload) {
		return fromBridgePayload(payload, true, "screenshot");
	}

	static CapturedImage mapImage(Map<String, Object> payload) {
		return fromBridgePayload(payload, false, "map image");
	}

	private static CapturedImage fromBridgePayload(Map<String, Object> payload, boolean hasSourceSize, String kind) {
		try {
			int width = requiredInt(payload, "width");
			int height = requiredInt(payload, "height");
			return new CapturedImage(
				Base64.getDecoder().decode(requiredString(payload, "imageBase64")),
				requiredString(payload, "format"),
				width,
				height,
				hasSourceSize ? requiredInt(payload, "sourceWidth") : width,
				hasSourceSize ? requiredInt(payload, "sourceHeight") : height,
				requiredLong(payload, "capturedAtMs")
			);
		}
		catch (IllegalArgumentException exception) {
			throw new BridgeUnavailableException("bridge_io_error", "Bridge returned an invalid " + kind + " payload");
		}
	}

	private static String requiredString(Map<String, Object> payload, String key) {
		Object value = payload.get(key);
		if (value instanceof String text && !text.isBlank()) {
			return text;
		}
		throw new IllegalArgumentException("Missing string field: " + key);
	}

	private static int requiredInt(Map<String, Object> payload, String key) {
		Object value = payload.get(key);
		if (value instanceof Number number) {
			return number.intValue();
		}
		throw new IllegalArgumentException("Missing integer field: " + key);
	}

	private static long requiredLong(Map<String, Object> payload, String key) {
		Object value = payload.get(key);
		if (value instanceof Number number) {
			return number.longValue();
		}
		throw new IllegalArgumentException("Missing integer field: " + key);
	}
}
