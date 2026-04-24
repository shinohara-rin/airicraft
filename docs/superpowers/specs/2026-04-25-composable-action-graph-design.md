# Composable Action Graph Runtime Design

## Summary

Airicraft should move complex behavior out of planner micromanagement and into a composable action graph runtime.

The core product model is:

- Java registers typed primitive actions that can mutate or inspect Minecraft state.
- YAML actionsets compose primitives into higher-level reusable skills.
- A resolver builds and executes dependency routes from desired facts.
- The LLM planner chooses goals, reviews ambiguity, and can author or patch YAML actionsets through scoped tools.
- The runtime remains the source of truth for facts, execution, watches, traces, and cancellation.

The first end-to-end proof is:

> From a fresh vanilla survival overworld, make bread.

That test is intentionally hard enough to exercise inventory inspection, route alternatives, seed acquisition, tool/crafting dependencies, site discovery, tilling, planting, async crop growth, harvesting, crafting, failure traces, replanning, and planner-assisted actionset repair.

## Goals

- Support complex behavior through reusable action primitives instead of one-off planner prompts.
- Let high-level tasks such as farming, building, chopping, hunting, and exploration share the same primitive vocabulary.
- Allow the planner and operator tools to inspect, validate, create, and patch YAML actionsets without adding Java code for every new skill.
- Preserve deterministic execution for world mutation: YAML may compose actions, but Java primitives remain the only actuation layer.
- Track world state as typed runtime facts, not as planner memory.
- Support async commitments such as "wait for wheat to mature" without blocking the bot's body.
- Expose enough CLI/debug state for live development, trace inspection, and recovery.

## Non-Goals

- No arbitrary code execution from YAML or planner-authored actionsets.
- No full safety/protection model in the proof of concept. Destructive actions are acceptable while exploring boundaries.
- No attempt to solve all building, hunting, farming, and survival automation in the first implementation stage.
- No replacement of existing primitive executors where they already work.
- No requirement that every route is LLM-reviewed before execution.

## Existing Architecture To Reuse

Airicraft already has the important split this design needs:

- Planner/chat input flows through `DialogueRuntime` and `PlannerOrchestrator`.
- Planner-visible tools are described through `PlannerToolCatalog`, `PlannerToolRegistry`, and provider-style extensions.
- Long-running jobs flow through `ActiveJobRuntime`.
- Concrete world execution flows through `WorldTaskRequest` and `DispatchingWorldTaskExecutor`.
- Existing primitive executors include Baritone-backed navigation/mining, crafting, drop/give item, and entity interaction.
- `PlannerContextAggregator` and `PlannerAmbientContext` already summarize runtime state back to the planner.

The new system should extend these seams instead of adding a parallel companion controller.

## Product Concepts

### Primitive Action

A primitive action is a Java-registered capability with a stable schema and executor binding.

Examples:

- `inspect_inventory`
- `inspect_area`
- `find_block`
- `pathfind_to`
- `mine_block`
- `interact_block`
- `place_block`
- `use_item`
- `craft_item`
- `attack_entity`
- `wait_for_fact`

Each primitive declares:

- id
- version
- summary
- parameter schema
- guard fact types that must already be true
- need fact types that the resolver may satisfy before dispatch
- produced fact types
- consumed fact types
- cost hints
- failure reasons
- whether it owns foreground actuation
- whether it is cancellable
- default timeout
- capability tags
- executor binding to existing or new `WorldTaskRequest` variants
- planner/operator documentation
- trace payload shape

Primitive docs should be generated or exposed from the registry so the planner can research the local action system without broad filesystem or web access.

Actionsets may require primitive version ranges:

```yaml
uses:
  craft_item: ">=1"
  mine_block: ">=1"
```

### Fact

A fact is a typed statement about the world, inventory, a pending watch, or an execution result.

Facts are runtime-owned and provenance-aware:

