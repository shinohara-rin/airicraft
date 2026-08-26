package ai.moeru.airicraft.agent.tasks;

/**
 * Deterministic transition policy for the Baritone-to-underwater-harvest
 * ownership handoff. Live player and Baritone observations stay in the
 * coordinator shell.
 */
public final class HybridMiningPolicy {
	static final long POST_REPLAN_STALL_TICKS = 60L;
	static final double POST_REPLAN_PROGRESS_DISTANCE = 0.75D;
	static final long HANDOFF_TIMEOUT_TICKS = 20L;

	private HybridMiningPolicy() {
	}

	static StallUpdate startPostReplanStall(long tick, ProgressSample sample) {
		return sample == null || !sample.touchingWater()
			? new StallUpdate(null, false)
			: new StallUpdate(new PostReplanStall(tick, sample), false);
	}

	static StallUpdate observePostReplanStall(PostReplanStall previous, long tick, ProgressSample sample) {
		if (previous == null || sample == null || !sample.touchingWater()) {
			return new StallUpdate(null, false);
		}
		if (tick < previous.anchorTick()
			|| previous.anchor().distanceTo(sample) >= POST_REPLAN_PROGRESS_DISTANCE) {
			return new StallUpdate(new PostReplanStall(tick, sample), false);
		}
		if (tick - previous.anchorTick() < POST_REPLAN_STALL_TICKS) {
			return new StallUpdate(previous, false);
		}
		// Start a fresh observation window even when the shell cannot accept a
		// fallback, so a missing local source is not probed on every tick.
		return new StallUpdate(new PostReplanStall(tick, sample), true);
	}

	static ReleaseDecision releaseDecision(long elapsedTicks, ReleaseStatus status) {
		if (status != null && status.ownershipReleased() && status.cancellationDrained()) {
			return ReleaseDecision.START_UNDERWATER_HARVEST;
		}
		return elapsedTicks >= HANDOFF_TIMEOUT_TICKS
			? ReleaseDecision.FAIL_TIMEOUT
			: ReleaseDecision.WAIT;
	}

	static boolean shouldTryUnderwaterFallback(TaskTerminationCause terminationCause) {
		return terminationCause == TaskTerminationCause.CALCULATION_FAILED
			|| terminationCause == TaskTerminationCause.BARITONE_CANCELLED;
	}

	enum ReleaseDecision {
		WAIT,
		START_UNDERWATER_HARVEST,
		FAIL_TIMEOUT
	}

	public record ProgressSample(boolean touchingWater, double x, double y, double z) {
		double distanceTo(ProgressSample other) {
			double dx = x - other.x;
			double dy = y - other.y;
			double dz = z - other.z;
			return Math.sqrt(dx * dx + dy * dy + dz * dz);
		}
	}

	record PostReplanStall(long anchorTick, ProgressSample anchor) {
	}

	record StallUpdate(PostReplanStall state, boolean fallbackDue) {
	}

	public record ReleaseStatus(boolean ownershipReleased, boolean cancellationDrained) {
		static ReleaseStatus waiting() {
			return new ReleaseStatus(false, false);
		}
	}
}
