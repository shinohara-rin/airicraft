package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;

import java.util.List;

public record ReturnToSurfaceStepArgs(
	GoalPosition targetPosition,
	String targetKind,
	boolean useTowering,
	List<String> fillerBlockIds
) {
	public static final List<String> DEFAULT_FILLER_BLOCK_IDS = List.of(
		"minecraft:dirt",
		"minecraft:cobblestone",
		"minecraft:oak_planks",
		"minecraft:spruce_planks",
		"minecraft:birch_planks",
		"minecraft:jungle_planks",
		"minecraft:acacia_planks",
		"minecraft:dark_oak_planks",
		"minecraft:mangrove_planks",
		"minecraft:cherry_planks",
		"minecraft:pale_oak_planks"
	);

	public ReturnToSurfaceStepArgs {
		targetKind = targetKind == null || targetKind.isBlank() ? "unknown" : targetKind.trim();
		fillerBlockIds = normalizeFillerBlockIds(fillerBlockIds);
	}

	public static List<String> normalizeFillerBlockIds(List<String> blockIds) {
		if (blockIds == null || blockIds.isEmpty()) {
			return DEFAULT_FILLER_BLOCK_IDS;
		}
		List<String> normalized = blockIds.stream()
			.filter(value -> value != null && !value.isBlank())
			.map(String::trim)
			.distinct()
			.toList();
		return normalized.isEmpty() ? DEFAULT_FILLER_BLOCK_IDS : normalized;
	}
}
