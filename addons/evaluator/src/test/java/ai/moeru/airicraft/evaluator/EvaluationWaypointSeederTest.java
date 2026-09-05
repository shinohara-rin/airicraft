package ai.moeru.airicraft.evaluator;

import ai.moeru.airicraft.BridgeUnavailableException;
import ai.moeru.airicraft.agent.evaluation.EvaluationBudget;
import ai.moeru.airicraft.agent.evaluation.EvaluationEvidenceSettings;
import ai.moeru.airicraft.agent.evaluation.EvaluationScenario;
import ai.moeru.airicraft.agent.evaluation.EvaluationWaypoint;
import ai.moeru.airicraft.agent.integration.map.MapCapabilities;
import ai.moeru.airicraft.agent.integration.map.MapImageCapture;
import ai.moeru.airicraft.agent.integration.map.MapImageRequest;
import ai.moeru.airicraft.agent.integration.map.MapIntegrationProvider;
import ai.moeru.airicraft.agent.integration.map.MapIntegrationRegistry;
import ai.moeru.airicraft.agent.integration.map.MapWaypoint;
import ai.moeru.airicraft.agent.integration.map.MapWaypointQuery;
import ai.moeru.airicraft.agent.integration.map.MapWaypointWrite;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EvaluationWaypointSeederTest {
	@Test
	void seedsConfiguredWaypointsIntoRequestedProvider() {
		StubMapProvider provider = new StubMapProvider("journeymap", true);
		EvaluationWaypointSeeder seeder = new EvaluationWaypointSeeder(() -> MapIntegrationRegistry.of(provider));

		seeder.seed(scenario(List.of(new EvaluationWaypoint("journeymap", "farm-here", "Farm here", "minecraft:overworld", -31, 63, -63))));

		assertEquals("farm-here", provider.lastWrite.id());
		assertEquals("Farm here", provider.lastWrite.name());
		assertEquals("minecraft:overworld", provider.lastWrite.dimension());
		assertEquals(-31, provider.lastWrite.x());
		assertEquals(63, provider.lastWrite.y());
		assertEquals(-63, provider.lastWrite.z());
	}

	@Test
	void missingConfiguredProviderFailsSetup() {
		EvaluationWaypointSeeder seeder = new EvaluationWaypointSeeder(() -> MapIntegrationRegistry.of(new StubMapProvider("journeymap", false)));

		BridgeUnavailableException exception = assertThrows(
			BridgeUnavailableException.class,
			() -> seeder.seed(scenario(List.of(new EvaluationWaypoint("journeymap", "Farm here", "Farm here", "minecraft:overworld", -31, 63, -63))))
		);

		assertEquals("evaluation_waypoint_provider_unavailable", exception.code());
	}

	@Test
	void scenariosWithoutWaypointsAreNoop() {
		StubMapProvider provider = new StubMapProvider("journeymap", true);
		EvaluationWaypointSeeder seeder = new EvaluationWaypointSeeder(() -> MapIntegrationRegistry.of(provider));

		seeder.seed(scenario(List.of()));

		assertNull(provider.lastWrite);
	}

	private static EvaluationScenario scenario(List<EvaluationWaypoint> waypoints) {
		return new EvaluationScenario(
			"farm_from_scratch",
			"Farm from scratch",
			"1.21.8",
			"dev",
			null,
			"world.zip",
			true,
			"Build a farm",
			EvaluationBudget.defaults(),
			List.of(),
			waypoints,
			EvaluationEvidenceSettings.defaults(),
			null
		);
	}

	private static final class StubMapProvider implements MapIntegrationProvider {
		private final String id;
		private final boolean available;
		private MapWaypointWrite lastWrite;

		private StubMapProvider(String id, boolean available) {
			this.id = id;
			this.available = available;
		}

		@Override
		public String id() {
			return id;
		}

		@Override
		public boolean available() {
			return available;
		}

		@Override
		public MapCapabilities capabilities() {
			return MapCapabilities.of(MapCapabilities.MapCapability.WRITE_WAYPOINTS);
		}

		@Override
		public List<MapWaypoint> listWaypoints(MapWaypointQuery query) {
			return List.of();
		}

		@Override
		public MapWaypoint upsertWaypoint(MapWaypointWrite request) {
			lastWrite = request;
			return new MapWaypoint(id, request.id(), request.name(), request.dimension(), request.x(), request.y(), request.z(), 0x33aaff, true, true, true);
		}

		@Override
		public boolean deleteWaypoint(String waypointId) {
			return false;
		}

		@Override
		public CompletableFuture<MapImageCapture> captureMap(MapImageRequest request) {
			return CompletableFuture.failedFuture(new UnsupportedOperationException("not implemented"));
		}
	}
}
