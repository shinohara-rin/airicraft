package ai.moeru.airicraft.agent.actions;

import java.util.List;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.Map;
import ai.moeru.airicraft.agent.tasks.CraftingOpportunity;

public record AiricraftPlanningSnapshot(
	List<ActionFact> facts,
	BlockAcquisitionIndex blockAcquisitions,
	NearbyBlockAvailability nearbyBlockAvailability,
	ActionResolverContext context
) {
	/** Captures values only; no planner, execution, or world mutation is started. */
	public static AiricraftPlanningSnapshot capture(ActionGraphExecutionInput input) {
		ActionResolverContext context = input.context();
		ActionFactStore facts = new ActionFactStore(input.observedFacts());
		input.observedInventory().forEach((id, count) -> facts.upsert(fact(
			ActionFactIdentity.inventoryItem(context.worldId(), context.actorId(), id), Map.of("count", count), ActionFactProvenance.OBSERVED, context)));
		input.observedResources().forEach((id, count) -> facts.upsert(fact(
			ActionFactIdentity.inventoryResource(context.worldId(), context.actorId(), id), Map.of("count", count), ActionFactProvenance.OBSERVED, context)));
		addCrafts(facts, input.knownCrafts(), ActionFactProvenance.INFERRED, context);
		addCrafts(facts, input.availableCrafts(), ActionFactProvenance.OBSERVED, context);
		input.knownSmelts().forEach(recipe -> facts.upsert(fact(
			ActionFactIdentity.smeltRecipe(context.worldId(), context.actorId(), recipe.optionId()),
			Map.of("inputItemId", recipe.inputItemId(), "outputItemId", recipe.outputItemId(),
				"outputCount", recipe.outputCount(), "maxInputQuantity", recipe.maxInputQuantity(),
				"cookTimeTicks", recipe.cookTimeTicks(), "stationItemId", recipe.stationItemId(), "stationItemCount", recipe.stationItemCount()),
			ActionFactProvenance.INFERRED, context)));
		input.availableSmelts().forEach(option -> facts.upsert(fact(
			ActionFactIdentity.smeltRecipe(context.worldId(), context.actorId(), option.optionId()),
			Map.of("inputItemId", option.inputItemId(), "outputItemId", option.outputItemId(),
				"outputCount", option.outputCount(), "maxInputQuantity", option.maxInputQuantity(), "cookTimeTicks", option.cookTimeTicks()),
			ActionFactProvenance.OBSERVED, context)));
		return new AiricraftPlanningSnapshot(facts.queryAll(), input.blockAcquisitions(), input.nearbyBlockAvailability(), context);
	}

	private static void addCrafts(ActionFactStore facts, List<CraftingOpportunity> recipes, ActionFactProvenance provenance, ActionResolverContext context) {
		for (CraftingOpportunity recipe : recipes) {
			Map<String, Integer> counts = new LinkedHashMap<>();
			recipe.inputItemIds().forEach(id -> counts.merge(id, 1, Integer::sum));
			facts.upsert(fact(ActionFactIdentity.craftRecipe(context.worldId(), context.actorId(), recipe.recipeId()),
				Map.of("outputItemId", recipe.outputItemId(), "outputCount", recipe.outputCount(), "inputItemIds", recipe.inputItemIds(),
					"inputCounts", counts, "gridKind", recipe.gridKind().name()), provenance, context));
		}
	}

	private static ActionFact fact(ActionFactIdentity identity, Map<String, Object> payload, ActionFactProvenance provenance, ActionResolverContext context) {
		return new ActionFact(identity, payload, provenance, context.currentTick(), ActionFact.NEVER_STALE);
	}

	public AiricraftPlanningSnapshot {
		ActionResolverContext safeContext = Objects.requireNonNull(context, "context");
		long currentTick = safeContext.currentTick();
		context = safeContext;
		facts = facts == null ? List.of() : facts.stream()
			.filter(fact -> fact.provenance() == ActionFactProvenance.OBSERVED
				|| fact.provenance() == ActionFactProvenance.EXECUTOR_REPORTED
				|| fact.provenance() == ActionFactProvenance.INFERRED)
			.filter(fact -> !fact.isStaleAt(currentTick))
			.toList();
		blockAcquisitions = blockAcquisitions == null ? BlockAcquisitionIndex.empty() : blockAcquisitions;
		nearbyBlockAvailability = nearbyBlockAvailability == null ? NearbyBlockAvailability.unknown() : nearbyBlockAvailability;
	}
}
