#!/usr/bin/env python3
"""PPO over the /v1/step external-policy API.

Unlike the ES runs (optimize_net.py), the policy here lives client-side: each
step POSTs per-arena intents, ticks the world once, and gets back post-tick
obs + score deltas + done flags. The network is the same 71->48->24->10 MLP
layout as NetPolicy (action heads: moveXY Gaussian / target-slot categorical /
attack+shield+sprint+jump Bernoulli), so trained weights ship back server-side
via params.net for eval identical to ES.

Reward is the endorsed scalar J /10 per tick (kills +10, dealt +0.1,
taken -0.3, -0.02/tick, terminal +6 clear / -50 death) — a training signal
only; evaluation stays multi-objective (5-dim vector + dominance, same as
every round before).
"""

import argparse
import json
import time
from collections import deque
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn
from torch.distributions import Bernoulli, Categorical, Normal

import sys
sys.path.insert(0, str(Path(__file__).parent))
from optimize_cmaes import (  # noqa: E402
    call, Sim, random_scenario, random_terrain, NETHER_MOB_POOL,
    metrics_of, METRIC_NAMES, MAX_TICKS)
from optimize_net import (  # noqa: E402
    encode, hostiles_of, spec_of, pack, unpack, stack_encode,
    SIZES, INPUT, K, STACK, F, G, FRAME)

RUN_DIR = Path(__file__).resolve().parent.parent / "run" / "sim-server"
EP_DIR = RUN_DIR / "sim" / "episodes"


# ----------------------------------------------------------------- model --

class Policy(nn.Module):
    def __init__(self, sizes=SIZES):
        super().__init__()
        self.sizes = sizes
        self.layers = nn.ModuleList(
            [nn.Linear(a, b) for a, b in zip(sizes[:-1], sizes[1:])])
        self.log_std = nn.Parameter(torch.full((2,), -1.0))

    def forward(self, x):
        a = x
        for i, l in enumerate(self.layers):
            a = l(a)
            if i < len(self.layers) - 1:
                a = torch.tanh(a)
        return a  # raw 10-dim

    def flat(self):
        return pack([(l.weight.detach().cpu().numpy(),
                      l.bias.detach().cpu().numpy()) for l in self.layers])

    def load_flat(self, vec):
        for (w, b), l in zip(unpack(vec), self.layers):
            l.weight.data = torch.tensor(w, dtype=torch.float32)
            l.bias.data = torch.tensor(b, dtype=torch.float32)


class Value(nn.Module):
    def __init__(self):
        super().__init__()
        self.net = nn.Sequential(nn.Linear(INPUT, 48), nn.Tanh(),
                                 nn.Linear(48, 1))

    def forward(self, x):
        return self.net(x).squeeze(-1)


class PolicyPtr(nn.Module):
    """Pointer-head policy: shared trunk -> ctx(48); per-slot scorer
    scores each hostile's 14 feats against ctx (weight-shared across slots,
    the neural analog of the AST target grammar); separate move/flag heads.

    forward() returns the same (B, 2+K+4) layout as Policy so the PPO loop
    needs no changes. Slot feats are sliced from the latest stacked frame
    (positions [G+k*F, G+k*F+F) of frame STACK-1)."""

    def __init__(self, hid=(96, 48), ptr_hid=16):
        super().__init__()
        self.h1 = nn.Linear(INPUT, hid[0])
        self.h2 = nn.Linear(hid[0], hid[1])
        self.p1 = nn.Linear(hid[1] + F, ptr_hid)
        self.p2 = nn.Linear(ptr_hid, 1)
        self.mvH = nn.Linear(hid[1], 2)
        self.flH = nn.Linear(hid[1], 4)
        self.log_std = nn.Parameter(torch.full((2,), -1.0))

    def forward(self, x):
        ctx = torch.tanh(self.h2(torch.tanh(self.h1(x))))
        latest = x[:, (STACK - 1) * FRAME:(STACK - 1) * FRAME + FRAME]
        sf = latest[:, G:G + K * F].reshape(-1, K, F)          # (B,K,14)
        ctxk = ctx.unsqueeze(1).expand(-1, K, -1)              # (B,K,48)
        sc = self.p2(torch.tanh(self.p1(
            torch.cat([ctxk, sf], dim=-1)))).squeeze(-1)       # (B,K)
        return torch.cat([self.mvH(ctx), sc, self.flH(ctx)], dim=-1)

    def flat(self):
        parts = []
        for l in (self.h1, self.h2, self.p1, self.p2, self.mvH, self.flH):
            parts += [l.weight.detach().cpu().numpy().ravel(),
                      l.bias.detach().cpu().numpy().ravel()]
        return np.concatenate(parts)

    def load_trunk(self, vec):
        """Warm-start h1/h2 from a flat vec whose first two layers share dims."""
        hid = unpack_sized(vec, SIZES)
        for (w, b), l in zip(hid[:2], (self.h1, self.h2)):
            l.weight.data = torch.tensor(w, dtype=torch.float32)
            l.bias.data = torch.tensor(b, dtype=torch.float32)


