package ai.moeru.airicraft.agent.actions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

public final class ActionsetLibraryLoader {
	private final ActionsetValidator validator;

	public ActionsetLibraryLoader(ActionsetValidator validator) {
		this.validator = Objects.requireNonNull(validator, "validator");
	}

	public static ActionsetLibraryLoader defaults() {
		return new ActionsetLibraryLoader(ActionsetValidator.defaults());
	}

	public ActionsetLoadResult load(Path root) {
		Path actionsetRoot = root == null ? Path.of("actionsets") : root;
		LinkedHashMap<String, ActionsetEntry> entries = new LinkedHashMap<>();
		ArrayList<ActionsetLoadDiagnostic> diagnostics = new ArrayList<>();
		for (ActionsetNamespace namespace : ActionsetNamespace.values()) {
			loadNamespace(actionsetRoot, namespace, entries, diagnostics);
		}
		validateIndexedCycles(entries, diagnostics);
		return new ActionsetLoadResult(new ActionsetIndex(entries), diagnostics);
	}

	private void loadNamespace(
		Path root,
		ActionsetNamespace namespace,
		Map<String, ActionsetEntry> entries,
		List<ActionsetLoadDiagnostic> diagnostics
	) {
		Path namespaceRoot = root.resolve(namespace.directoryName());
		if (!Files.exists(namespaceRoot)) {
			return;
		}
		try (Stream<Path> stream = Files.walk(namespaceRoot)) {
			List<Path> files = stream
				.filter(Files::isRegularFile)
				.filter(ActionsetLibraryLoader::isYamlFile)
				.sorted(Comparator.comparing(path -> normalized(root, path)))
				.toList();
			for (Path file : files) {
				loadFile(root, namespace, file, entries, diagnostics);
			}
		}
		catch (IOException exception) {
			diagnostics.add(new ActionsetLoadDiagnostic(
				"source_read_failed",
				"$",
				exception.getMessage(),
				normalized(root, namespaceRoot),
				namespace,
				namespace.indexed()
			));
		}
	}

	private void loadFile(
		Path root,
		ActionsetNamespace namespace,
		Path file,
		Map<String, ActionsetEntry> entries,
		List<ActionsetLoadDiagnostic> diagnostics
	) {
		String sourceName = normalized(root, file);
		ActionsetDocument document;
		try {
			document = ActionsetParser.parse(file);
		}
		catch (Exception exception) {
			diagnostics.add(new ActionsetLoadDiagnostic(
				"parse_failed",
				"$",
				exception.getMessage(),
				sourceName,
				namespace,
				namespace.indexed()
			));
			return;
		}

		ActionsetValidationResult validation = validator.validate(document);
		if (!validation.valid()) {
			for (ActionsetValidationError error : validation.errors()) {
				diagnostics.add(new ActionsetLoadDiagnostic(
					error.code(),
					error.path(),
					error.message(),
					sourceName,
					namespace,
					namespace.indexed()
				));
			}
			return;
		}
		if (!namespace.indexed()) {
			return;
		}

		for (Map.Entry<String, Object> action : actionMap(document).entrySet()) {
			String actionId = action.getKey();
			if (entries.containsKey(actionId)) {
				ActionsetEntry existing = entries.get(actionId);
				diagnostics.add(new ActionsetLoadDiagnostic(
					"duplicate_action",
					"$.actions." + actionId,
					"actionset \"" + actionId + "\" already loaded from " + existing.sourceName(),
					sourceName,
					namespace,
					true
				));
				continue;
			}
			entries.put(actionId, new ActionsetEntry(
				actionId,
				namespace,
				sourceName,
				file,
				objectMap(action.getValue())
			));
		}
	}

	private static Map<String, Object> actionMap(ActionsetDocument document) {
		return objectMap(document.root().get("actions"));
	}

