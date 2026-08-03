package ai.moeru.airicraft.debug;

import ai.moeru.airicraft.BridgeUnavailableException;
import ai.moeru.airicraft.FirstPersonScreenshotService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientTickTraceRecorderTest {
	@Test
	void rollingWindowRetainsTheRequestedClientTickSpan() {
		ClientTickTraceRecorder recorder = new ClientTickTraceRecorder();
		var status = recorder.start(config(Set.of(ClientTickTraceRecorder.TraceInfo.METADATA), 3), 10L);

		recorder.record(record(status.traceId(), 11L));
		recorder.record(record(status.traceId(), 12L));
		recorder.record(record(status.traceId(), 13L));
		recorder.record(record(status.traceId(), 14L));

		var current = recorder.status();
		assertEquals(12L, current.oldestClientTickId());
		assertEquals(14L, current.latestClientTickId());
		assertEquals(3, current.recordCount());
	}

	@Test
	void recordPagesReportEvictionAndContinueByClientTickId() {
		ClientTickTraceRecorder recorder = new ClientTickTraceRecorder();
		var status = recorder.start(config(Set.of(ClientTickTraceRecorder.TraceInfo.METADATA), 3), 10L);
		for (long tick = 11L; tick <= 14L; tick++) {
			recorder.record(record(status.traceId(), tick));
		}

		var first = recorder.records(status.traceId(), 10L, 2);
		var second = recorder.records(status.traceId(), first.nextSinceClientTickId(), 2);

		assertTrue(first.truncated());
		assertFalse(first.complete());
		assertEquals(List.of(12L, 13L), first.records().stream().map(ClientTickTraceRecorder.TraceTickRecord::clientTickId).toList());
		assertEquals(13L, first.nextSinceClientTickId());
		assertEquals(List.of(14L), second.records().stream().map(ClientTickTraceRecorder.TraceTickRecord::clientTickId).toList());
		assertTrue(second.complete());
	}

	@Test
	void stopKeepsRecordsAndRequiresCurrentTraceIdentity() {
		ClientTickTraceRecorder recorder = new ClientTickTraceRecorder();
		var status = recorder.start(config(Set.of(ClientTickTraceRecorder.TraceInfo.PLAYER_STATE), 10), 4L);
		recorder.record(record(status.traceId(), 5L));

		assertThrows(BridgeUnavailableException.class, () -> recorder.stop("stale"));
		var stopped = recorder.stop(status.traceId());

		assertFalse(stopped.active());
		assertEquals(1, recorder.records(status.traceId(), null, 10).records().size());
	}

	@Test
	void onceTraceStopsAfterTheRequestedRecordCountAndSettlesTheLastFrame() {
		ClientTickTraceRecorder recorder = new ClientTickTraceRecorder();
		var status = recorder.start(new ClientTickTraceRecorder.TraceConfig(
			Set.of(ClientTickTraceRecorder.TraceInfo.FRAME),
			2,
			true,
			null,
			null
		), 10L);

		recorder.record(new ClientTickTraceRecorder.TraceTickRecord(
			status.traceId(), 11L, 1_011L, null, null, null, null, null,
			ClientTickTraceRecorder.TraceFrame.pending(1_011L), Map.of()
		));
		recorder.record(new ClientTickTraceRecorder.TraceTickRecord(
			status.traceId(), 12L, 1_012L, null, null, null, null, null,
			ClientTickTraceRecorder.TraceFrame.pending(1_012L), Map.of()
		));

		assertFalse(recorder.status().active());
		assertTrue(recorder.status().once());
		assertTrue(recorder.waitingForFrame());

		recorder.completeFrame(
			status.traceId(),
			12L,
			ClientTickTraceRecorder.TraceFrame.captured("png", 1, 1, 1, 1, 1_013L, new byte[]{1})
		);
		recorder.record(record(status.traceId(), 13L));

		assertFalse(recorder.waitingForFrame());
		assertEquals(
			List.of(11L, 12L),
			recorder.records(status.traceId(), null, 10).records().stream()
				.map(ClientTickTraceRecorder.TraceTickRecord::clientTickId)
				.toList()
		);
	}

	@Test
	void staleFrameCompletionCannotModifyANewTrace() {
		ClientTickTraceRecorder recorder = new ClientTickTraceRecorder();
		var first = recorder.start(config(Set.of(ClientTickTraceRecorder.TraceInfo.FRAME), 10), 0L);
		recorder.record(record(first.traceId(), 1L));
		recorder.stop(first.traceId());
		var second = recorder.start(config(Set.of(ClientTickTraceRecorder.TraceInfo.METADATA), 10), 1L);
		recorder.record(record(second.traceId(), 2L));

		recorder.completeFrame(
			first.traceId(),
			1L,
			ClientTickTraceRecorder.TraceFrame.captured("png", 1, 1, 1, 1, 2L, new byte[]{1})
		);

		assertEquals(1, recorder.records(second.traceId(), null, 10).records().size());
		assertEquals(2L, recorder.records(second.traceId(), null, 10).records().getFirst().clientTickId());
	}

	@Test
	void frameTraceWaitsForTheSelectedTicksFrame() {
		ClientTickTraceRecorder recorder = new ClientTickTraceRecorder();
		var status = recorder.start(config(Set.of(ClientTickTraceRecorder.TraceInfo.FRAME), 10), 0L);
		recorder.record(new ClientTickTraceRecorder.TraceTickRecord(
			status.traceId(),
			1L,
			1_001L,
				null,
				null,
				null,
				null,
				null,
				ClientTickTraceRecorder.TraceFrame.pending(1_001L),
			Map.of()
		));

		assertTrue(recorder.waitingForFrame());

		recorder.completeFrame(
			status.traceId(),
			1L,
			ClientTickTraceRecorder.TraceFrame.captured("png", 1, 1, 1, 1, 1_002L, new byte[]{1})
		);

		assertFalse(recorder.waitingForFrame());
	}

	@Test
	void runtimeBlocksTheNextClientTickUntilTheTraceFrameCompletes() {
		ClientTickTraceRecorder recorder = new ClientTickTraceRecorder();
		var status = recorder.start(config(Set.of(ClientTickTraceRecorder.TraceInfo.FRAME), 10), 0L);
		recorder.record(new ClientTickTraceRecorder.TraceTickRecord(
			status.traceId(), 1L, 1_001L, null, null, null, null, null,
			ClientTickTraceRecorder.TraceFrame.pending(1_001L), Map.of()
		));
		ClientTickDebugRuntime runtime = new ClientTickDebugRuntime(
			new ClientTickDebugController(),
			new FirstPersonScreenshotService(),
			recorder
		);

		assertFalse(runtime.allowVanillaTick(true));

		recorder.completeFrame(
			status.traceId(),
			1L,
			ClientTickTraceRecorder.TraceFrame.captured("png", 1, 1, 1, 1, 1_002L, new byte[]{1})
		);

		assertTrue(runtime.allowVanillaTick(true));
	}

	@Test
	void frameTraceUsesASmallerBoundedWindow() {
		assertThrows(
			BridgeUnavailableException.class,
			() -> config(Set.of(ClientTickTraceRecorder.TraceInfo.FRAME), 201)
		);
	}

	@Test
	void playerCenteredEntityRadiusResolvesAtEachTick() {
		var spec = new ClientTickTraceRecorder.EntityQuerySpec(
			null,
			null,
			null,
			null,
			16.0D,
			null,
			null,
			null,
			Set.of("minecraft:zombie"),
			true,
			true,
			false,
			false,
			12
		);

		var query = spec.resolve(new ClientTickPlayerSnapshot.PositionSnapshot(10.0D, 64.0D, -4.0D, 10, 64, -4));

		assertEquals(10.0D, query.radius().x());
		assertEquals(64.0D, query.radius().y());
		assertEquals(-4.0D, query.radius().z());
		assertEquals(16.0D, query.radius().radius());
		assertEquals(Set.of("minecraft:zombie"), query.entityTypeIds());
		assertEquals(12, query.limit());
	}

	@Test
	void recordsRequestedPlayerActionsAndBreakProgress() {
		ClientTickTraceRecorder recorder = new ClientTickTraceRecorder();
		var status = recorder.start(config(Set.of(ClientTickTraceRecorder.TraceInfo.PLAYER_ACTIONS), 10), 0L);
		var actions = new ClientTickPlayerActionsSnapshot(
			List.of(new ClientTickPlayerActionsSnapshot.ActionState("attack", true, true)),
			new ClientTickPlayerActionsSnapshot.BreakProgress(
				new ClientTickPlayerActionsSnapshot.Position(3, 64, -2),
				0.6F,
				6,
				true
			)
		);
		recorder.record(new ClientTickTraceRecorder.TraceTickRecord(
			status.traceId(), 1L, 1_001L, null, null, actions, null, null, null, Map.of()
		));

		var record = recorder.records(status.traceId(), null, 10).records().getFirst();
		assertTrue(record.playerActions().actions().getFirst().started());
		assertEquals(6, record.playerActions().breakProgress().stage());
	}

	@Test
	void blocksInfoRequiresABoundedRegion() {
		assertThrows(
			BridgeUnavailableException.class,
			() -> config(Set.of(ClientTickTraceRecorder.TraceInfo.BLOCKS), 10)
		);
		assertThrows(
			BridgeUnavailableException.class,
			() -> new ClientTickTraceRecorder.TraceConfig(
				Set.of(ClientTickTraceRecorder.TraceInfo.BLOCKS),
				10,
				false,
				null,
				new ClientTickWorldQueryService.RegionBounds(0, 0, 0, 16, 16, 16)
			)
		);
		assertThrows(
			BridgeUnavailableException.class,
			() -> new ClientTickTraceRecorder.TraceConfig(
				Set.of(ClientTickTraceRecorder.TraceInfo.BLOCKS),
				100,
				false,
				null,
				new ClientTickWorldQueryService.RegionBounds(0, 0, 0, 15, 15, 15)
			)
		);
	}

	private static ClientTickTraceRecorder.TraceConfig config(
		Set<ClientTickTraceRecorder.TraceInfo> infos,
		int windowTicks
	) {
		return new ClientTickTraceRecorder.TraceConfig(infos, windowTicks, false, null, null);
	}

	private static ClientTickTraceRecorder.TraceTickRecord record(String traceId, long clientTickId) {
		return new ClientTickTraceRecorder.TraceTickRecord(
			traceId,
			clientTickId,
			1_000L + clientTickId,
			new ClientTickTraceRecorder.TraceMetadata(2, "minecraft:overworld", 100L, 100L, 4L, "IDLE"),
			null,
			null,
			null,
			null,
			null,
			Map.of()
		);
	}
}
