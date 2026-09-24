#!/usr/bin/env python3
"""MAP-Elites over `ast` policy programs: diversity-preserving structural search.

Instead of squeezing all programs onto a single Pareto front, the archive is a
64-cell behavior grid — (mean kills) x (mean damage taken) — and each cell
keeps its own non-dominated elite programs. Niche specialists (e.g. an
ultra-safe low-kill policy) survive instead of being dominated out by the
generalist front. Progress = grid coverage + hypervolume of the union of all
cell elites on the shared-scenario-set objective vector.

Evaluation reuses optimize_cmaes infra; variation reuses optimize_gp's GP ops.
"""

import argparse
import copy
import json
import sys
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from optimize_cmaes import (  # noqa: E402
    Sim, call, metrics_of, METRIC_NAMES, HV_REF, HV_IDEAL,
    _nd_fronts, _crowding, hypervolume, random_scenario, random_terrain,
    NETHER_MOB_POOL)
import optimize_gp as gp  # noqa: E402
from optimize_polish import (  # noqa: E402
    genome_of, apply_genome, mutate_genome)

KILL_BINS = np.arange(9)            # kills mean 0..7 (clipped)
TAKEN_EDGES = [2, 4, 6, 9, 13, 18, 25]  # damage-taken bin edges -> 8 bins
TICKS_EDGES = [140, 180, 220, 280, 350, 450, 540]  # -ticks bins -> 8
GRID = (8, 8)


def cell_of(objs, cell2="taken"):
    kills_b = int(np.clip(int(round(objs[0])), 0, 7))
    if cell2 == "ticks":
        # speed niches once survival is solved: fast fighters keep their cells
        return kills_b, int(np.digitize(-objs[4], TICKS_EDGES))
    taken_b = int(np.digitize(-objs[1], TAKEN_EDGES))
    return kills_b, taken_b


