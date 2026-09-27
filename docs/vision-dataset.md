# Vision dataset capture

Airicraft can dump paired frames and ground truth for training a spatially aware
vision model. Each capture is one first-person frame plus everything needed to
explain it: camera pose, a raycast label grid, a voxel region with a
line-of-sight mask, and entity records with screen projections.

```bash
airicraft dataset capture --label village-look
airicraft dataset capture --yaw 35 --pitch -5 --stride-px 4 --region-radius 48
airicraft dataset capture --look-at 100,70,-40 --no-region
airicraft dataset status
```

`airicraft dataset capture` blocks until the frame has been rendered, labeled,
and written; the response includes the capture directory and file list.
`--yaw`/`--pitch`/`--look-at` point the camera before the shot; without them the
current view is captured. Tip: run `airicraft agent debug ticks pause` first to
freeze the world between captures.

## Layout

```
<output-dir>/<captureId>/
  frame.png        854x480 first-person frame, letterboxed like ticks screenshots
  meta.json        camera pose, effective FOV, projection, letterbox, world info
                   (dimensionId, worldTime, timeOfDay, moonPhase, raining,
                   thundering, biome at eye, skyLight/blockLight/lightLevel)
  labels.json.gz   raycast grid — one record per cell
  region.json.gz   voxel dump around the camera, with viewVisible LOS mask
  entities.json    entities with box projection and covered cells
```

`<output-dir>` defaults to `<gameDir>/airicraft/dataset` (`run/airicraft/dataset`
in the dev client); relative paths resolve against the game directory. An index
file `captures.jsonl` at its root is appended once per capture.

## `labels.json.gz`

`cells[]` is a grid over the **output** image (default stride 8 px → 107x60
cells). Each cell is `kind: block|entity|sky|padding` plus, for blocks,
`blockX/Y/Z`, `blockId`, `stateKey` (`minecraft:oak_log[axis=y]`), and `depth`
(ray distance); for entities, `entityId`, `entityUuid`, `entityType`. `padding`
marks letterbox bars. Block cells additionally carry `cutoutChecked` — `true`
when the label was confirmed against rendered quad texels (see below).

Hit cells (block and entity) also carry `egoForward`/`egoRight`/`egoUp`: the
ray hit point decomposed into the camera's egocentric frame — `forward` is
along the look direction, `right`/`up` are the camera right/up axes, all in
meters. The basis vectors are exported as `meta.json.cameraBasis`; absolute
hit/block positions are recoverable as `camera + forward*f + right*r + up*u`.

Transparent-texture blocks (crossed plants, fancy-graphics leaves, glass) are
refined per texel: a hit on a non-opaque cube is intersected with the block's
baked quads and the sprite alpha at the hit UV is sampled — rays through
transparent texels continue past the block to whatever the pixel actually shows
(the next block, an entity, or sky). `cutoutChecked: true` on a block cell means
its label passed this check; without it the label is the raw outline-shape hit
(solid blocks, or states whose model exposes no quads, e.g. fluids).

Blocks inside a render section whose chunk mesh has not been built yet are
invisible on the captured frame (the pixels show sky/clouds while the block
exists in world data). Rays pass through such sections entirely, so a cell
labels what the pixel shows, not what the world contains.

If the camera eye block suffocates the player (`shouldSuffocate` — e.g. a
`/tp` landing inside terrain) or the eye is inside a fluid
(`camera_submerged`), the capture is skipped: the response is
`skipped: true` with `skipReason` set and no files are written.

## `region.json.gz`

Axis-aligned region centered on the camera (`--region-radius`,
`--region-below`, `--region-above`, up to 4M cells / radius ≤ 64). Each cell:
`x,y,z`, `loaded` (chunk loaded), `id`, `stateKey`, `air`, `light`,
`skyVisible` (sky above), and `viewVisible` — whether any label-grid ray hit
this exact block this frame. `viewVisible` is the LOS mask used to ask "is
this block visible?" even though it never produced a pixel label.

## `entities.json`

Per entity: `pos` (absolute), `ego` (egocentric forward/right/up of the feet
position), `box`, `distance`, `screen` (projected pixel rect, may be `null`
when off-screen or behind), `hitCells` (how many grid rays hit it), `onGround`,
`airTicks`.

## Consistency guarantees

- The label pass runs inside the world render pass right after the framebuffer
  snapshot, so pixels and labels share the same tick, camera, and projection.
- The camera basis is `camera.getYaw()/getPitch()` — the same interpolated
  values the renderer used that frame. Projection is rebuilt from the
  *effective* FOV (`getFov(camera, tickProgress, true)` →
  `getBasicProjectionMatrix`), matching vanilla's matrix, so pixel↔world math
  stays exact under sprint/zoom FOV changes.
- `depth` is world-space distance along the ray. `egoForward/egoRight/egoUp`
  use the `cameraBasis` vectors exported in `meta.json`.
- Cutout-aware hits move the reported `depth`/`pos` to the quad surface the ray
  actually crosses, and a block passed through this way contributes no
  `viewVisible` mark for that pixel. The same applies to blocks passed through
  because their render section had no built mesh.

## Training data: `scripts/generate-spatial-qa`

```bash
python3 scripts/generate-spatial-qa --input ~/.airicraft/dataset --out qa/
```

Emits `train.jsonl` / `eval.jsonl` (split at capture granularity, images are
`<captureId>/frame.png`), `patch_labels.jsonl` (per-32px-patch majority label
for patch-level pretraining), and `summary.json`. QA types: `block_at_pixel`,
`entity_at_pixel`, `point_to`, `egocentric_offset`, `distance_to`,
`depth_compare`, `left_right`, `above_below`, `count_visible`, `entity_count`,
`line_of_sight`. Stdlib only — safe to iterate on labels without rebuilding
the mod.

## Collection tips

- Freeze the world between shots: `airicraft agent debug ticks pause`.
- Capture diverse views by scripting `yaw`/`pitch` sweeps against the same
  position, or use `--look-at` on interesting blocks from a `voxels` scan.
- `stridePx` trades label density for capture latency; 8 is a good default,
  4 is near-dense, 16 is cheap overview.
- Captures are single-shot; spam thousands of requests for scale-out rather
  than one giant region.
