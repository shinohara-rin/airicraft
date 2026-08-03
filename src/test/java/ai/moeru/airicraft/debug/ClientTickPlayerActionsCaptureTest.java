package ai.moeru.airicraft.debug;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientTickPlayerActionsCaptureTest {
	@AfterEach
	void clearPlayerActionEvents() {
		ClientTickPlayerActionEvents.clear();
	}

	@Test
	void capturesAnInteractionStartWithoutAHeldKey() {
		ClientTickPlayerActionEvents.recordStart("attack");

		ClientTickPlayerActionsSnapshot snapshot = new ClientTickPlayerActionsCapture().capture(null, "capture-1");

		assertTrue(snapshot.actions().stream().anyMatch(action -> action.action().equals("attack") && action.started() && !action.pressed()));
	}

	@Test
	void clearsInteractionStartsAfterCapture() {
		ClientTickPlayerActionEvents.recordStart("use");
		ClientTickPlayerActionsCapture capture = new ClientTickPlayerActionsCapture();
		capture.capture(null, "capture-1");

		ClientTickPlayerActionsSnapshot next = capture.capture(null, "capture-1");

		assertFalse(next.actions().stream().anyMatch(action -> action.action().equals("use")));
	}

	@Test
	void capturesBreakProgressRecordedAtTheInteractionBoundary() {
		ClientTickPlayerActionsCapture capture = new ClientTickPlayerActionsCapture();
		capture.capture(null, "capture-1");
		ClientTickPlayerActionEvents.recordStart("attack");
		ClientTickPlayerActionEvents.recordBreakProgress(4, 70, -2, 0.6F);

		ClientTickPlayerActionsSnapshot snapshot = capture.capture(null, "capture-1");

		ClientTickPlayerActionsSnapshot.BreakProgress progress = snapshot.breakProgress();
		assertNotNull(progress);
		assertEquals(new ClientTickPlayerActionsSnapshot.Position(4, 70, -2), progress.position());
		assertEquals(0.6F, progress.progress());
		assertEquals(6, progress.stage());
		assertTrue(progress.started());
		assertTrue(snapshot.actions().stream().anyMatch(action -> action.action().equals("attack") && action.started()));
	}
}
