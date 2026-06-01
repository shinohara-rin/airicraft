package ai.moeru.airicraft.agent.tasks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SmeltingTaskExecutorTest {
	@Test
	void fuelItemsNeededRoundsUpCookTime() {
		assertEquals(0, SmeltingTaskExecutor.fuelItemsNeeded(0, 1600));
		assertEquals(1, SmeltingTaskExecutor.fuelItemsNeeded(200, 1600));
		assertEquals(2, SmeltingTaskExecutor.fuelItemsNeeded(1800, 1600));
	}
}
