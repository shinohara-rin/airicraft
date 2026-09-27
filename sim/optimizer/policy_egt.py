"""Entity-set + GRU policy (layout "egt").

Replaces the 1980-dim frame-stack input with per-tick structured features:

  globals (GE=16): hp, attack cooldown, last-attacked ticks, usingItem,
    useTicks, offhandPct, onGround, food, mobCount, encirclement
    (1 - |mean unit bearing|, =1 when perfectly surrounded), litCreepers,
    aimingCount, targetingCount, hpSum, nearDist, sprinting

  per-entity (K=8 hostiles, EF=18 + type id): dist + RBF range bins,
    sin/cos bearing relative to player yaw (rotation-invariant — the net
    no longer has to learn trigonometry from raw dx/dz), dy, radial and
    tangential *relative* velocity, speed, hp pct, playerHits, targeting,
    is_ranged, fuse progress, aiming. Type goes through an embedding.

  arch: shared entity MLP -> entity set E[8,32]; player-state query
    attends E (cross-attention) -> ctx; fused [g, attn, meanE] feeds a
    GRUCell (real recurrent memory across ticks, replacing frame-stack);
    heads: ptr scorer over entity embeddings (maskable, count-free),
    move(2), flags(4), value(1).

Mirrored in NetPolicy.java under spec {"layout":"egt"} — encode math must
stay identical on both sides.
"""
import numpy as np
import torch
import torch.nn as nn

K = 8            # max hostiles fed to the encoder
EF = 18          # per-entity numeric feats
TE = 8           # type embedding dim
GE = 16          # globals
D_E = 32         # entity embedding dim
D_H = 48         # GRU hidden

RANGED = ("skeleton", "stray", "pillager", "witch", "blaze", "breeze")
TYPE_VOCAB = ["zombie", "skeleton", "spider", "creeper", "zombified_piglin",
              "blaze", "wither_skeleton", "hoglin", "piglin", "other"]
NT = len(TYPE_VOCAB)
DIST_CENTERS = (1.0, 3.0, 5.0, 8.0, 14.0)
DIST_SIGMA = 2.0


def _clip(v, lo, hi):
    return max(lo, min(hi, v))


def _is_ranged(t):
    return t.rsplit(".", 1)[-1] in RANGED


def hostiles_of(obs):
    hs = []
    for e in obs.get("entities", []):
        if "health" not in e:
            continue
        if e.get("hostile") or e.get("targetingPlayer"):
            hs.append(e)
    hs.sort(key=lambda e: e["dist"])
    return hs


def type_id(t):
    base = t.rsplit(".", 1)[-1]   # "entity.minecraft.zombie" -> "zombie"
    for i, name in enumerate(TYPE_VOCAB[:-1]):
        if name == base:
            return i
    return NT - 1  # "other"