- observed by scan
- executor-reported by a terminal event
- expected by an actionset or selected route
- expected by watch
- inferred by resolver
- failed by route attempt
- stale

Initial fact families:

- `inventory.item`
- `inventory.tool`
- `world.block`
- `world.crop`
- `world.site`
- `world.entity`
- `craft.recipe`
- `watch.pending`
- `watch.fulfilled`
- `route.failure`

Facts must define identity keys so the fact store can merge updates deterministically.

Initial identity rules:

- `inventory.item`
  - identity: `worldId + actorId + itemId`
  - payload: count, slots when known, observed tick, stale policy, source
- `inventory.tool`
  - identity: `worldId + actorId + toolTag`
  - payload: item ids, durability when known, observed tick, stale policy, source
- `world.block`
  - identity: `worldId + dimension + blockPos`
  - payload: block id, block state summary, reachability, observed tick, stale policy, source
- `world.crop`
  - identity: `worldId + dimension + blockPos`
  - payload: crop id, age/mature flag, site id when known, observed tick, stale policy, source
- `world.site`
  - identity: `worldId + dimension + siteId`
  - payload: region, site type, aggregate crop/block counts, reachability, loaded-area policy, observed tick, stale policy, source
- `world.entity`
  - identity: `worldId + dimension + entity uuid`
  - payload: type id, name, position, alive flag, health when known, observed tick, stale policy, source
- `craft.recipe`
  - identity: `worldId + actorId + output item id + recipe id`
  - payload: output count, grid kind, required ingredients, observed tick, stale policy, source
- `watch.pending` / `watch.fulfilled`
  - identity: `worldId + watchId`
  - payload: watched fact, owner goal id, timeout, loaded-area requirement, last poll, source
- `route.failure`
  - identity: `worldId + normalized goal key + route id + failure code`
  - payload: count, last failure tick, last trace id, source

Aggregate facts are required. The resolver should not rely on planner-visible block-level lists to answer questions such as "mature wheat count near site X >= 3." Use aggregate facts such as `world.site` crop counts or explicit crop-group facts derived from block observations.

Facts carry freshness and scope:

- world/server identity
- dimension
- position or region when applicable
- tick and wall-clock observation time
- stale-after policy

The planner may see summaries and may request detailed facts, but the planner is not the fact database.

Actionset-declared `produces` facts are not truth by themselves. Route selection may create expected facts, but observed or executor-reported facts must confirm terminal state before goals succeed. Resolver ranking should prefer observed and executor-reported facts over inferred or expected facts.

### Actionset

An actionset is a YAML document that defines reusable high-level actions.

Actionsets are declarative graphs with guards, not arbitrary workflows. They can express:

- parameters
- guards that must already hold for an alternative
- needs that the resolver may satisfy recursively as subgoals
- produced facts
- consumed facts
- alternatives
- costs
- ordered steps
- fallback routes
- retry budgets
- watches

Actionsets are an HTN/GOAP hybrid:

- actionset = named method for achieving produced fact(s)
- alternative = candidate method for that actionset
- guards = facts that must already hold for that alternative
- needs = subgoals the resolver may satisfy before running the alternative
- steps = ordered primitive, actionset, goal, or watch calls
- produces = expected terminal facts
- consumes = optional expected inventory/resource consumption

Ordered steps are allowed. Full arbitrary workflow code is not. Long waits are represented as fact watches, and repeated progress is represented by resolver re-entry after facts change.

Step kinds are explicit to avoid name collisions:

```yaml
steps:
  - id: craft_bread
    primitive: craft_item
    args: {}

  - id: obtain_wheat
    goal:
      fact: inventory.item
      itemId: minecraft:wheat
      countAtLeast:
        expr: "params.quantity * 3"

  - id: prepare_site
    actionset: prepare_farmland_site
    args: {}

  - id: wait_for_wheat
    watch: {}
```

Stage 1 should use a small typed expression subset, not arbitrary string templating:

