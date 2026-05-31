package ai.moeru.airicraft.agent.tasks;

import net.minecraft.recipe.NetworkRecipeId;
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

	@Test
	void visibleScreensDoNotDetermineCraftingReadiness() {
		assertFalse(CraftingTaskExecutor.isVisibleScreenBlockingCrafting(null));
		assertFalse(CraftingTaskExecutor.isVisibleScreenBlockingCrafting("InventoryScreen"));
		assertFalse(CraftingTaskExecutor.isVisibleScreenBlockingCrafting("ChatScreen"));
		assertFalse(CraftingTaskExecutor.isVisibleScreenBlockingCrafting("GameMenuScreen"));
		assertFalse(CraftingTaskExecutor.isVisibleScreenBlockingCrafting("GenericContainerScreen"));
		assertFalse(CraftingTaskExecutor.isVisibleScreenBlockingCrafting("HandledScreen"));
	}

	@Test
	void craftPlanCarriesResolvedNetworkRecipeId() {
		NetworkRecipeId networkRecipeId = new NetworkRecipeId(42);
		CraftingOpportunityResolver.CraftingRecipeResolution resolution = new CraftingOpportunityResolver.CraftingRecipeResolution(
			networkRecipeId,
			null,
			4,
			2,
			CraftingGridKind.PLAYER_2X2,
			java.util.List.of(),
			null
		);

		CraftingTaskExecutor.CraftingPlan plan = CraftingTaskExecutor.toCraftingPlanForTests(resolution);

		assertEquals(networkRecipeId, plan.networkRecipeId());
		assertEquals(8, plan.targetOutputCount());
	}

	@Test
	void recipeFillRequestUsesHandlerSyncId() {
		NetworkRecipeId networkRecipeId = new NetworkRecipeId(7);

		CraftingTaskExecutor.RecipeFillRequest request = CraftingTaskExecutor.recipeFillRequestForTests(123, networkRecipeId);

		assertEquals(123, request.syncId());
		assertEquals(networkRecipeId, request.networkRecipeId());
		assertFalse(request.craftAll());
	}
}
