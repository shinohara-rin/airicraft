package ai.moeru.airicraft.agent.integration.map;

import ai.moeru.airicraft.BridgeUnavailableException;
import ai.moeru.airicraft.agent.llm.LlmImageAttachment;
import ai.moeru.airicraft.agent.llm.PlannerProviderToolResult;
import ai.moeru.airicraft.agent.llm.PlannerToolCall;
import ai.moeru.airicraft.agent.llm.PlannerToolCatalog;
import ai.moeru.airicraft.agent.llm.PlannerToolProvider;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

public final class MapPlannerToolProvider implements PlannerToolProvider {
	public static final String INSPECT_WAYPOINTS = "inspect_map_waypoints";
	public static final String SET_WAYPOINT = "set_map_waypoint";
	public static final String DELETE_WAYPOINT = "delete_map_waypoint";
	public static final String TAKE_MAP_LOOK = "take_map_look";

	private final Supplier<MapIntegrationRegistry> registrySupplier;

	public MapPlannerToolProvider(Supplier<MapIntegrationRegistry> registrySupplier) {
		this.registrySupplier = Objects.requireNonNull(registrySupplier, "registrySupplier");
	}

	@Override
	public String id() {
		return "map_integration";
	}

	@Override
	public boolean available() {
		return registry().preferred().isPresent();
	}

	@Override
	public List<Map<String, Object>> openAiTools() {
		if (!available()) {
			return List.of();
		}
		return List.of(
			PlannerToolCatalog.toolForProvider(
				INSPECT_WAYPOINTS,
				"Inspect map waypoints from the active map provider.",
				PlannerToolCatalog.propertiesForProvider(
					PlannerToolCatalog.propForProvider("provider", PlannerToolCatalog.optionalStringForProvider("Optional map provider id.")),
					PlannerToolCatalog.propForProvider("dimension", PlannerToolCatalog.optionalStringForProvider("Optional dimension id."))
				),
				List.of()
			),
			PlannerToolCatalog.toolForProvider(
				SET_WAYPOINT,
				"Create or update a map waypoint.",
				PlannerToolCatalog.propertiesForProvider(
					PlannerToolCatalog.propForProvider("provider", PlannerToolCatalog.optionalStringForProvider("Optional map provider id.")),
					PlannerToolCatalog.propForProvider("id", PlannerToolCatalog.optionalStringForProvider("Optional existing waypoint id.")),
					PlannerToolCatalog.propForProvider("name", PlannerToolCatalog.stringForProvider("Waypoint name.")),
					PlannerToolCatalog.propForProvider("dimension", PlannerToolCatalog.optionalStringForProvider("Dimension id.")),
					PlannerToolCatalog.propForProvider("x", Map.of("type", "integer", "description", "Block X.")),
					PlannerToolCatalog.propForProvider("y", Map.of("type", "integer", "description", "Block Y.")),
					PlannerToolCatalog.propForProvider("z", Map.of("type", "integer", "description", "Block Z."))
				),
				List.of("name", "dimension", "x", "y", "z")
			),
			PlannerToolCatalog.toolForProvider(
				DELETE_WAYPOINT,
				"Delete a map waypoint.",
				PlannerToolCatalog.propertiesForProvider(
					PlannerToolCatalog.propForProvider("provider", PlannerToolCatalog.optionalStringForProvider("Optional map provider id.")),
					PlannerToolCatalog.propForProvider("id", PlannerToolCatalog.stringForProvider("Waypoint id."))
				),
				List.of("id")
			),
			PlannerToolCatalog.toolForProvider(
				TAKE_MAP_LOOK,
				"Attach a stable internal map image from the active map provider, independent of the current on-screen HUD size.",
				PlannerToolCatalog.propertiesForProvider(
					PlannerToolCatalog.propForProvider("provider", PlannerToolCatalog.optionalStringForProvider("Optional map provider id.")),
					PlannerToolCatalog.propForProvider("kind", PlannerToolCatalog.enumStringForProvider("Map image kind.", List.of("worldmap", "minimap"))),
					PlannerToolCatalog.propForProvider("dimension", PlannerToolCatalog.optionalStringForProvider("Dimension id.")),
					PlannerToolCatalog.propForProvider("radiusChunks", Map.of("type", "integer", "description", "Optional map radius in chunks.")),
					PlannerToolCatalog.propForProvider("zoom", Map.of("type", "integer", "description", "Optional map zoom level.")),
					PlannerToolCatalog.propForProvider("grid", Map.of("type", "boolean", "description", "Whether to include a chunk grid overlay.")),
					PlannerToolCatalog.propForProvider("originX", Map.of("type", "integer", "description", "Optional map center block X.")),
					PlannerToolCatalog.propForProvider("originZ", Map.of("type", "integer", "description", "Optional map center block Z."))
				),
				List.of()
			)
		);
	}

