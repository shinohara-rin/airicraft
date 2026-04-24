package ai.moeru.airicraft.agent.actions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimitiveActionRegistryTest {
	@Test
	void defaultRegistryExposesVersionedPrimitiveMetadata() {
		PrimitiveActionRegistry registry = PrimitiveActionRegistry.defaults();

		PrimitiveActionMetadata craftItem = registry.require("craft_item");

		assertEquals("craft_item", craftItem.id());
		assertEquals(1, craftItem.version());
		assertTrue(craftItem.foregroundActuation());
		assertTrue(craftItem.cancellable());
		assertTrue(craftItem.capabilityTags().contains("crafting"));
		assertTrue(craftItem.parameterSchema().containsKey("itemId"));
		assertTrue(craftItem.parameterSchema().containsKey("quantity"));
		assertFalse(craftItem.failureCodes().isEmpty());
	}

	@Test
	void defaultRegistryListsReusableWorldPrimitives() {
		PrimitiveActionRegistry registry = PrimitiveActionRegistry.defaults();

		assertTrue(registry.contains("inspect_inventory"));
		assertTrue(registry.contains("find_block"));
		assertTrue(registry.contains("pathfind_to"));
		assertTrue(registry.contains("mine_block"));
		assertTrue(registry.contains("interact_block"));
		assertTrue(registry.contains("place_block"));
		assertTrue(registry.contains("craft_item"));
		assertTrue(registry.contains("wait_for_fact"));
	}
}