PTR_SIZES = [(1980, 96), (96, 48), (62, 16), (16, 1), (48, 2), (48, 4)]


def spec_of_ptr(vec):
    off, layers = 0, []
    for (n_in, n_out) in PTR_SIZES:
        nw, nb = n_in * n_out, n_out
        w = vec[off:off + nw].reshape(n_out, n_in)
        b = vec[off + nw:off + nw + nb]
        layers.append({"shape": [n_in, n_out],
                       "w": np.round(w, 5).tolist(),
                       "b": np.round(b, 5).tolist()})
        off += nw + nb
    return {"layout": "ptr", "layers": layers[:2],
            "ptr": layers[2:4], "heads": layers[4:6]}


# -------------------------------------------------------------- actions --

AST = None  # champ program for neuro-symbolic hybrid mode (--ast)
SEL = None      # --sel: list of champ programs; net picks one per tick
SEL_SIZES = None
PTR = False     # --ptr: PolicyPtr pointer-head policy
FAMILYMIX = False  # --familymix: balance scenario pools uniformly
FAM_POOLS = {"standard": None,
             "ranged": [("skeleton", 0.65), ("zombie", 0.20), ("creeper", 0.15)],
             "melee": [("zombie", 0.55), ("spider", 0.30), ("creeper", 0.15)],
             "nether": NETHER_MOB_POOL}


def unpack_sized(vec, sizes):
    off, out = 0, []
    for n_in, n_out in zip(sizes[:-1], sizes[1:]):
        nw, nb = n_in * n_out, n_out
        w = vec[off:off + nw].reshape(n_out, n_in)
        b = vec[off + nw:off + nw + nb]
        out.append((w, b))
        off += nw + nb
    return out


def spec_of_sized(vec, sizes):
    layers = []
    for (w, b), (n_in, n_out) in zip(unpack_sized(vec, sizes),
                                    zip(sizes[:-1], sizes[1:])):
        layers.append({"shape": [int(n_in), int(n_out)],
                       "w": np.round(w, 5).tolist(),
                       "b": np.round(b, 5).tolist()})
    return {"layout": "sel", "layers": layers}


def act_to_intent(obs, move, target_idx, flags):
    """Sampled action -> intent JSON, mirroring NetPolicy.decide.

    In --ast mode the discrete flags come from the server-side AST program
    (ExternalPolicy composes them), so only move + look are posted.
    """
    hs = hostiles_of(obs)
    intent = {"moveDir": [float(np.clip(move[0], -1, 1)),
                          float(np.clip(move[1], -1, 1))]}
    if AST is None:
        intent["attack"] = bool(flags[0])
        intent["sprint"] = bool(flags[2])
        intent["jump"] = bool(flags[3] and obs["player"].get("onGround", False))
    if hs:
        intent["lookEntity"] = int(hs[min(int(target_idx), len(hs) - 1)]["id"])
    if AST is None:
        if flags[1]:
            intent["useHand"] = "off"
        else:
            intent["stopUsing"] = True
    return intent


# ---------------------------------------------------------------- reward --

def rew(dk, ddealt, dtaken, dticks, score):
    r = 10.0 * dk + 0.1 * ddealt - 0.3 * dtaken - 0.05 * dticks
    if score is not None:
        oc = score.get("outcome")
        if oc == "ALL_MOBS_CLEARED":
            r += 6.0
        elif oc == "PLAYER_DIED":
            r -= 50.0
        elif oc == "TIMEOUT":
            r -= 15.0   # no-progress timeout is near-death — blocks turtling
    return r


# -------------------------------------------------------------------- env --

