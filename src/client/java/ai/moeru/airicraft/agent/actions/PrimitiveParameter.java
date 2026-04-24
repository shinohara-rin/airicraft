package ai.moeru.airicraft.agent.actions;

public record PrimitiveParameter(
	String type,
	boolean required,
	String summary
) {
	public PrimitiveParameter {
		if (type == null || type.isBlank()) {
			throw new IllegalArgumentException("type is required");
		}
		summary = summary == null ? "" : summary;
	}
}
