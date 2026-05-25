package ai.moeru.airicraft.agent.idle;

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

public final class IdleIdeasLoader {
	private static final Yaml YAML = createYaml();
	private static final String TEMPLATE_RESOURCE = "/config/airicraft/idle-ideas.yml.example";
	private static final String TEMPLATE_FILENAME = "idle-ideas.yml.example";
	private static final String CONFIG_FILENAME = "idle-ideas.yml";

	private IdleIdeasLoader() {
	}

	public static IdleIdeasConfig load() {
		try {
			return loadInternal(false);
		}
		catch (ConfigLoadException exception) {
			Airicraft.LOGGER.warn("Failed to load idle ideas config; using defaults", exception);
			return IdleIdeasConfig.defaults();
		}
	}

	public static IdleIdeasConfig loadStrict() throws ConfigLoadException {
		return loadInternal(true);
	}

	private static IdleIdeasConfig loadInternal(boolean strict) throws ConfigLoadException {
		IdleIdeasConfig defaults = IdleIdeasConfig.defaults();
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

	static IdleIdeasConfig fromMap(Map<String, Object> root, IdleIdeasConfig defaults) {
		return fromMap(root, defaults, false);
	}

	static IdleIdeasConfig fromMapStrict(Map<String, Object> root, IdleIdeasConfig defaults) {
		return fromMap(root, defaults, true);
	}

	private static IdleIdeasConfig fromMap(Map<String, Object> root, IdleIdeasConfig defaults, boolean strict) {
		return new IdleIdeasConfig(
			readBoolean(root, "enabled", defaults.enabled(), strict),
			readInt(root, "initialDelaySeconds", defaults.initialDelaySeconds()),
			readInt(root, "cooldownSeconds", defaults.cooldownSeconds()),
			readIdeas(root, defaults.ideas(), strict)
		);
	}

	private static List<String> readIdeas(Map<String, Object> root, List<String> fallback, boolean strict) {
		if (root == null || !root.containsKey("ideas") || root.get("ideas") == null) {
			return fallback;
		}
		Object value = root.get("ideas");
		if (!(value instanceof List<?> rawList)) {
			if (strict) {
				throw new IllegalArgumentException("ideas must be a YAML list of strings");
			}
			return fallback;
		}
		ArrayList<String> ideas = new ArrayList<>(rawList.size());
		for (Object entry : rawList) {
			if (entry == null) {
				continue;
			}
			if (strict && (entry instanceof Map<?, ?> || entry instanceof List<?>)) {
				throw new IllegalArgumentException("ideas entries must be scalar strings");
			}
			String text = String.valueOf(entry).strip();
			if (!text.isEmpty()) {
				ideas.add(text);
			}
		}
		return ideas.isEmpty() ? fallback : List.copyOf(ideas);
	}

	private static void ensureFile(Path path) throws IOException {
		if (Files.exists(path)) {
			return;
		}

		try (InputStream stream = IdleIdeasLoader.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
			if (stream == null) {
				throw new IOException("Missing embedded idle ideas template: " + TEMPLATE_RESOURCE);
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

	private static int readInt(Map<String, Object> root, String fieldName, int fallback) {
		if (root == null || !root.containsKey(fieldName) || root.get(fieldName) == null) {
			return fallback;
		}
		Object value = root.get(fieldName);
		if (value instanceof Number number) {
			return number.intValue();
		}
		return Integer.parseInt(String.valueOf(value));
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