- integer literals
- `params.<name>`
- `steps.<stepId>.<outputName>`
- `+`, `-`, `*`, `/`
- optional `min()` / `max()` only if the implementation plan needs them

Expressions are typechecked by the validator. If Stage 1 needs an even smaller surface, use explicit parameter multipliers such as:

```yaml
countAtLeastFromParam:
  param: quantity
  multiplier: 3
```

Example shape:

```yaml
version: 1
actions:
  make_bread:
    summary: Produce bread in inventory.
    params:
      quantity:
        type: integer
        default: 1
        min: 1
    produces:
      - fact: inventory.item
        itemId: minecraft:bread
        countAtLeast:
          expr: "params.quantity"
    alternatives:
      - id: already_have_bread
        cost: 0
        guards:
          - fact: inventory.item
            itemId: minecraft:bread
            countAtLeast:
              expr: "params.quantity"
        steps: []
      - id: craft_from_inventory_wheat
        cost: 10
        guards:
          - fact: inventory.item
            itemId: minecraft:wheat
            countAtLeast:
              expr: "params.quantity * 3"
        steps:
          - id: craft_bread
            primitive: craft_item
            args:
              itemId: minecraft:bread
              quantity:
                expr: "params.quantity"
      - id: obtain_wheat_then_craft
        cost: 40
        needs:
          - fact: inventory.item
            itemId: minecraft:wheat
            countAtLeast:
              expr: "params.quantity * 3"
        steps:
          - id: craft_bread
            primitive: craft_item
            args:
              itemId: minecraft:bread
              quantity:
                expr: "params.quantity"
```

Separate actionsets should handle subgoals such as `obtain_wheat`, `obtain_tool`, `prepare_farmland_site`, and `bootstrap_wheat_farm`.

### Resolver

The resolver accepts a desired fact and builds candidate routes from primitives and actionsets.

Responsibilities:

- validate actionsets
- expand action dependencies
- detect cycles
- bind parameters
- query current facts
- rank route alternatives by cost, confidence, freshness, and failure history
- choose a route without planner involvement when confidence is high
- emit one foreground primitive at a time
- update route state after primitive terminal events
- suspend route execution on watches
- replan on failure, stale facts, or watch fulfillment
- produce concise debug traces

The default route-selection rule is resolver-first. The LLM planner is pulled in only for ambiguity, repeated failures, stale/conflicting facts, or high-cost tradeoffs.

The first implementation should be an ordered HTN executor with limited recursive `needs`, not a full GOAP search engine.

Reason:

- HTN-style actionsets match the YAML mental model.
- Ordered steps produce clearer traces.
- Recursive `needs` are enough for bread-style dependency expansion.
- Full GOAP search can be added later if this model proves too rigid.

Resolver limits are part of the spec, not a performance afterthought:

- maximum route depth
- maximum expanded nodes
- maximum alternatives per goal
- maximum parameter bindings
- maximum wall-clock resolve time
- maximum primitive cost
- maximum total route cost
- maximum retry count

Cycle detection uses a normalized goal key:

```text
fact type + identity fields + constraints + world scope + bound params
```

Examples:

- `inventory.item(world=current, actor=self, itemId=minecraft:wheat, countAtLeast=3)`
- `world.site(world=current, dimension=overworld, siteType=farmland, cropId=minecraft:wheat, minPlots=3)`

This prevents loops such as `obtain_item(wheat) -> make_bread -> obtain_item(wheat)`.

### Route Trace

Trace output is required from Stage 1 onward because planner-authored actionsets need a debug loop.

Minimum trace events:

- `goal_started`
- `fact_query`
- `route_candidate_built`
- `route_selected`
- `step_started`
- `primitive_dispatched`
- `primitive_succeeded`
- `primitive_failed`
- `fact_added`
- `watch_registered`
- `watch_fulfilled`
- `watch_timed_out`
- `route_replanned`
- `goal_cancelled`
- `goal_succeeded`
- `goal_failed`

