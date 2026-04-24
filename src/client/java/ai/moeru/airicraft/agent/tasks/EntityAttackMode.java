package ai.moeru.airicraft.agent.tasks;

import java.util.Locale;

public enum EntityAttackMode {
	KILL("kill"),
	HIT_ONCE("hit_once");

	private final String wireValue;

	EntityAttackMode(String wireValue) {
		this.wireValue = wireValue;
	}

	public String wireValue() {
		return wireValue;
	}

	public static EntityAttackMode fromWireValue(String value) {
		if (value == null || value.isBlank()) {
			return KILL;
		}
		String normalized = value.trim().toLowerCase(Locale.ROOT);
		for (EntityAttackMode mode : values()) {
			if (mode.wireValue.equals(normalized)) {
				return mode;
			}
		}
		throw new IllegalArgumentException("Unsupported attack mode: " + value);
	}
}
