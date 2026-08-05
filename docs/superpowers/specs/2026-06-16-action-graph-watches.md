# Action Graph Watches

## Goal

Watches let an action graph route wait for an async world condition without keeping a foreground primitive active.

## Cooperative Scheduler Contract

`ActionGraphCoordinator` owns graph execution residency independently from the
execution state:

- `FOREGROUND` owns movement/input and may resolve, dispatch, or observe a
  primitive.
- `SUSPENDED` remains in `WATCHING` and may only ingest facts and poll watches.
- `RUNNABLE` has a fulfilled watch and waits in fulfillment-tick, then
  creation-order FIFO order.
- `TERMINAL` is retained for bounded inspection history.

Only one execution may be foreground. Registering a watch releases that slot.
A different goal is admitted only when the foreground slot is empty; an
identical nonterminal goal returns its existing execution. Fulfilled watches do
not preempt current work and resume automatically after the slot becomes idle.

The coordinator retains at most 16 nonterminal executions and the latest 32
terminal executions.

## Watch Runtime Contract

When execution reaches a watch step:

- Register a stable watch id scoped to the graph execution and step id.
- Transition the graph to `WATCHING`.
- Keep `activeTaskId` empty unless a foreground primitive is actually running.
- Emit trace events for registration, fulfillment, timeout, and cancellation.
- Emit `action_graph.goal_suspended` once for the suspension transition.
- Resume route advancement only after the watched fact or terminal goal fact is observed.

Passive ticks can ingest facts and evaluate conditions but cannot dispatch a
primitive. They continue during survival reflexes, while runnable executions
wait until actuation is allowed.

## Progress Eligibility

Method providers can set world-progress requirements in `ActionWatchSpec`.
The `AREA_TICKING` kind can use the matched fact as its anchor.

`matched_fact` pins the watch to the exact guard fact identity, including a
crop group's `siteId`. A crop-group fact should expose `origin: {x, y, z}`; an
older fact without it falls back to the agent position at watch registration
and records `watch_anchor_fallback`.

An `area_ticking` watch consumes its timeout only while its world and dimension
match, its anchor chunk is loaded, and the anchor lies inside the server's
client-visible simulation distance. The completion predicate is still checked
while progress is paused so an externally advanced condition can fulfill as
soon as it is observed. Progress pause/resume trace events are transition-only.

Cancellation must use the same graph cancellation path as foreground primitive cancellation. If no primitive is active, cancelling a watch only transitions the graph goal to `CANCELLED`.

## Inspection Contract

Goal inspection exposes:

- `executionId`
- `state`
- `watchCount`
- `pendingWatch`
- `traceEventCount`
- verbose `trace`

`list_action_goals` and the bridge/CLI goal list expose all residencies. Inspect,
trace, and cancellation accept an optional execution id. Without one, inspect
selects foreground or the most recently updated nonterminal execution; cancel
selects foreground or the sole nonterminal execution and rejects an ambiguous
set of suspended executions.

Wrapper watch inspection aggregates all nonterminal graph goals:

```bash
airicraft agent actions watches list [--verbose]
```

## Persistence Boundary

Pending watches are volatile by default. Fulfilled watches may be persisted as durable facts if they represent reusable world knowledge.

The first persistent fact policy reflects this:

- `watch.pending` is volatile.
- `watch.fulfilled` is persistent by default.

## Non-Goals

- Do not block the planner prompt while a watch is pending.
- Do not keep Baritone or another primitive executor active for a passive condition wait.
- Do not make watch polling a planner responsibility.
- Do not invent graph-defined filler jobs when a goal suspends. The one-time
  planner notification may lead to a brief explanation, one useful high-level
  graph goal, or no new work.
