package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.session.SessionSnapshot;

import java.util.Optional;

public final class SmeltingTaskExecutor implements WorldTaskExecutor {
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
		if (activeTask.isEmpty()) {
			snapshot = TaskExecutionSnapshot.idle();
			return Optional.empty();
		}
		WorldTaskRequest request = activeTask.get();
		if (request.type() != WorldTaskType.SMELT_ITEMS && request.type() != WorldTaskType.COLLECT_SMELTED_ITEMS) {
			snapshot = TaskExecutionSnapshot.idle();
			return Optional.empty();
		}
		if (sessionSnapshot != null && !sessionSnapshot.companionActuationAllowed()) {
			snapshot = new TaskExecutionSnapshot(
				TaskExecutionState.PAUSED_BY_SESSION_GATE,
				request.taskId(),
				null,
				"smelting",
				"session_gate",
				null,
				null
			);
			return Optional.empty();
		}
		String event = request.type() == WorldTaskType.SMELT_ITEMS ? "smelting_started" : "smelting_collected";
		snapshot = new TaskExecutionSnapshot(
			TaskExecutionState.COMPLETED,
			request.taskId(),
			null,
			"smelting",
			event,
			null,
			TaskTerminationCause.GOAL_REACHED
		);
		return Optional.of(new TaskTerminalEvent(
			request.taskId(),
			null,
			TaskExecutionState.COMPLETED,
			event,
			TaskTerminationCause.GOAL_REACHED
		));
	}

	@Override
	public TaskExecutionSnapshot snapshot() {
		return snapshot;
	}

	@Override
	public void onWorldLeave() {
		snapshot = TaskExecutionSnapshot.idle();
	}

	@Override
	public void shutdown() {
		snapshot = TaskExecutionSnapshot.idle();
	}
}
