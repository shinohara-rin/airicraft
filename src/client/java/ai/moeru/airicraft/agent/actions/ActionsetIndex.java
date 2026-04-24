package ai.moeru.airicraft.agent.actions;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class ActionsetIndex {
	private static final ActionsetIndex EMPTY = new ActionsetIndex(Map.of());

	private final Map<String, ActionsetEntry> entries;

	public ActionsetIndex(Map<String, ActionsetEntry> entries) {
		this.entries = entries == null || entries.isEmpty()
			? Map.of()
			: Collections.unmodifiableMap(new LinkedHashMap<>(entries));
	}

	public static ActionsetIndex empty() {
		return EMPTY;
	}

	public boolean contains(String actionId) {
		return entries.containsKey(actionId);
	}

	public Optional<ActionsetEntry> find(String actionId) {
		return Optional.ofNullable(entries.get(actionId));
	}

	public ActionsetEntry require(String actionId) {
		ActionsetEntry entry = entries.get(actionId);
		if (entry == null) {
			throw new IllegalArgumentException("Unknown actionset: " + actionId);
		}
		return entry;
	}

	public Collection<ActionsetEntry> all() {
		return entries.values();
	}

	public int size() {
		return entries.size();
	}
}
