# Action Graph Architecture Solidification

## Summary

Airicraft should move low-level Minecraft execution decisions out of the LLM
planner and into an action graph runtime. The planner remains responsible for
understanding the user's request, choosing a high-level intent, answering
questions, and asking for clarification. The action graph owns facts, route
selection, primitive dispatch, cancellation, terminal success, background
watches, and traces.

The earlier `composable-action-graph` branch is reference material only. It is
not a merge base for `dev`, because recent `dev` work added direct planner
tools for crafting, smelting, block mutation, return-to-surface, and
coordinate-grounded world reads. The architecture here keeps those additions as
primitive execution machinery, but changes their product boundary: direct action
tools are temporary compatibility adapters into graph-owned execution, not the
long-term planner interface.

## Conflict Analysis

Current `dev` exposes many procedural tools directly to the planner:

- `craft_recipe`, with prompt policy for recipe ids, recipe run counts, table
  setup, and multi-step crafting.
- `smelt_items`, `collect_smelted_items`, and `cancel_smelting`, with prompt
  policy for furnace selection, fuel shortfalls, confirmations, output
  collection, and async status.
- `place_block`, `use_block`, and `break_blocks`, with prompt policy for exact
  inspection, target freshness, support resolution, and retry after rejection.
- Movement and resource tools such as `navigate_to`, `return_to_surface`,
  `mine_blocks`, `ensure_blocks_in_inventory`, and `collect_resource`.

That tool surface works, but it makes the planner perform low-level sequencing.
For example, "make bread" can become a planner script of checking craftables,
choosing a recipe id, crafting, inspecting inventory, resolving missing wheat,
and deciding whether to mine, farm, wait, or retry. Smelting has the same issue:
fuel shortfalls, furnace ownership, ready output, and collection state become
planner prompt policy instead of runtime state.

The graph-first boundary resolves the conflict:

- The planner starts a typed action goal such as "inventory item
  `minecraft:bread` count at least 1".
- Providers ingest safe reads and executor feedback into typed facts.
- The advisor chooses a route through recipe, inventory, world, and provider
  knowledge.
- The graph runtime dispatches one foreground primitive at a time through the
  existing task executors.
- Terminal events and inspections update facts and traces before the next route
  step.

The planner may still use safe reads to answer questions or debug a failure,
but it should not be required to hand-chain reads into low-level action tools.

## Product Boundary

### Planner Role

The planner owns high-level intent:

- Convert user language into a typed action goal.
- Ask the user when the goal is ambiguous or unsafe to infer.
- Answer questions using safe reads, graph status, and traces.
- Review repeated failures, conflicting facts, or high-cost tradeoffs when the
  resolver asks for help.

The planner does not own:

- Crafting recipe expansion.
- Smelting lifecycle policy.
- Inventory deficit math.
- Block-target freshness gates.
- Primitive retry policy.
- Foreground actuation ownership.
- Watch polling or resumption.
- Final success determination.

### Action Graph Role

The action graph owns the runtime model:

- Typed facts with provenance, freshness, world scope, and identity keys.
- Providers that translate inventory, recipes, smelting observations, world
  scans, entities, and executor events into facts and candidate routes.
- A primitive registry describing Java-backed capabilities, schemas, guards,
  produced facts, failure codes, foreground ownership, and cancellation.
- A resolver that turns typed goals into ranked routes.
- Active graph goal state, including selected route, active primitive,
  suspended watches, failure history, and cancellation.
- Foreground primitive dispatch through `WorldTaskRequest`,
  `DispatchingWorldTaskExecutor`, and existing focused executors.
- Background watches for world conditions such as crop maturity or furnace
  readiness.
- Trace events for goal start, fact query, route selection, primitive dispatch,
  primitive terminal result, fact update, watch fulfillment, replanning,
  cancellation, and success/failure.

Existing executor classes remain the actuation layer. The graph does not fork a
parallel Minecraft controller; it wraps and coordinates the current primitive
runtime.

### Safe Reads

Safe read tools remain planner-visible during and after migration:

