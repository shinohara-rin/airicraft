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
import math
import os
import sys
import threading
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
TRAJ_GLOBS = [str(Path(__file__).parent / "traj_v2/*.jsonl"),
              str(Path(__file__).parent / "traj_me11/*.jsonl")]


# ---------------- GAIL: learned per-tick reward ---------------------------
# Discriminator D(state_feats, act_feats) -> logit; reward = clipped logit
# (log-odds). Expert = champ AST trajectories; negatives = each iter's own
# rollouts. The state/action pair for D is the SAME-tick (obs_i, action_i)
# — the action actually applied at tick i — unlike the policy-side pairing
# (obs_{i-3}, action_i) needed for logp.

SF_DIM = PE.GE + PE.EF + 1        # g | mean live ef | n/K
AF_DIM = 2 + (PE.K + 1) + 4       # mv | tgt onehot(+none) | flags


def state_feats(obs):
    g, ef, tid, n = PE.encode_egt(obs)
    live = ef[:n] if n > 0 else np.zeros((1, PE.EF), dtype=np.float32)
    return np.concatenate([g, live.mean(0), [n / PE.K]]) \
        .astype(np.float32)


def act_feats(mv, tgt, flags):
    oh = np.zeros(PE.K + 1, dtype=np.float32)
    oh[tgt if 0 <= tgt < PE.K else PE.K] = 1.0
    return np.concatenate([mv, oh, flags]).astype(np.float32)


class GailD(nn.Module):
    def __init__(self):
        super().__init__()
        self.net = nn.Sequential(nn.Linear(SF_DIM + AF_DIM, 64), nn.ReLU(),
                                 nn.Linear(64, 64), nn.ReLU(),
                                 nn.Linear(64, 1))

    def forward(self, sf, af):
        return self.net(torch.cat([sf, af], -1)).squeeze(-1)


def load_expert_pairs():
    """(sf, af) positive pairs from champ AST trajectory JSONLs."""
    import glob
    sfs, afs = [], []
    n_ep = 0
    for pat in TRAJ_GLOBS:
        for fp in sorted(glob.glob(pat)):
            obs_l, it_l = [], []
            try:
                with open(fp) as f:
                    for line in f:
                        try:
                            d = json.loads(line)
                        except json.JSONDecodeError:
                            break
                        if d.get("type") == "tick":
                            obs_l.append(d["obs"])
                            it_l.append(d.get("intent") or {})
            except FileNotFoundError:
                continue
            n_ep += 1
            for obs, it in zip(obs_l, it_l):
                hs = PE.hostiles_of(obs)
                ent = it.get("lookEntity")
                tgt = -1
                if ent is not None:
                    for k, e in enumerate(hs[:PE.K]):
                        if e.get("id") == ent:
                            tgt = k
                            break
                mdir = np.array(it.get("moveDir") or [0.0, 0.0],
                                dtype=np.float32)
                nm = np.linalg.norm(mdir)
                if nm > 1e-6:
                    mdir = mdir / nm
                flags = np.array([
                    1.0 if it.get("attack") else 0.0,
                    1.0 if it.get("useHand") == "off" else 0.0,
                    1.0 if it.get("sprint") else 0.0,
                    1.0 if it.get("jump") else 0.0], dtype=np.float32)
                sfs.append(state_feats(obs))
                afs.append(act_feats(mdir, tgt, flags))
    print(f"[gail] expert pairs: {len(sfs)} ticks from {n_ep} episodes",
          flush=True)
    return np.stack(sfs), np.stack(afs)


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


