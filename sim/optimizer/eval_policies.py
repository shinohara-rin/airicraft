"""Consolidated CRN eval: every candidate policy over the SAME eval set.

One Sim, arenas set up once at start (repeat setup_arena on a live name
leaks duplicate fake players and corrupts results). Each policy sweeps the
full (scen, terr) set in batches of narenas; policies are compared on the
identical set -> paired (CRN) comparison.

Metrics are the raw 5-dim objective vector [kills, -taken, clear, survived,
-ticks]; evaluation stays multi-objective, no scalarization.

Usage:
  python3 eval_policies.py [--seed 7777] [--nscen 24] [--arenas 8]
"""
import argparse
import json
import sys
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from optimize_cmaes import (Sim, metrics_of, METRIC_NAMES,  # noqa: E402
                            random_scenario, random_terrain,
                            NETHER_MOB_POOL)
from optimize_net import spec_of, INPUT  # noqa: E402
from train_rl import spec_of_ptr, spec_of_sized, FAM_POOLS  # noqa: E402


def load_ast(path):
    return {"ast": json.loads(Path(path).read_text())}


def load_net(path):
    return {"net": spec_of(np.load(path))}


def load_ptr(path):
    return {"net": spec_of_ptr(np.load(path))}


def load_sel(net_path, programs_path):
    programs = json.loads(Path(programs_path).read_text())
    vec = np.load(net_path)
    return {"net": spec_of_sized(vec, (INPUT, 96, 48, len(programs))),
            "programs": programs}


def sample_eval_set(rng, n):
    """Same distribution as train_rl --familymix evals."""
    out = []
    fams = list(FAM_POOLS)
    for _ in range(n):
        fam = fams[rng.integers(len(fams))]
        pool = FAM_POOLS[fam]
        nether = fam == "nether"
        scen = random_scenario(rng, min_r=5.5, max_r=8.0,
                               min_n=4, max_n=9, pool=pool)
        terr = random_terrain(
            rng, avoid_pts=[(dx, dz) for _t, dx, dz in scen], nether=nether)
        out.append((fam, scen, terr))
    return out


def eval_policy(sim, arenas, policy, params, eval_set):
    """Mean metric vector over eval_set; one episode per (scen,terr)."""
    acc = np.zeros(len(METRIC_NAMES))
    per_scen = []
    n_done = 0
    for c0 in range(0, len(eval_set), len(arenas)):
        chunk = eval_set[c0:c0 + len(arenas)]
        scens = [s for _f, s, _t in chunk]
        terrs = [t for _f, s, t in chunk]
        try:
            scores = sim.run_batch([params] * len(chunk), None,
                                   scenarios=scens, terrains=terrs,
                                   policy=policy)
        except RuntimeError as e:
            print(f"  [warn] batch skipped: {e}", flush=True)
            continue
        n_done += 1
        for i, s in enumerate(scores):
            m = metrics_of(s)
            acc += m
            per_scen.append({"fam": chunk[i][0], "vec": m.tolist()})
    n_eps = max(len(per_scen), 1)
    return acc / n_eps, per_scen


def dominates(a, b):
    return bool(np.all(a >= b) and np.any(a > b))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seed", type=int, default=7777)
    ap.add_argument("--nscen", type=int, default=24)
    ap.add_argument("--arenas", type=int, default=8)
    ap.add_argument("--ptr3", default="results_ptr3/pol_best.npy")
    ap.add_argument("--ptr2b", default="results_ptr2b/pol_best.npy")
    ap.add_argument("--out", default="eval_final.json")
    args = ap.parse_args()

    arenas = [f"ev{i}" for i in range(args.arenas)]
    sim = Sim(arenas)
    for name in arenas:
        sim.setup_arena(name)
    print(f"[setup] {len(arenas)} arenas ready", flush=True)

    rng = np.random.default_rng(args.seed)
    eval_set = sample_eval_set(rng, args.nscen)
    fams = [f for f, _s, _t in eval_set]
    print(f"[evalset] {args.nscen} scenarios, families={fams}", flush=True)

    me11 = "champ_me11_dom.json"
    candidates = [
        ("baseline", "baseline-melee", None),
        ("me11_dom", "ast", load_ast(me11)),
        ("bc_v20", "net", load_net("bc_v20.npy")),
        ("rl7", "net", load_net("champ_rl7.npy")),
        ("hyb1", "hybrid", {**load_net("champ_hyb1.npy"), **load_ast(me11)}),
        ("sel1", "selector", load_sel("champ_sel1.npy", "programs_sel.json")),
        ("ptr1", "net", load_ptr("champ_ptr1.npy")),
        ("ptr2", "net", load_ptr("champ_ptr2.npy")),
    ]
    if Path(args.ptr2b).exists():
        candidates.append(("ptr2b", "net", load_ptr(args.ptr2b)))
    if Path(args.ptr3).exists():
        candidates.append(("ptr3", "net", load_ptr(args.ptr3)))

    results = {}
    for name, policy, params in candidates:
        t0 = time.time()
        vec, per_scen = eval_policy(sim, arenas, policy, params, eval_set)
        results[name] = {"vec": vec.tolist(), "per_scen": per_scen}
        degen = ""
        if vec[3] > 0.95 and vec[0] < 0.5:
            degen = "  <-- DEGENERATE (survives by not fighting)"
        print(f"[{name}] {[round(float(v), 3) for v in vec]} "
              f"({time.time() - t0:.0f}s){degen}", flush=True)

    # pairwise domination counts on mean vectors
    names = list(results)
    print("\n[dominance] A strictly dominates B (mean vectors):")
    for a in names:
        va = np.array(results[a]["vec"])
        for b in names:
            if a != b and dominates(va, np.array(results[b]["vec"])):
                print(f"  {a} > {b}")

    # per-scenario domination: how many scenarios A's vector dominates B's
    print("\n[per-scenario dominance wins]:")
    for a in names:
        for b in names:
            if a >= b:
                continue
            pa = {i: np.array(x["vec"]) for i, x in enumerate(results[a]["per_scen"])}
            pb = {i: np.array(x["vec"]) for i, x in enumerate(results[b]["per_scen"])}
            both = sorted(set(pa) & set(pb))
            na = sum(1 for i in both if dominates(pa[i], pb[i]))
            nb = sum(1 for i in both if dominates(pb[i], pa[i]))
            print(f"  {a} vs {b}: {na}-{nb}")

    Path(args.out).write_text(json.dumps(
        {"seed": args.seed, "eval_set": [
            {"fam": f, "scen": s, "terr": t} for f, s, t in eval_set],
         "results": results}, indent=1))
    print(f"\nsaved {args.out}")


if __name__ == "__main__":
    main()
