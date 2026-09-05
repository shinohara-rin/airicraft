package ai.moeru.airicraft.agent.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrozenWorldLoadServiceTest {
	@Test
	void frozenScenarioDetoursToDisposableCopy(@TempDir Path tempDir) throws Exception {
		Path gameDir = tempDir.resolve("run");
		Path scenarios = tempDir.resolve("scenarios");
		Path world = tempDir.resolve("aa");
		Files.createDirectories(world);
		Files.writeString(world.resolve("level.dat"), "baseline", StandardCharsets.UTF_8);
		EvaluationWorldFixtureService fixtureService = new EvaluationWorldFixtureService(gameDir, new EvaluationScenarioRepository(scenarios));
		fixtureService.freezeWorld(world, "aa", () -> {
		});
		FrozenWorldLoadService service = new FrozenWorldLoadService(gameDir, fixtureService);

		FrozenWorldLoadService.WorldFixtureStatus status = service.statusForDirectory("aa");

		assertEquals(FrozenWorldLoadService.WorldFixtureState.FROZEN, status.state());
		assertTrue(status.frozenLoadMustDetour());
		assertEquals("Airicraft Frozen", status.menuLabel().orElseThrow());

		EvaluationWorldFixtureService.RestoredWorld restored = service.restoreFrozenDisposableCopy("aa").orElseThrow();
		assertTrue(restored.worldName().startsWith("airicraft_eval_aa_"));
		assertEquals("baseline", Files.readString(restored.path().resolve("level.dat"), StandardCharsets.UTF_8));
		assertTrue(Files.exists(restored.path().resolve(EvaluationWorldFixtureService.METADATA_FILENAME)));
	}

	@Test
	void unfrozenScenarioDisplaysEditableStateAndDoesNotDetour(@TempDir Path tempDir) throws Exception {
		Path gameDir = tempDir.resolve("run");
		Path scenarios = tempDir.resolve("scenarios");
		Path world = tempDir.resolve("aa");
		Files.createDirectories(world);
		Files.writeString(world.resolve("level.dat"), "baseline", StandardCharsets.UTF_8);
		EvaluationScenarioRepository repository = new EvaluationScenarioRepository(scenarios);
		EvaluationWorldFixtureService fixtureService = new EvaluationWorldFixtureService(gameDir, repository);
		fixtureService.freezeWorld(world, "aa", () -> {
		});
		EvaluationScenario scenario = repository.require("aa");
		repository.write(new EvaluationScenario(
			scenario.id(),
			scenario.name(),
			scenario.minecraftVersion(),
			scenario.airicraftVersion(),
			scenario.configPath(),
			scenario.worldArchive(),
			false,
			scenario.prompt(),
			scenario.budget(),
			scenario.checks(),
			scenario.waypoints(),
			scenario.evidence(),
				scenario.goal()
		));
		FrozenWorldLoadService service = new FrozenWorldLoadService(gameDir, fixtureService);

		FrozenWorldLoadService.WorldFixtureStatus status = service.statusForDirectory("aa");

		assertEquals(FrozenWorldLoadService.WorldFixtureState.UNFROZEN, status.state());
		assertEquals("Airicraft Unfrozen", status.menuLabel().orElseThrow());
		assertTrue(service.restoreFrozenDisposableCopy("aa").isEmpty());
	}

	@Test
	void disposableWorldMetadataDisplaysEvalCopyAndDoesNotDetour(@TempDir Path tempDir) throws Exception {
		Path gameDir = tempDir.resolve("run");
		Path scenarios = tempDir.resolve("scenarios");
		Path disposable = gameDir.resolve("saves").resolve("airicraft_eval_aa_1");
		Files.createDirectories(disposable);
		Files.writeString(disposable.resolve(EvaluationWorldFixtureService.METADATA_FILENAME), "{}", StandardCharsets.UTF_8);
		EvaluationWorldFixtureService fixtureService = new EvaluationWorldFixtureService(gameDir, new EvaluationScenarioRepository(scenarios));
		FrozenWorldLoadService service = new FrozenWorldLoadService(gameDir, fixtureService);

		FrozenWorldLoadService.WorldFixtureStatus status = service.statusForDirectory("airicraft_eval_aa_1");

		assertEquals(FrozenWorldLoadService.WorldFixtureState.DISPOSABLE_COPY, status.state());
		assertEquals("Airicraft Eval Copy", status.menuLabel().orElseThrow());
		assertTrue(service.restoreFrozenDisposableCopy("airicraft_eval_aa_1").isEmpty());
	}

	@Test
	void frozenScenarioMissingArchiveStillBlocksOriginalLoad(@TempDir Path tempDir) throws Exception {
		Path gameDir = tempDir.resolve("run");
		Path scenarios = tempDir.resolve("scenarios");
		EvaluationScenarioRepository repository = new EvaluationScenarioRepository(scenarios);
		repository.write(new EvaluationScenario(
			"aa",
			"aa",
			"1.21.8",
			"dev",
			null,
			"world.zip",
			true,
			"",
			EvaluationBudget.defaults(),
			java.util.List.of(),
			java.util.List.of(),
			EvaluationEvidenceSettings.defaults(),
			null
		));
		EvaluationWorldFixtureService fixtureService = new EvaluationWorldFixtureService(gameDir, repository);
		FrozenWorldLoadService service = new FrozenWorldLoadService(gameDir, fixtureService);

		FrozenWorldLoadService.WorldFixtureStatus status = service.statusForDirectory("aa");

		assertEquals(FrozenWorldLoadService.WorldFixtureState.FROZEN_MISSING_ARCHIVE, status.state());
		assertTrue(status.frozenLoadMustDetour());
		assertEquals("Airicraft Frozen Missing Archive", status.menuLabel().orElseThrow());
		EvaluationWorldFixtureService.EvaluationWorldFixtureException exception = org.junit.jupiter.api.Assertions.assertThrows(
			EvaluationWorldFixtureService.EvaluationWorldFixtureException.class,
			() -> service.restoreFrozenDisposableCopy("aa")
		);
		assertEquals("world_archive_not_found", exception.code());
	}
}
