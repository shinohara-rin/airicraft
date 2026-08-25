package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonPrimitive;

public record PlannerExecutionResult(
	PlannerRequest request,
	PlannerResponse response,
	LlmUsageSnapshot usage,
	LlmFailureType failureType,
	String failureMessage,
	long generation,
	int attempt,
	PlannerSessionPhase phase,
	boolean stale
) {
	public PlannerExecutionResult(
		PlannerRequest request,
		PlannerResponse response,
		LlmUsageSnapshot usage,
		LlmFailureType failureType,
		String failureMessage
	) {
		this(request, response, usage, failureType, failureMessage, 0L, 1, PlannerSessionPhase.PLANNER_REQUEST, false);
	}

	public boolean succeeded() {
		return failureType == null;
	}

	public static PlannerExecutionResult discarded(
		PlannerRequest request,
		long generation,
		int attempt,
		PlannerSessionPhase phase
	) {
		return new PlannerExecutionResult(
			request,
			new PlannerResponse(
				"",
				new PlannerIntent("none", null, null),
				null,
				null,
				new JsonPrimitive("PLANNER OFF")
			),
			LlmUsageSnapshot.unknown(),
			null,
			null,
			generation,
			attempt,
			phase,
			false
		);
	}
}
