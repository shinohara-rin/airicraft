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

At 2026-09-07, 56 tests pass: 11 kernel, 6 geometric sensing, 13 acquisition and 1 observed-reach, 9 production, 6 decision replay, 3 recording tests, and 7 smelting-production tests. The 32 evaluator tests and evaluation-launcher Python suite also pass (24 launcher tests). Five recording-inspector tests cover old-policy diagnosis and incomplete input detection.

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
| `20260907-174947-087516-94884` | Tool progression correction allowed stone-pickaxe crafting and raw-iron harvesting. Furnace supply then exhausted excavation alternatives despite nearby previously observed stone. Production policy v6 records the failure and bounded cleanup; the next correction adds approaching observed stone before another excavation. |
| `20260907-175633-378080-96539` | Collected furnace stone but could not return: later excavation could remove footholds belonging to earlier tasks. Stopped after repeated return attempts. Added session-scoped foothold memory, recorded and replayed with observations. |
| `20260907-180207-162291-97887` | Routes and resource production progressed with no motor failures. All three exposed iron ores were mined, but pickup chose the player's lower height instead of the drop's floor. Search eventually exhausted its budget. Production policy v8 preserves this failure; drop-height recovery is next. |
| `20260907-181125-003484-138/01-system-one-iron` | Pickup correction collected raw iron. Furnace supply then took a two-block descent from Y196 to Y194, leaving no climbable return route. Failed in 1753 elapsed ticks, harness OK, zero planner calls, motor released. All 1677 turns replayed with production policy v9. |
| `20260907-181125-003484-138/02-system-one-smelting` | Focused fixture loaded raw iron and plank fuel into an observed furnace, waited without owning the motor, and collected an ingot. PASSED in 282 ticks, harness OK, zero planner calls; all 212 turns replayed. Cleanup still overwrote success while a child released the motor, leaving runtime CANCELLED. This fixture isolates furnace interaction and does not prove the iron-pickaxe chain. |
| `20260907-182610-738023-4574/02-system-one-smelting` | Root goal observation now ends the whole branch after motor release. PASSED in 293 elapsed ticks, runtime SUCCEEDED, zero planner calls, motor fully released. All 219 turns replayed with production policy v10. |
| `20260907-182610-738023-4574/01-system-one-iron` | One-block descent and intermediate mining stances allowed climbing from Y194 back to Y198, crafting a furnace, and smelting one ingot. Furnace placement at (-1,199,18) then occupied a used stair cell; subsequent surface navigation failed and raw-iron search exhausted. FAILED in 2621 elapsed ticks, zero planner calls; all 2545 turns replayed. Placement must preserve passage clearance as well as excavation preserving supports. |
| `20260907-183337-793482-6614/01-iron-pickaxe` | Original frozen scenario, no fixture supplies: harvested spruce, crafted a wooden pickaxe, excavated stone, and crafted a stone pickaxe. Exposed-iron search then exhausted; underground resource exploration remains missing. FAILED in 1827 elapsed ticks with zero planner calls. All 1805 decision turns replayed with production policy v11. |
| `20260907-183337-793482-6614/02-system-one-iron` | New placement avoided the used route, but wooden-pickaxe crafting failed at tick 429 with `crafting_cursor_occupied`. The adapter issued three predicted clicks per ingredient and treated an occupied cursor on the next tick as terminal. Stopped the ensuing alternative-wood searches through the isolated client-stop endpoint. No terminal fixture pass or complete replay claim. |
| `20260907-184153-125742-8728/01-system-one-iron` | Empty-inventory full production fixture PASSED in 2164 elapsed ticks, zero planner calls, harness OK, runtime SUCCEEDED, motor fully released. Crafted tools, excavated stone, harvested exposed ore, placed a furnace outside the used passage, smelted three ingots, and crafted the iron pickaxe. All 2086 decision turns replayed. Motor v7 waits for the cursor and recipe slots to settle between ingredient transfers. This is fixture proof, not a pass of the original terrain scenario. |
| `20260907-184153-125742-8728/02-system-one-production` | Concurrent stone-pickaxe regression PASSED in 819 elapsed ticks, zero planner calls, runtime SUCCEEDED. All 747 decision turns replayed. Both clients exercised crafting under concurrent live load without the earlier cursor failure. |

The failed recording is a concrete recorder/harness finding: an artifact existing on disk does not prove that recording continued through gameplay. Setup completion is now latched once, and post-outcome recording includes motor release before cleanup finishes. The final production Recorder Play was rendered successfully to 676 frames and `recording-render/fpv.mp4`. Sampled first-person frames were visually inspected through log harvesting, excavation, cobblestone collection, and return toward the table. The renderer covers its available server ticks 28–703, versus requested 28–724, and reports 314 ignored unsupported packets (310 chunk unloads, 4 player positions). Its images are supporting evidence; they do not independently prove final inventory or exact runtime-tick alignment.

