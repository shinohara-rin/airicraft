package ai.moeru.airicraft.agent.evaluation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
		private Optional<String> declaredFailure = Optional.empty();
		private final ArrayList<String> triggers = new ArrayList<>();

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
			return true;
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
			return "minecraft:air";
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
