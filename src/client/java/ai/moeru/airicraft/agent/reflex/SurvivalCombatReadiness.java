package ai.moeru.airicraft.agent.reflex;

final class SurvivalCombatReadiness {
	static final double MIN_HEALTH_RATIO = 0.75D;
	static final int MIN_ARMOR_POINTS = 8;
	static final int MIN_HUNGER = 12;
	static final int MAX_THREATS = 2;
	static final double MAX_ENGAGE_DISTANCE = 4.5D;

	private SurvivalCombatReadiness() {
	}

	static boolean shouldFight(State state) {
		return state != null
			&& state.healthRatio() >= MIN_HEALTH_RATIO
			&& state.armorPoints() >= MIN_ARMOR_POINTS
			&& state.weaponEquipped()
			&& state.hunger() >= MIN_HUNGER
			&& state.foodAvailable()
			&& state.threatCount() >= 1
			&& state.threatCount() <= MAX_THREATS
			&& state.nearestThreatDistance() <= MAX_ENGAGE_DISTANCE
			&& state.lineOfSight()
			&& !state.environmentHazard();
	}

	record State(
		double healthRatio,
		int armorPoints,
		boolean weaponEquipped,
		int hunger,
		boolean foodAvailable,
		int threatCount,
		double nearestThreatDistance,
		boolean lineOfSight,
		boolean environmentHazard
	) {
	}
}
