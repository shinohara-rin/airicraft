package ai.moeru.airicraft.compat.journeymap;

import ai.moeru.airicraft.agent.integration.map.MapWaypoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JourneyMapIntegrationProviderTest {
	@Test
	void convertsWaypointFieldsToAiricraftWaypoint() {
		MapWaypoint converted = JourneyMapWaypointMapper.toMapWaypoint(
			"guid-1",
			"Home",
			"minecraft:overworld",
			1,
			64,
			2,
			0x3366ff,
			true,
			true,
			false
		);

		assertEquals("journeymap", converted.providerId());
		assertEquals("guid-1", converted.id());
		assertEquals("Home", converted.name());
		assertEquals("minecraft:overworld", converted.dimension());
		assertEquals(1, converted.x());
		assertEquals(64, converted.y());
		assertEquals(2, converted.z());
		assertEquals(0x3366ff, converted.color());
		assertEquals(true, converted.enabled());
		assertEquals(true, converted.showOnMap());
		assertEquals(false, converted.showInWorld());
	}
}
