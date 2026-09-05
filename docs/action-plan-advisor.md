# Action Plan Advisor Boundary

Airicraft uses `ai.moeru:action-plan-advisor:0.1.0` for deterministic route advice.

The advisor never authorizes or executes a route. It owns recursion, cycle checks,
route caching, depth limits, the exploration budget, and provider ordering.

Airicraft owns mutable facts, provenance, freshness, observations, watches, retries,
cancellation, primitive dispatch, and executor state.

The planner-facing flow is:

```text
recommend_actions (optional, read-only)
  -> AiricraftPlanningSnapshot
  -> AiricraftPlanAdvisor
  -> standalone PlanAdvisor
  -> PlanAdvice
  -> candidate steps returned to planner
  -> planner selects steps (or writes them directly)
  -> commit_action_plan with fresh planContext
  -> ActionGraphExecutionRuntime runs committed steps
  -> primitive admission checks -> active job -> world executor
```

Advice runs off the Minecraft thread over captured values and starts no execution.
`commit_action_plan` takes the final typed goal, a bounded list of `{primitive,
args}` steps, and the context returned by advice or `inspect_action_goal`.
Context changes across runtime reloads, world changes, graph control changes,
and active-job replacement. A stale context cannot replace newer work. Existing
foreground work must be cancelled explicitly before replacement.

Committed execution never invokes the solver, including after successful steps.
Missing requirements and exhausted mechanical retries stop it with
`REPLAN_REQUIRED`, retain the original goal and failed step/reason, release the
foreground lane, and issue one planner notification. The planner chooses recovery;
the execution does not broaden permissions. Existing primitive safety checks,
including mining illumination, remain in force. All steps finishing without an
observed goal also requires a new planner decision, not a success claim.

For this trial, the old automatic goal path remains behind the bridge debug
commands and in-process callers for compatibility. `AutoCommittingRoutePlanner`
still serves those callers. The normal planner no longer advertises or executes
`start_action_goal`. This is not a wholesale removal of the legacy runtime.

Airicraft supplies resource, recipe, smelting, and mining method providers. Each
provider returns generic commands and asks `ResolutionContext` for prerequisites.

The advisor repository is pinned at `vendor/action-plan-advisor`. Gradle uses a
composite build for compilation and Loom Jar-in-Jar packaging for distribution.

Run this command if the submodule is absent:

```bash
git submodule update --init --recursive
```
