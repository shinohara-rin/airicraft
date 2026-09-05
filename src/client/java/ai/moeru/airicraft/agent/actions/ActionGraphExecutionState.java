package ai.moeru.airicraft.agent.actions;

public enum ActionGraphExecutionState {
	IDLE,
	RESOLVING,
	READY,
	DISPATCHING,
	WAITING_PRIMITIVE,
	OBSERVING,
	WATCHING,
	REPLANNING,
	BLOCKED,
	REPLAN_REQUIRED,
	SUCCEEDED,
	FAILED,
	CANCELLED
}
