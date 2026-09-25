#!/usr/bin/env python3
"""Evolutionary training of the `net` (MLP) policy — continuous-structure search.

The AST grammar's ceiling is discrete rules; this script searches MLP weight
space directly (same privileged obs -> same intent surface, all legality still
enforced by the executor). Two phases:

  1. --imitate  bootstrap: behavior-clone champion AST trajectories
     (traj_me11/*.jsonl) into the net with pure-numpy supervised training,
     giving ES a strong warm start instead of random weights.
  2. ES phase:  (mu+lambda) evolution — non-dominated sort + crowding over the
     same 5-dim objective vector, kids = parent blend + gaussian jitter.
     Same multi-objective eval as optimize_mapelites (no scalarization).

Usage:
  python3 optimize_net.py --imitate --gens 80 --patience 100 --out results_net1
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

# ------------------------------------------------------------- net layout --

K, F, G = 6, 14, 15
FRAME = G + K * F              # one encoded frame, 99 dims
STACK = 3                       # frame history fed to the net
INPUT = FRAME * STACK           # 297
HID = (96, 48)
OUTPUT = 2 + K + 4             # moveXY, target scores, atk/shield/sprint/jump = 10
SIZES = (INPUT,) + HID + (OUTPUT,)


def unpack(vec):
    """flat params -> list of (W[out][in], b[out])"""
    layers, i = [], 0
    for n_in, n_out in zip(SIZES[:-1], SIZES[1:]):
        w = vec[i:i + n_out * n_in].reshape(n_out, n_in)
        i += n_out * n_in
        b = vec[i:i + n_out]
        i += n_out
        layers.append((w, b))
    return layers


def pack(layers):
    return np.concatenate([np.concatenate([w.ravel(), b]) for w, b in layers])


def spec_of(vec):
    """params JSON consumed by NetPolicy.configure"""
    layers = []
    for (w, b), (n_in, n_out) in zip(unpack(vec), zip(SIZES[:-1], SIZES[1:])):
        layers.append({"shape": [int(n_in), int(n_out)],
                       "w": np.round(w, 5).tolist(),
                       "b": np.round(b, 5).tolist()})
    return {"layout": "v2", "layers": layers}


def forward(vec, x):
    a = x
    for li, (w, b) in enumerate(unpack(vec)):
        a = b + w @ a
        if li < len(SIZES) - 2:
            a = np.tanh(a)
    return a


# ------------------------------------------------------------ obs encoder --
# Must mirror NetPolicy.encode (Java) exactly.

RANGED = ("skeleton", "stray", "pillager", "witch", "blaze", "breeze")


def _is_ranged(t):
    return any(s in t for s in RANGED)


def hostiles_of(obs):
    hs = []
    for e in obs.get("entities", []):
        if "health" not in e:
            continue
        if e.get("hostile") or e.get("targetingPlayer"):
            hs.append(e)
    hs.sort(key=lambda e: e["dist"])
    return hs


def clip(v, lo, hi):
    return max(lo, min(hi, v))


def encode(obs):
    x = np.zeros(FRAME)
    p = obs["player"]
    hs = hostiles_of(obs)
    px, pz = p["pos"]["x"], p["pos"]["z"]

    hp_sum = fuse = lit = aiming = targeting = 0.0
    nd = 99.0
    for e in hs:
        hp_sum += e["health"]
        nd = min(nd, e["dist"])
        if "fuse" in e:
            fuse = max(fuse, e["fuse"])
            lit += e["fuse"] > 0.4
        aiming += bool(e.get("aiming"))
        targeting += bool(e.get("targetingPlayer"))

    x[0] = clip(p["health"] / 20.0, 0, 1)
    x[1] = clip(p.get("attackCooldown", 0), 0, 1)
    x[2] = clip(p.get("lastAttackedTicks", 0) / 60.0, 0, 1)
    x[3] = 1.0 if p.get("usingItem") else 0.0
    x[4] = clip(p.get("useTicks", 0) / 40.0, 0, 1)
    x[5] = clip(p.get("offhandPct", 1), 0, 1)
    x[6] = 1.0 if p.get("onGround") else 0.0
    x[7] = clip(p.get("food", 20) / 20.0, 0, 1)
    x[8] = clip(len(hs) / 9.0, 0, 1)
    x[9] = clip(nd / 20.0, 0, 1)
    x[10] = clip(hp_sum / 200.0, 0, 1)
    x[11] = clip(fuse, 0, 1)
    x[12] = clip(lit / 3.0, 0, 1)
    x[13] = clip(aiming / 4.0, 0, 1)
    x[14] = clip(targeting / 9.0, 0, 1)

    for k, e in enumerate(hs[:K]):
        o = G + k * F
        t = e["type"]
        x[o] = clip(e["dist"] / 20.0, 0, 1)
        x[o + 1] = clip((e["pos"]["x"] - px) / 20.0, -1, 1)
        x[o + 2] = clip((e["pos"]["z"] - pz) / 20.0, -1, 1)
        x[o + 3] = clip(e["pos"]["y"] - p["pos"]["y"], -1, 1) / 4.0
        x[o + 4] = clip(e["health"] / 30.0, 0, 1)
        x[o + 5] = 1.0 if e.get("targetingPlayer") else 0.0
        x[o + 6] = 1.0 if _is_ranged(t) else 0.0
        x[o + 7] = clip(e.get("fuse", 0), 0, 1)
        x[o + 8] = 1.0 if e.get("aiming") else 0.0
        x[o + 9] = clip(e.get("playerHits", 0) / 6.0, 0, 1)
        x[o + 10] = 1.0 if "creeper" in t else 0.0
        x[o + 11] = 1.0 if "wither" in t else 0.0
        x[o + 12] = 1.0 if "blaze" in t else 0.0
        x[o + 13] = clip(e.get("speed", 0) / 5.0, 0, 1)
    return x


def stack_encode(hist):
    """hist: list of up-to-STACK encodes (oldest first). Concatenates into the
    net input, padding the front by repeating the oldest frame — mirrors
    NetPolicy's deque."""
    h = list(hist)[-STACK:]
    while len(h) < STACK:
        h.insert(0, h[0])
    return np.concatenate(h)


