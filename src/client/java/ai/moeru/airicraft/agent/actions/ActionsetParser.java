package ai.moeru.airicraft.agent.actions;

import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ActionsetParser {
	private static final Yaml YAML = new Yaml();

	private ActionsetParser() {
	}

	public static ActionsetDocument parse(String sourceName, String yamlText) {
		Object loaded = YAML.load(yamlText == null ? "" : yamlText);
		if (!(loaded instanceof Map<?, ?> map)) {
			return new ActionsetDocument(sourceName, Map.of());
		}
		return new ActionsetDocument(sourceName, typedMap(map));
	}

	private static Map<String, Object> typedMap(Map<?, ?> input) {
		LinkedHashMap<String, Object> typed = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : input.entrySet()) {
			typed.put(String.valueOf(entry.getKey()), normalize(entry.getValue()));
		}
		return typed;
	}

	private static Object normalize(Object value) {
		if (value instanceof Map<?, ?> map) {
			return typedMap(map);
		}
		if (value instanceof List<?> list) {
			ArrayList<Object> normalized = new ArrayList<>();
			for (Object item : list) {
				normalized.add(normalize(item));
			}
			return List.copyOf(normalized);
		}
		return value;
	}
}
