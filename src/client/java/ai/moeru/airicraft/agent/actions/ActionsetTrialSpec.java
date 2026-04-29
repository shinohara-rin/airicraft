package ai.moeru.airicraft.agent.actions;

import java.util.Map;

public record ActionsetTrialSpec(
	String draftId,
	ActionGoal goal,
	Map<String, Integer> assumedInventory,
	boolean allowWorldMutation,
	long timeoutTicks
) {
	public ActionsetTrialSpec {
		draftId = draftId == null ? "" : draftId;
		if (goal == null) {
			throw new IllegalArgumentException("goal is required");
		}
		assumedInventory = assumedInventory == null ? Map.of() : Map.copyOf(assumedInventory);
		timeoutTicks = Math.max(1L, timeoutTicks);
	}

	public static ActionsetTrialSpec inventoryItem(
		String draftId,
		String itemId,
		int quantity,
		Map<String, Integer> assumedInventory,
		boolean allowWorldMutation,
		long timeoutTicks
	) {
		return new ActionsetTrialSpec(
			draftId,
			ActionGoal.inventoryItem(itemId, quantity),
			assumedInventory,
			allowWorldMutation,
			timeoutTicks
		);
	}
}
