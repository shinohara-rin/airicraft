package ai.moeru.airicraft.debug;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientTickDebugControllerTest {
	@Test
	void pauseCapturesCurrentRenderedFrameWithoutAdvancingATick() {
		ClientTickDebugController controller = new ClientTickDebugController();
		var future = controller.pause();

		assertFalse(controller.allowVanillaTick(true));
		var intent = controller.onRenderedFrameBoundary().orElseThrow();
		controller.attachSnapshot(intent, snapshot(intent));
		controller.completeFrame(intent, frame());

		var capture = future.join();
		assertEquals(0L, capture.snapshot().clientTickId());
		assertEquals(1L, capture.pauseEpoch());
		assertEquals("CAPTURED", capture.frame().status());
		assertTrue(controller.status().paused());
	}

	@Test
	void stepAllowsOneClientTickThenWaitsForItsRenderedFrame() {
		ClientTickDebugController controller = pausedController();
		var initial = controller.status();
		var future = controller.step(initial.debugSessionId(), initial.pauseEpoch());

		assertTrue(controller.allowVanillaTick(true));
		controller.onClientTickStarted();
		assertTrue(controller.allowVanillaTick(true));
		var intent = controller.onClientTickCompleted().orElseThrow();
		assertFalse(controller.allowVanillaTick(true));
		controller.attachSnapshot(intent, snapshot(intent));
		controller.completeFrame(intent, frame());

		var capture = future.join();
		assertEquals(1L, capture.snapshot().clientTickId());
		assertEquals(2L, capture.pauseEpoch());
		assertFalse(controller.allowVanillaTick(true));
	}

	@Test
	void pauseUsesAnAlreadyScheduledClientTickAsTheCapturedBoundary() {
		ClientTickDebugController controller = new ClientTickDebugController();
		var future = controller.pause();

		controller.onClientTickStarted();
		assertTrue(controller.allowVanillaTick(true));
		var intent = controller.onClientTickCompleted().orElseThrow();
		controller.attachSnapshot(intent, snapshot(intent));
		controller.completeFrame(intent, frame());

		assertEquals(1L, future.join().snapshot().clientTickId());
	}

	@Test
	void staleSessionAndEpochCannotAdvanceTheClient() {
		ClientTickDebugController controller = pausedController();
		var status = controller.status();

		var staleSession = assertThrows(
			ClientTickDebugController.DebugStateException.class,
			() -> controller.step("stale", status.pauseEpoch())
		);
		assertEquals("stale_debug_session", staleSession.code());

		var staleEpoch = assertThrows(
			ClientTickDebugController.DebugStateException.class,
			() -> controller.step(status.debugSessionId(), status.pauseEpoch() - 1L)
		);
		assertEquals("stale_pause_epoch", staleEpoch.code());
	}

	@Test
	void advancingInvalidatesThePreviousWorldSnapshotHandle() {
		ClientTickDebugController controller = pausedController();
		var status = controller.status();
		controller.requireCurrentSnapshot(status.snapshotId());

		controller.step(status.debugSessionId(), status.pauseEpoch());

		var exception = assertThrows(
			ClientTickDebugController.DebugStateException.class,
			() -> controller.requireCurrentSnapshot(status.snapshotId())
		);
		assertEquals("snapshot_not_paused", exception.code());
	}

	@Test
	void continueRequiresCurrentAuthorityAndReturnsToNormalTicks() {
		ClientTickDebugController controller = pausedController();
		var status = controller.status();

		controller.continueRunning(status.debugSessionId(), status.pauseEpoch());

		assertEquals(ClientTickDebugController.Phase.RUNNING, controller.status().phase());
		assertTrue(controller.allowVanillaTick(true));
	}

	private static ClientTickDebugController pausedController() {
		ClientTickDebugController controller = new ClientTickDebugController();
		var future = controller.pause();
		var intent = controller.onRenderedFrameBoundary().orElseThrow();
		controller.attachSnapshot(intent, snapshot(intent));
		controller.completeFrame(intent, frame());
		future.join();
		return controller;
	}

	private static ClientTickDebugController.ClientTickSnapshot snapshot(
		ClientTickDebugController.CaptureIntent intent
	) {
		return new ClientTickDebugController.ClientTickSnapshot(
			1,
			intent.debugSessionId(),
			intent.captureId(),
			intent.snapshotId(),
			intent.clientTickId(),
			100L,
			"minecraft:overworld",
			200L,
			300L,
			new ClientTickDebugController.PlayerSnapshot(1.5D, 64.0D, 2.5D, 1, 64, 2, 10.0F, 20.0F, 20.0F, 20, 300),
			4L,
			"IDLE"
		);
	}

	private static ClientTickDebugController.ClientTickFrame frame() {
		return ClientTickDebugController.ClientTickFrame.captured("png", 854, 480, 1920, 1080, 200L, new byte[]{1, 2, 3});
	}
}
