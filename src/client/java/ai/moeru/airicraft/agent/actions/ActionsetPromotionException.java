package ai.moeru.airicraft.agent.actions;

public final class ActionsetPromotionException extends RuntimeException {
	private final String code;

	public ActionsetPromotionException(String code, String message) {
		super(message);
		this.code = code == null || code.isBlank() ? "actionset_error" : code;
	}

	public String code() {
		return code;
	}
}
