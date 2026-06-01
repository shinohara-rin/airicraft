package ai.moeru.airicraft.agent.tasks;

public record SmeltingOutputReadyEvent(
	String processId,
	String optionId,
	SmeltingStationKey stationKey,
	String outputItemId,
	int outputCount,
	int inputQuantity
) {
}
