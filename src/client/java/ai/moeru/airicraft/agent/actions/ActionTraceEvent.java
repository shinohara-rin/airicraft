package ai.moeru.airicraft.agent.actions;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ActionTraceEvent(
	String eventType,
	String actionId,
	String alternativeId,
	String stepId,
	Map<String, Object> payload
) {
	public ActionTraceEvent {
		if (eventType == null || eventType.isBlank()) {
			throw new IllegalArgumentException("eventType is required");
		}
		actionId = actionId == null ? "" : actionId;
		alternativeId = alternativeId == null ? "" : alternativeId;
		stepId = stepId == null ? "" : stepId;
		payload = payload == null || payload.isEmpty()
			? Map.of()
			: Collections.unmodifiableMap(new LinkedHashMap<>(payload));
	}
}
