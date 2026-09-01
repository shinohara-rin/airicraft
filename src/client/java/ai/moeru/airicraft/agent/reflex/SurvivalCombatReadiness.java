package ai.moeru.airicraft.agent.reflex;

final class SurvivalCombatReadiness {
	static final double MAX_ENGAGE_DISTANCE = 8.0D;

	private SurvivalCombatReadiness() {
	}

	static boolean shouldFight(State state) {
		return state != null
			&& state.threatCount() == 1
			&& state.nearestThreatDistance() <= MAX_ENGAGE_DISTANCE
			&& state.lineOfSight()
			&& !state.environmentHazard();
	}

	record State(
		int threatCount,
		double nearestThreatDistance,
		boolean lineOfSight,
		boolean environmentHazard
	) {
	}
}
