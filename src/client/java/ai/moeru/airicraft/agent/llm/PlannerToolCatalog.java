package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class PlannerToolCatalog {
	public static final String TAKE_A_LOOK = "take_a_look";
	public static final String INSPECT_INVENTORY = "inspect_inventory";
	public static final String CHECK_CRAFTABLES = "check_craftables";
	public static final String INSPECT_NEARBY_ENTITIES = "inspect_nearby_entities";
	public static final String FOLLOW_PLAYER = "follow_player";
	public static final String NAVIGATE_TO = "navigate_to";
	public static final String MINE_BLOCKS = "mine_blocks";
	public static final String COLLECT_RESOURCE = "collect_resource";
	public static final String CRAFT_RECIPE = "craft_recipe";
	public static final String DROP_ITEMS = "drop_items";
	public static final String GIVE_PLAYER = "give_player";
	public static final String ATTACK_ENTITY = "attack_entity";
	public static final String USE_ENTITY = "use_entity";
	public static final String CANCEL_TASK = "cancel_task";
	public static final String CLEAR_GOAL = "clear_goal";
	public static final String UPDATE_EVENT_POLICY = "update_event_policy";

	private PlannerToolCatalog() {
	}

	public static List<Map<String, Object>> openAiTools() {
		return List.of(
			tool(TAKE_A_LOOK, "Inspect current first-person view.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("prompt", string("Short prompt describing what to inspect."))
			), List.of()),
			tool(INSPECT_INVENTORY, "Inspect current inventory counts.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("prompt", string("Optional inventory question."))
			), List.of()),
			tool(CHECK_CRAFTABLES, "Check currently executable crafting options.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("prompt", string("Optional crafting question."))
			), List.of()),
			tool(INSPECT_NEARBY_ENTITIES, "List nearby loaded entities with exact selectors such as uuid, name, entityTypeId, distance, and health when available.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("prompt", string("Optional nearby-entity question."))
			), List.of()),
			tool(FOLLOW_PLAYER, "Follow a named player.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("targetPlayer", string("Player name to follow."))
			), List.of("targetPlayer")),
			tool(NAVIGATE_TO, "Navigate to a block position.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("x", number("Block x coordinate.")),
				prop("y", number("Block y coordinate.")),
				prop("z", number("Block z coordinate.")),
				prop("exactY", bool("Whether y must match exactly."))
			), List.of("x", "y", "z", "exactY")),
			tool(MINE_BLOCKS, "Mine matching blocks.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("blockIds", stringArray("Namespaced block ids to mine.")),
				prop("quantity", integer("Number of blocks to mine."))
			), List.of("blockIds", "quantity")),
			tool(COLLECT_RESOURCE, "Collect a supported resource kind.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("resourceKind", enumString("Resource kind.", List.of("WOOD_LOGS"))),
				prop("quantity", integer("Quantity to collect."))
			), List.of("resourceKind", "quantity")),
			tool(CRAFT_RECIPE, "Run a listed crafting recipe, including automatic crafting-table setup for 3x3 recipes.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("recipeId", string("Exact recipe id from check_craftables.")),
				prop("times", integer("Recipe run count."))
			), List.of("recipeId", "times")),
			tool(DROP_ITEMS, "Drop exact items from current inventory at the current position.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("itemId", string("Exact namespaced item id from inspect_inventory itemCounts.")),
				prop("quantity", integer("Number of items to drop."))
			), List.of("itemId", "quantity")),
			tool(GIVE_PLAYER, "Drop exact items for a named nearby player to pick up.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("targetPlayer", string("Nearby player name receiving the items.")),
				prop("itemId", string("Exact namespaced item id from inspect_inventory itemCounts.")),
				prop("quantity", integer("Number of items to drop."))
			), List.of("targetPlayer", "itemId", "quantity")),
			tool(ATTACK_ENTITY, "Attack one nearby entity. Always copy the exact uuid from inspect_nearby_entities or focus, and optionally include name or entityTypeId.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("uuid", optionalString("Exact entity uuid when available.")),
				prop("name", optionalString("Visible custom name or display name when available.")),
				prop("entityTypeId", optionalString("Exact namespaced entity type id, for example minecraft:sheep."))
			), List.of("uuid")),
			tool(USE_ENTITY, "Use current hand or an optional item on one nearby entity. Always copy the exact uuid from inspect_nearby_entities or focus, and optionally include name or entityTypeId.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("uuid", optionalString("Exact entity uuid when available.")),
				prop("name", optionalString("Visible custom name or display name when available.")),
				prop("entityTypeId", optionalString("Exact namespaced entity type id, for example minecraft:sheep.")),
				prop("itemId", optionalString("Optional exact namespaced item id to equip first, for example minecraft:shears."))
			), List.of("uuid")),
			tool(CANCEL_TASK, "Cancel the current task or job.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("reason", string("Optional cancellation reason."))
			), List.of()),
			tool(CLEAR_GOAL, "Clear the current goal.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed."))
			), List.of()),
			tool(UPDATE_EVENT_POLICY, "Update future event routing policy.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("clearAll", bool("Clear all active planner policy rules.")),
				prop("removeRuleIds", stringArray("Rule ids to remove.")),
				prop("upserts", array("Policy rule upserts.", policyUpsertSchema()))
			), List.of())
		);
	}

	public static PlannerToolCall parseToolCall(JsonObject object) {
		return parseToolCall(object, PlannerToolRegistry.empty());
	}

	public static PlannerToolCall parseToolCall(JsonObject object, PlannerToolRegistry toolRegistry) {
		if (object == null) {
			throw new JsonParseException("Missing tool call");
		}
		String id = getString(object, "id").orElse(null);
		String type = getString(object, "type").orElse("function");
		if (!"function".equals(type)) {
			throw new JsonParseException("Unsupported tool call type: " + type);
		}
		JsonObject function = object.has("function") && object.get("function").isJsonObject()
			? object.getAsJsonObject("function")
			: null;
		if (function == null) {
			throw new JsonParseException("Missing tool function");
		}
		String name = getString(function, "name").orElseThrow(() -> new JsonParseException("Missing tool name"));
		JsonObject arguments = parseArguments(getString(function, "arguments").orElse("{}"));
		validateArguments(name, arguments, toolRegistry);
		return new PlannerToolCall(id, name, arguments, getString(arguments, "narration").orElse(null), object);
	}

	public static JsonArray toOpenAiToolCalls(List<PlannerToolCall> toolCalls) {
		JsonArray array = new JsonArray();
		if (toolCalls == null) {
			return array;
		}
		for (PlannerToolCall toolCall : toolCalls) {
			if (toolCall == null) {
				continue;
			}
			if (toolCall.rawToolCall() != null && toolCall.rawToolCall().isJsonObject()) {
				JsonObject replayed = toolCall.rawToolCall().getAsJsonObject().deepCopy();
				replayed.addProperty("id", toolCall.id());
				if (!replayed.has("type") || replayed.get("type").isJsonNull()) {
					replayed.addProperty("type", "function");
				}
				array.add(replayed);
				continue;
			}
			JsonObject function = new JsonObject();
			function.addProperty("name", toolCall.name());
			function.addProperty("arguments", toolCall.arguments().toString());
			JsonObject item = new JsonObject();
			item.addProperty("id", toolCall.id());
			item.addProperty("type", "function");
			item.add("function", function);
			array.add(item);
		}
		return array;
	}

	public static boolean isReadTool(String name) {
		return switch (normalizeName(name)) {
			case TAKE_A_LOOK, INSPECT_INVENTORY, CHECK_CRAFTABLES, INSPECT_NEARBY_ENTITIES -> true;
			default -> false;
		};
	}

	public static boolean isKnownTool(String name) {
		return switch (normalizeName(name)) {
			case TAKE_A_LOOK,
				INSPECT_INVENTORY,
				CHECK_CRAFTABLES,
				INSPECT_NEARBY_ENTITIES,
				FOLLOW_PLAYER,
				NAVIGATE_TO,
				MINE_BLOCKS,
				COLLECT_RESOURCE,
				CRAFT_RECIPE,
				DROP_ITEMS,
				GIVE_PLAYER,
				ATTACK_ENTITY,
				USE_ENTITY,
				CANCEL_TASK,
				CLEAR_GOAL,
				UPDATE_EVENT_POLICY -> true;
			default -> false;
		};
	}

	public static String normalizeName(String name) {
		return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
	}

	public static Map<String, Object> toolForProvider(
		String name,
		String description,
		Map<String, Object> properties,
		List<String> required
	) {
		return tool(name, description, properties, required);
	}

	@SafeVarargs
	public static Map<String, Object> propertiesForProvider(Map.Entry<String, Object>... entries) {
		return properties(entries);
	}

	public static Map.Entry<String, Object> propForProvider(String name, Map<String, Object> schema) {
		return prop(name, schema);
	}

	public static Map<String, Object> stringForProvider(String description) {
		return string(description);
	}

	public static Map<String, Object> optionalStringForProvider(String description) {
		return optionalString(description);
	}

	public static Map<String, Object> enumStringForProvider(String description, List<String> values) {
		return enumString(description, values);
	}

	private static JsonObject parseArguments(String raw) {
		if (raw == null || raw.isBlank()) {
			return new JsonObject();
		}
		try {
			JsonElement parsed = JsonParser.parseString(raw);
			if (!parsed.isJsonObject()) {
				throw new JsonParseException("Tool arguments must be a JSON object");
			}
			return parsed.getAsJsonObject();
		}
		catch (JsonParseException exception) {
			throw exception;
		}
		catch (RuntimeException exception) {
			throw new JsonParseException("Invalid tool arguments", exception);
		}
	}

	private static void validateArguments(String name, JsonObject arguments, PlannerToolRegistry toolRegistry) {
		if (!isKnownTool(name)) {
			Objects.requireNonNullElse(toolRegistry, PlannerToolRegistry.empty()).validateProviderArguments(name, arguments);
			return;
		}
		validateArguments(name, arguments);
	}

	private static void validateArguments(String name, JsonObject arguments) {
		switch (normalizeName(name)) {
			case TAKE_A_LOOK, INSPECT_INVENTORY, CHECK_CRAFTABLES, INSPECT_NEARBY_ENTITIES, CLEAR_GOAL -> {
			}
			case FOLLOW_PLAYER -> requireString(arguments, "targetPlayer");
			case NAVIGATE_TO -> {
				requireInt(arguments, "x");
				requireInt(arguments, "y");
				requireInt(arguments, "z");
				requireBoolean(arguments, "exactY");
			}
			case MINE_BLOCKS -> {
				requireStringArray(arguments, "blockIds");
				requirePositiveInt(arguments, "quantity");
			}
			case COLLECT_RESOURCE -> {
				String kind = requireString(arguments, "resourceKind");
				if (!"WOOD_LOGS".equals(kind)) {
					throw new JsonParseException("Unsupported resourceKind: " + kind);
				}
				requirePositiveInt(arguments, "quantity");
			}
			case CRAFT_RECIPE -> {
				requireString(arguments, "recipeId");
				requirePositiveInt(arguments, "times");
			}
			case DROP_ITEMS -> {
				requireString(arguments, "itemId");
				requirePositiveInt(arguments, "quantity");
			}
			case GIVE_PLAYER -> {
				requireString(arguments, "targetPlayer");
				requireString(arguments, "itemId");
				requirePositiveInt(arguments, "quantity");
			}
			case ATTACK_ENTITY -> requireEntitySelector(arguments);
			case USE_ENTITY -> {
				requireEntitySelector(arguments);
				if (arguments.has("itemId") && !arguments.get("itemId").isJsonNull()) {
					requireString(arguments, "itemId");
				}
			}
			case CANCEL_TASK -> {
			}
			case UPDATE_EVENT_POLICY -> validatePolicyArguments(arguments);
			default -> throw new JsonParseException("Unknown planner tool: " + name);
		}
	}

	private static void requireEntitySelector(JsonObject arguments) {
		boolean hasUuid = getString(arguments, "uuid").isPresent();
		boolean hasName = getString(arguments, "name").isPresent();
		boolean hasEntityTypeId = getString(arguments, "entityTypeId").isPresent();
		if (!hasUuid && !hasName && !hasEntityTypeId) {
			throw new JsonParseException("entity selector requires uuid, name, or entityTypeId");
		}
	}

	private static void validatePolicyArguments(JsonObject arguments) {
		if (arguments.has("clearAll") && !arguments.get("clearAll").isJsonPrimitive()) {
			throw new JsonParseException("clearAll must be boolean");
		}
		if (arguments.has("removeRuleIds")) {
			requireStringArray(arguments, "removeRuleIds", true);
		}
		if (!arguments.has("upserts") || arguments.get("upserts").isJsonNull()) {
			return;
		}
		if (!arguments.get("upserts").isJsonArray()) {
			throw new JsonParseException("upserts must be an array");
		}
		for (JsonElement element : arguments.getAsJsonArray("upserts")) {
			if (!element.isJsonObject()) {
				throw new JsonParseException("upsert must be an object");
			}
			JsonObject upsert = element.getAsJsonObject();
			requireString(upsert, "effect");
			if (!upsert.has("match") || !upsert.get("match").isJsonObject()) {
				throw new JsonParseException("upsert match must be an object");
			}
			requireString(upsert.getAsJsonObject("match"), "eventType");
		}
	}

	private static String requireString(JsonObject object, String key) {
		String value = getString(object, key).orElse(null);
		if (value == null || value.isBlank()) {
			throw new JsonParseException(key + " must be a non-empty string");
		}
		return value;
	}

	private static int requireInt(JsonObject object, String key) {
		try {
			if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
				throw new JsonParseException(key + " must be an integer");
			}
			return object.get(key).getAsInt();
		}
		catch (RuntimeException exception) {
			throw new JsonParseException(key + " must be an integer", exception);
		}
	}

	private static int requirePositiveInt(JsonObject object, String key) {
		int value = requireInt(object, key);
		if (value <= 0) {
			throw new JsonParseException(key + " must be positive");
		}
		return value;
	}

	private static void requireBoolean(JsonObject object, String key) {
		try {
			if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
				throw new JsonParseException(key + " must be boolean");
			}
			object.get(key).getAsBoolean();
		}
		catch (RuntimeException exception) {
			throw new JsonParseException(key + " must be boolean", exception);
		}
	}

	private static void requireStringArray(JsonObject object, String key) {
		requireStringArray(object, key, false);
	}

	private static void requireStringArray(JsonObject object, String key, boolean allowEmpty) {
		if (!object.has(key) || !object.get(key).isJsonArray()) {
			throw new JsonParseException(key + " must be a string array");
		}
		JsonArray array = object.getAsJsonArray(key);
		if (!allowEmpty && array.isEmpty()) {
			throw new JsonParseException(key + " must not be empty");
		}
		for (JsonElement element : array) {
			if (!element.isJsonPrimitive() || element.getAsString().isBlank()) {
				throw new JsonParseException(key + " must contain non-empty strings");
			}
		}
	}

	private static Optional<String> getString(JsonObject object, String fieldName) {
		if (object == null || !object.has(fieldName) || object.get(fieldName).isJsonNull()) {
			return Optional.empty();
		}
		try {
			String value = object.get(fieldName).getAsString();
			return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
		}
		catch (RuntimeException exception) {
			return Optional.empty();
		}
	}

	private static Map<String, Object> tool(String name, String description, Map<String, Object> properties, List<String> required) {
		LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
		parameters.put("type", "object");
		parameters.put("properties", properties);
		parameters.put("required", required);
		parameters.put("additionalProperties", false);
		return Map.of(
			"type", "function",
			"function", Map.of(
				"name", name,
				"description", description,
				"parameters", parameters
			)
		);
	}

	@SafeVarargs
	private static Map<String, Object> properties(Map.Entry<String, Object>... entries) {
		LinkedHashMap<String, Object> map = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : entries) {
			map.put(entry.getKey(), entry.getValue());
		}
		return map;
	}

	private static Map.Entry<String, Object> prop(String name, Object schema) {
		return Map.entry(name, schema);
	}

	private static Map<String, Object> string(String description) {
		return Map.of("type", "string", "description", description);
	}

	private static Map<String, Object> optionalString(String description) {
		return Map.of("type", "string", "description", description);
	}

	private static Map<String, Object> integer(String description) {
		return Map.of("type", "integer", "description", description);
	}

	private static Map<String, Object> number(String description) {
		return Map.of("type", "number", "description", description);
	}

	private static Map<String, Object> bool(String description) {
		return Map.of("type", "boolean", "description", description);
	}

	private static Map<String, Object> enumString(String description, List<String> values) {
		LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "string");
		schema.put("description", description);
		schema.put("enum", values);
		return schema;
	}

	private static Map<String, Object> stringArray(String description) {
		return array(description, string("Array item."));
	}

	private static Map<String, Object> array(String description, Object items) {
		LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "array");
		schema.put("description", description);
		schema.put("items", items);
		return schema;
	}

	private static Map<String, Object> policyUpsertSchema() {
		return Map.of(
			"type", "object",
			"properties", properties(
				prop("ruleId", string("Optional stable rule id.")),
				prop("effect", enumString("Policy effect.", List.of("allow", "ignore", "semantic_only", "trigger_only"))),
				prop("match", Map.of(
					"type", "object",
					"properties", properties(
						prop("eventType", string("Event type to match.")),
						prop("player", string("Optional player match.")),
						prop("speaker", string("Optional speaker match.")),
						prop("actor", string("Optional actor match.")),
						prop("itemId", string("Optional item id match.")),
						prop("damageTypeId", string("Optional damage type id match.")),
						prop("attackerName", string("Optional attacker name match."))
					),
					"required", List.of("eventType"),
					"additionalProperties", false
				)),
				prop("reason", string("Optional reason."))
			),
			"required", List.of("effect", "match"),
			"additionalProperties", false
		);
	}
}
