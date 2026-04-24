package ai.moeru.airicraft.agent.actions;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PrimitiveActionRegistry {
	private final Map<String, PrimitiveActionMetadata> actions;

	private PrimitiveActionRegistry(Map<String, PrimitiveActionMetadata> actions) {
		this.actions = Map.copyOf(actions);
	}

	public static PrimitiveActionRegistry defaults() {
		LinkedHashMap<String, PrimitiveActionMetadata> actions = new LinkedHashMap<>();
		register(actions, metadata(
			"inspect_inventory",
			"Inspect current inventory item counts.",
			Map.of("prompt", param("string", false, "Optional inventory question.")),
			List.of(),
			List.of("inventory.item", "inventory.tool"),
			List.of("inspection"),
			false,
			false,
			"CurrentInventoryTool"
		));
		register(actions, metadata(
			"inspect_area",
			"Inspect loaded blocks and entities near the actor.",
			Map.of("radius", param("integer", false, "Search radius.")),
			List.of(),
			List.of("world.block", "world.site", "world.entity"),
			List.of("inspection"),
			false,
			false,
			"WorldEvidence"
		));
		register(actions, metadata(
			"find_block",
			"Find nearby loaded blocks matching block ids.",
			Map.of(
				"blockIds", param("string[]", true, "Namespaced block ids."),
				"radius", param("integer", false, "Search radius.")
			),
			List.of(),
			List.of("world.block", "world.site"),
			List.of("inspection", "world_scan"),
			false,
			false,
			"WorldEvidence"
		));
		register(actions, metadata(
			"pathfind_to",
			"Navigate to a block position.",
			Map.of(
				"x", param("integer", true, "Block x coordinate."),
				"y", param("integer", true, "Block y coordinate."),
				"z", param("integer", true, "Block z coordinate."),
				"exactY", param("boolean", false, "Whether y must match exactly.")
			),
			List.of(),
			List.of(),
			List.of("movement"),
			true,
			true,
			"WorldTaskRequest.NAVIGATE"
		));
		register(actions, metadata(
			"mine_block",
			"Mine matching blocks.",
			Map.of(
				"blockIds", param("string[]", true, "Namespaced block ids."),
				"quantity", param("integer", true, "Number of blocks to mine.")
			),
			List.of(),
			List.of("inventory.item"),
			List.of("mining", "destructive"),
			true,
			true,
			"WorldTaskRequest.MINE"
		));
		register(actions, metadata(
			"interact_block",
			"Use or interact with a block.",
			Map.of(
				"x", param("integer", true, "Block x coordinate."),
				"y", param("integer", true, "Block y coordinate."),
				"z", param("integer", true, "Block z coordinate."),
				"itemId", param("string", false, "Optional held item id.")
			),
			List.of("world.block"),
			List.of("world.block"),
			List.of("block_interaction"),
			true,
			true,
			"pending"
		));
		register(actions, metadata(
			"place_block",
			"Place an inventory block item at a position.",
			Map.of(
				"itemId", param("string", true, "Namespaced item id."),
				"x", param("integer", true, "Block x coordinate."),
				"y", param("integer", true, "Block y coordinate."),
				"z", param("integer", true, "Block z coordinate.")
			),
			List.of("inventory.item"),
			List.of("world.block"),
			List.of("block_interaction", "destructive"),
			true,
			true,
			"pending"
		));
		register(actions, metadata(
			"use_item",
			"Use an item with an optional target.",
			Map.of("itemId", param("string", true, "Namespaced item id.")),
			List.of("inventory.item"),
			List.of("world.block", "world.entity"),
			List.of("item_use"),
			true,
			true,
			"pending"
		));
		register(actions, metadata(
			"craft_item",
			"Craft an item from available recipe evidence.",
			Map.of(
				"itemId", param("string", true, "Namespaced output item id."),
				"quantity", param("integer", true, "Desired output count.")
			),
			List.of("craft.recipe"),
			List.of("inventory.item"),
			List.of("crafting"),
			true,
			true,
			"WorldTaskRequest.CRAFT_RECIPE"
		));
		register(actions, metadata(
			"attack_entity",
			"Attack a nearby entity.",
			Map.of("uuid", param("string", true, "Entity uuid token.")),
			List.of("world.entity"),
			List.of("world.entity", "inventory.item"),
			List.of("combat", "destructive"),
			true,
			true,
			"WorldTaskRequest.ATTACK_ENTITY"
		));
		register(actions, metadata(
			"wait_for_fact",
			"Register a background watch for a fact.",
			Map.of("fact", param("object", true, "Watched typed fact.")),
			List.of(),
			List.of("watch.pending", "watch.fulfilled"),
			List.of("watch"),
			false,
			true,
			"ActionGraphWatch"
		));
		return new PrimitiveActionRegistry(actions);
	}

	public boolean contains(String id) {
		return actions.containsKey(id);
	}

	public PrimitiveActionMetadata require(String id) {
		PrimitiveActionMetadata metadata = actions.get(id);
		if (metadata == null) {
			throw new IllegalArgumentException("Unknown primitive action: " + id);
		}
		return metadata;
	}

	public Collection<PrimitiveActionMetadata> all() {
		return actions.values();
	}

	private static PrimitiveActionMetadata metadata(
		String id,
		String summary,
		Map<String, PrimitiveParameter> params,
		List<String> guardFacts,
		List<String> producedFacts,
		List<String> tags,
		boolean foregroundActuation,
		boolean cancellable,
		String executorBinding
	) {
		return new PrimitiveActionMetadata(
			id,
			1,
			summary,
			params,
			guardFacts,
			List.of(),
			producedFacts,
			List.of(),
			10,
			List.of("session_gate", "timeout", "cancelled", "executor_failed"),
			foregroundActuation,
			cancellable,
			20 * 60,
			tags,
			executorBinding
		);
	}

	private static PrimitiveParameter param(String type, boolean required, String summary) {
		return new PrimitiveParameter(type, required, summary);
	}

	private static void register(LinkedHashMap<String, PrimitiveActionMetadata> actions, PrimitiveActionMetadata metadata) {
		actions.put(metadata.id(), metadata);
	}
}
