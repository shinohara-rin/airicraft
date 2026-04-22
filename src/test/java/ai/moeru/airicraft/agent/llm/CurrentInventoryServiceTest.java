package ai.moeru.airicraft.agent.llm;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static ai.moeru.airicraft.agent.llm.CurrentInventoryService.CraftingTableAccess.CAN_CRAFT;
import static ai.moeru.airicraft.agent.llm.CurrentInventoryService.CraftingTableAccess.IN_INVENTORY;
import static ai.moeru.airicraft.agent.llm.CurrentInventoryService.CraftingTableAccess.MISSING;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CurrentInventoryServiceTest {
	@Test
	void unusableNearbyTableWithoutPortableSetupCountsAsMissing() {
		assertEquals(
			MISSING,
			CurrentInventoryService.craftingTableAccess(false, false, Map.of())
		);
	}

	@Test
	void portableSetupStillCountsExecutableWithoutUsableNearbyTable() {
		assertEquals(
			IN_INVENTORY,
			CurrentInventoryService.craftingTableAccess(false, false, Map.of("minecraft:crafting_table", 1))
		);
		assertEquals(
			CAN_CRAFT,
			CurrentInventoryService.craftingTableAccess(false, false, Map.of("minecraft:oak_planks", 4))
		);
	}
}