	@Override
	public String promptInstructions() {
		if (!available()) {
			return "";
		}
		return """
			If map waypoints or map images are useful, call inspect_map_waypoints, set_map_waypoint, delete_map_waypoint, or take_map_look.
			take_map_look attaches a stable internal minimap/worldmap image to the follow-up. Use it for minimap or map questions instead of take_a_look; it is not a capture of the current on-screen HUD.
			Pass originX and originZ when the map should be centered somewhere other than the player's current position.
			""";
	}

	@Override
	public boolean handles(String toolName) {
		String normalized = PlannerToolCatalog.normalizeName(toolName);
		return INSPECT_WAYPOINTS.equals(normalized)
			|| SET_WAYPOINT.equals(normalized)
			|| DELETE_WAYPOINT.equals(normalized)
			|| TAKE_MAP_LOOK.equals(normalized);
	}

	@Override
	public boolean isReadTool(String toolName) {
		String normalized = PlannerToolCatalog.normalizeName(toolName);
		return INSPECT_WAYPOINTS.equals(normalized) || TAKE_MAP_LOOK.equals(normalized);
	}

	@Override
	public void validateArguments(String toolName, JsonObject arguments) {
		String normalized = PlannerToolCatalog.normalizeName(toolName);
		if (SET_WAYPOINT.equals(normalized)) {
			requireString(arguments, "name");
			requireString(arguments, "dimension");
			requireInt(arguments, "x");
			requireInt(arguments, "y");
			requireInt(arguments, "z");
		}
		if (DELETE_WAYPOINT.equals(normalized)) {
			requireString(arguments, "id");
		}
	}

	@Override
	public CompletableFuture<String> execute(PlannerToolCall toolCall) {
		return executeResult(toolCall).thenApply(PlannerProviderToolResult::text);
	}

	@Override
	public CompletableFuture<PlannerProviderToolResult> executeResult(PlannerToolCall toolCall) {
		String name = PlannerToolCatalog.normalizeName(toolCall == null ? null : toolCall.name());
		JsonObject args = toolCall == null ? null : toolCall.arguments();
		return switch (name) {
			case INSPECT_WAYPOINTS -> CompletableFuture.completedFuture(PlannerProviderToolResult.text(inspectWaypoints(args)));
			case SET_WAYPOINT -> CompletableFuture.completedFuture(PlannerProviderToolResult.text(setWaypoint(args)));
			case DELETE_WAYPOINT -> CompletableFuture.completedFuture(PlannerProviderToolResult.text(deleteWaypoint(args)));
			case TAKE_MAP_LOOK -> takeMapLook(args);
			default -> CompletableFuture.completedFuture(PlannerProviderToolResult.text("MAP_UNAVAILABLE: unknown_map_tool"));
		};
	}

	private String inspectWaypoints(JsonObject args) {
		MapIntegrationProvider provider = provider(stringArg(args, "provider"));
		List<MapWaypoint> waypoints = provider.listWaypoints(new MapWaypointQuery(provider.id(), stringArg(args, "dimension")));
		return "Tool result for inspect_map_waypoints: provider=" + provider.id()
			+ ", waypoints=" + waypoints.stream().map(MapPlannerToolProvider::waypointText).toList();
	}

	private String setWaypoint(JsonObject args) {
		MapIntegrationProvider provider = provider(stringArg(args, "provider"));
		MapWaypoint waypoint = provider.upsertWaypoint(new MapWaypointWrite(
			provider.id(),
			stringArg(args, "id"),
			stringArg(args, "name"),
			stringArg(args, "dimension"),
			intArg(args, "x", 0),
			intArg(args, "y", 0),
			intArg(args, "z", 0),
			null,
			true,
			true,
			true
		));
		return "Tool result for set_map_waypoint: provider=" + provider.id() + ", waypoint=" + waypointText(waypoint);
	}

	private String deleteWaypoint(JsonObject args) {
		MapIntegrationProvider provider = provider(stringArg(args, "provider"));
		String id = stringArg(args, "id");
		boolean deleted = provider.deleteWaypoint(id);
		return deleted
			? "Tool result for delete_map_waypoint: provider=" + provider.id() + ", deleted=true, waypointId=" + id
			: "MAP_UNAVAILABLE: waypoint_not_found id=" + id;
	}

