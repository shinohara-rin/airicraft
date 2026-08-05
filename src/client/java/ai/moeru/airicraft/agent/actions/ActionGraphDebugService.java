package ai.moeru.airicraft.agent.actions;

import ai.moeru.airicraft.agent.tasks.CraftingOpportunity;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ActionGraphDebugService {
	public ActionGraphDebugService() {
	}

	public Map<String, Object> inspectActionGraph() {
		List<Map<String, Object>> primitives = PrimitiveActionRegistry.defaults().all().stream()
			.sorted(Comparator.comparing(PrimitiveActionMetadata::id))
			.map(ActionGraphDebugService::primitivePayload)
			.toList();
		List<Map<String, Object>> domainProviders = domainProviders();

		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("available", true);
		payload.put("primitiveCount", primitives.size());
		payload.put("domainProviderCount", domainProviders.size());
		payload.put("primitives", primitives);
		payload.put("domainProviders", domainProviders);
		return payload;
	}

	public Map<String, Object> resolveInventoryItem(ActionGraphResolveRequest request) {
		return resolveInventoryItemResolution(request).payload();
	}

	public ActionGraphResolvedInventoryItem resolveInventoryItemResolution(ActionGraphResolveRequest request) {
		ActionFactStore facts = new ActionFactStore();
		addInventoryFacts(facts, request, request.observedInventory(), ActionFactProvenance.OBSERVED);
		addInventoryFacts(facts, request, request.assumedInventory(), ActionFactProvenance.EXECUTOR_REPORTED);
		addCraftRecipeFacts(facts, request);

		ActionResolveResult resolveResult = new AutoCommittingRoutePlanner().adviseAndCommit(
			new AiricraftPlanningSnapshot(
				facts.queryAll(),
				BlockAcquisitionIndex.empty(),
				NearbyBlockAvailability.unknown(),
				request.context()
			),
			ActionGoal.inventoryItem(request.itemId(), request.quantity()),
			java.util.Set.of()
		);

		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("available", true);
		payload.put("goal", Map.of(
			"fact", "inventory.item",
			"itemId", request.itemId(),
			"countAtLeast", request.quantity()
		));
		payload.put("factSourceCounts", Map.of(
			"observedInventory", request.observedInventory().size(),
			"observedCraftRecipes", request.availableCrafts().size(),
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
		return List.of(
			providerPayload("resource_provider", ActionFactType.INVENTORY_RESOURCE.id()),
			providerPayload("recipe_provider", ActionFactType.CRAFT_RECIPE.id()),
			providerPayload("smelting_provider", ActionFactType.SMELT_RECIPE.id()),
			providerPayload("mining_provider", ActionFactType.WORLD_BLOCK.id())
		);
	}

	private static Map<String, Object> providerPayload(String id, String inputFact) {
		return Map.of("id", id, "inputFacts", List.of(inputFact), "advisory", true);
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

	private static void addCraftRecipeFacts(ActionFactStore facts, ActionGraphResolveRequest request) {
		for (CraftingOpportunity opportunity : request.availableCrafts()) {
			LinkedHashMap<String, Integer> inputCounts = new LinkedHashMap<>();
			for (String inputItemId : opportunity.inputItemIds()) {
				inputCounts.merge(inputItemId, 1, Integer::sum);
			}
			facts.upsert(new ActionFact(
				ActionFactIdentity.craftRecipe(request.context().worldId(), request.context().actorId(), opportunity.recipeId()),
				Map.of(
					"outputItemId", opportunity.outputItemId(),
					"outputCount", opportunity.outputCount(),
					"inputItemIds", opportunity.inputItemIds(),
					"inputCounts", inputCounts,
					"gridKind", opportunity.gridKind().name()
				),
				ActionFactProvenance.OBSERVED,
				request.context().currentTick(),
				request.context().currentTick() + 1
			));
		}
	}

	private static Map<String, Object> primitivePayload(PrimitiveActionMetadata metadata) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("id", metadata.id());
		payload.put("version", metadata.version());
		payload.put("summary", metadata.summary());
		payload.put("cost", metadata.cost());
		payload.put("role", metadata.role().name());
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
		payload.put("methodKey", Map.of(
			"providerId", step.methodKey().providerId().value(),
			"methodId", step.methodKey().methodId()
		));
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

}
