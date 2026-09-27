"""Distill AST expert behavior into the egt (entity-set + GRU) policy.

Same labels as distill_ptr.py (lookEntity -> target slot among the first
K dist-sorted hostiles; flags in deployment decode order; moveDir MSE),
but the recurrent policy needs its hidden state rolled along each
episode, so training is per-episode TBPTT: forward tick by tick through
a CHUNK-tick window, backward once per window, carry h (detached) onward.

Output is a flat egt vec -> --initempty for train_rl --egt.
"""
import glob
import json
import sys
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn

sys.path.insert(0, str(Path(__file__).parent))
import policy_egt as PE  # noqa: E402

TRAJ_GLOBS = ["traj_v2/*.jsonl", "traj_me11/*.jsonl"]
OUT = Path("distilled_egt.npy")
EPOCHS = 6
CHUNK = 64          # TBPTT window (ticks)
LR = 3e-4
OBS_DELAY = 3       # ActionProfile.obsDelayTicks: intent_t was decided on obs_{t-3}


def load_episodes():
    eps = []
    n_skip = n_tot = 0
    for pat in TRAJ_GLOBS:
        for fp in sorted(glob.glob(pat)):
            raw = []   # (enc, intent) per recorded tick, in order
            with open(fp) as f:
                for line in f:
                    d = json.loads(line)
                    if d.get("type") != "tick":
                        continue
                    raw.append((PE.encode_egt(d["obs"]),
                                PE.hostiles_of(d["obs"]),
                                d.get("intent") or {}))
            # intent_t came from decide(obs_{t-3}) — shift labels forward
            seq = []
            for i, ((g, ef, tid, n), hs, _it0) in enumerate(raw):
                j = i + OBS_DELAY
                if j >= len(raw):
                    break
                it = raw[j][2]
                ent = it.get("lookEntity")
                label = -1
                if ent is not None:
                    for k, e in enumerate(hs[:PE.K]):
                        if e.get("id") == ent:
                            label = k
                            break
                # flag order matches NetPolicy decode: attack, use, sprint, jump
                flags = np.array([
                    1.0 if it.get("attack") else 0.0,
                    1.0 if it.get("useHand") == "off" else 0.0,
                    1.0 if it.get("sprint") else 0.0,
                    1.0 if it.get("jump") else 0.0,
                ], dtype=np.float32)
                mdir = np.array(it.get("moveDir") or [0.0, 0.0],
                                dtype=np.float32)
                nm = np.linalg.norm(mdir)
                if nm > 1e-6:
                    mdir = mdir / nm
                if label < 0:
                    n_skip += 1
                else:
                    n_tot += 1
                seq.append((g, ef, tid, n, label, mdir, flags))
            if len(seq) >= 8:
                eps.append(seq)
    print(f"episodes: {len(eps)} usable, {n_tot} labeled ticks, {n_skip} skipped")
    return eps


def main():
    eps = load_episodes()
    pol = PE.PolicyEGT()
    opt = torch.optim.Adam(pol.parameters(), lr=LR)
    cef = nn.CrossEntropyLoss()
    bcef = nn.BCEWithLogitsLoss()
    msef = nn.MSELoss()
    kidx = torch.arange(PE.K)

    for ep in range(EPOCHS):
        order = np.random.permutation(len(eps))
        tce = tacc = tfl = tmv = cnt = 0
        for ei in order:
            seq = eps[ei]
            h = torch.zeros(1, PE.D_H)
            for s in range(0, len(seq), CHUNK):
                chunk = seq[s:s + CHUNK]
                loss = None
                for (g, ef, tid, n, label, mdir, flags) in chunk:
                    out, _v, h = pol(
                        torch.tensor(g).unsqueeze(0),
                        torch.tensor(ef).unsqueeze(0),
                        torch.tensor(tid).unsqueeze(0),
                        torch.tensor([n]), h)
                    mvL = out[:, :2]
                    scL = out[:, 2:2 + PE.K]
                    flL = out[:, 2 + PE.K:]
                    l = bcef(flL, torch.tensor(flags).unsqueeze(0)) \
                        + msef(mvL, torch.tensor(mdir).unsqueeze(0))
                    if label >= 0:
                        scM = scL.masked_fill(kidx >= n, -1e9)
                        l = l + cef(scM, torch.tensor([label]))
                        tce += cef(scM, torch.tensor([label])).item()
                        tacc += int(scM.argmax(1).item() == label)
                        cnt += 1
                    tfl += bcef(flL, torch.tensor(flags).unsqueeze(0)).item()
                    tmv += msef(mvL, torch.tensor(mdir).unsqueeze(0)).item()
                    loss = l if loss is None else loss + l
                opt.zero_grad()
                loss.backward()
                nn.utils.clip_grad_norm_(pol.parameters(), 0.5)
                opt.step()
                h = h.detach()
        print(f"epoch {ep}: ce={tce/max(cnt,1):.4f} acc={tacc/max(cnt,1):.3f} "
              f"fl={tfl/max(cnt,1):.4f} mv={tmv/max(cnt,1):.4f}", flush=True)

    np.save(OUT, pol.flat())
    print(f"saved {OUT}")


if __name__ == "__main__":
    main()
