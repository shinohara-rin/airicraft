package ai.moeru.airicraft.agent.llm;

public enum PlannerContextEntryType {
	USER_TURN,
	ASSISTANT_TURN,
	TOOL_REQUEST,
	TOOL_RESULT,
	NOTICE
}
