# System 1 implementation evidence

Tracks the [implementation plan](superpowers/plans/2026-09-06-system-one-redesign.md). The full redesign remains in progress. A passing stone fixture does not establish completion of the iron-pickaxe or game-completion gates.

## Implemented so far

- `TaskKernel`: immutable inputs/results and bounded transitions; child tasks retain parent continuations; commands have session/run/task/attempt identity; replacement work waits for acknowledged release. Synthetic-domain tests cover repair/resumption, stale feedback, cancellation, alternate-method recovery, budgets, and replay.
- Opt-in replacement entrance: `scripts/run-evaluation-scenarios --system-one` implies no-LLM mode. Its tick path bypasses the legacy execution/reflex loop. Structured inventory goals enter reactive production tasks.
- Geometric perception: bounded rays stop at the first occupied voxel, do not cross occluding corners, hide unlit identities, and restrict dark geometry to the near field. This is a geometric sensor, not screenshot recognition or proof of human-equivalent visibility.
- Baritone terrain boundary: block-state interfaces capture immutable observed terrain; hidden live/cached cells are replaced by a non-traversable, non-supporting sentinel. Execution-time direct reads also use observations. Navigation cannot issue attack/use input, perform mining/placement preparation, or select buckets. Scoped settings disable independent avoidance, inventory/tool selection, parkour, sprint, and unloaded-goal simplification. See the [source audit and live probe](system-one-baritone-boundary.md); paired autonomous playthroughs and broader terrain variants remain required.
- Exact breaking aims at and ray-checks the target face, verifies reach/tool eligibility, and avoids breaking the player's own footing. Soil excavation prioritizes lower exposed surfaces and preserves tools when an empty hand is equally effective.
- Excavation surveys on demand rather than performing four quarter-turns after every move. This correction follows the user's live observation of repeated spins.
- Decision capture records an initial mission plus every tick's observation delta, motor feedback, cancellation, effects, and lifecycle events. A bounded background writer reports overflow/storage failure as incomplete; evaluator cleanup waits for motor release and writer completion. Offline replay rejects missing turns, incompatible versions, altered decisions, and missing/incomplete footers.

- Production tasks load crafting patterns from the active recipe catalog, preserve parent ingredient reservations, account for recipe yields, and terminate prerequisite cycles. Observed resource sources affect recipe choice. Child tasks harvest logs, bootstrap tools, acquire/place tables, and return toward remembered workstations. Crafting, exact placement, and inventory selection stay under the motor owner; crafting release drains the cursor and input grid. Production recordings include the complete immutable recipe/prior catalog and support offline replay.

The empty-inventory stone-pickaxe production fixture now passes live. Excavation protects visited footholds, clears a lower step before walking into it, and retains the destination while clearing overhead entry space. Evaluation cleanup preserves an already pending terminal outcome while waiting for physical release. Smelting load/wait/collect tasks, catalog-derived fuel durations, and an exposed-resource iron fixture are implemented but not yet live-validated successfully. Lighting repairs, survival control, and the original iron-pickaxe gate remain unimplemented or unverified.

## Tests and live evidence

Focused command:

```bash
source .envrc
./gradlew :test --tests 'ai.moeru.airicraft.systemone.*'
```

At 2026-09-07, 51 tests pass: 11 kernel, 6 geometric sensing, 11 acquisition, 9 production, 6 decision replay, 3 recording tests, and 5 smelting-production tests. The 32 evaluator tests and evaluation-launcher Python suite also pass (24 launcher tests). Four recording-inspector tests cover old-policy diagnosis and incomplete input detection.

Live fixture command:

```bash
source .envrc
scripts/run-evaluation-scenarios --system-one --scenario system-one-stone \
  --recorder-jar /absolute/path/to/recorder-profile.jar \
  --stop-client-after-scenario --bridge-timeout-seconds 240
```

The evaluator prepares a disposable platform: stone underneath three soil layers, with one wooden pickaxe supplied. Fixture construction executes on the integrated-server thread before goal submission. Setup state and coordinates are not supplied to the agent. This fixture deliberately isolates acquisition; it does not replace the original iron-pickaxe scenario. Use `--scenario system-one-production` for the empty-inventory version with an observed log column and a stone-pickaxe goal. That fixture supplies no tools or ingredients directly to the player.