Each trace event carries:

- trace id
- goal id
- route id
- step id when applicable
- tick
- wall time
- world id
- payload

### Foreground Jobs And Background Watches

The bot has one body. Only one foreground primitive may own movement/input at a time.

The bot may have many background watches and commitments:

- planted wheat may mature
- furnace may finish
- daylight may arrive
- a player may return nearby
- an inventory target may become satisfied
- a hostile entity may enter range

Watches are not blocking `wait` tasks. They are durable fact subscriptions that can wake the resolver when satisfied or timed out.

Example:

```yaml
watch:
  fact: world.crop_group
  cropId: minecraft:wheat
  site:
    expr: "steps.prepare_site.siteId"
  matureCountAtLeast:
    expr: "params.quantity * 3"
  requiresLoadedArea: true
  poll:
    whenNearby: true
    intervalTicks: 200
  timeout:
    gameTicks: 24000
    onTimeout: replan
```

Minecraft-specific watch policy:

- Crop growth depends on loaded/ticking area.
- If a watch has `requiresLoadedArea: true`, the idle scheduler should keep the bot near enough for the area to tick unless a higher-priority foreground goal preempts it.
- If the bot leaves the loaded area, the watch remains pending but should record that progress is not expected while unloaded.
- Polling confirms facts; timers are only hints and timeout budgets.

If a user asks for bread while an existing farm is growing, the resolver should attach the bread goal to the existing wheat-maturity watch rather than bootstrap a duplicate farm.

## Runtime Architecture

### New Runtime Blocks

- `PrimitiveActionRegistry`
  - owns Java primitive metadata and executor bindings
- `ActionsetStore`
  - scoped load/save/list/read/write for YAML actionsets
- `ActionsetValidator`
  - validates schema, primitive references, fact references, cycles, and parameter binding
- `FactStore`
  - world-scoped persistent typed facts and watches
- `ActionGraphResolver`
  - builds and ranks dependency routes
- `ActionGraphRuntime`
  - owns active graph goals, foreground primitive dispatch, suspended commitments, and traces
- `ActionTraceStore`
  - keeps recent and persisted traces for CLI and planner debugging

### Existing Blocks To Extend

- `ActiveJobRuntime`
  - add `ACTION_GRAPH` as a parent job type
- `WorldTaskRequest`
  - add missing primitive request variants as stages require them
- `DispatchingWorldTaskExecutor`
  - route new primitive request variants to focused executors
- `PlannerToolRegistry`
  - expose actionset and action-graph tools
- `PlannerAmbientContext`
  - summarize active graph goals, pending watches, selected route, and last failure
- `ModBridgeServer`
  - expose action graph bridge endpoints
- wrapper CLI
  - expose `airicraft actions ...`

## Planner And Operator Surfaces

### Planner Tools

Planner tools should be scoped to the action system:

- list primitive docs
- list actionsets
- read actionset
- write actionset draft
- validate actionset
- resolve desired fact
- start action goal
- inspect action goal status
- inspect recent trace

Planner writes are limited to the configured actionset folder and must pass validation before execution.

Planner-authored YAML still needs blast-radius controls even in a destructive proof of concept:

- actionsets declare allowed primitive categories
- actionsets declare max cost budget
- actionsets declare max route depth
- actionsets declare max retry count
- destructive primitives require explicit allowlisting in the actionset namespace
- drafts and enabled actionsets are separate
- only validated enabled actionsets enter the resolver index

Suggested actionset layout:

```text
actionsets/
  builtin/
  operator/
  planner_drafts/
  enabled/
```

Invalid planner drafts may persist under `planner_drafts/`, but they must never load into the enabled resolver index.

### CLI Debug Surface

Add an `actions` namespace:

