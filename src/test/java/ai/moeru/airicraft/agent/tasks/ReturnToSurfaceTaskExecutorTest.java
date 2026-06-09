package ai.moeru.airicraft.agent.tasks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReturnToSurfaceTaskExecutorTest {
	@Test
	void targetReachedUndergroundWithoutToweringFailsInsteadOfCompleting() {
		assertEquals(
			ReturnToSurfaceTaskExecutor.SurfaceTargetOutcome.FAIL,
			ReturnToSurfaceTaskExecutor.surfaceTargetOutcome(false, false)
		);
	}

	@Test
	void targetReachedUndergroundWithToweringFallsBackToTowering() {
		assertEquals(
			ReturnToSurfaceTaskExecutor.SurfaceTargetOutcome.TOWER,
			ReturnToSurfaceTaskExecutor.surfaceTargetOutcome(false, true)
		);
	}

	@Test
	void targetReachedOnSafeSurfaceCompletes() {
		assertEquals(
			ReturnToSurfaceTaskExecutor.SurfaceTargetOutcome.COMPLETE,
			ReturnToSurfaceTaskExecutor.surfaceTargetOutcome(true, false)
		);
	}
}