class Env:
    """One arena's external-policy episode lifecycle + per-env trajectory."""

    def __init__(self, name, rng, hard=True, netherfrac=0.3):
        self.name = name
        self.rng = rng
        self.hard = hard
        self.netherfrac = netherfrac
        self.obs = None
        self.acc = {"kills": 0, "dealt": 0.0, "taken": 0.0, "tick": 0}
        self.ep_id = None
        self.need_reset = True
        self.done_seen = False
        self.traj = []
        self.ep_scores = []
        self.hist = deque(maxlen=STACK)   # recent encodes for frame-stack

    def reset_phase1(self):
        """Reset arena + spawn a fresh scenario. No world ticks happen here;
        the caller materializes entities via /v1/step {ticks:10} before
        start() so the episode never sees an empty world.

        Spawn occasionally 500s on collision ("no empty space"); resample the
        scenario a few times like the batch runner tolerates."""
        for _try in range(6):
            if FAMILYMIX:
                fam = list(FAM_POOLS)[self.rng.integers(len(FAM_POOLS))]
                pool = FAM_POOLS[fam]
                nether = fam == "nether"
            else:
                nether = self.rng.random() < self.netherfrac
                pool = NETHER_MOB_POOL if nether else None
            scen = random_scenario(
                self.rng, min_r=5.5, max_r=8.0, min_n=4, max_n=9,
                pool=pool) if self.hard \
                else random_scenario(self.rng, pool=pool)
            terr = random_terrain(self.rng,
                                  avoid_pts=[(dx, dz) for _t, dx, dz in scen],
                                  nether=nether)
            try:
                sim.reset_and_spawn(self.name, scen, terrain=terr)
                return
            except RuntimeError as e:
                if _try == 5:
                    print(f"  [warn] {self.name} reset failed x6: {e}", flush=True)

    def start(self):
        body = {"arena": self.name, "policy": "external",
                "maxTicks": MAX_TICKS, "obsRadius": 20.0}
        if SEL is not None:
            body["params"] = {"programs": SEL}
        elif AST is not None:
            body["params"] = {"ast": AST}
        ep = call("POST", "/v1/episode", body)
        self.ep_id = ep.get("id")
        self.acc = {"kills": 0, "dealt": 0.0, "taken": 0.0, "tick": 0}
        self.obs = None
        self.need_reset = False
        self.done_seen = False
        self.hist.clear()

    def finish_transition(self, a):
        """Fold a step response into acc + close/mark the trajectory tail.
        Returns reward delta (already added to traj[-1] if present)."""
        acc = self.acc
        dk = a["kills"] - acc["kills"]
        ddealt = a["damageDealt"] - acc["dealt"]
        dtaken = a["damageTaken"] - acc["taken"]
        dticks = a["tick"] - acc["tick"]
        score = a.get("score") if a["done"] else None
        r = rew(dk, ddealt, dtaken, dticks, score)
        self.acc = {"kills": a["kills"], "dealt": a["damageDealt"],
                    "taken": a["damageTaken"], "tick": a["tick"]}
        if a["done"] and not self.done_seen:
            self.done_seen = True
            self.ep_scores.append(a.get("score", {}))
            self.need_reset = True
            self.delete_log()
        elif not a["done"]:
            self.obs = a["obs"]
            self.hist.append(encode(a["obs"]))
        return r

    def delete_log(self):
        if self.ep_id:
            p = EP_DIR / f"episode-{self.ep_id}.jsonl"
            try:
                p.unlink()
            except OSError:
                pass


