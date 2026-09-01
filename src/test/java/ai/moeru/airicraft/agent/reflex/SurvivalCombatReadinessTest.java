package ai.moeru.airicraft.agent.reflex;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurvivalCombatReadinessTest {
	@Test
	void fightsWhenHealthyArmoredArmedFedAndFacingBoundedMeleeThreats() {
		assertTrue(SurvivalCombatReadiness.shouldFight(new SurvivalCombatReadiness.State(
			0.9D, 12, true, 16, true, 2, 4.0D, true, false
		)));
	}

	@Test
	void fleesWhenAnySafetyBoundaryIsMissing() {
		assertFalse(SurvivalCombatReadiness.shouldFight(new SurvivalCombatReadiness.State(
			0.65D, 12, true, 16, true, 1, 3.0D, true, false
		)));
		assertFalse(SurvivalCombatReadiness.shouldFight(new SurvivalCombatReadiness.State(
			0.9D, 3, true, 16, true, 1, 3.0D, true, false
		)));
		assertFalse(SurvivalCombatReadiness.shouldFight(new SurvivalCombatReadiness.State(
			0.9D, 12, false, 16, true, 1, 3.0D, true, false
		)));
		assertFalse(SurvivalCombatReadiness.shouldFight(new SurvivalCombatReadiness.State(
			0.9D, 12, true, 5, true, 1, 3.0D, true, false
		)));
		assertFalse(SurvivalCombatReadiness.shouldFight(new SurvivalCombatReadiness.State(
			0.9D, 12, true, 16, false, 1, 3.0D, true, false
		)));
		assertFalse(SurvivalCombatReadiness.shouldFight(new SurvivalCombatReadiness.State(
			0.9D, 12, true, 16, true, 3, 3.0D, true, false
		)));
		assertFalse(SurvivalCombatReadiness.shouldFight(new SurvivalCombatReadiness.State(
			0.9D, 12, true, 16, true, 1, 3.0D, true, true
		)));
	}
}
