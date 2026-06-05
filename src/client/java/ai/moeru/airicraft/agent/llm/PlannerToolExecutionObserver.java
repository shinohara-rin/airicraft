package ai.moeru.airicraft.agent.llm;

public interface PlannerToolExecutionObserver {
	PlannerToolExecutionObserver NO_OP = toolCall -> {
	};

	void beforePlannerToolExecution(PlannerToolCall toolCall);
}
