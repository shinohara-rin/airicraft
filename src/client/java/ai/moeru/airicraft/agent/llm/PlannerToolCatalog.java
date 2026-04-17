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
import java.util.Optional;

public final class PlannerToolCatalog {
	public static final String TAKE_A_LOOK = "take_a_look";
	public static final String INSPECT_INVENTORY = "inspect_inventory";
	public static final String INSPECT_RECIPES = "inspect_recipes";
	public static final String FOLLOW_PLAYER = "follow_player";
	public static final String NAVIGATE_TO = "navigate_to";
	public static final String MINE_BLOCKS = "mine_blocks";
	public static final String COLLECT_RESOURCE = "collect_resource";
	public static final String CRAFT_RECIPE = "craft_recipe";
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
			tool(INSPECT_RECIPES, "Inspect current 2x2 crafting opportunities.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("prompt", string("Optional crafting question."))
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
			tool(CRAFT_RECIPE, "Run a listed 2x2 crafting recipe.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("recipeId", string("Exact recipe id from inspect_recipes.")),
				prop("times", integer("Recipe run count."))
			), List.of("recipeId", "times")),
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
		validateArguments(name, arguments);
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
				array.add(toolCall.rawToolCall());
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
			case TAKE_A_LOOK, INSPECT_INVENTORY, INSPECT_RECIPES -> true;
			default -> false;
		};
	}

	public static boolean isKnownTool(String name) {
		return switch (normalizeName(name)) {
			case TAKE_A_LOOK,
				INSPECT_INVENTORY,
				INSPECT_RECIPES,
				FOLLOW_PLAYER,
				NAVIGATE_TO,
				MINE_BLOCKS,
				COLLECT_RESOURCE,
				CRAFT_RECIPE,
				CANCEL_TASK,
				CLEAR_GOAL,
				UPDATE_EVENT_POLICY -> true;
			default -> false;
		};
	}

	public static String normalizeName(String name) {
		return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
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

	private static void validateArguments(String name, JsonObject arguments) {
		switch (normalizeName(name)) {
			case TAKE_A_LOOK, INSPECT_INVENTORY, INSPECT_RECIPES, CLEAR_GOAL -> {
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
			case CANCEL_TASK -> {
			}
			case UPDATE_EVENT_POLICY -> validatePolicyArguments(arguments);
			default -> throw new JsonParseException("Unknown planner tool: " + name);
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
