package ai.moeru.airicraft.agent.tasks;

import java.util.Locale;

public record EntitySelector(
	String uuid,
	String name,
	String entityTypeId
) {
	public EntitySelector {
		uuid = normalizedValue(uuid);
		name = normalizedValue(name);
		entityTypeId = normalizedLowercaseValue(entityTypeId);
		if (uuid == null && name == null && entityTypeId == null) {
			throw new IllegalArgumentException("entity selector requires uuid, name, or entityTypeId");
		}
	}

	private static String normalizedValue(String value) {
		String trimmed = value == null ? null : value.trim();
		return trimmed == null || trimmed.isEmpty() ? null : trimmed;
	}

	private static String normalizedLowercaseValue(String value) {
		String trimmed = normalizedValue(value);
		return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
	}
}
