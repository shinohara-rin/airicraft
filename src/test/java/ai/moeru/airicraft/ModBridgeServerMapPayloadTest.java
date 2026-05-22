package ai.moeru.airicraft;

import ai.moeru.airicraft.agent.integration.map.MapCapabilities;
import ai.moeru.airicraft.agent.integration.map.MapCapabilities.MapCapability;
import ai.moeru.airicraft.agent.integration.map.MapImageCapture;
import ai.moeru.airicraft.agent.integration.map.MapImageRequest;
import ai.moeru.airicraft.agent.integration.map.MapIntegrationProvider;
import ai.moeru.airicraft.agent.integration.map.MapIntegrationRegistry;
import ai.moeru.airicraft.agent.integration.map.MapWaypoint;
import ai.moeru.airicraft.agent.integration.map.MapWaypointQuery;
import ai.moeru.airicraft.agent.integration.map.MapWaypointWrite;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModBridgeServerMapPayloadTest {
	@Test
	void mapStatusPayloadReportsPreferredProviderAndCapabilities() {
		Map<String, Object> payload = ModBridgeServer.mapStatusPayload(MapIntegrationRegistry.of(new StubMapProvider()));

		assertEquals(true, payload.get("available"));
		assertEquals("journeymap", payload.get("preferredProvider"));
		List<?> providers = (List<?>) payload.get("providers");
		Map<?, ?> provider = (Map<?, ?>) providers.getFirst();
		assertEquals("journeymap", provider.get("id"));
		assertEquals(true, provider.get("available"));
		assertEquals(List.of("READ_WAYPOINTS", "WORLDMAP_IMAGE"), provider.get("capabilities"));
	}

	@Test
	void mapWaypointsPayloadWrapsWaypoints() {
		MapWaypoint waypoint = new MapWaypoint("journeymap", "guid-1", "Home", "minecraft:overworld", 1, 64, 2, 0x3366ff, true, true, true);

		Map<String, Object> payload = ModBridgeServer.mapWaypointsPayload(List.of(waypoint));

		List<?> waypoints = (List<?>) payload.get("waypoints");
		Map<?, ?> item = (Map<?, ?>) waypoints.getFirst();
		assertEquals("guid-1", item.get("id"));
		assertEquals("Home", item.get("name"));
		assertEquals("minecraft:overworld", item.get("dimension"));
	}

	private static final class StubMapProvider implements MapIntegrationProvider {
		@Override
		public String id() {
			return "journeymap";
		}

		@Override
		public boolean available() {
			return true;
		}

		@Override
		public MapCapabilities capabilities() {
			return MapCapabilities.of(MapCapability.READ_WAYPOINTS, MapCapability.WORLDMAP_IMAGE);
		}

		@Override
		public List<MapWaypoint> listWaypoints(MapWaypointQuery query) {
			return List.of();
		}

		@Override
		public MapWaypoint upsertWaypoint(MapWaypointWrite request) {
			throw new UnsupportedOperationException("stub");
		}

		@Override
		public boolean deleteWaypoint(String waypointId) {
			throw new UnsupportedOperationException("stub");
		}

		@Override
		public CompletableFuture<MapImageCapture> captureMap(MapImageRequest request) {
			return CompletableFuture.failedFuture(new UnsupportedOperationException("stub"));
		}
	}
}
