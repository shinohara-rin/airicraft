# System 1 Baritone boundary

The replacement runtime uses Baritone for navigation to a selected stance. Acquisition chooses targets in the Minecraft domain. The motor owns individual breaking and future placing/interacting commands.

## Audited dependency

- Bundled `libs/baritone-unoptimized-fabric-1.15.0.jar`, SHA-256 `d82f590654a901d28a705c0a0f13d916350620301bd86dd33de8c0bcc3a8d5d1`.
- Source reference: [Baritone v1.15.0](https://github.com/cabaletta/baritone/tree/612a8a6dc31edd13fa35123aa422e0cdca5c3389), checked against the bundled remapped classes with `javap`.
- Production-style client validation is required. A successful Java compile does not establish that a Mixin selected the intended overload after remapping.

## Access and control decisions

| Path | Boundary |
| --- | --- |
| `CalculationContext.get`, `BlockStateInterface.get0`, block/fluid shape wrapper | Each block-state interface captures an immutable authorized terrain view. `get0(int,int,int)` returns that view before live chunks or cached terrain can be queried. Unknown cells are barriers and cannot serve as footing. |
| `isLoaded`, `worldContainsLoadedChunk` | Answer from authorized columns, independent of actual chunk/cache availability. |
| `MovementHelper.fullyPassable(IPlayerContext, BlockPos)` | Redirect the direct world lookup to observed terrain. This overload otherwise bypasses `BlockStateInterface`. |
| `MovementFall` ladder scan and destination lookup; `PathExecutor` sprint geometry | Redirect direct block-state reads to current observed terrain. Calculation snapshots remain immutable; execution revalidates against the latest observations. |
| `VecUtils.calculateBlockCenter`, `RotationUtils.reachable*` | Navigation uses cell centers and cannot probe interaction faces. Exact motor interactions have their own visibility/reach validation. |
| `Movement.prepared` | Test observed obstacles and report `UNREACHABLE`. Do not run the original falling-entity scan or implicit mining preparation. |
| `MovementHelper.attemptToPlaceABlock` | Return `NO_OPTION` and mark the movement unreachable before ray casts or inventory selection. |
| `InputOverrideHandler` | Deny Baritone's forced left/right clicks. Movement and look remain available. This is necessary because disabling path-calculation break/place settings alone does not disable executor fallback clicks. |
| `MovementFall` bucket handling | Treat bucket slots as unavailable to this executor. Falling into water cannot select a bucket or collect/place water. |
| `Favoring` / `Avoidance` | Disable Baritone avoidance while navigating; it otherwise reads cached spawners and loaded entities. A later observed-hazard policy must supply its own decisions. |
| Inventory and movement settings | Save/restore scoped settings. Disable automatic inventory/tool selection, terrain changes, parkour, sprint, bucket falls, and unloaded-goal simplification. Enforce elapsed-tick and cumulative travel budgets in the motor. |

Allowed engine inputs include player pose/contact, inventory, dimension height and world border. In particular, Baritone's own-feet slab adjustment and own-feet fluid contact checks remain physical contact inputs. They are not remote terrain acquisition. Minecraft physics still uses the real world; this boundary governs information supplied to decisions and motor route selection.

The hooks are enabled only by the temporary System 1 selector. They do not grant other Baritone processes resource-acquisition authority. New motor capabilities must preserve this separation instead of enabling automatic interactions globally.

## Live contract probe

`system-one-terrain` prepares the isolated stone platform, runs an adapter probe, restores the platform, clears the synthetic terrain view, then submits the ordinary autonomous cobblestone goal. Probe inputs and fixture ground truth are evaluator-owned and are never passed to that goal.

The probe holds a small authorized corridor fixed while changing 102 unauthorized cells between air and stone in the real integrated-server world. It waits for client observations of all fixture updates before each measurement. It checks both block-state interface constructors, unknown footing and passability, blocked movement failure, click authority, implicit placement, and bucket selection. It compares all 132 movement results across six origins and the actual A* path/cost. Positive controls require known air to remain passable, forward input to work, and the known route to reach its goal.

Artifacts:

- `system-one-terrain-probe.json`: both cost vectors, paths, total costs, and equality result.
- `system-one-decisions.jsonl.gz`: subsequent autonomous goal's actual decision inputs and outputs.
- `system-one-final.json`: goal outcome and released motor state.

This is an adapter test using synthetic authorized observations. It complements the geometric sensor's paired-layout unit tests; it is not a paired autonomous playthrough, a modpack-wide audit, or proof that geometric sensing matches human vision. Additional excavation variants, lighting cases, and world-session cleanup remain separate gates in the implementation plan.

## Recorded block-state reconstruction (v83)

`MinecraftScene` encodes the identified surface state as registry identity plus an immutable property map. `MinecraftSensor` publishes its recorded `Seen` memory through `ObservedTerrain.publishObserved`; it no longer keeps a parallel map of raw engine states. The decoder requires the exact active registry property set and valid serialized values, otherwise returning a barrier. Captured navigation views retain immutable decoded states. Production format 5 / stone format 4 preserve property changes and timestamp-only refreshes.

The terrain probe now round-trips every registered state of slabs, stairs, logs, wall torches, water and air through JSON, checks invalid/missing properties, and checks that Baritone receives the reconstructed top slab while an earlier captured view retains the bottom slab. Its paired route/cost probe publishes its synthetic observations through the same reconstruction boundary. These are adapter tests, separate from the autonomous goal and from a complete hidden-read audit.

This remains engine-assisted geometric sensing. Recording properties makes existing navigation inputs explicit; it does not prove that every registry property is visually distinguishable from the exposed face. The full perception-violation count remains unavailable.

[Live proof](evaluations/system-one-v83-recorded-states.json): the serial terrain run `20260908-155032-853371-59624` passes all 110 registry-state round trips, malformed-state blocking, immutable captured-view and real Baritone lookup checks, plus unchanged paired-world movement/path results. The subsequent autonomous goal passes and all 357 recorded decisions replay exactly. Capture and client shutdown complete normally. This does not establish sustained integrated performance or close the broader perception audit. The initial attempt stopped responding during world startup and is retained as a capture failure.