def encode_egt(obs):
    """obs -> (globals[GE], ent_feats[K, EF], type_ids[K], n_live).

    Zero-padded entity slots beyond n_live (mask covers them downstream).
    """
    p = obs["player"]
    hs = hostiles_of(obs)
    px, py, pz = p["pos"]["x"], p["pos"]["y"], p["pos"]["z"]
    pvx, pvz = p["vel"]["x"], p["vel"]["z"]
    yaw = np.deg2rad(p.get("yaw", 0.0))
    fx, fz = -np.sin(yaw), np.cos(yaw)   # MC facing unit vector in (x,z)

    ux = uz = 0.0
    lit = aiming = targeting = 0.0
    hp_sum = 0.0
    nd = 99.0
    ent = np.zeros((K, EF), dtype=np.float32)
    tid = np.full(K, NT - 1, dtype=np.int64)

    for k, e in enumerate(hs[:K]):
        dx, dz = e["pos"]["x"] - px, e["pos"]["z"] - pz
        d = max(e["dist"], 1e-6)
        ex, ez = dx / d, dz / d                       # unit bearing
        ux += ex; uz += ez
        hp_sum += e["health"]
        nd = min(nd, d)
        t = e["type"]
        fuz = e.get("fuse", 0.0)
        aim = 1.0 if e.get("aiming") else 0.0
        tgt = 1.0 if e.get("targetingPlayer") else 0.0
        lit += fuz > 0.4
        aiming += aim
        targeting += tgt

        # bearing relative to facing: cos/sin -> rotation invariant
        cosb = fx * ex + fz * ez
        sinb = fx * ez - fz * ex                       # 2D cross: left/right
        # velocity of entity relative to player, split radial/tangent
        rvx, rvz = e["vel"]["x"] - pvx, e["vel"]["z"] - pvz
        vrad = rvx * ex + rvz * ez                     # +ve = receding
        vtan = rvx * ez - rvz * ex
        spd = np.hypot(e["vel"]["x"], e["vel"]["z"])

        row = ent[k]
        row[0] = _clip(d / 20.0, 0, 1)
        for i, c in enumerate(DIST_CENTERS):
            row[1 + i] = np.exp(-((d - c) ** 2) / (2 * DIST_SIGMA ** 2))
        row[6] = sinb
        row[7] = cosb
        row[8] = _clip((e["pos"]["y"] - py) / 4.0, -1, 1)
        row[9] = _clip(vrad / 5.0, -1, 1)
        row[10] = _clip(vtan / 5.0, -1, 1)
        row[11] = _clip(spd / 5.0, 0, 1)
        row[12] = _clip(e["health"] / max(e.get("maxHealth", 20.0), 1.0), 0, 1)
        row[13] = _clip(e.get("playerHits", 0) / 6.0, 0, 1)
        row[14] = tgt
        row[15] = 1.0 if _is_ranged(t) else 0.0
        row[16] = _clip(fuz, 0, 1)
        row[17] = aim
        tid[k] = type_id(t)

    n = min(len(hs), K)
    encirc = 1.0 - np.hypot(ux, uz) / n if n > 0 else 0.0

    g = np.zeros(GE, dtype=np.float32)
    g[0] = _clip(p["health"] / 20.0, 0, 1)
    g[1] = _clip(p.get("attackCooldown", 0), 0, 1)
    g[2] = _clip(p.get("lastAttackedTicks", 0) / 60.0, 0, 1)
    g[3] = 1.0 if p.get("usingItem") else 0.0
    g[4] = _clip(p.get("useTicks", 0) / 40.0, 0, 1)
    g[5] = _clip(p.get("offhandPct", 1), 0, 1)
    g[6] = 1.0 if p.get("onGround") else 0.0
    g[7] = _clip(p.get("food", 20) / 20.0, 0, 1)
    g[8] = _clip(len(hs) / 9.0, 0, 1)
    g[9] = _clip(encirc, 0, 1)
    g[10] = _clip(lit / 3.0, 0, 1)
    g[11] = _clip(aiming / 4.0, 0, 1)
    g[12] = _clip(targeting / 9.0, 0, 1)
    g[13] = _clip(hp_sum / 200.0, 0, 1)
    g[14] = _clip(nd / 20.0, 0, 1)
    g[15] = 1.0 if p.get("sprinting") else 0.0
    return g, ent, tid, n


# ------------------------------------------------------------------ model --

