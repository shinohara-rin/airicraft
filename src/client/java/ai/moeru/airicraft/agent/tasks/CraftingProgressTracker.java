package ai.moeru.airicraft.agent.tasks;

final class CraftingProgressTracker {
	private int craftedOutputCount;
	private int takeBaselineOutputCount = -1;
	private int pendingResultCount;

	void reset() {
		craftedOutputCount = 0;
		takeBaselineOutputCount = -1;
		pendingResultCount = 0;
	}

	int craftedOutputCount() {
		return craftedOutputCount;
	}

	boolean targetReached(int requestedQuantity) {
		return craftedOutputCount >= requestedQuantity;
	}

	void beginTake(int currentOutputCount, int resultCount) {
		takeBaselineOutputCount = currentOutputCount;
		pendingResultCount = Math.max(0, resultCount);
	}

	boolean finishTakeIfInventoryIncreased(int currentOutputCount) {
		if (takeBaselineOutputCount < 0 || pendingResultCount <= 0) {
			return false;
		}
		int gainedCount = currentOutputCount - takeBaselineOutputCount;
		if (gainedCount <= 0) {
			return false;
		}
		craftedOutputCount += Math.min(gainedCount, pendingResultCount);
		clearPendingTake();
		return true;
	}

	private void clearPendingTake() {
		takeBaselineOutputCount = -1;
		pendingResultCount = 0;
	}
}
