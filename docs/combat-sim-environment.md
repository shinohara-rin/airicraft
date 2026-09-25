# Combat Sim Environment (`sim/`)

Server-side training environment for optimizing a Minecraft combat policy with
machine learning. Phase 1 implements the trustworthy-simulation layer: a fake
player driven by strictly-legal inputs, an explicit tick protocol, arenas with
reset, privileged-state observations, and episode recording — all inside the
dedicated server (no client).

The client mod (`airicraft`) remains the eventual porting/smoke-test target;
the sim mod (`airicraft-sim`) is server-only (`environment: "server"`).

## Running the dev server

```
./gradlew :sim:runServer
```

The dev server uses `sim/run/sim-server` as its game directory. Recommended
`server.properties` values (already present in a dev-generated run dir):

- `online-mode=false`, `level-type=minecraft\:flat`, `spawn-monsters=false`
- `max-tick-time=-1` — sprint-mode burst ticking must not trip the watchdog
- `pause-when-empty-seconds=-1` — fake players are not counted for auto-pause,
  so without this the server freezes itself after ~60s
- `view-distance=6`, `simulation-distance=4` — keeps sprint cost low

A control HTTP API listens on `airicraft.sim.port` (default `8777`).

## Control API (`SimHttpControl`)

All mutating endpoints run on the server thread and block the HTTP caller until
done (max 30s).

| Method/Path | Purpose |
|---|---|
| `GET /v1/status` | serverTick, gate mode, arenas, episodes |
| `GET /v1/observe?arena=&radius=` | live privileged-state snapshot |
| `POST /v1/arena` | create/reset arena `{name, world?, center:[x,y,z], size?, yaw?}` |
| `POST /v1/player` | spawn fake player `{arena, name?, pos?, yaw?}` |
| `POST /v1/spawn` | spawn mobs `{arena, type, pos? | count?, minDist?, targetPlayer?}` |
| `POST /v1/equip` | equip player `{arena, items:[{slot,id,count?}]}` |
| `POST /v1/episode` | start episode `{arena, policy, params?, maxTicks?, obsRadius?}` |
| `GET /v1/episode?id=` / `POST /v1/episode/stop?id=` | query/stop; non-running replies include `score` |
| `POST /v1/reset` | reset arena `{arena}` — rebuilds the platform, wiping terrain features |
| `POST /v1/terrain` | place blocks `{arena, block, pos:[[x,y,z],...]}` — region-bounded, y ∈ [floor, floor+3] |
| `POST /v1/tick` | `{mode: freeze|run|sprint, ticks?}` — `sprint` is synchronous: the HTTP call returns after the batch has ticked |

## Architecture

### Fake player (`fake/`)

`FakePlayerEntity extends ServerPlayerEntity` joined through a no-op
`FakeClientConnection`. Three vanilla mechanics had to be re-enabled for a
packet-less player:

- **`canMoveVoluntarily()` / `canActVoluntarily()`** — `PlayerEntity` returns
  `false` on the server (movement is client-authoritative). Overriding to
  `true` restores the full input→`travel()` physics path.
- **`playerTick()`** — vanilla drives entity physics from
  `ServerPlayNetworkHandler.tick()` via the connection loop, which never fires
  for a fake connection. `FakePlayerEntity.tick()` calls `playerTick()`
  directly each tick.
- **`lastAttackedTicks`** — the real client increments it inside the
  move-packet path; incremented manually so attack cooldowns advance.

### Legality executor (`input/`)

`SimInputExecutor` translates a policy `Intent` (look target, move dir,
jump/sprint/sneak, attack press, use-item) into the same state a real client
produces — never calling `attack()` directly on a desired target:

- `ActionProfile` pins human-limit invariants: `maxTurnDegPerTick` (45°),
  `attackIntervalTicks` (2t), `reachBlocks` (3.0), `aimToleranceBlocks`,
  `obsDelayTicks` (3t observation delay fed to the policy).
- Look: intent resolves to a world point; yaw/pitch move toward it clamped per
  tick — no instant 360° snaps.
- Attack: rate-limited press-attack. A press is only *executed* if the current
  crosshair ray (from the clamped head yaw) actually intersects a living
  entity's bounding box within reach (`canInteractWithEntity` + expanded-box
  raycast). Rejections are logged as events.
- Movement: writes `forwardSpeed/sidewaysSpeed/upwardSpeed/jumping`, exactly
  what client input processing writes (×0.98 scaling included).

### Tick protocol (`tick/`, `mixin/`)

`SimTickGate` has three modes injected at `MinecraftServer.tick` head:

- `RUN` — normal ticking (default)
- `FREEZE` — every server tick is cancelled; the world is fully paused
- `SPRINT` — the gate stays open while the server thread itself bursts N
  `server.tick()` calls back-to-back (synchronous HTTP response after)

`SimRuntime.beforeTick/afterTick` bracket each accepted tick: `preTick`
(snapshots obs → policy decides on `obsDelayTicks`-stale obs → queues intent)
and `postTick` (damage accounting, executor events, termination checks).

Sprint batches run on the **server thread** via `server.execute` — a dedicated
sprint thread calling `server.tick` races the main loop's chunk/task drain and
crashes the world state. Throughput: ~1100 ticks/s (~55× realtime).

### World rules for training