# ------------------------------------------------------------- imitation --

def imitate(traj_dir, seed=0, epochs=60, lr=0.01):
    """Behavior-clone champion AST trajectories into an MLP warm start.

    Targets: moveXY (regression, tanh out), target slot (CE over K), and four
    binary heads (atk/shield/sprint/jump, BCE). Pure numpy — keeps the forward
    pass structurally identical to the Java runtime.
    """
    rng = np.random.default_rng(seed)
    X, Ym, Yt, Yb = [], [], [], []
    n_ticks = 0
    for f in sorted(Path(traj_dir).glob("*.jsonl")):
        hist = []
        for line in open(f):
            r = json.loads(line)
            if r.get("type") != "tick" or "intent" not in r:
                continue
            obs, it = r["obs"], r["intent"]
            hs = hostiles_of(obs)
            if not hs:
                continue
            frame = encode(obs)
            hist.append(frame)
            x = stack_encode(hist)
            n_ticks += 1
            X.append(x)
            md = it.get("moveDir") or [0.0, 0.0]
            Ym.append([clip(md[0], -1, 1), clip(md[1], -1, 1)])
            tgt = np.zeros(K)
            look = it.get("lookEntity")
            slot = 0
            for k, e in enumerate(hs[:K]):
                if e.get("id") == look:
                    slot = k
                    break
            tgt[slot] = 1.0
            Yt.append(tgt)
            Yb.append([1.0 if it.get("attack") else 0.0,
                       1.0 if it.get("useHand") == "off" else 0.0,
                       1.0 if it.get("sprint") else 0.0,
                       1.0 if it.get("jump") else 0.0])
    X = np.array(X)
    Ym = np.array(Ym)
    Yt = np.array(Yt)
    Yb = np.array(Yb)
    print(f"[imitate] {len(X)} ticks from {traj_dir}", flush=True)

    # init: small random + zero-ish output layer
    layers = []
    for n_in, n_out in zip(SIZES[:-1], SIZES[1:]):
        scale = 0.15 if n_out == OUTPUT else np.sqrt(2.0 / n_in)
        layers.append([rng.standard_normal((n_out, n_in)) * scale,
                       np.zeros(n_out)])
    vec = pack([(w, b) for w, b in layers])
    W = unpack(vec)

    def fwd(xb):
        a = [xb]
        for li, (w, b) in enumerate(W):
            a.append(a[-1] @ w.T + b)
            if li < len(W) - 1:
                a[-1] = np.tanh(a[-1])
        return a

    n = len(X)
    bs = 2048
    for ep in range(epochs):
        perm = rng.permutation(n)
        tot = 0.0
        for s in range(0, n, bs):
            idx = perm[s:s + bs]
            a = fwd(X[idx])
            y = a[-1]
            m = len(idx)
            dm = y[:, :2] - Ym[idx]                          # mse on move
            p = np.exp(y[:, 2:2 + K] - y[:, 2:2 + K].max(1, keepdims=True))
            p /= p.sum(1, keepdims=True)
            dt = (p - Yt[idx]) / K                           # softmax CE
            sig = 1.0 / (1.0 + np.exp(-y[:, 2 + K:]))
            db = sig - Yb[idx]                               # bce on heads
            d = np.concatenate([dm, dt, db], axis=1) / m
            tot += float(np.abs(d).sum())
            for li in range(len(W) - 1, -1, -1):
                w, b = W[li]
                if li > 0:
                    ga = d @ w * (1.0 - a[li] ** 2)          # tanh'
                W[li] = (w - lr * (d.T @ a[li]),
                         b - lr * d.sum(0))
                if li > 0:
                    d = ga
        if ep % 15 == 0 or ep == epochs - 1:
            print(f"  [imitate] epoch {ep} mean|grad|={tot / n:.4f}", flush=True)
    return pack([(w, b) for w, b in W])


