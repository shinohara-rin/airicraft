package ai.moeru.airicraft.agent.idle;

import ai.moeru.airicraft.agent.llm.PlannerTrigger;
import ai.moeru.airicraft.agent.llm.PlannerTriggerType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdleIdeaSchedulerTest {
	private static final long BASE_MS = 1_000_000_000L;
	private static final List<String> IDEAS = List.of(
		"Idea A",
		"Idea B"
	);

	@Test
	void doesNotFireBeforeInitialDelay() {
		IdleIdeaScheduler scheduler = new IdleIdeaScheduler(new IdleIdeasConfig(true, 30, 90, IDEAS));

		assertTrue(scheduler.tick(true, 1L, BASE_MS).isEmpty());
		assertTrue(scheduler.tick(true, 2L, BASE_MS + 29_999L).isEmpty());
	}

	@Test
	void firesOnceAfterInitialDelay() {
		IdleIdeaScheduler scheduler = new IdleIdeaScheduler(new IdleIdeasConfig(true, 30, 90, IDEAS));

		assertTrue(scheduler.tick(true, 1L, BASE_MS).isEmpty());
		Optional<PlannerTrigger> trigger = scheduler.tick(true, 2L, BASE_MS + 30_000L);

		assertTrue(trigger.isPresent());
		assertEquals(PlannerTriggerType.IDLE_THINK, trigger.get().type());
		assertEquals("self", trigger.get().speaker());
		assertTrue(trigger.get().text().startsWith("IDLE THINK:"));
		assertTrue(trigger.get().text().contains("Idea A"));
		assertTrue(trigger.get().text().contains("Idea B"));
	}

	@Test
	void enforcesCooldownBetweenFires() {
		IdleIdeaScheduler scheduler = new IdleIdeaScheduler(new IdleIdeasConfig(true, 30, 90, IDEAS));

		scheduler.tick(true, 1L, BASE_MS);
		assertTrue(scheduler.tick(true, 2L, BASE_MS + 30_000L).isPresent());
		assertTrue(scheduler.tick(true, 3L, BASE_MS + 60_000L).isEmpty());
		assertTrue(scheduler.tick(true, 4L, BASE_MS + 119_999L).isEmpty());
		assertTrue(scheduler.tick(true, 5L, BASE_MS + 120_000L).isPresent());
	}

	@Test
	void resetsIdleTimerWhenJobBecomesActive() {
		IdleIdeaScheduler scheduler = new IdleIdeaScheduler(new IdleIdeasConfig(true, 30, 90, IDEAS));

		scheduler.tick(true, 1L, BASE_MS);
		assertTrue(scheduler.tick(false, 2L, BASE_MS + 20_000L).isEmpty());
		assertTrue(scheduler.tick(true, 3L, BASE_MS + 40_000L).isEmpty());
		assertTrue(scheduler.tick(true, 4L, BASE_MS + 70_000L).isPresent());
	}

	@Test
	void doesNotFireWhenDisabled() {
		IdleIdeaScheduler scheduler = new IdleIdeaScheduler(new IdleIdeasConfig(false, 30, 90, IDEAS));

		assertTrue(scheduler.tick(true, 1L, BASE_MS).isEmpty());
		assertTrue(scheduler.tick(true, 2L, BASE_MS + 1_000_000L).isEmpty());
	}

	@Test
	void doesNotFireWhenIdeasEmpty() {
		IdleIdeaScheduler scheduler = new IdleIdeaScheduler(new IdleIdeasConfig(true, 30, 90, List.of()));

		assertTrue(scheduler.tick(true, 1L, BASE_MS).isEmpty());
		assertTrue(scheduler.tick(true, 2L, BASE_MS + 1_000_000L).isEmpty());
	}

	@Test
	void resetClearsState() {
		IdleIdeaScheduler scheduler = new IdleIdeaScheduler(new IdleIdeasConfig(true, 30, 90, IDEAS));

		scheduler.tick(true, 1L, BASE_MS);
		assertTrue(scheduler.tick(true, 2L, BASE_MS + 30_000L).isPresent());
		scheduler.reset();
		assertTrue(scheduler.tick(true, 3L, BASE_MS + 31_000L).isEmpty());
		assertTrue(scheduler.tick(true, 4L, BASE_MS + 61_000L).isPresent());
	}

	@Test
	void updateConfigSwitchesIdeas() {
		IdleIdeaScheduler scheduler = new IdleIdeaScheduler(new IdleIdeasConfig(true, 30, 90, IDEAS));

		scheduler.tick(true, 1L, BASE_MS);
		assertTrue(scheduler.tick(true, 2L, BASE_MS + 30_000L).isPresent());

		scheduler.updateConfig(new IdleIdeasConfig(false, 30, 90, IDEAS));
		assertTrue(scheduler.tick(true, 3L, BASE_MS + 200_000L).isEmpty());
		assertFalse(scheduler.tick(true, 4L, BASE_MS + 1_000_000L).isPresent());
	}
}
