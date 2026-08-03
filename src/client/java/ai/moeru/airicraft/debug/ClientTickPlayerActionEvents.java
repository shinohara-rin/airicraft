package ai.moeru.airicraft.debug;

import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.Set;

/**
 * Collects player interaction starts between client-tick snapshots.
 */
public final class ClientTickPlayerActionEvents {
	private static final Set<String> STARTED_ACTIONS = new LinkedHashSet<>();
	private static ClientTickPlayerActionsSnapshot.BreakProgress breakProgress;

	private ClientTickPlayerActionEvents() {
	}

	public static synchronized void recordStart(String action) {
		if (action != null && !action.isBlank()) {
			STARTED_ACTIONS.add(action);
		}
	}

	public static synchronized void recordBreakProgress(int x, int y, int z, float progress) {
		int stage = Math.clamp((int) Math.floor(progress * 10.0F), 0, 9);
		breakProgress = new ClientTickPlayerActionsSnapshot.BreakProgress(
			new ClientTickPlayerActionsSnapshot.Position(x, y, z),
			progress,
			stage,
			false
		);
	}

	static synchronized EventBatch take() {
		Set<String> actions = Collections.unmodifiableSet(new LinkedHashSet<>(STARTED_ACTIONS));
		ClientTickPlayerActionsSnapshot.BreakProgress nextBreakProgress = breakProgress;
		STARTED_ACTIONS.clear();
		breakProgress = null;
		return new EventBatch(actions, nextBreakProgress);
	}

	public static synchronized void clear() {
		STARTED_ACTIONS.clear();
		breakProgress = null;
	}

	record EventBatch(
		Set<String> startedActions,
		ClientTickPlayerActionsSnapshot.BreakProgress breakProgress
	) {
	}
}
