package ai.moeru.airicraft.agent.tasks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridMiningPolicyTest {
	@Test
	void underwaterFallbackRequiresBaritoneTargetSearchFailure() {
		assertTrue(HybridMiningPolicy.shouldTryUnderwaterFallback(TaskTerminationCause.CALCULATION_FAILED));
		assertTrue(HybridMiningPolicy.shouldTryUnderwaterFallback(TaskTerminationCause.BARITONE_CANCELLED));
		assertFalse(HybridMiningPolicy.shouldTryUnderwaterFallback(TaskTerminationCause.GOAL_REACHED));
		assertFalse(HybridMiningPolicy.shouldTryUnderwaterFallback(null));
	}
}
