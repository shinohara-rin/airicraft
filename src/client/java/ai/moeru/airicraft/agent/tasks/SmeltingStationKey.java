package ai.moeru.airicraft.agent.tasks;

public record SmeltingStationKey(
	String dimensionId,
	int x,
	int y,
	int z
) {
	public SmeltingStationKey {
		dimensionId = dimensionId == null || dimensionId.isBlank() ? "unknown" : dimensionId.trim();
	}

	public String compact() {
		return dimensionId + "@" + x + "," + y + "," + z;
	}
}
