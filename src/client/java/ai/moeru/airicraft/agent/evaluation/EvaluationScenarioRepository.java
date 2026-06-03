package ai.moeru.airicraft.agent.evaluation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public final class EvaluationScenarioRepository {
	private static final String SCENARIO_CONFIG = "scenario.yml";

	private final Path root;

	public EvaluationScenarioRepository(Path root) {
		this.root = root.toAbsolutePath().normalize();
	}

	public Path root() {
		return root;
	}

	public List<EvaluationScenario> list() {
		if (Files.notExists(root)) {
			return List.of();
		}
		try (Stream<Path> stream = Files.list(root)) {
			ArrayList<EvaluationScenario> scenarios = new ArrayList<>();
			for (Path dir : stream.filter(Files::isDirectory).sorted(Comparator.comparing(Path::toString)).toList()) {
				Path config = dir.resolve(SCENARIO_CONFIG);
				if (Files.exists(config)) {
					scenarios.add(EvaluationScenarioLoader.load(config));
				}
			}
			return List.copyOf(scenarios);
		}
		catch (IOException exception) {
			throw new EvaluationScenarioRepositoryException("scenario_list_failed", "Failed to list evaluation scenarios", exception);
		}
	}

	public Optional<EvaluationScenario> find(String scenarioId) {
		String normalized = normalizeScenarioId(scenarioId);
		Path config = configPath(normalized);
		if (Files.notExists(config)) {
			return Optional.empty();
		}
		try {
			return Optional.of(EvaluationScenarioLoader.load(config));
		}
		catch (IOException exception) {
			throw new EvaluationScenarioRepositoryException("scenario_load_failed", "Failed to load scenario: " + normalized, exception);
		}
	}

	public EvaluationScenario require(String scenarioId) {
		return find(scenarioId)
			.orElseThrow(() -> new EvaluationScenarioRepositoryException("scenario_not_found", "Evaluation scenario not found: " + scenarioId));
	}

	public void write(EvaluationScenario scenario) {
		try {
			EvaluationScenarioLoader.write(configPath(scenario.id()), scenario);
		}
		catch (IOException exception) {
			throw new EvaluationScenarioRepositoryException("scenario_write_failed", "Failed to write scenario: " + scenario.id(), exception);
		}
	}

	public Path scenarioDir(String scenarioId) {
		return root.resolve(normalizeScenarioId(scenarioId)).normalize();
	}

	public Path configPath(String scenarioId) {
		return scenarioDir(scenarioId).resolve(SCENARIO_CONFIG);
	}

	public Path archivePath(EvaluationScenario scenario) {
		return scenarioDir(scenario.id()).resolve(scenario.worldArchive()).normalize();
	}

	public static String normalizeScenarioId(String value) {
		String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
		normalized = normalized.replaceAll("[^a-z0-9._-]+", "-");
		normalized = normalized.replaceAll("(^[-.]+|[-.]+$)", "");
		if (normalized.isBlank()) {
			throw new EvaluationScenarioRepositoryException("invalid_scenario_id", "Scenario id is required");
		}
		return normalized;
	}

	public static final class EvaluationScenarioRepositoryException extends RuntimeException {
		private final String code;

		public EvaluationScenarioRepositoryException(String code, String message) {
			super(message);
			this.code = code;
		}

		public EvaluationScenarioRepositoryException(String code, String message, Throwable cause) {
			super(message, cause);
			this.code = code;
		}

		public String code() {
			return code;
		}
	}
}
