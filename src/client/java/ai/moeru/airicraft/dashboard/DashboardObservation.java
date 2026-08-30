package ai.moeru.airicraft.dashboard;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

public record DashboardObservation(
	long sequence,
	String sessionId,
	long tick,
	long capturedAtMs,
	String type,
	String payloadJson
) {
	public DashboardObservation {
		sessionId = Objects.requireNonNull(sessionId, "sessionId");
		type = Objects.requireNonNull(type, "type");
		payloadJson = Objects.requireNonNull(payloadJson, "payloadJson");
	}

	public int retainedBytes() {
		return payloadJson.getBytes(StandardCharsets.UTF_8).length + sessionId.length() + type.length() + 64;
	}

	public JsonElement payload() {
		return JsonParser.parseString(payloadJson);
	}
}
