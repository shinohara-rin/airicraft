# Recorded support counterexamples

These deterministic gzip JSON fixtures contain only Minecraft observation values.
Each top-level object records its source evaluation directory and decision tick.
`observation` uses `StoneTape.Observation`; `changed` contains the reconstructed
remembered cells within eight blocks of the recorded feet on each axis, and
`removed` is empty. Inventory, pose, footing history, and vitals are retained.
No hidden fixture ground truth or control credentials are included.

- `canopy.json.gz`: v51 canopy at tick 259, with partial contact beside the feet voxel.
- `original-search.json.gz`: v54 original at tick 1036, after bootstrapping tools.

The reduction only removes remembered cells. It cannot introduce a walking edge
or authorize an otherwise unknown block. Tests preserve current contact and the
explicit return anchor while exercising the previously blocked decisions.
