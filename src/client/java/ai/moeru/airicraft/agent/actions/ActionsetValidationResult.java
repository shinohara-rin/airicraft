package ai.moeru.airicraft.agent.actions;

import java.util.List;

public record ActionsetValidationResult(
	List<ActionsetValidationError> errors
) {
	public ActionsetValidationResult {
		errors = errors == null ? List.of() : List.copyOf(errors);
	}

	public boolean valid() {
		return errors.isEmpty();
	}
}
