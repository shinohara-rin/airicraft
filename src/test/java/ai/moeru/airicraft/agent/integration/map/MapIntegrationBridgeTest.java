package ai.moeru.airicraft.agent.integration.map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapIntegrationBridgeTest {
	@AfterEach
	void tearDown() {
		MapIntegrationBridge.clearProviders();
	}

	@Test
	void startsUnavailable() {
		MapIntegrationBridge.clearProviders();

		assertTrue(MapIntegrationBridge.registry().availableProviders().isEmpty());
		assertTrue(MapIntegrationBridge.statusPayload().contains("available=false"));
	}

	@Test
	void registeredProviderIsVisible() {
		MapIntegrationBridge.clearProviders();
		MapIntegrationBridge.setProviders(List.of(new StubMapProvider("journeymap", true)));

		assertEquals("journeymap", MapIntegrationBridge.registry().preferred().orElseThrow().id());
	}

	private record StubMapProvider(String id, boolean available) implements MapIntegrationProvider {
		@Override
		public MapCapabilities capabilities() {
			return MapCapabilities.none();
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
