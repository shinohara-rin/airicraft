package ai.moeru.airicraft.agent.idle;

import ai.moeru.airicraft.agent.llm.PlannerTrigger;
import ai.moeru.airicraft.agent.llm.PlannerTriggerType;

import java.util.List;
import java.util.Optional;

public final class IdleIdeaScheduler {
	private volatile IdleIdeasConfig config;
	private long idleStartTimestampMs = -1L;
	private long lastFireTimestampMs = -1L;

	public IdleIdeaScheduler(IdleIdeasConfig config) {
		this.config = config == null ? IdleIdeasConfig.defaults() : config;
	}

	public synchronized void updateConfig(IdleIdeasConfig nextConfig) {
		this.config = nextConfig == null ? IdleIdeasConfig.defaults() : nextConfig;
	}

	public synchronized void reset() {
		idleStartTimestampMs = -1L;
		lastFireTimestampMs = -1L;
	}

	public synchronized Optional<PlannerTrigger> tick(boolean activeJobIdle, long tickCount, long nowMs) {
		IdleIdeasConfig current = config;
		if (!current.enabled() || current.ideas().isEmpty()) {
			idleStartTimestampMs = -1L;
			return Optional.empty();
		}
		if (!activeJobIdle) {
			idleStartTimestampMs = -1L;
			return Optional.empty();
		}
		if (idleStartTimestampMs < 0L) {
			idleStartTimestampMs = nowMs;
		}
		long sinceIdleMs = Math.max(0L, nowMs - idleStartTimestampMs);
		long sinceLastFireMs = lastFireTimestampMs < 0L ? Long.MAX_VALUE : Math.max(0L, nowMs - lastFireTimestampMs);
		if (sinceIdleMs < current.initialDelaySeconds() * 1000L) {
			return Optional.empty();
		}
		if (sinceLastFireMs < current.cooldownSeconds() * 1000L) {
			return Optional.empty();
		}
		lastFireTimestampMs = nowMs;
		return Optional.of(buildTrigger(current.ideas(), tickCount, nowMs));
	}

	private static PlannerTrigger buildTrigger(List<String> ideas, long tickCount, long nowMs) {
		StringBuilder builder = new StringBuilder(512);
		builder.append("IDLE THINK: You currently have no active task and no recent player input. ")
			.append("Treat this turn as your own initiative window. ")
			.append("Pick exactly one small concrete next step now by calling the appropriate action tool, ")
			.append("or ask the player one short focused question in plaintext if a design decision genuinely needs their input. ")
			.append("Do not ask the player questions back-to-back across idle turns.\n")
			.append("Survival progression ideas to consider (pick one whose preconditions are met now; do not read the list back to the player):\n");
		for (String idea : ideas) {
			builder.append("- ").append(idea).append('\n');
		}
		return PlannerTrigger.pending(PlannerTriggerType.IDLE_THINK, "self", builder.toString(), tickCount, nowMs);
	}
}