class PolicyEGT(nn.Module):
    """forward() takes per-step features + the previous GRU hidden and returns
    the action tensor [mv(2) | scores(K) | flags(4)], the value estimate, and
    the new hidden state — so callers keep `h` per env per episode."""

    def __init__(self):
        super().__init__()
        self.typeEmb = nn.Embedding(NT, TE)
        self.ent1 = nn.Linear(EF + TE, D_E)
        self.ent2 = nn.Linear(D_E, D_E)
        self.g1 = nn.Linear(GE, D_E)
        self.g2 = nn.Linear(D_E, D_E)
        self.attq = nn.Linear(D_E, D_E, bias=False)
        self.gru = nn.GRUCell(3 * D_E, D_H)
        self.ptr1 = nn.Linear(D_H + D_E, 16)
        self.ptr2 = nn.Linear(16, 1)
        self.mvH = nn.Linear(D_H, 2)
        self.flH = nn.Linear(D_H, 4)
        self.vH = nn.Linear(D_H, 1)
        self.log_std = nn.Parameter(torch.full((2,), -1.0))

    def forward(self, g, ef, tid, n_live, h):
        """g:(B,GE) ef:(B,K,EF) tid:(B,K) n_live:(B,) h:(B,D_H)
        -> (act(B,2+K+4), v(B), h'(B,D_H))"""
        B = g.shape[0]
        emb = self.typeEmb(tid)                        # (B,K,TE)
        e = torch.tanh(self.ent1(torch.cat([ef, emb], -1)))
        e = torch.tanh(self.ent2(e))                   # (B,K,D_E)
        gv = torch.tanh(self.g2(torch.tanh(self.g1(g))))   # (B,D_E)
        q = self.attq(gv).unsqueeze(1)                 # (B,1,D_E)
        logits = (e @ q.transpose(1, 2)).squeeze(-1) / np.sqrt(D_E)  # (B,K)
        mask = torch.arange(K).unsqueeze(0) >= n_live.unsqueeze(1)
        logits = logits.masked_fill(mask, -1e9)
        w = torch.softmax(logits, dim=-1).unsqueeze(1)  # (B,1,K)
        attn = (w @ e).squeeze(1)                      # (B,D_E)
        alive = (n_live > 0).float().unsqueeze(1)
        mean_e = (e * (~mask).float().unsqueeze(-1)).sum(1) / \
            n_live.clamp(min=1).unsqueeze(1) * alive
        fused = torch.cat([gv, attn, mean_e], dim=-1)  # (B,3*D_E)
        h2 = self.gru(fused, h)                        # (B,D_H)

        hk = h2.unsqueeze(1).expand(-1, K, -1)
        sc = self.ptr2(torch.tanh(self.ptr1(
            torch.cat([hk, e], dim=-1)))).squeeze(-1)  # (B,K)
        act = torch.cat([self.mvH(h2), sc, self.flH(h2)], dim=-1)
        return act, self.vH(h2).squeeze(-1), h2

    # ---- flat packing (order matters — spec_of_egt consumes it) ----
    def flat(self):
        parts = []
        parts.append(self.typeEmb.weight.detach().cpu().numpy().ravel())
        for l in (self.ent1, self.ent2, self.g1, self.g2, self.attq):
            parts.append(l.weight.detach().cpu().numpy().ravel())
            if l.bias is not None:
                parts.append(l.bias.detach().cpu().numpy().ravel())
        for name in ("weight_ih", "weight_hh", "bias_ih", "bias_hh"):
            parts.append(getattr(self.gru, name).detach().cpu().numpy().ravel())
        for l in (self.ptr1, self.ptr2, self.mvH, self.flH, self.vH):
            parts.append(l.weight.detach().cpu().numpy().ravel())
            parts.append(l.bias.detach().cpu().numpy().ravel())
        parts.append(self.log_std.detach().cpu().numpy().ravel())
        return np.concatenate(parts)


def _pop(vec, off, shape):
    n = int(np.prod(shape))
    return vec[off:off + n].reshape(shape), off + n


def unpack_egt(vec):
    """flat -> dict of named arrays; inverse of PolicyEGT.flat()."""
    off = 0
    d = {}
    d["typeEmb"], off = _pop(vec, off, (NT, TE))
    d["ent1w"], off = _pop(vec, off, (D_E, EF + TE))
    d["ent1b"], off = _pop(vec, off, (D_E,))
    d["ent2w"], off = _pop(vec, off, (D_E, D_E))
    d["ent2b"], off = _pop(vec, off, (D_E,))
    d["g1w"], off = _pop(vec, off, (D_E, GE))
    d["g1b"], off = _pop(vec, off, (D_E,))
    d["g2w"], off = _pop(vec, off, (D_E, D_E))
    d["g2b"], off = _pop(vec, off, (D_E,))
    d["attq"], off = _pop(vec, off, (D_E, D_E))
    d["wih"], off = _pop(vec, off, (3 * D_H, 3 * D_E))
    d["whh"], off = _pop(vec, off, (3 * D_H, D_H))
    d["bih"], off = _pop(vec, off, (3 * D_H,))
    d["bhh"], off = _pop(vec, off, (3 * D_H,))
    d["ptr1w"], off = _pop(vec, off, (16, D_H + D_E))
    d["ptr1b"], off = _pop(vec, off, (16,))
    d["ptr2w"], off = _pop(vec, off, (1, 16))
    d["ptr2b"], off = _pop(vec, off, (1,))
    d["mvw"], off = _pop(vec, off, (2, D_H))
    d["mvb"], off = _pop(vec, off, (2,))
    d["flw"], off = _pop(vec, off, (4, D_H))
    d["flb"], off = _pop(vec, off, (4,))
    d["vw"], off = _pop(vec, off, (1, D_H))
    d["vb"], off = _pop(vec, off, (1,))
    d["log_std"], off = _pop(vec, off, (2,))
    return d


