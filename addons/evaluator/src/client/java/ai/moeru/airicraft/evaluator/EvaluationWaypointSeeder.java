package ai.moeru.airicraft.evaluator;

import ai.moeru.airicraft.BridgeUnavailableException;
import ai.moeru.airicraft.agent.evaluation.EvaluationScenario;
import ai.moeru.airicraft.agent.evaluation.EvaluationWaypoint;
import ai.moeru.airicraft.agent.integration.map.MapCapabilities;
import ai.moeru.airicraft.agent.integration.map.MapIntegrationBridge;
import ai.moeru.airicraft.agent.integration.map.MapIntegrationProvider;
import ai.moeru.airicraft.agent.integration.map.MapIntegrationRegistry;
import ai.moeru.airicraft.agent.integration.map.MapWaypointWrite;

import java.util.Objects;
import java.util.function.Supplier;

final class EvaluationWaypointSeeder {
	private final Supplier<MapIntegrationRegistry> registrySupplier;

	EvaluationWaypointSeeder() {
		this(MapIntegrationBridge::registry);
	}

	EvaluationWaypointSeeder(Supplier<MapIntegrationRegistry> registrySupplier) {
		this.registrySupplier = Objects.requireNonNull(registrySupplier, "registrySupplier");
	}

	void seed(EvaluationScenario scenario) {
		if (scenario == null || scenario.waypoints().isEmpty()) {
			return;
		}
		MapIntegrationRegistry registry = Objects.requireNonNullElse(registrySupplier.get(), MapIntegrationRegistry.empty());
		for (EvaluationWaypoint waypoint : scenario.waypoints()) {
			MapIntegrationProvider provider = providerFor(registry, waypoint);
			if (!provider.capabilities().has(MapCapabilities.MapCapability.WRITE_WAYPOINTS)) {
				throw new BridgeUnavailableException(
					"evaluation_waypoint_provider_unavailable",
					"Map provider cannot write evaluation waypoint " + waypoint.name() + ": " + provider.id()
				);
			}
			try {
				provider.upsertWaypoint(new MapWaypointWrite(
					provider.id(),
					waypoint.id(),
					waypoint.name(),
					waypoint.dimension(),
					waypoint.x(),
					waypoint.y(),
					waypoint.z(),
					null,
					true,
					true,
					true
				));
			}
			catch (RuntimeException exception) {
				throw new BridgeUnavailableException(
					"evaluation_waypoint_seed_failed",
					"Failed to seed waypoint " + waypoint.name() + " into provider " + provider.id() + ": " + exception.getMessage()
				);
			}
		}
	}

	private static MapIntegrationProvider providerFor(MapIntegrationRegistry registry, EvaluationWaypoint waypoint) {
		String requestedProvider = waypoint.provider();
		if (requestedProvider != null && !requestedProvider.isBlank()) {
			return registry.provider(requestedProvider)
				.filter(MapIntegrationProvider::available)
				.orElseThrow(() -> new BridgeUnavailableException(
					"evaluation_waypoint_provider_unavailable",
					"Map provider unavailable for evaluation waypoint " + waypoint.name() + ": " + requestedProvider
				));
		}
		return registry.preferred()
			.orElseThrow(() -> new BridgeUnavailableException(
				"evaluation_waypoint_provider_unavailable",
				"No map provider is available for evaluation waypoint " + waypoint.name()
			));
	}
}
