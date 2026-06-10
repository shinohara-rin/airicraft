package ai.moeru.airicraft.agent.evaluation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioEvaluationRunnerTest {
	@Test
	void emitsInitialPromptAndHeartbeatUntilCheckPasses() {
		ScenarioEvaluationRunner runner = new ScenarioEvaluationRunner();
		FakeContext context = new FakeContext();
		EvaluationScenario scenario = scenario(List.of(new EvaluationCheck("inventory_contains", Map.of(
			"itemId", "minecraft:iron_ingot",
			"count", 1
		))), new EvaluationBudget(4, 200, 0, 5));

		runner.start(scenario, 0, 0);
		runner.onTick(context);
		assertEquals(List.of("initial:@agent smelt iron"), context.triggers);
		assertEquals(EvaluationStatus.RUNNING, runner.report(context.tick).status());

		context.tick = 5;
		runner.onTick(context);
		assertEquals("heartbeat:EVALUATION HEARTBEAT: Continue working on scenario smelting-basic. Stop only when the expected outcome is reached, the task is impossible, or you need to report a blocking failure.", context.triggers.get(1));

		context.inventoryCount = 1;
		context.tick = 6;
		runner.onTick(context);
		assertEquals(EvaluationStatus.PASSED, runner.report(context.tick).status());
	}

	@Test
	void waitsForWorldBeforeEmittingInitialPrompt() {
		ScenarioEvaluationRunner runner = new ScenarioEvaluationRunner();
		FakeContext context = new FakeContext();
		context.worldLoaded = false;
		EvaluationScenario scenario = scenario(List.of(new EvaluationCheck("inventory_contains", Map.of(
			"itemId", "minecraft:iron_ingot",
			"count", 1
		))), new EvaluationBudget(4, 200, 0, 5));

		runner.start(scenario, 0, 0);
		runner.onTick(context);

		EvaluationReport pendingReport = runner.report(context.tick);
		assertEquals(EvaluationStatus.PENDING_WORLD, pendingReport.status());
		assertEquals("Waiting for evaluation world", pendingReport.message());
		assertTrue(context.triggers.isEmpty());

		context.tick = 3;
		context.worldLoaded = true;
		runner.onTick(context);

		assertEquals(EvaluationStatus.RUNNING, runner.report(context.tick).status());
		assertEquals(List.of("initial:@agent smelt iron"), context.triggers);
	}

	@Test
	void failsWhenWorldLoadBudgetIsExhaustedBeforePrompt() {
		ScenarioEvaluationRunner runner = new ScenarioEvaluationRunner();
		FakeContext context = new FakeContext();
		context.worldLoaded = false;
		EvaluationScenario scenario = scenario(List.of(new EvaluationCheck("inventory_contains", Map.of(
			"itemId", "minecraft:iron_ingot",
			"count", 1
		))), new EvaluationBudget(4, 5, 0, 5));

		runner.start(scenario, 0, 0);
		context.tick = 5;
		runner.onTick(context);

		EvaluationReport report = runner.report(context.tick);
		assertEquals(EvaluationStatus.FAILED, report.status());
		assertEquals("Evaluation world did not load before budget was exhausted", report.message());
		assertTrue(report.evidenceReviewRequired());
		assertTrue(context.triggers.isEmpty());
	}

	@Test
	void freezesReportAndStopsTriggersAfterTerminalStatus() {
		ScenarioEvaluationRunner runner = new ScenarioEvaluationRunner();
		FakeContext context = new FakeContext();
		EvaluationScenario scenario = scenario(List.of(new EvaluationCheck("inventory_contains", Map.of(
			"itemId", "minecraft:iron_ingot",
			"count", 1
		))), new EvaluationBudget(4, 200, 0, 5));

		runner.start(scenario, 0, 0);
		runner.onTick(context);
		context.tick = 6;
		context.inventoryCount = 1;
		runner.onTick(context);

		EvaluationReport terminalReport = runner.report(context.tick);
		assertEquals(EvaluationStatus.PASSED, terminalReport.status());
		assertEquals(6, terminalReport.elapsedTicks());
		assertTrue(runner.terminal());
		assertEquals(1, context.triggers.size());

		context.tick = 100;
		runner.onTick(context);

		assertEquals(1, context.triggers.size());
		assertEquals(6, runner.report(context.tick).elapsedTicks());
	}

	@Test
	void failsWhenPlannerTurnBudgetIsExhaustedBeforeCheckPasses() {
		ScenarioEvaluationRunner runner = new ScenarioEvaluationRunner();
		FakeContext context = new FakeContext();
		EvaluationScenario scenario = scenario(List.of(new EvaluationCheck("inventory_contains", Map.of(
			"itemId", "minecraft:iron_ingot",
			"count", 1
		))), new EvaluationBudget(1, 200, 0, 5));

		runner.start(scenario, 0, 0);
		runner.onTick(context);
		runner.onTick(context);

		EvaluationReport report = runner.report(context.tick);
		assertEquals(EvaluationStatus.FAILED, report.status());
		assertTrue(report.evidenceReviewRequired());
		assertEquals("Evaluation budget exhausted before expected outcome", report.message());
	}

	@Test
	void passesBlockCountWithinSelfRadius() {
		ScenarioEvaluationRunner runner = new ScenarioEvaluationRunner();
		FakeContext context = new FakeContext();
		context.playerBlockX = 10;
		context.playerBlockY = 64;
		context.playerBlockZ = -4;
		context.setBlock(9, 64, -4, "minecraft:wheat");
		context.setBlock(10, 64, -4, "minecraft:wheat");
		context.setBlock(11, 64, -4, "minecraft:wheat");
		context.setBlock(20, 64, -4, "minecraft:wheat");
		EvaluationScenario scenario = scenario(List.of(new EvaluationCheck("block_count", Map.of(
			"blockId", "minecraft:wheat",
			"count", 3,
			"scope", "self",
			"horizontalRadius", 1,
			"verticalRadius", 0
		))), new EvaluationBudget(4, 200, 0, 5));

		runner.start(scenario, 0, 0);
		runner.onTick(context);

		EvaluationReport report = runner.report(context.tick);
		assertEquals(EvaluationStatus.PASSED, report.status());
		assertTrue(report.checks().getFirst().message().contains("found 3x minecraft:wheat"));
	}

	@Test
	void passesBlockCountWithinBox() {
		ScenarioEvaluationRunner runner = new ScenarioEvaluationRunner();
		FakeContext context = new FakeContext();
		context.setBlock(2, 65, 2, "minecraft:wheat");
		context.setBlock(3, 65, 2, "minecraft:wheat");
		context.setBlock(10, 65, 2, "minecraft:wheat");
		EvaluationScenario scenario = scenario(List.of(new EvaluationCheck("block_count", Map.of(
			"blockId", "minecraft:wheat",
			"count", 2,
			"scope", "box",
			"x1", 3,
			"y1", 65,
			"z1", 2,
			"x2", 2,
			"y2", 65,
			"z2", 2
		))), new EvaluationBudget(4, 200, 0, 5));

		runner.start(scenario, 0, 0);
		runner.onTick(context);

		assertEquals(EvaluationStatus.PASSED, runner.report(context.tick).status());
	}

	@Test
	void blockStateCanRequireExactStateProperties() {
		ScenarioEvaluationRunner runner = new ScenarioEvaluationRunner();
		FakeContext context = new FakeContext();
		context.setBlock(1, 64, 1, "minecraft:water", Map.of("level", "8"));
		EvaluationScenario scenario = scenario(List.of(new EvaluationCheck("block_state", Map.of(
			"blockId", "minecraft:water",
			"x", 1,
			"y", 64,
			"z", 1,
			"state", Map.of("level", "0")
		))), new EvaluationBudget(1, 200, 0, 5));

		runner.start(scenario, 0, 0);
		runner.onTick(context);

		EvaluationReport report = runner.report(context.tick);
		assertEquals(EvaluationStatus.FAILED, report.status());
		assertTrue(report.checks().getFirst().message().contains("state level was 8, expected 0"));
	}

	@Test
	void blockCountCanRequireExactStateProperties() {
		ScenarioEvaluationRunner runner = new ScenarioEvaluationRunner();
		FakeContext context = new FakeContext();
		context.setBlock(2, 65, 2, "minecraft:wheat", Map.of("age", "0"));
		context.setBlock(3, 65, 2, "minecraft:wheat", Map.of("age", "7"));
		EvaluationScenario scenario = scenario(List.of(new EvaluationCheck("block_count", Map.of(
			"blockId", "minecraft:wheat",
			"count", 1,
			"scope", "box",
			"x1", 2,
			"y1", 65,
			"z1", 2,
			"x2", 3,
			"y2", 65,
			"z2", 2,
			"state", Map.of("age", "7")
		))), new EvaluationBudget(4, 200, 0, 5));

		runner.start(scenario, 0, 0);
		runner.onTick(context);

		EvaluationReport report = runner.report(context.tick);
		assertEquals(EvaluationStatus.PASSED, report.status());
		assertTrue(report.checks().getFirst().message().contains("found 1x minecraft:wheat state={age=7}"));
	}

	@Test
	void marksScenarioForReviewWhenNoDeterministicChecksExist() {
		ScenarioEvaluationRunner runner = new ScenarioEvaluationRunner();
		FakeContext context = new FakeContext();
		EvaluationScenario scenario = scenario(List.of(new EvaluationCheck("external_judge", Map.of())), new EvaluationBudget(1, 20, 0, 5));

		runner.start(scenario, 0, 0);
		runner.onTick(context);
		runner.onTick(context);

		EvaluationReport report = runner.report(context.tick);
		assertEquals(EvaluationStatus.NEEDS_REVIEW, report.status());
		assertTrue(report.evidenceReviewRequired());
	}

	private static EvaluationScenario scenario(List<EvaluationCheck> checks, EvaluationBudget budget) {
		return new EvaluationScenario(
			"smelting-basic",
			"Smelting basic",
			"1.21.8",
			"dev",
			null,
			"world.zip",
			true,
			"@agent smelt iron",
			budget,
			checks,
			EvaluationEvidenceSettings.defaults()
		);
	}

	private static final class FakeContext implements ScenarioEvaluationRunner.Context {
		private long tick;
		private int inventoryCount;
		private boolean plannerInFlight;
		private boolean worldLoaded = true;
		private int playerBlockX;
		private int playerBlockY;
		private int playerBlockZ;
		private final Map<String, String> blocks = new HashMap<>();
		private final Map<String, Map<String, String>> blockProperties = new HashMap<>();
		private Optional<String> declaredFailure = Optional.empty();
		private final ArrayList<String> triggers = new ArrayList<>();

		private void setBlock(int x, int y, int z, String blockId) {
			blocks.put(x + "," + y + "," + z, blockId);
		}

		private void setBlock(int x, int y, int z, String blockId, Map<String, String> properties) {
			String key = x + "," + y + "," + z;
			blocks.put(key, blockId);
			blockProperties.put(key, Map.copyOf(properties));
		}

		@Override
		public long tick() {
			return tick;
		}

		@Override
		public long nowMs() {
			return tick * 50L;
		}

		@Override
		public boolean worldLoaded() {
			return worldLoaded;
		}

		@Override
		public boolean plannerConfigured() {
			return true;
		}

		@Override
		public boolean plannerInFlight() {
			return plannerInFlight;
		}

		@Override
		public Optional<String> declaredFailure() {
			return declaredFailure;
		}

		@Override
		public int inventoryCount(String itemId) {
			return inventoryCount;
		}

		@Override
		public String blockIdAt(int x, int y, int z) {
			return blocks.getOrDefault(x + "," + y + "," + z, "minecraft:air");
		}

		@Override
		public Map<String, String> blockPropertiesAt(int x, int y, int z) {
			return blockProperties.getOrDefault(x + "," + y + "," + z, Map.of());
		}

		@Override
		public int playerBlockX() {
			return playerBlockX;
		}

		@Override
		public int playerBlockY() {
			return playerBlockY;
		}

		@Override
		public int playerBlockZ() {
			return playerBlockZ;
		}

		@Override
		public boolean eventContains(String eventType) {
			return false;
		}

		@Override
		public String lastChatText() {
			return "";
		}

		@Override
		public String taskState() {
			return "IDLE";
		}

		@Override
		public String taskExecutionState() {
			return "IDLE";
		}

		@Override
		public void emitInitialPrompt(String prompt) {
			triggers.add("initial:" + prompt);
		}

		@Override
		public void emitHeartbeat(String message) {
			triggers.add("heartbeat:" + message);
		}
	}
}
