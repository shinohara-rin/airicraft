# Codex Driver Survival Playthrough

Date: 2026-09-01  
Mode: external Codex driver (`scripts/codex-driver`)  
Objective: progress from a fresh survival world to the Ender Dragon, using only the exposed Airicraft agent tools for gameplay.

## Rules of this log

- Record non-fatal bugs with the evidence, workaround, and gameplay impact.
- Record missing but non-mission-critical harness capabilities separately.
- Do not treat a queued tool result as completed work; wait for its task state and events.
- A true blocker is a failure that prevents progress with every available exposed tool and has no safe in-game workaround.

## Playthrough log

### Launch

- Client launch started with an isolated bridge-state file at `/private/tmp/airicraft-codex-driver-01a05cde.json`.
- Initial sandboxed launch could not open Gradle's user-cache lock file. Retried the same local launcher outside the sandbox; this is an environment permission boundary, not an Airicraft gameplay defect.

### Tick-debug proof

- Paused at client tick `914` using `agent debug ticks pause`; the returned snapshot and rendered `854x480` frame showed the survival player stationary on a desert coast.
- One `step` using the returned `debugSessionId` and `pauseEpoch` advanced the client tick to `915`, rotated the pause epoch to `2`, and returned a fresh frame. `ticks state` remained `PAUSED` with that exact identity.
- The snapshot's `worldTime` moved from `318` to `640` while `clientTickId` moved exactly once. This discrepancy is recorded below for investigation; the client-tick contract itself held.

## Non-fatal bugs / workarounds

- **Tick-debug snapshot world-time mismatch.** Evidence: pause snapshot at `clientTickId=914` reported `worldTime=318`; the one-step snapshot at `clientTickId=915` reported `worldTime=640`. Workaround: use `clientTickId`, `pauseEpoch`, and snapshot IDs as the timing authority, not `worldTime`, until clarified. Impact: no current gameplay block, but it weakens time-sensitive debugging evidence.
- **Navigation can fail at the destination perimeter.** Evidence: `navigate_to(284,67,-141)` started at `(65,67,-86)`, moved the player to `(281,65,-144)`, then ended `CALCULATION_FAILED` / `Path calculation failed` with an estimated 25 ticks remaining. The target was visibly in a small azalea/oak forest. Workaround: once within local-resource range, switch to the typed resource collector rather than retrying the same exact navigation goal. Impact: non-fatal for local collection, but unreliable for objectives requiring a precise target block.
- **Tick-debug pause does not freeze hostile damage.** Evidence: after `pause` returned `paused: true`, `clientTickId=3963`, and `pauseEpoch=1`, the event stream recorded an arrow hit, fall damage, four zombie hits, death, and respawn, all at tick `3963`. The player lost the four collected logs. Workaround: do not use tick pause as a combat or real-time safety mechanism; it can still preserve a client-side snapshot for diagnosis. Impact: severe for interactive safety, but the fresh respawn leaves the survival attempt recoverable.
- **Blocked collection can keep the player moving into danger.** Evidence: wood collection declared `WAITING_FOR_PICKUP` / `target_missing` after collecting 4 of 16 logs, but its active mining task continued, moving from Y=67 to Y=44 before a hostile reflex. Workaround: issue `discover_tools` for `cancel_task` and cancel a resource job as soon as it reports target exhaustion. Impact: recoverable, but dangerous and costly in early survival.

## Harness gaps (non-mission-critical)

- **Task inspection is impractically verbose for the driver loop.** `agent tasks --verbose` serializes the full known craft/smelt catalogue (hundreds of entries) alongside live task state, obscuring the state transitions needed for play. A compact task-status command or an explicit detail level would make live control substantially safer.

## Genuine blockers

_None yet._
