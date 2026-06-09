package ai.moeru.airicraft.agent.tasks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

	@Test
	void underwaterWithoutUsefulHorizontalTargetAscends() {
		assertEquals(
			ReturnToSurfaceTaskExecutor.RecoveryMovement.ASCENDING,
			ReturnToSurfaceTaskExecutor.recoveryMovement(true, false, 0.0D, false)
		);
		assertEquals(
			ReturnToSurfaceTaskExecutor.RecoveryMovement.ASCENDING,
			ReturnToSurfaceTaskExecutor.recoveryMovement(true, true, 4.0D, false)
		);
	}

	@Test
	void underwaterWithUsefulHorizontalTargetMovesTowardTarget() {
		assertEquals(
			ReturnToSurfaceTaskExecutor.RecoveryMovement.TOWARD_TARGET,
			ReturnToSurfaceTaskExecutor.recoveryMovement(true, true, 4.01D, false)
		);
	}

	@Test
	void breathableButNotSurfaceDoesNotCompleteRecovery() {
		assertEquals(
			ReturnToSurfaceTaskExecutor.RecoveryMovement.BREATHABLE,
			ReturnToSurfaceTaskExecutor.recoveryMovement(false, true, 100.0D, false)
		);
	}

	@Test
	void breathableRecoveryRequiresStableWindowBeforeExiting() {
		assertFalse(ReturnToSurfaceTaskExecutor.shouldExitUnderwaterRecovery(false, 99));
		assertFalse(ReturnToSurfaceTaskExecutor.shouldExitUnderwaterRecovery(true, 11));
		assertTrue(ReturnToSurfaceTaskExecutor.shouldExitUnderwaterRecovery(true, 12));
	}

	@Test
	void breathableWaterContactDoesNotReenterRecovery() {
		assertTrue(ReturnToSurfaceTaskExecutor.shouldEnterUnderwaterRecovery(true, false, false));
		assertTrue(ReturnToSurfaceTaskExecutor.shouldEnterUnderwaterRecovery(false, true, false));
		assertFalse(ReturnToSurfaceTaskExecutor.shouldEnterUnderwaterRecovery(true, false, true));
		assertFalse(ReturnToSurfaceTaskExecutor.shouldEnterUnderwaterRecovery(false, false, false));
	}

	@Test
	void underwaterStuckReportsStuckRecoveryState() {
		assertEquals(
			ReturnToSurfaceTaskExecutor.RecoveryMovement.STUCK,
			ReturnToSurfaceTaskExecutor.recoveryMovement(true, true, 100.0D, true)
		);
	}

	@Test
	void towerHeadroomClearsOnlySolidDryObstructions() {
		assertTrue(ReturnToSurfaceTaskExecutor.shouldClearTowerHeadroom(true, false, false));
		assertFalse(ReturnToSurfaceTaskExecutor.shouldClearTowerHeadroom(false, false, false));
		assertFalse(ReturnToSurfaceTaskExecutor.shouldClearTowerHeadroom(true, true, false));
		assertFalse(ReturnToSurfaceTaskExecutor.shouldClearTowerHeadroom(true, false, true));
	}

	@Test
	void headroomToolSelectionMatchesNamespacedToolSuffixes() {
		assertTrue(ReturnToSurfaceTaskExecutor.shouldSelectHeadroomTool("minecraft:wooden_pickaxe", "_pickaxe"));
		assertTrue(ReturnToSurfaceTaskExecutor.shouldSelectHeadroomTool("minecraft:stone_shovel", "_shovel"));
		assertFalse(ReturnToSurfaceTaskExecutor.shouldSelectHeadroomTool("minecraft:cobblestone", "_pickaxe"));
		assertFalse(ReturnToSurfaceTaskExecutor.shouldSelectHeadroomTool(null, "_pickaxe"));
	}
}
