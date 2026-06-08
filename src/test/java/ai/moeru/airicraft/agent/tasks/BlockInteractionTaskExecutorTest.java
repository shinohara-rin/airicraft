package ai.moeru.airicraft.agent.tasks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockInteractionTaskExecutorTest {
	@Test
	void directWaterPlacementRequiresAHorizontalCavity() {
		assertFalse(BlockInteractionTaskExecutor.isSafeDirectWaterTarget(0));
		assertFalse(BlockInteractionTaskExecutor.isSafeDirectWaterTarget(1));
		assertFalse(BlockInteractionTaskExecutor.isSafeDirectWaterTarget(2));
		assertTrue(BlockInteractionTaskExecutor.isSafeDirectWaterTarget(3));
		assertTrue(BlockInteractionTaskExecutor.isSafeDirectWaterTarget(4));
	}
}