def run_batch_train(sim, params, scenarios, terrains, run_dir=RUN_DIR):
    """run_batch variant: keeps episode JSONL, returns (scores, paths).

    `run_dir` is the instance's server run directory used to resolve the
    relative episode-log paths the API returns (each fleet instance writes
    under its own run dir).
    """
    logs = []
    for name, prm, scen, terr in zip(sim.arenas, params, scenarios, terrains):
        sim.reset_and_spawn(name, scen, None, terr)
    try:
        sim.call("POST", "/v1/tick", {"mode": "sprint", "ticks": 10})
    except RuntimeError:
        pass
    for name, prm in zip(sim.arenas, params):
        body = {"arena": name, "policy": "net",
                "maxTicks": MAX_TICKS, "obsRadius": 20.0}
        if prm:
            body["params"] = prm
        ep = sim.call("POST", "/v1/episode", body)
        logs.append(ep["log"])
    try:
        sim.call("POST", "/v1/tick", {"mode": "sprint", "ticks": MAX_TICKS + 50})
    except RuntimeError as e:
        print(f"  [warn] sprint call: {e}", flush=True)
    deadline = time.time() + 900
    while time.time() < deadline:
        st = sim.call("GET", "/v1/status")
        if st.get("gate") == "RUN" and len(st.get("episodes", [])) == 0:
            break
        if st.get("gate") == "RUN":
            try:
                sim.call("POST", "/v1/tick",
                         {"mode": "sprint", "ticks": MAX_TICKS + 50})
            except RuntimeError:
                pass
        time.sleep(0.3)
    scores, paths = [], []
    for lg in logs:
        scores.append(episode_score(lg, run_dir=run_dir))
        p = Path(lg)
        paths.append(p if p.is_absolute() else Path(run_dir) / p)
    return scores, paths


def run_batch_all(insts, params, scenarios, terrains):
    """Run one arena-batch per fleet instance in parallel.

    insts: list of (Sim, run_dir, idx_list) where idx_list holds the global
    arena indices this instance covers. params/scenarios/terrains are the
    global envs-length lists. Returns (scores, paths) in global order.
    """
    if len(insts) == 1:
        sim, rd, idxs = insts[0]
        return run_batch_train(
            sim, [params[i] for i in idxs], [scenarios[i] for i in idxs],
            [terrains[i] for i in idxs], rd)
    out = [None] * len(insts)

    def work(k):
        sim, rd, idxs = insts[k]
        try:
            out[k] = run_batch_train(
                sim, [params[i] for i in idxs], [scenarios[i] for i in idxs],
                [terrains[i] for i in idxs], rd)
        except BaseException as e:  # propagate after join
            out[k] = e

    threads = [threading.Thread(target=work, args=(k,), daemon=True)
               for k in range(len(insts))]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    for o in out:
        if isinstance(o, BaseException):
            raise o
    n = sum(len(i[2]) for i in insts)
    scores, paths = [None] * n, [None] * n
    for (sim, rd, idxs), (sc, pt) in zip(insts, out):
        for j, gi in enumerate(idxs):
            scores[gi] = sc[j]
            paths[gi] = pt[j]
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
            mv = np.array(sm["mv"], dtype=np.float32)
            flags = np.array(sm.get("flags") or [0]*4, dtype=np.float32)
            out.append({"g": g, "ef": ef, "tid": tid, "n": n,
                        "h": h.squeeze(0).numpy().copy(),
                        "mv": mv, "tgt": int(sm.get("tgt", -1)),
                        "flags": flags,
                        "old_logp": float(sm["logp"]),
                        # same-tick (obs_i, action_i) pair for the GAIL D
                        "dsf": state_feats(obs_list[i]),
                        "daf": act_feats(mv, int(sm.get("tgt", -1)), flags)})
            xg = torch.tensor(g[None], dtype=torch.float32)
            xe = torch.tensor(ef[None], dtype=torch.float32)
            xt = torch.tensor(tid[None], dtype=torch.long)
            xn = torch.tensor([n])
            _, _, h = pol(xg, xe, xt, xn, h)
    return out