# ------------------------------------------------------------------- ppo --

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--envs", type=int, default=12)
    ap.add_argument("--steps", type=int, default=256, help="steps per iter per env")
    ap.add_argument("--iters", type=int, default=400)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--lr", type=float, default=3e-4)
    ap.add_argument("--gamma", type=float, default=0.998)
    ap.add_argument("--lam", type=float, default=0.95)
    ap.add_argument("--clip", type=float, default=0.2)
    ap.add_argument("--ent", type=float, default=0.003,
                    help="entropy coef for the move (Gaussian) head")
    ap.add_argument("--entb", type=float, default=0.0005,
                    help="entropy coef for discrete heads — kept low so flags commit")
    ap.add_argument("--margin", type=float, default=0.15,
                    help="flag-logit commit margin; 0 disables margin shaping")
    ap.add_argument("--marginw", type=float, default=0.02)
    ap.add_argument("--ema", type=float, default=0.995,
                    help="EMA decay for the eval/averaged policy; 0 disables")
    ap.add_argument("--epochs", type=int, default=4)
    ap.add_argument("--mb", type=int, default=512)
    ap.add_argument("--netherfrac", type=float, default=0.3)
    ap.add_argument("--init", default=None, help="flat .npy warm start")
    ap.add_argument("--evalevery", type=int, default=10)
    ap.add_argument("--neval", type=int, default=12)
    ap.add_argument("--out", default=str(Path(__file__).parent / "results_rl"))
    ap.add_argument("--ast", default=None,
                    help="champ AST JSON: neuro-symbolic hybrid — net supplies "
                         "move+target only, flags come from the AST program")
    ap.add_argument("--sel", default=None,
                    help="JSON list of champ programs: selector MoE — the net "
                         "outputs N-way scores picking which program runs each "
                         "tick; inherits each champ's full intent")
    ap.add_argument("--familymix", action="store_true",
                    help="balance resets uniformly over scenario families "
                         "(standard/ranged/melee/nether) so the selector sees "
                         "the family distinctions it must learn")
    ap.add_argument("--ptr", action="store_true",
                    help="pointer-head policy: per-slot target scorer "
                         "(slot feats + global ctx) instead of positional "
                         "target logits — the neural analog of AST's "
                         "conditioned target grammar")
    args = ap.parse_args()

    if args.ast:
        global AST
        AST = json.loads(Path(args.ast).read_text())
    if args.sel:
        global SEL, SEL_SIZES
        SEL = json.loads(Path(args.sel).read_text())
        SEL_SIZES = (INPUT, 96, 48, len(SEL))
        print(f"[sel] {len(SEL)} programs; selector head {SEL_SIZES}", flush=True)
    if args.familymix:
        global FAMILYMIX
        FAMILYMIX = True
    if args.ptr:
        global PTR
        PTR = True

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    torch.manual_seed(args.seed)

    global sim, pol
    arenas = [f"rl-{i}" for i in range(args.envs)]
    sim = Sim(arenas)
    print("[setup] arenas ...", flush=True)
    call("POST", "/v1/tick", {"mode": "freeze"})
    for name in arenas:
        sim.setup_arena(name)
    call("POST", "/v1/step", {"ticks": 10})

    pol = PolicyPtr() if PTR else Policy(SIZES if SEL is None else SEL_SIZES)
    val = Value()
    if args.init:
        iv = np.load(args.init)
        if PTR:
            pol.load_trunk(iv)
        elif SEL is not None:
            # hidden layers are shared with the 12-out BC champion: reuse
            # them and leave the fresh 48->NPROG selector head at init
            hid = unpack_sized(iv, SIZES)
            for (w, b), l in zip(hid[:-1], pol.layers[:-1]):
                l.weight.data = torch.tensor(w, dtype=torch.float32)
                l.bias.data = torch.tensor(b, dtype=torch.float32)
        else:
            pol.load_flat(iv)
        print(f"[init] warm from {args.init}", flush=True)
    opt = torch.optim.Adam(list(pol.parameters()) + list(val.parameters()), lr=args.lr)

    envs = [Env(n, np.random.default_rng(args.seed * 977 + i),
                netherfrac=args.netherfrac) for i, n in enumerate(arenas)]
    for e in envs:
        e.reset_phase1()
    call("POST", "/v1/step", {"ticks": 10})
    for e in envs:
        e.start()
    init = call("POST", "/v1/step", {"ticks": 0})
    for e in envs:
        if e.name in init:
            e.obs = init[e.name].get("obs")
            if e.obs is not None:
                e.hist.clear()
                e.hist.append(encode(e.obs))

    hist = open(out / "history.jsonl", "a")

    def sample_eval_set(r, n):
        s = []
        for _ in range(n):
            if FAMILYMIX:
                fam = list(FAM_POOLS)[r.integers(len(FAM_POOLS))]
                pool = FAM_POOLS[fam]
                nether = fam == "nether"
            else:
                nether = r.random() < args.netherfrac
                pool = NETHER_MOB_POOL if nether else None
            scen = random_scenario(r, min_r=5.5, max_r=8.0, min_n=4, max_n=9,
                                   pool=pool)
            terr = random_terrain(r, avoid_pts=[(dx, dz) for _t, dx, dz in scen],
                                  nether=nether)
            s.append((scen, terr))
        return s

    eval_rng = np.random.default_rng(args.seed * 7919 + 50000)
    best_key = None
    ema_flat = pol.flat() if args.ema > 0 else None

    for it in range(1, args.iters + 1):
        t0 = time.time()
        ep_end = 0
        for e in envs:
            e.traj = []

        for _t in range(args.steps):
            idx = [i for i, e in enumerate(envs)
                   if not e.need_reset and e.obs is not None]
            if idx:
                xs = torch.tensor(np.stack([stack_encode(envs[i].hist) for i in idx]),
                                  dtype=torch.float32)
                with torch.no_grad():
                    y = pol(xs)
                    v = val(xs)
                n_valid = [min(len(hostiles_of(envs[i].obs)), K) for i in idx]
                if SEL is not None:
                    prog_d = Categorical(logits=y[:, :len(SEL)])
                    pg = prog_d.sample()
                    mv = torch.zeros(len(idx), 2)
                    tg = torch.zeros(len(idx), dtype=torch.long)
                    fg = torch.zeros(len(idx), 4)
                    logp = prog_d.log_prob(pg)
                else:
                    std = torch.exp(pol.log_std)
                    move_d = Normal(y[:, 0:2], std.expand_as(y[:, 0:2]))
                    tgt_logits = y[:, 2:2 + K].clone()
                    for r_i, nv in enumerate(n_valid):
                        tgt_logits[r_i, nv:] = -1e9
                    tgt_d = Categorical(logits=tgt_logits)
                    flag_d = Bernoulli(logits=y[:, 2 + K:2 + K + 4])
                    mv = move_d.sample()
                    tg = tgt_d.sample()
                    fg = flag_d.sample()
                    hasT = torch.tensor([n > 0 for n in n_valid], dtype=torch.bool)
                    logp = (move_d.log_prob(mv).sum(-1)
                            + torch.where(hasT, tgt_d.log_prob(tg),
                                          torch.zeros(len(idx)))
                            + flag_d.log_prob(fg).sum(-1))
                intents = {}
                for j, i in enumerate(idx):
                    if SEL is not None:
                        intents[envs[i].name] = {"program": int(pg[j])}
                    else:
                        intents[envs[i].name] = act_to_intent(
                            envs[i].obs, mv[j], tg[j], fg[j])
                try:
                    resp = call("POST", "/v1/step", {"intents": intents, "ticks": 1})
                except RuntimeError as e:
                    print(f"  [warn] step failed: {e}", flush=True)
                    resp = {}
                for j, i in enumerate(idx):
                    e = envs[i]
                    a = resp.get(e.name)
                    if a is None:
                        continue
                    r = e.finish_transition(a)
                    e.traj.append({"x": xs[j].numpy(), "mv": mv[j].numpy(),
                                   "tgt": (int(pg[j]) if SEL is not None
                                           else tg[j].item()),
                                   "flg": fg[j].numpy(),
                                   "hasT": (True if SEL is not None
                                            else bool(hasT[j])),
                                   "nv": n_valid[j],
                                   "logp": logp[j].item(), "v": v[j].item(),
                                   "r": r, "done": a["done"]})
                    if a["done"]:
                        ep_end += 1
                # episodes not in idx still ticked under pending intents:
                # fold their delta into their last transition (same action)
                for i, e in enumerate(envs):
                    if i in idx:
                        continue
                    a = resp.get(e.name)
                    if a is None or not e.traj:
                        continue
                    e.traj[-1]["r"] += e.finish_transition(a)
                    if a["done"]:
                        e.traj[-1]["done"] = True
                        ep_end += 1
            else:
                resp = {}

            # ---- reset phase: spawn new scenarios for done envs ----
            todo = [e for e in envs if e.need_reset]
            if todo:
                for e in todo:
                    e.reset_phase1()
                try:
                    resp2 = call("POST", "/v1/step", {"ticks": 10})
                except RuntimeError as e:
                    print(f"  [warn] mat step: {e}", flush=True)
                    resp2 = {}
                for e in envs:
                    a = resp2.get(e.name)
                    if a is None or e.name in [t.name for t in todo]:
                        continue
                    if e.traj:
                        e.traj[-1]["r"] += e.finish_transition(a)
                        if a["done"]:
                            e.traj[-1]["done"] = True
                            ep_end += 1
                for e in todo:
                    if e.need_reset:
                        e.start()
                obs0 = call("POST", "/v1/step", {"ticks": 0})
                for e in envs:
                    a = obs0.get(e.name)
                    if a is not None and not a["done"]:
                        e.obs = a.get("obs")
                        if e.obs is not None:
                            e.hist.append(encode(e.obs))

        # ------------------------------------------------ GAE + PPO update
        flat = []
        for i, e in enumerate(envs):
            tr = e.traj
            n = len(tr)
            if n == 0:
                continue
            with torch.no_grad():
                boot = 0.0 if (tr[-1]["done"] or not e.hist) else float(
                    val(torch.tensor(stack_encode(e.hist), dtype=torch.float32)))
            lastgae = 0.0
            for t in reversed(range(n)):
                nonterm = 0.0 if tr[t]["done"] else 1.0
                nextv = tr[t + 1]["v"] if t + 1 < n else boot
                delta = tr[t]["r"] + args.gamma * nextv * nonterm - tr[t]["v"]
                lastgae = delta + args.gamma * args.lam * nonterm * lastgae
                tr[t]["adv"] = lastgae
                tr[t]["ret"] = lastgae + tr[t]["v"]
            flat.extend(tr)

        if flat:
            X = torch.tensor(np.stack([t["x"] for t in flat]), dtype=torch.float32)
            Mv = torch.tensor(np.stack([t["mv"] for t in flat]), dtype=torch.float32)
            Tg = torch.tensor([t["tgt"] for t in flat], dtype=torch.long)
            Fg = torch.tensor(np.stack([t["flg"] for t in flat]), dtype=torch.float32)
            Ht = torch.tensor([t["hasT"] for t in flat], dtype=torch.bool)
            Nv = torch.tensor([t["nv"] for t in flat], dtype=torch.long)
            OldL = torch.tensor([t["logp"] for t in flat], dtype=torch.float32)
            Adv = torch.tensor([t["adv"] for t in flat], dtype=torch.float32)
            Adv = (Adv - Adv.mean()) / (Adv.std() + 1e-8)
            Ret = torch.tensor([t["ret"] for t in flat], dtype=torch.float32)

            n = len(flat)
            pl = vl = el = 0.0
            nb = 0
            for _ep in range(args.epochs):
                perm = torch.randperm(n)
                for s in range(0, n, args.mb):
                    b = perm[s:s + args.mb]
                    y = pol(X[b])
                    if SEL is not None:
                        prog_d = Categorical(logits=y[:, :len(SEL)])
                        lp = prog_d.log_prob(Tg[b])
                        entm = torch.zeros(1)
                        entb = prog_d.entropy().mean()
                    else:
                        tgt_logits = y[:, 2:2 + K].clone()
                        for r_i in range(len(b)):
                            tgt_logits[r_i, Nv[b][r_i]:] = -1e9
                        move_d = Normal(y[:, 0:2],
                                        torch.exp(pol.log_std).expand_as(y[:, 0:2]))
                        tgt_d = Categorical(logits=tgt_logits)
                        flag_d = Bernoulli(logits=y[:, 2 + K:2 + K + 4])
                        lp = (move_d.log_prob(Mv[b]).sum(-1)
                              + torch.where(Ht[b], tgt_d.log_prob(Tg[b]),
                                            torch.zeros(len(b)))
                              + flag_d.log_prob(Fg[b]).sum(-1))
                        entm = move_d.entropy().sum(-1).mean()
                        entb = (tgt_d.entropy().mean()
                                + flag_d.entropy().sum(-1).mean())
                    ratio = torch.exp(lp - OldL[b])
                    s1 = ratio * Adv[b]
                    s2 = torch.clamp(ratio, 1 - args.clip, 1 + args.clip) * Adv[b]
                    pol_loss = -torch.min(s1, s2).mean()
                    v = val(X[b])
                    v_loss = 0.5 * ((v - Ret[b]) ** 2).mean()
                    loss = pol_loss + v_loss - args.ent * entm - args.entb * entb
                    if args.margin > 0 and SEL is None:
                        fl = y[:, 2 + K:2 + K + 4]
                        loss = loss + args.marginw * torch.relu(
                            args.margin - fl.abs()).mean()
                    opt.zero_grad()
                    loss.backward()
                    nn.utils.clip_grad_norm_(
                        list(pol.parameters()) + list(val.parameters()), 0.5)
                    opt.step()
                    pl += pol_loss.item(); vl += v_loss.item(); el += (entm + entb).item()
                    nb += 1
            pl /= nb; vl /= nb; el /= nb
        else:
            pl = vl = el = 0.0

        dt = time.time() - t0
        T = len(flat)
        recent = [s for e in envs for s in e.ep_scores[-24:]]
        outcomes = {}
        for s in recent:
            outcomes[s.get("outcome", "?")] = outcomes.get(s.get("outcome", "?"), 0) + 1
        flag_rates = np.mean(np.stack([t["flg"] for t in flat]), axis=0).tolist() if flat else []
        line = {"iter": it, "episodes_done": ep_end, "samples": T,
                "outcomes": outcomes,
                "kills_mean": float(np.mean([s.get("kills", 0) for s in recent])) if recent else 0.0,
                "taken_mean": float(np.mean([s.get("damageTaken", 0) for s in recent])) if recent else 0.0,
                "flag_rates": flag_rates,   # atk/shield/sprint/jump sample rates
                "pol_loss": pl, "v_loss": vl, "entropy": el, "dt": round(dt, 1)}
        hist.write(json.dumps(line) + "\n"); hist.flush()
        print(f"[{it}] n={T} done={ep_end} outcomes={outcomes} "
              f"vl={vl:.3f} ent={el:.3f} dt={dt:.1f}s", flush=True)

        np.save(out / "pol_latest.npy", pol.flat())
        if ema_flat is not None:
            ema_flat = args.ema * ema_flat + (1.0 - args.ema) * pol.flat()

        if it % args.evalevery == 0:
            # stop live episodes so eval sprints don't corrupt bookkeeping
            for e in envs:
                if e.ep_id:
                    try:
                        call("POST", f"/v1/episode/stop?id={e.ep_id}")
                    except RuntimeError:
                        pass
                    e.need_reset = True
                    e.delete_log()
            # evaluate the EMA-averaged policy (deployment candidate), not the
            # churning live weights: averaged iterates smooth PPO oscillation
            eval_flat = ema_flat if ema_flat is not None else pol.flat()
            es = sample_eval_set(eval_rng, args.neval)
            if PTR:
                params = [{"net": spec_of_ptr(eval_flat)} for _ in arenas]
                epol = "net"
            elif SEL is not None:
                params = [{"net": spec_of_sized(eval_flat, SEL_SIZES),
                           "programs": SEL} for _ in arenas]
                epol = "selector"
            elif AST is not None:
                params = [{"net": spec_of(eval_flat), "ast": AST}
                          for _ in arenas]
                epol = "hybrid"
            else:
                params = [{"net": spec_of(eval_flat)} for _ in arenas]
                epol = "net"
            acc = np.zeros(len(METRIC_NAMES))
            n_done = 0
            for scen, terr in es:
                try:
                    scores = sim.run_batch(params, scen,
                                           terrains=[terr] * len(arenas),
                                           policy=epol)
                    n_done += 1
                    for s2 in scores:
                        acc += metrics_of(s2)
                except RuntimeError as e:
                    print(f"  [warn] eval batch: {e}", flush=True)
            vec = acc / max(n_done * len(arenas), 1)
            deg = vec[0] < 0.5 and vec[3] > 0.95
            line = {"iter": it, "eval_vec": vec.tolist(),
                    "degenerate_suspect": bool(deg)}
            hist.write(json.dumps(line) + "\n"); hist.flush()
            print(f"[eval {it}] {np.round(vec, 3).tolist()}"
                  + ("  [DEGENERATE?]" if deg else ""), flush=True)
            # deployment-keyed best tracking: kills first, then clear/survived
            key = (round(float(vec[0]), 3), round(float(vec[2]), 3),
                   round(float(vec[3]), 3), round(float(vec[4]), 3))
            if not deg and (best_key is None or key > best_key):
                best_key = key
                np.save(out / "pol_best.npy", eval_flat)
                print(f"  [best] {key}", flush=True)
            call("POST", "/v1/tick", {"mode": "freeze"})

    print("[done]", flush=True)


if __name__ == "__main__":
    main()
