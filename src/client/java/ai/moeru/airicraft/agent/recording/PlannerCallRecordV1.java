package ai.moeru.airicraft.agent.recording;

import com.google.gson.JsonElement;
import com.google.gson.annotations.SerializedName;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/**
 * One final planner model call published as an {@code airicraft.planner-call.v1}
 * JSONL record. All identifiers, ticks, and times that can exceed JavaScript's
 * safe integer range are serialized as decimal strings.
 */
public record PlannerCallRecordV1(
	int schemaVersion,
	String callId,
	String sequence,
	String turnId,
	PlannerAttempt plannerAttempt,
	Timeline timeline,
	Timing timing,
	Model model,
	Request request,
	Outcome outcome
) {
	public static final int SCHEMA_VERSION = 1;
	public static final String ASSET_SCHEMA = "airicraft.planner-call.v1";

	public PlannerCallRecordV1 {
		if (schemaVersion != SCHEMA_VERSION) {
			throw new IllegalArgumentException("schemaVersion must be " + SCHEMA_VERSION);
		}
		callId = required(callId, "callId");
		sequence = positiveDecimal(sequence, "sequence");
		turnId = required(turnId, "turnId");
		plannerAttempt = Objects.requireNonNull(plannerAttempt, "plannerAttempt");
		timeline = Objects.requireNonNull(timeline, "timeline");
		timing = Objects.requireNonNull(timing, "timing");
		model = Objects.requireNonNull(model, "model");
		request = Objects.requireNonNull(request, "request");
		outcome = Objects.requireNonNull(outcome, "outcome");
	}

	public record PlannerAttempt(
		String generation,
		int attempt,
		String phase
	) {
		public PlannerAttempt {
			generation = nonNegativeDecimal(generation, "plannerAttempt.generation");
			if (attempt < 1) {
				throw new IllegalArgumentException("plannerAttempt.attempt must be positive");
			}
			phase = required(phase, "plannerAttempt.phase");
		}
	}

	public record Timeline(
		Anchor submitted,
		Anchor completed,
		Anchor applied
	) {
		public Timeline {
			submitted = Objects.requireNonNull(submitted, "timeline.submitted");
			completed = Objects.requireNonNull(completed, "timeline.completed");
			if (completed.value().compareTo(submitted.value()) < 0) {
				throw new IllegalArgumentException("timeline.completed must not precede timeline.submitted");
			}
			if (applied != null && applied.value().compareTo(completed.value()) < 0) {
				throw new IllegalArgumentException("timeline.applied must not precede timeline.completed");
			}
		}
	}

	public record Anchor(String serverTick) {
		public Anchor {
			serverTick = nonNegativeDecimal(serverTick, "serverTick");
		}

		private BigInteger value() {
			return new BigInteger(serverTick);
		}
	}

	public record Timing(
		String requestedAtUnixMs,
		String completedAtUnixMs,
		String latencyMs
	) {
		public Timing {
			requestedAtUnixMs = nonNegativeDecimal(requestedAtUnixMs, "timing.requestedAtUnixMs");
			completedAtUnixMs = nonNegativeDecimal(completedAtUnixMs, "timing.completedAtUnixMs");
			latencyMs = nonNegativeDecimal(latencyMs, "timing.latencyMs");
			BigInteger requested = new BigInteger(requestedAtUnixMs);
			BigInteger completed = new BigInteger(completedAtUnixMs);
			if (completed.compareTo(requested) < 0) {
				throw new IllegalArgumentException("timing.completedAtUnixMs must not precede timing.requestedAtUnixMs");
			}
			if (!completed.subtract(requested).equals(new BigInteger(latencyMs))) {
				throw new IllegalArgumentException("timing.latencyMs must equal completedAtUnixMs - requestedAtUnixMs");
			}
		}
	}

	public record Model(
		String provider,
		String name
	) {
		public Model {
			provider = required(provider, "model.provider");
			name = required(name, "model.name");
		}
	}

	public record Request(
		List<JsonElement> messages,
		List<JsonElement> tools
	) {
		public Request {
			messages = jsonObjects(messages, "request.messages");
			tools = jsonObjects(tools, "request.tools");
		}
	}

	public record Outcome(
		Status status,
		JsonElement assistantContent,
		List<JsonElement> toolCalls,
		Usage usage,
		Failure failure
	) {
		public Outcome {
			status = Objects.requireNonNull(status, "outcome.status");
			assistantContent = assistantContent == null || assistantContent.isJsonNull()
				? null
				: assistantContent.deepCopy();
			toolCalls = jsonObjects(toolCalls, "outcome.toolCalls");
			usage = Objects.requireNonNull(usage, "outcome.usage");
			if (status == Status.COMPLETED && failure != null) {
				throw new IllegalArgumentException("outcome.failure is not valid for a completed call");
			}
			if (status != Status.COMPLETED && failure == null) {
				throw new IllegalArgumentException("outcome.failure is required for a failed or cancelled call");
			}
		}
	}

	public enum Status {
		@SerializedName("completed")
		COMPLETED,
		@SerializedName("failed")
		FAILED,
		@SerializedName("cancelled")
		CANCELLED
	}

	public record Usage(
		Integer promptTokens,
		Integer completionTokens,
		Integer totalTokens
	) {
		public Usage {
			nonNegative(promptTokens, "outcome.usage.promptTokens");
			nonNegative(completionTokens, "outcome.usage.completionTokens");
			nonNegative(totalTokens, "outcome.usage.totalTokens");
		}
	}

	public record Failure(
		String type,
		String message
	) {
		public Failure {
			type = required(type, "outcome.failure.type");
			message = required(message, "outcome.failure.message");
		}
	}

	private static List<JsonElement> jsonObjects(List<JsonElement> values, String field) {
		Objects.requireNonNull(values, field);
		return values.stream()
			.map(value -> {
				if (value == null || !value.isJsonObject()) {
					throw new IllegalArgumentException(field + " must contain only JSON objects");
				}
				return value.deepCopy();
			})
			.toList();
	}

	private static String required(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return value;
	}

	private static String positiveDecimal(String value, String field) {
		String normalized = nonNegativeDecimal(value, field);
		if (new BigInteger(normalized).signum() == 0) {
			throw new IllegalArgumentException(field + " must be positive");
		}
		return normalized;
	}

	private static String nonNegativeDecimal(String value, String field) {
		if (value == null || !value.matches("0|[1-9][0-9]*")) {
			throw new IllegalArgumentException(field + " must be a non-negative decimal string");
		}
		return value;
	}

	private static void nonNegative(Integer value, String field) {
		if (value != null && value.intValue() < 0) {
			throw new IllegalArgumentException(field + " must not be negative");
		}
	}
}
