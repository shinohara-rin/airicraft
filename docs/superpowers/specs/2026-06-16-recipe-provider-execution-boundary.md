# Recipe Provider Execution Boundary

## Context

The action graph needs broad recipe knowledge for route planning, but crafting remains a foreground world action with strict executable safety checks. Current `dev` already exposes `check_craftables`, `craft_recipe`, and optional REI `search_recipes`; the graph must not collapse these into one meaning.

## Contract

`craft.recipe` facts are recipe knowledge. They can come from:

- Vanilla recipe-book craftability observations.
- Current executable `availableCrafts`.
- Optional recipe-viewer providers such as REI, and later JEI.
- Method-provider fixtures in tests.

Executable craft dispatch is narrower. A `craft_item` primitive may dispatch only when the selected recipe is present in current executable craft opportunities, or when the executor is explicitly upgraded to safely execute provider-only recipes.

Therefore:

- The resolver may use `craft.recipe` facts to build route candidates and compute ingredient deficits.
- The primitive mapper must re-check current executable craft opportunities before creating a `CRAFT_RECIPE` active job.
- REI search results remain recipe-viewer knowledge. They may inform planning and future provider facts, but they do not by themselves prove the player can craft now.
- `check_craftables` remains the compatibility read for executable recipe ids during migration.
- `craft_recipe` remains a legacy direct action wrapper until it is graph-backed; its accepted recipe id must still be executable.

## Deficit Semantics

For an inventory-item goal:

- `targetCount` is the requested minimum item count.
- `existingCount` is the currently usable observed or authoritative inventory count.
- `deficitCount = max(0, targetCount - existingCount)`.

For a recipe provider route:

- `craftTimes = ceil(deficitCount / outputCount)`.
- Required ingredient count is each recipe input count multiplied by `craftTimes`.
- The final `craft_item` primitive quantity is the output item deficit, not the number of recipe runs.
- The primitive mapper converts output quantity back to recipe runs using the selected executable `CraftingOpportunity`.

Example:

- Goal: `minecraft:bread` count at least `3`.
- Existing bread: `1`.
- Deficit: `2`.
- Recipe: `3 * minecraft:wheat -> 1 * minecraft:bread`.
- Required wheat: `6`.
- Final primitive: `craft_item itemId=minecraft:bread recipeId=<recipe> quantity=2`.
- Executor job: `CRAFT_RECIPE recipeId=<recipe> times=2`.

## REI Boundary

REI-backed `search_recipes` is intentionally informational during this phase:

- It can expose recipes the player does not currently have materials or station access for.
- It should keep returning text that marks results as recipe-viewer knowledge, not current craftability.
- Compat smoke should verify REI still loads and `search_recipes` still works, but graph dispatch must not trust REI alone as executable proof.

When a future REI provider emits graph facts, those facts must include provenance and executability metadata, for example:

- `provenance=PROVIDER_KNOWLEDGE`
- `executableNow=false`
- `provider=rei`

Until that metadata and executor support exist, provider-only recipe facts should be used for route exploration and explanations, not foreground crafting dispatch.

## Migration Implications

- `make_bread` stays as a compatibility smoke fixture while generic provider routes mature.
- Generic `recipe_provider` is the preferred permanent route for bread-from-wheat when a `craft.recipe` fact is present.
- Planner prompts should stop teaching recipe expansion once graph-backed `start_action_goal` is available to the planner.
- Existing `check_craftables` and `craft_recipe` tests should remain passing until the legacy tools become graph-backed adapters.
