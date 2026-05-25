package ai.moeru.airicraft.agent.llm;

public enum PlannerTriggerType {
	CHAT("chat"),
	CRAFT("craft"),
	DAMAGE("damage"),
	PICKUP("pickup"),
	SYSTEM("system"),
	IDLE_THINK("idle_think");

	private final String promptLabel;

	PlannerTriggerType(String promptLabel) {
		this.promptLabel = promptLabel;
	}

	public String promptLabel() {
		return promptLabel;
	}
}