| Run directory under `eval-output/` | Evidence |
| --- | --- |
| `20260906-195642-628152-51720` | First look/break succeeded; first navigation crashed because one Baritone constructor overload had not captured its terrain view. Corrected the constructor selector. |
| `20260906-195834-754333-53801` | Interrupted rerun exited before a terminal scenario report. No gameplay success claim. |
| `20260907-154110-841283-61691` | Navigation worked, but excavation widened the surface and wore out the pickaxe. The fixture incorrectly gated evaluator ticks on remaining at the starting height, causing recording to stop after descent. Recovered 485 remaining System 1 events from the live event buffer; earlier events were truncated. Gracefully stopped the client. |
| `20260907-154739-926189-63272` | Obtained three cobblestone in 446 elapsed ticks; scenario PASSED and harness OK. Recorder Play finalized. This run still performed routine panorama scans; the follow-up run tests their removal. |
| `20260907-155134-120434-64304` | Obtained three cobblestone in 376 elapsed ticks with zero survey-look commands. Scenario PASSED; harness OK; Recorder Play finalized. `system-one-final.json` confirms SUCCEEDED and no remaining motor command, pathing process, or pending release. Planner journal is empty and final LLM records are empty. |
| `20260907-160438-513978-67297` | Obtained three cobblestone in 376 elapsed ticks; scenario PASSED and harness OK. Decision recording completed with 312 input rows. Offline replay verified all 311 turns, matching effects, lifecycle events, and SUCCEEDED inventory evidence. |
| `20260907-162016-195995-72174` | Live startup caught an incorrect overload selected when remapping the passability hook. Client failed before gameplay; corrected the selector to an explicit descriptor. |
| `20260907-162237-150390-72866` | Live adapter probe held its authorized corridor fixed while changing 102 hidden cells. All 132 movement results, the A* route, and cost matched. Subsequent autonomous stone goal PASSED in 388 ticks with zero planner turns. |
| `20260907-162605-529498-73805` | Final probe also checks placement rejection and bucket-selection isolation. Paired route/cost checks passed; autonomous stone goal PASSED in 382 ticks, harness OK, zero planner calls, and motor fully released. All 312 decision turns replayed offline. |
| `20260907-164921-022470-78966` | First production attempt chose acacia ingredients despite observed oak. Saved events identified the incorrect cost estimate; stopped the run and added an observed-resource preference regression. No terminal success claim. |
| `20260907-165313-281422-80092` | Harvested logs, crafted the table and wooden pickaxe, and obtained three cobblestone. Failed because a nearby remembered table was occluded. Replay reproduced all 693 decision turns and the failure. Added station visibility/return logic and placement body-clearance checks. |
| `20260907-170343-610028-83181` | Return attempts failed: excavation had removed earlier footholds. The fallback created another table but could not place it from the shaft. Scenario FAILED in 824 elapsed ticks; zero planner turns. This exposes an acquisition defect that the stone-only fixture did not exercise. |
| `20260907-171617-714595-86198` | Protecting visited footholds alone caused surface widening: walking onto each cleared cell immediately protected the floor that needed excavation. Stopped this repetitive run and corrected the step sequence. |
| `20260907-171921-268801-87086` | The corrected sequence descended two steps and preserved footholds. Later descent failed because the destination ceiling blocked entry despite two-block standing clearance. Recorded observations identified the obstructing grass block. All 967 turns replayed with policy v3 at checkpoint `dc70f25`. Added a retained step destination and overhead preparation. |
| `20260907-172346-877489-88255` | Empty-inventory stone-pickaxe goal PASSED in 683 ticks, zero planner turns. Cleanup overwrote pending success with cancellation; all 618 turns reproduced that race offline. Corrected cleanup to preserve terminal decisions while waiting for release. |
| `20260907-172709-641844-89225` | Final production verification PASSED in 690 elapsed ticks; harness OK, zero planner/LLM calls, runtime SUCCEEDED, motor fully released, client exited normally. All 628 decision turns replayed offline. There were 16 break commands, 7 crafts, 7 navigations, 1 placement, and zero survey turns; one occluded target recovered. |
| `20260907-174348-876499-93376` | First exposed-iron attempt FAILED before motor actuation: resource cost estimation preferred an unavailable diamond tool. Cyclic block-compression recipes divided a finite unavailability penalty into an apparently cheaper cost. The 8 recorded decision turns replay exactly with production policy v5. |

The failed recording is a concrete recorder/harness finding: an artifact existing on disk does not prove that recording continued through gameplay. Setup completion is now latched once, and post-outcome recording includes motor release before cleanup finishes. The final production Recorder Play was rendered successfully to 676 frames and `recording-render/fpv.mp4`. Sampled first-person frames were visually inspected through log harvesting, excavation, cobblestone collection, and return toward the table. The renderer covers its available server ticks 28–703, versus requested 28–724, and reports 314 ignored unsupported packets (310 chunk unloads, 4 player positions). Its images are supporting evidence; they do not independently prove final inventory or exact runtime-tick alignment.

Replay the recorded live decision inputs (no Minecraft client required):

```bash
./gradlew replaySystemOne \
  -Pairicraft.replayFile="$PWD/eval-output/20260907-172709-641844-89225/01-system-one-production/system-one-decisions.jsonl.gz"
```

This verifies the deterministic decision boundary; it does not replay physics or prove sensor fairness. The replay entry point reads both stone and production records. Policy revisions must match the recorded decisions; historical failures should be replayed from their implementation checkpoint.

Inspect any historical policy's recording without launching Minecraft:

```bash
scripts/inspect-system-one-recording \
  eval-output/20260907-171921-268801-87086/01-system-one-production/system-one-decisions.jsonl.gz
```

The compact report includes inventory changes, command counts, failure reasons and the last failed command's parent chain. Input coverage is checked separately from decision replay; truncated compression, missing/reordered turns, missing footers, and trailing records report incomplete coverage. The inspector never claims policy replay.

## Outstanding plan gates

1. Extend the verified live decision recording/replay to subsequent domain methods; expose accurate runtime/version diagnostics and finish lifecycle ownership on reload/world changes.
2. Finish stone variants, paired autonomous hidden-layout runs, and controlled travel/work/perception metrics. The source audit, adapter-level paired-world probe, and survey correction now have live evidence.
3. Extend production to smelting/fuels, exposed iron harvesting, passive furnace waits, and longer resource commitments.
4. Implement exploration continuations, maintained lighting, resupply children, bounded relaxation, and survival preemption through the same motor owner.
5. Pass the original iron-pickaxe scenario and frozen development/held-out evaluations; extend injected-failure diagnosis and visual Recorder Play review to those behaviors.
6. Migrate remaining entry points and delete the old execution ownership and temporary selector.
7. Add and evaluate the capabilities needed for autonomous Minecraft completion across multiple seeds.
