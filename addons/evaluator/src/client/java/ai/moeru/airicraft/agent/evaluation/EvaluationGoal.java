package ai.moeru.airicraft.agent.evaluation;

import ai.moeru.airicraft.agent.actions.ActionGoal;

/** An explicit objective, independent of the scenario's prose and outcome checks. */
public record EvaluationGoal(String kind, String itemId, int quantity) {
	public EvaluationGoal {
		if (!"inventory_item".equals(kind)) {
			throw new IllegalArgumentException("Unsupported evaluation goal kind: " + kind);
		}
		if (itemId == null || !itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
			throw new IllegalArgumentException("Evaluation goal requires a namespaced itemId");
		}
		if (quantity < 1) {
			throw new IllegalArgumentException("Evaluation goal quantity must be positive");
		}
	}

	public ActionGoal toActionGoal() {
		return ActionGoal.inventoryItem(itemId, quantity);
	}
}
