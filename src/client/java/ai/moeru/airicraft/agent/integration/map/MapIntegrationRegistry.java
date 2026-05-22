package ai.moeru.airicraft.agent.integration.map;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class MapIntegrationRegistry {
	private static final MapIntegrationRegistry EMPTY = new MapIntegrationRegistry(List.of());

	private final List<MapIntegrationProvider> providers;

	private MapIntegrationRegistry(List<MapIntegrationProvider> providers) {
		this.providers = List.copyOf(providers);
	}

	public static MapIntegrationRegistry empty() {
		return EMPTY;
	}

	public static MapIntegrationRegistry of(MapIntegrationProvider... providers) {
		if (providers == null || providers.length == 0) {
			return empty();
		}
		return of(Arrays.asList(providers));
	}

	public static MapIntegrationRegistry of(List<MapIntegrationProvider> providers) {
		if (providers == null || providers.isEmpty()) {
			return empty();
		}
		Map<String, MapIntegrationProvider> byId = new LinkedHashMap<>();
		for (MapIntegrationProvider provider : providers) {
			if (provider == null) {
				continue;
			}
			String id = normalizeProviderId(provider.id());
			if (byId.putIfAbsent(id, provider) != null) {
				throw new IllegalArgumentException("Duplicate map provider id: " + id);
			}
		}
		if (byId.isEmpty()) {
			return empty();
		}
		return new MapIntegrationRegistry(List.copyOf(byId.values()));
	}

	public List<MapIntegrationProvider> providers() {
		return providers;
	}

	public Optional<MapIntegrationProvider> provider(String id) {
		String normalized = normalizeProviderId(id);
		return providers.stream()
			.filter(provider -> normalizeProviderId(provider.id()).equals(normalized))
			.findFirst();
	}

	public Optional<MapIntegrationProvider> preferred() {
		return availableProviders().stream().findFirst();
	}

	public List<MapIntegrationProvider> availableProviders() {
		return providers.stream()
			.filter(MapIntegrationProvider::available)
			.toList();
	}

	private static String normalizeProviderId(String id) {
		String normalized = Objects.requireNonNullElse(id, "").trim().toLowerCase(Locale.ROOT);
		if (normalized.isBlank()) {
			throw new IllegalArgumentException("Map provider id must not be blank");
		}
		return normalized;
	}
}
