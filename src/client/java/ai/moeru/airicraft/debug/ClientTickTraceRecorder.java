package ai.moeru.airicraft.debug;

import ai.moeru.airicraft.BridgeUnavailableException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class ClientTickTraceRecorder {
	public static final int MAX_WINDOW_TICKS = 1_200;
	public static final int MAX_FRAME_WINDOW_TICKS = 200;
	public static final int DEFAULT_RECORD_LIMIT = 32;
	public static final int MAX_RECORD_LIMIT = 256;
	public static final long MAX_RETAINED_ENTITY_OBSERVATIONS = 100_000L;
	public static final long MAX_RETAINED_BLOCK_OBSERVATIONS = 250_000L;

	private final LinkedHashMap<Long, TraceTickRecord> records = new LinkedHashMap<>();

	private boolean active;
	private String traceId;
	private TraceConfig config;
	private long startedClientTickId;

	public synchronized TraceStatus start(TraceConfig nextConfig, long currentClientTickId) {
		if (active) {
			throw new BridgeUnavailableException("trace_active", "A client tick trace is already active");
		}
		if (nextConfig == null) {
			throw new BridgeUnavailableException("invalid_request", "Missing client tick trace configuration");
		}
		config = nextConfig;
		traceId = UUID.randomUUID().toString();
		startedClientTickId = currentClientTickId;
		records.clear();
		active = true;
		return status();
	}

	public synchronized TraceStatus stop(String requestedTraceId) {
		requireTrace(requestedTraceId);
		if (!active) {
			throw new BridgeUnavailableException("trace_not_active", "The client tick trace is not active");
		}
		active = false;
		return status();
	}

	public synchronized Optional<ActiveTrace> activeTrace() {
		if (!active || traceId == null || config == null) {
			return Optional.empty();
		}
		return Optional.of(new ActiveTrace(traceId, config));
	}

	public synchronized boolean waitingForFrame() {
		if (config == null || !config.infos().contains(TraceInfo.FRAME) || records.isEmpty()) {
			return false;
		}
		TraceFrame frame = records.get(lastKey()).frame();
		return frame != null && "PENDING".equals(frame.status());
	}

	public synchronized void record(TraceTickRecord record) {
		if (!active || record == null || !record.traceId().equals(traceId)) {
			return;
		}
		records.put(record.clientTickId(), record);
		long oldestRetainedTick = record.clientTickId() - config.windowTicks() + 1L;
		Iterator<Long> iterator = records.keySet().iterator();
		while (iterator.hasNext()) {
			if (iterator.next() >= oldestRetainedTick) {
				break;
			}
			iterator.remove();
		}
		if (config.once() && records.size() >= config.windowTicks()) {
			active = false;
		}
	}

	public synchronized void completeFrame(String requestedTraceId, long clientTickId, TraceFrame frame) {
		if (traceId == null || !traceId.equals(requestedTraceId)) {
			return;
		}
		TraceTickRecord current = records.get(clientTickId);
		if (current != null) {
			records.put(clientTickId, current.withFrame(frame));
		}
	}

	public synchronized TraceStatus status() {
		Long oldestClientTickId = records.isEmpty() ? null : records.keySet().iterator().next();
		Long latestClientTickId = records.isEmpty() ? null : lastKey();
		return new TraceStatus(
			active,
			traceId,
			config == null ? Set.of() : config.infos(),
			config == null ? 0 : config.windowTicks(),
			config != null && config.once(),
			traceId == null ? null : startedClientTickId,
			oldestClientTickId,
			latestClientTickId,
			records.size()
		);
	}

	public synchronized TraceRecordPage records(
		String requestedTraceId,
		Long sinceClientTickId,
		int limit
	) {
		requireTrace(requestedTraceId);
		if (limit < 1 || limit > MAX_RECORD_LIMIT) {
			throw new BridgeUnavailableException("invalid_request", "limit must be between 1 and " + MAX_RECORD_LIMIT);
		}
		Long oldestClientTickId = records.isEmpty() ? null : records.keySet().iterator().next();
		Long latestClientTickId = records.isEmpty() ? null : lastKey();
		boolean truncated = sinceClientTickId != null
			&& oldestClientTickId != null
			&& sinceClientTickId < oldestClientTickId - 1L;
		List<TraceTickRecord> page = new ArrayList<>();
		boolean hasMore = false;
		for (TraceTickRecord record : records.values()) {
			if (sinceClientTickId != null && record.clientTickId() <= sinceClientTickId) {
				continue;
			}
			if (page.size() == limit) {
				hasMore = true;
				break;
			}
			page.add(record);
		}
		Long nextSinceClientTickId = page.isEmpty()
			? sinceClientTickId
			: page.get(page.size() - 1).clientTickId();
		return new TraceRecordPage(
			traceId,
			active,
			oldestClientTickId,
			latestClientTickId,
			truncated,
			!hasMore,
			nextSinceClientTickId,
			List.copyOf(page)
		);
	}

	public synchronized void reset() {
		active = false;
		traceId = null;
		config = null;
		startedClientTickId = 0L;
		records.clear();
	}

	private void requireTrace(String requestedTraceId) {
		if (traceId == null) {
			throw new BridgeUnavailableException("trace_not_found", "No client tick trace is available");
		}
		if (requestedTraceId == null || !traceId.equals(requestedTraceId)) {
			throw new BridgeUnavailableException("stale_trace", "The client tick trace identifier is stale");
		}
	}

	private Long lastKey() {
		Long latest = null;
		for (Long clientTickId : records.keySet()) {
			latest = clientTickId;
		}
		return latest;
	}

	public enum TraceInfo {
		METADATA("metadata"),
		PLAYER_STATE("player_state"),
		ENTITIES("entities"),
		BLOCKS("blocks"),
		FRAME("frame");

		private final String wireName;

		TraceInfo(String wireName) {
			this.wireName = wireName;
		}

		public String wireName() {
			return wireName;
		}

		public static TraceInfo parse(String value) {
			String normalized = value == null
				? ""
				: value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
			for (TraceInfo info : values()) {
				if (info.wireName.equals(normalized)) {
					return info;
				}
			}
			throw new BridgeUnavailableException("invalid_request", "Unsupported trace info: " + value);
		}
	}

	public record TraceConfig(
		Set<TraceInfo> infos,
		int windowTicks,
		boolean once,
		EntityQuerySpec entityQuery,
		ClientTickWorldQueryService.RegionBounds blockRegion
	) {
		public TraceConfig {
			if (infos == null || infos.isEmpty()) {
				throw new BridgeUnavailableException("invalid_request", "infos must contain at least one trace info name");
			}
			infos = Collections.unmodifiableSet(EnumSet.copyOf(infos));
			if (windowTicks < 1 || windowTicks > MAX_WINDOW_TICKS) {
				throw new BridgeUnavailableException("invalid_request", "windowTicks must be between 1 and " + MAX_WINDOW_TICKS);
			}
			if (infos.contains(TraceInfo.FRAME) && windowTicks > MAX_FRAME_WINDOW_TICKS) {
				throw new BridgeUnavailableException(
					"invalid_request",
					"windowTicks must not exceed " + MAX_FRAME_WINDOW_TICKS + " when frame capture is enabled"
				);
			}
			if (infos.contains(TraceInfo.ENTITIES)) {
				entityQuery = entityQuery == null ? EntityQuerySpec.all() : entityQuery;
				if ((long) windowTicks * entityQuery.limit() > MAX_RETAINED_ENTITY_OBSERVATIONS) {
					throw new BridgeUnavailableException(
						"trace_too_large",
						"windowTicks times the entity limit must not exceed " + MAX_RETAINED_ENTITY_OBSERVATIONS
					);
				}
			}
			else if (entityQuery != null) {
				throw new BridgeUnavailableException("invalid_request", "entityQuery requires the entities trace info");
			}
			if (infos.contains(TraceInfo.BLOCKS)) {
				if (blockRegion == null) {
					throw new BridgeUnavailableException("invalid_request", "blockQuery is required for the blocks trace info");
				}
				if (blockRegion.totalCellCount() > ClientTickWorldQueryService.MAX_PAGE_LIMIT) {
					throw new BridgeUnavailableException(
						"region_too_large",
						"A trace block region must contain at most " + ClientTickWorldQueryService.MAX_PAGE_LIMIT + " blocks"
					);
				}
				if (blockRegion.totalCellCount() * windowTicks > MAX_RETAINED_BLOCK_OBSERVATIONS) {
					throw new BridgeUnavailableException(
						"trace_too_large",
						"windowTicks times the block count must not exceed " + MAX_RETAINED_BLOCK_OBSERVATIONS
					);
				}
			}
			else if (blockRegion != null) {
				throw new BridgeUnavailableException("invalid_request", "blockQuery requires the blocks trace info");
			}
		}
	}

	public record EntityQuerySpec(
		ClientTickWorldQueryService.RegionBounds region,
		Double centerX,
		Double centerY,
		Double centerZ,
		Double radius,
		Integer entityId,
		String uuid,
		String name,
		Set<String> entityTypeIds,
		Boolean alive,
		boolean livingOnly,
		boolean playerOnly,
		boolean includeSelf,
		int limit
	) {
		public EntityQuerySpec {
			entityTypeIds = entityTypeIds == null
				? Set.of()
				: Collections.unmodifiableSet(new LinkedHashSet<>(entityTypeIds));
			int centerCount = countNonNull(centerX, centerY, centerZ);
			if (centerCount != 0 && centerCount != 3) {
				throw new BridgeUnavailableException("invalid_request", "Provide all three entity radius center coordinates");
			}
			if (centerCount > 0 && radius == null) {
				throw new BridgeUnavailableException("invalid_request", "Provide entity radius with center coordinates");
			}
			if (region != null && radius != null) {
				throw new BridgeUnavailableException("invalid_request", "Use either an entity region or radius, not both");
			}
			if (limit < 1 || limit > ClientTickEntityQueryService.MAX_PAGE_LIMIT) {
				throw new BridgeUnavailableException(
					"invalid_request",
					"entity query limit must be between 1 and " + ClientTickEntityQueryService.MAX_PAGE_LIMIT
				);
			}
			if (radius != null) {
				double validationX = centerX == null ? 0.0D : centerX;
				double validationY = centerY == null ? 0.0D : centerY;
				double validationZ = centerZ == null ? 0.0D : centerZ;
				new ClientTickEntityQueryService.RadiusBounds(validationX, validationY, validationZ, radius);
			}
		}

		public static EntityQuerySpec all() {
			return new EntityQuerySpec(
				null, null, null, null, null, null, null, null, Set.of(), null,
				false, false, false, ClientTickEntityQueryService.DEFAULT_PAGE_LIMIT
			);
		}

		public ClientTickEntityQueryService.EntityQuery resolve(
			ClientTickPlayerSnapshot.PositionSnapshot playerPosition
		) {
			ClientTickEntityQueryService.RadiusBounds resolvedRadius = null;
			if (radius != null) {
				resolvedRadius = new ClientTickEntityQueryService.RadiusBounds(
					centerX == null ? playerPosition.x() : centerX,
					centerY == null ? playerPosition.y() : centerY,
					centerZ == null ? playerPosition.z() : centerZ,
					radius
				);
			}
			return new ClientTickEntityQueryService.EntityQuery(
				region,
				resolvedRadius,
				entityId,
				uuid,
				name,
				entityTypeIds,
				alive,
				livingOnly,
				playerOnly,
				includeSelf,
				0L,
				limit
			);
		}
	}

	public record ActiveTrace(String traceId, TraceConfig config) {
	}

	public record TraceMetadata(
		int schemaVersion,
		String dimensionId,
		long worldTime,
		long timeOfDay,
		long plannerGeneration,
		String plannerPhase
	) {
	}

	public record TraceFrame(
		String status,
		String format,
		int width,
		int height,
		int sourceWidth,
		int sourceHeight,
		long capturedAtMs,
		byte[] imageBytes,
		String errorCode,
		String message
	) {
		public TraceFrame {
			imageBytes = imageBytes == null ? new byte[0] : imageBytes.clone();
		}

		@Override
		public byte[] imageBytes() {
			return imageBytes.clone();
		}

		public static TraceFrame pending(long capturedAtMs) {
			return new TraceFrame("PENDING", null, 0, 0, 0, 0, capturedAtMs, new byte[0], null, null);
		}

		public static TraceFrame captured(
			String format,
			int width,
			int height,
			int sourceWidth,
			int sourceHeight,
			long capturedAtMs,
			byte[] imageBytes
		) {
			return new TraceFrame(
				"CAPTURED", format, width, height, sourceWidth, sourceHeight,
				capturedAtMs, imageBytes, null, null
			);
		}

		public static TraceFrame failed(String code, String message, long capturedAtMs) {
			return new TraceFrame("FAILED", null, 0, 0, 0, 0, capturedAtMs, new byte[0], code, message);
		}
	}

	public record TraceError(String code, String message) {
	}

	public record TraceTickRecord(
		String traceId,
		long clientTickId,
		long capturedAtMs,
		TraceMetadata metadata,
		ClientTickPlayerSnapshot playerState,
		ClientTickEntityQueryService.EntityQueryResult entities,
		Map<String, Object> blocks,
		TraceFrame frame,
		Map<String, TraceError> errors
	) {
		public TraceTickRecord {
			blocks = blocks == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(blocks));
			errors = errors == null
				? Map.of()
				: Collections.unmodifiableMap(new LinkedHashMap<>(errors));
		}

		TraceTickRecord withFrame(TraceFrame value) {
			return new TraceTickRecord(
				traceId, clientTickId, capturedAtMs, metadata, playerState, entities, blocks, value, errors
			);
		}
	}

	public record TraceStatus(
		boolean active,
		String traceId,
		Set<TraceInfo> infos,
		int windowTicks,
		boolean once,
		Long startedClientTickId,
		Long oldestClientTickId,
		Long latestClientTickId,
		int recordCount
	) {
	}

	public record TraceRecordPage(
		String traceId,
		boolean active,
		Long oldestClientTickId,
		Long latestClientTickId,
		boolean truncated,
		boolean complete,
		Long nextSinceClientTickId,
		List<TraceTickRecord> records
	) {
	}

	private static int countNonNull(Object... values) {
		int count = 0;
		for (Object value : values) {
			if (value != null) {
				count++;
			}
		}
		return count;
	}
}