- `take_a_look`
- `inspect_inventory`
- `inspect_world`
- `inspect_nearby_entities`
- `check_craftables`
- `check_smeltables`
- `inspect_smelting`
- provider read tools such as `search_recipes`

Their role is answering and debugging, not action sequencing. The same services
behind these tools should also feed graph providers. For example,
`check_craftables` can still answer "what can you craft?", while the recipe
provider uses equivalent craftability evidence to route an inventory-item goal.

## Planner-Facing Interfaces

The primary action surface becomes graph-oriented:

- `start_action_goal`
- `list_action_goals`
- `inspect_action_goal`
- `cancel_action_goal`
- `inspect_action_trace`
- `list_action_capabilities`

`start_action_goal` accepts typed v1 goal kinds:

- `inventory_item`: satisfy item count in inventory, including crafting,
  smelting, gathering, harvesting, or other provider routes.
- `resource_collection`: gather supported resources such as logs while allowing
  graph-owned route choice and progress accounting.
- `movement`: reach a position, surface target, player, or provider-generated
  waypoint.
- `block_modification`: place, use, or break exact targets after graph-owned
  freshness and guard checks.
- `entity_interaction`: attack or use one selected entity through entity facts
  and executor feedback.
- `item_transfer`: drop or give exact items after inventory facts and proximity
  checks.
- `smelting_output`: produce or collect smelted output through furnace,
  fuel, ownership, readiness, and collection facts.

The planner should prefer `start_action_goal` for any action. Direct procedural
tools stay visible only as migration compatibility.

## Direct Tool Compatibility

Direct action tools remain temporarily to avoid breaking current workflows and
tests. During the dual-surface phase, they must be thin compatibility adapters:

- `craft_recipe` creates the same graph goal or primitive graph step as an
  equivalent `inventory_item` route.
- `smelt_items` and `collect_smelted_items` create or resume graph-owned
  smelting goals and facts.
- `place_block`, `use_block`, and `break_blocks` enter graph-owned block
  modification goals or primitive steps, preserving freshness and guard checks.
- Movement/resource/drop/give/entity tools enter graph-owned goals or primitive
  steps once their migration stage lands.

Compatibility results should include graph identity once graph-backed:

```text
Tool result for craft_recipe: accepted graphGoalId=<id> traceId=<id> legacyTool=craft_recipe
```

The graph identity lets the planner, CLI, and tests follow the same trace path
regardless of whether the goal started through the new interface or a legacy
tool.

Direct tools must not keep a separate source of truth for progress,
cancellation, terminal success, or failure once their graph wrapper exists.

## Provider Responsibilities

Providers translate current game evidence into graph facts and route fragments.
They are runtime code, not prompt policy.

Crafting provider responsibilities:

- Convert recipe-book, current craftability, and optional REI/JEI knowledge
  into `craft.recipe` facts and route candidates.
- Own output-count and ingredient-deficit math.
- Expand reachable recipe chains such as logs to planks to sticks.
- Recheck executable craftability at dispatch time.
- Confirm terminal success through executor-reported or observed
  `inventory.item` facts.

Smelting provider responsibilities:

- Represent input, fuel, furnace candidate, process ownership, readiness,
  confirmation, and output facts.
- Convert fuel shortfalls into missing-fuel facts, subgoals, or graph failures.
- Keep furnace ownership and confirmation policy out of planner prompt scripts.
- Resume collection through graph state when output is ready.

World and block providers:

- Convert `inspect_world`, `find_world_features`, and executor feedback into
  `world.block`, `world.site`, `world.crop`, and freshness facts.
- Gate block modification primitives on graph-owned observed target facts.
- Record stale-target and support-resolution failures as traceable route
  failures.

Entity and inventory providers:

- Convert nearby entity reads, focus results, and executor terminal events into
  entity and inventory facts.
- Keep item-transfer and entity-action success dependent on observed or
  executor-reported facts, not queued acceptance.

## Runtime Flow

A normal graph-backed action follows this flow:

1. Planner calls `start_action_goal` with a typed goal.
2. Graph runtime creates a goal id and trace id.
3. Providers refresh needed facts from safe read services and current runtime
   snapshots.