# -------------------------------------------------------------------- es --

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--gens", type=int, default=60)
    ap.add_argument("--pop", type=int, default=12)
    ap.add_argument("--arenas", type=int, default=12)
    ap.add_argument("--nscen", type=int, default=12)
    ap.add_argument("--neval", type=int, default=24)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--patience", type=int, default=0)
    ap.add_argument("--netherfrac", type=float, default=0.3)
    ap.add_argument("--hard", action="store_true", default=True)
    ap.add_argument("--sigma", type=float, default=0.08,
                    help="gaussian jitter sd on weights for ES kids")
    ap.add_argument("--imitate", default=None,
                    help="behavior-clone this trajectory dir first")
    ap.add_argument("--init", default=None,
                    help="init weights from a saved flat .npy vector")
    ap.add_argument("--resume", default=None,
                    help="warm-start parents from a saved pop_final.json")
    ap.add_argument("--champs", nargs="*", default=[],
                    help="AST champ JSONs re-evaluated on the final set")
    ap.add_argument("--out", default=str(Path(__file__).parent / "results_net"))
    args = ap.parse_args()

    rng = np.random.default_rng(args.seed)
    arenas = [f"net-{i}" for i in range(max(args.arenas, args.pop))]
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
            scen = random_scenario(
                r, min_r=5.5, max_r=8.0, min_n=4, max_n=9,
                pool=NETHER_MOB_POOL if nether else None) if args.hard \
                else random_scenario(r, pool=NETHER_MOB_POOL if nether else None)
            terr = random_terrain(r, avoid_pts=[(dx, dz) for _t, dx, dz in scen],
                                  nether=nether)
            out.append((scen, terr))
        return out

    def eval_pop(vecs, eval_set):
        objs = np.full((len(vecs), len(METRIC_NAMES)), -1e9)
        for c0 in range(0, len(vecs), len(arenas)):
            chunk = vecs[c0:c0 + len(arenas)]
            acc = np.zeros((len(chunk), len(METRIC_NAMES)))
            n_done = 0
            for scen, terr in eval_set:
                params_batch = [{"net": spec_of(v)} for v in chunk]
                try:
                    scores = sim.run_batch(params_batch, scen,
                                           terrains=[terr] * len(arenas),
                                           policy="net")
                except RuntimeError as e:
                    print(f"  [warn] batch skipped: {e}", flush=True)
                    continue
                n_done += 1
                for i, s in enumerate(scores):
                    acc[i] += metrics_of(s)
            objs[c0:c0 + len(chunk)] = acc / max(n_done, 1)
        return objs

    # ---- initial parent pool ----
    dim = int(sum(o * (i + 1) for i, o in zip(SIZES[:-1], SIZES[1:])))
    print(f"[init] weight dim={dim}", flush=True)
    if args.resume:
        parents = [np.array(v) for v in json.loads(Path(args.resume).read_text())]
        print(f"[resume] {len(parents)} parents", flush=True)
    elif args.init:
        base = np.load(args.init)
        parents = [base + rng.standard_normal(dim) * args.sigma * 0.5
                   for _ in range(args.pop)]
    elif args.imitate:
        base = imitate(args.imitate, seed=args.seed)
        np.save(Path(args.out).parent / (Path(args.out).name + "_bc.npy"), base)
        parents = [base + rng.standard_normal(dim) * args.sigma * 0.5
                   for _ in range(args.pop)]
    else:
        parents = [rng.standard_normal(dim) * 0.1 for _ in range(args.pop)]
    while len(parents) < args.pop:
        parents.append(parents[len(parents) % max(1, len(parents))]
                       + rng.standard_normal(dim) * args.sigma * 0.5)
    parents = parents[:args.pop]

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    hist_path = out / "history.jsonl"

    best_hv, stale = -1.0, 0

    def select(px, po):
        """non-dominated rank + crowding -> survivor indices (fixed mu)"""
        fronts = _nd_fronts(po)
        rank = np.empty(len(px), int)
        crowd = np.zeros(len(px))
        for fi, fr in enumerate(fronts):
            for i in fr:
                rank[i] = fi
            cd = _crowding(np.array(fr), po)
            for i, c in zip(fr, cd):
                crowd[i] = c
        order = np.lexsort((-crowd, rank))
        return order[:args.pop]

    with open(hist_path, "a") as hf:
        for gen in range(args.gens):
            t0 = time.time()
            kids = []
            for _k in range(args.pop):
                i, j = rng.integers(0, len(parents), 2)
                pa, pb = parents[i], parents[j]
                u = rng.random(dim)
                child = np.where(u < 0.5, pa, pb) \
                    if rng.random() < 0.5 else (pa + pb) / 2.0
                mask = rng.random(dim) < 0.35  # sparse jitter: most weights kept
                child = child + mask * rng.standard_normal(dim) * args.sigma
                kids.append(child)
            eval_set = sample_eval_set(
                np.random.default_rng(args.seed * 7919 + gen + 3000), args.nscen)
            # parents re-scored on the same fresh draw as kids: no frozen lucky
            # scores, and CRN pairing makes the comparison fair
            parent_objs = eval_pop(parents, eval_set)
            kid_objs = eval_pop(kids, eval_set)

            pool_x = parents + kids
            pool_o = np.vstack([parent_objs, kid_objs])
            keep = select(pool_x, pool_o)
            parents = [pool_x[i] for i in keep]
            parent_objs = pool_o[keep]

            front = _nd_fronts(parent_objs)[0]
            hv = hypervolume(parent_objs[front], HV_REF, HV_IDEAL)
            rec = {"gen": gen, "front_size": len(front), "hv": round(hv, 2),
                   "stale": stale, "sec": round(time.time() - t0, 1)}
            hf.write(json.dumps(rec) + "\n")
            hf.flush()
            print(f"[gen {gen}] front={len(front)} hv={hv:7.1f} ({rec['sec']}s)",
                  flush=True)
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
                (out / "pop_final.json").write_text(json.dumps(
                    [np.round(v, 5).tolist() for v in parents]))
        (out / "pop_final.json").write_text(json.dumps(
            [np.round(v, 5).tolist() for v in parents]))

    # ---- final: parents' front + champions on a fresh large set ----
    print("[final] re-evaluating front on fresh scenarios ...", flush=True)
    eval_rng = np.random.default_rng(555)
    big_set = sample_eval_set(eval_rng, max(args.nscen * 2, args.neval))
    front = _nd_fronts(parent_objs)[0]
    finalists = [parents[i] for i in front]
    final_objs = eval_pop(finalists, big_set)

    # baseline + AST champions through the same set (policy ast)
    def eval_ast(progs):
        objs = np.zeros((len(progs), len(METRIC_NAMES)))
        for c0 in range(0, len(progs), len(arenas)):
            chunk = progs[c0:c0 + len(arenas)]
            acc = np.zeros((len(chunk), len(METRIC_NAMES)))
            n_done = 0
            for scen, terr in big_set:
                try:
                    scores = sim.run_batch([{"ast": p} for p in chunk], scen,
                                           terrains=[terr] * len(arenas),
                                           policy="ast")
                except RuntimeError:
                    continue
                n_done += 1
                for i, s in enumerate(scores):
                    acc[i] += metrics_of(s)
            objs[c0:c0 + len(chunk)] = acc / max(n_done, 1)
        return objs

    ast_progs = [gp.baseline_program()]
    for f in args.champs:
        ast_progs.append(json.loads(Path(f).read_text()))
    ast_objs = eval_ast(ast_progs)

    report = {
        "metric_names": METRIC_NAMES,
        "baseline_ast_metrics": np.round(ast_objs[0], 3).tolist(),
        "ast_champ_metrics": {
            Path(f).stem: np.round(ast_objs[i + 1], 3).tolist()
            for i, f in enumerate(args.champs)},
        "pareto_front": [
            {"net_spec": spec_of(finalists[i]),
             "metrics": np.round(final_objs[i], 3).tolist()}
            for i in _nd_fronts(final_objs)[0]
        ],
        "eval_set_size": len(big_set),
        "hypervolume_final": round(
            hypervolume(final_objs[_nd_fronts(final_objs)[0]],
                        HV_REF, HV_IDEAL), 2),
    }
    (out / "final_report.json").write_text(json.dumps(report, indent=2))
    np.save(out / "front_weights.npy", np.array(finalists))
    print(json.dumps({k: v for k, v in report.items()
                      if k != "pareto_front"}, indent=2), flush=True)
    print(f"[final] front members: {len(report['pareto_front'])}", flush=True)


if __name__ == "__main__":
    main()