def prune_cell(members, keep=3):
    """members: list of (program, objvec); keep non-dominated, then most spread."""
    if len(members) <= keep:
        return members
    objs = np.array([o for _p, o in members])
    fronts = _nd_fronts(objs)
    picked = []
    for f in fronts:
        for i in f:
            picked.append(members[i])
            if len(picked) >= keep:
                return picked
    return picked


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--gens", type=int, default=25)
    ap.add_argument("--pop", type=int, default=12, help="kids emitted per generation")
    ap.add_argument("--arenas", type=int, default=12)
    ap.add_argument("--nscen", type=int, default=12)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--patience", type=int, default=0,
                    help="early stop after N generations without hypervolume improvement")
    ap.add_argument("--resume", default=None,
                    help="warm-start the archive from a saved grid_final.json")
    ap.add_argument("--cell2", default="taken", choices=["taken", "ticks"],
                    help="second archive dimension: damage taken or speed")
    ap.add_argument("--hard", action="store_true",
                    help="harder eval scenarios: 4-9 mobs in a tighter ring")
    ap.add_argument("--seed_file", nargs="*", default=[],
                    help="extra program JSON files injected into the seed pool")
    ap.add_argument("--netherfrac", type=float, default=0.0,
                    help="fraction of eval scenarios drawn from the nether pool")
    ap.add_argument("--screen", type=int, default=0,
                    help="racing: screen kids on N scenarios before the full "
                         "eval set; bottom 40% never gets the full eval")
    ap.add_argument("--memetic", type=int, default=0,
                    help="every N gens inject constant-jittered variants of "
                         "the best cell elites (local parameter polish)")
    ap.add_argument("--out", default=str(Path(__file__).parent / "results_me"))
    args = ap.parse_args()

    rng = np.random.default_rng(args.seed)
    arenas = [f"me-{i}" for i in range(max(args.arenas, args.pop))]
    sim = Sim(arenas)
    print("[setup] arenas ...", flush=True)
    for name in arenas:
        sim.setup_arena(name)
    call("POST", "/v1/tick", {"mode": "sprint", "ticks": 40})
    call("POST", "/v1/tick", {"mode": "run"})

    def sample_eval_set(r, n):
        out = []
        for _ in range(n):
            nether = r.random() < args.netherfrac
            if args.hard:
                scen = random_scenario(
                    r, min_r=5.5, max_r=8.0, min_n=4, max_n=9,
                    pool=NETHER_MOB_POOL if nether else None)
            else:
                scen = random_scenario(
                    r, pool=NETHER_MOB_POOL if nether else None)
            terr = random_terrain(r, avoid_pts=[(dx, dz) for _t, dx, dz in scen],
                                  nether=nether)
            out.append((scen, terr))
        return out

    def eval_pop(programs, eval_set):
        """Mean 5-dim objective per program over eval_set. Programs are
        evaluated in arena-sized chunks (run_batch evaluates at most
        len(arenas) per call); every chunk sees the same eval_set."""
        objs = np.full((len(programs), len(METRIC_NAMES)), -1e9)
        for c0 in range(0, len(programs), len(arenas)):
            chunk = programs[c0:c0 + len(arenas)]
            acc = np.zeros((len(chunk), len(METRIC_NAMES)))
            n_done = 0
            for scen, terr in eval_set:
                params_batch = [{"ast": p} for p in chunk]
                try:
                    scores = sim.run_batch(params_batch, scen,
                                           terrains=[terr] * len(arenas),
                                           policy="ast")
                except RuntimeError as e:
                    print(f"  [warn] batch skipped: {e}", flush=True)
                    continue
                n_done += 1
                for i, s in enumerate(scores):
                    acc[i] += metrics_of(s)
            objs[c0:c0 + len(chunk)] = acc / max(n_done, 1)
        return objs

    def dead_prune(prog):
        """Rules after an unconditional one can never fire — truncate there."""
        for i, r in enumerate(prog["rules"][:-1]):
            w = r.get("when")
            if w is None or (isinstance(w, dict) and w.get("op") == "true"):
                prog = copy.deepcopy(prog)
                prog["rules"] = prog["rules"][:i + 1]
                return prog
        return prog

    def blend_acts(pa, pb, rng):
        """Numeric uniform crossover: child keeps pa's structure; each act's
        numeric constants are lerped against pb's aligned rule."""
        child = copy.deepcopy(pa)
        for ra, rb in zip(child["rules"], pb["rules"]):
            aa, ab = ra["act"], rb["act"]
            for k in set(aa) & set(ab):
                va, vb = aa[k], ab[k]
                if isinstance(va, (int, float)) and isinstance(vb, (int, float)):
                    v = float(rng.random()) * va + (1 - float(rng.random())) * vb
                    aa[k] = int(round(v)) if isinstance(va, int) else round(v, 2)
        return child

    def screen_rank(objs):
        """Batch-normalized objective sum — triage ranking only; the archive
        still stores raw objective vectors."""
        sd = objs.std(0) + 1e-9
        return ((objs - objs.mean(0)) / sd).sum(1)

    grid = {}  # (ki,ti) -> list[(program, obj)]

    def insert(prog, obj):
        c = cell_of(obj, args.cell2)
        members = grid.get(c, [])
        members.append((prog, obj))
        grid[c] = prune_cell(members)
        return c

    def occupied():
        return list(grid.keys())

    # ---- seed / resume archive ----
    if args.resume:
        saved = json.loads(Path(args.resume).read_text())
        seeds = [p for ms in saved.values() for p in ms]
        print(f"[resume] {len(seeds)} programs from {args.resume}", flush=True)
    else:
        seeds = [gp.baseline_program(), gp.template_ranged_first(),
                 gp.template_lowhp_first()] + [gp.rand_program(rng)
                                               for _ in range(args.pop - 3)]
    seeds += [json.loads(Path(f).read_text()) for f in args.seed_file]
    seeds = [gp.prune(p) for p in seeds]
    objs = eval_pop(seeds, sample_eval_set(np.random.default_rng(args.seed + 1),
                                           args.nscen))
    for p, o in zip(seeds, objs):
        insert(p, o)
    print(f"[seed] coverage={len(grid)}/{GRID[0]*GRID[1]}", flush=True)

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    hist_path = out / "history.jsonl"

    def save_grid():
        (out / "grid_final.json").write_text(json.dumps({
            f"{k[0]},{k[1]}": [p for p, _o in ms]
            for k, ms in grid.items()}, indent=2))

    best_hv, stale = -1.0, 0
    with open(hist_path, "a") as hf:
        for gen in range(args.gens):
            t0 = time.time()
            kids = []
            while len(kids) < args.pop:
                u = rng.random()
                if u < 0.12 or not occupied():
                    kids.append(dead_prune(gp.prune(gp.rand_program(rng))))
                    continue
                occ = occupied()
                if u < 0.28 and len(occ) >= 2:
                    # crossover elites from two different cells
                    ca, cb = occ[int(rng.integers(0, len(occ)))], occ[int(rng.integers(0, len(occ)))]
                    pa = grid[ca][int(rng.integers(0, len(grid[ca])))][0]
                    pb = grid[cb][int(rng.integers(0, len(grid[cb])))][0]
                    ka, kb = gp.crossover(pa, pb, rng)
                    kids += [dead_prune(gp.prune(ka)), dead_prune(gp.prune(kb))]
                elif u < 0.42 and len(occ) >= 2:
                    # numeric blend: pa's structure x pb's constants
                    ca, cb = occ[int(rng.integers(0, len(occ)))], occ[int(rng.integers(0, len(occ)))]
                    pa = grid[ca][int(rng.integers(0, len(grid[ca])))][0]
                    pb = grid[cb][int(rng.integers(0, len(grid[cb])))][0]
                    kids.append(dead_prune(gp.prune(blend_acts(pa, pb, rng))))
                else:
                    # mutate a random cell elite; cells holding fewer elites
                    # (sparser niches) get more emission — novelty pressure
                    weights = np.array([1.0 / len(grid[c]) for c in occ])
                    weights /= weights.sum()
                    ci = int(rng.choice(len(occ), p=weights))
                    cell = occ[ci]
                    parent = grid[cell][int(rng.integers(0, len(grid[cell])))][0]
                    kids.append(dead_prune(gp.prune(gp.mutate(parent, rng))))
            kids = kids[:args.pop]
            if args.memetic and gen % args.memetic == args.memetic - 1:
                # local polish: one constant-jittered child from the best
                # elite of each top cell (by kills+clear+survived)
                scored = sorted(
                    occupied(),
                    key=lambda c: max(o[0] + o[2] + o[3] for _p, o in grid[c]),
                    reverse=True)
                for c in scored[:args.pop // 3]:
                    elite = max(grid[c],
                                key=lambda po: po[1][0] + po[1][2] + po[1][3])[0]
                    kids.append(dead_prune(gp.prune(apply_genome(
                        elite, mutate_genome(genome_of(elite), rng)))))
            eval_set = sample_eval_set(
                np.random.default_rng(args.seed * 7919 + gen + 3000), args.nscen)
            if args.screen and len(kids) > 1 and args.nscen > args.screen:
                # racing: cheap screen on the first N scenarios (shared CRN
                # prefix), full eval only for the top 60%
                small = eval_pop(kids, eval_set[:args.screen])
                keep = np.argsort(-screen_rank(small))[
                    :max(1, int(np.ceil(len(kids) * 0.6)))]
                kids = [kids[i] for i in keep]
            kid_objs = eval_pop(kids, eval_set)
            for p, o in zip(kids, kid_objs):
                insert(p, o)

            union = [(p, o) for ms in grid.values() for p, o in ms]
            union_o = np.array([o for _p, o in union])
            front = _nd_fronts(union_o)[0]
            hv = hypervolume(union_o[front], HV_REF, HV_IDEAL)
            cov = len(grid) / (GRID[0] * GRID[1])
            rec = {"gen": gen, "coverage": round(cov, 3),
                   "cells": len(grid), "front_size": len(front),
                   "hv": round(hv, 2), "stale": stale,
                   "sec": round(time.time() - t0, 1)}
            hf.write(json.dumps(rec) + "\n")
            hf.flush()
            print(f"[gen {gen}] cells={len(grid)} cov={cov:.0%} "
                  f"front={len(front)} hv={hv:7.1f} ({rec['sec']}s)", flush=True)
            if hv > best_hv + 1.0:
                best_hv = hv
                stale = 0
            else:
                stale += 1
            if args.patience and stale >= args.patience:
                print(f"[stop] no HV improvement for {stale} gens "
                      f"(best={best_hv:.1f})", flush=True)
                break
            if stale and stale % 25 == 0:
                print(f"  [stale] {stale} gens since last HV gain", flush=True)
            if gen % 25 == 0:
                save_grid()

    # ---- final: union of cell elites re-evaluated on a fresh large set ----
    print("[final] re-evaluating map elites on fresh scenarios ...", flush=True)
    save_grid()
    union = [(p, o) for ms in grid.values() for p, o in ms]
    finalists = [p for p, _o in union]
    eval_rng = np.random.default_rng(555)
    big_set = sample_eval_set(eval_rng, max(args.nscen * 2, 24))
    final_objs = eval_pop(finalists, big_set)
    base_objs = eval_pop([gp.baseline_program()], big_set)[0]
    global_front = _nd_fronts(final_objs)[0]

    # re-bin on final evals for an honest coverage figure
    final_cells = {}
    for (p, _o), o in zip(union, final_objs):
        c = cell_of(o, args.cell2)
        final_cells.setdefault(c, []).append({"program": p,
                                              "metrics": np.round(o, 3).tolist()})
    report = {
        "metric_names": METRIC_NAMES,
        "baseline_ast_metrics": np.round(base_objs, 3).tolist(),
        "coverage_final": len(final_cells) / (GRID[0] * GRID[1]),
        "cells_occupied": len(final_cells),
        "pareto_front": [
            {"program": finalists[i], "metrics": np.round(final_objs[i], 3).tolist()}
            for i in global_front
        ],
        "grid": {f"{k[0]},{k[1]}": v for k, v in sorted(final_cells.items())},
        "eval_set_size": len(big_set),
        "hypervolume_final": round(hypervolume(final_objs[global_front], HV_REF, HV_IDEAL), 2),
    }
    (out / "final_report.json").write_text(json.dumps(report, indent=2))
    print(json.dumps({k: v for k, v in report.items() if k != "grid"},
                     indent=2), flush=True)


if __name__ == "__main__":
    main()
