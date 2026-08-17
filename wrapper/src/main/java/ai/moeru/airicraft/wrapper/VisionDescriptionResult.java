package ai.moeru.airicraft.wrapper;

import java.util.Map;

record VisionDescriptionResult(
	String format,
	long capturedAtMs,
	String model,
	String description
) {
	static VisionDescriptionResult fromBridgePayload(Map<String, Object> payload) {
		return new VisionDescriptionResult(
			requiredString(payload, "format"),
			requiredLong(payload, "capturedAtMs"),
			requiredString(payload, "model"),
			requiredString(payload, "description")
		);
	}

	private static String requiredString(Map<String, Object> payload, String key) {
		Object value = payload.get(key);
		if (value instanceof String text && !text.isBlank()) {
			return text;
		}
		throw new IllegalArgumentException("Missing string field: " + key);
	}

	private static long requiredLong(Map<String, Object> payload, String key) {
		Object value = payload.get(key);
		if (value instanceof Number number) {
			return number.longValue();
		}
		throw new IllegalArgumentException("Missing integer field: " + key);
	}
}
