package ai.moeru.airicraft.agent.tasks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftingProgressTrackerTest {
	@Test
	void takeCompletionUsesPerTakeBaselineInsteadOfHighWaterMark() {
		CraftingProgressTracker tracker = new CraftingProgressTracker();
		tracker.reset();

		tracker.beginTake(10, 4);
		assertTrue(tracker.finishTakeIfInventoryIncreased(14));
		assertEquals(4, tracker.craftedOutputCount());

		tracker.beginTake(2, 4);
		assertTrue(tracker.finishTakeIfInventoryIncreased(6));
		assertEquals(8, tracker.craftedOutputCount());
	}
}
