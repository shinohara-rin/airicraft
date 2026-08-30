package ai.moeru.airicraft.dashboard;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashboardObservationStoreTest {
	@Test
	void recordsVersionedObservationsWithinOneSession() {
		DashboardObservationStore store = new DashboardObservationStore(1024L * 1024L);
		String sessionId = store.startSession("client_started", 0L, 100L);
		store.append("semantic_event", 3L, 150L, Map.of("type", "task.started"));

		DashboardObservationStore.Query query = store.queryAfter(0L, 100);

		assertEquals(sessionId, query.sessionId());
		assertEquals(2, query.observations().size());
		assertEquals("session_started", query.observations().get(0).type());
		assertEquals("semantic_event", query.observations().get(1).type());
		assertEquals(3L, query.latestTick());
		assertFalse(query.truncated());
	}

	@Test
	void startsANewSessionWithoutDiscardingRetainedHistory() {
		DashboardObservationStore store = new DashboardObservationStore(1024L * 1024L);
		String first = store.startSession("client_started", 0L, 100L);
		store.append("runtime_snapshot", 5L, 150L, Map.of("state", "idle"));
		String second = store.startSession("runtime_reloaded", 6L, 200L);

		assertNotEquals(first, second);
		assertEquals(3, store.retainedObservations().size());
		assertEquals(first, store.retainedObservations().get(1).sessionId());
		assertEquals(second, store.retainedObservations().get(2).sessionId());
	}

	@Test
	void reportsCursorGapsAfterBudgetEviction() {
		DashboardObservationStore store = new DashboardObservationStore(1024L * 1024L);
		store.startSession("client_started", 0L, 100L);
		String large = "x".repeat(400_000);
		for (int index = 0; index < 5; index++) {
			store.append("log", index, 200L + index, Map.of("message", large));
		}

		DashboardObservationStore.Query query = store.queryAfter(0L, 100);

		assertTrue(query.truncated());
		assertTrue(query.oldestSequence() > 1L);
		assertTrue(query.droppedByType().getOrDefault("log", 0L) > 0L);
		assertTrue(query.retainedBytes() <= query.maxBytes());
	}
}