`applyWorldRules` pins `DO_DAYLIGHT_CYCLE`/`DO_WEATHER_CYCLE` off and sets the
clock to 18000 (midnight): zombies/skeletons must not burn mid-episode.

Arena creation **force-loads** its chunks (`setChunkForced`) — entities spawned
into a not-entity-loaded chunk go to pending storage and are invisible to
`world.getEntitiesByClass` until they materialize later. Required episode
recipe: create arena → warm-up ticks → spawn → settle ticks → start episode.

`reset()` also clears player inventory (re-equip afterwards) and revives a
dead player (`reviveForSim`). A player dead ~20+ ticks is `remove()`d by
vanilla `updatePostDeath`; a removed entity can never be teleported or ticked,
so `resetPlayer` swaps in a fresh `FakePlayerEntity` carrying over the
executor.

### Arena + spawn rules (`arena/`, `spawn/`)

`Arena` is a named world region (center + half-size) with a flat platform,
player spawn point, and a tracked-mob set. `reset()` removes all living
entities/projectiles/items in the region (except fake players) and restores
the player to a clean baseline.

`SpawnRules` enforces the user's constraint: external/manual spawning is
allowed but **never on the player's face** — `DEFAULT_MIN_DISTANCE` 5m
(hard floor 2m via `ABSOLUTE_MIN_DISTANCE`). Both explicit positions and random
`pickSpawnPos` go through the same distance check plus `isSpaceEmpty`.

### Episodes (`episode/`, `observe/`)

An episode records JSONL: `{"type":"tick",t,obs,intent}` per tick,
`{"type":"event",t,name}` for executor/kill/death events, and a final
`{"type":"end",score:{outcome,ticks,kills,damageTaken,damageDealt,remainingMobs}}`.
Logs land in `<run>/sim/episodes/`. Outcomes: `PLAYER_DIED`,
`ALL_MOBS_CLEARED`, `TIMEOUT`, `STOPPED`, `ERROR`.

`ObservationSnapshot` is privileged state (no vision): player pos/vel/health/
food/yaw/pitch/onGround/cooldown/lastAttackedTicks/speeds/using/sprinting/
hands, plus all entities within radius sorted by distance (id, type, pos, vel,
dist, tracked, hostile, health, targetingPlayer).

### Policies (`policy/`)

`CombatPolicy { id(); reset(); configure(params); Intent decide(JsonObject obs) }`
consumes the delayed observation JSON — the same contract a learned policy
will use. `configure` applies optimizer-supplied tunables (`params` in
`POST /v1/episode`) before the episode starts.
`baseline-melee` is a hand-tuned melee script (approach/orbit/flee-centroid +
cooldown-gated attack) exposing 8 tunables: `engageDistance`, `engageSlack`,
`sprintBeyond`, `crowdRadius`, `crowdThreshold`, `attackRange`,
`minLastAttackTicks`, `strafeFlipTicks`. `idle` does nothing. `net` is a
pure-Java MLP whose weights arrive per episode in `params.net` (round 7).

## Optimizer (`sim/optimizer/`)

`optimize_cmaes.py` runs the full loop over the HTTP API: N parallel arenas →
an evolutionary algorithm samples policy tunables → each candidate is
evaluated on a freshly-sampled **shared scenario set** (common random
numbers) → tell. Sprint makes one evaluation batch (~650 ticks × 8 arenas)
take ~0.5s.

### Multi-objective mode (default): `--algo nsga2`

No scalarization. Each candidate's objective is the raw 5-dim metric vector
`[kills, −damageTaken, clear, survived, −ticks]` (all maximized), averaged
over `--nscen` random scenarios drawn fresh per generation and shared across
the population. Selection is NSGA-II (non-dominated sort + crowding
distance); per-generation progress is tracked as the front's hypervolume in
a fixed reference box. The final report re-evaluates the surviving front on
a large fresh scenario set and dumps every frontier member's params +
metrics so a trade-off can be chosen *after* optimization.

```
python3 sim/optimizer/optimize_cmaes.py --algo nsga2 --gens 15 --pop 8 --nscen 12
```

Randomization axes per scenario: mob mix (weighted zombie/skeleton/creeper/
spider), count 3–7, formation (ring/arc/cluster/pincer), radius 5.5–9 with
±0.8 baked jitter re-clamped to ≥5.5m and ≥1.6m between spawn points, plus
terrain features via `POST /v1/terrain` (pillars, walls, mounds, water
pools, cobwebs; ≥2m from player, ≥1.6m from mob points).

### Scalar mode (legacy): `--algo cmaes`

`J = 100·kills + dealt − 3·taken + 60·clear − 500·death − 0.2·ticks` —
kept for reference/comparison only.

`kills`/`damageDealt` are **player-credited** (`SimRuntime.isPlayerCredit`
on the last `DamageSource` seen per mob): damage by the player, mob-vs-mob
friendly fire, and falls count — baiting mobs into each other or off ledges
is positioning strategy. Explosions, entity cramming, and suffocation do
not. `damageTaken` is raw player health loss.

Result (NSGA-II, 15 gens, pop 8, nscen 12): front hypervolume 92.5k → 109.7k
(+19%); the final frontier on 24 fresh scenarios spans survived∈[0.42,1.0],
clear∈[0.42,0.875], ticks∈[118,275], damageTaken∈[2.1,19.1] — exposing an
explicit never-die/careful vs fast/aggressive trade-off axis the scalar
fitness could not express. Outputs land in `results_mo*/`
(`history.jsonl`, `final_report.json`).