```text
airicraft actions primitives
airicraft actions actionsets list
airicraft actions actionsets show --id <id>
airicraft actions actionsets validate [--id <id>|--all]
airicraft actions actionsets reload
airicraft actions facts list [--world current]
airicraft actions watches list
airicraft actions resolve --goal <fact-expression>
airicraft actions start --action <id> [--arg key=value]
airicraft actions status
airicraft actions traces list
airicraft actions traces show --trace-id <id>
airicraft actions cancel --goal-id <id>
```

These commands should follow the existing CLI output contract: deterministic plain text, `status: ok` / `status: error`, stable error codes, and parse failures exiting `2`.

Validation errors should use stable paths and codes:

```text
status: error
command: actions actionsets validate
error_code: actionset.validation_failed
actionset: make_bread
errors:
- code: unknown_primitive
  path: $.actions.make_bread.alternatives[0].steps[0].primitive
  message: unknown primitive "craft_recipee"
- code: type_mismatch
  path: $.actions.make_bread.params.quantity.default
  expected: integer
  actual: string
```

Exit code policy:

- `0` success
- `2` CLI parse or validation failure, matching the current CLI parse/validation contract
- `3` bridge discovery or transport failure
- `4` bridge/domain/state failure
- `1` unexpected internal failure

## Staged Delivery

Each stage must produce working, testable software on its own. Do not start implementation plans for later stages until the current stage has passed its acceptance gates.

### Stage 0: Spec And Boundaries

Goal:

- Settle the product and architecture spec.

Deliverables:

- This design document.
- Follow-up implementation plan only for Stage 1.

Acceptance:

- Spec names the product model, async model, JIT boundary, planner role, fact model, and staged delivery gates.
- No code implementation is started.

### Stage 1: Primitive And Fact Foundations

Goal:

- Add non-actuating foundations: primitive registry metadata, fact model, YAML schema draft, validator, and CLI inspection.

Deliverables:

- Java primitive metadata registry with docs for existing capabilities.
- Typed fact model and in-memory fact store.
- YAML actionset parser and validator.
- `airicraft actions primitives`, `actionsets list/show/validate`, and `facts list`.
- Seeded `make_bread` actionset that validates but is not fully executable yet.

Acceptance:

- Actionset schema distinguishes `guards`, `needs`, `steps`, `produces`, and `consumes`.
- Step references are explicit: `primitive`, `actionset`, `goal`, or `watch`.
- Parameter expressions are typechecked.
- Quantity expressions in `make_bread` validate `bread quantity -> wheat quantity * 3`.
- Fact identity keys exist for each initial fact family.
- Primitive docs include version, failure codes, foreground ownership, cancellability, timeout, and capability tags.
- Validator reports JSONPath/YAMLPath-like error paths.
- Invalid YAML reports stable validation errors.
- Unknown primitive references are rejected.
- Cycles are rejected.
- Invalid planner drafts cannot enter the enabled actionset index.
- Primitive docs are visible through CLI and planner-facing tool surface.
- No world mutation is required for this stage.

### Stage 2: Action Graph Execution Over Existing Primitives

Goal:

- Execute action graph routes that can be satisfied by current primitives.

Deliverables:

- `ACTION_GRAPH` active job type.
- Resolver route expansion and route trace model.
- Runtime dispatch from graph step to one active `WorldTaskRequest`.
- CLI `actions resolve/start/status/traces/cancel`.
- Planner tool to start an action goal.

Acceptance:

- A seeded actionset can route to existing `collect_resource`, `craft_recipe`, `drop_items`, or entity primitives.
- Only one foreground primitive owns actuation at a time.
- Cancellation clears the graph and active primitive.
- Terminal primitive failures become graph trace failures and can trigger fallback.

### Stage 3: JIT Actionset Authoring

Goal:

- Let the planner and operator create or patch actionsets dynamically, with validation before execution.

Deliverables:

- Scoped actionset folder under Airicraft config/state.
- Planner tools for list/read/write/validate actionsets.
- Bridge and CLI reload path.
- Trace-to-validation loop for debugging failed actionsets.

