package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.agent.evaluation.EvaluationBudget;
import ai.moeru.airicraft.agent.evaluation.EvaluationCheckResult;
import ai.moeru.airicraft.agent.evaluation.EvaluationEvidenceSettings;
import ai.moeru.airicraft.agent.evaluation.EvaluationReport;
import ai.moeru.airicraft.agent.evaluation.EvaluationScenario;
import ai.moeru.airicraft.agent.evaluation.EvaluationStatus;
import ai.moeru.airicraft.agent.evaluation.EvaluationWorldFixtureService;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskTerminalEvent;
import ai.moeru.airicraft.agent.tasks.WorldTaskExecutor;
import ai.moeru.airicraft.agent.tasks.WorldTaskRequest;
import ai.moeru.airicraft.evaluator.EvaluationFlightRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationFlightRecorderTest {
	@Test
	void writesSamplesSummaryAndTerminalSnapshots(@TempDir Path tempDir) throws Exception {
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(new NoopExecutor());
		EvaluationScenario scenario = scenario();
		EvaluationFlightRecorder recorder = new EvaluationFlightRecorder();
		recorder.start(
			scenario,
			tempDir,
			new EvaluationWorldFixtureService.RestoredWorld("iron-pickaxe", "airicraft_eval_iron-pickaxe", tempDir.resolve("world"))
		);

		recorder.recordTick(
			scenario,
			new EvaluationReport(
				EvaluationStatus.RUNNING,
				scenario.id(),
				"running",
				1L,
				1,
				List.of(),
				false,
				Map.of()
			),
			runtime,
			() -> Map.of("available", true, "evidence", Map.of())
		);
		recorder.recordTick(
			scenario,
			terminalReport(scenario),
			runtime,
			() -> Map.of("available", true, "evidence", Map.of("report", "ok"))
		);
		runtime.finishEvaluation();
		int terminalSampleCount = Files.readAllLines(tempDir.resolve("status-samples.jsonl"), StandardCharsets.UTF_8).size();
		recorder.recordTick(
			scenario,
			new EvaluationReport(
				EvaluationStatus.PASSED,
				scenario.id(),
				"all checks passed",
				3L,
				1,
				List.of(new EvaluationCheckResult("inventory_contains", true, "inventory contains 1x minecraft:iron_pickaxe")),
				false,
				Map.of()
			),
			runtime,
			() -> Map.of("available", true, "evidence", Map.of("report", "ok"))
		);

		String summary = Files.readString(tempDir.resolve("summary.json"), StandardCharsets.UTF_8);
		assertTrue(summary.contains("\"reportStatus\":\"PASSED\""));
		assertTrue(Files.readString(tempDir.resolve("status-samples.jsonl"), StandardCharsets.UTF_8).contains("\"evaluation_results\""));
		assertEquals(terminalSampleCount, Files.readAllLines(tempDir.resolve("status-samples.jsonl"), StandardCharsets.UTF_8).size());
		assertTrue(Files.exists(tempDir.resolve("results-final.json")));
		assertTrue(Files.exists(tempDir.resolve("evidence-final.json")));
		assertTrue(Files.exists(tempDir.resolve("agent-debug-llm-calls-final.json")));
		assertTrue(Files.exists(tempDir.resolve("planner-calls.jsonl")));
		assertEquals("", Files.readString(tempDir.resolve("planner-calls.jsonl"), StandardCharsets.UTF_8));
	}

	private static EvaluationScenario scenario() {
		return new EvaluationScenario(
			"iron-pickaxe",
			"Iron pickaxe",
			"1.21.8",
			"dev",
			null,
			"world.zip",
			true,
			"@agent obtain an iron pickaxe",
			EvaluationBudget.defaults(),
			List.of(),
			List.of(),
			EvaluationEvidenceSettings.defaults(),
			null
		);
	}

	private static EvaluationReport terminalReport(EvaluationScenario scenario) {
		return new EvaluationReport(
			EvaluationStatus.PASSED,
			scenario.id(),
			"all checks passed",
			2L,
			1,
			List.of(new EvaluationCheckResult("inventory_contains", true, "inventory contains 1x minecraft:iron_pickaxe")),
			false,
			Map.of()
		);
	}

	private static final class NoopExecutor implements WorldTaskExecutor {
		@Override
		public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
			return Optional.empty();
		}

		@Override
		public TaskExecutionSnapshot snapshot() {
			return TaskExecutionSnapshot.idle();
		}

		@Override
		public void onWorldLeave() {
		}

		@Override
		public void shutdown() {
		}
	}
}
