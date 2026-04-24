package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityInteractionTaskExecutorTest {
	@Test
	void waitsForTransientBusyStateBeforeFailing() {
		assertTrue(EntityInteractionTaskExecutor.shouldWaitForBusyState(0));
		assertTrue(EntityInteractionTaskExecutor.shouldWaitForBusyState(99));
		assertFalse(EntityInteractionTaskExecutor.shouldWaitForBusyState(100));
	}

	@Test
	void refreshesBaritoneChaseGoalWhenTargetMovedEnoughOrRefreshIntervalElapsed() {
		GoalPosition current = new GoalPosition(10, 64, 20, false);

		assertTrue(EntityInteractionTaskExecutor.shouldRefreshChaseGoal(null, current, 0));
		assertFalse(EntityInteractionTaskExecutor.shouldRefreshChaseGoal(current, current, 0));
		assertFalse(EntityInteractionTaskExecutor.shouldRefreshChaseGoal(current, new GoalPosition(11, 64, 20, false), 0));
		assertTrue(EntityInteractionTaskExecutor.shouldRefreshChaseGoal(current, new GoalPosition(12, 64, 20, false), 0));
		assertTrue(EntityInteractionTaskExecutor.shouldRefreshChaseGoal(current, current, 10));
	}

	@Test
	void directChaseRequiresCloseVisibleAndNotStuck() {
		assertTrue(EntityInteractionTaskExecutor.shouldUseDirectChase(9.9D, true, false));

		assertFalse(EntityInteractionTaskExecutor.shouldUseDirectChase(10.1D, true, false));
		assertFalse(EntityInteractionTaskExecutor.shouldUseDirectChase(9.9D, false, false));
		assertFalse(EntityInteractionTaskExecutor.shouldUseDirectChase(9.9D, true, true));
	}

	@Test
	void attackModeDefaultsToKillAndParsesHitOnce() {
		assertEquals(EntityAttackMode.KILL, EntityAttackMode.fromWireValue(null));
		assertEquals(EntityAttackMode.KILL, EntityAttackMode.fromWireValue("kill"));
		assertEquals(EntityAttackMode.HIT_ONCE, EntityAttackMode.fromWireValue("hit_once"));
	}
}
