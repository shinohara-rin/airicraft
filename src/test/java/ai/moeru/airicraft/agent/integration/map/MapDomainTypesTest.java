package ai.moeru.airicraft.agent.integration.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MapDomainTypesTest {
	@Test
	void waypointIdsAreStableProviderScopedIds() {
		MapWaypoint waypoint = new MapWaypoint(
			"journeymap",
			"jm-guid-1",
			"Home",
			"minecraft:overworld",
			1,
			64,
			2,
			0x3366ff,
			true,
			true,
			true
		);

		assertEquals("journeymap", waypoint.providerId());
		assertEquals("jm-guid-1", waypoint.id());
		assertEquals("minecraft:overworld", waypoint.dimension());
	}

	@Test
	void imageCaptureDefensivelyCopiesPngBytes() {
		byte[] bytes = new byte[] {1, 2, 3};
		MapImageCapture capture = new MapImageCapture("journeymap", "worldmap", "png", 16, 16, 123L, bytes);

		bytes[0] = 9;

		assertArrayEquals(new byte[] {1, 2, 3}, capture.imageBytes());
	}
}