4. Resolver builds and ranks route candidates.
5. Runtime dispatches one foreground primitive through existing task executors.
6. Terminal events update facts and trace state.
7. Resolver continues, replans, suspends on a watch, asks for planner review,
   or marks the goal terminal.

Only one foreground execution may own movement/input at a time. Suspended
watches coexist with foreground work but never dispatch primitives. Fulfilled
watches enter a deterministic FIFO runnable queue and resume only after current
foreground work finishes. The scheduler never invents work and never preempts a
foreground goal on watch fulfillment.

Cancellation is unified: cancelling a graph goal must cancel the active
foreground primitive, clear pending graph-owned state for that goal, and record
a terminal trace event.

## Migration Order

1. Architecture spec only.
   - Land this document and review the boundary before code changes.
2. Fact, provider, and action-goal shell.
   - Add graph goal ids, trace ids, typed goal records, primitive metadata, and
     read-only inspection/status surfaces.
3. Crafting and smelting wrappers first.
   - Route `craft_recipe`, `smelt_items`, `collect_smelted_items`, and
     `cancel_smelting` through graph-owned goals/facts/traces.
   - Move recipe expansion and smelting lifecycle policy out of prompt text and
     into providers.
4. Block, movement, resource, transfer, and entity wrappers.
   - Route block mutation, navigation, return-to-surface, resource collection,
     drop/give, and entity interaction through graph-owned goals or primitive
     graph steps.
5. Prompt contraction.
   - Shrink planner prompt policy from procedural sequencing to goal selection,
     safe reads, graph status, and failure review.
6. Persistent facts and watches.
   - Add world-scoped durable facts, watch registration, watch fulfillment,
     suspended goals, and watch-triggered resumption.

Each migration stage must keep existing focused regression tests passing or
update them to assert the new graph identity and trace path.

## Acceptance Scenarios

### Bread Goal

User says "make bread." The planner starts one `inventory_item` goal for
`minecraft:bread`. It should not manually chain `check_craftables` into
`craft_recipe`. The graph may use crafting provider facts, acquisition routes,
or watches. Terminal success requires observed or
executor-reported bread inventory.

### Legacy Craft Tool

A legacy `craft_recipe` call enters the same graph/fact/trace path as the
equivalent graph goal. The result exposes `graphGoalId` and `traceId`, and
subsequent status inspection reads graph state rather than a separate craft-only
pending result path.

### Smelting Fuel Shortfall

A smelting attempt without enough fuel records a missing-fuel fact, subgoal, or
graph failure trace. The planner is not responsible for reading a
`insufficient_fuel` string and hand-writing a recovery script. The resolver may
route to fuel acquisition or ask for review when no route is known.

### Block Action Freshness

A block placement, use, or break action runs only after graph-owned freshness
and guard facts cover the target. Stale or rejected targets become traceable
route failures and can trigger provider refresh or replanning.

### Unified Cancellation

Cancelling a goal cancels the graph goal and any active foreground primitive
through one path. The runtime records cancellation in the trace and no
compatibility tool keeps independent active state afterward.

## Implementation Notes For The Next Plan

- Start with records/interfaces for typed goals, graph goal ids, trace ids, and
  graph status before moving direct tools.
- Treat `ActiveJobRuntime`, `WorldTaskRequest`, and focused task executors as
  primitive dispatch infrastructure.
- Do not remove direct tools in the first migration plan; wrap them and mark
  them legacy in prompt/tool docs.
- Add graph identity to compatibility tool results as soon as the wrapper path
  exists.
- Keep `check_craftables`, `check_smeltables`, and `inspect_smelting` as safe
  reads while making their services provider inputs.
- Defer persistent disk facts and async watches until the graph shell and
  wrapper migrations are stable.

## Assumptions

- This document lands before graph runtime code changes.
- The old `composable-action-graph` branch remains reference material only.
- The first implementation plan will target the graph shell and wrapper path,
  not the full bread vertical slice.
- Direct tools remain visible during migration to avoid breaking current
  workflows, evaluator scenarios, and focused tests.
