package ai.moeru.airicraft.dashboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class DashboardObservationStore {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final Map<String, Integer> TYPE_CAPS = Map.of(
		"runtime_snapshot", 4096,
		"semantic_event", 4096,
		"debug_timeline", 4096,
		"llm_call", 2048,
		"log", 8192,
		"visual_frame", 512,
		"session_started", 128,
		"observation_gap", 512
	);

	private final ArrayDeque<DashboardObservation> observations = new ArrayDeque<>();
	private final Map<String, Integer> typeCounts = new LinkedHashMap<>();
	private final Map<String, Long> droppedByType = new LinkedHashMap<>();
	private long maxBytes;
	private long retainedBytes;
	private long nextSequence = 1L;
	private long latestTick;
	private String sessionId = UUID.randomUUID().toString();
	private long sessionStartedAtMs;

	public DashboardObservationStore(long maxBytes) {
		this.maxBytes = Math.max(1024L * 1024L, maxBytes);
	}

	public synchronized String startSession(String reason, long tick, long capturedAtMs) {
		sessionId = UUID.randomUUID().toString();
		sessionStartedAtMs = capturedAtMs;
		latestTick = tick;
		appendJson("session_started", tick, capturedAtMs, GSON.toJson(Map.of(
			"reason", reason == null || reason.isBlank() ? "runtime_started" : reason
		)));
		return sessionId;
	}

	public DashboardObservation append(String type, long tick, long capturedAtMs, Object payload) {
		return appendJson(type, tick, capturedAtMs, GSON.toJson(payload));
	}

	public synchronized DashboardObservation appendJson(
		String type,
		long tick,
		long capturedAtMs,
		String payloadJson
	) {
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(payloadJson, "payloadJson");
		latestTick = Math.max(latestTick, tick);

		DashboardObservation observation = new DashboardObservation(
			nextSequence++,
			sessionId,
			tick,
			capturedAtMs,
			type,
			payloadJson
		);
		if (observation.retainedBytes() > maxBytes) {
			incrementDropped(type);
			observation = new DashboardObservation(
				observation.sequence(),
				sessionId,
				tick,
				capturedAtMs,
				"observation_gap",
				GSON.toJson(Map.of(
					"reason", "observation_exceeds_history_budget",
					"originalType", type,
					"estimatedBytes", observation.retainedBytes()
				))
			);
		}

		observations.addLast(observation);
		retainedBytes += observation.retainedBytes();
		typeCounts.merge(observation.type(), 1, Integer::sum);
		trimType(observation.type());
		trimBudget();
		notifyAll();
		return observation;
	}

	public synchronized void updateMaxBytes(long nextMaxBytes) {
		maxBytes = Math.max(1024L * 1024L, nextMaxBytes);
		trimBudget();
	}

	public synchronized Query queryAfter(long sinceSequence, int limit) {
		int safeLimit = Math.max(1, Math.min(5000, limit));
		long oldestSequence = observations.isEmpty() ? nextSequence : observations.peekFirst().sequence();
		long latestSequence = observations.isEmpty() ? nextSequence - 1L : observations.peekLast().sequence();
		ArrayList<DashboardObservation> matches = new ArrayList<>();
		for (DashboardObservation observation : observations) {
			if (observation.sequence() > sinceSequence) {
				matches.add(observation);
				if (matches.size() >= safeLimit) {
					break;
				}
			}
		}
		boolean truncated = !observations.isEmpty() && sinceSequence < oldestSequence - 1L;
		return new Query(
			sessionId,
			sessionStartedAtMs,
			latestTick,
			oldestSequence,
			latestSequence,
			truncated,
			retainedBytes,
			maxBytes,
			Map.copyOf(droppedByType),
			List.copyOf(matches)
		);
	}

	public synchronized Query snapshot() {
		return queryAfter(Long.MIN_VALUE, 5000);
	}

	public synchronized boolean awaitAfter(long sinceSequence, Duration timeout) throws InterruptedException {
		long timeoutMillis = Math.max(1L, timeout.toMillis());
		long deadline = System.currentTimeMillis() + timeoutMillis;
		while (latestSequence() <= sinceSequence) {
			long remaining = deadline - System.currentTimeMillis();
			if (remaining <= 0L) {
				return false;
			}
			wait(remaining);
		}
		return true;
	}

	public synchronized List<DashboardObservation> retainedObservations() {
		return List.copyOf(observations);
	}

	public synchronized long latestTick() {
		return latestTick;
	}

	private long latestSequence() {
		return observations.isEmpty() ? nextSequence - 1L : observations.peekLast().sequence();
	}

	private void trimType(String type) {
		int cap = TYPE_CAPS.getOrDefault(type, 4096);
		while (typeCounts.getOrDefault(type, 0) > cap) {
			Iterator<DashboardObservation> iterator = observations.iterator();
			while (iterator.hasNext()) {
				DashboardObservation candidate = iterator.next();
				if (candidate.type().equals(type)) {
					iterator.remove();
					removed(candidate);
					break;
				}
			}
		}
	}

	private void trimBudget() {
		while (retainedBytes > maxBytes && !observations.isEmpty()) {
			removed(observations.removeFirst());
		}
	}

	private void removed(DashboardObservation observation) {
		retainedBytes -= observation.retainedBytes();
		typeCounts.computeIfPresent(observation.type(), (ignored, count) -> count <= 1 ? null : count - 1);
		incrementDropped(observation.type());
	}

	private void incrementDropped(String type) {
		droppedByType.merge(type, 1L, Long::sum);
	}

	public record Query(
		String sessionId,
		long sessionStartedAtMs,
		long latestTick,
		long oldestSequence,
		long latestSequence,
		boolean truncated,
		long retainedBytes,
		long maxBytes,
		Map<String, Long> droppedByType,
		List<DashboardObservation> observations
	) {
	}
}
