package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.session.SessionSnapshot;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class BaritoneTaskExecutor implements WorldTaskExecutor {
	private final BaritoneFacade facade;

	private WorldTaskRequest appliedTask;
	private String terminalEventTaskId;
	private TaskExecutionState terminalEventState;
	private TaskTerminationCause terminalEventCause;
	private String pendingInternalCancelTaskId;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	public BaritoneTaskExecutor(BaritoneFacade facade) {
		this.facade = Objects.requireNonNull(facade, "facade");
		this.facade.applySettings();
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
		if (!facade.isLoaded()) {
			reset();
			return Optional.empty();
		}

		if (activeTask.isEmpty()) {
			if (appliedTask != null) {
				pendingInternalCancelTaskId = appliedTask.taskId();
				facade.cancel();
			}
			reset();
			return Optional.empty();
		}

		if (!sessionSnapshot.companionActuationAllowed()) {
			clearTerminalEvent(activeTask.get());
			snapshot = new TaskExecutionSnapshot(
				TaskExecutionState.PAUSED_BY_SESSION_GATE,
				activeTask.get().taskId(),
				activeTask.get().goal(),
				null,
				null,
				null,
				null
			);
			return Optional.empty();
		}

		boolean taskTargetChanged = !sameTaskTarget(activeTask.get(), appliedTask);
		if (taskTargetChanged) {
			if (appliedTask != null) {
				pendingInternalCancelTaskId = appliedTask.taskId();
				facade.cancel();
			}
			clearTerminalEvent(activeTask.get());
			try {
				applyGoal(activeTask.get().goal());
			}
			catch (RuntimeException exception) {
				appliedTask = activeTask.get();
				return failTaskStart(appliedTask, exception);
			}
		}
		appliedTask = activeTask.get();

		Optional<String> pathEvent = facade.pollPathEvent();
		Optional<TerminalOutcome> terminalOutcome = terminalOutcomeFor(pathEvent, appliedTask);
		if (terminalOutcome.isPresent() && isSuppressedInternalCancel(pathEvent)) {
			terminalOutcome = Optional.empty();
		}
		TaskExecutionState state = terminalOutcome
			.map(TerminalOutcome::state)
			.orElseGet(() -> taskTargetChanged || !isTerminal(snapshot.state()) ? TaskExecutionState.RUNNING : snapshot.state());
		TaskTerminationCause terminationCause = terminalOutcome.map(TerminalOutcome::cause).orElse(null);
		snapshot = new TaskExecutionSnapshot(
			state,
			appliedTask.taskId(),
			appliedTask.goal(),
			facade.activeProcessName().orElse(null),
			pathEvent.orElse(null),
			facade.estimatedTicksToGoal().orElse(null),
			terminationCause
		);

		if (terminalOutcome.isEmpty()) {
			return Optional.empty();
		}
		if (Objects.equals(appliedTask.taskId(), terminalEventTaskId)
			&& terminalOutcome.get().state() == terminalEventState
			&& terminalOutcome.get().cause() == terminalEventCause) {
			return Optional.empty();
		}
		terminalEventTaskId = appliedTask.taskId();
		terminalEventState = terminalOutcome.get().state();
		terminalEventCause = terminalOutcome.get().cause();
		return Optional.of(new TaskTerminalEvent(
			appliedTask.taskId(),
			appliedTask.goal(),
			terminalOutcome.get().state(),
			messageFor(terminalOutcome.get().state()),
			terminalOutcome.get().cause()
		));
	}

	private void applyGoal(GoalSnapshot goal) {
		switch (goal.type()) {
			case FOLLOW_PLAYER -> facade.startFollow(goal.targetPlayer());
			case NAVIGATE_TO -> facade.startNavigate(goal.position());
			case MINE_BLOCKS -> facade.startMine(goal.mineSpec());
		}
	}

	private Optional<TaskTerminalEvent> failTaskStart(WorldTaskRequest request, RuntimeException exception) {
		String message = nonEmpty(exception.getMessage(), exception.getClass().getSimpleName());
		snapshot = new TaskExecutionSnapshot(
			TaskExecutionState.FAILED,
			request.taskId(),
			request.goal(),
			null,
			message,
			null,
			null
		);
		terminalEventTaskId = request.taskId();
		terminalEventState = TaskExecutionState.FAILED;
		terminalEventCause = null;
		return Optional.of(new TaskTerminalEvent(
			request.taskId(),
			request.goal(),
			TaskExecutionState.FAILED,
			message,
			null
		));
	}

	private Optional<TerminalOutcome> terminalOutcomeFor(Optional<String> pathEvent, WorldTaskRequest activeTask) {
		if (pathEvent.isEmpty()) {
			return Optional.empty();
		}
		String normalized = pathEvent.get().trim().toUpperCase(Locale.ROOT);
		return switch (normalized) {
			case "AT_GOAL" -> Optional.of(new TerminalOutcome(TaskExecutionState.COMPLETED, TaskTerminationCause.GOAL_REACHED));
			case "CALC_FAILED" -> Optional.of(new TerminalOutcome(TaskExecutionState.FAILED, TaskTerminationCause.CALCULATION_FAILED));
			case "CANCELLED", "CANCELED" -> Optional.of(new TerminalOutcome(cancelledStateFor(activeTask == null ? null : activeTask.goal()), TaskTerminationCause.BARITONE_CANCELLED));
			default -> Optional.empty();
		};
	}

	private TaskExecutionState cancelledStateFor(GoalSnapshot activeGoal) {
		if (activeGoal == null || activeGoal.type() != GoalType.NAVIGATE_TO || activeGoal.position() == null) {
			return TaskExecutionState.CANCELLED;
		}
		return facade.navigationGoalReached(activeGoal.position())
			? TaskExecutionState.COMPLETED
			: TaskExecutionState.CANCELLED;
	}

	private static boolean isTerminal(TaskExecutionState state) {
		return state == TaskExecutionState.COMPLETED
			|| state == TaskExecutionState.FAILED
			|| state == TaskExecutionState.CANCELLED;
	}

	private boolean isSuppressedInternalCancel(Optional<String> pathEvent) {
		if (pathEvent.isEmpty() || pendingInternalCancelTaskId == null) {
			return false;
		}
		String normalized = pathEvent.get().trim().toUpperCase(Locale.ROOT);
		if (!normalized.equals("CANCELLED") && !normalized.equals("CANCELED")) {
			return false;
		}
		pendingInternalCancelTaskId = null;
		return true;
	}

	private static boolean sameTaskTarget(WorldTaskRequest left, WorldTaskRequest right) {
		if (left == right) {
			return true;
		}
		if (left == null || right == null) {
			return false;
		}
		return Objects.equals(left.taskId(), right.taskId())
			&& sameGoalTarget(left.goal(), right.goal());
	}

	private static boolean sameGoalTarget(GoalSnapshot left, GoalSnapshot right) {
		if (left == right) {
			return true;
		}
		if (left == null || right == null) {
			return false;
		}
		return left.type() == right.type()
			&& Objects.equals(left.targetPlayer(), right.targetPlayer())
			&& Objects.equals(left.position(), right.position())
			&& Objects.equals(left.mineSpec(), right.mineSpec());
	}

	private static String messageFor(TaskExecutionState state) {
		return switch (state) {
			case COMPLETED -> "Goal reached";
			case FAILED -> "Path calculation failed";
			case CANCELLED -> "Task cancelled";
			default -> "Task update";
		};
	}

	private static String nonEmpty(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}

	@Override
	public TaskExecutionSnapshot snapshot() {
		return snapshot;
	}

	@Override
	public void onWorldLeave() {
		facade.cancel();
		reset();
	}

	@Override
	public void shutdown() {
		onWorldLeave();
	}

	private void reset() {
		appliedTask = null;
		terminalEventTaskId = null;
		terminalEventState = null;
		terminalEventCause = null;
		pendingInternalCancelTaskId = null;
		snapshot = TaskExecutionSnapshot.idle();
	}

	private void clearTerminalEvent(WorldTaskRequest task) {
		if (!sameTaskTarget(task, appliedTask) || !Objects.equals(task.taskId(), terminalEventTaskId)) {
			terminalEventTaskId = null;
			terminalEventState = null;
			terminalEventCause = null;
		}
	}

	private record TerminalOutcome(TaskExecutionState state, TaskTerminationCause cause) {
	}
}