The successful exposed-iron fixture's Recorder Play also rendered 2304 frames at 960×540 and 20 fps. Inspected frames 400, 900, 1350, 1600, 2200, and 2300 show stone/ore mining and furnace/table interactions. The final inventory is not shown in those frames; the evaluator and replayed inventory observation establish the iron-pickaxe result. Render coverage is global ticks 18–2321 versus requested 18–2341, with 238 skipped unsupported packets (234 chunk unloads, four player positions). These are explicit external playback limits, not proof of complete tick alignment.

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

The compact report includes inventory changes, command counts, failure reasons, the first failed prerequisite chain, and the last failed command's parent chain. Input coverage is checked separately from decision replay; truncated compression, missing/reordered turns, missing footers, and trailing records report incomplete coverage. The inspector never claims policy replay.

## Outstanding plan gates

Underground search and a separate lighting policy are implemented experimentally in production policy v12. Search uses observed cave floors and individual exposed excavation surfaces; geological priors supply only a preferred depth and work bounds. Lighting has hysteresis, child-task supply/placement, return continuations, and a time/region allowance after repair failure. The perception lens is unchanged. Seventy-two System 1 tests and 33 evaluator tests pass, including repair ownership and preserved destinations. Evaluator event checks now support exact payload predicates, so the cave fixtures require supply/placement resumption evidence as well as raw-iron inventory.

The first live cave batch, `20260907-190840-236654-14858`, failed search in both fixtures. `system-one-lighting` entered the cave, requested supply at tick 152, crafted eight torches, placed one, observed restored working light, and resumed exploration at tick 172. The supplied-torch fixture also placed light and resumed. Both reached the cave's end before lateral exploration changed their heading and led excavation into side walls. Final results were FAILED at 960 and 945 elapsed ticks, with zero planner turns. These runs prove the local supply/placement cycle, not successful underground acquisition or the full away-from-cave resupply gate.

The v12 lighting and underground tapes replay all 886 and 865 turns. Policy v13 keeps the search heading across lateral movement; motor v8 requires grounded arrival in the requested destination block. Batch `20260907-191513-735706-16562` still failed at 974/964 elapsed ticks: preferring any unvisited floor caused backtracking from the cave's end to its entrance before excavation. Recorded positions isolate the remaining frontier-ranking defect; the supply/placement/resume cycle continued to work.

Policy v14 prevents backward floor selection from replacing forward search. Batch `20260907-192007-336591-17947` PASSED both cave fixtures: lighting supply in 303 elapsed ticks and supplied-torch exploration in 279, zero planner calls, runtime SUCCEEDED, and all motor control released. The lighting fixture's payload checks confirm supply suspension, acquisition of eight torches, and resumption after observed working light. Ore first becomes identified at ticks 288 and 256, after exploration opens the tunnel end. These are local-crafting and underground-discovery proofs; running out partway through a longer cave, away-from-cave supply, charcoal bootstrap, and hazard/changed-route cases remain separate gates.

1. Extend the verified live decision recording/replay to subsequent domain methods; expose accurate runtime/version diagnostics and finish lifecycle ownership on reload/world changes.
2. Finish stone variants, paired autonomous hidden-layout runs, and controlled travel/work/perception metrics. The source audit, adapter-level paired-world probe, and survey correction now have live evidence.
3. Exposed-iron production and passive smelting now have a complete live fixture pass. Extend quantity/fuel accounting beyond one-input batches, test charcoal alternatives, and add underground iron acquisition for the original scenario.
4. Implement exploration continuations, maintained lighting, resupply children, bounded relaxation, and survival preemption through the same motor owner.
5. Pass the original iron-pickaxe scenario and frozen development/held-out evaluations; extend injected-failure diagnosis and visual Recorder Play review to those behaviors.
6. Migrate remaining entry points and delete the old execution ownership and temporary selector.
7. Add and evaluate the capabilities needed for autonomous Minecraft completion across multiple seeds.

The new long-cave `system-one-lighting-exhaustion` fixture starts with one torch, two coal, and two sticks, with a 24-block passage. Batch `20260907-193145-189244-21203` consumed the carried torch before supply (placement at tick 157, supply at 200), crafted eight, and repeatedly restored light while preserving exploration task 2. It discovered and mined ore but failed pickup: the ore cavity had a solid ceiling, and pickup required standing in the exact drop column instead of an adjacent accessible cell. The fallback issued four explicit 90-degree survey commands, providing a concrete cause for spins in this run. Final FAILED at 1214 elapsed ticks; all 1134 turns replay with v14.

The concurrent original `iron-pickaxe` run descended from Y64 to Y61, observed and harvested coal, and crafted eight torches autonomously. Placement failed because the narrow stair's available floors were protected footholds. After 80 ticks of bounded reduced-light progress it returned to the search origin; fallback harvesting exhausted. FAILED at 1857 elapsed ticks, zero planner calls; all 1836 turns replay with v14. This establishes real-terrain supply and bounded retreat, not an original-scenario pass. Next corrections are adjacent drop pickup and lighting placement in narrow passages. The surface-floor ranking concern did not cause this failure and remains unmodified.
