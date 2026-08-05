# Action Plan Advisor Boundary

Airicraft uses `ai.moeru:action-plan-advisor:0.1.0` for deterministic route advice.

The advisor never authorizes or executes a route. It owns recursion, cycle checks,
route caching, depth limits, the exploration budget, and provider ordering.

Airicraft owns mutable facts, provenance, freshness, observations, watches, retries,
cancellation, primitive dispatch, and executor state.

The runtime flow is:

```text
start_action_goal
  -> AiricraftPlanningSnapshot
  -> AiricraftPlanAdvisor
  -> standalone PlanAdvisor
  -> PlanAdvice
  -> AutoCommittingRoutePlanner
  -> ActionRoute
  -> ActionGraphExecutionRuntime
```

`AutoCommittingRoutePlanner` is a compatibility adapter. It validates the advice
and commits one recommendation to the existing action graph runtime.

Airicraft supplies resource, recipe, smelting, and mining method providers. Each
provider returns generic commands and asks `ResolutionContext` for prerequisites.

The advisor repository is pinned at `vendor/action-plan-advisor`. Gradle uses a
composite build for compilation and Loom Jar-in-Jar packaging for distribution.

Run this command if the submodule is absent:

```bash
git submodule update --init --recursive
```
