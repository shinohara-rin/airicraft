package ai.moeru.airicraft.agent.integration.map;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public final class MapIntegrationBridge {
	private static final AtomicReference<MapIntegrationRegistry> REGISTRY = new AtomicReference<>(MapIntegrationRegistry.empty());

	private MapIntegrationBridge() {
	}

	public static MapIntegrationRegistry registry() {
		return REGISTRY.get();
	}

	public static void setProviders(List<MapIntegrationProvider> providers) {
		REGISTRY.set(MapIntegrationRegistry.of(providers));
	}

	public static void clearProviders() {
		REGISTRY.set(MapIntegrationRegistry.empty());
	}

	public static String statusPayload() {
		MapIntegrationRegistry registry = registry();
		String providers = registry.providers().stream()
			.map(provider -> provider.id() + ":" + provider.available())
			.collect(Collectors.joining(","));
		return "available=" + registry.preferred().isPresent()
			+ ", preferred=" + registry.preferred().map(MapIntegrationProvider::id).orElse("")
			+ ", providers=[" + providers + "]";
	}
}
