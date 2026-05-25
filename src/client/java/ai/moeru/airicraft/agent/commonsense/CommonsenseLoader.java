package ai.moeru.airicraft.agent.commonsense;

import ai.moeru.airicraft.Airicraft;
import ai.moeru.airicraft.ConfigLoadException;
import net.fabricmc.loader.api.FabricLoader;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CommonsenseLoader {
	private static final Yaml YAML = createYaml();
	private static final String TEMPLATE_RESOURCE = "/config/airicraft/commonsense.yml.example";
	private static final String TEMPLATE_FILENAME = "commonsense.yml.example";
	private static final String CONFIG_FILENAME = "commonsense.yml";

	private CommonsenseLoader() {
	}

	public static CommonsenseConfig load() {
		try {
			return loadInternal(false);
		}
		catch (ConfigLoadException exception) {
			Airicraft.LOGGER.warn("Failed to load commonsense config; using defaults", exception);
			return CommonsenseConfig.defaults();
		}
	}

	public static CommonsenseConfig loadStrict() throws ConfigLoadException {
		return loadInternal(true);
	}

	private static CommonsenseConfig loadInternal(boolean strict) throws ConfigLoadException {
		CommonsenseConfig defaults = CommonsenseConfig.defaults();
		Path configDir = FabricLoader.getInstance().getConfigDir().resolve("airicraft");
		Path templatePath = configDir.resolve(TEMPLATE_FILENAME);
		Path configPath = configDir.resolve(CONFIG_FILENAME);

		try {
			Files.createDirectories(configDir);
			ensureFile(templatePath);
			if (Files.notExists(configPath)) {
				Files.copy(templatePath, configPath);
			}

			try (Reader fileReader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
				return strict ? fromMapStrict(parseYaml(fileReader), defaults) : fromMap(parseYaml(fileReader), defaults);
			}
		}
		catch (IOException | RuntimeException exception) {
			throw new ConfigLoadException(
				configPath,
				"Failed to load %s: %s".formatted(configPath.getFileName(), nonEmpty(exception.getMessage(), exception.getClass().getSimpleName())),
				exception
			);
		}
	}

	static CommonsenseConfig fromMap(Map<String, Object> root, CommonsenseConfig defaults) {
		return fromMap(root, defaults, false);
	}

	static CommonsenseConfig fromMapStrict(Map<String, Object> root, CommonsenseConfig defaults) {
		return fromMap(root, defaults, true);
	}

	private static CommonsenseConfig fromMap(Map<String, Object> root, CommonsenseConfig defaults, boolean strict) {
		return new CommonsenseConfig(
			readBoolean(root, "enabled", defaults.enabled(), strict),
			readRules(root, defaults.rules(), strict)
		);
	}

	private static List<String> readRules(Map<String, Object> root, List<String> fallback, boolean strict) {
		if (root == null || !root.containsKey("rules") || root.get("rules") == null) {
			return fallback;
		}
		Object value = root.get("rules");
		if (!(value instanceof List<?> rawList)) {
			if (strict) {
				throw new IllegalArgumentException("rules must be a YAML list of strings");
			}
			return fallback;
		}
		ArrayList<String> rules = new ArrayList<>(rawList.size());
		for (Object entry : rawList) {
			if (entry == null) {
				continue;
			}
			if (strict && (entry instanceof Map<?, ?> || entry instanceof List<?>)) {
				throw new IllegalArgumentException("rules entries must be scalar strings");
			}
			String text = String.valueOf(entry).strip();
			if (!text.isEmpty()) {
				rules.add(text);
			}
		}
		return rules.isEmpty() ? fallback : List.copyOf(rules);
	}

	private static void ensureFile(Path path) throws IOException {
		if (Files.exists(path)) {
			return;
		}

		try (InputStream stream = CommonsenseLoader.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
			if (stream == null) {
				throw new IOException("Missing embedded commonsense template: " + TEMPLATE_RESOURCE);
			}
			Files.copy(stream, path);
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> parseYaml(Reader reader) {
		Object loaded = YAML.load(reader);
		if (loaded instanceof Map<?, ?> map) {
			Map<String, Object> typed = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				typed.put(String.valueOf(entry.getKey()), entry.getValue());
			}
			return typed;
		}
		return Map.of();
	}

	private static boolean readBoolean(Map<String, Object> root, String fieldName, boolean fallback, boolean strict) {
		if (root == null || !root.containsKey(fieldName) || root.get(fieldName) == null) {
			return fallback;
		}
		Object value = root.get(fieldName);
		if (value instanceof Boolean booleanValue) {
			return booleanValue;
		}
		String text = String.valueOf(value);
		if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
			return Boolean.parseBoolean(text);
		}
		if (strict) {
			throw new IllegalArgumentException(fieldName + " must be true or false");
		}
		return Boolean.parseBoolean(text);
	}

	private static Yaml createYaml() {
		DumperOptions options = new DumperOptions();
		options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
		options.setPrettyFlow(true);
		options.setIndent(2);
		return new Yaml(options);
	}

	private static String nonEmpty(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}
}
