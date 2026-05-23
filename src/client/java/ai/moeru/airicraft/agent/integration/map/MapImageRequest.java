package ai.moeru.airicraft.agent.integration.map;

public record MapImageRequest(
	String providerId,
	String kind,
	String dimension,
	int radiusChunks,
	int zoom,
	boolean grid,
	Integer originX,
	Integer originZ
) {
	public MapImageRequest(
		String providerId,
		String kind,
		String dimension,
		int radiusChunks,
		int zoom,
		boolean grid
	) {
		this(providerId, kind, dimension, radiusChunks, zoom, grid, null, null);
	}

	public boolean hasPartialOrigin() {
		return (originX == null) != (originZ == null);
	}
}
