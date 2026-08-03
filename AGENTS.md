# Airicraft Agent Notes

## Current Project State
- This repo is a Fabric mod for Minecraft `1.21.8`.
- It currently uses Yarn mappings, not Mojang official mappings.
- Java target is `21`.
- The build is a multi-project Gradle build with:
  - root project: Fabric mod
  - `wrapper/`: standalone Java CLI for agent-driven control

## Build And Run
- Before running build or test verification commands, source `.envrc` first if exists: `source .envrc`
- Full build: `./gradlew build`
- Run Minecraft client in dev: `./gradlew runClient`
- `runClient` starts JDWP by default on `127.0.0.1:5005` with `suspend=n`
- Attach a debugger with `jdb -attach 127.0.0.1:5005` or any JDWP client
- Override JDWP settings with Gradle properties, for example:
  - `./gradlew runClient -Pairicraft.jdwp.port=5006`
  - `./gradlew runClient -Pairicraft.jdwp.suspend=y`
- Compatibility smoke:
  - Use `scripts/compat run`, not plain `runClient`.
  - Why: external optional-mod jars are production/intermediary; dev remap path can conflict.
  - It integrates all supported optional mods instead of testing them one at a time.
  - Debug port: JDWP `127.0.0.1:5007`.
  - Config shared with normal dev: `run/config/airicraft`.
  - Jar cache ignored: `.airicraft-compat/integration/`; never vendor optional-mod jars or copy them into `run/mods`.
  - Setup/list jars: `scripts/compat setup`, `scripts/compat mods`.
  - Verify live: mod list has `airicraft` + `airicraft-journeymap-compat` + `journeymap` + `airicraft-rei-compat` + `roughlyenoughitems`; `airicraft map status` says `available: true`, `preferredProvider: journeymap`; planner still exposes `search_recipes`.
- Arthas CLI live-debug:
  - Cold-start path: `scripts/arthas kickstart` starts `runClient`, waits for the bridge, joins the first saved world, opens LAN, and attaches Arthas for later probes. It is cold-only and fails fast if a client is already running.
  - Manual start: `source .envrc && ./gradlew runClient`
  - Attach once: `source .envrc && ./gradlew arthasAttach`
  - Default probe interface after attach: `scripts/arthas v`, `scripts/arthas sc 'ai.moeru.airicraft.*'`, `scripts/arthas sm <class> <method>`, `scripts/arthas w <class> <method>`, `scripts/arthas raw 'thread -n 1'`
  - If another JVM owns the default Arthas port, pass the Minecraft port: `scripts/arthas --port 8564 sc ai.moeru.airicraft.ModBridgeServer`
  - If select fails: `jps -lv`, then `source .envrc && ./gradlew arthasAttach -Pairicraft.arthas.pid=<pid>`
  - Useful probes: `sc ai.moeru.airicraft.*`, `sm <class>`, `jad <class>`, `thread -n 5`, `dashboard`
  - Useful live observe: `watch <class> <method> '{params, returnObj, throwExp}' -n 1 -m 1 --timeout 10`
  - Useful path cost: `trace <class> <method> '#cost>10' -n 1 -m 1 --timeout 10`
  - Useful call source: `stack <class> <method> -n 1 --timeout 10`
  - Useful history: `tt -t <class> <method> -n 1 -m 1 --timeout 10`; cleanup with `tt --delete-all`
  - Use full class/method patterns. Broad `watch`/`trace`/`tt` can slow or hang client.
  - `ognl`, `vmtool`, `sysprop`, `vmoption`, `redefine`, `retransform`, `mc` mutate runtime. Ask before use.
  - OGNL runs on Arthas thread, not Minecraft client thread. Do not mutate MC world/player state with OGNL.
  - Airibridge = safe domain actions. Arthas = inspect/instrument. JDWP = pause/step.
  - If behavior weird after rebuild, restart `runClient`; Arthas sees loaded old classes until client restart.
- CLI entrypoint: `wrapper/src/main/java/ai/moeru/airicraft/wrapper/AiricraftCliMain.java`
- CLI artifact is built by the `wrapper` subproject as a runnable jar and application distribution.

## Architecture
- The public control surface is the standalone `wrapper` CLI.
- The Fabric mod exposes an internal localhost HTTP bridge.
- Bridge discovery is via `~/.airicraft/bridge-state.json`.
- The CLI reads the state file, calls the localhost bridge, and deletes stale state if the bridge is unreachable.

## Key Mod-Side Files
- `src/client/java/ai/moeru/airicraft/ModBridgeServer.java`
  - localhost bridge entrypoint
  - bridge auth, routing, error mapping
- `src/client/java/ai/moeru/airicraft/ClientRuntimeController.java`
  - bridge lifecycle and highlight ticking/rendering
- `src/client/java/ai/moeru/airicraft/HighlightManager.java`
  - block/region highlight registry and rendering
- `src/client/java/ai/moeru/airicraft/SingleplayerWorldService.java`
  - list and join saved singleplayer worlds
- `src/client/java/ai/moeru/airicraft/SavedServerService.java`
  - list and join saved multiplayer servers

## Key Wrapper Files
- `wrapper/src/main/java/ai/moeru/airicraft/wrapper/AiricraftCliMain.java`
  - CLI command tree, text output, error handling
