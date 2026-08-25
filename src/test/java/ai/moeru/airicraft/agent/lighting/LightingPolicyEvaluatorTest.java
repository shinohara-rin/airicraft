package ai.moeru.airicraft.agent.lighting;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightingPolicyEvaluatorTest {
	@Test
	void darknessPolicyRequiresMiningOffhandAndUndergroundWhenConfigured() {
		LightingPolicy policy = new LightingPolicy(true, LightingPolicy.Mode.DARKNESS, 2, true, 6, 1L);

		assertTrue(LightingPolicyEvaluator.shouldPlace(policy, true, true, false, 2, 0, false));
		assertFalse(LightingPolicyEvaluator.shouldPlace(policy, false, true, false, 0, 0, false));
		assertFalse(LightingPolicyEvaluator.shouldPlace(policy, true, false, false, 0, 0, false));
		assertFalse(LightingPolicyEvaluator.shouldPlace(policy, true, true, true, 0, 0, false));
		assertFalse(LightingPolicyEvaluator.shouldPlace(policy, true, true, false, 3, 0, false));
	}

	@Test
	void spawnProofPolicyUsesBlockLightAndHonorsSpacing() {
		LightingPolicy policy = new LightingPolicy(true, LightingPolicy.Mode.SPAWN_PROOF, 7, false, 6, 1L);

		assertTrue(LightingPolicyEvaluator.shouldPlace(policy, true, true, true, 15, 7, false));
		assertFalse(LightingPolicyEvaluator.shouldPlace(policy, true, true, true, 0, 8, false));
		assertFalse(LightingPolicyEvaluator.shouldPlace(policy, true, true, true, 0, 0, true));
	}
}