### Structural search (GP): `optimize_gp.py`

`ast` policy (`AstPolicy.java`) interprets a JSON rule list shipped via
episode `params`: ordered rules `{when: <cond-tree>, act: <action>}`, first
match wins. Conditions are and/or/not trees over scalar obs features
(distances, per-type counts within a radius, self/mob HP, cooldown) plus
nearest-type substring checks; actions resolve a target
(`nearest|lowestHp|ranged|melee|farthest|centroid`) and a movement mode
(`approach|flee|orbit|kite|hold`) plus an attack rule.

`optimize_gp.py` evolves these programs with subtree mutation (constants,
features, comparators, rule add/remove/reorder, NOT wrap) and rule-swap
crossover under the same shared-scenario-set NSGA-II selection:

```
python3 sim/optimizer/optimize_gp.py --gens 18 --pop 12 --nscen 12 --seed 8
```

Result so far: template-seeded GP expands the trade-off front (HV 71k→103k
during search) but no evolved program strictly dominates the hand baseline
on the fresh 24-scenario eval — gains are trade-off shifts (e.g. +kills
against −survived). A hand-probed "charge ranged attackers first" template
does sit non-dominated vs baseline on a paired 24-scenario set (more kills,
higher survival, at the cost of more damage taken and slower clears) —
evidence the structural space holds real improvements that parameter search
cannot reach.

### MAP-Elites (diversity-preserving): `optimize_mapelites.py`

The archive is a 64-cell behavior grid — (mean kills) × (mean damage taken)
— each cell keeping up to 3 non-dominated elite programs. Emitters mutate /
crossover cell elites with novelty-biased cell selection, so niche
specialists survive instead of being dominated out. Extra action primitives:
`use:"off"` (shield block), `stopUse`, `jump` (crits), `zigzag` (serpentine
approach), `type:<mob>` / `targeting` targets; extra features
`targetingCount`, `mobHpSum`, `usingItem`. Players carry sword + shield.

```
python3 sim/optimizer/optimize_mapelites.py --gens 25 --pop 12 --nscen 12 --seed 11
```

Result (25 gens): training coverage 39% of the grid; the evolved standout is
a **shield-kite** structure (`use:"off"` + kite zombies at ~1.9m + always
attack): on 24 fresh scenarios — **100% clears, 100% survival, damage taken
4.9** (baseline: 87.5%/87.5%/10.7) at the cost of ~25% slower clears. A
second elite reaches baseline-level kills (3.0) with 40% less damage taken
(6.4 vs 10.7) and better survival (91.7%) — again only slower. Both are
non-dominated vs the hand baseline: the frontier now contains points the
baseline cannot reach in survival and damage-avoidance.

#### Long-budget run + saturation study

`--patience N` stops the run after N consecutive generations without a
union-front hypervolume gain, and `--resume grid_final.json` warm-starts the
archive so long runs can be continued instead of restarted. A chained run
(me3 archive → 583 generations total, stopped by patience-100) gives a clear
saturation picture: hypervolume climbs in **discrete jumps separated by
40–125-generation plateaus** — 129k → 136k (g35) → 143k (g78) → 151k (g196)
→ 153k (g321) → 154k (g421) → 154.2k (g482) → flat for the final 100 gens.
Each jump is a structural discovery (a rule reordering or a new
condition/target combination unlocking a whole niche); plateaus are dead
flat, not slowly creeping. Gains shrink after ~gen 320 — marginal structural
novelty is exhausted at this grammar/primitive set.

Final frontier (24 fresh scenarios; baseline `[3.17, -10.6, .875, .875,
-154]` on the same set):

- ~18 fighting elites now dominate baseline on 4 of 5 dimensions, e.g.
  `[3.21, -4.16, 1.0, 1.0, -161]` — more kills, 2.5× less damage taken,
  perfect clear + survival, only ~7 ticks slower. Still no strict dominator
  (baseline keeps the speed crown at the balanced end).
- Tank extreme `[3.17, -1.47, 1.0, 1.0, -194]`: same kills, **7× less damage
  taken**, perfect clear/survival, 26% slower.
- Kills extreme `[3.42, -5.96, .92, .96, -227]`: highest kill count; learned
  to sprint out of creeper clumps (`mobCount(r3.5)>=3 → flee creeper`).
- Winning structure family: shield-up kiting — `use:"off"` + kite/approach
  `targeting` mobs, `hold` on zombies with ready-attacks. Degenerate
  stall-for-survival programs (survive 1.0, kills ~0, run the whole clock)
  do exist on the front — they hold the `survived=1.0` extremes — but stay
  ~3/24 of the front and never invade fighting cells.

Three environment bugs were found and fixed by this run: natural-spawn
accumulation on the permanent-night flat world (now `doMobSpawning`,
`doTraderSpawning`, `doInsomnia`, `doPatrolSpawning` all off), an
advancement-tracker heap leak (dead fake players were re-spawned through
`onPlayerConnect` but never released — now corpses stay in the world and are
revived in place on reset, ~7000 leaked players / 4 GB was the failure
mode), and episode-log disk exhaustion (logs are deleted after scoring).

