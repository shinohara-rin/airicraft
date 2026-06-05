package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.tasks.EntityAttackMode;
import ai.moeru.airicraft.agent.tasks.ReturnToSurfaceStepArgs;
import ai.moeru.airicraft.agent.tasks.SmeltingFuelMode;
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
import java.util.function.Consumer;

public final class PlannerToolCatalog {
	public static final String TAKE_A_LOOK = "take_a_look";
	public static final String INSPECT_WORLD = "inspect_world";
	public static final String INSPECT_INVENTORY = "inspect_inventory";
	public static final String CHECK_CRAFTABLES = "check_craftables";
	public static final String CHECK_SMELTABLES = "check_smeltables";
	public static final String INSPECT_SMELTING = "inspect_smelting";
	public static final String INSPECT_NEARBY_ENTITIES = "inspect_nearby_entities";
	public static final String FOLLOW_PLAYER = "follow_player";
	public static final String NAVIGATE_TO = "navigate_to";
	public static final String RETURN_TO_SURFACE = "return_to_surface";
	public static final String MINE_BLOCKS = "mine_blocks";
	public static final String ENSURE_BLOCKS_IN_INVENTORY = "ensure_blocks_in_inventory";
	public static final String COLLECT_RESOURCE = "collect_resource";
	public static final String CRAFT_RECIPE = "craft_recipe";
	public static final String SMELT_ITEMS = "smelt_items";
	public static final String COLLECT_SMELTED_ITEMS = "collect_smelted_items";
	public static final String CANCEL_SMELTING = "cancel_smelting";
	public static final String DROP_ITEMS = "drop_items";
	public static final String GIVE_PLAYER = "give_player";
	public static final String ATTACK_ENTITY = "attack_entity";
	public static final String USE_ENTITY = "use_entity";
	public static final String PLACE_BLOCK = "place_block";
	public static final String USE_BLOCK = "use_block";
	public static final String CANCEL_TASK = "cancel_task";
	public static final String CLEAR_GOAL = "clear_goal";
	public static final String UPDATE_EVENT_POLICY = "update_event_policy";

