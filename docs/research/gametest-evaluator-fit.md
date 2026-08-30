# GameTest fit for the Airicraft evaluator

Date: 2026-08-30
Scope: Minecraft Java 1.21.8, Yarn `1.21.8+build.1`, Fabric API `0.136.1+1.21.8`

## Conclusion

A wholesale migration is not practical. Vanilla/Fabric server GameTest and Fabric Client GameTest solve useful but narrower problems than Airicraft's evaluator:

- Use **server GameTest** for short, deterministic, server-authoritative mechanics and world-state checks that can start from an SNBT structure.
- Use **Fabric Client GameTest** for bounded client lifecycle, UI/input, render, screenshot, and client/server synchronization smoke tests.
- Keep the **Airicraft evaluator** as the owner of long-horizon planner/LLM evaluation, frozen full-world fixtures, optional-mod compatibility, external-driver runs, parallel process isolation, subjective review, and durable diagnostic/recording artifacts.

The practical migration is therefore selective extraction, not replacement. A small GameTest lane could remove some custom test setup for low-level mechanics, but wrapping the existing scenarios in GameTest would mostly preserve the current evaluator inside a second harness and add lifecycle constraints without deleting the hard parts.

## What GameTest actually provides in this version

### Server GameTest

Fabric's 1.21.8 API exposes Minecraft's server-side framework through the `fabric-gametest` entrypoint and `@GameTest`. Each test is an instance method taking a Yarn `net.minecraft.test.TestContext`. The annotation selects a test environment and SNBT structure, and configures `maxTicks`, `setupTicks`, required/optional status, rotation, manual-only execution, attempts, required successes, and sky access. The defaults are an empty 8x8 structure and a 20-tick timeout. [Fabric API 0.136.1 GameTest Javadoc](https://maven.fabricmc.net/docs/fabric-api-0.136.1%2B1.21.8/net/fabricmc/fabric/api/gametest/v1/GameTest.html)

The structure is a data-pack resource under `data/<modid>/gametest/structure/*.snbt` and is placed before the method runs. `TestContext` provides relative-coordinate world manipulation, entity spawning, immediate assertions, delayed/repeated tasks, success conditions, and explicit completion/failure. This is a strong fit for compact, deterministic fixtures. [Fabric API GameTest package](https://maven.fabricmc.net/docs/fabric-api-0.136.1%2B1.21.8/net/fabricmc/fabric/api/gametest/v1/package-summary.html), [Yarn 1.21.8 `TestContext`](https://maven.fabricmc.net/docs/yarn-1.21.8%2Bbuild.1/net/minecraft/test/TestContext.html)

Fabric runs it as a special headless dedicated test server, enabled with `-Dfabric-api.gametest`. The 1.21.8 implementation supports a test-name filter, a verification mode that repeats tests 100 times for each rotation, and a JUnit XML report path through system properties. [Fabric 1.21.8 GameTest source](https://github.com/FabricMC/fabric/tree/1.21.8/fabric-gametest-api-v1)

Loom can create a separate test source set and both server/client run configurations. On the exact 1.21.8 Yarn documentation page, `configureTests` defaults server and client GameTests on, can clear the client run directory, and requires explicit EULA acceptance. [Fabric Loom 1.21.8 test DSL](https://docs.fabricmc.net/1.21.8/develop/loom/fabric-api#tests) The official 1.21.8 reference project uses the same API and Fabric API `0.134.0+1.21.8`, so Airicraft's newer `0.136.1+1.21.8` dependency is on the supported line. [Fabric docs 1.21.8 reference build](https://github.com/FabricMC/fabric-docs/blob/main/reference/1.21.8/build.gradle)

### Fabric Client GameTest

Client GameTest is a separate, Fabric-specific experimental API registered through `fabric-client-gametest`. Its tests run sequentially on a dedicated test thread; after each test the client must return to the title screen, and after the suite the game closes. It deliberately controls ticking: the game stays paused until the test asks for ticks, and while a server is running there is exactly one server tick per client tick. Network packets are synchronized to be processed consistently before the next tick. [Fabric API 0.136.1 client GameTest package](https://maven.fabricmc.net/docs/fabric-api-0.136.1%2B1.21.8/net/fabricmc/fabric/api/client/gametest/v1/package-summary.html)

The client context offers timed predicate waits, screen interaction, synthetic keyboard/mouse input, screenshots and fuzzy screenshot assertions, and safe execution on client/server threads. Its world builder creates a new singleplayer flat world by default (seed 1, structures/mob spawning/weather/daylight disabled) or a dedicated server. A `TestWorldSave` can reopen a world created earlier **within the same test**; the public builder does not expose an existing-save import operation. [Client context Javadoc](https://maven.fabricmc.net/docs/fabric-api-0.136.1%2B1.21.8/net/fabricmc/fabric/api/client/gametest/v1/context/ClientGameTestContext.html), [world builder Javadoc](https://maven.fabricmc.net/docs/fabric-api-0.136.1%2B1.21.8/net/fabricmc/fabric/api/client/gametest/v1/world/TestWorldBuilder.html)

This makes Client GameTest attractive for focused UI/render/input smoke. It is not a drop-in runner for Airicraft's prebuilt survival saves, production-style compatibility mod pack, or process-level recorder orchestration.

## Airicraft evaluator boundary

The current evaluator is an optional **client addon**, not merely an assertion library:

1. Scenario YAML carries a natural-language prompt, full-world ZIP, frozen flag, planner-turn/tick/wall-clock budgets, heartbeat cadence, deterministic checks, waypoints, and evidence settings. [`EvaluationScenario`](../../addons/evaluator/src/client/java/ai/moeru/airicraft/agent/evaluation/EvaluationScenario.java), [`EvaluationScenarioLoader`](../../addons/evaluator/src/client/java/ai/moeru/airicraft/agent/evaluation/EvaluationScenarioLoader.java)
2. Each run restores the archived save into a disposable singleplayer world and joins it through the normal client flow. [`EvaluationWorldFixtureService`](../../addons/evaluator/src/client/java/ai/moeru/airicraft/agent/evaluation/EvaluationWorldFixtureService.java)
3. The runner drives either the configured planner or an external driver, emits the initial prompt and periodic heartbeats, evaluates Airicraft-specific state, and distinguishes `PASSED`, `FAILED`, and `NEEDS_REVIEW`. Its checks include inventory, block/state/count, Airicraft events, chat, task state, and task-execution state. [`ScenarioEvaluationRunner`](../../addons/evaluator/src/client/java/ai/moeru/airicraft/agent/evaluation/ScenarioEvaluationRunner.java)
4. The addon records status samples, events, debug timeline, LLM calls, planner-call records, final agent/task snapshots, and final world evidence. [`EvaluationFlightRecorder`](../../addons/evaluator/src/client/java/ai/moeru/airicraft/evaluator/EvaluationFlightRecorder.java)
5. The outer runner starts production-style clients, isolates game and bridge directories per worker, runs scenarios in parallel processes, produces a batch manifest/Markdown summary, redacts secrets, and can attach a finalized external recorder Play/replay. [`run-evaluation-scenarios`](../../scripts/run-evaluation-scenarios)

The checked-in scenarios demonstrate the mismatch in scale. They restore complete 5.5-16 MB zipped saves and allow 1,200-120,000 ticks (up to 6,000 nominal seconds), with 4-80 planner turns. For example, [`farm_from_scratch`](../../scenarios/farm_from_scratch/scenario.yml) allows 120,000 ticks and [`iron-pickaxe`](../../scenarios/iron-pickaxe/scenario.yml) allows 72,000. GameTest's configurable timeout can represent those numbers, but doing so does not make its small, deterministic test-site execution model suitable for probabilistic LLM jobs.

## Capability fit

| Concern | Server GameTest | Client GameTest | Current evaluator |
|---|---|---|---|
| Compact deterministic world fixture | Excellent: SNBT structure + relative coordinates | Possible after creating a world | Possible, but full-save ZIP is heavier |
| Vanilla server mechanics/assertions | Excellent | Accessible through integrated/dedicated server helpers | Possible through custom checks |
| Client UI, input, rendering, screenshots | No | Excellent | Client exists, but helpers are custom/manual |
| Existing authored survival save | No stock import path; would need custom setup | No public existing-save import path | Native fixture model |
| LLM/planner/external-driver lifecycle | No built-in concept | No built-in concept | Core responsibility |
| Long-horizon probabilistic outcome | Technically waitable, operationally awkward | Technically waitable, sequential and process-bound | Designed for budgets, heartbeats, and review |
| Subjective/external review | No | No | `NEEDS_REVIEW` and retained evidence |
| Optional-mod production compatibility | Separate custom run wiring required | Separate custom production run wiring required | Existing evaluator+compat launch profile |
| Reports | Console + optional JUnit XML | Failure/exit plus screenshots; not the server XML reporter | JSON/JSONL evidence, summaries, batch manifest, replay linkage |
| Batch isolation/parallelism | One headless test server can batch compact tests | Sequential within one client | Isolated parallel clients and retained failed workers |

## Recommended boundary

### Migrate or add first

- New deterministic tests for block/entity mechanics, callbacks, world-state transitions, and action primitives that can complete in a small bounded structure.
- Existing tests that currently spend most of their code constructing Minecraft world state before making one server-authoritative assertion.
- Focused client smoke for screen registration, key bindings, HUD/render behavior, and screenshot stability. Keep these separate from the evaluator rather than making Client GameTest a parent harness for evaluator scenarios.

### Keep in the evaluator

- All current planner scenarios unless a scenario is explicitly being decomposed into a deterministic lower-level invariant.
- Full-save survival environments, JourneyMap/REI/Baritone compatibility, player-visible client lifecycle, external recorder capture, and external-driver operation.
- Airicraft-domain verdicts (`task_execution_state`, planner turns, event provenance), subjective review, and the diagnostic evidence bundle.
- Multi-minute/hour-scale scenarios and batch process isolation.

### Avoid

- Converting each YAML scenario into an annotated GameTest method while still restoring ZIPs, starting the planner, polling it, recording artifacts, and managing cleanup inside the method. That is adapter churn, not removal of custom wheels.
- Replacing evaluator JSON/JSONL artifacts with JUnit XML. XML can be an additional CI summary, but it does not carry the evidence needed to diagnose agent behavior.
- Treating server GameTest and Client GameTest as one unified lifecycle. They are separate runners with different threading, world, reporting, and batching contracts.

## Low-risk proof before adoption

Implement a separate test source set and port two narrow cases only:

1. One server-authoritative primitive with a tiny SNBT structure and tick-based assertion.
2. One client smoke that creates a world, waits for chunks, exercises a client interaction, and captures a screenshot.

Measure cold-start time, flakiness over server verification mode, CI behavior, mapping/API churn, and whether failures are diagnosable from JUnit/log/screenshot output. Adoption is justified if these tests delete custom setup or provide materially better determinism. No current end-to-end evaluator scenario needs to move for that experiment.

## Primary sources

- [Fabric Loom 1.21.8 test DSL](https://docs.fabricmc.net/1.21.8/develop/loom/fabric-api#tests)
- [Fabric API `GameTest` 0.136.1+1.21.8 Javadoc](https://maven.fabricmc.net/docs/fabric-api-0.136.1%2B1.21.8/net/fabricmc/fabric/api/gametest/v1/GameTest.html)
- [Fabric API server GameTest package Javadoc](https://maven.fabricmc.net/docs/fabric-api-0.136.1%2B1.21.8/net/fabricmc/fabric/api/gametest/v1/package-summary.html)
- [Fabric API client GameTest package Javadoc](https://maven.fabricmc.net/docs/fabric-api-0.136.1%2B1.21.8/net/fabricmc/fabric/api/client/gametest/v1/package-summary.html)
- [Yarn 1.21.8 `TestContext` Javadoc](https://maven.fabricmc.net/docs/yarn-1.21.8%2Bbuild.1/net/minecraft/test/TestContext.html)
- [Fabric 1.21.8 GameTest implementation](https://github.com/FabricMC/fabric/tree/1.21.8/fabric-gametest-api-v1)
- [Fabric's 1.21.8 release notes](https://fabricmc.net/2025/06/15/1216.html)

The Minecraft Wiki [GameTest overview](https://minecraft.wiki/w/GameTest) was used only as orientation; version-specific conclusions above are grounded in Fabric's 1.21.8 documentation, API artifact, and source.
