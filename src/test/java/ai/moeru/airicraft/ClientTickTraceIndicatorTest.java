package ai.moeru.airicraft;

import ai.moeru.airicraft.debug.ClientTickTraceRecorder;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientTickTraceIndicatorTest {
	@Test
	void showsOnlyWhileATraceIsActive() {
		assertTrue(ClientTickTraceIndicator.isVisible(status(true)));
		assertFalse(ClientTickTraceIndicator.isVisible(status(false)));
	}

	@Test
	void pinsTheIndicatorToTheTopRightCorner() {
		ClientTickTraceIndicator.IndicatorBounds bounds = ClientTickTraceIndicator.layout(320, 30, 9);

		assertEquals(276, bounds.left());
		assertEquals(6, bounds.top());
		assertEquals(314, bounds.right());
		assertEquals(23, bounds.bottom());
	}

	private static ClientTickTraceRecorder.TraceStatus status(boolean active) {
		return new ClientTickTraceRecorder.TraceStatus(
			active,
			"trace",
			Set.of(ClientTickTraceRecorder.TraceInfo.METADATA),
			100,
			false,
			1L,
			null,
			null,
			0
		);
	}
}
