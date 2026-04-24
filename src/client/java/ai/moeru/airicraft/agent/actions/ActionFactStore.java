package ai.moeru.airicraft.agent.actions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ActionFactStore {
	private final Map<ActionFactIdentity, ActionFact> facts = new LinkedHashMap<>();

	public synchronized ActionFact upsert(ActionFact fact) {
		Objects.requireNonNull(fact, "fact");
		ActionFact existing = facts.get(fact.identity());
		if (existing == null || shouldReplace(existing, fact)) {
			facts.put(fact.identity(), fact);
			return fact;
		}
		return existing;
	}

	public synchronized Optional<ActionFact> find(ActionFactIdentity identity) {
		return Optional.ofNullable(facts.get(Objects.requireNonNull(identity, "identity")));
	}

	public synchronized List<ActionFact> query(ActionFactType type) {
		return query(type, Map.of());
	}

	public synchronized List<ActionFact> query(ActionFactType type, Map<String, String> requiredKeys) {
		Objects.requireNonNull(type, "type");
		Map<String, String> keys = requiredKeys == null ? Map.of() : requiredKeys;
		return facts.values().stream()
			.filter(fact -> fact.identity().matches(type, keys))
			.toList();
	}

	public synchronized int size() {
		return facts.size();
	}

	public synchronized void clear() {
		facts.clear();
	}

	private static boolean shouldReplace(ActionFact existing, ActionFact incoming) {
		if (existing.provenance().authoritative() && !incoming.provenance().authoritative()) {
			return false;
		}
		if (!existing.provenance().authoritative() && incoming.provenance().authoritative()) {
			return true;
		}
		return incoming.observedTick() >= existing.observedTick();
	}
}
