package ai.moeru.airicraft.agent.reflex;

import java.util.List;

public record SurvivalReflexSnapshot(
	SurvivalReflexState state,
	SurvivalReflexCause cause,
	SurvivalReflexAction action,
	long safetyEpoch,
	String holdId,
	String interruptedJobId,
	String interruptedActionExecutionId,
	List<ThreatSnapshot> threats,
	Float health,
	Float maxHealth,
	Integer air,
	Integer maxAir,
	long startedTick,
	long lastDangerTick,
	int breathableTicks,
	String lastActuatorFailure
) {
	public SurvivalReflexSnapshot {
		state = state == null ? SurvivalReflexState.IDLE : state;
		if (state == SurvivalReflexState.AWAITING_PLANNER && (holdId == null || holdId.isBlank())) {
			throw new IllegalArgumentException("AWAITING_PLANNER requires a holdId");
		}
		threats = threats == null ? List.of() : List.copyOf(threats);
	}

	public static SurvivalReflexSnapshot idle() {
		return new SurvivalReflexSnapshot(
			SurvivalReflexState.IDLE, null, null, 0L, null, null, null, List.of(),
			null, null, null, null, -1L, -1L, 0, null
		);
	}

	public boolean ownsActuation() {
		return state == SurvivalReflexState.ACTIVE
			|| (state == SurvivalReflexState.AWAITING_PLANNER && cause == SurvivalReflexCause.DROWNING);
	}

	public boolean holdsNormalTasks() {
		return state == SurvivalReflexState.ACTIVE || state == SurvivalReflexState.AWAITING_PLANNER;
	}

	public record ThreatSnapshot(
		String uuid,
		String name,
		String entityTypeId,
		double distance,
		boolean alive,
		boolean lineOfSight
	) {
	}
}
