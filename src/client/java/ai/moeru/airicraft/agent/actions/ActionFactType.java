package ai.moeru.airicraft.agent.actions;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public enum ActionFactType {
	INVENTORY_ITEM("inventory.item"),
	INVENTORY_TOOL("inventory.tool"),
	WORLD_BLOCK("world.block"),
	WORLD_CROP("world.crop"),
	WORLD_CROP_GROUP("world.crop_group"),
	WORLD_SITE("world.site"),
	WORLD_ENTITY("world.entity"),
	CRAFT_RECIPE("craft.recipe"),
	WATCH_PENDING("watch.pending"),
	WATCH_FULFILLED("watch.fulfilled"),
	ROUTE_FAILURE("route.failure");

	private final String id;

	ActionFactType(String id) {
		this.id = id;
	}

	public String id() {
		return id;
	}

	public static Optional<ActionFactType> fromId(String id) {
		return Arrays.stream(values())
			.filter(type -> type.id.equals(id))
			.findFirst();
	}

	public static Set<String> knownIds() {
		return Arrays.stream(values())
			.map(ActionFactType::id)
			.collect(Collectors.toUnmodifiableSet());
	}
}
