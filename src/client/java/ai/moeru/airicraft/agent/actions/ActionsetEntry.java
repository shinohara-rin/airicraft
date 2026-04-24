package ai.moeru.airicraft.agent.actions;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ActionsetEntry(
	String actionId,
	ActionsetNamespace namespace,
	String sourceName,
	Path sourcePath,
	Map<String, Object> definition
) {
	public ActionsetEntry {
		if (actionId == null || actionId.isBlank()) {
			throw new IllegalArgumentException("actionId is required");
		}
		if (namespace == null) {
			throw new IllegalArgumentException("namespace is required");
		}
		sourceName = sourceName == null || sourceName.isBlank() ? "<unknown>" : sourceName;
		definition = copyDefinition(definition);
	}

	private static Map<String, Object> copyDefinition(Map<String, Object> definition) {
		if (definition == null || definition.isEmpty()) {
			return Map.of();
		}
		return Collections.unmodifiableMap(new LinkedHashMap<>(definition));
	}
}
