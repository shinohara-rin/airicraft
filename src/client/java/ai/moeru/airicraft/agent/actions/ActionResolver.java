package ai.moeru.airicraft.agent.actions;

import ai.moeru.airicraft.agent.tasks.ResourceGatheringCatalog;
import ai.moeru.actionplan.CandidateRoute;
import ai.moeru.actionplan.ResolutionContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class ActionResolver {
	private static final int DEFAULT_SMELT_COOK_TICKS = 200;
	private static final int FUEL_TICKS_PLANKS = 300;
	private static final int FUEL_TICKS_LOGS = 300;
	private static final int FUEL_TICKS_STICKS = 100;
	private static final int FUEL_TICKS_COAL = 1600;
	private static final int PROVIDER_RANK_RECIPE = 0;
	private static final int PROVIDER_RANK_RESOURCE = 0;
	private static final int PROVIDER_RANK_SMELTING = 1;
	private static final int PROVIDER_RANK_MINING = 2;
	private static final Set<ActionFactProvenance> GUARD_USABLE_PROVENANCE = Set.of(
		ActionFactProvenance.OBSERVED,
		ActionFactProvenance.EXECUTOR_REPORTED,
		ActionFactProvenance.INFERRED
	);

	private final ActionsetIndex index;
	private final ActionFactStore facts;
	private final BlockAcquisitionIndex blockAcquisitions;
	private final NearbyBlockAvailability nearbyBlockAvailability;
	private final ActionResolverContext context;
	private final int maxDepth;
	private final Set<String> blockedAlternativeKeys;
	private final boolean preferActionsetRoutes;
	private final int explorationBudget;
	private final Map<String, List<ActionFact>> craftRecipesByOutput;
	private final Map<String, List<ActionFact>> smeltRecipesByOutput;
	private final Map<String, ActionRoute> successfulRoutes = new HashMap<>();
	private int expandedGoals;
	private int routeCacheHits;
	private boolean budgetExceeded;
	private ResolutionContext advisoryContext;

	public static ActionResolveResult resolve(ActionResolutionRequest request) {
		Objects.requireNonNull(request, "request");
		return new ActionResolver(
			request.actionsets(),
			new ActionFactStore(request.facts()),
			request.blockAcquisitions(),
			request.nearbyBlockAvailability(),
			request.context(),
			request.maxDepth(),
			request.blockedAlternativeKeys(),
			request.preferActionsetRoutes(),
			request.explorationBudget()
		).resolve(request.goal());
	}

	static ActionResolveResult resolveProvider(
		ActionResolutionRequest request,
		String providerId,
		ResolutionContext advisoryContext
	) {
		Objects.requireNonNull(request, "request");
		ActionResolver resolver = new ActionResolver(
			request.actionsets(),
			new ActionFactStore(request.facts()),
			request.blockAcquisitions(),
			request.nearbyBlockAvailability(),
			request.context(),
			request.maxDepth(),
			request.blockedAlternativeKeys(),
			false,
			request.explorationBudget()
		);
		resolver.advisoryContext = Objects.requireNonNull(advisoryContext, "advisoryContext");
		return resolver.resolveWithProvider(request.goal(), providerId);
	}

	private ActionResolveResult resolveWithProvider(ActionGoal goal, String providerId) {
		ArrayList<ActionTraceEvent> trace = new ArrayList<>();
		if (goalSatisfied(goal, trace)) {
			return ActionResolveResult.success(ActionRoute.empty(), trace);
		}
		Optional<ProviderCandidate> candidate = switch (providerId) {
			case "resource_provider" -> resolveResourceProviderGoal(goal, 0, new LinkedHashSet<>(), trace);
			case "recipe_provider" -> resolveRecipeProviderGoal(goal, 0, new LinkedHashSet<>(), trace);
			case "smelting_provider" -> resolveSmeltingProviderGoal(goal, 0, new LinkedHashSet<>(), trace);
			case "mining_provider" -> resolveMiningProviderGoal(goal, 0, new LinkedHashSet<>(), trace);
			default -> Optional.empty();
		};
		if (candidate.isPresent()) {
			return ActionResolveResult.success(candidate.get().route(), trace);
		}
		return ActionResolveResult.failure("no_route", "provider has no route", trace);
	}

	public ActionResolver(ActionsetIndex index, ActionFactStore facts, ActionResolverContext context) {
		this(index, facts, BlockAcquisitionIndex.empty(), context, ActionResolutionRequest.DEFAULT_MAX_DEPTH);
	}

	public ActionResolver(
		ActionsetIndex index,
		ActionFactStore facts,
		BlockAcquisitionIndex blockAcquisitions,
		ActionResolverContext context
	) {
		this(index, facts, blockAcquisitions, context, ActionResolutionRequest.DEFAULT_MAX_DEPTH);
	}

	public ActionResolver(ActionsetIndex index, ActionFactStore facts, ActionResolverContext context, int maxDepth) {
		this(index, facts, BlockAcquisitionIndex.empty(), context, maxDepth);
	}

	private ActionResolver(
		ActionsetIndex index,
		ActionFactStore facts,
		BlockAcquisitionIndex blockAcquisitions,
		ActionResolverContext context,
		int maxDepth
	) {
		this(
			index,
			facts,
			blockAcquisitions,
			NearbyBlockAvailability.unknown(),
			context,
			maxDepth,
			Set.of(),
			false,
			ActionResolutionRequest.DEFAULT_EXPLORATION_BUDGET
		);
	}

	public ActionResolver(
		ActionsetIndex index,
		ActionFactStore facts,
		ActionResolverContext context,
		int maxDepth,
		Set<String> blockedAlternativeKeys
	) {
		this(
			index,
			facts,
			BlockAcquisitionIndex.empty(),
			NearbyBlockAvailability.unknown(),
			context,
			maxDepth,
			blockedAlternativeKeys,
			false,
			ActionResolutionRequest.DEFAULT_EXPLORATION_BUDGET
		);
	}

	public ActionResolver(
		ActionsetIndex index,
		ActionFactStore facts,
		ActionResolverContext context,
		int maxDepth,
		Set<String> blockedAlternativeKeys,
		boolean preferActionsetRoutes
	) {
		this(
			index,
			facts,
			BlockAcquisitionIndex.empty(),
			NearbyBlockAvailability.unknown(),
			context,
			maxDepth,
			blockedAlternativeKeys,
			preferActionsetRoutes,
			ActionResolutionRequest.DEFAULT_EXPLORATION_BUDGET
		);
	}

	private ActionResolver(
		ActionsetIndex index,
		ActionFactStore facts,
		BlockAcquisitionIndex blockAcquisitions,
		NearbyBlockAvailability nearbyBlockAvailability,
		ActionResolverContext context,
		int maxDepth,
		Set<String> blockedAlternativeKeys,
		boolean preferActionsetRoutes,
		int explorationBudget
	) {
		this.index = Objects.requireNonNull(index, "index");
		this.facts = Objects.requireNonNull(facts, "facts");
		this.blockAcquisitions = blockAcquisitions == null ? BlockAcquisitionIndex.empty() : blockAcquisitions;
		this.nearbyBlockAvailability = nearbyBlockAvailability == null ? NearbyBlockAvailability.unknown() : nearbyBlockAvailability;
		this.context = Objects.requireNonNull(context, "context");
		this.maxDepth = Math.max(1, maxDepth);
		this.blockedAlternativeKeys = blockedAlternativeKeys == null ? Set.of() : Set.copyOf(blockedAlternativeKeys);
		this.preferActionsetRoutes = preferActionsetRoutes;
		this.explorationBudget = Math.max(1, explorationBudget);
		this.craftRecipesByOutput = providerFactsByOutput(ActionFactType.CRAFT_RECIPE, "outputItemId", "recipeId");
		this.smeltRecipesByOutput = providerFactsByOutput(ActionFactType.SMELT_RECIPE, "outputItemId", "optionId");
	}

	public ActionResolveResult resolve(ActionGoal goal) {
		ArrayList<ActionTraceEvent> trace = new ArrayList<>();
		Optional<ActionRoute> route = resolveGoal(goal, 0, new LinkedHashSet<>(), trace);
		trace.add(event("resolution_stats", "", "", "", Map.of(
			"expandedGoals", expandedGoals,
			"routeCacheHits", routeCacheHits,
			"explorationBudget", explorationBudget
		)));
		if (budgetExceeded) {
			trace.add(event("goal_failed", "", "", "", Map.of("goal", goal.normalizedKey(), "failureCode", "resolution_budget_exceeded")));
			return ActionResolveResult.failure(
				"resolution_budget_exceeded",
				"route resolution exceeded its deterministic exploration budget of " + explorationBudget,
				trace
			);
		}
		if (route.isPresent()) {
			trace.add(event("goal_succeeded", "", "", "", Map.of("goal", goal.normalizedKey())));
			return ActionResolveResult.success(route.get(), trace);
		}
		ResolutionFailure failure = classifyUnresolvedGoal(goal);
		LinkedHashMap<String, Object> failurePayload = new LinkedHashMap<>(failure.payload());
		failurePayload.put("goal", goal.normalizedKey());
		failurePayload.put("failureCode", failure.code());
		trace.add(event("goal_failed", "", "", "", failurePayload));
		return ActionResolveResult.failure(failure.code(), failure.message(), trace);
	}

	private ResolutionFailure classifyUnresolvedGoal(ActionGoal goal) {
		if (goal.factType() == ActionFactType.INVENTORY_ITEM) {
			String itemId = goal.keys().getOrDefault("itemId", "");
			if (!itemId.isBlank() && !hasKnownInventoryAcquisitionMethod(goal, itemId)) {
				return new ResolutionFailure(
					"unknown_acquisition_method",
					"no registered acquisition method for inventory item " + itemId + "; no target search was started",
					Map.of("itemId", itemId, "searchStarted", false)
				);
			}
		}
		if (goal.factType() == ActionFactType.INVENTORY_RESOURCE) {
			String resourceKind = goal.keys().getOrDefault("resourceKind", "");
			if (ResourceGatheringCatalog.entry(resourceKind).isEmpty()) {
				return new ResolutionFailure(
					"unsupported_resource_kind",
					"unsupported resource kind " + resourceKind + "; no target search was started",
					Map.of("resourceKind", resourceKind, "searchStarted", false)
				);
			}
		}
		return new ResolutionFailure(
			"no_route",
			"no route can satisfy " + goal.normalizedKey(),
			Map.of("searchStarted", false)
		);
	}

	private boolean hasKnownInventoryAcquisitionMethod(ActionGoal goal, String itemId) {
		return !matchingActionsets(goal).isEmpty()
			|| !blockAcquisitions.rulesForOutput(itemId).isEmpty()
			|| craftRecipesByOutput.containsKey(itemId)
			|| smeltRecipesByOutput.containsKey(itemId);
	}

	private record ResolutionFailure(String code, String message, Map<String, Object> payload) {
		private ResolutionFailure {
			payload = payload == null ? Map.of() : Map.copyOf(payload);
		}
	}

	private Optional<ActionRoute> resolveGoal(
		ActionGoal goal,
		int depth,
		LinkedHashSet<String> resolving,
		List<ActionTraceEvent> trace
	) {
		if (advisoryContext != null) {
			return advisoryContext.resolve(AiricraftPlanConversions.toGoal(goal))
				.map(AiricraftPlanConversions::toActionRoute);
		}
		trace.add(event("goal_started", "", "", "", Map.of("goal", goal.normalizedKey(), "depth", depth)));
		if (++expandedGoals > explorationBudget) {
			budgetExceeded = true;
			trace.add(event("goal_failed", "", "", "", Map.of("goal", goal.normalizedKey(), "failureCode", "resolution_budget_exceeded")));
			return Optional.empty();
		}
		if (depth > maxDepth) {
			trace.add(event("goal_failed", "", "", "", Map.of("goal", goal.normalizedKey(), "failureCode", "max_depth_exceeded")));
			return Optional.empty();
		}
		String goalKey = goal.normalizedKey();
		if (resolving.contains(goalKey)) {
			trace.add(event("goal_failed", "", "", "", Map.of("goal", goal.normalizedKey(), "failureCode", "cycle_detected")));
			return Optional.empty();
		}
		ActionRoute cached = successfulRoutes.get(goalKey);
		if (cached != null) {
			routeCacheHits++;
			trace.add(event("route_cache_hit", "", "", "", Map.of("goal", goalKey, "cost", cached.cost())));
			return Optional.of(cached);
		}
		resolving.add(goalKey);
		try {
			Optional<ActionRoute> route;
			if (goalSatisfied(goal, trace)) {
				route = Optional.of(ActionRoute.empty());
			}
			else {
				route = preferActionsetRoutes
					? resolveActionsetGoal(goal, depth, resolving, trace)
					: resolveDomainProviderGoal(goal, depth, resolving, trace);
				if (route.isEmpty()) {
					route = preferActionsetRoutes
						? resolveDomainProviderGoal(goal, depth, resolving, trace)
						: resolveActionsetGoal(goal, depth, resolving, trace);
				}
			}
			route.ifPresent(resolved -> successfulRoutes.put(goalKey, resolved));
			return route;
		}
		finally {
			resolving.remove(goalKey);
		}
	}

	private Optional<ActionRoute> resolveDomainProviderGoal(
		ActionGoal goal,
		int depth,
		LinkedHashSet<String> resolving,
		List<ActionTraceEvent> trace
	) {
		ArrayList<ProviderCandidate> candidates = new ArrayList<>();
		for (ProviderResolver resolver : List.<ProviderResolver>of(
			this::resolveResourceProviderGoal,
			this::resolveRecipeProviderGoal,
			this::resolveSmeltingProviderGoal,
			this::resolveMiningProviderGoal
		)) {
			resolver.resolve(goal, depth, resolving, trace).ifPresent(candidates::add);
			if (budgetExceeded) {
				return Optional.empty();
			}
		}
		ProviderCandidate selected = candidates.stream()
			.min(Comparator
				.comparingInt((ProviderCandidate candidate) -> candidate.route().cost())
				.thenComparingInt(ProviderCandidate::rank)
				.thenComparing(ProviderCandidate::actionId)
				.thenComparing(ProviderCandidate::alternativeId))
			.orElse(null);
		if (selected == null) {
			return Optional.empty();
		}
		trace.add(event(
			"route_selected",
			selected.actionId(),
			selected.alternativeId(),
			"",
			Map.of("goal", goal.normalizedKey(), "cost", selected.route().cost())
		));
		return Optional.of(selected.route());
	}

	private Optional<ActionRoute> resolveActionsetGoal(
		ActionGoal goal,
		int depth,
		LinkedHashSet<String> resolving,
		List<ActionTraceEvent> trace
	) {
		for (ActionsetEntry entry : matchingActionsets(goal)) {
			Map<String, Integer> params = bindParams(entry.definition(), goal);
			for (Map<String, Object> alternative : alternatives(entry)) {
				String alternativeId = scalar(alternative.get("id"), "<unnamed>");
				if (blockedAlternativeKeys.contains(entry.actionId() + ":" + alternativeId)) {
					trace.add(event(
						"route_candidate_blocked",
						entry.actionId(),
						alternativeId,
						"",
						Map.of("goal", goal.normalizedKey(), "reason", "previous_failure")
					));
					continue;
				}
				trace.add(event(
					"route_candidate_built",
					entry.actionId(),
					alternativeId,
					"",
					Map.of("goal", goal.normalizedKey(), "cost", intrinsicAlternativeCost(alternative))
				));

				List<ActionFact> matchedGuards = matchedGuards(alternative, params, trace, entry.actionId(), alternativeId);
				if (matchedGuards == null) {
					continue;
				}

				Optional<ActionRoute> expanded = expandAlternative(entry, alternative, params, matchedGuards, depth, resolving, trace);
				if (expanded.isPresent()) {
					trace.add(event("route_selected", entry.actionId(), alternativeId, "", Map.of("goal", goal.normalizedKey())));
					return expanded;
				}
			}
		}
		return Optional.empty();
	}

	private Optional<ProviderCandidate> resolveResourceProviderGoal(
		ActionGoal goal,
		int depth,
		LinkedHashSet<String> resolving,
		List<ActionTraceEvent> trace
	) {
		if (goal.factType() != ActionFactType.INVENTORY_RESOURCE) {
			return Optional.empty();
		}
		String resourceKind = goal.keys().getOrDefault("resourceKind", "");
		Optional<ResourceGatheringCatalog.ResourceEntry> entry = ResourceGatheringCatalog.entry(resourceKind);
		if (entry.isEmpty()) {
			trace.add(event(
				"route_candidate_rejected",
				"resource_provider",
				resourceKind,
				"",
				Map.of("goal", goal.normalizedKey(), "reason", "unsupported_resource_kind")
			));
			return Optional.empty();
		}
		String alternativeKey = "resource_provider:" + resourceKind;
		if (blockedAlternativeKeys.contains(alternativeKey)) {
			trace.add(event(
				"route_candidate_blocked",
				"resource_provider",
				resourceKind,
				"",
				Map.of("goal", goal.normalizedKey(), "reason", "previous_failure")
			));
			return Optional.empty();
		}
		int targetCount = goal.minimum("countAtLeast", 1);
		int deficitCount = Math.max(0, targetCount - existingGoalCount(goal));
		if (deficitCount <= 0) {
			return Optional.of(new ProviderCandidate(ActionRoute.empty(), "resource_provider", resourceKind, PROVIDER_RANK_RESOURCE));
		}
		if (!entry.get().aggregate()) {
			String itemId = entry.get().primaryItemId();
			if (itemId.isBlank()) {
				return Optional.empty();
			}
			trace.add(event(
				"route_candidate_built",
				"resource_provider",
				resourceKind,
				"",
				Map.of("goal", goal.normalizedKey(), "cost", 35, "resourceKind", resourceKind, "itemId", itemId)
			));
			Optional<ActionRoute> itemRoute = resolveGoal(ActionGoal.inventoryItem(itemId, targetCount), depth + 1, resolving, trace);
			if (itemRoute.isEmpty()) {
				trace.add(event(
					"route_candidate_rejected",
					"resource_provider",
					resourceKind,
					"",
					Map.of("goal", goal.normalizedKey(), "reason", "item_route_unavailable", "itemId", itemId)
				));
				return Optional.empty();
			}
			return Optional.of(new ProviderCandidate(itemRoute.get(), "resource_provider", resourceKind, PROVIDER_RANK_RESOURCE));
		}
		int resourceCost = estimateAggregateResourceCost(entry.get(), deficitCount);
		trace.add(event(
			"route_candidate_built",
			"resource_provider",
			resourceKind,
			"",
			Map.of("goal", goal.normalizedKey(), "cost", resourceCost, "resourceKind", resourceKind)
		));
		LinkedHashMap<String, Object> args = new LinkedHashMap<>();
		args.put("resourceKind", resourceKind);
		args.put("quantity", deficitCount);
		ActionPlanStep step = new ActionPlanStep(ActionStepKind.PRIMITIVE, "resource_provider", resourceKind, "collect_resource", "collect_resource", args);
		trace.add(event("primitive_planned", "resource_provider", resourceKind, "collect_resource", Map.of("primitive", "collect_resource", "resourceKind", resourceKind)));
		return Optional.of(new ProviderCandidate(
			new ActionRoute(List.of(step), resourceCost),
			"resource_provider",
			resourceKind,
			PROVIDER_RANK_RESOURCE
		));
	}

	private Optional<ProviderCandidate> resolveRecipeProviderGoal(
		ActionGoal goal,
		int depth,
		LinkedHashSet<String> resolving,
		List<ActionTraceEvent> trace
	) {
		if (goal.factType() != ActionFactType.INVENTORY_ITEM) {
			return Optional.empty();
		}
		String outputItemId = goal.keys().getOrDefault("itemId", "");
		if (outputItemId.isBlank()) {
			return Optional.empty();
		}
		int targetCount = goal.minimum("countAtLeast", 1);
		int deficitCount = Math.max(0, targetCount - existingGoalCount(goal));
		if (deficitCount <= 0) {
			return Optional.of(new ProviderCandidate(ActionRoute.empty(), "recipe_provider", outputItemId, PROVIDER_RANK_RECIPE));
		}

		ActionRoute bestRoute = null;
		String bestRecipeId = "";
		for (ActionFact recipe : craftRecipesByOutput.getOrDefault(outputItemId, List.of())) {
			String recipeId = recipe.identity().keys().getOrDefault("recipeId", "");
			String alternativeKey = "recipe_provider:" + recipeId;
			if (blockedAlternativeKeys.contains(alternativeKey)) {
				trace.add(event(
					"route_candidate_blocked",
					"recipe_provider",
					recipeId,
					"",
					Map.of("goal", goal.normalizedKey(), "reason", "previous_failure")
				));
				continue;
			}
			Map<String, Integer> inputCounts = recipeInputCounts(recipe);
			if (inputCounts.isEmpty()) {
				continue;
			}
			int outputCount = Math.max(1, intPayload(recipe, "outputCount", 1));
			int craftTimes = Math.max(1, (int) Math.ceil(deficitCount / (double) outputCount));
			String gridKind = scalar(recipe.payload().get("gridKind"), "");
			Map<String, Integer> effectiveInputCounts = effectiveRecipeInputCounts(outputItemId, inputCounts, gridKind);
			ArrayList<ActionPlanStep> steps = new ArrayList<>();
			int routeCost = 0;
			boolean inputsResolved = true;
			for (Map.Entry<String, Integer> input : effectiveInputCounts.entrySet()) {
				int requiredCount = input.getValue() * craftTimes;
				if (requiredCount <= 0) {
					continue;
				}
				Optional<ActionRoute> subRoute = resolveRecipeInputGoal(input.getKey(), requiredCount, depth, resolving, trace);
				if (subRoute.isEmpty()) {
					inputsResolved = false;
					break;
				}
				steps.addAll(subRoute.get().steps());
				routeCost = saturatingAdd(routeCost, subRoute.get().cost());
			}
			if (!inputsResolved) {
				continue;
			}

			LinkedHashMap<String, Object> args = new LinkedHashMap<>();
			args.put("itemId", outputItemId);
			args.put("recipeId", recipeId);
			args.put("quantity", deficitCount);
			steps.add(new ActionPlanStep(ActionStepKind.PRIMITIVE, "recipe_provider", recipeId, "craft_item", "craft_item", args));
			trace.add(event("primitive_planned", "recipe_provider", recipeId, "craft_item", Map.of("primitive", "craft_item", "itemId", outputItemId)));
			ActionRoute candidateRoute = new ActionRoute(steps, routeCost);
			trace.add(event(
				"route_candidate_built",
				"recipe_provider",
				recipeId,
				"",
				Map.of(
					"goal", goal.normalizedKey(),
					"cost", routeCost,
					"outputItemId", outputItemId,
					"intrinsicCost", 0
				)
			));
			if (bestRoute == null || candidateRoute.cost() < bestRoute.cost()) {
				bestRoute = candidateRoute;
				bestRecipeId = recipeId;
			}
		}

		if (bestRoute == null) {
			return Optional.empty();
		}
		return Optional.of(new ProviderCandidate(bestRoute, "recipe_provider", bestRecipeId, PROVIDER_RANK_RECIPE));
	}

	private Map<String, Integer> effectiveRecipeInputCounts(
		String outputItemId,
		Map<String, Integer> inputCounts,
		String gridKind
	) {
		if (!"WORKBENCH_3X3".equals(gridKind)
			|| "minecraft:crafting_table".equals(outputItemId)
			|| existingGoalCount(ActionGoal.inventoryItem("minecraft:crafting_table", 1)) >= 1) {
			return inputCounts;
		}
		LinkedHashMap<String, Integer> effective = new LinkedHashMap<>(inputCounts);
		effective.merge(workbenchSetupPlankItemId(inputCounts), 4, Integer::sum);
		return effective;
	}

	private String workbenchSetupPlankItemId(Map<String, Integer> inputCounts) {
		for (String plankItemId : ActionGraphDomainKnowledge.plankItemIds()) {
			if (inputCounts.containsKey(plankItemId)) {
				return plankItemId;
			}
		}
		for (String plankItemId : ActionGraphDomainKnowledge.plankItemIds()) {
			int count = existingGoalCount(ActionGoal.inventoryItem(plankItemId, 1));
			if (count >= 4) {
				return plankItemId;
			}
		}
		String bestObservedLogPlank = "";
		int bestObservedLogCount = 0;
		for (int index = 0; index < ActionGraphDomainKnowledge.logItemIds().size(); index++) {
			String logItemId = ActionGraphDomainKnowledge.logItemIds().get(index);
			int count = existingGoalCount(ActionGoal.inventoryItem(logItemId, 1));
			if (count > bestObservedLogCount && index < ActionGraphDomainKnowledge.plankItemIds().size()) {
				bestObservedLogPlank = ActionGraphDomainKnowledge.plankItemIds().get(index);
				bestObservedLogCount = count;
			}
		}
		if (!bestObservedLogPlank.isBlank()) {
			return bestObservedLogPlank;
		}
		String bestObservedPlank = "";
		int bestObservedPlankCount = 0;
		for (String plankItemId : ActionGraphDomainKnowledge.plankItemIds()) {
			int count = existingGoalCount(ActionGoal.inventoryItem(plankItemId, 1));
			if (count > bestObservedPlankCount) {
				bestObservedPlank = plankItemId;
				bestObservedPlankCount = count;
			}
		}
		if (!bestObservedPlank.isBlank()) {
			return bestObservedPlank;
		}
		return "minecraft:oak_planks";
	}

	private Optional<ActionRoute> resolveRecipeInputGoal(
		String itemId,
		int requiredCount,
		int depth,
		LinkedHashSet<String> resolving,
		List<ActionTraceEvent> trace
	) {
		if (ActionGraphDomainKnowledge.plankItemIds().contains(itemId)) {
			return resolveRecipePlankInputGoal(itemId, requiredCount, depth, resolving, trace);
		}
		if (!ActionGraphDomainKnowledge.logItemIds().contains(itemId)) {
			return resolveGoal(
				ActionGoal.inventoryItem(itemId, requiredCount),
				depth + 1,
				resolving,
				trace
			);
		}
		int exactCount = existingGoalCount(ActionGoal.inventoryItem(itemId, requiredCount));
		if (exactCount >= requiredCount) {
			return Optional.of(ActionRoute.empty());
		}
		int totalWoodLogs = existingGoalCount(ActionGoal.resourceCollection("WOOD_LOGS", 1));
		if (exactCount <= 0 && totalWoodLogs > 0) {
			trace.add(event(
				"route_candidate_blocked",
				"resource_provider",
				itemId,
				"",
				Map.of("goal", ActionGoal.inventoryItem(itemId, requiredCount).normalizedKey(), "reason", "missing_observed_log_variant")
			));
			return Optional.empty();
		}
		int missingExactLogs = requiredCount - exactCount;
		return resolveGoal(
			ActionGoal.resourceCollection("WOOD_LOGS", totalWoodLogs + missingExactLogs),
			depth + 1,
			resolving,
			trace
		);
	}

	private Optional<ActionRoute> resolveRecipePlankInputGoal(
		String itemId,
		int requiredCount,
		int depth,
		LinkedHashSet<String> resolving,
		List<ActionTraceEvent> trace
	) {
		int exactCount = existingGoalCount(ActionGoal.inventoryItem(itemId, requiredCount));
		if (exactCount >= requiredCount) {
			return Optional.of(ActionRoute.empty());
		}
		String logItemId = logItemForPlank(itemId);
		int logCount = logItemId.isBlank() ? 0 : existingGoalCount(ActionGoal.inventoryItem(logItemId, 1));
		if (logCount > 0) {
			return resolveGoal(
				ActionGoal.inventoryItem(itemId, requiredCount),
				depth + 1,
				resolving,
				trace
			);
		}
		int missingLogs = Math.max(1, (int) Math.ceil((requiredCount - exactCount) / 4.0));
		if (exactCount > 0 || !observedAnyWoodMaterial()) {
			int totalWoodLogs = existingGoalCount(ActionGoal.resourceCollection("WOOD_LOGS", 1));
			return resolveGoal(
				ActionGoal.resourceCollection("WOOD_LOGS", totalWoodLogs + missingLogs),
				depth + 1,
				resolving,
				trace
			);
		}
		trace.add(event(
			"route_candidate_blocked",
			"recipe_provider",
			itemId,
			"",
			Map.of("goal", ActionGoal.inventoryItem(itemId, requiredCount).normalizedKey(), "reason", "missing_observed_plank_variant")
		));
		return Optional.empty();
	}

	private static String logItemForPlank(String plankItemId) {
		int index = ActionGraphDomainKnowledge.plankItemIds().indexOf(plankItemId);
		if (index < 0 || index >= ActionGraphDomainKnowledge.logItemIds().size()) {
			return "";
		}
		return ActionGraphDomainKnowledge.logItemIds().get(index);
	}

	private boolean observedAnyWoodMaterial() {
		if (existingGoalCount(ActionGoal.resourceCollection("WOOD_LOGS", 1)) > 0) {
			return true;
		}
		for (String logItemId : ActionGraphDomainKnowledge.logItemIds()) {
			if (existingGoalCount(ActionGoal.inventoryItem(logItemId, 1)) > 0) {
				return true;
			}
		}
		for (String plankItemId : ActionGraphDomainKnowledge.plankItemIds()) {
			if (existingGoalCount(ActionGoal.inventoryItem(plankItemId, 1)) > 0) {
				return true;
			}
		}
		return false;
	}

	private Optional<ProviderCandidate> resolveMiningProviderGoal(
		ActionGoal goal,
		int depth,
		LinkedHashSet<String> resolving,
		List<ActionTraceEvent> trace
	) {
		if (goal.factType() != ActionFactType.INVENTORY_ITEM) {
			return Optional.empty();
		}
		String itemId = goal.keys().getOrDefault("itemId", "");
		if (itemId.isBlank()) {
			return Optional.empty();
		}
		int targetCount = goal.minimum("countAtLeast", 1);
		int deficitCount = Math.max(0, targetCount - existingGoalCount(goal));
		if (deficitCount <= 0) {
			return Optional.of(new ProviderCandidate(ActionRoute.empty(), "mining_provider", itemId, PROVIDER_RANK_MINING));
		}
		List<BlockAcquisitionRule> acquisitionRules = blockAcquisitions.rulesForOutput(itemId);
		if (acquisitionRules.isEmpty()) {
			return Optional.empty();
		}
		String alternativeKey = "mining_provider:" + itemId;
		if (blockedAlternativeKeys.contains(alternativeKey)) {
			trace.add(event(
				"route_candidate_blocked",
				"mining_provider",
				itemId,
				"",
				Map.of("goal", goal.normalizedKey(), "reason", "previous_failure")
			));
			return Optional.empty();
		}

		ActionRoute bestRoute = null;
		String bestOptionId = "";
		for (BlockAcquisitionRule rule : acquisitionRules.stream()
			.sorted(Comparator
				.comparing(BlockAcquisitionRule::blockId)
				.thenComparing(BlockAcquisitionRule::lootTableId))
			.toList()) {
			if (!rule.dropEstimateKnown() || rule.expectedDropsPerBreak() <= 0.0) {
				trace.add(event(
					"route_candidate_rejected",
					"mining_provider",
					rule.blockId(),
					"",
					Map.of("goal", goal.normalizedKey(), "reason", "unknown_drop_estimate", "itemId", itemId)
				));
				continue;
			}
			int expectedBreakCount = expectedBreakCount(deficitCount, rule.expectedDropsPerBreak());
			int availabilityPenalty = availabilityPenalty(rule, expectedBreakCount);
			MiningToolPlan toolPlan = resolveMiningToolPlan(
				rule,
				expectedBreakCount,
				availabilityPenalty,
				depth,
				resolving,
				trace,
				goal,
				itemId
			);
			if (!toolPlan.available()) {
				continue;
			}
			List<String> blockIds = List.of(rule.blockId());
			String optionId = rule.blockId() + ":" + toolPlan.normalizedToolKey();
			int routeCost = saturatingAdd(toolPlan.route().cost(), toolPlan.miningWorkCost());
			ArrayList<ActionPlanStep> steps = new ArrayList<>(toolPlan.route().steps());
			LinkedHashMap<String, Object> args = new LinkedHashMap<>();
			args.put("itemId", itemId);
			args.put("blockIds", blockIds);
			args.put("matchingItemIds", List.of(itemId));
			args.put("requiredToolItemIds", toolPlan.requiredToolItemIds());
			args.put("quantity", deficitCount);
			args.put("targetCount", targetCount);
			args.put("estimatedBreakCount", expectedBreakCount);
			steps.add(new ActionPlanStep(ActionStepKind.PRIMITIVE, "mining_provider", optionId, "mine_block", "mine_block", args));
			ActionRoute candidate = new ActionRoute(List.copyOf(steps), routeCost);
			trace.add(event(
				"route_candidate_built",
				"mining_provider",
				optionId,
				"",
				Map.ofEntries(
					Map.entry("goal", goal.normalizedKey()),
					Map.entry("cost", routeCost),
					Map.entry("itemId", itemId),
					Map.entry("blockIds", blockIds),
					Map.entry("requiredToolItemIds", args.get("requiredToolItemIds")),
					Map.entry("dropProbability", rule.dropProbability()),
					Map.entry("expectedDropsPerBreak", rule.expectedDropsPerBreak()),
					Map.entry("expectedBreakCount", expectedBreakCount),
					Map.entry("breakTicks", toolPlan.breakTicks()),
					Map.entry("availabilityObserved", nearbyBlockAvailability.observed()),
					Map.entry("nearbyBlockCount", nearbyBlockAvailability.count(rule.blockId())),
					Map.entry("availabilityPenalty", availabilityPenalty),
					Map.entry("baseMiningCost", MiningCostModel.BASE_MINING_COST),
					Map.entry("breakTickCost", MiningCostModel.breakTickCost(toolPlan.breakTicks())),
					Map.entry("miningWorkCost", toolPlan.miningWorkCost())
				)
			));
			if (bestRoute == null
				|| candidate.cost() < bestRoute.cost()
				|| (candidate.cost() == bestRoute.cost() && optionId.compareTo(bestOptionId) < 0)) {
				bestRoute = candidate;
				bestOptionId = optionId;
			}
		}
		if (bestRoute == null) {
			return Optional.empty();
		}
		ActionPlanStep selected = bestRoute.steps().getLast();
		trace.add(event("primitive_planned", "mining_provider", bestOptionId, "mine_block", Map.of(
			"primitive", "mine_block",
			"itemId", itemId,
			"blockIds", selected.args().get("blockIds"),
			"requiredToolItemIds", selected.args().get("requiredToolItemIds")
		)));
		return Optional.of(new ProviderCandidate(bestRoute, "mining_provider", bestOptionId, PROVIDER_RANK_MINING));
	}

	private MiningToolPlan resolveMiningToolPlan(
		BlockAcquisitionRule rule,
		int expectedBreakCount,
		int availabilityPenalty,
		int depth,
		LinkedHashSet<String> resolving,
		List<ActionTraceEvent> trace,
		ActionGoal goal,
		String outputItemId
	) {
		MiningToolPlan bestPlan = null;
		if (rule.emptyHandAllowed()) {
			int handTicks = normalizedBreakTicks(rule.emptyHandBreakTicks());
			bestPlan = MiningToolPlan.available(
				ActionRoute.empty(),
				List.of(),
				handTicks,
				MiningCostModel.workCost(handTicks, expectedBreakCount, availabilityPenalty)
			);
			for (Map.Entry<String, Integer> tool : rule.breakTicksByToolItemId().entrySet()) {
				if (existingGoalCount(ActionGoal.inventoryItem(tool.getKey(), 1)) < 1) {
					continue;
				}
				int breakTicks = normalizedBreakTicks(tool.getValue());
				MiningToolPlan candidate = MiningToolPlan.available(
					ActionRoute.empty(),
					List.of(tool.getKey()),
					breakTicks,
					MiningCostModel.workCost(breakTicks, expectedBreakCount, availabilityPenalty)
				);
				bestPlan = cheaperMiningToolPlan(bestPlan, candidate);
			}
		}
		else {
			for (String toolItemId : rule.usableToolItemIds()) {
				ActionRoute toolRoute;
				if (existingGoalCount(ActionGoal.inventoryItem(toolItemId, 1)) >= 1) {
					toolRoute = ActionRoute.empty();
				}
				else {
					Optional<ActionRoute> candidate = resolveGoal(
						ActionGoal.inventoryItem(toolItemId, 1),
						depth + 1,
						resolving,
						trace
					);
					if (candidate.isEmpty()) {
						continue;
					}
					toolRoute = candidate.get();
				}
				int breakTicks = normalizedBreakTicks(rule.breakTicksByToolItemId().getOrDefault(toolItemId, rule.emptyHandBreakTicks()));
				MiningToolPlan candidate = MiningToolPlan.available(
					toolRoute,
					List.of(toolItemId),
					breakTicks,
					MiningCostModel.workCost(breakTicks, expectedBreakCount, availabilityPenalty)
				);
				bestPlan = cheaperMiningToolPlan(bestPlan, candidate);
			}
		}
		if (bestPlan != null) {
			return bestPlan;
		}
		trace.add(event(
			"route_candidate_rejected",
			"mining_provider",
			outputItemId,
			"",
			Map.of(
				"goal", goal.normalizedKey(),
				"reason", "missing_tool_prerequisite",
				"toolItemIds", rule.usableToolItemIds()
			)
		));
		return MiningToolPlan.unavailable();
	}

	private Optional<ProviderCandidate> resolveSmeltingProviderGoal(
		ActionGoal goal,
		int depth,
		LinkedHashSet<String> resolving,
		List<ActionTraceEvent> trace
	) {
		if (goal.factType() != ActionFactType.INVENTORY_ITEM) {
			return Optional.empty();
		}
		String outputItemId = goal.keys().getOrDefault("itemId", "");
		if (outputItemId.isBlank()) {
			return Optional.empty();
		}
		int targetCount = goal.minimum("countAtLeast", 1);
		int deficitCount = Math.max(0, targetCount - existingGoalCount(goal));
		if (deficitCount <= 0) {
			return Optional.of(new ProviderCandidate(ActionRoute.empty(), "smelting_provider", outputItemId, PROVIDER_RANK_SMELTING));
		}

		ActionRoute bestRoute = null;
		String bestOptionId = "";
		int bestProvenanceRank = Integer.MAX_VALUE;
		for (ActionFact recipe : smeltRecipesByOutput.getOrDefault(outputItemId, List.of())) {
			String optionId = recipe.identity().keys().getOrDefault("optionId", "");
			String alternativeKey = "smelting_provider:" + optionId;
			if (blockedAlternativeKeys.contains(alternativeKey)) {
				trace.add(event(
					"route_candidate_blocked",
					"smelting_provider",
					optionId,
					"",
					Map.of("goal", goal.normalizedKey(), "reason", "previous_failure")
				));
				continue;
			}
			String inputItemId = scalar(recipe.payload().get("inputItemId"), "");
			if (inputItemId.isBlank()) {
				continue;
			}
			int outputCount = Math.max(1, intPayload(recipe, "outputCount", 1));
			int inputQuantity = Math.max(1, (int) Math.ceil(deficitCount / (double) outputCount));
			int maxInputQuantity = Math.max(1, intPayload(recipe, "maxInputQuantity", inputQuantity));
			if (inputQuantity > maxInputQuantity) {
				continue;
			}
			ArrayList<ActionPlanStep> steps = new ArrayList<>();
			int routeCost = 25;
			String stationItemId = scalar(recipe.payload().get("stationItemId"), "");
			int stationItemCount = intPayload(recipe, "stationItemCount", 0);
			if (!stationItemId.isBlank() && stationItemCount > 0) {
				Optional<ActionRoute> stationRoute = resolveGoal(
					ActionGoal.inventoryItem(stationItemId, stationItemCount),
					depth + 1,
					resolving,
					trace
				);
				if (stationRoute.isEmpty()) {
					continue;
				}
				steps.addAll(stationRoute.get().steps());
				routeCost = saturatingAdd(routeCost, stationRoute.get().cost());
			}

			Optional<ActionRoute> inputRoute = resolveGoal(
				ActionGoal.inventoryItem(inputItemId, inputQuantity),
				depth + 1,
				resolving,
				trace
			);
			if (inputRoute.isEmpty()) {
				continue;
			}

			steps.addAll(inputRoute.get().steps());
			routeCost = saturatingAdd(routeCost, inputRoute.get().cost());

			int cookTimeTicks = intPayload(recipe, "cookTimeTicks", DEFAULT_SMELT_COOK_TICKS);
			Optional<FuelPlan> fuelPlan = resolveSmeltingFuel(
				inputQuantity,
				cookTimeTicks,
				depth,
				resolving,
				trace,
				goal
			);
			if (fuelPlan.isPresent()) {
				steps.addAll(fuelPlan.get().route().steps());
				routeCost = saturatingAdd(routeCost, fuelPlan.get().route().cost());
			}
			else {
				trace.add(event(
					"route_candidate_rejected",
					"smelting_provider",
					optionId,
					"",
					Map.of("goal", goal.normalizedKey(), "reason", "missing_fuel_subgoal")
				));
				continue;
			}

			LinkedHashMap<String, Object> smeltArgs = new LinkedHashMap<>();
			smeltArgs.put("itemId", outputItemId);
			smeltArgs.put("inputItemId", inputItemId);
			smeltArgs.put("optionId", optionId);
			smeltArgs.put("quantity", deficitCount);
			smeltArgs.put("inputQuantity", inputQuantity);
			fuelPlan.ifPresent(plan -> {
				smeltArgs.put("fuelItemId", plan.itemId());
				smeltArgs.put("fuelQuantity", plan.quantity());
			});
			steps.add(new ActionPlanStep(ActionStepKind.PRIMITIVE, "smelting_provider", optionId, "smelt_item", "smelt_item", smeltArgs));
			trace.add(event("primitive_planned", "smelting_provider", optionId, "smelt_item", Map.of(
				"primitive", "smelt_item",
				"itemId", outputItemId,
				"inputItemId", inputItemId
			)));

			ActionWatchSpec readyWatch = new ActionWatchSpec(
				new ActionFactCondition(
					ActionFactType.SMELTING_PROCESS,
					Map.of(
						"worldId", context.worldId(),
						"actorId", context.actorId(),
						"optionId", optionId,
						"itemId", outputItemId
					),
					Map.of("ready", 1)
				),
				null,
				Math.max(1200L, (long) cookTimeTicks * inputQuantity + 1200L),
				ActionWatchProgressKind.AREA_TICKING,
				null
			);
			Map<String, Object> watchArgs = Map.of(
				"fact", ActionFactType.SMELTING_PROCESS.id(),
				"optionId", optionId,
				"itemId", outputItemId,
				"readyAtLeast", 1,
				"timeoutTicks", readyWatch.timeoutTicks()
			);
			steps.add(new ActionPlanStep(
				ActionStepKind.WATCH,
				"smelting_provider",
				optionId,
				"wait_for_smelted_item",
				"watch",
				watchArgs,
				readyWatch
			));
			trace.add(event("watch_planned", "smelting_provider", optionId, "wait_for_smelted_item", watchArgs));

			LinkedHashMap<String, Object> collectArgs = new LinkedHashMap<>();
			collectArgs.put("itemId", outputItemId);
			collectArgs.put("quantity", deficitCount);
			steps.add(new ActionPlanStep(ActionStepKind.PRIMITIVE, "smelting_provider", optionId, "collect_smelted_item", "collect_smelted_item", collectArgs));
			trace.add(event("primitive_planned", "smelting_provider", optionId, "collect_smelted_item", Map.of(
				"primitive", "collect_smelted_item",
				"itemId", outputItemId
			)));
			trace.add(event(
				"route_candidate_built",
				"smelting_provider",
				optionId,
				"",
				Map.of("goal", goal.normalizedKey(), "cost", routeCost, "outputItemId", outputItemId)
			));
			ActionRoute candidateRoute = new ActionRoute(steps, routeCost);
			int provenanceRank = recipe.provenance().authoritative() ? 0 : 1;
			if (bestRoute == null
				|| candidateRoute.cost() < bestRoute.cost()
				|| candidateRoute.cost() == bestRoute.cost() && provenanceRank < bestProvenanceRank
				|| candidateRoute.cost() == bestRoute.cost() && provenanceRank == bestProvenanceRank && optionId.compareTo(bestOptionId) < 0) {
				bestRoute = candidateRoute;
				bestOptionId = optionId;
				bestProvenanceRank = provenanceRank;
			}
		}

		if (bestRoute == null) {
			return Optional.empty();
		}
		return Optional.of(new ProviderCandidate(bestRoute, "smelting_provider", bestOptionId, PROVIDER_RANK_SMELTING));
	}

	private Optional<FuelPlan> resolveSmeltingFuel(
		int inputQuantity,
		int cookTimeTicks,
		int depth,
		LinkedHashSet<String> resolving,
		List<ActionTraceEvent> trace,
		ActionGoal smeltingGoal
	) {
		int requiredFuelTicks = inputQuantity * Math.max(1, cookTimeTicks);
		if (requiredFuelTicks <= 0) {
			return Optional.empty();
		}
		List<FuelCandidate> orderedCandidates = fuelCandidates().stream()
			.sorted(Comparator
				.comparingInt((FuelCandidate candidate) -> fuelPriority(candidate.itemId()))
				.thenComparing(Comparator.comparingInt(this::fuelAcquisitionAffinity).reversed()))
			.toList();
		FuelPlan bestPlan = null;
		for (FuelCandidate candidate : orderedCandidates) {
			int requiredQuantity = fuelItemsNeeded(requiredFuelTicks, candidate.fuelTicks());
			if (requiredQuantity <= 0) {
				continue;
			}
			ActionGoal fuelGoal = ActionGoal.inventoryItem(candidate.itemId(), requiredQuantity);
			int priority = fuelPriority(candidate.itemId());
			if (existingGoalCount(fuelGoal) >= requiredQuantity) {
				bestPlan = chooseCheaperFuelPlan(bestPlan, new FuelPlan(candidate.itemId(), requiredQuantity, ActionRoute.empty(), priority));
			}
		}
		for (FuelCandidate candidate : orderedCandidates) {
			int priority = fuelPriority(candidate.itemId());
			if (bestPlan != null && priority >= bestPlan.priority()) {
				break;
			}
			int requiredQuantity = fuelItemsNeeded(requiredFuelTicks, candidate.fuelTicks());
			if (requiredQuantity <= 0) {
				continue;
			}
			ActionGoal fuelGoal = ActionGoal.inventoryItem(candidate.itemId(), requiredQuantity);
			Optional<ActionRoute> fuelRoute = resolveGoal(fuelGoal, depth + 1, resolving, trace);
			if (fuelRoute.isPresent()) {
				bestPlan = new FuelPlan(candidate.itemId(), requiredQuantity, fuelRoute.get(), priority);
				break;
			}
			if (budgetExceeded) {
				break;
			}
		}
		if (bestPlan == null) {
			return Optional.empty();
		}
		trace.add(event(
			bestPlan.route().steps().isEmpty() ? "fuel_subgoal_satisfied" : "fuel_subgoal_planned",
			"smelting_provider",
			bestPlan.itemId(),
			"",
			Map.of("goal", smeltingGoal.normalizedKey(), "fuelItemId", bestPlan.itemId(), "fuelQuantity", bestPlan.quantity())
		));
		return Optional.of(bestPlan);
	}

	private int fuelAcquisitionAffinity(FuelCandidate candidate) {
		int affinity = existingGoalCount(ActionGoal.inventoryItem(candidate.itemId(), 1)) * 1_000;
		for (ActionFact recipe : craftRecipesByOutput.getOrDefault(candidate.itemId(), List.of())) {
			for (String inputItemId : recipeInputCounts(recipe).keySet()) {
				affinity += existingGoalCount(ActionGoal.inventoryItem(inputItemId, 1));
			}
		}
		return affinity;
	}

	private int fuelPriority(String itemId) {
		if ("minecraft:coal".equals(itemId) && hasExistingMiningToolFor("minecraft:coal")) {
			return 0;
		}
		if ("minecraft:coal".equals(itemId) || "minecraft:charcoal".equals(itemId)) {
			return 30;
		}
		if (ActionGraphDomainKnowledge.plankItemIds().contains(itemId) || "minecraft:stick".equals(itemId)) {
			return 10;
		}
		if (ActionGraphDomainKnowledge.logItemIds().contains(itemId)) {
			return 20;
		}
		return 5;
	}

	private boolean hasExistingMiningToolFor(String itemId) {
		return blockAcquisitions.rulesForOutput(itemId).stream()
			.anyMatch(rule -> rule.emptyHandAllowed() || rule.usableToolItemIds().stream()
				.anyMatch(toolItemId -> existingGoalCount(ActionGoal.inventoryItem(toolItemId, 1)) >= 1));
	}

	private static FuelPlan chooseCheaperFuelPlan(FuelPlan current, FuelPlan candidate) {
		if (current == null) {
			return candidate;
		}
		int priorityCompare = Integer.compare(candidate.priority(), current.priority());
		if (priorityCompare < 0) {
			return candidate;
		}
		if (priorityCompare > 0) {
			return current;
		}
		int costCompare = Integer.compare(candidate.route().cost(), current.route().cost());
		if (costCompare < 0) {
			return candidate;
		}
		if (costCompare == 0 && candidate.quantity() < current.quantity()) {
			return candidate;
		}
		return current;
	}

	private Optional<ActionRoute> expandAlternative(
		ActionsetEntry entry,
		Map<String, Object> alternative,
		Map<String, Integer> params,
		List<ActionFact> matchedGuards,
		int depth,
		LinkedHashSet<String> resolving,
		List<ActionTraceEvent> trace
	) {
		ArrayList<ActionPlanStep> steps = new ArrayList<>();
		int routeCost = intrinsicAlternativeCost(alternative);
		String alternativeId = scalar(alternative.get("id"), "<unnamed>");

		for (Object needObject : objectList(alternative.get("needs"))) {
			ActionGoal need = goalFromFactSpec(objectMap(needObject), params);
			Optional<ActionRoute> subRoute = resolveGoal(need, depth + 1, resolving, trace);
			if (subRoute.isEmpty()) {
				return Optional.empty();
			}
			steps.addAll(subRoute.get().steps());
			routeCost = saturatingAdd(routeCost, subRoute.get().cost());
		}

		for (Object stepObject : objectList(alternative.get("steps"))) {
			Map<String, Object> step = objectMap(stepObject);
			String stepId = scalar(step.get("id"), "");
			if (step.containsKey("primitive")) {
				String primitive = scalar(step.get("primitive"), "");
				Map<String, Object> args = evaluateArgs(objectMap(step.get("args")), params);
				steps.add(new ActionPlanStep(ActionStepKind.PRIMITIVE, entry.actionId(), alternativeId, stepId, primitive, args));
				trace.add(event("primitive_planned", entry.actionId(), alternativeId, stepId, Map.of("primitive", primitive)));
				continue;
			}
			if (step.containsKey("goal")) {
				ActionGoal stepGoal = goalFromFactSpec(objectMap(step.get("goal")), params);
				Optional<ActionRoute> subRoute = resolveGoal(stepGoal, depth + 1, resolving, trace);
				if (subRoute.isEmpty()) {
					return Optional.empty();
				}
				steps.addAll(subRoute.get().steps());
				routeCost = saturatingAdd(routeCost, subRoute.get().cost());
				continue;
			}
			if (step.containsKey("actionset")) {
				String actionset = scalar(step.get("actionset"), "");
				Map<String, Object> args = evaluateArgs(objectMap(step.get("args")), params);
				steps.add(new ActionPlanStep(ActionStepKind.ACTIONSET, entry.actionId(), alternativeId, stepId, actionset, args));
				trace.add(event("step_planned", entry.actionId(), alternativeId, stepId, Map.of("actionset", actionset)));
				continue;
			}
			if (step.containsKey("watch")) {
				Map<String, Object> watch = evaluateArgs(objectMap(step.get("watch")), params);
				ActionWatchSpec watchSpec = watchSpec(watch, matchedGuards);
				steps.add(new ActionPlanStep(ActionStepKind.WATCH, entry.actionId(), alternativeId, stepId, "watch", watch, watchSpec));
				trace.add(event("watch_registered", entry.actionId(), alternativeId, stepId, watch));
			}
		}

		return Optional.of(new ActionRoute(steps, routeCost));
	}

	private List<ActionFact> matchedGuards(
		Map<String, Object> alternative,
		Map<String, Integer> params,
		List<ActionTraceEvent> trace,
		String actionId,
		String alternativeId
	) {
		ArrayList<ActionFact> matched = new ArrayList<>();
		for (Object guardObject : objectList(alternative.get("guards"))) {
			Optional<ActionFact> fact = matchingFact(objectMap(guardObject), params, trace, actionId, alternativeId);
			if (fact.isEmpty()) {
				return null;
			}
			matched.add(fact.get());
		}
		return List.copyOf(matched);
	}

	private boolean goalSatisfied(ActionGoal goal, List<ActionTraceEvent> trace) {
		LinkedHashMap<String, Object> factSpec = new LinkedHashMap<>();
		factSpec.put("fact", goal.factType().id());
		factSpec.putAll(goal.keys());
		goal.minimums().forEach((key, value) -> factSpec.put(key, value));
		return matchingFact(factSpec, Map.of(), trace, "", "").isPresent();
	}

	private boolean factSatisfied(
		Map<String, Object> factSpec,
		Map<String, Integer> params,
		List<ActionTraceEvent> trace,
		String actionId,
		String alternativeId
	) {
		return matchingFact(factSpec, params, trace, actionId, alternativeId).isPresent();
	}

	private Optional<ActionFact> matchingFact(
		Map<String, Object> factSpec,
		Map<String, Integer> params,
		List<ActionTraceEvent> trace,
		String actionId,
		String alternativeId
	) {
		ActionFactCondition requirement = requirementFromSpec(factSpec, params);
		List<ActionFact> matches = facts.query(requirement.factType(), requirement.queryKeys());
		Optional<ActionFact> matched = matches.stream()
			.filter(this::usableFact)
			.filter(requirement::satisfiedBy)
			.sorted(Comparator
				.comparingLong(ActionFact::observedTick).reversed()
				.thenComparing(fact -> fact.identity().keys().toString()))
			.findFirst();
		trace.add(event(
			"fact_query",
			actionId,
			alternativeId,
			"",
			Map.of(
				"fact", requirement.factType().id(),
				"keys", requirement.queryKeys(),
				"satisfied", matched.isPresent(),
				"matchedIdentity", matched.map(fact -> fact.identity().keys()).orElse(Map.of())
			)
		));
		return matched;
	}

	private ActionWatchSpec watchSpec(Map<String, Object> watch, List<ActionFact> matchedGuards) {
		ActionFactCondition condition = requirementFromSpec(watch, Map.of());
		ActionFactCondition initialCondition = condition;
		Map<String, Object> progress = objectMap(watch.get("progress"));
		ActionWatchProgressKind progressKind = "area_ticking".equals(scalar(progress.get("kind"), ""))
			? ActionWatchProgressKind.AREA_TICKING
			: ActionWatchProgressKind.NONE;
		ActionFact sourceFact = null;
		if ("matched_fact".equals(scalar(progress.get("anchor"), ""))) {
			sourceFact = matchedGuards.stream()
				.filter(fact -> fact.identity().type() == initialCondition.factType())
				.filter(fact -> initialCondition.queryKeys().entrySet().stream()
					.allMatch(entry -> entry.getValue().equals(fact.identity().keys().get(entry.getKey()))))
				.findFirst()
				.orElse(null);
		}
		if (sourceFact != null) {
			LinkedHashMap<String, String> exactKeys = new LinkedHashMap<>(condition.queryKeys());
			exactKeys.putAll(sourceFact.identity().keys());
			condition = new ActionFactCondition(condition.factType(), exactKeys, condition.minimums());
		}
		return new ActionWatchSpec(
			condition,
			sourceFact == null ? null : sourceFact.identity(),
			longValue(watch.get("timeoutTicks"), 24000L),
			progressKind,
			anchorFromFact(sourceFact)
		);
	}

	private static ActionWatchAnchor anchorFromFact(ActionFact fact) {
		if (fact == null || !(fact.payload().get("origin") instanceof Map<?, ?> origin)) {
			return null;
		}
		Object x = origin.get("x");
		Object y = origin.get("y");
		Object z = origin.get("z");
		if (!(x instanceof Number xNumber) || !(y instanceof Number yNumber) || !(z instanceof Number zNumber)) {
			return null;
		}
		return new ActionWatchAnchor(
			fact.identity().keys().getOrDefault("worldId", ""),
			fact.identity().keys().getOrDefault("dimension", ""),
			xNumber.intValue(),
			yNumber.intValue(),
			zNumber.intValue(),
			false
		);
	}

	private boolean usableFact(ActionFact fact) {
		return GUARD_USABLE_PROVENANCE.contains(fact.provenance()) && !fact.isStaleAt(context.currentTick());
	}

	private Map<String, List<ActionFact>> providerFactsByOutput(
		ActionFactType factType,
		String outputPayloadKey,
		String identitySortKey
	) {
		Map<String, String> query = Map.of(
			"worldId", context.worldId(),
			"actorId", context.actorId()
		);
		LinkedHashMap<String, List<ActionFact>> indexed = new LinkedHashMap<>();
		for (ActionFact fact : facts.query(factType, query).stream()
			.filter(this::usableFact)
			.sorted(Comparator.comparing(candidate -> candidate.identity().keys().getOrDefault(identitySortKey, "")))
			.toList()) {
			String outputItemId = scalar(fact.payload().get(outputPayloadKey), "");
			if (!outputItemId.isBlank()) {
				indexed.computeIfAbsent(outputItemId, ignored -> new ArrayList<>()).add(fact);
			}
		}
		LinkedHashMap<String, List<ActionFact>> immutable = new LinkedHashMap<>();
		indexed.forEach((itemId, candidates) -> immutable.put(itemId, List.copyOf(candidates)));
		return Map.copyOf(immutable);
	}

	private List<ActionsetEntry> matchingActionsets(ActionGoal goal) {
		return index.all().stream()
			.filter(entry -> producesGoal(entry.definition(), goal))
			.toList();
	}

	private static boolean producesGoal(Map<String, Object> action, ActionGoal goal) {
		for (Object produceObject : objectList(action.get("produces"))) {
			Map<String, Object> produced = objectMap(produceObject);
			if (!goal.factType().id().equals(scalar(produced.get("fact"), ""))) {
				continue;
			}
			boolean keysMatch = true;
			for (Map.Entry<String, String> key : goal.keys().entrySet()) {
				String producedValue = scalar(produced.get(key.getKey()), null);
				if (producedValue != null && !producedValue.equals(key.getValue())) {
					keysMatch = false;
					break;
				}
			}
			if (keysMatch) {
				return true;
			}
		}
		return false;
	}

	private Map<String, Integer> bindParams(Map<String, Object> action, ActionGoal goal) {
		LinkedHashMap<String, Integer> params = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : objectMap(action.get("params")).entrySet()) {
			Map<String, Object> definition = objectMap(entry.getValue());
			Object defaultValue = definition.get("default");
			if (defaultValue instanceof Number number) {
				params.put(entry.getKey(), number.intValue());
			}
		}
		if (params.containsKey("quantity")) {
			params.put("quantity", goal.minimum("countAtLeast", goal.minimum("matureCountAtLeast", params.get("quantity"))));
		}
		int targetCount = goal.minimum("countAtLeast", goal.minimum("matureCountAtLeast", params.getOrDefault("quantity", 1)));
		int existingCount = existingGoalCount(goal);
		params.put("goal.targetCount", targetCount);
		params.put("goal.existingCount", existingCount);
		params.put("goal.deficitCount", Math.max(0, targetCount - existingCount));
		return Map.copyOf(params);
	}

	private int existingGoalCount(ActionGoal goal) {
		if (goal.factType() != ActionFactType.INVENTORY_ITEM && goal.factType() != ActionFactType.INVENTORY_RESOURCE) {
			return 0;
		}
		LinkedHashMap<String, String> queryKeys = new LinkedHashMap<>();
		queryKeys.put("worldId", context.worldId());
		queryKeys.put("actorId", context.actorId());
		queryKeys.putAll(goal.keys());
		return facts.query(goal.factType(), queryKeys).stream()
			.filter(this::usableFact)
			.map(ActionFact::payload)
			.map(payload -> payload.get("count"))
			.filter(Number.class::isInstance)
			.map(Number.class::cast)
			.mapToInt(Number::intValue)
			.max()
			.orElse(0);
	}

	private int estimateAggregateResourceCost(
		ResourceGatheringCatalog.ResourceEntry entry,
		int deficitCount
	) {
		int bestCost = Integer.MAX_VALUE;
		for (String itemId : entry.acceptedItemIds()) {
			for (BlockAcquisitionRule rule : blockAcquisitions.rulesForOutput(itemId)) {
				if (!rule.dropEstimateKnown() || rule.expectedDropsPerBreak() <= 0.0) {
					continue;
				}
				int breakTicks = fastestImmediatelyAvailableBreakTicks(rule);
				if (breakTicks <= 0) {
					continue;
				}
				int expectedBreakCount = expectedBreakCount(deficitCount, rule.expectedDropsPerBreak());
				int cost = MiningCostModel.workCost(
					breakTicks,
					expectedBreakCount,
					availabilityPenalty(rule, expectedBreakCount)
				);
				bestCost = Math.min(bestCost, cost);
			}
		}
		return bestCost == Integer.MAX_VALUE ? 20 : bestCost;
	}

	private int fastestImmediatelyAvailableBreakTicks(BlockAcquisitionRule rule) {
		int best = rule.emptyHandAllowed() ? normalizedBreakTicks(rule.emptyHandBreakTicks()) : Integer.MAX_VALUE;
		for (Map.Entry<String, Integer> tool : rule.breakTicksByToolItemId().entrySet()) {
			if (existingGoalCount(ActionGoal.inventoryItem(tool.getKey(), 1)) >= 1) {
				best = Math.min(best, normalizedBreakTicks(tool.getValue()));
			}
		}
		return best == Integer.MAX_VALUE ? -1 : best;
	}

	private int availabilityPenalty(BlockAcquisitionRule rule, int expectedBreakCount) {
		return MiningCostModel.availabilityPenalty(
			nearbyBlockAvailability.observed(),
			nearbyBlockAvailability.count(rule.blockId()),
			expectedBreakCount
		);
	}

	private static int expectedBreakCount(int itemCount, double expectedDropsPerBreak) {
		if (itemCount <= 0) {
			return 0;
		}
		if (!Double.isFinite(expectedDropsPerBreak) || expectedDropsPerBreak <= 0.0) {
			return Integer.MAX_VALUE;
		}
		double expected = Math.ceil(itemCount / expectedDropsPerBreak);
		return expected >= Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(1, (int) expected);
	}

	private static int normalizedBreakTicks(int breakTicks) {
		return Math.max(1, breakTicks);
	}

	private static MiningToolPlan cheaperMiningToolPlan(MiningToolPlan current, MiningToolPlan candidate) {
		if (current == null) {
			return candidate;
		}
		int currentCost = saturatingAdd(current.route().cost(), current.miningWorkCost());
		int candidateCost = saturatingAdd(candidate.route().cost(), candidate.miningWorkCost());
		if (candidateCost != currentCost) {
			return candidateCost < currentCost ? candidate : current;
		}
		if (candidate.breakTicks() != current.breakTicks()) {
			return candidate.breakTicks() < current.breakTicks() ? candidate : current;
		}
		return candidate.normalizedToolKey().compareTo(current.normalizedToolKey()) < 0 ? candidate : current;
	}

	private static int saturatingAdd(int left, int right) {
		long result = (long) Math.max(0, left) + Math.max(0, right);
		return result >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
	}

	@FunctionalInterface
	private interface ProviderResolver {
		Optional<ProviderCandidate> resolve(
			ActionGoal goal,
			int depth,
			LinkedHashSet<String> resolving,
			List<ActionTraceEvent> trace
		);
	}

	private record ProviderCandidate(
		ActionRoute route,
		String actionId,
		String alternativeId,
		int rank
	) {
		private ProviderCandidate {
			route = route == null ? ActionRoute.empty() : route;
			actionId = actionId == null ? "" : actionId;
			alternativeId = alternativeId == null ? "" : alternativeId;
		}
	}

	private record MiningToolPlan(
		boolean available,
		ActionRoute route,
		List<String> requiredToolItemIds,
		int breakTicks,
		int miningWorkCost
	) {
		private MiningToolPlan {
			route = route == null ? ActionRoute.empty() : route;
			requiredToolItemIds = requiredToolItemIds == null ? List.of() : requiredToolItemIds.stream()
				.filter(value -> value != null && !value.isBlank())
				.distinct()
				.sorted()
				.toList();
			breakTicks = normalizedBreakTicks(breakTicks);
			miningWorkCost = Math.max(0, miningWorkCost);
		}

		private static MiningToolPlan available(
			ActionRoute route,
			List<String> requiredToolItemIds,
			int breakTicks,
			int miningWorkCost
		) {
			return new MiningToolPlan(true, route, requiredToolItemIds, breakTicks, miningWorkCost);
		}

		private static MiningToolPlan unavailable() {
			return new MiningToolPlan(false, ActionRoute.empty(), List.of(), 1, 0);
		}

		private String normalizedToolKey() {
			return requiredToolItemIds.isEmpty() ? "hand" : String.join(",", requiredToolItemIds);
		}
	}

	private static int fuelItemsNeeded(int requiredFuelTicks, int fuelTicksPerItem) {
		if (requiredFuelTicks <= 0) {
			return 0;
		}
		if (fuelTicksPerItem <= 0) {
			return Integer.MAX_VALUE;
		}
		return (requiredFuelTicks + fuelTicksPerItem - 1) / fuelTicksPerItem;
	}

	private static List<FuelCandidate> fuelCandidates() {
		ArrayList<FuelCandidate> candidates = new ArrayList<>();
		for (String itemId : ActionGraphDomainKnowledge.plankItemIds()) {
			candidates.add(new FuelCandidate(itemId, FUEL_TICKS_PLANKS));
		}
		candidates.add(new FuelCandidate("minecraft:stick", FUEL_TICKS_STICKS));
		for (String itemId : ActionGraphDomainKnowledge.logItemIds()) {
			candidates.add(new FuelCandidate(itemId, FUEL_TICKS_LOGS));
		}
		candidates.add(new FuelCandidate("minecraft:coal", FUEL_TICKS_COAL));
		candidates.add(new FuelCandidate("minecraft:charcoal", FUEL_TICKS_COAL));
		return List.copyOf(candidates);
	}

	private ActionGoal goalFromFactSpec(Map<String, Object> factSpec, Map<String, Integer> params) {
		ActionFactType factType = ActionFactType.fromId(scalar(factSpec.get("fact"), ""))
			.orElseThrow(() -> new IllegalArgumentException("unknown fact type " + factSpec.get("fact")));
		LinkedHashMap<String, String> keys = new LinkedHashMap<>();
		for (String key : identityKeyNames()) {
			String value = scalar(factSpec.get(key), null);
			if (value != null && !value.isBlank()) {
				keys.put(key, value);
			}
		}
		LinkedHashMap<String, Integer> minimums = new LinkedHashMap<>();
		if (factSpec.containsKey("countAtLeast")) {
			minimums.put("countAtLeast", evaluateInt(factSpec.get("countAtLeast"), params));
		}
		if (factSpec.containsKey("matureCountAtLeast")) {
			minimums.put("matureCountAtLeast", evaluateInt(factSpec.get("matureCountAtLeast"), params));
		}
		if (factSpec.containsKey("readyAtLeast")) {
			minimums.put("readyAtLeast", evaluateInt(factSpec.get("readyAtLeast"), params));
		}
		return new ActionGoal(factType, keys, minimums);
	}

	private ActionFactCondition requirementFromSpec(Map<String, Object> factSpec, Map<String, Integer> params) {
		ActionFactType factType = ActionFactType.fromId(scalar(factSpec.get("fact"), ""))
			.orElseThrow(() -> new IllegalArgumentException("unknown fact type " + factSpec.get("fact")));
		LinkedHashMap<String, String> queryKeys = new LinkedHashMap<>();
		queryKeys.put("worldId", context.worldId());
		if (factType == ActionFactType.INVENTORY_ITEM || factType == ActionFactType.INVENTORY_RESOURCE || factType == ActionFactType.INVENTORY_TOOL || factType == ActionFactType.CRAFT_RECIPE || factType == ActionFactType.SMELT_RECIPE || factType == ActionFactType.SMELTING_PROCESS) {
			queryKeys.put("actorId", context.actorId());
		}
		if (worldDimensionScopedFact(factType)) {
			queryKeys.put("dimension", context.dimension());
		}
		for (String key : identityKeyNames()) {
			String value = scalar(factSpec.get(key), null);
			if (value != null && !value.isBlank()) {
				queryKeys.put(key, value);
			}
		}
		LinkedHashMap<String, Integer> minimums = new LinkedHashMap<>();
		if (factSpec.containsKey("countAtLeast")) {
			minimums.put("count", evaluateInt(factSpec.get("countAtLeast"), params));
		}
		if (factSpec.containsKey("matureCountAtLeast")) {
			minimums.put("matureCount", evaluateInt(factSpec.get("matureCountAtLeast"), params));
		}
		if (factSpec.containsKey("readyAtLeast")) {
			minimums.put("ready", evaluateInt(factSpec.get("readyAtLeast"), params));
		}
		return new ActionFactCondition(factType, queryKeys, minimums);
	}

	private static long longValue(Object value, long fallback) {
		return value instanceof Number number ? number.longValue() : fallback;
	}

	private static Map<String, Integer> recipeInputCounts(ActionFact recipe) {
		LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
		Object inputCounts = recipe.payload().get("inputCounts");
		if (inputCounts instanceof Map<?, ?> map) {
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				String itemId = String.valueOf(entry.getKey());
				if (itemId.isBlank()) {
					continue;
				}
				int count = entry.getValue() instanceof Number number ? number.intValue() : 0;
				if (count > 0) {
					counts.merge(itemId, count, Integer::sum);
				}
			}
		}
		if (!counts.isEmpty()) {
			return Map.copyOf(counts);
		}
		Object inputItemIds = recipe.payload().get("inputItemIds");
		if (inputItemIds instanceof List<?> list) {
			for (Object item : list) {
				String itemId = String.valueOf(item);
				if (!itemId.isBlank()) {
					counts.merge(itemId, 1, Integer::sum);
				}
			}
		}
		return Map.copyOf(counts);
	}

	private static int intPayload(ActionFact fact, String key, int fallback) {
		Object value = fact.payload().get(key);
		return value instanceof Number number ? number.intValue() : fallback;
	}

	private static List<Map<String, Object>> alternatives(ActionsetEntry entry) {
		return objectList(entry.definition().get("alternatives")).stream()
			.map(ActionResolver::objectMap)
			.sorted(Comparator.comparingInt(ActionResolver::intrinsicAlternativeCost))
			.toList();
	}

	private static int intrinsicAlternativeCost(Map<String, Object> alternative) {
		boolean containsCraft = false;
		for (Object stepObject : objectList(alternative.get("steps"))) {
			Map<String, Object> step = objectMap(stepObject);
			if (step.containsKey("primitive")) {
				if (!"craft_item".equals(scalar(step.get("primitive"), ""))) {
					return cost(alternative);
				}
				containsCraft = true;
			}
			else if (step.containsKey("watch") || step.containsKey("actionset")) {
				return cost(alternative);
			}
		}
		return containsCraft ? 0 : cost(alternative);
	}

	private static int cost(Map<String, Object> alternative) {
		Object cost = alternative.get("cost");
		return cost instanceof Number number ? number.intValue() : 10;
	}

	private static Map<String, Object> evaluateArgs(Map<String, Object> args, Map<String, Integer> params) {
		LinkedHashMap<String, Object> evaluated = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : args.entrySet()) {
			evaluated.put(entry.getKey(), evaluateValue(entry.getValue(), params));
		}
		return evaluated;
	}

	private static Object evaluateValue(Object value, Map<String, Integer> params) {
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> typed = objectMap(map);
			if (typed.containsKey("expr")) {
				return evaluateInt(typed, params);
			}
			return evaluateArgs(typed, params);
		}
		if (value instanceof List<?> list) {
			return list.stream()
				.map(item -> evaluateValue(item, params))
				.toList();
		}
		return value;
	}

	private static int evaluateInt(Object value, Map<String, Integer> params) {
		if (value instanceof Number number) {
			return number.intValue();
		}
		if (value instanceof Map<?, ?> map) {
			String expression = scalar(objectMap(map).get("expr"), "");
			return new IntegerExpression(expression, params).parse();
		}
		return new IntegerExpression(scalar(value, "0"), params).parse();
	}

	private static ActionTraceEvent event(
		String eventType,
		String actionId,
		String alternativeId,
		String stepId,
		Map<String, Object> payload
	) {
		return new ActionTraceEvent(eventType, actionId, alternativeId, stepId, payload);
	}

	private static List<String> identityKeyNames() {
		return List.of("itemId", "resourceKind", "toolTag", "dimension", "blockPos", "cropId", "siteId", "siteType", "plotId", "candidateId", "sourceId", "sampleId", "entityTypeId", "entityId", "recipeId", "optionId", "processId", "watchId", "goalId");
	}

	private static boolean worldDimensionScopedFact(ActionFactType factType) {
		return factType == ActionFactType.WORLD_BLOCK
			|| factType == ActionFactType.WORLD_CROP
			|| factType == ActionFactType.WORLD_CROP_GROUP
			|| factType == ActionFactType.WORLD_SITE
			|| factType == ActionFactType.WORLD_FARM_SITE
			|| factType == ActionFactType.WORLD_FARM_PLOT
			|| factType == ActionFactType.WORLD_SOIL_CANDIDATE
			|| factType == ActionFactType.WORLD_HYDRATION_SOURCE
			|| factType == ActionFactType.WORLD_LIGHT_LEVEL
			|| factType == ActionFactType.WORLD_CROP_SEED_SOURCE
			|| factType == ActionFactType.WORLD_ENTITY;
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

	private static List<Object> objectList(Object value) {
		if (value instanceof List<?> list) {
			return List.copyOf(list);
		}
		return List.of();
	}

	private static String scalar(Object value, String fallback) {
		return value == null ? fallback : String.valueOf(value);
	}

	private record FuelCandidate(String itemId, int fuelTicks) {
	}

	private record FuelPlan(String itemId, int quantity, ActionRoute route, int priority) {
	}

	private static final class IntegerExpression {
		private final String expression;
		private final Map<String, Integer> params;
		private int index;

		IntegerExpression(String expression, Map<String, Integer> params) {
			this.expression = expression == null ? "" : expression;
			this.params = params == null ? Map.of() : params;
		}

		int parse() {
			int value = parseExpression();
			skipWhitespace();
			if (index != expression.length()) {
				throw new IllegalArgumentException("unexpected token in expression: " + expression);
			}
			return value;
		}

		private int parseExpression() {
			int value = parseTerm();
			while (true) {
				skipWhitespace();
				if (match('+')) {
					value += parseTerm();
				}
				else if (match('-')) {
					value -= parseTerm();
				}
				else {
					return value;
				}
			}
		}

		private int parseTerm() {
			int value = parseFactor();
			while (true) {
				skipWhitespace();
				if (match('*')) {
					value *= parseFactor();
				}
				else if (match('/')) {
					value /= parseFactor();
				}
				else {
					return value;
				}
			}
		}

		private int parseFactor() {
			skipWhitespace();
			if (match('-')) {
				return -parseFactor();
			}
			if (match('(')) {
				int value = parseExpression();
				expect(')');
				return value;
			}
			if (peekDigit()) {
				return parseInteger();
			}
			return parseIdentifier();
		}

		private int parseInteger() {
			int start = index;
			while (index < expression.length() && Character.isDigit(expression.charAt(index))) {
				index++;
			}
			return Integer.parseInt(expression.substring(start, index));
		}

		private int parseIdentifier() {
			int start = index;
			while (index < expression.length()) {
				char value = expression.charAt(index);
				if (!Character.isLetterOrDigit(value) && value != '_' && value != '.') {
					break;
				}
				index++;
			}
			String identifier = expression.substring(start, index);
			Integer exact = params.get(identifier);
			if (exact != null) {
				return exact;
			}
			if (identifier.startsWith("params.")) {
				String param = identifier.substring("params.".length());
				Integer value = params.get(param);
				if (value != null) {
					return value;
				}
			}
			throw new IllegalArgumentException("unknown identifier in expression: " + identifier);
		}

		private boolean match(char expected) {
			if (index < expression.length() && expression.charAt(index) == expected) {
				index++;
				return true;
			}
			return false;
		}

		private void expect(char expected) {
			if (!match(expected)) {
				throw new IllegalArgumentException("expected '" + expected + "' in expression: " + expression);
			}
		}

		private boolean peekDigit() {
			return index < expression.length() && Character.isDigit(expression.charAt(index));
		}

		private void skipWhitespace() {
			while (index < expression.length() && Character.isWhitespace(expression.charAt(index))) {
				index++;
			}
		}
	}
}
