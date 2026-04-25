package ai.moeru.airicraft.agent.actions;

import java.nio.file.Path;
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

	public Map<String, Object> resolveInventoryItem(ActionGraphResolveRequest request) {
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
		return payload;
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
}
