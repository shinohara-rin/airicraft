package ai.moeru.airicraft.agent.actions;

public record ActionsetLoadDiagnostic(
	String code,
	String path,
	String message,
	String sourceName,
	ActionsetNamespace namespace,
	boolean blocking
) {
	public ActionsetLoadDiagnostic {
		code = code == null || code.isBlank() ? "unknown" : code;
		path = path == null || path.isBlank() ? "$" : path;
		message = message == null ? "" : message;
		sourceName = sourceName == null || sourceName.isBlank() ? "<unknown>" : sourceName;
	}
}
