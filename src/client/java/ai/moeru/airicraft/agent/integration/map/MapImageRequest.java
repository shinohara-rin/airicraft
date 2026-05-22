package ai.moeru.airicraft.agent.integration.map;

public record MapImageRequest(
	String providerId,
	String kind,
	String dimension,
	int radiusChunks,
	int zoom,
	boolean grid
) {
}
