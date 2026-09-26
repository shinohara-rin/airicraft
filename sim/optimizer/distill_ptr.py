"""Distill AST behavior into the full ptr policy (target + flags + moveDir).

Per tick the expert intent records:
  lookEntity -> target slot label (dist-sorted hostiles)
  moveDir    -> continuous move vector (x, z)
  attack/use-shield/sprint/jump signals -> 4 flag bits (deployment decode order)

Losses: CE over K slots (masked to live hostiles), BCE on the 4 flag logits,
MSE on the 2-dim move head. Trunk BC-warm, everything trainable.
"""
import glob
import json
import sys
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn

sys.path.insert(0, str(Path(__file__).parent))
from optimize_net import encode, hostiles_of, stack_encode, K, F, G, FRAME, STACK  # noqa: E402
from train_rl import PolicyPtr  # noqa: E402

TRAJ_GLOBS = ["traj_v2/*.jsonl", "traj_me11/*.jsonl"]
OUT = Path("distilled_ptr_full.npy")
EPOCHS = 6
BATCH = 1024
LR = 3e-4


def load_labels():
    xs, tgt, mv, fl = [], [], [], []
    n_skip = n_tot = 0
    for pat in TRAJ_GLOBS:
        for fp in sorted(glob.glob(pat)):
            hist = []
            with open(fp) as f:
                for line in f:
                    d = json.loads(line)
                    if d.get("type") != "tick":
                        continue
                    fr = encode(d["obs"])
                    hist.append(fr)
                    if len(hist) > STACK:
                        hist.pop(0)
                    it = d.get("intent") or {}
                    ent = it.get("lookEntity")
                    hs = hostiles_of(d["obs"])[:K]
                    label = -1
                    if ent is not None:
                        for k, e in enumerate(hs):
                            if e.get("id") == ent:
                                label = k
                                break
                    if label < 0:
                        n_skip += 1
                        continue
                    # flag order matches NetPolicy decode: attack, use, sprint, jump
                    flags = np.array([
                        1.0 if it.get("attack") else 0.0,
                        1.0 if it.get("useHand") == "off" else 0.0,
                        1.0 if it.get("sprint") else 0.0,
                        1.0 if it.get("jump") else 0.0,
                    ], dtype=np.float32)
                    mdir = np.array(it.get("moveDir") or [0.0, 0.0],
                                    dtype=np.float32)
                    n = np.linalg.norm(mdir)
                    if n > 1e-6:
                        mdir = mdir / n
                    xs.append((stack_encode(hist), len(hs)))
                    tgt.append(label); mv.append(mdir); fl.append(flags)
                    n_tot += 1
    print(f"labels: {n_tot} usable ticks, {n_skip} skipped")
    return xs, tgt, mv, fl


def main():
    xs, tgt, mv, fl = load_labels()
    pol = PolicyPtr()
    bc = Path("bc_v20.npy")
    if bc.exists():
        pol.load_trunk(np.load(bc))
        print("trunk warm-started from bc_v20.npy")

    opt = torch.optim.Adam(pol.parameters(), lr=LR)
    cef = nn.CrossEntropyLoss()
    bcef = nn.BCEWithLogitsLoss()
    msef = nn.MSELoss()

    n = len(xs)
    idx = np.arange(n)
    for ep in range(EPOCHS):
        np.random.shuffle(idx)
        tce = tacc = tfl = tmv = cnt = 0
        for s in range(0, n, BATCH):
            bi = idx[s:s + BATCH]
            xb = torch.from_numpy(np.stack([xs[i][0] for i in bi])).float()
            nhs = np.array([xs[i][1] for i in bi])
            yb = torch.from_numpy(np.array([tgt[i] for i in bi])).long()
            mb = torch.from_numpy(np.stack([mv[i] for i in bi])).float()
            fb = torch.from_numpy(np.stack([fl[i] for i in bi])).float()

            out = pol(xb)
            mvL, scL, flL = out[:, :2], out[:, 2:2 + K], out[:, 2 + K:]
            mask = torch.arange(K).unsqueeze(0) >= torch.from_numpy(nhs).unsqueeze(1)
            scL = scL.masked_fill(mask, -1e9)
            loss = cef(scL, yb) + bcef(flL, fb) + msef(mvL, mb)
            opt.zero_grad(); loss.backward(); opt.step()
            tce += cef(scL, yb).item() * len(bi)
            tacc += (scL.argmax(1) == yb).sum().item()
            tfl += bcef(flL, fb).item() * len(bi)
            tmv += msef(mvL, mb).item() * len(bi)
            cnt += len(bi)
        print(f"epoch {ep}: ce={tce/cnt:.4f} acc={tacc/cnt:.3f} "
              f"fl={tfl/cnt:.4f} mv={tmv/cnt:.4f}")

    np.save(OUT, pol.flat())
    print(f"saved {OUT}")


if __name__ == "__main__":
    main()
