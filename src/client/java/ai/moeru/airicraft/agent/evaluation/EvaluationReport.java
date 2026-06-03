package ai.moeru.airicraft.agent.evaluation;

import java.util.List;
import java.util.Map;

public record EvaluationReport(
	EvaluationStatus status,
	String scenarioId,
	String message,
	long elapsedTicks,
	int plannerTurns,
	List<EvaluationCheckResult> checks,
	boolean evidenceReviewRequired,
	Map<String, Object> diagnostics
) {
	public EvaluationReport {
		checks = checks == null ? List.of() : List.copyOf(checks);
		diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
	}

	public static EvaluationReport idle() {
		return new EvaluationReport(EvaluationStatus.IDLE, null, null, 0L, 0, List.of(), false, Map.of());
	}
}
