#!/usr/bin/env python3
"""GRPO trainer over server-side sampled rollouts (egt policies).

vs train_rl.py (PPO + /v1/step external loop):
- Rollouts run in SPRINT mode: each arena's NetPolicy samples its action
  server-side every tick (params {"net": spec, "sample": true}) and the
  episode JSONL records the exact sampled action + its Java-side logp.
  No per-tick HTTP — the world ticks in bursts at full speed.
- No critic: episode scalar returns are group-normalized within a
  same-scenario group (GRPO); the group baseline also absorbs scenario
  difficulty (nether >> melee), which the value net always struggled with.
- Update = PPO clip on logp ratios. Python replays each episode's tick
  stream to recompute logp under the CURRENT weights: the sampled action
  recorded at tick i was decided on obs_{max(0,i-3)} (ActionProfile
  obsDelayTicks=3, obsHistory keeps delay+1), and the GRU hidden is
  reproduced by carrying h through that replay (stored-h approximation,
  same as train_rl --egt).
"""
import argparse
import json
import sys
import time
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn
from torch.distributions import Bernoulli, Categorical, Normal

sys.path.insert(0, str(Path(__file__).parent))
from optimize_cmaes import (  # noqa: E402
    call, Sim, metrics_of, METRIC_NAMES, episode_score, RUN_DIR,
    random_scenario, random_terrain, NETHER_MOB_POOL, MAX_TICKS)
import policy_egt as PE  # noqa: E402

FAM_POOLS = {"standard": None,
             "ranged": [("skeleton", 0.65), ("zombie", 0.20),
                        ("creeper", 0.15)],
             "melee": [("zombie", 0.55), ("spider", 0.30),
                       ("creeper", 0.15)],
             "nether": NETHER_MOB_POOL}
OBS_DELAY = 3  # ActionProfile.obsDelayTicks


def ep_return(score):
    """Scalar episode return — same shaping as train_rl.rew() aggregated."""
    if score is None:
        return -50.0
    r = (10.0 * score.get("kills", 0)
         + 0.1 * score.get("damageDealt", 0.0)
         - 0.3 * score.get("damageTaken", 0.0)
         - 0.05 * score.get("ticks", 0))
    oc = score.get("outcome")
    if oc == "ALL_MOBS_CLEARED":
        r += 6.0
    elif oc == "PLAYER_DIED":
        r -= 50.0
    elif oc == "TIMEOUT":
        r -= 15.0
    return r


def parse_episode(path):
    """-> (obs list, sampled list, score dict) or None."""
    obs, sampled, score = [], [], None
    try:
        for line in open(path):
            try:
                d = json.loads(line)
            except json.JSONDecodeError:
                break
            t = d.get("type")
            if t == "tick":
                obs.append(d["obs"])
                sampled.append(d.get("sampled"))
            elif t == "end":
                score = d.get("score")
    except FileNotFoundError:
        return None
    if score is None:
        return None
    return obs, sampled, score


def run_batch_train(sim, params, scenarios, terrains):
    """run_batch variant: keeps episode JSONL, returns (scores, paths)."""
    logs = []
    for name, prm, scen, terr in zip(sim.arenas, params, scenarios, terrains):
        sim.reset_and_spawn(name, scen, None, terr)
    try:
        call("POST", "/v1/tick", {"mode": "sprint", "ticks": 10})
    except RuntimeError:
        pass
    for name, prm in zip(sim.arenas, params):
        body = {"arena": name, "policy": "net",
                "maxTicks": MAX_TICKS, "obsRadius": 20.0}
        if prm:
            body["params"] = prm
        ep = call("POST", "/v1/episode", body)
        logs.append(ep["log"])
    try:
        call("POST", "/v1/tick", {"mode": "sprint", "ticks": MAX_TICKS + 50})
    except RuntimeError as e:
        print(f"  [warn] sprint call: {e}", flush=True)
    deadline = time.time() + 900
    while time.time() < deadline:
        st = call("GET", "/v1/status")
        if st.get("gate") == "RUN" and len(st.get("episodes", [])) == 0:
            break
        if st.get("gate") == "RUN":
            try:
                call("POST", "/v1/tick",
                     {"mode": "sprint", "ticks": MAX_TICKS + 50})
            except RuntimeError:
                pass
        time.sleep(0.3)
    scores, paths = [], []
    for lg in logs:
        scores.append(episode_score(lg))
        p = Path(lg)
        paths.append(p if p.is_absolute() else RUN_DIR / p)
    return scores, paths


