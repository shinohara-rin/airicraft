package ai.moeru.airicraft.agent.actions;

import java.util.Map;

public record ActionsetDocument(
	String sourceName,
	Map<String, Object> root
) {
	public ActionsetDocument {
		sourceName = sourceName == null || sourceName.isBlank() ? "<memory>" : sourceName;
		root = root == null ? Map.of() : Map.copyOf(root);
	}
}
