package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.Airicraft;
import ai.moeru.airicraft.agent.AgentConfig;
import ai.moeru.airicraft.agent.observability.AgentObservability;
import ai.moeru.airicraft.agent.observability.NoopObservability;
import ai.moeru.airicraft.agent.observability.TraceSanitizer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.opentelemetry.context.Context;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

public final class OpenAiCompatibleLlmBackend implements LlmBackend {
	private final AgentConfig.LlmConfig config;
	private final OpenAiCompatibleChatClient chatClient;
	private final AgentObservability observability;
	private final PlannerToolRegistry toolRegistry;
	private final Deque<Object> injectedOutcomes = new ArrayDeque<>();

	public OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig config) {
		this(config, NoopObservability.INSTANCE, PlannerToolRegistry.empty());
	}

	public OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig config, PlannerToolRegistry toolRegistry) {
		this(config, NoopObservability.INSTANCE, toolRegistry);
	}

	public OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig config, AgentObservability observability) {
		this(config, observability, PlannerToolRegistry.empty());
	}

	public OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig config, AgentObservability observability, PlannerToolRegistry toolRegistry) {
		this.config = Objects.requireNonNull(config, "config");
		this.observability = Objects.requireNonNull(observability, "observability");
		this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
		this.chatClient = new OpenAiCompatibleChatClient(config, observability, toolRegistry);
	}

	@Override
	public synchronized LlmCallResult<PlannerResponse> generate(LlmConversation conversation) throws LlmBackendException {
		Objects.requireNonNull(conversation, "conversation");

		Object injected = injectedOutcomes.pollFirst();
		if (injected instanceof PlannerResponse plannerResponse) {
			observability.recordLlmResponse(Context.current(), null, config.model(), LlmUsageSnapshot.unknown(), plannerResponse);
			return LlmCallResult.of(plannerResponse, LlmUsageSnapshot.unknown(), null, config.model());
		}
		if (injected instanceof TimeoutException timeoutException) {
			observability.recordFailure(Context.current(), LlmFailureType.TIMEOUT.name(), timeoutException.getMessage(), timeoutException);
			throw new LlmBackendException(LlmFailureType.TIMEOUT, timeoutException.getMessage(), timeoutException);
		}

		LlmCallResult<String> rawResponse = chatClient.complete(conversation, LlmRequestOptions.planner());
		PlannerResponse plannerResponse = parsePlannerResponse(conversation, rawResponse);
		observability.recordLlmResponse(Context.current(), rawResponse.statusCode(), rawResponse.responseModel(), rawResponse.usage(), plannerResponse);
		return LlmCallResult.of(plannerResponse, rawResponse.usage(), rawResponse.statusCode(), rawResponse.responseModel());
	}

	@Override
	public synchronized void injectMockResponse(PlannerResponse response) {
		injectedOutcomes.addLast(Objects.requireNonNull(response, "response"));
	}

	@Override
	public synchronized void injectTimeout() {
		injectedOutcomes.addLast(new TimeoutException("Injected LLM timeout"));
	}

	@Override
	public boolean isConfigured() {
		return config.isConfigured();
	}

	private PlannerResponse parsePlannerResponse(LlmConversation conversation, LlmCallResult<String> rawResponse) throws LlmBackendException {
		String responseBody = rawResponse == null ? "" : rawResponse.payload();
		try {
			JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
			JsonArray choices = root.getAsJsonArray("choices");
			if (choices == null || choices.isEmpty()) {
				throw new JsonParseException("Missing choices");
			}

			JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
			if (message == null) {
				throw new JsonParseException("Missing message");
			}

			JsonElement rawAssistantContent = OpenAiCompatibleMessageContent.rawContentForReplay(message.get("content"));
			String visibleText = OpenAiCompatibleMessageContent.extractVisibleText(message.get("content"));
			PlannerToolCall toolCall = parseToolCall(message);
			if (toolCall != null) {
				Airicraft.LOGGER.info(
					"Planner parsed tool_call name={} narration={}",
					toolCall.name(),
					summarizeForLog(toolCall.narration())
				);
				return new PlannerResponse("", toolCall, rawAssistantContent);
			}
			String replyText = visibleText.strip();
			Airicraft.LOGGER.info("Planner parsed plaintext reply={}", summarizeForLog(replyText));
			return new PlannerResponse(replyText, null, rawAssistantContent);
		}
		catch (IllegalArgumentException | JsonParseException exception) {
			String failureMessage = plannerParseFailureMessage(exception);
			Airicraft.LOGGER.warn("Failed to parse planner response summary={}", TraceSanitizer.summarizeChatResponseForLog(responseBody), exception);
			observability.recordFailedLlmInput(Context.current(), conversation, rawResponse == null ? null : rawResponse.requestBody());
			observability.recordLlmResponse(
				Context.current(),
				rawResponse == null ? null : rawResponse.statusCode(),
				rawResponse == null ? config.model() : rawResponse.responseModel(),
				rawResponse == null ? LlmUsageSnapshot.unknown() : rawResponse.usage(),
				responseBody
			);
			observability.recordFailure(Context.current(), LlmFailureType.PARSE_ERROR.name(), failureMessage, exception);
			throw new LlmBackendException(LlmFailureType.PARSE_ERROR, failureMessage, exception);
		}
	}

	private PlannerToolCall parseToolCall(JsonObject message) {
		if (message == null || !message.has("tool_calls") || !message.get("tool_calls").isJsonArray()) {
			return null;
		}
		JsonArray toolCalls = message.getAsJsonArray("tool_calls");
		if (toolCalls.isEmpty()) {
			return null;
		}
		if (toolCalls.size() > 1) {
			PlannerToolCall selected = selectEntityActionToolCall(toolCalls);
			if (selected != null) {
				Airicraft.LOGGER.warn("Planner returned multiple tool calls names={} selected={}", summarizeToolCallNames(toolCalls), selected.name());
				return selected;
			}
			throw new JsonParseException("Planner returned multiple tool calls: " + summarizeToolCallNames(toolCalls));
		}
		if (!toolCalls.get(0).isJsonObject()) {
			throw new JsonParseException("Planner tool call must be an object");
		}
		return PlannerToolCatalog.parseToolCall(toolCalls.get(0).getAsJsonObject(), toolRegistry);
	}

	private PlannerToolCall selectEntityActionToolCall(JsonArray toolCalls) {
		List<JsonObject> callObjects = toolCalls.asList().stream()
			.filter(JsonElement::isJsonObject)
			.map(JsonElement::getAsJsonObject)
			.toList();
		if (callObjects.size() != toolCalls.size()) {
			return null;
		}

		List<JsonObject> entityActionCalls = callObjects.stream()
			.filter(this::isEntityInteractionToolCall)
			.toList();
		if (entityActionCalls.isEmpty()) {
			return null;
		}

		boolean onlyReadOrEntityActions = callObjects.stream().allMatch(call ->
			isEntityInteractionToolCall(call) || PlannerToolCatalog.isReadTool(rawToolName(call))
		);
		if (!onlyReadOrEntityActions) {
			return null;
		}

		long distinctEntityActionNames = entityActionCalls.stream()
			.map(this::rawToolName)
			.distinct()
			.count();
		if (distinctEntityActionNames != 1L) {
			return null;
		}

		return PlannerToolCatalog.parseToolCall(entityActionCalls.getFirst(), toolRegistry);
	}

	private boolean isEntityInteractionToolCall(JsonObject toolCall) {
		String name = rawToolName(toolCall);
		return PlannerToolCatalog.ATTACK_ENTITY.equals(name) || PlannerToolCatalog.USE_ENTITY.equals(name);
	}

	private String rawToolName(JsonObject toolCall) {
		if (toolCall == null || !toolCall.has("function") || !toolCall.get("function").isJsonObject()) {
			return "";
		}
		JsonObject function = toolCall.getAsJsonObject("function");
		if (!function.has("name") || function.get("name").isJsonNull()) {
			return "";
		}
		return PlannerToolCatalog.normalizeName(function.get("name").getAsString());
	}

	private static String summarizeToolCallNames(JsonArray toolCalls) {
		LinkedHashSet<String> names = new LinkedHashSet<>();
		for (JsonElement toolCall : toolCalls) {
			if (!toolCall.isJsonObject()) {
				names.add("<non_object>");
				continue;
			}
			JsonObject object = toolCall.getAsJsonObject();
			if (!object.has("function") || !object.get("function").isJsonObject()) {
				names.add("<missing_function>");
				continue;
			}
			JsonObject function = object.getAsJsonObject("function");
			if (!function.has("name") || function.get("name").isJsonNull()) {
				names.add("<missing_name>");
				continue;
			}
			names.add(PlannerToolCatalog.normalizeName(function.get("name").getAsString()));
		}
		return String.join(",", names);
	}

	private static String plannerParseFailureMessage(Exception exception) {
		String detail = exception == null ? "" : exception.getMessage();
		if (detail == null || detail.isBlank()) {
			return "Failed to parse planner response";
		}
		return "Failed to parse planner response: " + detail;
	}

	static String stripMarkdownCodeFences(String text) {
		String trimmed = text.strip();
		if (trimmed.startsWith("```")) {
			int firstNewline = trimmed.indexOf('\n');
			if (firstNewline >= 0) {
				trimmed = trimmed.substring(firstNewline + 1);
			}
			if (trimmed.endsWith("```")) {
				trimmed = trimmed.substring(0, trimmed.length() - 3);
			}
			return trimmed.strip();
		}
		return text;
	}

	private static String summarizeForLog(String text) {
		return OpenAiCompatibleChatClient.summarizeForLog(text);
	}
}
