package ai.moeru.airicraft.agent.lighting;

import org.junit.jupiter.api.Test;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

	@Test
	void wallCandidatesPreferEveryLeftPositionBeforeRightFallbacks() {
		var candidates = LightingRuntime.wallPlacementCandidates(new BlockPos(10, 20, 30), Direction.NORTH);

		assertEquals(6, candidates.size());
		assertEquals(new BlockPos(9, 21, 31), candidates.get(0).support());
		assertEquals(Direction.EAST, candidates.get(0).face());
		assertEquals("left", candidates.get(0).side());
		assertEquals(new BlockPos(11, 21, 31), candidates.get(3).support());
		assertEquals(Direction.WEST, candidates.get(3).face());
		assertEquals("right", candidates.get(3).side());
	}
}
