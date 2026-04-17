package ai.moeru.airicraft.agent.llm;

public record LlmRequestOptions(
	boolean jsonObjectResponseFormat,
	boolean plannerTools
) {
	public static LlmRequestOptions planner() {
		return new LlmRequestOptions(false, true);
	}

	public static LlmRequestOptions compaction() {
		return new LlmRequestOptions(true, false);
	}

	public static LlmRequestOptions plain() {
		return new LlmRequestOptions(false, false);
	}
}
