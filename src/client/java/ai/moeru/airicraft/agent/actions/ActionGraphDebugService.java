package ai.moeru.airicraft.agent.actions;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ActionGraphDebugService {
	private final Path actionsetRoot;

	public ActionGraphDebugService() {
		this(ActionsetLibraryPaths.defaultRoot());
	}

	public ActionGraphDebugService(Path actionsetRoot) {
		this.actionsetRoot = actionsetRoot == null ? ActionsetLibraryPaths.defaultRoot() : actionsetRoot;
	}

	public Map<String, Object> inspectActionGraph() {
		ActionsetLoadResult loadResult = ActionsetLibraryLoader.defaults().load(actionsetRoot);
		List<Map<String, Object>> primitives = PrimitiveActionRegistry.defaults().all().stream()
			.sorted(Comparator.comparing(PrimitiveActionMetadata::id))
			.map(ActionGraphDebugService::primitivePayload)
			.toList();
		List<Map<String, Object>> actionsets = loadResult.index().all().stream()
			.sorted(Comparator.comparing(ActionsetEntry::actionId))
			.map(ActionGraphDebugService::actionsetPayload)
			.toList();
		List<Map<String, Object>> domainProviders = domainProviders();

		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("available", true);
		payload.put("actionsetRoot", actionsetRoot.toString());
		payload.put("actionsetValid", loadResult.valid());
		payload.put("primitiveCount", primitives.size());
		payload.put("actionsetCount", actionsets.size());
		payload.put("domainProviderCount", domainProviders.size());
		payload.put("diagnosticCount", loadResult.diagnostics().size());
		payload.put("primitives", primitives);
		payload.put("actionsets", actionsets);
		payload.put("domainProviders", domainProviders);
		payload.put("actionsetDiagnostics", loadResult.diagnostics().stream()
			.map(ActionGraphDebugService::diagnosticPayload)
			.toList());
		return payload;
	}

	public Map<String, Object> resolveInventoryItem(ActionGraphResolveRequest request) {
		return resolveInventoryItemResolution(request).payload();
	}

	public ActionGraphResolvedInventoryItem resolveInventoryItemResolution(ActionGraphResolveRequest request) {
		ActionsetLoadResult loadResult = ActionsetLibraryLoader.defaults().load(actionsetRoot);
		ActionFactStore facts = new ActionFactStore();
		addInventoryFacts(facts, request, request.observedInventory(), ActionFactProvenance.OBSERVED);
		addInventoryFacts(facts, request, request.assumedInventory(), ActionFactProvenance.EXECUTOR_REPORTED);

		ActionResolveResult resolveResult = loadResult.valid()
			? new ActionResolver(loadResult.index(), facts, request.context()).resolve(ActionGoal.inventoryItem(request.itemId(), request.quantity()))
			: ActionResolveResult.failure("actionset_validation_failed", "Actionset library validation failed", List.of());

		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("available", true);
		payload.put("actionsetRoot", actionsetRoot.toString());
		payload.put("actionsetValid", loadResult.valid());
		payload.put("actionsetDiagnostics", loadResult.diagnostics().stream()
			.map(ActionGraphDebugService::diagnosticPayload)
			.toList());
		payload.put("goal", Map.of(
			"fact", "inventory.item",
			"itemId", request.itemId(),
			"countAtLeast", request.quantity()
		));
		payload.put("factSourceCounts", Map.of(
			"observedInventory", request.observedInventory().size(),
			"assumedInventory", request.assumedInventory().size()
		));
		payload.put("resolved", resolveResult.resolved());
		payload.put("failureCode", resolveResult.failureCode());
		payload.put("message", resolveResult.message());
		payload.put("route", routePayload(resolveResult.route()));
		payload.put("trace", resolveResult.trace().stream()
			.map(ActionGraphDebugService::tracePayload)
			.toList());
		return new ActionGraphResolvedInventoryItem(resolveResult, payload);
	}

	private static List<Map<String, Object>> domainProviders() {
		return List.of(Map.of(
			"id", "recipe_provider",
			"summary", "Plans craft_item route fragments from observed craft.recipe facts.",
			"inputFacts", List.of(ActionFactType.CRAFT_RECIPE.id(), ActionFactType.INVENTORY_ITEM.id()),
			"producedGoal", ActionFactType.INVENTORY_ITEM.id()
		));
	}

	private static void addInventoryFacts(
		ActionFactStore facts,
		ActionGraphResolveRequest request,
		Map<String, Integer> inventory,
		ActionFactProvenance provenance
	) {
		for (Map.Entry<String, Integer> entry : inventory.entrySet()) {
			facts.upsert(new ActionFact(
				ActionFactIdentity.inventoryItem(request.context().worldId(), request.context().actorId(), entry.getKey()),
				Map.of("count", entry.getValue()),
				provenance,
				request.context().currentTick(),
				ActionFact.NEVER_STALE
			));
		}
	}

	private static Map<String, Object> primitivePayload(PrimitiveActionMetadata metadata) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("id", metadata.id());
		payload.put("version", metadata.version());
		payload.put("summary", metadata.summary());
		payload.put("cost", metadata.cost());
		payload.put("foregroundActuation", metadata.foregroundActuation());
		payload.put("cancellable", metadata.cancellable());
		payload.put("defaultTimeoutTicks", metadata.defaultTimeoutTicks());
		payload.put("executorBinding", metadata.executorBinding());
		payload.put("capabilityTags", metadata.capabilityTags());
		payload.put("guardFactTypes", metadata.guardFactTypes());
		payload.put("needFactTypes", metadata.needFactTypes());
		payload.put("producedFactTypes", metadata.producedFactTypes());
		payload.put("consumedFactTypes", metadata.consumedFactTypes());
		payload.put("failureCodes", metadata.failureCodes());
		payload.put("params", metadata.parameterSchema().entrySet().stream()
			.sorted(Map.Entry.comparingByKey())
			.map(entry -> primitiveParameterPayload(entry.getKey(), entry.getValue()))
			.toList());
		return payload;
	}

	private static Map<String, Object> primitiveParameterPayload(String name, PrimitiveParameter parameter) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("name", name);
		payload.put("type", parameter.type());
		payload.put("required", parameter.required());
		payload.put("summary", parameter.summary());
		return payload;
	}

	private static Map<String, Object> actionsetPayload(ActionsetEntry entry) {
		Map<String, Object> definition = entry.definition();
		List<Map<String, Object>> alternatives = objectList(definition.get("alternatives")).stream()
			.map(ActionGraphDebugService::objectMap)
			.map(ActionGraphDebugService::alternativePayload)
			.toList();

		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("actionId", entry.actionId());
		payload.put("namespace", entry.namespace().directoryName());
		payload.put("sourceName", entry.sourceName());
		payload.put("summary", stringValue(definition.get("summary")));
		payload.put("paramCount", objectMap(definition.get("params")).size());
		payload.put("alternativeCount", alternatives.size());
		payload.put("produces", objectList(definition.get("produces")).stream()
			.map(ActionGraphDebugService::objectMap)
			.map(ActionGraphDebugService::factLabel)
			.toList());
		payload.put("alternatives", alternatives);
		return payload;
	}

	private static Map<String, Object> alternativePayload(Map<String, Object> alternative) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("id", stringValue(alternative.get("id")));
		if (alternative.containsKey("cost")) {
			payload.put("cost", alternative.get("cost"));
		}
		payload.put("guardCount", objectList(alternative.get("guards")).size());
		payload.put("needCount", objectList(alternative.get("needs")).size());
		payload.put("stepCount", objectList(alternative.get("steps")).size());
		return payload;
	}

	private static String factLabel(Map<String, Object> fact) {
		String factType = stringValue(fact.get("fact"));
		if (factType.isBlank()) {
			return "<unknown>";
		}
		for (String key : List.of("itemId", "toolTag", "cropId", "siteId", "siteType", "entityTypeId", "recipeId")) {
			String value = stringValue(fact.get(key));
			if (!value.isBlank()) {
				return factType + ":" + value;
			}
		}
		return factType;
	}

	private static Map<String, Object> routePayload(ActionRoute route) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("cost", route.cost());
		payload.put("steps", route.steps().stream()
			.map(ActionGraphDebugService::stepPayload)
			.toList());
		return payload;
	}

	private static Map<String, Object> stepPayload(ActionPlanStep step) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("kind", step.kind().name());
		payload.put("actionId", step.actionId());
		payload.put("alternativeId", step.alternativeId());
		payload.put("stepId", step.stepId());
		payload.put("targetId", step.targetId());
		payload.put("args", step.args());
		return payload;
	}

	private static Map<String, Object> tracePayload(ActionTraceEvent event) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("eventType", event.eventType());
		payload.put("actionId", event.actionId());
		payload.put("alternativeId", event.alternativeId());
		payload.put("stepId", event.stepId());
		payload.put("payload", event.payload());
		return payload;
	}

	private static Map<String, Object> diagnosticPayload(ActionsetLoadDiagnostic diagnostic) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("code", diagnostic.code());
		payload.put("path", diagnostic.path());
		payload.put("message", diagnostic.message());
		payload.put("sourceName", diagnostic.sourceName());
		payload.put("namespace", diagnostic.namespace() == null ? "" : diagnostic.namespace().name());
		payload.put("blocking", diagnostic.blocking());
		return payload;
	}

	private static List<Object> objectList(Object value) {
		if (value instanceof List<?> list) {
			return List.copyOf(list);
		}
		return List.of();
	}

	private static Map<String, Object> objectMap(Object value) {
		if (value instanceof Map<?, ?> map) {
			LinkedHashMap<String, Object> typed = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				typed.put(String.valueOf(entry.getKey()), entry.getValue());
			}
			return typed;
		}
		return Map.of();
	}

	private static String stringValue(Object value) {
		return value == null ? "" : String.valueOf(value);
	}
}
