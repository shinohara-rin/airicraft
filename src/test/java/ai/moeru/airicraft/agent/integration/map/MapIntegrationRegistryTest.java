package ai.moeru.airicraft.agent.integration.map;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MapIntegrationRegistryTest {
	@Test
	void availableProviderPrefersFirstAvailableRegisteredAdapter() {
		StubMapProvider unavailable = new StubMapProvider("journeymap", false);
		StubMapProvider available = new StubMapProvider("xaero", true);
		MapIntegrationRegistry registry = MapIntegrationRegistry.of(unavailable, available);

		assertEquals("xaero", registry.preferred().orElseThrow().id());
	}

	@Test
	void duplicateProviderIdsFailFast() {
		StubMapProvider first = new StubMapProvider("journeymap", true);
		StubMapProvider second = new StubMapProvider("journeymap", true);

		assertThrows(IllegalArgumentException.class, () -> MapIntegrationRegistry.of(first, second));
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
