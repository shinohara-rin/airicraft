package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.observability.AgentObservability;
import ai.moeru.airicraft.agent.observability.NoopObservability;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PlannerCompactionService {
	private final OpenAiCompatibleChatClient chatClient;
	private final AgentObservability observability;
	private ExecutorService executorService;

	private CompletableFuture<LlmCallResult<CompactionCheckpoint>> inFlight;
	private Context inFlightContext;

	public PlannerCompactionService(OpenAiCompatibleChatClient chatClient) {
		this(chatClient, NoopObservability.INSTANCE);
	}

	public PlannerCompactionService(OpenAiCompatibleChatClient chatClient, AgentObservability observability) {
		this.chatClient = Objects.requireNonNull(chatClient, "chatClient");
		this.observability = Objects.requireNonNull(observability, "observability");
		this.executorService = newExecutorService();
	}

	private static ExecutorService newExecutorService() {
		return Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "airicraft-compaction");
			thread.setDaemon(true);
			return thread;
		});
	}

	public boolean hasInFlight() {
		return inFlight != null;
	}

	public boolean submit(LlmConversation conversation) {
		return submit(conversation, Context.current());
	}

	public boolean submit(LlmConversation conversation, Context parentContext) {
		Objects.requireNonNull(conversation, "conversation");
		if (inFlight != null) {
			return false;
		}

		Context executionContext = parentContext == null ? Context.current() : parentContext;
		Context compactionContext = observability.startChildSpan(
			AgentObservability.PLANNER_COMPACTION_SPAN_NAME,
			executionContext
		);
		inFlight = CompletableFuture.supplyAsync(() -> {
			try (Scope scope = compactionContext.makeCurrent()) {
					LlmCallResult<String> response = chatClient.complete(conversation, LlmRequestOptions.compaction());
				CompactionCheckpoint checkpoint = parseCheckpoint(response.payload());
				observability.recordLlmResponse(Context.current(), response.statusCode(), response.responseModel(), response.usage(), checkpoint);
				return LlmCallResult.of(checkpoint, response.usage(), response.statusCode(), response.responseModel());
			}
			catch (LlmBackendException exception) {
				throw new CompletionException(exception);
			}
		}, executorService);
		inFlightContext = compactionContext;
		return true;
	}

	public CompactionExecutionResult poll() {
		if (inFlight == null || !inFlight.isDone()) {
			return null;
		}

		CompletableFuture<LlmCallResult<CompactionCheckpoint>> future = inFlight;
		inFlight = null;
		Context completedContext = inFlightContext;
		inFlightContext = null;
		endCurrentFlightSpan(completedContext);
		Context failureContext = completedContext == null ? Context.current() : completedContext;
		try {
			LlmCallResult<CompactionCheckpoint> result = future.join();
			return new CompactionExecutionResult(result.payload(), result.usage(), null, null);
		}
		catch (CompletionException exception) {
			Throwable cause = exception.getCause();
			if (cause instanceof LlmBackendException backendException) {
				observability.recordFailure(
					failureContext,
					backendException.failureType().name(),
					backendException.getMessage(),
					backendException
				);
				return new CompactionExecutionResult(null, LlmUsageSnapshot.unknown(), backendException.failureType(), backendException.getMessage());
			}
			observability.recordFailure(
				failureContext,
				LlmFailureType.PROVIDER_ERROR.name(),
				cause == null ? exception.getMessage() : cause.getMessage(),
				cause instanceof Throwable throwable ? throwable : exception
			);
			return new CompactionExecutionResult(
				null,
				LlmUsageSnapshot.unknown(),
				LlmFailureType.PROVIDER_ERROR,
				cause == null ? exception.getMessage() : cause.getMessage()
			);
		}
	}

	public void reset() {
		cancelInFlight();
		executorService.shutdownNow();
		executorService = newExecutorService();
	}

	public void shutdown() {
		cancelInFlight();
		executorService.shutdownNow();
	}

	private void cancelInFlight() {
		if (inFlight == null) {
			return;
		}
		inFlight.cancel(true);
		inFlight = null;
		endCurrentFlightSpan(inFlightContext);
		inFlightContext = null;
	}

	private void endCurrentFlightSpan(Context context) {
		if (context != null) {
			observability.endSpan(context);
		}
	}

	private CompactionCheckpoint parseCheckpoint(String responseBody) throws LlmBackendException {
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
			JsonObject payload = OpenAiCompatibleMessageContent.extractJsonObject(message.get("content"))
				.filter(PlannerCompactionService::looksLikeCompactionPayload)
				.orElseThrow(() -> new JsonParseException("Missing compaction payload"));
			return new CompactionCheckpoint(
				getString(payload, "time_anchor"),
				getString(payload, "session_state"),
				getString(payload, "active_goal"),
				getStringList(payload, "active_commitments"),
				getStringList(payload, "durable_facts"),
				getStringList(payload, "relevant_people"),
				getStringList(payload, "open_loops"),
				getStringList(payload, "recent_timeline"),
				getStringList(payload, "forgettable_noise")
			);
		}
		catch (IllegalStateException | JsonParseException exception) {
			observability.recordFailure(Context.current(), LlmFailureType.PARSE_ERROR.name(), "Failed to parse compaction response", exception);
			throw new LlmBackendException(LlmFailureType.PARSE_ERROR, "Failed to parse compaction response", exception);
		}
	}

	private static boolean looksLikeCompactionPayload(JsonObject payload) {
		return payload.has("time_anchor")
			|| payload.has("session_state")
			|| payload.has("active_goal")
			|| payload.has("active_commitments")
			|| payload.has("durable_facts")
			|| payload.has("recent_timeline");
	}

	private static String getString(JsonObject payload, String fieldName) {
		if (payload == null || !payload.has(fieldName) || payload.get(fieldName).isJsonNull()) {
			return null;
		}
		String value = payload.get(fieldName).getAsString();
		return value == null || value.isBlank() ? null : value;
	}

	private static List<String> getStringList(JsonObject payload, String fieldName) {
		if (payload == null || !payload.has(fieldName) || payload.get(fieldName).isJsonNull()) {
			return List.of();
		}
		if (!payload.get(fieldName).isJsonArray()) {
			String single = payload.get(fieldName).getAsString();
			return single == null || single.isBlank() ? List.of() : List.of(single);
		}
		ArrayList<String> values = new ArrayList<>();
		for (var item : payload.getAsJsonArray(fieldName)) {
			if (item == null || item.isJsonNull()) {
				continue;
			}
			String value = item.getAsString();
			if (value != null && !value.isBlank()) {
				values.add(value);
			}
		}
		return List.copyOf(values);
	}
}
