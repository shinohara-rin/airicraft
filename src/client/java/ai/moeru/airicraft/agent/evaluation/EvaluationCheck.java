package ai.moeru.airicraft.agent.evaluation;

import java.util.Map;
import java.util.Objects;

public record EvaluationCheck(String type, Map<String, Object> fields) {
	public EvaluationCheck {
		type = type == null || type.isBlank() ? "unknown" : type.trim();
		fields = fields == null ? Map.of() : Map.copyOf(fields);
	}

	public String string(String key) {
		Object value = fields.get(key);
		if (value == null) {
			return null;
		}
		String text = String.valueOf(value);
		return text.isBlank() ? null : text;
	}

	public int integer(String key, int fallback) {
		Object value = fields.get(key);
		if (value instanceof Number number) {
			return number.intValue();
		}
		if (value == null) {
			return fallback;
		}
		try {
			return Integer.parseInt(String.valueOf(value));
		}
		catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	public boolean hasSameField(String key, Object expected) {
		return Objects.equals(fields.get(key), expected);
	}
}
