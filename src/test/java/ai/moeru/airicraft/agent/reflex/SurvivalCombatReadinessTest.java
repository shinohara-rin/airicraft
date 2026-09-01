package ai.moeru.airicraft.agent.reflex;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurvivalCombatReadinessTest {
	@Test
	void fightsOneOrdinaryMobWithoutRequiringPerfectArmorOrFood() {
		assertTrue(SurvivalCombatReadiness.shouldFight(new SurvivalCombatReadiness.State(
			1, 4.0D, true, false
		)));
	}

	@Test
	void fleesWhenOverwhelmedDistantHiddenOrEnvironmentIsUnsafe() {
		assertFalse(SurvivalCombatReadiness.shouldFight(new SurvivalCombatReadiness.State(
			2, 3.0D, true, false
		)));
		assertFalse(SurvivalCombatReadiness.shouldFight(new SurvivalCombatReadiness.State(
			1, 8.01D, true, false
		)));
		assertFalse(SurvivalCombatReadiness.shouldFight(new SurvivalCombatReadiness.State(
			1, 3.0D, false, false
		)));
		assertFalse(SurvivalCombatReadiness.shouldFight(new SurvivalCombatReadiness.State(
			1, 3.0D, true, true
		)));
	}
}