#### Round 2: threat-state features → strict baseline domination

The me7 saturation verdict ("add primitives, not budget") was tested by
adding three threat-state observation features the grammar could not
express — `creeperFuse` (max creeper fuse fraction in radius, from
`CreeperEntity.getLerpedFuseTime`), `litCreeperCount` (fuse > 0.4 in
radius), `aimingCount` (mobs drawing a `RangedWeaponItem` in radius), and
`offhandPct` (offhand item durability fraction). The archive was
warm-started from the me7 grid (118 elites) and run to a second
patience-100 stop: 330 generations, HV 113k → 145k → 149.2k with the same
plateau-then-jump shape (last gain at gen ~231, then 100 flat).

On the *identical* 24-scenario final eval set (seed 555, so me7/me8 finals
are directly comparable) the frontier crossed the dominance line:

- **Three strict dominators of the baseline AST now exist** — e.g.
  `[2.917, -3.46, 1.0, 1.0, -147]` beats baseline
  `[2.92, -12.04, .958, .958, -151]` on all five axes (same kills, 3.5×
  less damage taken, perfect clear + survival, 3% faster). me7 had zero.
- Winning effective structure (dead trailing rules aside): `use:"off"`
  shield-hold with zigzag against ranged mobs while not blocking, else
  approach-and-attack nearest — a two-rule hybrid, not the 6-rule monsters.
- High-performance neighbors: `[3.21, -1.83, 1.0, 1.0, -177]` (kills up,
  6.6× less damage, slower) and `[3.08, -3.40, 1.0, 1.0, -161]`.
- New features appear on the final front but not in the dominators
  (`aimingCount` x2, `offhandPct` x1); their main contribution was
  enlarging the mutation space so the winning two-rule hybrid was
  reachable. `creeperFuse`/`litCreeperCount` live in archive cells, not on
  the front.
- Degenerate audit: 0 stall programs in the mid-run archive (gen ~125);
  the final front has 1/25 stall-shaped member (`survived=1.0`, 0.08
  clear, runs all 600 ticks) — same confined-to-extremes pattern as me7.
- Honest caveat: `hypervolume_final` is slightly *lower* than me7's
  (104.3k vs 106.6k) because the front shifted toward the
  low-damage/high-kills corner and lost some mid-tradeoff members — the
  frontier gained strict dominators but narrowed elsewhere.

#### Round 3: parameter polish on the dominators (`optimize_polish.py`)

Freezing each dominator's rule structure and running NSGA-II over only its
numeric constants (cond `v`/`r` thresholds + act `range`/`slack`/
`sprintBeyond`/`attackRange`/`readyTicks`/`flipTicks`; 35 gens, pop 16)
pushed the frontier strictly outward — on fresh 24-scenario evals every
final front member still dominates the baseline, and most dominate the
*unpolished* program they started from:

- 2-rule hybrid (dominator_11, 10 genes): 10/14 front members dominate the
  original. Champion `[3.33, -3.33, 1.0, 1.0, -139]` — +18% kills, 3.8×
  less damage, perfect clear/survival, **11% faster than baseline**.
  Damage extreme `[3.04, -1.39, 1.0, 1.0, -147]` takes 9× less damage.
- 5-rule variant (dominator_8, 26 genes): 11/11 dominate baseline, 5/11
  dominate the original; best `[2.96, -3.14, .96, .96, -134.5]` — 14%
  faster than baseline at equal clear.
- Net effect of rounds 2-3: the frontier now contains policies that beat
  the hand-written baseline on **all five objectives simultaneously** —
  kills, damage taken, clear rate, survival, and speed — which is the
  first point where the evolved rules are strictly better rather than a
  tradeoff.

#### Round 4: harder mobs + champion reseed (me9, `--hard`)

Stress test first: the polished champions still dominate the baseline on
harder scenario draws (4-9 mobs at radius 5.5-8 vs the training 3-7 at
5.5-9), but their damage taken (~5.5-6.9 avg HP) was not yet near-zero —
so the champions were fed back as MAP-Elites seeds on top of the me8
archive and the eval set itself was switched to `--hard`.

- 693 gens until the patience-100 stop: HV 142.6k -> 171.0k, plateaued at
  gen ~593; coverage 75% of the kills x damage grid.
- Final eval (fresh 24-scenario hard set; baseline
  `[3.46, -11.35, .79, .83, -194]`): 6 front members dominate the
  baseline. Champion `[4.0, -2.70, 1.0, 1.0, -176]` — +16% kills, 4.2x
  less damage, perfect clear/survival, 18 ticks faster. Lowest-damage
  combat points sit at ~2.3-2.4 avg HP taken: near-unscathed.
- Winning structure stays the 2-rule hybrid: `usingItem<0.5 -> hold +
  target ranged + shield (use:"off") + zigzag + sprint`, else
  `approach + attack ready on nearest`. Threat-state features
  (`litCreeperCount`, `aimingCount`, `offhandPct`) appear in some elites'
  conditions but not in the champion.
- Degenerate audit: 6/30 front members are stall elites
  (`survived=1.0`, kills ~0-1.3, ~500-600 ticks) — they live in the
  survived-extreme cells and do not displace combat elites.

#### Round 5: speed niches (`--cell2 ticks`, me10) + hard polish

