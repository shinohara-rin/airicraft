package ai.moeru.airicraft.agent.llm;

public interface PlannerLifecycleListener {
	PlannerLifecycleListener NO_OP = new PlannerLifecycleListener() {
	};

	default void onPlannerTurnSubmitted(PlannerRequest request) {
	}

	default void onConversationSubmitted(
		long generation,
		int attempt,
		PlannerSessionPhase phase,
		PlannerRequest request,
		LlmConversation conversation
	) {
	}

	default void onPlannerExecutionSucceeded(PlannerExecutionResult result) {
	}

	default void onPlannerExecutionFailed(PlannerExecutionResult result) {
	}

	default void onToolRequested(long generation, PlannerToolCall toolCall) {
	}

	default void onToolCompleted(long generation, String toolResult, boolean imageAttached) {
	}

	default void onCompactionCompleted(CompactionExecutionResult result) {
	}

	default void onReset(String reason) {
	}
}
