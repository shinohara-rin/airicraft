package ai.moeru.airicraft.agent.tasks;

public record SmeltingActionResult(
	boolean accepted,
	boolean confirmationRequired,
	String processId,
	String confirmationToken,
	String errorCode,
	String message
) {
	public static SmeltingActionResult accepted(String processId, String message) {
		return new SmeltingActionResult(true, false, normalize(processId), null, null, normalize(message));
	}

	public static SmeltingActionResult confirmationRequired(String token, String message) {
		return new SmeltingActionResult(false, true, null, normalize(token), null, normalize(message));
	}

	public static SmeltingActionResult failed(String errorCode, String message) {
		return new SmeltingActionResult(false, false, null, null, normalize(errorCode), normalize(message));
	}

	private static String normalize(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