With damage near-zero, the archive was re-binned as kills x speed
(`TICKS_EDGES`) instead of kills x damage: cells now compete on clear
time, which tilts selection toward fast-and-unscathed policies. As a side
benefit, stall programs all collapse into the slowest bin and stop
occupying distinct niches. me10 resumed the me9 grid + the 5 hard
champions as seeds under `--hard`, patience 100.

- 313 gens until the patience-100 stop: HV 139.9k -> 170.6k, plateaued at
  gen ~213. Final eval on a fresh hard set (baseline
  `[3.58, -11.91, .83, .83, -170]`): the frontier shifted mass toward
  fast clears — fastest dominator `[4.5, -9.5, .92, .92, -168]` — but
  speed vs damage is still a real tradeoff: ~150-180t entries take
  ~3.7-4.5 HP while the ~1.7-2.4 HP entries need 300+ ticks.
- Degenerate audit: only 1/31 front members is a stall elite (vs 6/30 in
  me9) — ticks binning suppressed stall niches as designed.
- Parameter polish (`optimize_polish.py --hard`) on the two best
  compromise structures then pushed the corner: `champ_pol26_0`
  `[3.88, -2.87, 1.0, 1.0, -169]` — perfect clear + survival at ~1.4
  hearts lost, faster than baseline; `champ_pol26_8`
  `[3.71, -2.71, .96, .96, -150]` trades 4% clear for 19 fewer ticks.
  `champ_pol3_1` `[4.08, -3.33, 1.0, 1.0, -176]` maximizes kills.
- Current frontier verdict: "fast and nearly unscathed" is achievable —
  the champions clear 4-9 mixed-mob ambushes in ~150-175 ticks taking
  ~1.5 hearts; the residual ~2.7 HP is the odd unavoidable hit, not a
  policy failure mode.

#### Round 6: new primitives + nether scenarios + search upgrades (me11)

Requested additions: a backstep primitive, per-mob count of hits the
*player* has landed (not mob HP), shield-swap timing features, more
scenario variety including Nether combat, and a look at improving the
search method itself.

New policy surface:

- `backstep` move mode: retreats directly away from the target when
  closer than `range + slack`, optionally sprinting — a disengage
  primitive distinct from `flee`/`orbit`/`kite`.
- `playerHits` per entity: `SimRuntime` counts every recorded damage
  source whose attacker is a `ServerPlayerEntity`, exposed on each
  observed entity. Drives focus-fire selection via `hitsNearest`/
  `hitsSum` features and the `mostHits` target picker (mob with most
  player hits, distance tiebreak) — lets rules finish off already-
  damaged mobs without exposing mob HP.
- `useTicks` on the player observation: consecutive ticks the active
  item has been in use, so rules can express shield-swap timing
  (e.g. raise/drop the shield after N ticks).
- `hostile` in observations now also covers `Monster`-implementing
  entities — hoglins and piglins are not `HostileEntity` subclasses,
  so nether mobs were invisible to type/hostile filters without it.

Scenario expansion (`optimize_cmaes.py` helpers, shared by all
optimizers): `NETHER_MOB_POOL` — zombified piglin, blaze (the ranged
pressure), wither skeleton, hoglin, piglin. `random_scenario` takes a
`pool` override and `random_terrain` a `nether` flag that adds
`lava_pool` terrain, swaps stone for netherrack, and allows lava walls.
`optimize_mapelites.py --netherfrac` sets the fraction of eval
scenarios drawn from the nether pool (me11: 0.3). Ghast excluded
(flying AoE, unreachable by melee); magma_cube excluded because split
offspring are untracked entities that would trigger
`ALL_MOBS_CLEARED` while babies are still alive.

Search upgrades (`optimize_mapelites.py`):

- `--screen N` (racing): evaluate each candidate on only the first N
  scenarios of the eval set, rank by a batch-normalized objective sum,
  and skip the full eval for the bottom 40% — ~27% eval savings spent
  on more generations instead.
- Blend crossover: for two parents with positionally aligned rules,
  lerps the numeric act constants (range/slack/sprint/attack params)
  rather than only swapping whole rules.
- Dead-rule pruning: truncates the rule list after the first
  unconditional rule (everything after it can never fire) — applied to
  every generated child.
- `--memetic N`: every N generations, one constant-jittered child of
  each top cell's elite (ranked by kills+clear+survived) is injected —
  local parameter polish inside the structural search.

me11: resumed the me10 grid (130 programs) + 5 polished champions as
seeds, `--hard --netherfrac 0.3 --cell2 ticks --screen 4 --memetic 40`,
patience 100.

Results (597 gens to the patience-100 stop, HV 113k -> 166.7k on the
training set — still stepwise, last structural jump ~gen 491; final eval
on 24 fresh hard+nether scenarios, baseline
`[3.21, -15.03, .67, .67, -183]`):

- The nether mobs hit noticeably harder than the overworld pool — the
  same baseline drops from ~87% clear on me10's set to 67% here and
  takes 15 damage instead of ~11, so cross-run HV/frontier numbers are
  not comparable; only within-report comparisons are paired.
- 4/20 front members dominate the baseline, e.g.
  `[4.125, -5.65, .96, .96, -173]` — kills +29%, 2.7x less damage, and
  faster. Archive champion `[5.29, -6.48, 1.0, 1.0, -232]` tops every
  pick criterion at once (max kills, max clear, max survival).
