package ai.moeru.airicraft.agent.evaluation;

public final class EvaluationWorldListUiState {
	private static boolean showEvaluationCopies = true;

	private EvaluationWorldListUiState() {
	}

	public static boolean showEvaluationCopies() {
		return showEvaluationCopies;
	}

	public static boolean toggleEvaluationCopies() {
		showEvaluationCopies = !showEvaluationCopies;
		return showEvaluationCopies;
	}
}
