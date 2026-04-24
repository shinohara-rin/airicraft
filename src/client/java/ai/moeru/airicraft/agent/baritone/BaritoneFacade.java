package ai.moeru.airicraft.agent.baritone;

import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;

import java.util.Optional;

public interface BaritoneFacade {
	boolean isLoaded();

	void applySettings();

	void startFollow(String playerName);

	void startNavigate(GoalPosition position);

	void startNavigateNear(GoalPosition position, int radiusBlocks);

	void startMine(GoalMineSpec spec);

	void cancel();

	Optional<String> activeProcessName();

	Optional<Double> estimatedTicksToGoal();

	Optional<String> pollPathEvent();

	boolean navigationGoalReached(GoalPosition position);
}
