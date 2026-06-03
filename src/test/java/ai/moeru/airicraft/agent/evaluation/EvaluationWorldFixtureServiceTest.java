package ai.moeru.airicraft.agent.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationWorldFixtureServiceTest {
	@Test
	void freezeCreatesFrozenScenarioArchiveAndRefusesOverwrite(@TempDir Path tempDir) throws Exception {
		Path gameDir = tempDir.resolve("run");
		Path scenarios = tempDir.resolve("scenarios");
		Path world = tempDir.resolve("setup-world");
		Files.createDirectories(world.resolve("region"));
		Files.writeString(world.resolve("level.dat"), "level", StandardCharsets.UTF_8);
		Files.writeString(world.resolve("region").resolve("r.0.0.mca"), "region", StandardCharsets.UTF_8);
		Files.writeString(world.resolve("session.lock"), "lock", StandardCharsets.UTF_8);
		EvaluationWorldFixtureService service = new EvaluationWorldFixtureService(gameDir, new EvaluationScenarioRepository(scenarios));

		EvaluationWorldFixtureService.FreezeResult result = service.freezeWorld(world, "Smelting World", () -> {
		});

		assertEquals("smelting-world", result.scenarioId());
		assertTrue(result.frozen());
		assertTrue(Files.exists(scenarios.resolve("smelting-world").resolve("scenario.yml")));
		assertTrue(Files.exists(scenarios.resolve("smelting-world").resolve("world.zip")));
		EvaluationScenario loaded = EvaluationScenarioLoader.load(scenarios.resolve("smelting-world").resolve("scenario.yml"));
		assertTrue(loaded.frozen());

		EvaluationWorldFixtureService.EvaluationWorldFixtureException exception = assertThrows(
			EvaluationWorldFixtureService.EvaluationWorldFixtureException.class,
			() -> service.freezeWorld(world, "Smelting World", () -> {
			})
		);
		assertEquals("scenario_frozen", exception.code());
	}

	@Test
	void restoreScenarioWorldCreatesDisposableCopyWithMetadata(@TempDir Path tempDir) throws Exception {
		Path gameDir = tempDir.resolve("run");
		Path scenarios = tempDir.resolve("scenarios");
		Path world = tempDir.resolve("setup-world");
		Files.createDirectories(world);
		Files.writeString(world.resolve("level.dat"), "level", StandardCharsets.UTF_8);
		EvaluationWorldFixtureService service = new EvaluationWorldFixtureService(gameDir, new EvaluationScenarioRepository(scenarios));
		service.freezeWorld(world, "smelting", () -> {
		});
		EvaluationScenario scenario = service.repository().require("smelting");

		EvaluationWorldFixtureService.RestoredWorld restored = service.restoreScenarioWorld(scenario);

		assertEquals("smelting", restored.scenarioId());
		assertTrue(restored.worldName().startsWith("airicraft_eval_smelting_"));
		assertTrue(Files.exists(restored.path().resolve("level.dat")));
		String metadata = Files.readString(restored.path().resolve(EvaluationWorldFixtureService.METADATA_FILENAME), StandardCharsets.UTF_8);
		assertTrue(metadata.contains("\"scenarioId\":\"smelting\""));
		assertTrue(metadata.contains("scenario.yml"));
	}
}
