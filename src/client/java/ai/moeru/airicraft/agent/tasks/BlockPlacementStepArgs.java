package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;

public record BlockPlacementStepArgs(
	String itemId,
	GoalPosition targetPosition,
	String facePreference,
	String requiredTargetMaterial
) {
	public BlockPlacementStepArgs {
		itemId = normalizeRequired(itemId, "itemId");
		targetPosition = targetPosition == null
			? null
			: new GoalPosition(targetPosition.x(), targetPosition.y(), targetPosition.z(), true);
		if (targetPosition == null) {
			throw new IllegalArgumentException("targetPosition must not be null");
		}
		facePreference = normalizeOptional(facePreference);
		requiredTargetMaterial = normalizeOptional(requiredTargetMaterial);
	}

	private static String normalizeRequired(String value, String fieldName) {
		String normalized = normalizeOptional(value);
		if (normalized == null) {
			throw new IllegalArgumentException(fieldName + " must not be blank");
		}
		return normalized;
	}

	private static String normalizeOptional(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
