package ai.moeru.airicraft.agent.evaluation;

public record EvaluationCheckResult(
	String type,
	boolean passed,
	String message
) {
	public static EvaluationCheckResult passed(EvaluationCheck check, String message) {
		return new EvaluationCheckResult(check.type(), true, message);
	}

	public static EvaluationCheckResult failed(EvaluationCheck check, String message) {
		return new EvaluationCheckResult(check.type(), false, message);
	}
}
