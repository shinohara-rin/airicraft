package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlannerToolCatalogTest {
	@Test
	void parsesDiscoverToolsWithBoundedResultCount() {
		PlannerToolCall call = PlannerToolCatalog.parseToolCall(toolCall("""
			{"query":"smelting","maxResults":3}
			"""));

		assertEquals(PlannerToolCatalog.DISCOVER_TOOLS, call.name());
		assertEquals("smelting", call.arguments().get("query").getAsString());
		assertEquals(3, call.arguments().get("maxResults").getAsInt());
	}

	@Test
	void rejectsDiscoverToolsResultCountOutsideTheCardLimit() {
		assertThrows(RuntimeException.class, () -> PlannerToolCatalog.parseToolCall(toolCall("""
			{"query":"smelting","maxResults":6}
			""")));
	}

	@Test
	void parsesEquipmentAndFoodToolsWithExactItemIds() {
		PlannerToolCall equip = PlannerToolCatalog.parseToolCall(toolCall(
			PlannerToolCatalog.EQUIP_ITEM,
			"{\"itemId\":\"minecraft:iron_chestplate\"}"
		));
		PlannerToolCall eat = PlannerToolCatalog.parseToolCall(toolCall(
			PlannerToolCatalog.EAT_FOOD,
			"{\"itemId\":\"minecraft:bread\"}"
		));

		assertEquals("minecraft:iron_chestplate", equip.arguments().get("itemId").getAsString());
		assertEquals("minecraft:bread", eat.arguments().get("itemId").getAsString());
	}

	private static JsonObject toolCall(String arguments) {
		return toolCall(PlannerToolCatalog.DISCOVER_TOOLS, arguments);
	}

	private static JsonObject toolCall(String name, String arguments) {
		JsonObject toolCall = new JsonObject();
		toolCall.addProperty("id", "call_discover");
		toolCall.addProperty("type", "function");
		JsonObject function = new JsonObject();
		function.addProperty("name", name);
		function.addProperty("arguments", arguments);
		toolCall.add("function", function);
		return toolCall;
	}
}
