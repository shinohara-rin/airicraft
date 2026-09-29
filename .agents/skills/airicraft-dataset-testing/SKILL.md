---
name: airicraft-dataset-testing
description: How to launch the Airicraft dev client and exercise the `airicraft dataset` capture pipeline end-to-end (world install, bridge, verification checks) on this repo's dev machine.
---

# Airicraft dataset capture E2E testing

## Launch

- Gradle needs `JAVA_HOME=$HOME/jdk25/jdk-25.0.4.1+1` (JDK 25). Passing `env` to the exec tool may not reach a backgrounded launch — set it inline in the command instead.
- `DISPLAY=:0 scripts/codex-driver` launches a real on-screen client (records well); bridge lands at `~/.airicraft/bridge-state.json` in ~60-90s.
- CLI is `wrapper/build/install/airicraft/bin/airicraft` (built by `wrapper:installDist`, which codex-driver runs).

## Getting into a world

- `airicraft worlds join --world-id <id>` needs a save under `run/saves/`. `scenarios/unpack-worlds` may crash on scenarios whose `worldArchive` points outside its dir (e.g. `../farm_easy/world.zip`) — workaround: `mkdir -p run/saves/<name> && unzip -q scenarios/<id>/world.zip -d run/saves/<name>`; the resulting worldId is `<name>-<hash>` from `airicraft worlds list`.
- `airicraft ticks pause` does not exist — tick control lives under `airicraft agent debug ticks pause|continue|step`.

## Verifying captures

- Output root: `run/airicraft/dataset/<captureId>/` with `frame.png`, `meta.json`, `labels.json.gz`, `region.json.gz`, `entities.json`, and `captures.jsonl` appended at the root.
- Strongest consistency check: for each `kind=block` label cell, rebuild the ray (cell center -> `outputToSource` letterbox inverse -> `sourcePixelRay` with meta camera basis + projectionMatrixRowMajor) and verify `eye + dir*depth` lands inside `[blockX,blockX+1]x[blockY,blockY+1]x[blockZ,blockZ+1]`. See /tmp/verify_capture.py approach — pass criteria: 100% inside AABB (tolerance ~0.06).
- `viewVisible` in region.json.gz must equal exactly the set of label-hit block positions **inside the region bounds** — hit blocks outside the region are legitimately absent.
- `entityCells` legitimately stays 0 in occluded scenes (entities listed in entities.json may all be behind terrain); don't fail on it, check cells covering onScreen entity rects have smaller block `depth` instead.
- Error paths: out-of-world capture -> `world_not_loaded`; validation order is world check -> perspective -> options, so `invalid_request` for bad --stride-px/--yaw only appears once in-world.