def replay_episode(pol, obs_list, sampled_list, device="cpu"):
    """Recompute the decide() stream Python-side.

    tick i's sampled action was decided on obs[max(0, i-OBS_DELAY)] with the
    GRU carried across decide calls (obs_0 is decided on OBS_DELAY+1 times —
    the warmup duplication is real, replicate it).

    Returns per-tick dict entries {g,ef,tid,n,h, act, old_logp} where act =
    (mv[2], tgt, flags[4]) as recorded. h is the GRU state BEFORE this tick's
    update (detached, stored once — updated weights reuse it, PPO-style).
    """
    out = []
    h = torch.zeros(1, PE.D_H)
    with torch.no_grad():
        for i, sm in enumerate(sampled_list):
            if sm is None:
                continue
            j = max(0, i - OBS_DELAY)
            g, ef, tid, n = PE.encode_egt(obs_list[j])
            out.append({"g": g, "ef": ef, "tid": tid, "n": n,
                        "h": h.squeeze(0).numpy().copy(),
                        "mv": np.array(sm["mv"], dtype=np.float32),
                        "tgt": int(sm.get("tgt", -1)),
                        "flags": np.array(sm.get("flags") or [0]*4,
                                          dtype=np.float32),
                        "old_logp": float(sm["logp"])})
            xg = torch.tensor(g[None], dtype=torch.float32)
            xe = torch.tensor(ef[None], dtype=torch.float32)
            xt = torch.tensor(tid[None], dtype=torch.long)
            xn = torch.tensor([n])
            _, _, h = pol(xg, xe, xt, xn, h)
    return out


