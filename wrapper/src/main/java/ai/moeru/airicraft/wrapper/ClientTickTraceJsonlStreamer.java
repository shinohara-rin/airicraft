package ai.moeru.airicraft.wrapper;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Streams one client tick trace into a JSON Lines file.
 *
 * <p>The bridge keeps the short rolling trace buffer. This class reads that
 * buffer at the client tick rate and batches file writes. It holds a pending
 * frame record until the capture completes so a record never loses its image
 * only because the capture completed after the first poll.</p>
 */
final class ClientTickTraceJsonlStreamer {
	static final int RECORD_PAGE_LIMIT = 256;
	static final long POLL_INTERVAL_MS = 50L;
	static final int OUTPUT_BUFFER_BYTES = 64 * 1024;
	private static final int FLUSH_RECORD_COUNT = 20;
	private static final int FINAL_FRAME_SETTLE_POLLS = 20;

	private final MinecraftTransport transport;
	private final ObjectMapper objectMapper;

	ClientTickTraceJsonlStreamer(MinecraftTransport transport, ObjectMapper objectMapper) {
		this.transport = transport;
		this.objectMapper = objectMapper;
	}

	StreamResult stream(
		Path outputPath,
		Map<String, Object> startPayload,
		boolean once,
		Runnable onStarted
	) throws IOException, InterruptedException {
		String traceId = requiredString(startPayload, "traceId", "Trace start response is missing traceId");
		long startedClientTickId = requiredLong(
			startPayload,
			"startedClientTickId",
			"Trace start response is missing startedClientTickId"
		);
		Path normalizedOutputPath = outputPath.toAbsolutePath().normalize();
		Path parent = normalizedOutputPath.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}

