package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;

import java.util.List;

public record BlockBreakStepArgs(
	List<Target> targets
) {
	public static final int MAX_TARGETS = 16;

	public BlockBreakStepArgs {
		targets = targets == null
			? List.of()
			: targets.stream()
				.map(target -> {
					if (target == null) {
						throw new IllegalArgumentException("targets must not contain null");
					}
					return target;
				})
				.toList();
		if (targets.isEmpty()) {
			throw new IllegalArgumentException("targets must not be empty");
		}
		if (targets.size() > MAX_TARGETS) {
			throw new IllegalArgumentException("targets must contain at most " + MAX_TARGETS + " entries");
		}
	}

	public record Target(
		GoalPosition position,
		List<String> expectedBlockIds
	) {
		public Target {
			position = position == null
				? null
				: new GoalPosition(position.x(), position.y(), position.z(), true);
			if (position == null) {
				throw new IllegalArgumentException("target position must not be null");
			}
			expectedBlockIds = expectedBlockIds == null
				? List.of()
				: expectedBlockIds.stream()
					.map(Target::normalizeOptional)
					.filter(value -> value != null)
					.toList();
			if (expectedBlockIds.isEmpty()) {
				throw new IllegalArgumentException("expectedBlockIds must not be empty");
			}
		}

		private static String normalizeOptional(String value) {
			return value == null || value.isBlank() ? null : value.trim();
		}
	}
}