def logp_of(pol, g, ef, tid, n, h, mv, tgt, flags):
    """logp of recorded action under CURRENT weights + head entropies."""
    y, _, _ = pol(g, ef, tid, n, h)
    mean = y[:, :2]
    std = pol.log_std.exp().clamp(1e-4, 2.0)
    mv_d = Normal(mean, std)
    mv_lp = mv_d.log_prob(mv).sum(-1)
    mv_ent = mv_d.entropy().sum(-1)

    scores = y[:, 2:2 + PE.K]
    mask = torch.arange(PE.K).unsqueeze(0) >= n.unsqueeze(1)
    scores = scores.masked_fill(mask, -1e9)
    tgt_d = Categorical(logits=scores)
    has_tgt = tgt >= 0
    tgt_lp = torch.where(has_tgt, tgt_d.log_prob(tgt.clamp(min=0)),
                         torch.zeros(len(tgt)))
    tgt_ent = torch.where(
        n > 0, tgt_d.entropy(), torch.zeros(len(tgt)))

    fl = y[:, 2 + PE.K:2 + PE.K + 4]
    fl_d = Bernoulli(logits=fl)
    fl_lp = fl_d.log_prob(flags).sum(-1)
    fl_ent = fl_d.entropy().sum(-1)

    return mv_lp + tgt_lp + fl_lp, mv_ent + tgt_ent + fl_ent, fl


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--envs", type=int, default=12)
    ap.add_argument("--groups", type=int, default=3,
                    help="same-scenario groups per iter; G=envs/groups "
                         "rollouts per group form the GRPO baseline")
    ap.add_argument("--iters", type=int, default=300)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--lr", type=float, default=1e-4)
    ap.add_argument("--clip", type=float, default=0.2)
    ap.add_argument("--ent", type=float, default=0.003)
    ap.add_argument("--margin", type=float, default=0.15)
    ap.add_argument("--marginw", type=float, default=0.02)
    ap.add_argument("--ema", type=float, default=0.995)
    ap.add_argument("--epochs", type=int, default=4)
    ap.add_argument("--mb", type=int, default=512)
    ap.add_argument("--initempty", default=None)
    ap.add_argument("--evalevery", type=int, default=10)
    ap.add_argument("--neval", type=int, default=12)
    ap.add_argument("--familymix", action="store_true")
    ap.add_argument("--out", default=str(Path(__file__).parent / "results_grpo"))
    args = ap.parse_args()

    assert args.envs % args.groups == 0
    G = args.envs // args.groups
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    torch.manual_seed(args.seed)
    rng = np.random.default_rng(args.seed)
    eval_rng = np.random.default_rng(999)

    arenas = [f"gr{i}" for i in range(args.envs)]
    sim = Sim(arenas)
    print("[setup] arenas ...", flush=True)
    call("POST", "/v1/tick", {"mode": "freeze"})
    for name in arenas:
        sim.setup_arena(name)
    call("POST", "/v1/step", {"ticks": 10})

    pol = PE.PolicyEGT()
    if args.initempty:
        PE.load_egt(pol, np.load(args.initempty))
        print(f"[init] egt load from {args.initempty}", flush=True)
    opt = torch.optim.Adam(pol.parameters(), lr=args.lr)
    ema_flat = pol.flat() if args.ema > 0 else None

    def sample_set(r, n):
        s = []
        for _ in range(n):
            fam = list(FAM_POOLS)[r.integers(len(FAM_POOLS))] \
                if args.familymix else "standard"
            pool = FAM_POOLS[fam]
            nether = fam == "nether"
            scen = random_scenario(r, min_r=5.5, max_r=8.0, min_n=4, max_n=9,
                                   pool=pool)
            terr = random_terrain(
                r, avoid_pts=[(dx, dz) for _t, dx, dz in scen], nether=nether)
            s.append((fam, scen, terr))
        return s

    hist = open(out / "history.jsonl", "a")
    best_key = None

    for it in range(1, args.iters + 1):
        t0 = time.time()
        cur_flat = pol.flat()
        spec = PE.spec_of_egt(cur_flat)
        # Java sees the 5-decimal-rounded spec — replay under the same
        # rounded weights so hidden states/logits match what generated the
        # actions (unrounded replay drifts ~0.1 nats over an episode).
        pol_shadow = PE.PolicyEGT()
        PE.load_egt(pol_shadow, np.round(cur_flat, 5))
        pol_shadow.eval()
        params = [{"net": spec, "sample": True} for _ in arenas]
        sets = [sample_set(rng, 1)[0] for _ in range(args.groups)]
        scenarios = []
        terrains = []
        for g in range(args.groups):
            for _ in range(G):
                scenarios.append(sets[g][1])
                terrains.append(sets[g][2])

        try:
            scores, paths = run_batch_train(sim, params, scenarios, terrains)
        except RuntimeError as e:
            # transient spawn/terrain failures — drop the iteration
            print(f"[{it}] batch failed, skipping: {e}", flush=True)
            continue

        # ---- parse + replay episodes --------------------------------------
        all_ticks = []          # per-tick replay records
        ep_rets = np.zeros(args.envs)
        outcomes = {}
        n_bad_lp = 0
        for ai, (sc, pth) in enumerate(zip(scores, paths)):
            gidx = ai // G
            parsed = parse_episode(pth)
            if parsed is None:
                ep_rets[ai] = ep_return(sc) if sc else -50.0
                continue
            obs_l, smp_l, score = parsed
            if sc is not None:
                score = sc
            ep_rets[ai] = ep_return(score)
            outcomes[score.get("outcome", "?")] = \
                outcomes.get(score.get("outcome", "?"), 0) + 1
            recs = replay_episode(pol_shadow, obs_l, smp_l)
            for r in recs:
                r["group"] = gidx
                r["ep"] = ai
            all_ticks.extend(recs)
            try:
                pth.unlink()
            except OSError:
                pass

        # iter-1 sanity: Python-side logp under the just-deployed weights
        # must match the Java-side logp recorded during sampling (drift >~1e-2
        # means the replayed obs/h stream diverges from what Java saw).
        if it == 1 and all_ticks:
            with torch.no_grad():
                lp_py = []
                for r in all_ticks[:400]:
                    y, _, _ = pol_shadow(
                        torch.tensor(r["g"][None]),
                        torch.tensor(r["ef"][None]),
                        torch.tensor(r["tid"][None], dtype=torch.long),
                        torch.tensor([r["n"]]),
                        torch.tensor(r["h"][None]))
                    m = y[0, :2]
                    sd = pol.log_std.exp()
                    lp = Normal(m, sd).log_prob(
                        torch.tensor(r["mv"])).sum().item()
                    sc = y[0, 2:2 + PE.K].clone()
                    sc[r["n"]:] = -1e9
                    if r["tgt"] >= 0 and r["n"] > 0:
                        lp += Categorical(logits=sc).log_prob(
                            torch.tensor(r["tgt"])).item()
                    flg = y[0, 2 + PE.K:2 + PE.K + 4]
                    lp += Bernoulli(logits=flg).log_prob(
                        torch.tensor(r["flags"])).sum().item()
                    lp_py.append(lp)
                lp_java = [r["old_logp"] for r in all_ticks[:400]]
                d = np.abs(np.array(lp_py) - np.array(lp_java))
                print(f"[sanity] logp |py-java| mean={d.mean():.4f} "
                      f"max={d.max():.4f} (expect ~1e-3)", flush=True)

        # group-normalized sequence advantages (GRPO)
        adv = np.zeros(args.envs)
        for g in range(args.groups):
            idx = [ai for ai in range(args.envs) if ai // G == g]
            rs = ep_rets[idx]
            mu, sd = rs.mean(), rs.std() + 1e-4
            for ai in idx:
                adv[ai] = (ep_rets[ai] - mu) / sd
        for r in all_ticks:
            r["adv"] = adv[r["ep"]]

        # ---- update --------------------------------------------------------
        pl = el = 0.0
        nb = 0
        if all_ticks:
            XG = torch.tensor(np.stack([r["g"] for r in all_ticks]))
            XE = torch.tensor(np.stack([r["ef"] for r in all_ticks]))
            XT = torch.tensor(np.stack([r["tid"] for r in all_ticks]),
                              dtype=torch.long)
            XN = torch.tensor([r["n"] for r in all_ticks])
            XH = torch.tensor(np.stack([r["h"] for r in all_ticks]))
            MV = torch.tensor(np.stack([r["mv"] for r in all_ticks]))
            TG = torch.tensor([r["tgt"] for r in all_ticks])
            FG = torch.tensor(np.stack([r["flags"] for r in all_ticks]))
            OLP = torch.tensor([r["old_logp"] for r in all_ticks],
                               dtype=torch.float32)
            ADV = torch.tensor([r["adv"] for r in all_ticks],
                               dtype=torch.float32)
            idx_all = np.arange(len(all_ticks))
            for _ep in range(args.epochs):
                rng.shuffle(idx_all)
                for c0 in range(0, len(idx_all), args.mb):
                    b = torch.tensor(idx_all[c0:c0 + args.mb])
                    lp, ent, fl = logp_of(pol, XG[b], XE[b], XT[b], XN[b],
                                          XH[b], MV[b], TG[b], FG[b])
                    ratio = torch.exp((lp - OLP[b]).clamp(-8, 8))
                    s1 = ratio * ADV[b]
                    s2 = torch.clamp(ratio, 1 - args.clip,
                                     1 + args.clip) * ADV[b]
                    pol_loss = -torch.min(s1, s2).mean()
                    loss = pol_loss - args.ent * ent.mean()
                    if args.margin > 0:
                        loss = loss + args.marginw * torch.relu(
                            args.margin - fl.abs()).mean()
                    opt.zero_grad()
                    loss.backward()
                    nn.utils.clip_grad_norm_(pol.parameters(), 0.5)
                    opt.step()
                    pl += pol_loss.item(); el += ent.mean().item(); nb += 1
            pl /= max(nb, 1); el /= max(nb, 1)

        if ema_flat is not None:
            ema_flat = args.ema * ema_flat + (1 - args.ema) * pol.flat()

        line = {"iter": it, "ep_rets": ep_rets.tolist(),
                "adv": adv.tolist(), "ticks": len(all_ticks),
                "outcomes": outcomes, "pol_loss": pl, "entropy": el,
                "dt": round(time.time() - t0, 1)}
        hist.write(json.dumps(line) + "\n"); hist.flush()
        print(f"[{it}] ticks={len(all_ticks)} rets={np.round(ep_rets,1).tolist()}"
              f" outcomes={outcomes} pl={pl:.4f} ent={el:.2f}"
              f" dt={time.time() - t0:.1f}s", flush=True)

        np.save(out / "pol_latest.npy", pol.flat())

        # ---- deterministic eval of the EMA/current weights -----------------
        if it % args.evalevery == 0:
            eval_flat = ema_flat if ema_flat is not None else pol.flat()
            es = sample_set(eval_rng, args.neval)
            eparams = [{"net": PE.spec_of_egt(eval_flat)} for _ in arenas]
            acc = np.zeros(len(METRIC_NAMES))
            n_done = 0
            for scen_fam, scen, terr in es:
                try:
                    sc2, _lp = run_batch_train(
                        sim, eparams, [scen] * len(arenas),
                        [terr] * len(arenas))
                    for p2 in _lp:
                        try:
                            p2.unlink()
                        except OSError:
                            pass
                    n_done += 1
                    for s2 in sc2:
                        acc += metrics_of(s2)
                except RuntimeError as e:
                    print(f"  [warn] eval batch: {e}", flush=True)
            vec = acc / max(n_done * len(arenas), 1)
            deg = vec[0] < 0.5 and vec[3] > 0.95
            hist.write(json.dumps({"iter": it, "eval_vec": vec.tolist(),
                                   "degenerate_suspect": bool(deg)}) + "\n")
            hist.flush()
            print(f"[eval {it}] {np.round(vec, 3).tolist()}"
                  + ("  [DEGENERATE?]" if deg else ""), flush=True)
            key = (round(float(vec[0]), 3), round(float(vec[2]), 3),
                   round(float(vec[3]), 3), round(float(vec[4]), 3))
            if not deg and (best_key is None or key > best_key):
                best_key = key
                np.save(out / "pol_best.npy", eval_flat)
                print(f"  [best] {key}", flush=True)

    print("[done]", flush=True)


if __name__ == "__main__":
    main()