Acceptance:

- Planner-authored YAML cannot reference unknown primitives or invalid facts.
- Invalid drafts are stored or rejected according to the final implementation plan, but never executed.
- A valid patched actionset can be reloaded without restarting the client.
- Planner prompt context shows summaries, not full actionset dumps.

### Stage 4: World-Scoped Fact Store And Async Watches

Goal:

- Support durable world facts, suspended goals, and watch-triggered replanning.

Deliverables:

- World/server-scoped persistent fact store.
- Watch model with poll intervals, stale facts, fulfillment, timeout, and replan events.
- Planner and CLI summaries for active goals and pending watches.
- Resolver behavior for suspending routes on watches and resuming when facts change.

Acceptance:

- A graph goal can suspend on a watch without occupying foreground actuation.
- Another foreground goal may run while a watch is pending.
- Watch fulfillment wakes the resolver.
- Watch timeout records a trace event and triggers fallback or planner review.
- Facts do not leak across worlds.

### Stage 5A: Bread From Existing Facts

Goal:

- Make `make_bread` work when required facts already exist.

Deliverables:

- `make_bread` routes for already-have-bread and craft-from-inventory-wheat.
- Deterministic resolver tests for `quantity * 3` wheat needs.
- `craft_item` primitive binding or wrapper over existing crafting support.
- CLI trace output for selected route and terminal facts.

Acceptance:

- Final terminal condition is `inventory.item minecraft:bread >= requested quantity`.
- `make_bread(quantity: 2)` requires six wheat before crafting.
- Declared `produces` does not mark bread as observed until executor or inventory inspection confirms it.

### Stage 5B: Controlled Farm Watch, Harvest, And Craft

Goal:

- Support planted/mature wheat facts and async watch resumption for a controlled farm site.

Deliverables:

- `world.site` and crop aggregate facts for reachable mature wheat counts.
- Harvest primitive or primitive binding.
- Crop-group watch with `requiresLoadedArea`.
- Resolver route for existing growing farm -> watch -> harvest -> craft bread.

Acceptance:

- If wheat is planted and not mature, the bread goal suspends on a watch rather than blocking the bot.
- If the watch requires loaded area, idle scheduling keeps the bot nearby unless preempted.
- Watch fulfillment resumes the bread route.
- If an existing farm is known, a new bread request attaches to it instead of starting from scratch.

### Stage 5C: Fresh Survival Bootstrap Bread

Goal:

- Bootstrap bread production from normal vanilla survival terrain.

Deliverables:

- Seed acquisition route.
- Wood/plank/stick/hoe acquisition route.
- Suitable hydrated farm-site discovery.
- Tilling and planting primitives or bindings.
- Fresh-world verification scenario or live smoke script.

Acceptance:

- From a fresh vanilla survival overworld with normal terrain assumptions, the agent can progress toward bread through the action graph.
- Failure traces explain the blocking fact when seeds, water, soil, wood, crafting, or crop growth cannot progress.
- Route fallback can replan from seed/hoe/site failures without restarting the goal.

### Stage 6: Modes And Generalized Skills

Goal:

- Build persistent behavior modes on top of action graphs.

Deliverables:

- Mode policy model for farming, chopping, exploration, hunting, and building.
- Idle policy that chooses useful runnable goals from active mode commitments.
- Planner and CLI mode start/stop/status controls.

Acceptance:

- Modes do not bypass primitive registry, fact store, or action graph traces.
- Idle actions run through the same resolver and foreground actuation rules.
- Planner can inspect current mode commitments and failures.

## Bread Route Requirements

The bread proof should support these route families:

1. Already have bread.
2. Have wheat and craft bread.
3. Find mature wheat and harvest.
4. Use existing growing wheat farm and wait via watch.
5. Bootstrap wheat production:
   - find or obtain seeds
   - obtain a hoe
   - find or prepare suitable farmland near water
   - till soil
   - plant wheat
   - watch maturity
   - harvest
   - craft bread

