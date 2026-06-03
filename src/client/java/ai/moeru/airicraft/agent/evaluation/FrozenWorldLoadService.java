package ai.moeru.airicraft.agent.evaluation;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class FrozenWorldLoadService {
	private final Path gameDir;
	private final EvaluationWorldFixtureService fixtureService;

	public FrozenWorldLoadService(Path gameDir, EvaluationWorldFixtureService fixtureService) {
		this.gameDir = gameDir.toAbsolutePath().normalize();
		this.fixtureService = fixtureService;
	}

	public static FrozenWorldLoadService createDefault() {
		Path gameDir = FabricLoader.getInstance().getGameDir();
		return new FrozenWorldLoadService(gameDir, EvaluationWorldFixtureService.createDefault());
	}

	public WorldFixtureStatus statusForDirectory(String directoryName) {
		String saveDirectory = normalizeSaveDirectory(directoryName);
		if (saveDirectory == null) {
			return WorldFixtureStatus.none();
		}
		if (isDisposableWorld(saveDirectory)) {
			return new WorldFixtureStatus(WorldFixtureState.DISPOSABLE_COPY, null, null, null);
		}

		Optional<EvaluationScenario> scenario = findScenario(saveDirectory);
		if (scenario.isEmpty()) {
			return WorldFixtureStatus.none();
		}

		EvaluationScenario current = scenario.get();
		Path configPath = fixtureService.repository().configPath(current.id());
		Path archivePath = fixtureService.repository().archivePath(current);
		if (!current.frozen()) {
			return new WorldFixtureStatus(WorldFixtureState.UNFROZEN, current.id(), configPath, archivePath);
		}
		if (Files.notExists(archivePath)) {
			return new WorldFixtureStatus(WorldFixtureState.FROZEN_MISSING_ARCHIVE, current.id(), configPath, archivePath);
		}
		return new WorldFixtureStatus(WorldFixtureState.FROZEN, current.id(), configPath, archivePath);
	}

	public Optional<EvaluationWorldFixtureService.RestoredWorld> restoreFrozenDisposableCopy(String directoryName) {
		WorldFixtureStatus status = statusForDirectory(directoryName);
		if (!status.frozenLoadMustDetour()) {
			return Optional.empty();
		}
		if (status.state() == WorldFixtureState.FROZEN_MISSING_ARCHIVE) {
			throw new EvaluationWorldFixtureService.EvaluationWorldFixtureException(
				"world_archive_not_found",
				"World archive not found for scenario: " + status.scenarioId()
			);
		}

		EvaluationScenario scenario = fixtureService.repository().require(status.scenarioId());
		return Optional.of(fixtureService.restoreScenarioWorld(scenario));
	}

	private Optional<EvaluationScenario> findScenario(String saveDirectory) {
		try {
			return fixtureService.repository().find(saveDirectory);
		}
		catch (EvaluationScenarioRepository.EvaluationScenarioRepositoryException exception) {
			if ("invalid_scenario_id".equals(exception.code())) {
				return Optional.empty();
			}
			throw exception;
		}
	}

	private boolean isDisposableWorld(String saveDirectory) {
		Path savesDir = gameDir.resolve("saves").normalize();
		Path worldDir = savesDir.resolve(saveDirectory).normalize();
		if (!worldDir.startsWith(savesDir)) {
			return false;
		}
		return Files.exists(worldDir.resolve(EvaluationWorldFixtureService.METADATA_FILENAME));
	}

	private static String normalizeSaveDirectory(String directoryName) {
		String value = directoryName == null ? "" : directoryName.trim();
		return value.isBlank() ? null : value;
	}

	public enum WorldFixtureState {
		NONE,
		UNFROZEN,
		FROZEN,
		FROZEN_MISSING_ARCHIVE,
		DISPOSABLE_COPY
	}

	public record WorldFixtureStatus(WorldFixtureState state, String scenarioId, Path configPath, Path archivePath) {
		public static WorldFixtureStatus none() {
			return new WorldFixtureStatus(WorldFixtureState.NONE, null, null, null);
		}

		public boolean frozenLoadMustDetour() {
			return state == WorldFixtureState.FROZEN || state == WorldFixtureState.FROZEN_MISSING_ARCHIVE;
		}

		public Optional<String> menuLabel() {
			return switch (state) {
				case UNFROZEN -> Optional.of("Airicraft Unfrozen");
				case FROZEN -> Optional.of("Airicraft Frozen");
				case FROZEN_MISSING_ARCHIVE -> Optional.of("Airicraft Frozen Missing Archive");
				case DISPOSABLE_COPY -> Optional.of("Airicraft Eval Copy");
				case NONE -> Optional.empty();
			};
		}
	}
}
