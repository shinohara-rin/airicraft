package ai.moeru.airicraft.wrapper;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientTickTraceJsonlStreamerTest {
	@Test
	void bufferWaitsForTheCompletedFrameBeforeItStreamsTheRecord() {
		ClientTickTraceJsonlStreamer.TraceRecordBuffer buffer = new ClientTickTraceJsonlStreamer.TraceRecordBuffer(40L);

		buffer.accept(record(41L, "PENDING"));

		assertTrue(buffer.drainCompleted().isEmpty());

		buffer.accept(record(41L, "CAPTURED"));

		List<Map<String, Object>> records = buffer.drainCompleted();
		assertEquals(1, records.size());
		assertEquals("CAPTURED", frameStatus(records.getFirst()));
		assertEquals(41L, buffer.lastWrittenClientTickId());
	}

	@Test
	void bufferStreamsRecordsInClientTickOrder() {
		ClientTickTraceJsonlStreamer.TraceRecordBuffer buffer = new ClientTickTraceJsonlStreamer.TraceRecordBuffer(40L);

		buffer.accept(record(42L, "CAPTURED"));
		buffer.accept(record(41L, "CAPTURED"));

		assertEquals(
			List.of(41L, 42L),
			buffer.drainCompleted().stream().map(record -> ((Number) record.get("clientTickId")).longValue()).toList()
		);
	}

	private static Map<String, Object> record(long clientTickId, String status) {
		return Map.of(
			"clientTickId", clientTickId,
			"frame", Map.of("status", status)
		);
	}

	private static String frameStatus(Map<String, Object> record) {
		return (String) ((Map<?, ?>) record.get("frame")).get("status");
	}
}
