package ai.moeru.airicraft.agent.llm;

public interface PlannerToolNarrationSink {
	PlannerToolNarrationSink NO_OP = toolCall -> {
	};

	void onToolNarration(PlannerToolCall toolCall);
}
