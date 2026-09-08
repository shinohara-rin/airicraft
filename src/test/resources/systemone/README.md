# Recorded support counterexamples

These deterministic gzip JSON fixtures contain only Minecraft observation values.
Each top-level object records its source evaluation directory and decision tick.
`observation` uses `StoneTape.Observation`; `changed` contains the reconstructed
remembered cells within eight blocks of the recorded feet on each axis, and
`removed` is empty. Inventory, pose, footing history, and vitals are retained.
No hidden fixture ground truth or control credentials are included.

These historical block-only fixtures explicitly supply `drops: []` for the v64
observation schema. Their original sensors did not record item observations;
the empty list is a test adaptation, not evidence that no items existed live.
Original evaluation recordings remain untouched.

- `canopy.json.gz`: v51 canopy at tick 259, with partial contact beside the feet voxel.
- `original-search.json.gz`: v54 original at tick 1036, after bootstrapping tools.
- `tree-origin.json.gz`: v62 tree-indicator fixture at tick 570, after walking off the remaining log. Source: `20260908-071051-813945-15866/02-system-one-tree-indicator`. The complete accumulated observation is retained because reducing the neighborhood removed part of the walking bypass. No hidden terrain is included.
- `tree-unused-perch.json.gz`: v64 tree-indicator fixture at tick 639, after collecting two logs and walking off the last log. Its complete observation includes an isolated, never-visited leaf perch that was unnecessarily reserving the log. Unlike the historical block-only fixtures, this source already recorded item observations.

The reduction only removes remembered cells. It cannot introduce a walking edge
or authorize an otherwise unknown block. Tests preserve current contact and the
explicit return anchor while exercising the previously blocked decisions.
