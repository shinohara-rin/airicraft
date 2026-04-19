package ai.moeru.airicraft.agent.integration.rei;

import java.util.Locale;

public enum RecipeSearchMode {
	ALL,
	OUTPUT,
	INPUT;

	static RecipeSearchMode parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return ALL;
		}
		return switch (raw.trim().toLowerCase(Locale.ROOT)) {
			case "all" -> ALL;
			case "output", "recipes" -> OUTPUT;
			case "input", "uses" -> INPUT;
			default -> throw new IllegalArgumentException("Unsupported recipe search mode: " + raw);
		};
	}

	String toolValue() {
		return name().toLowerCase(Locale.ROOT);
	}
}
