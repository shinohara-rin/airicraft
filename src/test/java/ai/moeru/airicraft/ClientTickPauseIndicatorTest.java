package ai.moeru.airicraft;

import ai.moeru.airicraft.debug.ClientTickDebugController;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientTickPauseIndicatorTest {
	@Test
	void showsOnlyAfterTheTickDebuggerIsPaused() {
		for (ClientTickDebugController.Phase phase : ClientTickDebugController.Phase.values()) {
			ClientTickDebugController.DebugStatus status = new ClientTickDebugController.DebugStatus(
				phase,
				"debug-session",
				1L,
				2L,
				"snapshot",
				"CAPTURED"
			);

			if (phase == ClientTickDebugController.Phase.PAUSED) {
				assertTrue(ClientTickPauseIndicator.isVisible(status));
			}
			else {
				assertFalse(ClientTickPauseIndicator.isVisible(status));
			}
		}
	}

	@Test
	void pinsTheIndicatorToTheTopRightCorner() {
		ClientTickPauseIndicator.IndicatorBounds bounds = ClientTickPauseIndicator.layout(320, 36, 9);

		assertEquals(270, bounds.left());
		assertEquals(6, bounds.top());
		assertEquals(314, bounds.right());
		assertEquals(23, bounds.bottom());
	}
}