	private static void validateIndexedCycles(
		Map<String, ActionsetEntry> entries,
		List<ActionsetLoadDiagnostic> diagnostics
	) {
		Map<String, String> producerByFact = new LinkedHashMap<>();
		for (ActionsetEntry entry : entries.values()) {
			for (String produced : factKeys(objectList(entry.definition().get("produces")))) {
				producerByFact.putIfAbsent(produced, entry.actionId());
			}
		}

		Map<String, Set<String>> edges = new LinkedHashMap<>();
		for (ActionsetEntry entry : entries.values()) {
			LinkedHashSet<String> targets = new LinkedHashSet<>();
			for (String need : neededFactKeys(entry.definition())) {
				String target = producerByFact.get(need);
				if (target != null) {
					targets.add(target);
				}
			}
			edges.put(entry.actionId(), targets);
		}

		LinkedHashSet<String> visiting = new LinkedHashSet<>();
		HashSet<String> visited = new HashSet<>();
		for (String actionId : edges.keySet()) {
			if (detectCycle(actionId, edges, visiting, visited)) {
				String cycleStart = visiting.iterator().next();
				ActionsetEntry entry = entries.get(cycleStart);
				diagnostics.add(new ActionsetLoadDiagnostic(
					"cycle_detected",
					"$.actions." + cycleStart,
					"cycle detected in indexed action needs graph",
					entry == null ? "<unknown>" : entry.sourceName(),
					entry == null ? null : entry.namespace(),
					true
				));
				return;
			}
		}
	}

	private static boolean detectCycle(
		String actionId,
		Map<String, Set<String>> edges,
		LinkedHashSet<String> visiting,
		Set<String> visited
	) {
		if (visited.contains(actionId)) {
			return false;
		}
		if (visiting.contains(actionId)) {
			return true;
		}
		visiting.add(actionId);
		for (String target : edges.getOrDefault(actionId, Set.of())) {
			if (detectCycle(target, edges, visiting, visited)) {
				return true;
			}
		}
		visiting.remove(actionId);
		visited.add(actionId);
		return false;
	}

	private static List<String> neededFactKeys(Map<String, Object> action) {
		ArrayList<String> needs = new ArrayList<>();
		for (Object alternativeObject : objectList(action.get("alternatives"))) {
			Map<String, Object> alternative = objectMap(alternativeObject);
			needs.addAll(factKeys(objectList(alternative.get("needs"))));
		}
		return needs;
	}

	private static List<String> factKeys(List<Object> factObjects) {
		ArrayList<String> keys = new ArrayList<>();
		for (Object factObject : factObjects) {
			keys.add(normalizedFactKey(objectMap(factObject)));
		}
		return keys;
	}

	private static String normalizedFactKey(Map<String, Object> fact) {
		String factType = scalar(fact.get("fact"));
		StringBuilder builder = new StringBuilder(factType == null ? "" : factType);
		for (String key : List.of("actorId", "itemId", "toolTag", "dimension", "blockPos", "cropId", "siteId", "siteType", "entityTypeId", "recipeId")) {
			String value = scalar(fact.get(key));
			if (value != null && !value.isBlank()) {
				builder.append('|').append(key).append('=').append(value);
			}
		}
		return builder.toString();
	}

	private static boolean isYamlFile(Path path) {
		String fileName = path.getFileName().toString();
		return fileName.endsWith(".yml") || fileName.endsWith(".yaml");
	}

	private static String normalized(Path root, Path path) {
		Path relative;
		try {
			relative = root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize());
		}
		catch (IllegalArgumentException exception) {
			relative = path;
		}
		return relative.toString().replace('\\', '/');
	}

	private static Map<String, Object> objectMap(Object value) {
		if (value instanceof Map<?, ?> map) {
			LinkedHashMap<String, Object> typed = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				typed.put(String.valueOf(entry.getKey()), entry.getValue());
			}
			return typed;
		}
		return Map.of();
	}

	private static List<Object> objectList(Object value) {
		if (value instanceof List<?> list) {
			return List.copyOf(list);
		}
		return List.of();
	}

	private static String scalar(Object value) {
		return value == null ? null : String.valueOf(value);
	}
}