	private static final Consumer<JsonObject> NO_ARGUMENT_VALIDATION = arguments -> {
	};
	private static final List<BuiltInTool> BUILT_IN_TOOLS = List.of(
		builtInTool(TAKE_A_LOOK, true, tool(TAKE_A_LOOK, "Inspect current first-person view.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("prompt", string("Short prompt describing what to inspect.")),
				prop("direction", enumString("Optional compass direction to face before capture.", List.of(
					"north",
					"northeast",
					"east",
					"southeast",
					"south",
					"southwest",
					"west",
					"northwest"
				))),
				prop("x", integer("Optional target block x coordinate. Provide x, y, and z together.")),
				prop("y", integer("Optional target block y coordinate. Provide x, y, and z together.")),
				prop("z", integer("Optional target block z coordinate. Provide x, y, and z together.")),
				prop("targetPlayer", optionalString("Optional loaded player name to look at before capture."))
			), List.of()), PlannerToolCatalog::validateTakeALookArguments),
		builtInTool(INSPECT_WORLD, true, tool(INSPECT_WORLD, "Inspect exact loaded world block state with fixed query modes.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("mode", enumString("World query mode.", List.of("inspect_area", "find_blocks", "find_placement_sites"))),
				prop("scope", enumString("Query scope.", List.of("self", "center", "box"))),
				prop("x", integer("Center block x coordinate for scope=center.")),
				prop("y", integer("Center block y coordinate for scope=center.")),
				prop("z", integer("Center block z coordinate for scope=center.")),
				prop("x1", integer("First box corner x coordinate for scope=box.")),
				prop("y1", integer("First box corner y coordinate for scope=box.")),
				prop("z1", integer("First box corner z coordinate for scope=box.")),
				prop("x2", integer("Second box corner x coordinate for scope=box.")),
				prop("y2", integer("Second box corner y coordinate for scope=box.")),
				prop("z2", integer("Second box corner z coordinate for scope=box.")),
				prop("horizontalRadius", integer("Horizontal radius for scope=self or scope=center. Default 8, maximum 16.")),
				prop("verticalRadius", integer("Vertical radius for scope=self or scope=center. Default 4, maximum 8.")),
				prop("maxResults", integer("Maximum search results. Default 32, maximum 64.")),
				prop("blockIds", stringArray("Block ids for find_blocks.")),
				prop("stateFilters", stringArray("Exact block-state filters for find_blocks, using key=value syntax.")),
				prop("targetMaterial", enumString("Allowed target block material for find_placement_sites.", List.of("air", "replaceable", "air_or_replaceable"))),
				prop("supportBlockIds", stringArray("Optional block ids required directly below each placement target.")),
				prop("supportStateFilters", stringArray("Exact support block-state filters using key=value syntax.")),
				prop("requireSolidTopSupport", bool("Whether the support block top face must be solid.")),
				prop("requireAirAbove", bool("Whether the block above the target must be air or replaceable.")),
				prop("requireStandableAdjacent", bool("Whether at least one adjacent standable player position is required. Default true.")),
				prop("requireWithinInteractionRange", bool("Whether the target must be within current interaction range.")),
				prop("nearbyRequiredBlockIds", stringArray("Optional nearby block ids required around each placement target.")),
				prop("nearbyRequiredHorizontalRadius", integer("Horizontal radius for nearbyRequiredBlockIds. Default 4, maximum 16.")),
				prop("nearbyRequiredVerticalRadius", integer("Vertical radius for nearbyRequiredBlockIds. Default 1, maximum 8."))
			), List.of("mode", "scope")), PlannerToolCatalog::validateInspectWorldArguments),
		builtInTool(INSPECT_INVENTORY, true, tool(INSPECT_INVENTORY, "Inspect current inventory counts.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("prompt", string("Optional inventory question."))
			), List.of()), NO_ARGUMENT_VALIDATION),
		builtInTool(CHECK_CRAFTABLES, true, tool(CHECK_CRAFTABLES, "Check currently executable crafting options.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("prompt", string("Optional crafting question."))
			), List.of()), NO_ARGUMENT_VALIDATION),
		builtInTool(CHECK_SMELTABLES, true, tool(CHECK_SMELTABLES, "Check currently executable smelting options and ranked furnace candidates.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("prompt", string("Optional smelting question."))
			), List.of()), NO_ARGUMENT_VALIDATION),
		builtInTool(INSPECT_SMELTING, true, tool(INSPECT_SMELTING, "Inspect Airicraft-owned smelting processes and nearby furnace observations.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("prompt", string("Optional smelting status question."))
			), List.of()), NO_ARGUMENT_VALIDATION),
		builtInTool(INSPECT_NEARBY_ENTITIES, true, tool(INSPECT_NEARBY_ENTITIES, "List nearby loaded entities with exact selectors such as uuid, name, entityTypeId, distance, and health when available.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("prompt", string("Optional nearby-entity question."))
			), List.of()), NO_ARGUMENT_VALIDATION),
		builtInTool(FOLLOW_PLAYER, false, tool(FOLLOW_PLAYER, "Follow a named player.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("targetPlayer", string("Player name to follow."))
			), List.of("targetPlayer")), PlannerToolCatalog::validateFollowPlayerArguments),
		builtInTool(NAVIGATE_TO, false, tool(NAVIGATE_TO, "Navigate to a block position.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("x", number("Block x coordinate.")),
				prop("y", number("Block y coordinate.")),
				prop("z", number("Block z coordinate.")),
				prop("exactY", bool("Whether y must match exactly."))
			), List.of("x", "y", "z", "exactY")), PlannerToolCatalog::validateNavigateToArguments),
		builtInTool(RETURN_TO_SURFACE, false, tool(RETURN_TO_SURFACE, "Return to the remembered surface or last safe ground after mining. Optionally tower upward with filler blocks if trapped in a shaft.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("useTowering", bool("Whether the executor may build a pillar underfoot while jumping if path navigation cannot return to the surface.")),
				prop("fillerBlockIds", stringArray("Optional namespaced block/item ids to use for towering. Omit to use defaults: " + String.join(", ", ReturnToSurfaceStepArgs.DEFAULT_FILLER_BLOCK_IDS) + "."))
			), List.of()), PlannerToolCatalog::validateReturnToSurfaceArguments),
		builtInTool(MINE_BLOCKS, false, tool(MINE_BLOCKS, "Mine matching blocks by block id. Do not pass item ids from inventory itemCounts.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("blockIds", stringArray("Namespaced block ids to mine, for example minecraft:iron_ore. These must be block ids, not item ids such as minecraft:raw_iron.")),
				prop("quantity", integer("Number of blocks to mine."))
			), List.of("blockIds", "quantity")), PlannerToolCatalog::validateMineBlocksArguments),
		builtInTool(ENSURE_BLOCKS_IN_INVENTORY, false, tool(ENSURE_BLOCKS_IN_INVENTORY, "Ensure the inventory contains at least a target count from mined block drops. Do not pass inventory item ids.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("blockIds", stringArray("Namespaced block ids whose drops count toward the target, for example minecraft:iron_ore. These must be block ids, not item ids such as minecraft:raw_iron.")),
				prop("quantity", integer("Minimum matching item count required in inventory. Existing inventory and pickups count."))
			), List.of("blockIds", "quantity")), PlannerToolCatalog::validateMineBlocksArguments),
		builtInTool(COLLECT_RESOURCE, false, tool(COLLECT_RESOURCE, "Collect a supported resource kind.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("resourceKind", enumString("Resource kind.", List.of("WOOD_LOGS"))),
				prop("quantity", integer("Quantity to collect."))
			), List.of("resourceKind", "quantity")), PlannerToolCatalog::validateCollectResourceArguments),
		builtInTool(CRAFT_RECIPE, false, tool(CRAFT_RECIPE, "Run a listed crafting recipe, including automatic crafting-table setup for 3x3 recipes.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("recipeId", string("Exact recipe id from check_craftables.")),
				prop("times", integer("Recipe run count."))
			), List.of("recipeId", "times")), PlannerToolCatalog::validateCraftRecipeArguments),
		builtInTool(SMELT_ITEMS, false, tool(SMELT_ITEMS, "Start one background smelting process from an exact optionId returned by check_smeltables.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("optionId", string("Exact optionId from check_smeltables.")),
				prop("inputQuantity", integer("Number of input items to smelt.")),
				prop("fuelMode", enumString("Fuel mode. Use auto unless explicitly selecting fuel.", List.of("auto", "manual"))),
				prop("fuelItemId", optionalString("Required when fuelMode is manual. Exact namespaced fuel item id.")),
				prop("fuelQuantity", integer("Fuel item quantity for manual fuel. Use 0 or omit for auto fuel.")),
				prop("confirmationToken", optionalString("Short-lived token returned when an occupied or stale furnace requires confirmation."))
			), List.of("optionId", "inputQuantity")), PlannerToolCatalog::validateSmeltItemsArguments),
		builtInTool(COLLECT_SMELTED_ITEMS, false, tool(COLLECT_SMELTED_ITEMS, "Collect output from an Airicraft-owned smelting process, or from an untracked occupied furnace with confirmation.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("processId", optionalString("Airicraft-owned process id from smelt_items or inspect_smelting.")),
				prop("confirmationToken", optionalString("Short-lived token required for untracked or occupied furnace collection."))
			), List.of()), PlannerToolCatalog::validateCollectSmeltedItemsArguments),
		builtInTool(CANCEL_SMELTING, false, tool(CANCEL_SMELTING, "Stop tracking an Airicraft-owned smelting process without reclaiming furnace contents.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("processId", string("Airicraft-owned process id to stop tracking."))
			), List.of("processId")), PlannerToolCatalog::validateCancelSmeltingArguments),
		builtInTool(DROP_ITEMS, false, tool(DROP_ITEMS, "Drop exact items from current inventory at the current position.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("itemId", string("Exact namespaced item id from inspect_inventory itemCounts.")),
				prop("quantity", integer("Number of items to drop."))
			), List.of("itemId", "quantity")), PlannerToolCatalog::validateDropItemsArguments),
		builtInTool(GIVE_PLAYER, false, tool(GIVE_PLAYER, "Drop exact items for a named nearby player to pick up.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("targetPlayer", string("Nearby player name receiving the items.")),
				prop("itemId", string("Exact namespaced item id from inspect_inventory itemCounts.")),
				prop("quantity", integer("Number of items to drop."))
			), List.of("targetPlayer", "itemId", "quantity")), PlannerToolCatalog::validateGivePlayerArguments),
		builtInTool(ATTACK_ENTITY, false, tool(ATTACK_ENTITY, "Attack one nearby entity. Default mode kill keeps attacking until the target dies; hit_once stops after one landed hit. Always copy the uuid token shown by inspect_nearby_entities or focus, and optionally include name or entityTypeId.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("uuid", optionalString("Entity uuid token copied from inspect_nearby_entities or focus. Full uuid also works.")),
				prop("name", optionalString("Visible custom name or display name when available.")),
				prop("entityTypeId", optionalString("Exact namespaced entity type id, for example minecraft:sheep.")),
				prop("mode", enumString("Attack mode. Use kill unless the user asks for one hit.", List.of("kill", "hit_once")))
			), List.of("uuid")), PlannerToolCatalog::validateAttackEntityArguments),
		builtInTool(USE_ENTITY, false, tool(USE_ENTITY, "Use current hand or an optional item on one nearby entity. Always copy the uuid token shown by inspect_nearby_entities or focus, and optionally include name or entityTypeId.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("uuid", optionalString("Entity uuid token copied from inspect_nearby_entities or focus. Full uuid also works.")),
				prop("name", optionalString("Visible custom name or display name when available.")),
				prop("entityTypeId", optionalString("Exact namespaced entity type id, for example minecraft:sheep.")),
				prop("itemId", optionalString("Optional exact namespaced item id to equip first, for example minecraft:shears."))
			), List.of("uuid")), PlannerToolCatalog::validateUseEntityArguments),
		builtInTool(PLACE_BLOCK, false, tool(PLACE_BLOCK, "Place a block item at an intended modified target position. Target position must have been observed by inspect_world within the last 10 planner tool calls.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("itemId", string("Exact namespaced item id from inspect_inventory itemCounts.")),
				prop("x", integer("Intended modified target block x coordinate.")),
				prop("y", integer("Intended modified target block y coordinate.")),
				prop("z", integer("Intended modified target block z coordinate.")),
				prop("facePreference", enumString("Optional adjacent support preference. auto derives best support.", List.of("auto", "down", "north", "south", "east", "west", "up"))),
				prop("requireCurrentTargetMaterial", enumString("Required current target material before placement. Default air_or_replaceable.", List.of("air", "replaceable", "air_or_replaceable")))
			), List.of("itemId", "x", "y", "z")), PlannerToolCatalog::validatePlaceBlockArguments),
		builtInTool(USE_BLOCK, false, tool(USE_BLOCK, "Use current hand or an optional item on an intended modified target position. If target is air/replaceable, runtime clicks adjacent support such as farmland below seeds. Target position must have been observed by inspect_world within the last 10 planner tool calls.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("itemId", optionalString("Optional exact namespaced item id to equip first, for example minecraft:wheat_seeds.")),
				prop("x", integer("Intended modified target block x coordinate.")),
				prop("y", integer("Intended modified target block y coordinate.")),
				prop("z", integer("Intended modified target block z coordinate.")),
				prop("facePreference", enumString("Optional adjacent support preference. auto derives best support.", List.of("auto", "down", "north", "south", "east", "west", "up"))),
				prop("expectedSupportBlockIds", stringArray("Optional exact block ids expected on the clicked support block.")),
				prop("expectedTargetMaterial", enumString("Optional current target material check before use.", List.of("air", "replaceable", "air_or_replaceable")))
			), List.of("x", "y", "z")), PlannerToolCatalog::validateUseBlockArguments),
		builtInTool(CANCEL_TASK, false, tool(CANCEL_TASK, "Cancel the current task or job.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("reason", string("Optional cancellation reason."))
			), List.of()), NO_ARGUMENT_VALIDATION),
		builtInTool(CLEAR_GOAL, false, tool(CLEAR_GOAL, "Clear the current goal.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed."))
			), List.of()), NO_ARGUMENT_VALIDATION),
		builtInTool(UPDATE_EVENT_POLICY, false, tool(UPDATE_EVENT_POLICY, "Update future event routing policy.", properties(
				prop("narration", optionalString("Optional visible narration before using the tool. Omit this field when no narration is needed.")),
				prop("clearAll", bool("Clear all active planner policy rules.")),
				prop("removeRuleIds", stringArray("Rule ids to remove.")),
				prop("upserts", array("Policy rule upserts.", policyUpsertSchema()))
			), List.of()), PlannerToolCatalog::validatePolicyArguments)
	);
	private static final Map<String, BuiltInTool> BUILT_IN_TOOLS_BY_NAME = builtInToolsByName();

	private PlannerToolCatalog() {
	}

	public static List<Map<String, Object>> openAiTools() {
		return BUILT_IN_TOOLS.stream()
			.map(BuiltInTool::openAiTool)
			.toList();
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
		BuiltInTool tool = builtInTool(name);
		return tool != null && tool.readTool();
	}

	public static boolean isKnownTool(String name) {
		return builtInTool(name) != null;
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
		BuiltInTool tool = builtInTool(name);
		if (tool == null) {
			throw new JsonParseException("Unknown planner tool: " + name);
		}
		tool.validate(arguments);
	}

	private static BuiltInTool builtInTool(String name) {
		return BUILT_IN_TOOLS_BY_NAME.get(normalizeName(name));
	}

	private static BuiltInTool builtInTool(String name, boolean readTool, Map<String, Object> openAiTool, Consumer<JsonObject> validator) {
		return new BuiltInTool(normalizeName(name), readTool, openAiTool, validator);
	}

	private static Map<String, BuiltInTool> builtInToolsByName() {
		LinkedHashMap<String, BuiltInTool> tools = new LinkedHashMap<>();
		for (BuiltInTool tool : BUILT_IN_TOOLS) {
			BuiltInTool previous = tools.put(tool.name(), tool);
			if (previous != null) {
				throw new IllegalStateException("Duplicate planner tool: " + tool.name());
			}
		}
		return Map.copyOf(tools);
	}

	private static void validateFollowPlayerArguments(JsonObject arguments) {
		requireString(arguments, "targetPlayer");
	}

	private static void validateNavigateToArguments(JsonObject arguments) {
		requireInt(arguments, "x");
		requireInt(arguments, "y");
		requireInt(arguments, "z");
		requireBoolean(arguments, "exactY");
	}

	private static void validateMineBlocksArguments(JsonObject arguments) {
		requireStringArray(arguments, "blockIds");
		requirePositiveInt(arguments, "quantity");
	}

	private static void validateReturnToSurfaceArguments(JsonObject arguments) {
		if (arguments.has("useTowering") && !arguments.get("useTowering").isJsonNull()) {
			requireBoolean(arguments, "useTowering");
		}
		if (arguments.has("fillerBlockIds") && !arguments.get("fillerBlockIds").isJsonNull()) {
			requireStringArray(arguments, "fillerBlockIds");
		}
	}

	private static void validateCollectResourceArguments(JsonObject arguments) {
		String kind = requireString(arguments, "resourceKind");
		if (!"WOOD_LOGS".equals(kind)) {
			throw new JsonParseException("Unsupported resourceKind: " + kind);
		}
		requirePositiveInt(arguments, "quantity");
	}

	private static void validateCraftRecipeArguments(JsonObject arguments) {
		requireString(arguments, "recipeId");
		requirePositiveInt(arguments, "times");
	}

	private static void validateCollectSmeltedItemsArguments(JsonObject arguments) {
		if (arguments.has("processId") && !arguments.get("processId").isJsonNull()) {
			requireString(arguments, "processId");
		}
		if (arguments.has("confirmationToken") && !arguments.get("confirmationToken").isJsonNull()) {
			requireString(arguments, "confirmationToken");
		}
	}

	private static void validateCancelSmeltingArguments(JsonObject arguments) {
		requireString(arguments, "processId");
	}

	private static void validateDropItemsArguments(JsonObject arguments) {
		requireString(arguments, "itemId");
		requirePositiveInt(arguments, "quantity");
	}

	private static void validateGivePlayerArguments(JsonObject arguments) {
		requireString(arguments, "targetPlayer");
		requireString(arguments, "itemId");
		requirePositiveInt(arguments, "quantity");
	}

	private static void validateAttackEntityArguments(JsonObject arguments) {
		requireEntitySelector(arguments);
		if (arguments.has("mode") && !arguments.get("mode").isJsonNull()) {
			requireAttackMode(arguments);
		}
	}

	private static void validateUseEntityArguments(JsonObject arguments) {
		requireEntitySelector(arguments);
		if (arguments.has("itemId") && !arguments.get("itemId").isJsonNull()) {
			requireString(arguments, "itemId");
		}
	}

	private static void validatePlaceBlockArguments(JsonObject arguments) {
		requireString(arguments, "itemId");
		requireInt(arguments, "x");
		requireInt(arguments, "y");
		requireInt(arguments, "z");
		validateFacePreference(arguments, "facePreference");
		validateTargetMaterial(arguments, "requireCurrentTargetMaterial");
	}

	private static void validateUseBlockArguments(JsonObject arguments) {
		if (arguments.has("itemId") && !arguments.get("itemId").isJsonNull()) {
			requireString(arguments, "itemId");
		}
		requireInt(arguments, "x");
		requireInt(arguments, "y");
		requireInt(arguments, "z");
		validateFacePreference(arguments, "facePreference");
		if (arguments.has("expectedSupportBlockIds") && !arguments.get("expectedSupportBlockIds").isJsonNull()) {
			requireStringArray(arguments, "expectedSupportBlockIds");
		}
		validateTargetMaterial(arguments, "expectedTargetMaterial");
	}

	private static void validateFacePreference(JsonObject arguments, String key) {
		if (!arguments.has(key) || arguments.get(key).isJsonNull()) {
			return;
		}
		String value = requireString(arguments, key);
		if (!List.of("auto", "down", "north", "south", "east", "west", "up").contains(value)) {
			throw new JsonParseException("Unsupported " + key + ": " + value);
		}
	}

	private static void validateTargetMaterial(JsonObject arguments, String key) {
		if (!arguments.has(key) || arguments.get(key).isJsonNull()) {
			return;
		}
		String value = requireString(arguments, key);
		if (!List.of("air", "replaceable", "air_or_replaceable").contains(value)) {
			throw new JsonParseException("Unsupported " + key + ": " + value);
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

	private static void validateTakeALookArguments(JsonObject arguments) {
		boolean hasDirection = getString(arguments, "direction").isPresent();
		boolean hasPlayer = getString(arguments, "targetPlayer").isPresent();
		boolean hasAnyCoordinate = arguments.has("x") || arguments.has("y") || arguments.has("z");
		boolean hasBlock = hasAnyCoordinate;
		int targetModes = (hasDirection ? 1 : 0) + (hasBlock ? 1 : 0) + (hasPlayer ? 1 : 0);
		if (targetModes > 1) {
			throw new JsonParseException("take_a_look accepts only one of direction, block coordinates, or targetPlayer");
		}
		if (hasDirection) {
			String direction = requireString(arguments, "direction").toLowerCase(Locale.ROOT);
			if (!List.of("north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest").contains(direction)) {
				throw new JsonParseException("Unsupported direction: " + direction);
			}
		}
		if (hasBlock) {
			requireInt(arguments, "x");
			requireInt(arguments, "y");
			requireInt(arguments, "z");
		}
	}

	private static void validateInspectWorldArguments(JsonObject arguments) {
		String mode = requireString(arguments, "mode");
		String scope = requireString(arguments, "scope");
		if (!List.of("inspect_area", "find_blocks", "find_placement_sites").contains(mode)) {
			throw new JsonParseException("Unsupported inspect_world mode: " + mode);
		}
		validateInspectWorldScope(arguments, scope);
		validateOptionalIntRange(arguments, "maxResults", 1, 64);
		validateOptionalIntRange(arguments, "nearbyRequiredHorizontalRadius", 0, 16);
		validateOptionalIntRange(arguments, "nearbyRequiredVerticalRadius", 0, 8);

		if ("inspect_area".equals(mode)) {
			rejectAny(arguments, "blockIds", "stateFilters", "targetMaterial", "supportBlockIds", "supportStateFilters",
				"requireSolidTopSupport", "requireAirAbove", "requireStandableAdjacent", "requireWithinInteractionRange",
				"nearbyRequiredBlockIds", "nearbyRequiredHorizontalRadius", "nearbyRequiredVerticalRadius", "maxResults");
			return;
		}
		if ("find_blocks".equals(mode)) {
			requireStringArray(arguments, "blockIds");
			validateStateFilters(arguments, "stateFilters");
			rejectAny(arguments, "targetMaterial", "supportBlockIds", "supportStateFilters",
				"requireSolidTopSupport", "requireAirAbove", "requireStandableAdjacent", "requireWithinInteractionRange",
				"nearbyRequiredBlockIds", "nearbyRequiredHorizontalRadius", "nearbyRequiredVerticalRadius");
			return;
		}

		rejectAny(arguments, "blockIds", "stateFilters");
		if (arguments.has("targetMaterial") && !arguments.get("targetMaterial").isJsonNull()) {
			String targetMaterial = requireString(arguments, "targetMaterial");
			if (!List.of("air", "replaceable", "air_or_replaceable").contains(targetMaterial)) {
				throw new JsonParseException("Unsupported targetMaterial: " + targetMaterial);
			}
		}
		if (arguments.has("supportBlockIds") && !arguments.get("supportBlockIds").isJsonNull()) {
			requireStringArray(arguments, "supportBlockIds");
		}
		validateStateFilters(arguments, "supportStateFilters");
		if (arguments.has("requireSolidTopSupport") && !arguments.get("requireSolidTopSupport").isJsonNull()) {
			requireBoolean(arguments, "requireSolidTopSupport");
		}
		if (arguments.has("requireAirAbove") && !arguments.get("requireAirAbove").isJsonNull()) {
			requireBoolean(arguments, "requireAirAbove");
		}
		if (arguments.has("requireStandableAdjacent") && !arguments.get("requireStandableAdjacent").isJsonNull()) {
			requireBoolean(arguments, "requireStandableAdjacent");
		}
		if (arguments.has("requireWithinInteractionRange") && !arguments.get("requireWithinInteractionRange").isJsonNull()) {
			requireBoolean(arguments, "requireWithinInteractionRange");
		}
		if (arguments.has("nearbyRequiredBlockIds") && !arguments.get("nearbyRequiredBlockIds").isJsonNull()) {
			requireStringArray(arguments, "nearbyRequiredBlockIds");
		}
	}

	private static void validateInspectWorldScope(JsonObject arguments, String scope) {
		switch (scope) {
			case "self" -> {
				rejectAny(arguments, "x", "y", "z", "x1", "y1", "z1", "x2", "y2", "z2");
				validateOptionalIntRange(arguments, "horizontalRadius", 0, 16);
				validateOptionalIntRange(arguments, "verticalRadius", 0, 8);
			}
			case "center" -> {
				requireInt(arguments, "x");
				requireInt(arguments, "y");
				requireInt(arguments, "z");
				rejectAny(arguments, "x1", "y1", "z1", "x2", "y2", "z2");
				validateOptionalIntRange(arguments, "horizontalRadius", 0, 16);
				validateOptionalIntRange(arguments, "verticalRadius", 0, 8);
			}
			case "box" -> {
				requireInt(arguments, "x1");
				requireInt(arguments, "y1");
				requireInt(arguments, "z1");
				requireInt(arguments, "x2");
				requireInt(arguments, "y2");
				requireInt(arguments, "z2");
				rejectAny(arguments, "x", "y", "z", "horizontalRadius", "verticalRadius");
			}
			default -> throw new JsonParseException("Unsupported inspect_world scope: " + scope);
		}
	}

	private static void validateStateFilters(JsonObject arguments, String key) {
		if (!arguments.has(key) || arguments.get(key).isJsonNull()) {
			return;
		}
		requireStringArray(arguments, key);
		for (JsonElement element : arguments.getAsJsonArray(key)) {
			String filter = element.getAsString();
			int separator = filter.indexOf('=');
			if (separator <= 0 || separator != filter.lastIndexOf('=') || separator == filter.length() - 1) {
				throw new JsonParseException(key + " values must use exact key=value syntax");
			}
		}
	}

	private static void validateOptionalIntRange(JsonObject arguments, String key, int min, int max) {
		if (!arguments.has(key) || arguments.get(key).isJsonNull()) {
			return;
		}
		int value = requireInt(arguments, key);
		if (value < min || value > max) {
			throw new JsonParseException(key + " must be between " + min + " and " + max);
		}
	}

	private static void rejectAny(JsonObject arguments, String... keys) {
		for (String key : keys) {
			if (arguments.has(key) && !arguments.get(key).isJsonNull()) {
				throw new JsonParseException("inspect_world field not allowed for this mode/scope: " + key);
			}
		}
	}

	private static void requireAttackMode(JsonObject arguments) {
		String mode = requireString(arguments, "mode");
		try {
			EntityAttackMode.fromWireValue(mode);
		}
		catch (IllegalArgumentException exception) {
			throw new JsonParseException(exception.getMessage(), exception);
		}
	}

	private static void validateSmeltItemsArguments(JsonObject arguments) {
		requireString(arguments, "optionId");
		requirePositiveInt(arguments, "inputQuantity");
		String fuelMode = getString(arguments, "fuelMode").orElse(null);
		try {
			SmeltingFuelMode parsedFuelMode = SmeltingFuelMode.fromWireValue(fuelMode);
			if (parsedFuelMode == SmeltingFuelMode.MANUAL) {
				requireString(arguments, "fuelItemId");
				requirePositiveInt(arguments, "fuelQuantity");
			}
			else if (arguments.has("fuelQuantity") && !arguments.get("fuelQuantity").isJsonNull()) {
				int fuelQuantity = requireInt(arguments, "fuelQuantity");
				if (fuelQuantity < 0) {
					throw new JsonParseException("fuelQuantity must be non-negative");
				}
			}
		}
		catch (IllegalArgumentException exception) {
			throw new JsonParseException(exception.getMessage(), exception);
		}
		if (arguments.has("confirmationToken") && !arguments.get("confirmationToken").isJsonNull()) {
			requireString(arguments, "confirmationToken");
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

	private record BuiltInTool(
		String name,
		boolean readTool,
		Map<String, Object> openAiTool,
		Consumer<JsonObject> validator
	) {
		private void validate(JsonObject arguments) {
			validator.accept(arguments);
		}
	}
}
