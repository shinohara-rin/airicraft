# System 1 implementation evidence

Tracks the [implementation plan](superpowers/plans/2026-09-06-system-one-redesign.md). The full redesign remains in progress. A passing stone fixture does not establish completion of the iron-pickaxe or game-completion gates.

## Implemented so far

- `TaskKernel`: immutable inputs/results and bounded transitions; child tasks retain parent continuations; commands have session/run/task/attempt identity; replacement work waits for acknowledged release. Synthetic-domain tests cover repair/resumption, stale feedback, cancellation, alternate-method recovery, budgets, and replay.
- Opt-in replacement entrance: `scripts/run-evaluation-scenarios --system-one` implies no-LLM mode. Its tick path bypasses the legacy execution/reflex loop. The initial Minecraft method supports local cobblestone acquisition with a supplied pickaxe.
- Geometric perception: bounded rays stop at the first occupied voxel, do not cross occluding corners, hide unlit identities, and restrict dark geometry to the near field. This is a geometric sensor, not screenshot recognition or proof of human-equivalent visibility.
- Baritone terrain hook: block-state interfaces capture immutable observed terrain; hidden live/cached cells are replaced by a non-traversable, non-supporting sentinel. Movement defaults disable terrain mutation, parkour, automatic inventory changes, and Baritone's independent avoidance discovery during owned navigation. Further adapter-level fairness audit and paired-world live checks remain required.
- Exact breaking aims at and ray-checks the target face, verifies reach/tool eligibility, and avoids breaking the player's own footing. Soil excavation prioritizes lower exposed surfaces and preserves tools when an empty hand is equally effective.
- Excavation surveys on demand rather than performing four quarter-turns after every move. This correction follows the user's live observation of repeated spins.

The replacement currently has no production recipe/smelting methods, lighting repairs, or survival controller. Do not use it as a general autonomous survival agent yet.

## Tests and live evidence

Focused command:

```bash
source .envrc
./gradlew :test --tests 'ai.moeru.airicraft.systemone.*'
```

At 2026-09-07, 24 tests pass: 10 kernel, 6 geometric sensing, and 8 acquisition tests. The existing evaluation-launcher Python suite also passes (23 tests).

Live fixture command:

```bash
source .envrc
scripts/run-evaluation-scenarios --system-one --scenario system-one-stone \
  --recorder-jar /absolute/path/to/recorder-profile.jar \
  --stop-client-after-scenario --bridge-timeout-seconds 240
```

The evaluator prepares a disposable platform: stone underneath three soil layers, with one wooden pickaxe supplied. Fixture construction executes on the integrated-server thread before goal submission. Setup state and coordinates are not supplied to the agent. This fixture deliberately isolates acquisition; it does not replace the original iron-pickaxe scenario.

| Run directory under `eval-output/` | Evidence |
| --- | --- |
| `20260906-195642-628152-51720` | First look/break succeeded; first navigation crashed because one Baritone constructor overload had not captured its terrain view. Corrected the constructor selector. |
| `20260906-195834-754333-53801` | Interrupted rerun exited before a terminal scenario report. No gameplay success claim. |
| `20260907-154110-841283-61691` | Navigation worked, but excavation widened the surface and wore out the pickaxe. The fixture incorrectly gated evaluator ticks on remaining at the starting height, causing recording to stop after descent. Recovered 485 remaining System 1 events from the live event buffer; earlier events were truncated. Gracefully stopped the client. |
| `20260907-154739-926189-63272` | Obtained three cobblestone in 446 elapsed ticks; scenario PASSED and harness OK. Recorder Play finalized. This run still performed routine panorama scans; the follow-up run tests their removal. |
| `20260907-155134-120434-64304` | Obtained three cobblestone in 376 elapsed ticks with zero survey-look commands. Scenario PASSED; harness OK; Recorder Play finalized. `system-one-final.json` confirms SUCCEEDED and no remaining motor command, pathing process, or pending release. Planner journal is empty and final LLM records are empty. |

The failed recording is a concrete recorder/harness finding: an artifact existing on disk does not prove that recording continued through gameplay. Setup completion is now latched once, and post-outcome recording includes motor release before cleanup finishes. Recorder Play playback has not yet been visually reviewed.

## Outstanding plan gates

1. Complete structured decision-input recording and offline replay of live runs; expose accurate runtime/version diagnostics and finish lifecycle ownership on reload/world changes.
2. Finish the stone variants, adapter-level hidden-terrain checks, and controlled travel/work/perception metrics. The survey correction has passed the controlled live fixture.
3. Implement resource production, alternative recipes/fuels, resource commitments, and passive smelting waits.
4. Implement exploration continuations, maintained lighting, resupply children, bounded relaxation, and survival preemption through the same motor owner.
5. Pass the original iron-pickaxe scenario and frozen development/held-out evaluations; battle-test diagnosis and visually review Recorder Play evidence.
6. Migrate remaining entry points and delete the old execution ownership and temporary selector.
7. Add and evaluate the capabilities needed for autonomous Minecraft completion across multiple seeds.
