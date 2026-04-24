package ai.moeru.airicraft.agent.actions;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ActionFactIdentity(
	ActionFactType type,
	Map<String, String> keys
) {
	public ActionFactIdentity {
		type = Objects.requireNonNull(type, "type");
		keys = copyKeys(keys);
	}

	public static ActionFactIdentity inventoryItem(String worldId, String actorId, String itemId) {
		return new ActionFactIdentity(ActionFactType.INVENTORY_ITEM, keys(
			"worldId", worldId,
			"actorId", actorId,
			"itemId", itemId
		));
	}

	public static ActionFactIdentity inventoryTool(String worldId, String actorId, String toolTag) {
		return new ActionFactIdentity(ActionFactType.INVENTORY_TOOL, keys(
			"worldId", worldId,
			"actorId", actorId,
			"toolTag", toolTag
		));
	}

	public static ActionFactIdentity worldBlock(String worldId, String dimension, String blockPos) {
		return new ActionFactIdentity(ActionFactType.WORLD_BLOCK, keys(
			"worldId", worldId,
			"dimension", dimension,
			"blockPos", blockPos
		));
	}

	public static ActionFactIdentity worldCrop(String worldId, String dimension, String blockPos) {
		return new ActionFactIdentity(ActionFactType.WORLD_CROP, keys(
			"worldId", worldId,
			"dimension", dimension,
			"blockPos", blockPos
		));
	}

	public static ActionFactIdentity worldCropGroup(String worldId, String dimension, String siteId, String cropId) {
		return new ActionFactIdentity(ActionFactType.WORLD_CROP_GROUP, keys(
			"worldId", worldId,
			"dimension", dimension,
			"siteId", siteId,
			"cropId", cropId
		));
	}

	public static ActionFactIdentity worldSite(String worldId, String dimension, String siteId) {
		return new ActionFactIdentity(ActionFactType.WORLD_SITE, keys(
			"worldId", worldId,
			"dimension", dimension,
			"siteId", siteId
		));
	}

	public static ActionFactIdentity worldEntity(String worldId, String dimension, String entityId) {
		return new ActionFactIdentity(ActionFactType.WORLD_ENTITY, keys(
			"worldId", worldId,
			"dimension", dimension,
			"entityId", entityId
		));
	}

	public static ActionFactIdentity craftRecipe(String worldId, String actorId, String recipeId) {
		return new ActionFactIdentity(ActionFactType.CRAFT_RECIPE, keys(
			"worldId", worldId,
			"actorId", actorId,
			"recipeId", recipeId
		));
	}

	public static ActionFactIdentity watchPending(String worldId, String watchId) {
		return watch(ActionFactType.WATCH_PENDING, worldId, watchId);
	}

	public static ActionFactIdentity watchFulfilled(String worldId, String watchId) {
		return watch(ActionFactType.WATCH_FULFILLED, worldId, watchId);
	}

	public static ActionFactIdentity routeFailure(String worldId, String goalId) {
		return new ActionFactIdentity(ActionFactType.ROUTE_FAILURE, keys(
			"worldId", worldId,
			"goalId", goalId
		));
	}

	public boolean matches(ActionFactType expectedType, Map<String, String> requiredKeys) {
		if (type != expectedType) {
			return false;
		}
		for (Map.Entry<String, String> entry : copyKeys(requiredKeys).entrySet()) {
			if (!entry.getValue().equals(keys.get(entry.getKey()))) {
				return false;
			}
		}
		return true;
	}

	private static ActionFactIdentity watch(ActionFactType type, String worldId, String watchId) {
		return new ActionFactIdentity(type, keys(
			"worldId", worldId,
			"watchId", watchId
		));
	}

	private static Map<String, String> keys(String... values) {
		if (values.length % 2 != 0) {
			throw new IllegalArgumentException("identity keys must be key/value pairs");
		}
		LinkedHashMap<String, String> keys = new LinkedHashMap<>();
		for (int index = 0; index < values.length; index += 2) {
			keys.put(requireKey(values[index]), requireValue(values[index], values[index + 1]));
		}
		return keys;
	}

	private static Map<String, String> copyKeys(Map<String, String> keys) {
		if (keys == null || keys.isEmpty()) {
			return Map.of();
		}
		LinkedHashMap<String, String> copy = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : keys.entrySet()) {
			copy.put(requireKey(entry.getKey()), requireValue(entry.getKey(), entry.getValue()));
		}
		return Collections.unmodifiableMap(copy);
	}

	private static String requireKey(String key) {
		if (key == null || key.isBlank()) {
			throw new IllegalArgumentException("identity key is required");
		}
		return key;
	}

	private static String requireValue(String key, String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("identity value is required for " + key);
		}
		return value;
	}
}
