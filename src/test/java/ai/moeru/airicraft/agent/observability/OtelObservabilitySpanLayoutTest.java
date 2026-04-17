package ai.moeru.airicraft.agent.observability;

import ai.moeru.airicraft.FirstPersonScreenshotService;
import ai.moeru.airicraft.agent.AgentConfig;
import ai.moeru.airicraft.agent.llm.LlmChatMessage;
import ai.moeru.airicraft.agent.llm.LlmConversation;
import ai.moeru.airicraft.agent.llm.LlmMessageKind;
import ai.moeru.airicraft.agent.llm.LlmUsageSnapshot;
import ai.moeru.airicraft.agent.llm.PlannerResponse;
import ai.moeru.airicraft.agent.llm.PlannerToolCall;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtelObservabilitySpanLayoutTest {
	@Test
	void weaveProfileEmitsPlannerRequestAsRootThreadRowWithPromptAndResponse() {
		CollectingSpanExporter exporter = new CollectingSpanExporter();
		OtelObservability observability = new OtelObservability(
			new AgentConfig.ObservabilityConfig(
				true,
				"otlp_http",
				"http://127.0.0.1:4318/v1/traces",
				Map.of(),
				Map.of(
					"wandb.entity", "shinohara-rin",
					"wandb.project", "airicraft"
				),
				"weave",
				false,
				true,
				true,
				false
			),
			SimpleSpanProcessor.create(exporter)
		);
			try {
				JsonObject followArgs = new JsonObject();
				followArgs.addProperty("targetPlayer", "Alice");
				followArgs.addProperty("narration", "Following you now");
				Context turnContext = observability.startTurnSpan(null, "session:test:speaker=rin");
				Context plannerContext = observability.startChildSpan(AgentObservability.PLANNER_REQUEST_SPAN_NAME, turnContext);
			observability.recordLlmRequest(
				plannerContext,
				"openai",
				URI.create("https://example.test/v1/chat/completions"),
				"planner-model",
				30_000L,
				LlmConversation.of(List.of(
					LlmChatMessage.system("You are Airicraft."),
					LlmChatMessage.user("Please follow me to the village and explain what you are doing.", LlmMessageKind.USER_TURN)
				)),
					"""
						{
						  "model": "planner-model",
						  "tools": [
						    {"type": "function", "function": {"name": "follow_player"}}
						  ],
						  "messages": [
						    {"role": "system", "content": "You are Airicraft."},
						    {"role": "user", "content": "Please follow me to the village and explain what you are doing."}
					  ]
					}
					"""
			);
			observability.recordLlmResponse(
				plannerContext,
				200,
				"planner-model",
					new LlmUsageSnapshot(111, 22, 133),
					new PlannerResponse(
						"",
						new PlannerToolCall("call_follow", "follow_player", followArgs, "Following you now", null),
						null
					)
				);
			observability.endSpan(plannerContext);
			observability.endSpan(turnContext);
		}
		finally {
			observability.shutdown();
		}

		List<SpanData> spans = exporter.finished();
		assertEquals(1, spans.size());
		SpanData plannerSpan = spans.getFirst();
		assertEquals(AgentObservability.PLANNER_REQUEST_SPAN_NAME, plannerSpan.getName());
		assertFalse(plannerSpan.getParentSpanContext().isValid());
		assertEquals("session:test:speaker=rin", plannerSpan.getAttributes().get(AttributeKey.stringKey("wandb.thread_id")));
			assertEquals(Boolean.TRUE, plannerSpan.getAttributes().get(AttributeKey.booleanKey("wandb.is_turn")));
			assertEquals("llm", plannerSpan.getAttributes().get(AttributeKey.stringKey("openinference.span.kind")));
			assertEquals("llm", plannerSpan.getAttributes().get(AttributeKey.stringKey("weave.span.kind")));
			assertEquals("follow_player", plannerSpan.getAttributes().get(AttributeKey.stringKey("airicraft.tool_name")));
			assertEquals("Following you now", plannerSpan.getAttributes().get(AttributeKey.stringKey("airicraft.tool_narration")));
			String inputValue = plannerSpan.getAttributes().get(AttributeKey.stringKey("input.value"));
		String outputValue = plannerSpan.getAttributes().get(AttributeKey.stringKey("output.value"));
		String genAiPrompt = plannerSpan.getAttributes().get(AttributeKey.stringKey("gen_ai.prompt"));
		String genAiSystem = plannerSpan.getAttributes().get(AttributeKey.stringKey("gen_ai.system"));
		String genAiCompletion = plannerSpan.getAttributes().get(AttributeKey.stringKey("gen_ai.completion"));
		assertNotNull(inputValue);
		assertNotNull(outputValue);
		assertNotNull(genAiPrompt);
		assertNotNull(genAiSystem);
		assertNotNull(genAiCompletion);
			JsonObject inputPayload = JsonParser.parseString(inputValue).getAsJsonObject();
			assertEquals("planner-model", inputPayload.get("model").getAsString());
			assertFalse(inputPayload.has("response_format"));
			assertEquals("follow_player", inputPayload.getAsJsonArray("tools").get(0).getAsJsonObject().getAsJsonObject("function").get("name").getAsString());
		JsonArray inputMessages = inputPayload.getAsJsonArray("messages");
		assertEquals(2, inputMessages.size());
		assertEquals("system", inputMessages.get(0).getAsJsonObject().get("role").getAsString());
		assertTrue(inputMessages.get(1).getAsJsonObject().get("content").getAsString().contains("Please follow me to the village"));
		JsonArray promptMessages = JsonParser.parseString(genAiPrompt).getAsJsonArray();
		assertEquals(2, promptMessages.size());
		assertEquals("system", promptMessages.get(0).getAsJsonObject().get("role").getAsString());
		assertEquals("user", promptMessages.get(1).getAsJsonObject().get("role").getAsString());
		assertTrue(genAiSystem.contains("You are Airicraft."));
			JsonObject outputPayload = JsonParser.parseString(outputValue).getAsJsonObject();
			assertEquals("assistant", outputPayload.get("role").getAsString());
			assertEquals("follow_player", outputPayload.getAsJsonObject("toolCall").get("name").getAsString());
			assertEquals("Following you now", outputPayload.getAsJsonObject("toolCall").get("narration").getAsString());
			assertTrue(genAiCompletion.contains("follow_player"));
	}

	@Test
	void genericProfilePreservesTurnParenting() {
		CollectingSpanExporter exporter = new CollectingSpanExporter();
		OtelObservability observability = new OtelObservability(
			new AgentConfig.ObservabilityConfig(
				true,
				"otlp_http",
				"http://127.0.0.1:4318/v1/traces",
				Map.of(),
				Map.of(),
				"generic",
				false,
				false,
				false,
				false
			),
			SimpleSpanProcessor.create(exporter)
		);
		try {
			Context turnContext = observability.startTurnSpan(null, "session:test:speaker=rin");
			Context plannerContext = observability.startChildSpan(AgentObservability.PLANNER_REQUEST_SPAN_NAME, turnContext);
			observability.endSpan(plannerContext);
			observability.endSpan(turnContext);
		}
		finally {
			observability.shutdown();
		}

		List<SpanData> spans = exporter.finished().stream()
			.sorted(Comparator.comparing(SpanData::getStartEpochNanos))
			.toList();
		assertEquals(2, spans.size());
		SpanData plannerTurn = spans.stream()
			.filter(span -> AgentObservability.TURN_SPAN_NAME.equals(span.getName()))
			.findFirst()
			.orElseThrow();
		SpanData plannerRequest = spans.stream()
			.filter(span -> AgentObservability.PLANNER_REQUEST_SPAN_NAME.equals(span.getName()))
			.findFirst()
			.orElseThrow();
		assertFalse(plannerTurn.getParentSpanContext().isValid());
		assertEquals(plannerTurn.getSpanId(), plannerRequest.getParentSpanId());
	}

	@Test
	void captureSpanIncludesImageOutputWhenImageCaptureEnabled() {
		CollectingSpanExporter exporter = new CollectingSpanExporter();
		OtelObservability observability = new OtelObservability(
			new AgentConfig.ObservabilityConfig(
				true,
				"otlp_http",
				"http://127.0.0.1:4318/v1/traces",
				Map.of(),
				Map.of(
					"wandb.entity", "shinohara-rin",
					"wandb.project", "airicraft"
				),
				"weave",
				false,
				false,
				false,
				true
			),
			SimpleSpanProcessor.create(exporter)
		);
		try {
			Context turnContext = observability.startTurnSpan(null, "session:test:speaker=rin");
			Context captureContext = observability.startChildSpan(AgentObservability.TOOL_CAPTURE_SPAN_NAME, turnContext);
			observability.recordImageCapture(
				captureContext,
				new FirstPersonScreenshotService.CapturedScreenshot(
					"png",
					854,
					480,
					1920,
					1080,
					1234L,
					new byte[]{1, 2, 3}
				)
			);
			observability.endSpan(captureContext);
			observability.endSpan(turnContext);
		}
		finally {
			observability.shutdown();
		}

		List<SpanData> spans = exporter.finished();
		assertEquals(1, spans.size());
		SpanData captureSpan = spans.getFirst();
		assertEquals(AgentObservability.TOOL_CAPTURE_SPAN_NAME, captureSpan.getName());
		assertEquals("image/png", captureSpan.getAttributes().get(AttributeKey.stringKey("airicraft.image.mime_type")));
		assertEquals(854L, captureSpan.getAttributes().get(AttributeKey.longKey("airicraft.image.width")));
		assertEquals(480L, captureSpan.getAttributes().get(AttributeKey.longKey("airicraft.image.height")));
		assertEquals(Boolean.TRUE, captureSpan.getAttributes().get(AttributeKey.booleanKey("airicraft.has_image_attachment")));
		String outputValue = captureSpan.getAttributes().get(AttributeKey.stringKey("output.value"));
		assertNotNull(outputValue);
		JsonObject outputPayload = JsonParser.parseString(outputValue).getAsJsonObject();
		assertEquals("image_capture", outputPayload.get("type").getAsString());
		assertEquals("image/png", outputPayload.get("mimeType").getAsString());
		assertEquals(true, outputPayload.get("imageIncludedInCompletion").getAsBoolean());
		String genAiCompletion = captureSpan.getAttributes().get(AttributeKey.stringKey("gen_ai.completion"));
		assertNotNull(genAiCompletion);
		JsonArray completion = JsonParser.parseString(genAiCompletion).getAsJsonArray();
		assertEquals(1, completion.size());
		JsonObject assistantMessage = completion.get(0).getAsJsonObject();
		assertEquals("assistant", assistantMessage.get("role").getAsString());
		JsonArray completionContent = assistantMessage.getAsJsonArray("content");
		assertEquals(1, completionContent.size());
		JsonObject imageContent = completionContent.get(0).getAsJsonObject();
		assertEquals("image_url", imageContent.get("type").getAsString());
		assertEquals("data:image/png;base64,AQID", imageContent.getAsJsonObject("image_url").get("url").getAsString());
		assertEquals("auto", imageContent.getAsJsonObject("image_url").get("detail").getAsString());
	}

	@Test
	void captureImagesPreservesActualImageUrlInLlmRequestTrace() {
		CollectingSpanExporter exporter = new CollectingSpanExporter();
		OtelObservability observability = new OtelObservability(
			new AgentConfig.ObservabilityConfig(
				true,
				"otlp_http",
				"http://127.0.0.1:4318/v1/traces",
				Map.of(),
				Map.of(
					"wandb.entity", "shinohara-rin",
					"wandb.project", "airicraft"
				),
				"weave",
				false,
				true,
				false,
				true
			),
			SimpleSpanProcessor.create(exporter)
		);
		try {
			Context turnContext = observability.startTurnSpan(null, "session:test:speaker=rin");
			Context plannerContext = observability.startChildSpan(AgentObservability.FOLLOW_UP_SPAN_NAME, turnContext);
			String requestBody = """
				{
				  "model": "planner-model",
				  "messages": [
				    {"role": "user", "content": [
				      {"type": "text", "text": "Tool result for take_a_look: current first-person view attached."},
				      {"type": "image_url", "image_url": {"url": "data:image/png;base64,AQID", "detail": "auto"}}
				    ]}
				  ]
				}
				""";
			observability.recordLlmRequest(
				plannerContext,
				"openai",
				URI.create("https://example.test/v1/chat/completions"),
				"planner-model",
				30_000L,
				LlmConversation.of(List.of(
					LlmChatMessage.user("Tool result for take_a_look: current first-person view attached.", LlmMessageKind.TOOL_RESULT)
				)),
				requestBody
			);
			observability.endSpan(plannerContext);
			observability.endSpan(turnContext);
		}
		finally {
			observability.shutdown();
		}

		List<SpanData> spans = exporter.finished();
		assertEquals(1, spans.size());
		SpanData plannerSpan = spans.getFirst();
		String inputValue = plannerSpan.getAttributes().get(AttributeKey.stringKey("input.value"));
		String promptValue = plannerSpan.getAttributes().get(AttributeKey.stringKey("gen_ai.prompt"));
		assertNotNull(inputValue);
		assertNotNull(promptValue);
		JsonObject inputPayload = JsonParser.parseString(inputValue).getAsJsonObject();
		JsonArray inputContents = inputPayload.getAsJsonArray("messages")
			.get(0).getAsJsonObject()
			.getAsJsonArray("content");
		assertEquals("image_url", inputContents.get(1).getAsJsonObject().get("type").getAsString());
		assertEquals(
			"data:image/png;base64,AQID",
			inputContents.get(1).getAsJsonObject().getAsJsonObject("image_url").get("url").getAsString()
		);
		JsonArray promptPayload = JsonParser.parseString(promptValue).getAsJsonArray();
		JsonArray promptContents = promptPayload.get(0).getAsJsonObject().getAsJsonArray("content");
		assertEquals("input_image", promptContents.get(1).getAsJsonObject().get("type").getAsString());
		assertEquals("data:image/png;base64,AQID", promptContents.get(1).getAsJsonObject().get("image_url").getAsString());
		assertEquals("auto", promptContents.get(1).getAsJsonObject().get("detail").getAsString());
	}

	private static final class CollectingSpanExporter implements SpanExporter {
		private final CopyOnWriteArrayList<SpanData> spans = new CopyOnWriteArrayList<>();

		@Override
		public CompletableResultCode export(Collection<SpanData> spans) {
			this.spans.addAll(new ArrayList<>(spans));
			return CompletableResultCode.ofSuccess();
		}

		@Override
		public CompletableResultCode flush() {
			return CompletableResultCode.ofSuccess();
		}

		@Override
		public CompletableResultCode shutdown() {
			return CompletableResultCode.ofSuccess();
		}

		List<SpanData> finished() {
			return List.copyOf(spans);
		}
	}
}
