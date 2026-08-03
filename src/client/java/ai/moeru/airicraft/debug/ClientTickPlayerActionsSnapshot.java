package ai.moeru.airicraft.debug;

import java.util.List;

public record ClientTickPlayerActionsSnapshot(
	List<ActionState> actions,
	BreakProgress breakProgress
) {
	public ClientTickPlayerActionsSnapshot {
		actions = List.copyOf(actions);
	}

	public record ActionState(String action, boolean pressed, boolean started) {
	}

	public record BreakProgress(Position position, float progress, int stage, boolean started) {
	}

	public record Position(int x, int y, int z) {
	}
}
