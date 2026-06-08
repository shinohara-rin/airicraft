package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;

public record LedgerStepPayload(
	CollectResourceStepArgs collectResource,
	GoalPosition navigateToPosition,
	NavigateToBlockKindStepArgs navigateToBlockKind,
	GoalMineSpec mineBlocks,
	CraftRecipeStepArgs craftRecipe,
	OpenContainerStepArgs openContainer,
	TransferItemsStepArgs transferItems,
	PlaceBlockStepArgs placeBlock,
	BlockUseStepArgs blockUse,
	DropItemsStepArgs dropItems,
	BlockBreakStepArgs blockBreak,
	EntityInteractionStepArgs entityInteraction,
	AskUserStepArgs askUser,
	FinishStepArgs finish
) {
	public LedgerStepPayload(
		CollectResourceStepArgs collectResource,
		GoalPosition navigateToPosition,
		NavigateToBlockKindStepArgs navigateToBlockKind,
		GoalMineSpec mineBlocks,
		CraftRecipeStepArgs craftRecipe,
		OpenContainerStepArgs openContainer,
		TransferItemsStepArgs transferItems,
		PlaceBlockStepArgs placeBlock,
		DropItemsStepArgs dropItems,
		EntityInteractionStepArgs entityInteraction,
		AskUserStepArgs askUser,
		FinishStepArgs finish
	) {
		this(
			collectResource,
			navigateToPosition,
			navigateToBlockKind,
			mineBlocks,
			craftRecipe,
			openContainer,
			transferItems,
			placeBlock,
			null,
			dropItems,
			null,
			entityInteraction,
			askUser,
			finish
		);
	}

	public LedgerStepPayload(
		CollectResourceStepArgs collectResource,
		GoalPosition navigateToPosition,
		NavigateToBlockKindStepArgs navigateToBlockKind,
		GoalMineSpec mineBlocks,
		CraftRecipeStepArgs craftRecipe,
		OpenContainerStepArgs openContainer,
		TransferItemsStepArgs transferItems,
		PlaceBlockStepArgs placeBlock,
		DropItemsStepArgs dropItems,
		AskUserStepArgs askUser,
		FinishStepArgs finish
	) {
		this(
			collectResource,
			navigateToPosition,
			navigateToBlockKind,
			mineBlocks,
			craftRecipe,
			openContainer,
			transferItems,
			placeBlock,
			null,
			dropItems,
			null,
			null,
			askUser,
			finish
		);
	}
}
