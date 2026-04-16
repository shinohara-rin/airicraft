package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.goals.GoalType;

import java.util.List;

public final class CollectResourceTaskHandler {
	private static final List<String> WOOD_LOG_BLOCK_IDS = List.of(
		"minecraft:oak_log",
		"minecraft:birch_log",
		"minecraft:spruce_log",
		"minecraft:jungle_log",
		"minecraft:acacia_log",
		"minecraft:dark_oak_log",
		"minecraft:mangrove_log",
		"minecraft:cherry_log"
	);

	public GoalSnapshot start(TaskSpec spec, long tick) {
		return start(spec, spec.quantity(), tick);
	}

	public GoalSnapshot start(TaskSpec spec, int remainingQuantity, long tick) {
		if (spec.type() != TaskType.COLLECT_RESOURCE) {
			throw new IllegalArgumentException("Unsupported task spec: " + spec);
		}
		return start(spec.resourceKind(), remainingQuantity, tick);
	}

	public GoalSnapshot start(TaskResourceKind resourceKind, int remainingQuantity, long tick) {
		if (resourceKind != TaskResourceKind.WOOD_LOGS) {
			throw new IllegalArgumentException("Unsupported resource kind: " + resourceKind);
		}
		return new GoalSnapshot(
			GoalType.MINE_BLOCKS,
			null,
			null,
			new GoalMineSpec(WOOD_LOG_BLOCK_IDS, remainingQuantity),
			tick,
			"task_runtime"
		);
	}

	public static List<String> targetBlockIds(TaskSpec spec) {
		if (spec == null || spec.type() != TaskType.COLLECT_RESOURCE || spec.resourceKind() != TaskResourceKind.WOOD_LOGS) {
			return List.of();
		}
		return WOOD_LOG_BLOCK_IDS;
	}

	public static boolean matchesResourceKind(TaskResourceKind resourceKind, List<String> blockIds) {
		if (resourceKind != TaskResourceKind.WOOD_LOGS || blockIds == null || blockIds.isEmpty()) {
			return false;
		}
		return blockIds.stream().allMatch(WOOD_LOG_BLOCK_IDS::contains);
	}
}