- Honest negative: none of the round's new primitives reached the
  front. The archive uses them (backstep x2, hitsNearest x6, useTicks
  x2 of 141 grid elites) but the final Pareto front is still all
  shield/hold/approach/zigzag structures — the gains came from the
  widened scenario diversity plus the search upgrades, not the new
  attack/disengage vocabulary.
- Degenerate audit: 0/20 stall elites on the front (the ticks-binning
  keeps working); the two slowest entries (~390-527 ticks) are legit
  slow-but-unscathed niches, not flee loops — they still kill mobs.

#### Round 6b: champion trajectory analysis → failure-mode taxonomy

Re-ran the 4 champions (clear/dom/fast/pol26) over 24 fresh
hard+nether scenarios keeping every per-tick JSONL
(`traj_collect.py`, logs under `traj_me11/`). First pass immediately
surfaced an **environment bug**, not just policy failures:

- **hoglin/piglin → zoglin/zombified_piglin conversion.** Nether mobs
  spawned in the overworld zombify after ~300 ticks, which swaps the
  tracked combatant for a fresh *untracked* entity. Consequences seen
  in the logs: kills stop being credited, `remainingMobs` can hit 0
  while a hostile zoglin horde still beats on the player (false
  clear), wanderers beyond the reset margin (+12) accumulate across
  episodes — one obs snapshot showed ~70 stray zoglins — and their
  hits still count in `damageTaken`. Fix: `SpawnService` now sets
  `setImmuneToZombification(true)` on `HoglinEntity` /
  `AbstractPiglinEntity` spawns. Verified: hoglin survives 350+ ticks
  unchanged; zero zoglins in any post-fix trajectory. Post-fix run
  totals: deaths 24 -> 17, pol26 avg damage 8.9 -> 6.0.

Remaining failure modes on clean data (96 episodes):

1. **Melee dogpile burst — the main death cause.** Deaths are swarm
   out-DPS, not a single threat: damage-window attribution always
   shows 3-5 mob types hitting (wither_skeleton + hoglin are the heavy
   hitters; wither DoT ignores the shield, hoglin knockback launches
   out of frontal block). `fast` dies early (t42-203) on 10/24 — it
   dives into the pack without shield discipline. Scenario s21
   (hoglin, 2x wither_skeleton, 2x piglin, blaze @ tight radius) kills
   all four champions. Shield-up at death in most cases — the
   perma-shield meta handles arrows, not multi-angle melee.
2. **Blaze vertical/wander out-of-reach — every remaining timeout.**
   `left=1` survivors are almost always a blaze either hovering ~2
   blocks above the sword band (4-5m 3D dist, dy≈2) or drifting past
   the 20m obs radius entirely (player holds empty hostile list,
   attacks nothing). Pure-melee cannot close it; the policy turtles
   under shield forever — a real mechanic limit, not an env bug.
   Options: accept the timeout cost, give ranged loadout slots, or cap
   spawn heights.
