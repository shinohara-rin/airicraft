package ai.moeru.airicraft.evaluator;

import ai.moeru.airicraft.Airicraft;
import ai.moeru.airicraft.agent.EmbodiedAgentRuntime;
import ai.moeru.airicraft.agent.evaluation.EvaluationReport;
import ai.moeru.airicraft.agent.evaluation.EvaluationScenario;
import ai.moeru.airicraft.agent.evaluation.EvaluationStatus;
import ai.moeru.airicraft.agent.evaluation.EvaluationWorldFixtureService;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

public final class EvaluationFlightRecorder {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final long STATUS_SAMPLE_INTERVAL_TICKS = 100L;

	private Path outputDir;
	private EvaluationScenario scenario;
	private String startedAt;
	private long nextStatusSampleTick;
	private Long latestEventSeqNo;
	private Long latestTimelineEntryId;
	private Long latestLlmSequenceId;
	private boolean eventsTruncated;
	private boolean timelineTruncated;
	private boolean llmCallsTruncated;
	private boolean terminalWritten;

	public void start(
		EvaluationScenario nextScenario,
		Path nextOutputDir,
		EvaluationWorldFixtureService.RestoredWorld restoredWorld
	) {
		this.outputDir = nextOutputDir.toAbsolutePath().normalize();
		this.scenario = nextScenario;
		this.startedAt = now();
		this.nextStatusSampleTick = 0L;
		this.latestEventSeqNo = null;
		this.latestTimelineEntryId = null;
		this.latestLlmSequenceId = null;
		this.eventsTruncated = false;
		this.timelineTruncated = false;
		this.llmCallsTruncated = false;
		this.terminalWritten = false;
		try {
			Files.createDirectories(outputDir);
			writeJson(outputDir.resolve("recording-start.json"), Map.of(
				"startedAt", startedAt,
				"scenario", nextScenario.id(),
				"outputDir", outputDir.toString(),
				"worldName", restoredWorld.worldName(),
				"worldPath", restoredWorld.path().toString()
			));
		}
		catch (IOException exception) {
			Airicraft.LOGGER.warn("Failed to initialize evaluation flight recorder", exception);
		}
	}

	public void recordTick(
		EvaluationScenario currentScenario,
		EvaluationReport report,
		EmbodiedAgentRuntime runtime,
		Supplier<Map<String, Object>> evidenceSupplier
	) {
		if (outputDir == null || currentScenario == null || report == null || runtime == null) {
			return;
		}
		if (terminalWritten) {
			return;
		}
		try {
			Files.createDirectories(outputDir);
			String collectedAt = now();
			drainEvents(runtime, collectedAt);
			drainTimeline(runtime, collectedAt);
			drainLlmCalls(runtime, collectedAt);
			boolean terminal = terminal(report.status());
			if (runtime.tickCount() >= nextStatusSampleTick || terminal) {
				appendJsonl(outputDir.resolve("status-samples.jsonl"), Map.of(
					"collectedAt", collectedAt,
					"kind", "evaluation_results",
					"payload", Map.of("available", true, "report", report)
				));
				nextStatusSampleTick = runtime.tickCount() + STATUS_SAMPLE_INTERVAL_TICKS;
			}
			writeSummary(report, terminal ? collectedAt : null);
			if (terminal && !terminalWritten) {
				writeFinalSnapshots(report, runtime, evidenceSupplier);
				terminalWritten = true;
			}
		}
		catch (IOException exception) {
			Airicraft.LOGGER.warn("Failed to record evaluation flight data", exception);
		}
	}

	public Map<String, Object> statusPayload() {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("active", outputDir != null);
		payload.put("outputDir", outputDir == null ? null : outputDir.toString());
		payload.put("scenarioId", scenario == null ? null : scenario.id());
		payload.put("eventsTruncated", eventsTruncated);
		payload.put("timelineTruncated", timelineTruncated);
		payload.put("llmCallsTruncated", llmCallsTruncated);
		return payload;
	}

	public void reset() {
		outputDir = null;
		scenario = null;
		startedAt = null;
		nextStatusSampleTick = 0L;
		latestEventSeqNo = null;
		latestTimelineEntryId = null;
		latestLlmSequenceId = null;
		eventsTruncated = false;
		timelineTruncated = false;
		llmCallsTruncated = false;
		terminalWritten = false;
	}

