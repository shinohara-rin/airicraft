package ai.moeru.airicraft.agent.evaluation;

public record EvaluationEvidenceSettings(
	boolean includePlannerJournal,
	boolean includeDebugTimeline,
	boolean includeRecentEvents,
	boolean includeTaskState,
	boolean includeWorldSnapshot
) {
	public static EvaluationEvidenceSettings defaults() {
		return new EvaluationEvidenceSettings(true, true, true, true, true);
	}
}
