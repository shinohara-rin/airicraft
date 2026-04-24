package ai.moeru.airicraft.agent.actions;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ActionFact(
	ActionFactIdentity identity,
	Map<String, Object> payload,
	ActionFactProvenance provenance,
	long observedTick,
	long staleAfterTick
) {
	public static final long NEVER_STALE = -1;

	public ActionFact {
		identity = Objects.requireNonNull(identity, "identity");
		payload = copyPayload(payload);
		provenance = Objects.requireNonNull(provenance, "provenance");
	}

	public boolean isStaleAt(long tick) {
		return staleAfterTick != NEVER_STALE && tick >= staleAfterTick;
	}

	private static Map<String, Object> copyPayload(Map<String, Object> payload) {
		if (payload == null || payload.isEmpty()) {
			return Map.of();
		}
		return Collections.unmodifiableMap(new LinkedHashMap<>(payload));
	}
}