- `wrapper/src/main/java/ai/moeru/airicraft/wrapper/HttpBridgeTransport.java`
  - bridge HTTP client and stale-state handling

## Current CLI Commands
- `airicraft status`
- `airicraft reload`
- `airicraft worlds list`
- `airicraft worlds join --world-id <id>`
- `airicraft servers list`
- `airicraft servers join --server-id <id>`
- `airicraft player focus`
- `airicraft player look-at --x <x> --y <y> --z <z>`
- `airicraft agent debug chat --message <text>`
- `airicraft agent debug idle-trigger`
- `airicraft agent debug state`
- `airicraft agent debug timeline [--since <entry-id>]`
- `airicraft agent debug ticks state`
- `airicraft agent debug ticks pause [--output-image <path>]`
- `airicraft agent debug ticks step --debug-session-id <id> --pause-epoch <epoch> [--output-image <path>]`
- `airicraft agent debug ticks continue --debug-session-id <id> --pause-epoch <epoch>`
- `airicraft agent debug trace status`
- `airicraft agent debug trace start --info <names> --window-ticks <ticks> --output <path> [--once] [--entity-query <json>] [--block-query <json>]`
- `airicraft agent debug trace stop --trace-id <id>`
- `airicraft agent debug world metadata --snapshot-id <id>`
- `airicraft agent debug world player-state --snapshot-id <id>`
- `airicraft agent debug world entities --snapshot-id <id> [region, radius, identity, name, type, and state filters]`
- `airicraft agent debug world get-block --snapshot-id <id> --x <x> --y <y> --z <z>`
- `airicraft agent debug world scan-box --snapshot-id <id> --min-* <n> --max-* <n> [--cursor <n>] [--limit <n>]`
- `airicraft agent debug world find-blocks --snapshot-id <id> --min-* <n> --max-* <n> --block-id <ids>`
- `airicraft agent debug world region-stats --snapshot-id <id> --min-* <n> --max-* <n> [--cursor <n>] [--limit <n>]`
- `airicraft world snapshot [--x <x> --y <y> --z <z>] [--radius <0-4>]`
- `airicraft highlights block --x <x> --y <y> --z <z> [--color <hex>] [--duration-seconds <1-86400>] [--overlay-text <text>]`
- `airicraft highlights region --x1 <x> --y1 <y> --z1 <z> --x2 <x> --y2 <y> --z2 <z> [--color <hex>] [--duration-seconds <1-86400>] [--overlay-text <text>]`
- `airicraft highlights list`
- `airicraft highlights clear --highlight-id <id>`
- `airicraft highlights clear-all`
- `airicraft help [command...]`

## CLI Output Contract
- Operational commands print deterministic plain text to `stdout`.
- Success starts with:
  - `status: ok`
  - `command: <command path>`
- Failure starts with:
  - `status: error`
  - `command: <command path>`
  - `error_code: <stable_code>`
  - `message: <text>`
- `help` and `--help` are text-only usage output.
- Exit codes:
  - `0` success
  - `2` CLI parse or validation failure
  - `3` bridge discovery or transport failure
  - `4` bridge/domain/state failure
  - `1` unexpected internal failure

## Current Bridge Endpoints
- `GET /v1/status`
- `POST /v1/reload`
- `GET /v1/worlds`
- `POST /v1/worlds/join`
- `GET /v1/servers`
- `POST /v1/servers/join`
- `GET /v1/focus`
- `GET /v1/world-snapshot`
- `GET|POST|DELETE /v1/highlights`

## Behavior Notes
- The bridge is tied to the Minecraft client process, not world load state.
- `airicraft reload` and `/airicraft reload` reload both config files live without restarting the client.
- Reload preserves the bridge session, highlights, and current world connection, but resets active agent/planner/task state.
- `airicraft status` is a probe command and still exits `0` when Minecraft is unavailable, reporting `available: false`.
- World-bound read/action commands still return `world_not_loaded` when no world is active.
- `airicraft worlds join` and `airicraft servers join` return `already_in_world` if a world is already loaded.
- `airicraft worlds list` is intended to return `already_in_world` once the client is restarted onto the latest code.
- `airicraft servers list` can still safely enumerate saved servers while out of world.
- Highlights support:
  - persistent by default
  - optional timeout
  - custom `overlayText`
  - block and region highlights
  - list, clear-one, clear-all

## Verified So Far
- `./gradlew build` passes.
- Wrapper bridge initialization and stale discovery cleanup logic work.
- Bridge stale discovery handling works.
- Focus, world snapshot, and highlight flows were previously tested end-to-end.
- Out-of-world world listing and `join_world` were tested against the bridge:
  - listing saved worlds worked
  - joining a saved world worked
  - repeated join while already in world returned `already_in_world`

## Important Caveat
- If behavior changes in bridge handlers do not appear in a running dev client, restart `runClient`.
- A running Minecraft dev process keeps the old classes loaded even if the repo has already been rebuilt.
- The in-mod verification scenarios are stateful. Running multiple planner/follow scenarios back to back in one client session can produce cross-scenario interference.
- In particular, `llm.degradation_goal_preserved` intentionally drives the runtime into degraded mode before reset, so later planner/follow scenarios should be run individually or after restarting `runClient`.
