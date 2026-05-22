package ai.moeru.airicraft.agent.integration.map;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public record MapCapabilities(Set<MapCapability> values) {
	public MapCapabilities {
		values = values == null || values.isEmpty()
			? Set.of()
			: Collections.unmodifiableSet(EnumSet.copyOf(values));
	}

	public static MapCapabilities none() {
		return new MapCapabilities(Set.of());
	}

	public static MapCapabilities of(MapCapability... capabilities) {
		if (capabilities == null || capabilities.length == 0) {
			return none();
		}
		return new MapCapabilities(EnumSet.copyOf(Arrays.asList(capabilities)));
	}

	public boolean has(MapCapability capability) {
		return values.contains(capability);
	}

	public enum MapCapability {
		READ_WAYPOINTS,
		WRITE_WAYPOINTS,
		DELETE_WAYPOINTS,
		MINIMAP_IMAGE,
		WORLDMAP_IMAGE
	}
}