def spec_of_egt(vec):
    """flat params -> params.net JSON for NetPolicy (layout 'egt')."""
    d = unpack_egt(vec)
    def layer(w, b):
        return {"shape": [int(w.shape[1]), int(w.shape[0])],
                "w": np.round(w, 5).tolist(),
                "b": np.round(b, 5).tolist()}
    return {
        "layout": "egt",
        "typeVocab": TYPE_VOCAB,
        "typeEmb": np.round(d["typeEmb"], 5).tolist(),
        "ent": [layer(d["ent1w"], d["ent1b"]), layer(d["ent2w"], d["ent2b"])],
        "glob": [layer(d["g1w"], d["g1b"]), layer(d["g2w"], d["g2b"])],
        "attq": np.round(d["attq"], 5).tolist(),
        "gru": {"wih": np.round(d["wih"], 5).tolist(),
                "whh": np.round(d["whh"], 5).tolist(),
                "bih": np.round(d["bih"], 5).tolist(),
                "bhh": np.round(d["bhh"], 5).tolist()},
        "ptr": [layer(d["ptr1w"], d["ptr1b"]), layer(d["ptr2w"], d["ptr2b"])],
        "heads": [layer(d["mvw"], d["mvb"]),
                  layer(d["flw"], d["flb"]),
                  layer(d["vw"], d["vb"])],
        "logStd": np.round(d["log_std"], 5).tolist(),
    }


def load_egt(pol, vec):
    """Load a flat egt vec into a PolicyEGT module."""
    d = unpack_egt(vec)
    pol.typeEmb.weight.data = torch.tensor(d["typeEmb"])
    for l, wn, bn in ((pol.ent1, "ent1w", "ent1b"), (pol.ent2, "ent2w", "ent2b"),
                      (pol.g1, "g1w", "g1b"), (pol.g2, "g2w", "g2b"),
                      (pol.attq, "attq", None),
                      (pol.ptr1, "ptr1w", "ptr1b"), (pol.ptr2, "ptr2w", "ptr2b"),
                      (pol.mvH, "mvw", "mvb"), (pol.flH, "flw", "flb"),
                      (pol.vH, "vw", "vb")):
        l.weight.data = torch.tensor(d[wn], dtype=torch.float32)
        if bn is not None:
            l.bias.data = torch.tensor(d[bn], dtype=torch.float32)
    pol.gru.weight_ih.data = torch.tensor(d["wih"], dtype=torch.float32)
    pol.gru.weight_hh.data = torch.tensor(d["whh"], dtype=torch.float32)
    pol.gru.bias_ih.data = torch.tensor(d["bih"], dtype=torch.float32)
    pol.gru.bias_hh.data = torch.tensor(d["bhh"], dtype=torch.float32)
    pol.log_std.data = torch.tensor(d["log_std"], dtype=torch.float32)


EGT_FLAT_DIM = int(np.sum([np.prod(s) for s in (
    (NT, TE), (D_E, EF + TE), (D_E,), (D_E, D_E), (D_E,), (D_E, GE), (D_E,),
    (D_E, D_E), (D_E,), (D_E, D_E),
    (3 * D_H, 3 * D_E), (3 * D_H, D_H), (3 * D_H,), (3 * D_H,),
    (16, D_H + D_E), (16,), (1, 16), (1,), (2, D_H), (2,), (4, D_H), (4,),
    (1, D_H), (1,), (2,))]))
