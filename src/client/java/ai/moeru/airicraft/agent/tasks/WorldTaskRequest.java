package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.goals.GoalType;

import java.util.Objects;

public record WorldTaskRequest(
	String taskId,
	String sourceJobId,
	WorldTaskType type,
	GoalSnapshot goal,
	CraftRecipeStepArgs craftRecipe
) {
	public WorldTaskRequest(String taskId, String sourceJobId, WorldTaskType type, GoalSnapshot goal) {
		this(taskId, sourceJobId, type, goal, null);
	}

	public WorldTaskRequest {
		taskId = normalizedValue(taskId, "taskId");
		sourceJobId = normalizedValue(sourceJobId, "sourceJobId");
		type = Objects.requireNonNull(type, "type");
		if (type == WorldTaskType.CRAFT_RECIPE) {
			craftRecipe = Objects.requireNonNull(craftRecipe, "craftRecipe");
		}
		else {
			goal = Objects.requireNonNull(goal, "goal");
		}
	}

	public static WorldTaskRequest direct(String taskId, GoalSnapshot goal) {
		return new WorldTaskRequest(taskId, taskId, typeFor(goal), goal, null);
	}

	public static WorldTaskRequest collectMine(String taskId, String sourceJobId, GoalSnapshot goal) {
		return new WorldTaskRequest(taskId, sourceJobId, WorldTaskType.MINE, goal, null);
	}

	public static WorldTaskRequest craftRecipe(String taskId, String sourceJobId, CraftRecipeStepArgs craftRecipe) {
		return new WorldTaskRequest(taskId, sourceJobId, WorldTaskType.CRAFT_RECIPE, null, craftRecipe);
	}

	private static WorldTaskType typeFor(GoalSnapshot goal) {
		GoalType goalType = Objects.requireNonNull(goal, "goal").type();
		return switch (goalType) {
			case FOLLOW_PLAYER -> WorldTaskType.FOLLOW;
			case NAVIGATE_TO -> WorldTaskType.NAVIGATE;
			case MINE_BLOCKS -> WorldTaskType.MINE;
		};
	}

	private static String normalizedValue(String value, String fieldName) {
		String trimmed = value == null ? null : value.trim();
		if (trimmed == null || trimmed.isEmpty()) {
			throw new IllegalArgumentException(fieldName + " is required");
		}
		return trimmed;
	}
}
