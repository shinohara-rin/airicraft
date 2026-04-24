package ai.moeru.airicraft.agent.actions;

public enum ActionsetNamespace {
	BUILTIN("builtin", true),
	OPERATOR("operator", true),
	ENABLED("enabled", true),
	PLANNER_DRAFTS("planner_drafts", false);

	private final String directoryName;
	private final boolean indexed;

	ActionsetNamespace(String directoryName, boolean indexed) {
		this.directoryName = directoryName;
		this.indexed = indexed;
	}

	public String directoryName() {
		return directoryName;
	}

	public boolean indexed() {
		return indexed;
	}
}