3. **Passive-piglin fixation — the subtler timeout.** The final
   survivor is sometimes a piglin that never aggro'd
   (`targetingPlayer:false`, 2-10hp, 2-5m away): the policy fixates on
   the unreachable blaze (`sprint:true` toward it, `attack:false`)
   and never cleans up the free kill standing next to it — a
   target-priority failure, expressible as a rule gap ("finish the
   passive mob in reach before chasing the distant one").
4. **Death-by-omission vs death-by-commitment.** `clear`/`dom`
   time-out safely (0-10 damage, shield up) but leave one mob;
   `fast` never times out because it dies first. The tradeoff axis is
   exactly the Pareto shape the front already showed.

#### Round 7: neural policy (`net`) vs the rule grammar

Question: can a continuous policy learn beyond the AST grammar's ceiling?
`NetPolicy.java` is a pure-Java MLP configured per episode via `params.net`
(`{"layout":"v1","layers":[{"shape":[in,out],"w","b"}]}`). Inputs = 15
global features (hp, cooldown, lastAttacked, offhand%, hostiles, nearest
dist, creeper fuse/aiming/targeting counts ...) + 4 nearest-hostile slots x
14 per-entity features (dist, dx/dz/dy, health, targeting, fuse, playerHits,
type flags) = 71; outputs = moveDir x/y, per-slot target logits (argmax ->
`lookEntity`), attack, shield up/down, sprint, jump = 10. Hidden (48,24)
tanh, 4882 params. Same observation and intent surface as the AST policies —
legality still enforced by the executor.

`optimize_net.py`: BC warm start cloning the me11 champions' trajectories
(`traj_me11/`, 22,356 ticks; move MSE + target-slot cross-entropy + 4 binary
heads), then NSGA-style ES over the weight vector (uniform-mask crossover or
mean blend + sparse sigma=0.08 jitter over ~35% of weights), same
multi-objective eval harness, champs re-run for pairing.

net1 (400 gens): training HV 544 -> 11,213 with the usual plateau-then-jump
shape — but the fresh 24-scenario front collapsed. Best member
`[1.58, -8.99, .08, .71, -452]`; **zero members dominate baseline
`[3.21, -14.78, .58, .71, -236]` or any me11 champion**; most of the
28-member front is near-degenerate survival (kills~=0, survived~1, ~600t).
The logged HV was inflated by a bug in `optimize_net.py` itself:
`select()` kept *every* candidate (pool grew +12/gen to ~150, gen time
13s->210s) and each member's score stayed frozen from the single eval draw
that first measured it — lucky draws accumulated as fake elites and parents
were effectively selected on stale noise.

Fix: fixed-mu selection (`order[:args.pop]`) + parents re-evaluated every
generation on the same fresh scenario draw as the kids (CRN pairing —
relative rankings stay fair, lucky scores can no longer persist). net2
re-runs from the same BC weights under the corrected selection.

#### Round 8: direct RL — per-tick external stepping + PPO

ES on the weight vector proved noise-dominated at this eval budget, so
policy improvement moved to real RL while keeping the exact deployment
contract (same 71→48→24→10 MLP, same `params.net` hand-off).

- `external` policy (`ExternalPolicy.java`): `decide()` returns a
  `volatile Intent` stashed via `/v1/step`; an intent persists across
  ticks until replaced (frame-skip semantics).
- `POST /v1/step {intents:{arena:intent}, ticks:N}`: stashes intents,
  bursts N world ticks on the server thread via `SimTickGate.step()`
  (same mechanism as `sprint` but restores the ambient FREEZE mode),
  then returns per-arena `{tick, kills, damageTaken, damageDealt, done,
  obs|score}`. `{ticks:0}` refreshes obs without ticking.
- `train_rl.py`: PPO over 12 vectorized arenas — Normal move head,
  masked-Categorical target slot, Bernoulli flags; per-env GAE +
  transition-level trajectory (correct bootstrapping at episode and
  materialization boundaries). Reward: `10·dkills + 0.1·ddealt
  − 0.3·dtaken − 0.05·dticks + {clear:+6, died:−50, TIMEOUT:−15}` —
  training signal only; evaluation stays multi-objective.
- Periodic eval stops live episodes, runs `params.net` deployment rollouts
  on a fresh hard+nether set (deterministic decode — thresholding `>0`),
  flags degenerate vectors, re-freezes the gate, and saves
  `pol_best.npy` whenever the deterministic vector improves.

**Rollout-vs-eval turtle pathology** (the central RL finding so far):
sampled-action rollouts clear ~60% while the deployed deterministic
policy flees (kills ~0, survived ~0.9, ~600t timeouts). Diagnosis: the
flag logits sit just below the decode threshold (attack logit ≈ −0.4 →
sampled ~40%, deployed 0%) and entropy *rises* — ambiguity pays in
expectation while the mean action is worthless. This is a commitment
problem, not a reward problem. Mitigation: split entropy (move head
0.003 exploratory vs discrete heads 0.0005 sharpening) + a small hinge
loss pushing flag logits past ±0.15, plus deployment-keyed best-
checkpoint tracking.

**Result (rl5, 500 iters, warm start from rl3b best, lr 1e-4):** the
commitment fixes got the deployed policy fighting, but PPO weight churn
kept oscillating the deterministic eval (0.25↔3.35 kills). Treating an
EMA of the weights (decay 0.995) as the deployment candidate — SWA-style
averaged iterates — collapsed the oscillation into a stable, improving
policy. Fresh 24-scenario hard+nether eval of `champ_rl5.npy`:

```
[champ_rl5] kills 3.04  taken -7.55  clear .583  survived .958  ticks -350
[baseline ] kills 3.21  taken -14.78 clear .583  survived .708  ticks -236
```

Baseline kill/clear parity at half the damage and +25pt survival; the
net only loses the speed axis. Still below the evolved rule champions
(me11_dom dominates it on every axis) — the neural policy closed the
ES-era gap to baseline but rule-programs remain the state of the art
on this task. `champ_rl5.npy` ships deployable via `params.net`.

#### Round 9: representation close-up (K=6 slots + 20-tick frame-stack) + neuro-symbolic hybrid

Two gaps separated the net from the rule champions: **memory** (rules get
rule-ordering/hysteresis for free; the MLP reacts per tick) and **pack
coverage** (4 hostile slots vs the rules' full 9-mob view). Net v2/v3 closes
both: 6 distance-sorted hostile slots and a 20-tick frame stack (~1s of
history — covers skeleton draw ~20t and most attack cooldowns), 99→1980-dim
input, mirrored encoders in `NetPolicy.java` / `optimize_net.py`.

BC corpus rebuilt wide: all 17 champion AST programs × 20 hard+nether
scenarios → 82,613 ticks in `traj_v2/`; `bc_v20.npy` warm-starts both the
pure-neural and hybrid runs.

**Neuro-symbolic hybrid** (user-directed): the net supplies only the
continuous fields (moveDir + lookEntity); the discrete flags (attack, shield,
sprint, jump) come from `champ_me11_dom`'s rules. `HybridPolicy` composes
them at deploy time (`params {net, ast}`); `ExternalPolicy` composes the same
way during training (`--ast` flag on `train_rl.py` posts only move+look
intents). Instant payoff: eval hit champ-tier within 10 iters — AST flags
carry the fight while the net learns where to move and what to aim at.

**Result (hyb1 500 iters + hyb2 500 iters warm-continued, lr 1e-4→5e-5):**
saturated. Fresh 24-scenario eval (same seed for both):

```
[hyb1_best] kills 3.52  taken -5.99  clear .708  survived .922  ticks -290
[hyb2_best] kills 3.48  taken -6.41  clear .740  survived .901  ticks -251
[me11_dom ] kills 4.03  taken -5.87  clear .823  survived .917  ticks -223
[baseline ] kills 2.84  taken -14.68 clear .578  survived .609  ticks -178
```

The hybrid beats baseline on 4/5 axes (half the damage, +30pt survival) but
stays below the flag donor itself — the rule grammar's `type:mob` target
switching out-selects the net's argmax-over-slots. A second 500-iter run at
halved lr produced a statistically identical vector: the move+target learning
is saturated at this flag donor's ceiling. Pure-neural rl6 (same v3
representation, full 12-out action space) was still ~0 kills at iter 60 —
identical early phase to rl5, killed early to free arenas for the hybrid;
rl7 reruns it for 800 iters to get past the entropy-collapse phase.

**Result (rl7, 800 iters, warm start bc_v20, lr 1e-4):** climbed to
rl5-tier (eval-290 [3.21 kills, .611 clear]) then drifted toward survival
— TIMEOUT share rose to ~30%, 10/192 final rollouts were zero-kill
non-deaths. Fresh 24-scen eval of `champ_rl7.npy`:

```
[rl7_best ] kills 2.12  taken -15.68 clear .411  survived .531  ticks -236
[hyb1_best] kills 3.52  taken -5.99  clear .708  survived .922  ticks -290
[me11_dom ] kills 4.03  taken -5.87  clear .823  survived .917  ticks -223
[baseline ] kills 2.84  taken -14.68 clear .578  survived .609  ticks -178
```

Pure neural converged *below* baseline; the hybrid is the current
non-rule champion (beats baseline 4/5 axes, best damage avoidance
overall). The residual gap to me11_dom is target selection — the AST
grammar's conditioned `type:mob` switching beats argmax-over-slots.
`champ_hyb1.npy` + `champ_rl7.npy` ship deployable.

### Selector MoE (sel1): net picks which champ program runs each tick

`SelectorPolicy` + external `{program:k}` intents + `train_rl.py --sel`:
the net (1980→96→48→17) scores all 17 champ programs per tick and the
argmax program supplies the whole intent — inheriting the evolved
grammar's target switching, the piece the flat hybrid lacked.

500-iter PPO-EMA (bc_v20 hidden-layer warm start), entropy 2.83→1.8
(specialized onto ~3 programs). Fresh 24-scen × 8 (N=192) final eval:

| policy | kills | -taken | clear | survived | -ticks |
|---|---|---|---|---|---|
| me11_dom | 4.25 | -5.8 | .84 | .92 | -225 |
| sel1_best | 4.15 | -8.9 | .72 | .81 | -252 |
| hyb1_best | 3.79 | -6.5 | .75 | .87 | -264 |
| baseline | 3.22 | -13.6 | .67 | .73 | -191 |

Selector > flat hybrid (+0.4 kills, target switching helped) but still
below me11_dom on 4/5 axes. Structural reason: in this pool me11_dom is
already the strongest single program on hard scenarios — a mixture with
weaker experts can only approach the best program's ceiling, not exceed
it. Selector value would show on scenario mixes where different experts
specialize; at uniform-hard difficulty it converges back toward
dom-always. `champ_sel1.npy` + `programs_sel.json` ship deployable.

## Verified end-to-end

- Fake player joins, moves under its own physics, looks, and kills mobs.
- Rate-limit and no-legal-target rejections appear in the log.
- `freeze` pauses the sim clock; `sprint` bursts ticks.
- Baseline melee vs 4 zombies: `ALL_MOBS_CLEARED` in 198 ticks, 80 damage
  dealt, 6 taken.

## Known limits / next steps

- `damageDealt` is estimated from per-tick mob health diffs (counts overkill).
- Policy quality: the baseline wins easy fights but is not tuned — that's the
  optimizer's job (CMA-ES over tunables, then AST mutation).
- PvP (fake player vs fake player), terrain variety, and loadout randomization
  are the next environment features.

### Selector sel2 (family-balanced curriculum): MoE ceiling confirmed

Family audit showed different champs win different families (ranged/nether:
champ_11; melee: me9_26) — so a selector COULD in principle beat every
single program. sel2 trained on a family-uniform mix (`--familymix`,
4 pools: standard/ranged/melee/nether), 500-iter PPO-EMA. Final eval
(same fresh 24-scen set): sel2 [3.77k,-5.1t,.75c,.92s] vs me11_dom
[3.84k,-5.2t,.85c,.94s] — still not surpassed. Per-family: melee clear
.984 hits the family-expert ceiling (me9_26 .984) but nether collapsed
(.31 vs .48) — per-tick free switching doesn't reliably reach the
family oracle.

Verdict on the neuro-symbolic chapter: hybrid < selector < me11_dom.
The mixture ceiling is the best program in the pool; me11_dom stands as
the champion of the current representation+grammar. Surpassing it needs
richer primitives (continuous target scoring, shield timing, backstep)
or the PvP regime, not more mixture machinery.

`champ_sel1.npy`, `champ_sel2.npy`, `programs_sel.json` ship deployable.
