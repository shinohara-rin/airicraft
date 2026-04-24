package ai.moeru.airicraft.agent.actions;

public enum ActionFactProvenance {
	OBSERVED(true),
	EXECUTOR_REPORTED(true),
	EXPECTED(false),
	INFERRED(false),
	FAILED(false),
	STALE(false);

	private final boolean authoritative;

	ActionFactProvenance(boolean authoritative) {
		this.authoritative = authoritative;
	}

	public boolean authoritative() {
		return authoritative;
	}
}
