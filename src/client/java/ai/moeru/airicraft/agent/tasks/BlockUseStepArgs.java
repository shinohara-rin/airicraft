package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;

import java.util.List;

public record BlockUseStepArgs(
	String itemId,
	GoalPosition targetPosition,
	String facePreference,
	List<String> expectedSupportBlockIds,
	String expectedTargetMaterial
) {
	public BlockUseStepArgs {
		itemId = normalizeOptional(itemId);
		targetPosition = targetPosition == null
			? null
			: new GoalPosition(targetPosition.x(), targetPosition.y(), targetPosition.z(), true);
		if (targetPosition == null) {
			throw new IllegalArgumentException("targetPosition must not be null");
		}
		facePreference = normalizeOptional(facePreference);
		expectedSupportBlockIds = expectedSupportBlockIds == null
			? List.of()
			: expectedSupportBlockIds.stream()
				.map(BlockUseStepArgs::normalizeOptional)
				.filter(value -> value != null)
				.toList();
		expectedTargetMaterial = normalizeOptional(expectedTargetMaterial);
	}

	private static String normalizeOptional(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