def replay_batch(pol, episodes):
    """Batched replay_episode: step all episodes' GRU streams in lockstep,
    one forward per time index instead of one per (episode, tick).

    episodes: list of (obs_list, sampled_list). Returns a parallel list of
    record lists identical in content to replay_episode's per-episode output.
    """
    E = len(episodes)
    out = [[] for _ in range(E)]
    encs = [None] * E
    for e, (obs_l, _s) in enumerate(episodes):
        encs[e] = [PE.encode_egt(o) for o in obs_l]
    lmax = max((len(s) for _o, s in episodes), default=0)
    h = torch.zeros(E, PE.D_H)
    with torch.no_grad():
        for i in range(lmax):
            act = [e for e in range(E)
                   if i < len(episodes[e][1])
                   and episodes[e][1][i] is not None]
            if not act:
                continue
            ja = [max(0, i - OBS_DELAY) for e in act]
            g = np.stack([encs[e][j][0] for e, j in zip(act, ja)])
            ef = np.stack([encs[e][j][1] for e, j in zip(act, ja)])
            tid = np.stack([encs[e][j][2] for e, j in zip(act, ja)])
            n = np.array([encs[e][j][3] for e, j in zip(act, ja)])
            h_prev = h[act].clone()
            _, _, h2 = pol(torch.tensor(g), torch.tensor(ef),
                           torch.tensor(tid, dtype=torch.long),
                           torch.tensor(n), h_prev)
            for row, e in zip(range(len(act)), act):
                sm = episodes[e][1][i]
                mv = np.array(sm["mv"], dtype=np.float32)
                flags = np.array(sm.get("flags") or [0] * 4,
                                 dtype=np.float32)
                out[e].append({
                    "g": g[row], "ef": ef[row], "tid": tid[row],
                    "n": int(n[row]), "h": h_prev[row].numpy().copy(),
                    "mv": mv, "tgt": int(sm.get("tgt", -1)),
                    "flags": flags, "old_logp": float(sm["logp"]),
                    "dsf": state_feats(episodes[e][0][i]),
                    "daf": act_feats(mv, int(sm.get("tgt", -1)), flags)})
            h[act] = h2
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
    ap.add_argument("--gail", action="store_true",
                    help="learned per-tick reward: GAIL discriminator on "
                         "champ AST trajectories vs own rollouts")
    ap.add_argument("--gail-out", type=float, default=1.0,
                    help="outcome bonus weight added to mean D reward "
                         "({clear:+1, died:-1, timeout:-0.3} * this)")
    ap.add_argument("--gail-mix", type=float, default=0.0,
                    help="if >0: reward = ep_return + mix*mean_D_logit "
                         "(learned term auxiliary to shaping, not a "
                         "replacement)")
    ap.add_argument("--gail-lr", type=float, default=3e-4)
    ap.add_argument("--gail-warmup", type=int, default=3,
                    help="iters that only train D (no policy update)")
    ap.add_argument("--gail-epochs", type=int, default=2)
    ap.add_argument("--gail-buf", type=int, default=2,
                    help="iters of negative replay kept for D")
    ap.add_argument("--evalevery", type=int, default=10)
    ap.add_argument("--neval", type=int, default=12)
    ap.add_argument("--familymix", action="store_true")
    ap.add_argument("--sims", default="http://127.0.0.1:8777",
                    help="comma-separated sim control base URLs, one per "
                         "fleet instance; arenas are split across them")
    ap.add_argument("--rundirs", default="",
                    help="comma-separated run dirs parallel to --sims "
                         "(default: run/sim-server for one instance, "
                         "run/sim-inst-i for a fleet)")
    ap.add_argument("--device", default="cpu")
    ap.add_argument("--wandb", action="store_true",
                    help="log metrics + replay videos to Weights & Biases "
                         "(needs `wandb` installed and WANDB_API_KEY set)")
    ap.add_argument("--wandb-project", default="airicraft-combat")
    ap.add_argument("--wandb-name", default=None)
    ap.add_argument("--wandb-entity", default=None)
    ap.add_argument("--video-every", type=int, default=0,
                    help="render N batch episodes to mp4 and log them every "
                         "this many iters (0=off)")
    ap.add_argument("--video-n", type=int, default=2,
                    help="episodes per video upload: best-return ep plus "
                         "first died/timeout for contrast")
    ap.add_argument("--out", default=str(Path(__file__).parent / "results_grpo"))
    args = ap.parse_args()

    assert args.envs % args.groups == 0
    G = args.envs // args.groups
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    torch.manual_seed(args.seed)
    rng = np.random.default_rng(args.seed)
    eval_rng = np.random.default_rng(999)
    device = torch.device(args.device)

    bases = [b.strip() for b in args.sims.split(",") if b.strip()]
    if args.rundirs.strip():
        rundirs = [Path(d.strip()) for d in args.rundirs.split(",")]
    elif len(bases) == 1:
        rundirs = [RUN_DIR]
    else:
        rundirs = [RUN_DIR.parent / f"sim-inst-{i}" for i in range(len(bases))]
    assert len(rundirs) == len(bases)
    # contiguous arena blocks per instance
    per = math.ceil(args.envs / len(bases))
    insts = []          # (Sim, run_dir, global_idx_list)
    arenas = []
    for i, b in enumerate(bases):
        idxs = list(range(i * per, min((i + 1) * per, args.envs)))
        if not idxs:
            continue
        sub = [f"gr{k}" for k in idxs]
        arenas.extend(sub)
        insts.append((Sim(sub, api=b), rundirs[i], idxs))
    wb = None
    if args.wandb:
        offline = os.environ.get("WANDB_MODE") == "offline"
        if not os.environ.get("WANDB_API_KEY") and not offline:
            print("[warn] --wandb set but WANDB_API_KEY is not in the "
                  "environment — logging disabled (set WANDB_MODE=offline "
                  "to log locally and `wandb sync` later)", flush=True)
        else:
            try:
                import wandb
                wandb.init(project=args.wandb_project,
                           name=args.wandb_name,
                           entity=args.wandb_entity or None,
                           config=dict(vars(args)))
                wb = wandb
            except Exception as e:
                print(f"[warn] wandb disabled: {e}", flush=True)

    print(f"[setup] {len(bases)} instance(s), {len(arenas)} arenas "
          f"({[len(i[2]) for i in insts]}) ...", flush=True)
    for sim, _rd, _idx in insts:
        sim.call("POST", "/v1/tick", {"mode": "freeze"})
        for name in sim.arenas:
            sim.setup_arena(name)
        sim.call("POST", "/v1/step", {"ticks": 10})

    pol = PE.PolicyEGT().to(device)
    if args.initempty:
        PE.load_egt(pol, np.load(args.initempty))
        pol.to(device)  # load_egt assigns CPU tensors into .data
        print(f"[init] egt load from {args.initempty}", flush=True)
    opt = torch.optim.Adam(pol.parameters(), lr=args.lr)
    ema_flat = pol.flat() if args.ema > 0 else None

    disc = dopt = None
    exp_sf = exp_af = None
    neg_buf = []
    if args.gail:
        exp_sf, exp_af = load_expert_pairs()
        disc = GailD().to(device)
        dopt = torch.optim.Adam(disc.parameters(), lr=args.gail_lr)

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
            scores, paths = run_batch_all(insts, params, scenarios, terrains)
        except RuntimeError as e:
            # transient spawn/terrain failures — drop the iteration
            print(f"[{it}] batch failed, skipping: {e}", flush=True)
            continue

        # ---- parse + replay episodes --------------------------------------
        all_ticks = []          # per-tick replay records
        ep_rets = np.zeros(args.envs)
        outcomes = {}
        parsed_eps = []         # (ai, obs_l, smp_l, path)
        for ai, (sc, pth) in enumerate(zip(scores, paths)):
            parsed = parse_episode(pth) if pth is not None else None
            if parsed is None:
                ep_rets[ai] = ep_return(sc) if sc else -50.0
                continue
            obs_l, smp_l, score = parsed
            if sc is not None:
                score = sc
            ep_rets[ai] = ep_return(score)
            outcomes[score.get("outcome", "?")] = \
                outcomes.get(score.get("outcome", "?"), 0) + 1
            parsed_eps.append((ai, obs_l, smp_l, pth))
        for (ai, _o, _s, _p), recs in zip(
                parsed_eps,
                replay_batch(pol_shadow,
                             [(o, s) for _a, o, s, _p in parsed_eps])):
            gidx = ai // G
            for r in recs:
                r["group"] = gidx
                r["ep"] = ai
            all_ticks.extend(recs)

        # optional replay videos: best-return ep + first death/timeout
        if wb and args.video_every and it % args.video_every == 0 \
                and parsed_eps:
            try:
                from render_replay import render
                picks = []
                ai_best = int(np.argmax(
                    [ep_rets[a] for a, *_ in parsed_eps]))
                picks.append(parsed_eps[ai_best])
                for pe in parsed_eps:
                    oc = scores[pe[0]].get("outcome") if scores[pe[0]] else None
                    if oc in ("PLAYER_DIED", "TIMEOUT") \
                            and pe[0] != picks[0][0]:
                        picks.append(pe)
                        break
                for pi, (ai, _o, _s, pth) in enumerate(
                        picks[:args.video_n]):
                    mp4 = out / f"replay_it{it}_ep{ai}.mp4"
                    try:
                        render(str(pth), str(mp4))
                        wb.log({f"replay/ep{ai}": wb.Video(
                            str(mp4), fps=12, format="mp4",
                            caption=f"iter {it} arena{ai} "
                                    f"ret={ep_rets[ai]:.1f} "
                                    f"{scores[ai].get('outcome')}")},
                            step=it)
                    except Exception as e:
                        print(f"  [warn] video ep{ai}: {e}", flush=True)
            except ImportError:
                print("  [warn] render_replay unavailable", flush=True)
        for _a, _o, _s, pth in parsed_eps:
            try:
                pth.unlink()
            except OSError:
                pass

        # ---- GAIL discriminator update + learned rewards ------------------
        if disc is not None:
            neg_sf = np.stack([r["dsf"] for r in all_ticks]) \
                if all_ticks else np.zeros((0, SF_DIM), np.float32)
            neg_af = np.stack([r["daf"] for r in all_ticks]) \
                if all_ticks else np.zeros((0, AF_DIM), np.float32)
            neg_buf.append((neg_sf, neg_af))
            while len(neg_buf) > args.gail_buf:
                neg_buf.pop(0)
            bsf = np.concatenate([x[0] for x in neg_buf])
            baf = np.concatenate([x[1] for x in neg_buf])
            d_loss = 0.0
            n_db = 0
            for _e in range(args.gail_epochs):
                bs = min(4096, len(bsf), len(exp_sf))
                pi = rng.choice(len(exp_sf), bs, replace=False)
                ni = rng.choice(len(bsf), bs, replace=False)
                sf = torch.tensor(np.concatenate([exp_sf[pi], bsf[ni]]),
                                  device=device)
                af = torch.tensor(np.concatenate([exp_af[pi], baf[ni]]),
                                  device=device)
                lab = torch.cat([torch.ones(bs), torch.zeros(bs)]).to(device)
                logits = disc(sf, af)
                dl = nn.functional.binary_cross_entropy_with_logits(
                    logits, lab)
                # mild gradient penalty keeps logits bounded early
                dl = dl + 1e-4 * (logits ** 2).mean()
                dopt.zero_grad(); dl.backward(); dopt.step()
                d_loss += dl.item(); n_db += 1
            with torch.no_grad():
                sf_t = torch.tensor(neg_sf, device=device)
                af_t = torch.tensor(neg_af, device=device)
                # learned per-tick reward = clipped log-odds
                r_t = disc(sf_t, af_t).clamp(-8, 8).cpu().numpy()
            k = 0
            ep_rets = np.zeros(args.envs)
            for ai in range(args.envs):
                ep_ticks = [r for r in all_ticks if r["ep"] == ai]
                n_t = len(ep_ticks)
                base = float(r_t[k:k + n_t].mean()) if n_t else -1.0
                k += n_t
                oc = scores[ai].get("outcome") if scores[ai] else None
                if args.gail_mix > 0:
                    ep_rets[ai] = ep_return(scores[ai]) \
                        + args.gail_mix * base
                else:
                    ob = {"ALL_MOBS_CLEARED": 1.0, "PLAYER_DIED": -1.0,
                          "TIMEOUT": -0.3}.get(oc, 0.0)
                    ep_rets[ai] = base + args.gail_out * ob
            print(f"  [gail] d_loss={d_loss/max(n_db,1):.3f} "
                  f"r_mean={r_t.mean():.2f}±{r_t.std():.2f} "
                  f"ep_rets={np.round(ep_rets,1).tolist()}", flush=True)
            if wb:
                wb.log({"iter": it, "gail/d_loss": d_loss / max(n_db, 1),
                        "gail/r_mean": float(r_t.mean()),
                        "gail/r_std": float(r_t.std())}, step=it)

        # iter-1 sanity: Python-side logp under the just-deployed weights
        # must match the Java-side logp recorded during sampling (drift >~1e-2
        # means the replayed obs/h stream diverges from what Java saw).
        if it == 1 and all_ticks:
            with torch.no_grad():
                lp_py = []  # per-head python logp
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
                if wb:
                    wb.log({"sanity/drift_mean": float(d.mean()),
                            "sanity/drift_max": float(d.max())}, step=it)

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
        if all_ticks and not (disc is not None and it <= args.gail_warmup):
            XG = torch.tensor(np.stack([r["g"] for r in all_ticks]),
                              device=device)
            XE = torch.tensor(np.stack([r["ef"] for r in all_ticks]),
                              device=device)
            XT = torch.tensor(np.stack([r["tid"] for r in all_ticks]),
                              dtype=torch.long, device=device)
            XN = torch.tensor([r["n"] for r in all_ticks], device=device)
            XH = torch.tensor(np.stack([r["h"] for r in all_ticks]),
                              device=device)
            MV = torch.tensor(np.stack([r["mv"] for r in all_ticks]),
                              device=device)
            TG = torch.tensor([r["tgt"] for r in all_ticks], device=device)
            FG = torch.tensor(np.stack([r["flags"] for r in all_ticks]),
                              device=device)
            OLP = torch.tensor([r["old_logp"] for r in all_ticks],
                               dtype=torch.float32, device=device)
            ADV = torch.tensor([r["adv"] for r in all_ticks],
                               dtype=torch.float32, device=device)
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
        if wb:
            wd = {"iter": it, "ticks": len(all_ticks), "pol_loss": pl,
                  "entropy": el, "dt": time.time() - t0,
                  "ret/mean": float(ep_rets.mean()),
                  "ret/min": float(ep_rets.min()),
                  "ret/max": float(ep_rets.max()),
                  "rets": wb.Histogram(np.nan_to_num(ep_rets, nan=-50.0)),
                  "adv/absmax": float(np.abs(adv).max())}
            for k, v in outcomes.items():
                wd[f"outcome/{k}"] = v
            wb.log(wd, step=it)

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
                    sc2, _lp = run_batch_all(
                        insts, eparams, [scen] * len(arenas),
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
            if wb:
                wb.log({"iter": it, "eval/kills": float(vec[0]),
                        "eval/taken": float(-vec[1]),
                        "eval/clear": float(vec[2]),
                        "eval/survived": float(vec[3]),
                        "eval/ticks": float(-vec[4]),
                        "eval/degenerate": bool(deg)}, step=it)
            key = (round(float(vec[0]), 3), round(float(vec[2]), 3),
                   round(float(vec[3]), 3), round(float(vec[4]), 3))
            if not deg and (best_key is None or key > best_key):
                best_key = key
                np.save(out / "pol_best.npy", eval_flat)
                if wb:
                    wb.save(str(out / "pol_best.npy"), policy="live")
                print(f"  [best] {key}", flush=True)
            np.save(out / "pol_last.npy", eval_flat)
            if wb:
                wb.save(str(out / "pol_last.npy"), policy="live")

    print("[done]", flush=True)
    if wb:
        wb.finish()


if __name__ == "__main__":
    main()