		TraceRecordBuffer records = new TraceRecordBuffer(startedClientTickId);
		boolean completed = false;
		boolean truncated = false;
		int recordsSinceFlush = 0;
		try (
			BufferedWriter writer = new BufferedWriter(
				new OutputStreamWriter(
					Files.newOutputStream(
						normalizedOutputPath,
						StandardOpenOption.CREATE,
						StandardOpenOption.TRUNCATE_EXISTING,
						StandardOpenOption.WRITE
					),
					StandardCharsets.UTF_8
				),
				OUTPUT_BUFFER_BYTES
			)
		) {
			writeEvent(writer, traceStartEvent(traceId, startedClientTickId, once, startPayload));
			writer.flush();
			onStarted.run();

			while (true) {
				TracePoll poll = poll(traceId, records.lastWrittenClientTickId(), records);
				if (poll.truncated()) {
					truncated = true;
					writeEvent(writer, traceGapEvent(traceId, records.lastWrittenClientTickId(), poll.payload()));
					recordsSinceFlush++;
				}
				recordsSinceFlush += writeRecords(writer, records.drainCompleted());

				if (!poll.active()) {
					recordsSinceFlush += settleFinalFrames(traceId, records, writer);
					recordsSinceFlush += writeRecords(writer, records.drainAll());
					writeEvent(writer, traceEndEvent(traceId, once, records.writtenRecordCount(), truncated, poll.payload()));
					writer.flush();
					completed = true;
					return new StreamResult(normalizedOutputPath, traceId, records.writtenRecordCount(), truncated);
				}

				if (recordsSinceFlush >= FLUSH_RECORD_COUNT) {
					writer.flush();
					recordsSinceFlush = 0;
				}
				Thread.sleep(POLL_INTERVAL_MS);
			}
		}
		finally {
			if (!completed) {
				try {
					transport.post("/v1/agent/debug/trace/stop", Map.of("traceId", traceId));
				}
				catch (RuntimeException ignored) {
					// The original write or transport failure gives the useful error.
				}
			}
		}
	}

	private int settleFinalFrames(
		String traceId,
		TraceRecordBuffer records,
		BufferedWriter writer
	) throws IOException, InterruptedException {
		int writes = 0;
		for (int attempt = 0; attempt < FINAL_FRAME_SETTLE_POLLS && records.hasPendingFrame(); attempt++) {
			Thread.sleep(POLL_INTERVAL_MS);
			TracePoll poll = poll(traceId, records.lastWrittenClientTickId(), records);
			writes += writeRecords(writer, records.drainCompleted());
			if (poll.active()) {
				throw new BridgeUnavailableException("trace_stream_state", "A stopped client tick trace became active");
			}
		}
		return writes;
	}

	private TracePoll poll(String traceId, long sinceClientTickId, TraceRecordBuffer records) {
		long pageSinceClientTickId = sinceClientTickId;
		boolean active = false;
		boolean truncated = false;
		Map<String, Object> finalPayload = Map.of();
		while (true) {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("traceId", traceId);
			body.put("sinceClientTickId", pageSinceClientTickId);
			body.put("limit", RECORD_PAGE_LIMIT);
			body.put("includeImageBytes", true);
			Map<String, Object> payload = transport.post("/v1/agent/debug/trace/records", body);
			active = booleanValue(payload, "active");
			truncated |= booleanValue(payload, "truncated");
			finalPayload = payload;
			for (Map<String, Object> record : maps(payload.get("records"))) {
				records.accept(record);
			}
			if (booleanValue(payload, "complete")) {
				return new TracePoll(active, truncated, finalPayload);
			}
			Long nextSinceClientTickId = optionalLong(payload.get("nextSinceClientTickId"));
			if (nextSinceClientTickId == null || nextSinceClientTickId <= pageSinceClientTickId) {
				return new TracePoll(active, truncated, finalPayload);
			}
			pageSinceClientTickId = nextSinceClientTickId;
		}
	}

	private int writeRecords(BufferedWriter writer, List<Map<String, Object>> records) throws IOException {
		for (Map<String, Object> record : records) {
			LinkedHashMap<String, Object> event = new LinkedHashMap<>();
			event.put("event", "trace_record");
			event.put("record", record);
			writeEvent(writer, event);
		}
		return records.size();
	}

	private void writeEvent(BufferedWriter writer, Map<String, Object> event) throws IOException {
		writer.write(objectMapper.writeValueAsString(event));
		writer.newLine();
	}

	private static Map<String, Object> traceStartEvent(
		String traceId,
		long startedClientTickId,
		boolean once,
		Map<String, Object> startPayload
	) {
		LinkedHashMap<String, Object> event = new LinkedHashMap<>();
		event.put("event", "trace_start");
		event.put("schemaVersion", 1);
		event.put("traceId", traceId);
		event.put("startedClientTickId", startedClientTickId);
		event.put("once", once);
		event.put("trace", startPayload);
		return event;
	}

	private static Map<String, Object> traceGapEvent(
		String traceId,
		long afterClientTickId,
		Map<String, Object> page
	) {
		LinkedHashMap<String, Object> event = new LinkedHashMap<>();
		event.put("event", "trace_gap");
		event.put("traceId", traceId);
		event.put("afterClientTickId", afterClientTickId);
		event.put("oldestClientTickId", page.get("oldestClientTickId"));
		event.put("latestClientTickId", page.get("latestClientTickId"));
		return event;
	}

	private static Map<String, Object> traceEndEvent(
		String traceId,
		boolean once,
		int writtenRecordCount,
		boolean truncated,
		Map<String, Object> page
	) {
		LinkedHashMap<String, Object> event = new LinkedHashMap<>();
		event.put("event", "trace_end");
		event.put("traceId", traceId);
		event.put("once", once);
		event.put("active", booleanValue(page, "active"));
		event.put("recordsWritten", writtenRecordCount);
		event.put("truncated", truncated);
		event.put("oldestClientTickId", page.get("oldestClientTickId"));
		event.put("latestClientTickId", page.get("latestClientTickId"));
		return event;
	}

	private static String requiredString(Map<String, Object> payload, String key, String message) {
		Object value = payload.get(key);
		if (value instanceof String text && !text.isBlank()) {
			return text;
		}
		throw new BridgeUnavailableException("bridge_io_error", message);
	}

	private static long requiredLong(Map<String, Object> payload, String key, String message) {
		Long value = optionalLong(payload.get(key));
		if (value != null) {
			return value;
		}
		throw new BridgeUnavailableException("bridge_io_error", message);
	}

	private static Long optionalLong(Object value) {
		if (value instanceof Number number) {
			return number.longValue();
		}
		return null;
	}

	private static boolean booleanValue(Map<String, Object> payload, String key) {
		return Boolean.TRUE.equals(payload.get(key));
	}

	private static List<Map<String, Object>> maps(Object value) {
		if (!(value instanceof List<?> values)) {
			return List.of();
		}
		List<Map<String, Object>> maps = new ArrayList<>();
		for (Object entry : values) {
			if (!(entry instanceof Map<?, ?> raw)) {
				throw new BridgeUnavailableException("bridge_io_error", "Bridge returned an invalid trace record");
			}
			LinkedHashMap<String, Object> map = new LinkedHashMap<>();
			for (Map.Entry<?, ?> item : raw.entrySet()) {
				if (item.getKey() instanceof String key) {
					map.put(key, item.getValue());
				}
			}
			maps.add(map);
		}
		return maps;
	}

	static final class TraceRecordBuffer {
		private final NavigableMap<Long, Map<String, Object>> records = new TreeMap<>();
		private long lastWrittenClientTickId;
		private int writtenRecordCount;

		TraceRecordBuffer(long initialSinceClientTickId) {
			lastWrittenClientTickId = initialSinceClientTickId;
		}

		void accept(Map<String, Object> record) {
			Long clientTickId = optionalLong(record.get("clientTickId"));
			if (clientTickId == null) {
				throw new BridgeUnavailableException("bridge_io_error", "Bridge returned a trace record without clientTickId");
			}
			if (clientTickId > lastWrittenClientTickId) {
				records.put(clientTickId, record);
			}
		}

		List<Map<String, Object>> drainCompleted() {
			return drain(false);
		}

		List<Map<String, Object>> drainAll() {
			return drain(true);
		}

		boolean hasPendingFrame() {
			return records.values().stream().anyMatch(ClientTickTraceJsonlStreamer::hasPendingFrame);
		}

		long lastWrittenClientTickId() {
			return lastWrittenClientTickId;
		}

		int writtenRecordCount() {
			return writtenRecordCount;
		}

		private List<Map<String, Object>> drain(boolean includePendingFrames) {
			List<Map<String, Object>> drained = new ArrayList<>();
			while (!records.isEmpty()) {
				Map.Entry<Long, Map<String, Object>> first = records.firstEntry();
				if (!includePendingFrames && ClientTickTraceJsonlStreamer.hasPendingFrame(first.getValue())) {
					break;
				}
				drained.add(first.getValue());
				records.pollFirstEntry();
				lastWrittenClientTickId = first.getKey();
				writtenRecordCount++;
			}
			return List.copyOf(drained);
		}
	}

	private static boolean hasPendingFrame(Map<String, Object> record) {
		Object value = record.get("frame");
		if (!(value instanceof Map<?, ?> frame)) {
			return false;
		}
		return "PENDING".equals(frame.get("status"));
	}

	record StreamResult(Path outputPath, String traceId, int writtenRecordCount, boolean truncated) {
	}

	private record TracePoll(boolean active, boolean truncated, Map<String, Object> payload) {
	}
}
