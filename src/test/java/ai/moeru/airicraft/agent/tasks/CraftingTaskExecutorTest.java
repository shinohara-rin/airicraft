package ai.moeru.airicraft.agent.tasks;

import net.minecraft.screen.PlayerScreenHandler;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static ai.moeru.airicraft.agent.tasks.CraftingTaskExecutor.TableNavigationOutcome.FALLBACK;
import static ai.moeru.airicraft.agent.tasks.CraftingTaskExecutor.TableNavigationOutcome.OPEN_TABLE;
import static ai.moeru.airicraft.agent.tasks.CraftingTaskExecutor.TableNavigationOutcome.WAIT;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

	@Test
	void stalledTableNavigationFallsBackAfterTimeout() {
		assertEquals(
			WAIT,
			CraftingTaskExecutor.tableNavigationOutcome(
				Optional.empty(),
				false,
				false,
				CraftingTaskExecutor.TABLE_NAVIGATION_TIMEOUT_TICKS
			)
		);
		assertEquals(
			FALLBACK,
			CraftingTaskExecutor.tableNavigationOutcome(
				Optional.empty(),
				false,
				false,
				CraftingTaskExecutor.TABLE_NAVIGATION_TIMEOUT_TICKS + 1
			)
		);
	}

	@Test
	void terminalNavigationEventsDoNotLoopForever() {
		assertEquals(
			OPEN_TABLE,
			CraftingTaskExecutor.tableNavigationOutcome(Optional.of("AT_GOAL"), false, false, 0)
		);
		assertEquals(
			FALLBACK,
			CraftingTaskExecutor.tableNavigationOutcome(Optional.of("CANCELED"), false, false, 0)
		);
		assertEquals(
			FALLBACK,
			CraftingTaskExecutor.tableNavigationOutcome(Optional.of("CALC_FAILED"), false, false, 0)
		);
	}
}
