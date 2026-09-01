package ai.moeru.airicraft.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerItemUseControllerTest {
	@Test
	void ordinaryFoodRequiresMissingHunger() {
		assertTrue(PlayerItemUseController.canStartEating(19, false));
		assertFalse(PlayerItemUseController.canStartEating(20, false));
		assertTrue(PlayerItemUseController.canStartEating(20, true));
	}

	@Test
	void consumptionCompletesOnHungerOrInventoryChange() {
		assertTrue(PlayerItemUseController.consumptionCompleted(12, 16, 3, 3));
		assertTrue(PlayerItemUseController.consumptionCompleted(12, 12, 3, 2));
		assertFalse(PlayerItemUseController.consumptionCompleted(12, 12, 3, 3));
	}
}
