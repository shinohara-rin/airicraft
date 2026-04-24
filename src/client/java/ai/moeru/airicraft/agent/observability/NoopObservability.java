package ai.moeru.airicraft.agent.observability;

import ai.moeru.airicraft.FirstPersonScreenshotService;
import ai.moeru.airicraft.agent.llm.LlmConversation;
import ai.moeru.airicraft.agent.llm.LlmUsageSnapshot;
import ai.moeru.airicraft.agent.llm.PlannerRequest;
import ai.moeru.airicraft.agent.llm.PlannerResponse;
import ai.moeru.airicraft.agent.llm.CompactionCheckpoint;
import ai.moeru.airicraft.agent.llm.VisionDescription;
import ai.moeru.airicraft.agent.llm.VisionRequest;
import io.opentelemetry.context.Context;

import java.net.URI;

public enum NoopObservability implements AgentObservability {
	INSTANCE;

	@Override
	public Context startTurnSpan(PlannerRequest request, String threadId) {
		return Context.root();
	}

	@Override
	public Context startChildSpan(String name, Context parent) {
		return parent == null ? Context.root() : parent;
	}

	@Override
	public void setSpanAttribute(Context context, String key, String value) {
	}

	@Override
	public void setSpanAttribute(Context context, String key, boolean value) {
	}

	@Override
	public void setSpanAttribute(Context context, String key, long value) {
	}

	@Override
	public void recordImageCapture(Context context, FirstPersonScreenshotService.CapturedScreenshot capture) {
	}

	@Override
	public void recordLlmRequest(
		Context context,
		String providerName,
		URI endpoint,
		String model,
		long timeoutMillis,
		LlmConversation conversation,
		String requestBody
	) {
	}

	@Override
	public void recordFailedLlmInput(Context context, LlmConversation conversation, String requestBody) {
	}

	@Override
	public void recordLlmRequest(
		Context context,
		String providerName,
		URI endpoint,
		String model,
		long timeoutMillis,
		VisionRequest request,
		String imageDetail,
		String requestBody
	) {
	}

	@Override
	public void recordLlmResponse(Context context, Integer statusCode, String responseModel, LlmUsageSnapshot usage, PlannerResponse plannerResponse) {
	}

	@Override
	public void recordLlmResponse(Context context, Integer statusCode, String responseModel, LlmUsageSnapshot usage, String rawResponseBody) {
	}

	@Override
	public void recordLlmResponse(Context context, Integer statusCode, String responseModel, LlmUsageSnapshot usage, CompactionCheckpoint checkpoint) {
	}

	@Override
	public void recordLlmResponse(Context context, Integer statusCode, String responseModel, LlmUsageSnapshot usage, VisionDescription visionDescription) {
	}

	@Override
	public void recordFailure(Context context, String failureType, String message, Throwable throwable) {
	}

	@Override
	public void endSpan(Context context) {
	}

	@Override
	public void shutdown() {
	}
}
