package ai.moeru.airicraft.agent.tasks;

public record CollectSmeltedItemsStepArgs(
	String processId,
	String confirmationToken
) {
	public CollectSmeltedItemsStepArgs {
		processId = processId == null || processId.isBlank() ? null : processId.trim();
		confirmationToken = confirmationToken == null || confirmationToken.isBlank() ? null : confirmationToken.trim();
	}
}
