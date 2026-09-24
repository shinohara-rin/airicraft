#!/usr/bin/env python3
"""Collect per-tick episode trajectories for champion failure-mode analysis.

Runs each champion program over a fixed set of hard+nether scenarios via the
sim control API, keeps every episode JSONL (the optimizer normally deletes
them after scoring), and writes a manifest pairing each log with its champion
and scenario.

Usage: python3 traj_collect.py [--nscen N] [--arenas K]
"""
import argparse
import json
import sys
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from optimize_cmaes import (  # noqa: E402
    RUN_DIR, MAX_TICKS, NETHER_MOB_POOL, call, random_scenario,
    random_terrain)

OUT = Path(__file__).resolve().parent / "traj_me11"

CHAMPS = {
    "clear": "champ_me11_clear.json",
    "dom": "champ_me11_dom.json",
    "fast": "champ_me11_fast.json",
    "pol26": "champ_pol26_0.json",   # overworld-era champion for comparison
}


def run_one(sim, arena, program, scen, terr, keep_dir, tag):
    sim.center[arena] = sim.center[arena]  # noop, keeps signature simple
    sim.reset_and_spawn(arena, scen, terrain=terr)
    try:
        call("POST", "/v1/tick", {"mode": "sprint", "ticks": 10})
    except RuntimeError:
        pass
    ep = call("POST", "/v1/episode", {
        "arena": arena, "policy": "ast", "maxTicks": MAX_TICKS,
        "obsRadius": 20.0, "params": {"ast": program}})
    log = ep["log"]
    deadline = time.time() + 900
    budget = MAX_TICKS + 50
    while time.time() < deadline:
        try:
            call("POST", "/v1/tick", {"mode": "sprint", "ticks": budget})
        except RuntimeError:
            pass
        st = call("GET", "/v1/status")
        if st.get("gate") == "RUN" and len(st.get("episodes", [])) == 0:
            break
        time.sleep(0.3)
    src = Path(log)
    if not src.is_absolute():
        src = RUN_DIR / src
    dst = keep_dir / f"{tag}.jsonl"
    if src.exists():
        dst.write_bytes(src.read_bytes())
        src.unlink(missing_ok=True)
        return str(dst)
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--nscen", type=int, default=24)
    ap.add_argument("--netherfrac", type=float, default=0.3)
    ap.add_argument("--seed", type=int, default=777)
    args = ap.parse_args()

    arenas = ["traj"]
    sim = __import__("optimize_cmaes").Sim(arenas)
    sim.setup_arena("traj")

    rng = np.random.default_rng(args.seed)
    eval_set = []
    for i in range(args.nscen):
        nether = rng.random() < args.netherfrac
        scen = random_scenario(rng, min_r=5.5, max_r=8.0, min_n=4, max_n=9,
                               pool=NETHER_MOB_POOL if nether else None)
        terr = random_terrain(rng, avoid_pts=[(dx, dz) for _t, dx, dz in scen],
                              nether=nether)
        eval_set.append((i, nether, scen, terr))

    OUT.mkdir(exist_ok=True)
    manifest = []
    for champ_name, path in CHAMPS.items():
        program = json.load(open(Path(__file__).parent / path))
        for i, nether, scen, terr in eval_set:
            tag = f"{champ_name}_s{i}"
            t0 = time.time()
            try:
                dst = run_one(sim, "traj", program, scen, terr, OUT, tag)
            except RuntimeError as e:
                print(f"[skip] {tag}: {e}", flush=True)
                continue
            manifest.append({"champ": champ_name, "scen": i,
                             "nether": nether,
                             "scen_types": [t for t, _x, _z in scen],
                             "log": dst})
            print(f"[done] {tag} nether={nether} mobs={len(scen)} "
                  f"({time.time()-t0:.1f}s)", flush=True)
    (OUT / "manifest.json").write_text(json.dumps(manifest, indent=1))
    print(f"collected {len(manifest)} trajectories -> {OUT}")


if __name__ == "__main__":
    main()
