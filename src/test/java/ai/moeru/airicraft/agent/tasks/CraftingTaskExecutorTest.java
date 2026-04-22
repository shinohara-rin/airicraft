package ai.moeru.airicraft.agent.tasks;

import net.minecraft.screen.PlayerScreenHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftingTaskExecutorTest {
	@Test
	void portableCraftingTableSourceSlotsIncludeOffhand() {
		assertTrue(CraftingTaskExecutor.isPortableCraftingTableSourceSlot(PlayerScreenHandler.INVENTORY_START));
		assertTrue(CraftingTaskExecutor.isPortableCraftingTableSourceSlot(PlayerScreenHandler.HOTBAR_START));
		assertTrue(CraftingTaskExecutor.isPortableCraftingTableSourceSlot(PlayerScreenHandler.OFFHAND_ID));

		assertFalse(CraftingTaskExecutor.isPortableCraftingTableSourceSlot(PlayerScreenHandler.CRAFTING_INPUT_START));
		assertFalse(CraftingTaskExecutor.isPortableCraftingTableSourceSlot(PlayerScreenHandler.EQUIPMENT_START));
	}
}