	private void drainEvents(EmbodiedAgentRuntime runtime, String collectedAt) throws IOException {
		var result = runtime.recentEvents(latestEventSeqNo);
		latestEventSeqNo = result.latestSeqNo();
		eventsTruncated = eventsTruncated || result.truncated();
		for (var event : result.events()) {
			appendJsonl(outputDir.resolve("events.jsonl"), Map.of("collectedAt", collectedAt, "event", event));
		}
	}

	private void drainTimeline(EmbodiedAgentRuntime runtime, String collectedAt) throws IOException {
		var result = runtime.debugTimeline(latestTimelineEntryId);
		latestTimelineEntryId = result.latestEntryId();
		timelineTruncated = timelineTruncated || result.truncated();
		for (var entry : result.entries()) {
			appendJsonl(outputDir.resolve("debug-timeline.jsonl"), Map.of("collectedAt", collectedAt, "entry", entry));
		}
	}

	private void drainLlmCalls(EmbodiedAgentRuntime runtime, String collectedAt) throws IOException {
		var result = runtime.llmFlightRecords(latestLlmSequenceId);
		latestLlmSequenceId = result.latestSequenceId();
		llmCallsTruncated = llmCallsTruncated || result.truncated();
		for (var record : result.records()) {
			appendJsonl(outputDir.resolve("llm-calls.jsonl"), Map.of("collectedAt", collectedAt, "record", record));
		}
	}

	private void writeSummary(EvaluationReport report, String finishedAt) throws IOException {
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("id", scenario.id());
		summary.put("name", scenario.name());
		summary.put("startedAt", startedAt);
		if (finishedAt != null) {
			summary.put("finishedAt", finishedAt);
		}
		summary.put("harnessStatus", "IN_MOD");
		summary.put("reportStatus", report.status().name());
		summary.put("message", report.message());
		summary.put("elapsedTicks", report.elapsedTicks());
		summary.put("plannerTurns", report.plannerTurns());
		summary.put("evidenceReviewRequired", report.evidenceReviewRequired());
		summary.put("outputDir", outputDir.toString());
		summary.put("latestEventSeqNo", latestEventSeqNo);
		summary.put("debugTimelineLatestEntryId", latestTimelineEntryId);
		summary.put("llmCallsLatestSequenceId", latestLlmSequenceId);
		summary.put("eventsTruncated", eventsTruncated);
		summary.put("debugTimelineTruncated", timelineTruncated);
		summary.put("llmCallsTruncated", llmCallsTruncated);
		writeJson(outputDir.resolve("summary.json"), summary);
	}

	private void writeFinalSnapshots(
		EvaluationReport report,
		EmbodiedAgentRuntime runtime,
		Supplier<Map<String, Object>> evidenceSupplier
	) throws IOException {
		writeJson(outputDir.resolve("results-final.json"), Map.of("available", true, "report", report));
		writeJson(outputDir.resolve("evidence-final.json"), evidenceSupplier.get());
		writeJson(outputDir.resolve("agent-status-final.json"), Map.of(
			"available", true,
			"session", runtime.sessionSnapshot(),
			"task", runtime.taskSnapshot(),
			"taskExecution", runtime.taskExecutionSnapshot(),
			"missionExecution", runtime.missionExecutionSnapshot(),
			"activeJob", runtime.activeJob(),
			"degraded", runtime.isDegraded()
		));
		writeJson(outputDir.resolve("agent-events-final.json"), runtime.recentEvents(null));
		writeJson(outputDir.resolve("agent-debug-timeline-final.json"), runtime.debugTimeline(null));
		writeJson(outputDir.resolve("agent-debug-llm-calls-final.json"), runtime.llmFlightRecords(null));
		writeJson(outputDir.resolve("world-evidence-final.json"), runtime.currentWorldEvidence());
	}

	private static boolean terminal(EvaluationStatus status) {
		return status == EvaluationStatus.PASSED || status == EvaluationStatus.FAILED || status == EvaluationStatus.NEEDS_REVIEW;
	}

	private static void appendJsonl(Path path, Object value) throws IOException {
		Files.writeString(
			path,
			GSON.toJson(value) + "\n",
			StandardOpenOption.CREATE,
			StandardOpenOption.APPEND
		);
	}

	private static void writeJson(Path path, Object value) throws IOException {
		Files.writeString(
			path,
			GSON.toJson(value) + "\n",
			StandardOpenOption.CREATE,
			StandardOpenOption.TRUNCATE_EXISTING
		);
	}

	private static String now() {
		return Instant.now().toString();
	}
}
