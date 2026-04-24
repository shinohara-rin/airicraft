package ai.moeru.airicraft.agent.actions;

import java.util.List;

public record ActionsetLoadResult(
	ActionsetIndex index,
	List<ActionsetLoadDiagnostic> diagnostics
) {
	public ActionsetLoadResult {
		index = index == null ? ActionsetIndex.empty() : index;
		diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
	}

	public boolean valid() {
		return diagnostics.stream().noneMatch(ActionsetLoadDiagnostic::blocking);
	}
}