Route ranking should initially prefer shorter and more certain routes. Planner review is only required when the resolver has low confidence, repeated failures, or competing high-cost routes.

Bread quantity math is part of validation:

```text
requested bread quantity = N
required wheat count = N * 3
```

The cleaner dependency graph is:

```text
make_bread -> obtain_wheat -> harvest / existing farm / bootstrap farm
```

Recommended `make_bread` skeleton:

```yaml
version: 1
actions:
  make_bread:
    summary: Produce bread in inventory.
    params:
      quantity:
        type: integer
        default: 1
        min: 1

    produces:
      - fact: inventory.item
        itemId: minecraft:bread
        countAtLeast:
          expr: "params.quantity"

    alternatives:
      - id: already_have_bread
        cost: 0
        guards:
          - fact: inventory.item
            itemId: minecraft:bread
            countAtLeast:
              expr: "params.quantity"
        steps: []

      - id: craft_from_inventory_wheat
        cost: 10
        guards:
          - fact: inventory.item
            itemId: minecraft:wheat
            countAtLeast:
              expr: "params.quantity * 3"
        steps:
          - id: craft_bread
            primitive: craft_item
            args:
              itemId: minecraft:bread
              quantity:
                expr: "params.quantity"

      - id: obtain_wheat_then_craft
        cost: 40
        needs:
          - fact: inventory.item
            itemId: minecraft:wheat
            countAtLeast:
              expr: "params.quantity * 3"
        steps:
          - id: craft_bread
            primitive: craft_item
            args:
              itemId: minecraft:bread
              quantity:
                expr: "params.quantity"
```

`obtain_wheat` should be a separate actionset whose alternatives can cover mature crops, existing growing farms, villages/chests later, and fresh farm bootstrap.

## Open Design Items For Stage 1 Plan

These must be refined in the Stage 1 implementation plan without reopening the architecture:

- Exact Java records/classes for typed facts and identity keys.
- Exact YAML parser representation for `guards`, `needs`, `produces`, `consumes`, and explicit step kinds.
- Exact expression representation and typechecker surface.
- Exact actionset folder paths for `builtin`, `operator`, `planner_drafts`, and `enabled`.
- Exact invalid-draft behavior: persist under `planner_drafts` or reject before write.
- Exact primitive metadata serialization shape.
- Exact CLI rendering for validation errors and trace snippets.

## Verification Strategy

Use a layered verification split:

- Schema and validator tests for actionset correctness.
- Resolver unit tests for route choice and cycle/failure handling.
- Runtime tests for `ACTION_GRAPH` foreground primitive ownership.
- Fact store tests for world scoping, staleness, watches, and persistence.
- Planner tool tests for scoped actionset authoring.
- CLI tests for deterministic output.
- Live smoke only after the route is executable.

The bread scenario should have both:

- a deterministic verification path where facts or crop maturity can be controlled, and
- a real survival smoke path where normal gameplay timing and failures are observable.

## Default Decisions

- Use resolver-first route choice.
- Use planner review only on ambiguity or failure.
- Use typed facts, not free-form string predicates.
- Use HTN-style YAML actionsets with typed facts, guarded alternatives, recursive needs, and ordered steps.
- Use explicit step kinds: `primitive`, `actionset`, `goal`, and `watch`.
- Use `guards` for facts that must already hold and `needs` for facts the resolver may satisfy recursively.
- Use a small typed expression subset instead of arbitrary string templates.
- Use world-scoped disk persistence for facts and watches.
- Use seeded editable `make_bread` actionset for the first vertical slice.
- Use `ACTION_GRAPH` as a new parent job type rather than replacing current jobs immediately.
- Keep one foreground actuation primitive at a time.
- Keep many background watches and commitments.
- Let both planner and operator/Codex work with actionsets through scoped tools and CLI.
- Keep invalid planner drafts out of the enabled resolver index.