	private CompletableFuture<PlannerProviderToolResult> takeMapLook(JsonObject args) {
		MapIntegrationProvider provider;
		try {
			provider = provider(stringArg(args, "provider"));
		}
		catch (RuntimeException exception) {
			return CompletableFuture.completedFuture(PlannerProviderToolResult.text(mapFailureText(exception)));
		}
		return provider.captureMap(new MapImageRequest(
			provider.id(),
			stringArg(args, "kind", "worldmap"),
			stringArg(args, "dimension"),
			intArg(args, "radiusChunks", 8),
			intArg(args, "zoom", 0),
			booleanArg(args, "grid", false),
			nullableIntArg(args, "originX"),
			nullableIntArg(args, "originZ")
		)).handle((capture, throwable) -> {
			if (throwable != null) {
				return PlannerProviderToolResult.text(mapFailureText(throwable));
			}
			return PlannerProviderToolResult.image(
				"Tool result for take_map_look: provider=" + provider.id()
					+ ", kind=" + capture.kind()
					+ ", image attached.",
				new LlmImageAttachment("image/" + capture.format().toLowerCase(java.util.Locale.ROOT), capture.imageBytes(), "auto")
			);
		});
	}

	private MapIntegrationProvider provider(String providerId) {
		MapIntegrationRegistry registry = registry();
		if (providerId != null && !providerId.isBlank()) {
			return registry.provider(providerId)
				.filter(MapIntegrationProvider::available)
				.orElseThrow(() -> new IllegalStateException("Map provider unavailable: " + providerId));
		}
		return registry.preferred().orElseThrow(() -> new IllegalStateException("No map provider is available"));
	}

	private MapIntegrationRegistry registry() {
		MapIntegrationRegistry registry = registrySupplier.get();
		return registry == null ? MapIntegrationRegistry.empty() : registry;
	}

	private static String waypointText(MapWaypoint waypoint) {
		return "{id=" + waypoint.id()
			+ ", name=\"" + waypoint.name() + "\""
			+ ", dim=" + waypoint.dimension()
			+ ", pos=" + waypoint.x() + "," + waypoint.y() + "," + waypoint.z()
			+ "}";
	}

	private static void requireString(JsonObject args, String key) {
		if (stringArg(args, key) == null || stringArg(args, key).isBlank()) {
			throw new JsonParseException(key + " must be a non-empty string");
		}
	}

	private static void requireInt(JsonObject args, String key) {
		if (args == null || !args.has(key) || !args.get(key).isJsonPrimitive()) {
			throw new JsonParseException(key + " must be an integer");
		}
		try {
			args.get(key).getAsInt();
		}
		catch (RuntimeException exception) {
			throw new JsonParseException(key + " must be an integer", exception);
		}
	}

	private static String stringArg(JsonObject args, String key) {
		return stringArg(args, key, null);
	}

	private static String stringArg(JsonObject args, String key, String fallback) {
		if (args == null || !args.has(key) || !args.get(key).isJsonPrimitive()) {
			return fallback;
		}
		return args.get(key).getAsString();
	}

	private static int intArg(JsonObject args, String key, int fallback) {
		if (args == null || !args.has(key) || !args.get(key).isJsonPrimitive()) {
			return fallback;
		}
		return args.get(key).getAsInt();
	}

	private static boolean booleanArg(JsonObject args, String key, boolean fallback) {
		if (args == null || !args.has(key) || !args.get(key).isJsonPrimitive()) {
			return fallback;
		}
		return args.get(key).getAsBoolean();
	}

	private static Integer nullableIntArg(JsonObject args, String key) {
		if (args == null || !args.has(key) || !args.get(key).isJsonPrimitive()) {
			return null;
		}
		return args.get(key).getAsInt();
	}

	private static String mapFailureText(Throwable throwable) {
		Throwable cause = throwable instanceof CompletionException completionException && completionException.getCause() != null
			? completionException.getCause()
			: throwable;
		if (cause instanceof BridgeUnavailableException bridgeUnavailableException) {
			String message = bridgeUnavailableException.getMessage();
			return "MAP_UNAVAILABLE: " + bridgeUnavailableException.code()
				+ (message == null || message.isBlank() ? "" : " - " + message);
		}
		return "MAP_UNAVAILABLE: map_capture_failed";
	}
}
