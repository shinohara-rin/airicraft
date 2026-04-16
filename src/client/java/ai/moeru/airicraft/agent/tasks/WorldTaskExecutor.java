package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.session.SessionSnapshot;

import java.util.Optional;

public interface WorldTaskExecutor {
	Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask);

	TaskExecutionSnapshot snapshot();

	void onWorldLeave();

	void shutdown();
}
