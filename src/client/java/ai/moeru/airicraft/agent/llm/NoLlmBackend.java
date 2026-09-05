package ai.moeru.airicraft.agent.llm;

/** A disconnected backend for deterministic runs, including vision and debug injections. */
public final class NoLlmBackend implements LlmBackend, VisionBackend {
	@Override
	public boolean isConfigured() {
		return false;
	}

	@Override
	public LlmCallResult<PlannerResponse> generate(LlmConversation conversation) {
		throw unavailable();
	}

	@Override
	public VisionDescription describe(VisionRequest request) {
		throw unavailable();
	}

	@Override
	public void injectMockResponse(PlannerResponse response) {
		throw unavailable();
	}

	@Override
	public void injectTimeout() {
		throw unavailable();
	}

	private static IllegalStateException unavailable() {
		return new IllegalStateException("LLM calls are disabled in no-LLM mode");
	}
}
