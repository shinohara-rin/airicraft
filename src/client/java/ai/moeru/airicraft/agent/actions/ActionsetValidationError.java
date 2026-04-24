package ai.moeru.airicraft.agent.actions;

public record ActionsetValidationError(
	String code,
	String path,
	String message,
	String expected,
	String actual
) {
	public ActionsetValidationError(String code, String path, String message) {
		this(code, path, message, null, null);
	}
}
