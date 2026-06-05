package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.goals.GoalType;

import java.util.Objects;

public record WorldTaskRequest(
	String taskId,
	String sourceJobId,
	WorldTaskType type,
	GoalSnapshot goal,
	CraftRecipeStepArgs craftRecipe,
	DropItemsStepArgs dropItems,
	EntityInteractionStepArgs entityInteraction,
	SmeltItemsStepArgs smeltItems,
	CollectSmeltedItemsStepArgs collectSmeltedItems,
	ReturnToSurfaceStepArgs returnToSurface,
	BlockPlacementStepArgs blockPlacement,
	BlockUseStepArgs blockUse
) {
	public WorldTaskRequest(String taskId, String sourceJobId, WorldTaskType type, GoalSnapshot goal) {
		this(taskId, sourceJobId, type, goal, null, null, null, null, null, null, null, null);
	}

	public WorldTaskRequest(String taskId, String sourceJobId, WorldTaskType type, GoalSnapshot goal, CraftRecipeStepArgs craftRecipe) {
		this(taskId, sourceJobId, type, goal, craftRecipe, null, null, null, null, null, null, null);
	}

	public WorldTaskRequest(String taskId, String sourceJobId, WorldTaskType type, GoalSnapshot goal, CraftRecipeStepArgs craftRecipe, DropItemsStepArgs dropItems) {
		this(taskId, sourceJobId, type, goal, craftRecipe, dropItems, null, null, null, null, null, null);
	}

	public WorldTaskRequest {
		taskId = normalizedValue(taskId, "taskId");
		sourceJobId = normalizedValue(sourceJobId, "sourceJobId");
		type = Objects.requireNonNull(type, "type");
		if (type == WorldTaskType.CRAFT_RECIPE) {
			craftRecipe = Objects.requireNonNull(craftRecipe, "craftRecipe");
		}
		else if (type == WorldTaskType.DROP_ITEMS) {
			dropItems = Objects.requireNonNull(dropItems, "dropItems");
		}
		else if (type == WorldTaskType.ATTACK_ENTITY || type == WorldTaskType.USE_ENTITY) {
			entityInteraction = Objects.requireNonNull(entityInteraction, "entityInteraction");
		}
		else if (type == WorldTaskType.SMELT_ITEMS) {
			smeltItems = Objects.requireNonNull(smeltItems, "smeltItems");
		}
		else if (type == WorldTaskType.COLLECT_SMELTED_ITEMS) {
			collectSmeltedItems = Objects.requireNonNull(collectSmeltedItems, "collectSmeltedItems");
		}
		else if (type == WorldTaskType.RETURN_TO_SURFACE) {
			returnToSurface = Objects.requireNonNull(returnToSurface, "returnToSurface");
		}
		else if (type == WorldTaskType.PLACE_BLOCK) {
			blockPlacement = Objects.requireNonNull(blockPlacement, "blockPlacement");
		}
		else if (type == WorldTaskType.USE_BLOCK) {
			blockUse = Objects.requireNonNull(blockUse, "blockUse");
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
		return new WorldTaskRequest(taskId, sourceJobId, WorldTaskType.CRAFT_RECIPE, null, craftRecipe, null, null, null, null, null, null, null);
	}

	public static WorldTaskRequest dropItems(String taskId, String sourceJobId, DropItemsStepArgs dropItems) {
		return new WorldTaskRequest(taskId, sourceJobId, WorldTaskType.DROP_ITEMS, null, null, dropItems, null, null, null, null, null, null);
	}

	public static WorldTaskRequest attackEntity(String taskId, String sourceJobId, EntityInteractionStepArgs entityInteraction) {
		return new WorldTaskRequest(taskId, sourceJobId, WorldTaskType.ATTACK_ENTITY, null, null, null, entityInteraction, null, null, null, null, null);
	}

	public static WorldTaskRequest useEntity(String taskId, String sourceJobId, EntityInteractionStepArgs entityInteraction) {
		return new WorldTaskRequest(taskId, sourceJobId, WorldTaskType.USE_ENTITY, null, null, null, entityInteraction, null, null, null, null, null);
	}

	public static WorldTaskRequest smeltItems(String taskId, String sourceJobId, SmeltItemsStepArgs smeltItems) {
		return new WorldTaskRequest(taskId, sourceJobId, WorldTaskType.SMELT_ITEMS, null, null, null, null, smeltItems, null, null, null, null);
	}

	public static WorldTaskRequest collectSmeltedItems(String taskId, String sourceJobId, CollectSmeltedItemsStepArgs collectSmeltedItems) {
		return new WorldTaskRequest(taskId, sourceJobId, WorldTaskType.COLLECT_SMELTED_ITEMS, null, null, null, null, null, collectSmeltedItems, null, null, null);
	}

	public static WorldTaskRequest returnToSurface(String taskId, String sourceJobId, ReturnToSurfaceStepArgs returnToSurface) {
		return new WorldTaskRequest(taskId, sourceJobId, WorldTaskType.RETURN_TO_SURFACE, null, null, null, null, null, null, returnToSurface, null, null);
	}

	public static WorldTaskRequest placeBlock(String taskId, String sourceJobId, BlockPlacementStepArgs blockPlacement) {
		return new WorldTaskRequest(taskId, sourceJobId, WorldTaskType.PLACE_BLOCK, null, null, null, null, null, null, null, blockPlacement, null);
	}

	public static WorldTaskRequest useBlock(String taskId, String sourceJobId, BlockUseStepArgs blockUse) {
		return new WorldTaskRequest(taskId, sourceJobId, WorldTaskType.USE_BLOCK, null, null, null, null, null, null, null, null, blockUse);
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
