package ai.moeru.airicraft.agent.reflex;

import ai.moeru.airicraft.agent.tasks.UnderwaterEscapeSearch;
import ai.moeru.airicraft.agent.tasks.UnderwaterEscapeNavigator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurvivalReflexRuntimeTest {
	@Test
	void drowningStartsAtLowAirThresholdOrFromDrowningDamage() {
		assertTrue(SurvivalReflexRuntime.shouldStartDrowning(true, 100, 100, false));
		assertFalse(SurvivalReflexRuntime.shouldStartDrowning(true, 101, 100, false));
		assertFalse(SurvivalReflexRuntime.shouldStartDrowning(false, 0, 100, false));
		assertTrue(SurvivalReflexRuntime.shouldStartDrowning(false, 300, 100, true));
	}

	@Test
	void drowningRequiresAirMarginAndTwelveStableTicksToResolve() {
		assertFalse(SurvivalReflexRuntime.airRecoveryMarginReached(false, 279, 300));
		assertTrue(SurvivalReflexRuntime.airRecoveryMarginReached(false, 280, 300));
		assertTrue(SurvivalReflexRuntime.airRecoveryMarginReached(false, 300, 300));
		assertFalse(SurvivalReflexRuntime.airRecoveryMarginReached(true, 300, 300));
		assertFalse(SurvivalReflexRuntime.drowningResolved(11));
		assertTrue(SurvivalReflexRuntime.drowningResolved(12));
	}

	@Test
	void stableBreathingEndsActiveDrowningBeforeSafeLandWork() {
		assertEquals(SurvivalReflexAction.REACH_SAFE_LAND, SurvivalReflexRuntime.drowningAction(false));
		assertEquals(SurvivalReflexAction.SWIM_TO_AIR, SurvivalReflexRuntime.drowningAction(true));
		assertTrue(SurvivalReflexRuntime.stableDrowningRecovery(true));
		assertFalse(SurvivalReflexRuntime.stableDrowningRecovery(false));
		assertTrue(SurvivalReflexRuntime.shouldKeepDrowningSafetyHold(false, false));
		assertFalse(SurvivalReflexRuntime.shouldKeepDrowningSafetyHold(false, true));
		assertTrue(SurvivalReflexRuntime.shouldKeepDrowningSafetyHold(true, true));
		assertEquals(UnderwaterEscapeSearch.SearchMode.BREATHABLE,
			SurvivalReflexRuntime.drowningSearchMode(false, true, 80, 300));
		assertEquals(UnderwaterEscapeSearch.SearchMode.BREATHABLE,
			SurvivalReflexRuntime.drowningSearchMode(true, false, 300, 300));
		assertEquals(UnderwaterEscapeSearch.SearchMode.SAFE_STANDING,
			SurvivalReflexRuntime.drowningSearchMode(false, false, 280, 300));
	}

	@Test
	void terminalSafeLandSearchFallsBackToAfloatHold() {
		assertFalse(SurvivalReflexRuntime.safeLandSearchExhausted(
			UnderwaterEscapeSearch.SearchStatus.SEARCHING,
			UnderwaterEscapeNavigator.Phase.EXHAUSTED
		));
		assertFalse(SurvivalReflexRuntime.safeLandSearchExhausted(
			UnderwaterEscapeSearch.SearchStatus.CELL_BUDGET_EXHAUSTED,
			UnderwaterEscapeNavigator.Phase.WAYPOINT_FALLBACK
		));
		assertTrue(SurvivalReflexRuntime.safeLandSearchExhausted(
			UnderwaterEscapeSearch.SearchStatus.CELL_BUDGET_EXHAUSTED,
			UnderwaterEscapeNavigator.Phase.EXHAUSTED
		));
		assertTrue(SurvivalReflexRuntime.safeLandSearchExhausted(
			UnderwaterEscapeSearch.SearchStatus.COMPLETE,
			UnderwaterEscapeNavigator.Phase.EXHAUSTED
		));
	}

	@Test
	void defendRequiresHighHealthOneCloseVisibleThreat() {
		assertTrue(SurvivalReflexRuntime.shouldDefend(0.75D, 1, 4.5D, true, 0.5D));
		assertFalse(SurvivalReflexRuntime.shouldDefend(0.5D, 1, 4.0D, true, 0.5D));
		assertFalse(SurvivalReflexRuntime.shouldDefend(0.75D, 2, 4.0D, true, 0.5D));
		assertFalse(SurvivalReflexRuntime.shouldDefend(0.75D, 1, 4.6D, true, 0.5D));
		assertFalse(SurvivalReflexRuntime.shouldDefend(0.75D, 1, 4.0D, false, 0.5D));
	}

	@Test
	void proactiveDetectionRequiresCloseVisibleLivingHostile() {
		assertTrue(SurvivalReflexRuntime.shouldDetectProactiveThreat(true, true, 8.0D, true));
		assertFalse(SurvivalReflexRuntime.shouldDetectProactiveThreat(true, true, 8.01D, true));
		assertFalse(SurvivalReflexRuntime.shouldDetectProactiveThreat(true, true, 4.0D, false));
		assertFalse(SurvivalReflexRuntime.shouldDetectProactiveThreat(false, true, 4.0D, true));
		assertFalse(SurvivalReflexRuntime.shouldDetectProactiveThreat(true, false, 4.0D, true));
	}

	@Test
	void threatResolutionHonorsDamageCooldown() {
		assertFalse(SurvivalReflexRuntime.mobThreatsResolved(1, 200, 100, 60));
		assertFalse(SurvivalReflexRuntime.mobThreatsResolved(0, 159, 100, 60));
		assertTrue(SurvivalReflexRuntime.mobThreatsResolved(0, 160, 100, 60));
	}

	@Test
	void fleeRaisesButNeverLowersWaterTraversalPenalty() {
		assertEquals(48.0D, SurvivalReflexRuntime.fleeWaterPenalty(3.0D));
		assertEquals(64.0D, SurvivalReflexRuntime.fleeWaterPenalty(64.0D));
	}

	@Test
	void staleTerminalPathEventCannotRejectAnActiveReplacementFleePath() {
		assertFalse(SurvivalReflexRuntime.shouldRejectFleeTarget(Optional.of("CANCELED"), true));
		assertFalse(SurvivalReflexRuntime.shouldRejectFleeTarget(Optional.of("CALC_FAILED"), true));
		assertTrue(SurvivalReflexRuntime.shouldRejectFleeTarget(Optional.of("CALC_FAILED"), false));
		assertFalse(SurvivalReflexRuntime.shouldRejectFleeTarget(Optional.of("CALC_FINISHED_NOW_EXECUTING"), false));
	}

	@Test
	void newDangerPreemptsSafetyHoldButDoesNotRestartActiveReflex() {
		assertTrue(SurvivalReflexRuntime.shouldBeginReflex(SurvivalReflexState.IDLE, true));
		assertTrue(SurvivalReflexRuntime.shouldBeginReflex(SurvivalReflexState.AWAITING_PLANNER, true));
		assertFalse(SurvivalReflexRuntime.shouldBeginReflex(SurvivalReflexState.ACTIVE, true));
		assertFalse(SurvivalReflexRuntime.shouldBeginReflex(SurvivalReflexState.AWAITING_PLANNER, false));
	}

	@Test
	void resolvedDrowningHoldTreadsWaterUntilPlannerDecision() {
		assertTrue(SurvivalReflexRuntime.shouldMaintainDrowningSafetyHold(
			SurvivalReflexState.AWAITING_PLANNER, SurvivalReflexCause.DROWNING, true));
		assertFalse(SurvivalReflexRuntime.shouldMaintainDrowningSafetyHold(
			SurvivalReflexState.AWAITING_PLANNER, SurvivalReflexCause.DROWNING, false));
		assertFalse(SurvivalReflexRuntime.shouldMaintainDrowningSafetyHold(
			SurvivalReflexState.ACTIVE, SurvivalReflexCause.DROWNING, true));
		assertFalse(SurvivalReflexRuntime.shouldMaintainDrowningSafetyHold(
			SurvivalReflexState.AWAITING_PLANNER, SurvivalReflexCause.MOB_ATTACK, true));
	}

	@Test
	void resumeRequiresResolvedMatchingSafetyHold() {
		SurvivalReflexSnapshot active = snapshot(SurvivalReflexState.ACTIVE, "hold-1");
		SurvivalReflexSnapshot awaiting = snapshot(SurvivalReflexState.AWAITING_PLANNER, "hold-1");

		assertEquals(SurvivalReflexRuntime.ResumeResult.REFLEX_ACTIVE,
			SurvivalReflexRuntime.validateResume(active, "hold-1"));
		assertEquals(SurvivalReflexRuntime.ResumeResult.NO_SAFETY_HOLD,
			SurvivalReflexRuntime.validateResume(SurvivalReflexSnapshot.idle(), "hold-1"));
		assertEquals(SurvivalReflexRuntime.ResumeResult.STALE_SAFETY_HOLD,
			SurvivalReflexRuntime.validateResume(awaiting, "old-hold"));
		assertEquals(SurvivalReflexRuntime.ResumeResult.RESUMED,
			SurvivalReflexRuntime.validateResume(awaiting, "hold-1"));
	}

	@Test
	void activeAndDrowningHoldOwnActuationWhileBothHoldNormalWork() {
		SurvivalReflexSnapshot active = snapshot(SurvivalReflexState.ACTIVE, "hold-1");
		SurvivalReflexSnapshot awaiting = snapshot(SurvivalReflexState.AWAITING_PLANNER, "hold-1");

		assertTrue(active.ownsActuation());
		assertTrue(active.holdsNormalTasks());
		assertTrue(awaiting.ownsActuation());
		assertTrue(awaiting.holdsNormalTasks());
	}

	private static SurvivalReflexSnapshot snapshot(SurvivalReflexState state, String holdId) {
		return new SurvivalReflexSnapshot(
			state, SurvivalReflexCause.DROWNING, SurvivalReflexAction.SWIM_TO_AIR, 2L, holdId,
			"job-1", "action-1", List.of(), 10.0F, 20.0F, 100, 300, 10L, 20L, 0, null
		);
	}
}
