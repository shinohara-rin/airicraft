package ai.moeru.airicraft.agent.actions;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

public record ActionGraphResolvedInventoryItem(
	ActionResolveResult result,
	Map<String, Object> payload
) {
	public ActionGraphResolvedInventoryItem {
		if (result == null) {
			throw new IllegalArgumentException("result is required");
		}
		payload = payload == null || payload.isEmpty()
			? Map.of()
			: Collections.unmodifiableMap(new LinkedHashMap<>(payload));
	}
}
