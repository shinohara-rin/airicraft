package ai.moeru.airicraft.agent.tasks;

import java.util.Locale;

public enum SmeltingFuelMode {
	AUTO("auto"),
	MANUAL("manual");

	private final String wireValue;

	SmeltingFuelMode(String wireValue) {
		this.wireValue = wireValue;
	}

	public String wireValue() {
		return wireValue;
	}

	public static SmeltingFuelMode fromWireValue(String value) {
		if (value == null || value.isBlank()) {
			return AUTO;
		}
		String normalized = value.trim().toLowerCase(Locale.ROOT);
		for (SmeltingFuelMode mode : values()) {
			if (mode.wireValue.equals(normalized)) {
				return mode;
			}
		}
		throw new IllegalArgumentException("Unsupported fuelMode: " + value);
	}
}
