"""Distill AST target-selection behavior into the ptr scorer head.

Each expert tick logs `lookEntity` — the entity the AST policy actually
targeted. Hostile slots are ordered by dist (encode()), so the label is the
slot index of that entity id (or -1 when it is not among the K=6 nearest
hostiles / no hostile present — those ticks are skipped as labels but still
usable for the trunk).

We train: ctx = tanh(trunk(stacked_obs)) [BC-warm trunk, trainable],
score_k = p2(tanh(p1([ctx, slot_k_feats]))), cross-entropy over the K slots.
Labels for slots beyond len(hs) are masked out of the softmax via -1e9.
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
OUT = Path("distilled_ptr.npy")
EPOCHS = 4
BATCH = 1024
LR = 3e-4


def load_labels():
    """Yield (stacked_input[K*F slot feats of latest frame], label) per tick."""
    xs, ys = [], []
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
                    ent = (d.get("intent") or {}).get("lookEntity")
                    hs = hostiles_of(d["obs"])[:K]
                    if ent is None or not hs:
                        n_skip += 1
                        continue
                    label = -1
                    for k, e in enumerate(hs):
                        if e.get("id") == ent:
                            label = k
                            break
                    if label < 0:
                        n_skip += 1
                        continue
                    xs.append((stack_encode(hist), len(hs)))
                    ys.append(label)
                    n_tot += 1
    print(f"labels: {n_tot} usable ticks, {n_skip} skipped "
          f"(no/near-miss target)")
    return xs, ys


def main():
    xs, ys = load_labels()
    pol = PolicyPtr()
    # warm trunk from bc_v20 if present
    bc = Path("bc_v20.npy")
    if bc.exists():
        pol.load_trunk(np.load(bc))
        print("trunk warm-started from bc_v20.npy")

    opt = torch.optim.Adam(pol.parameters(), lr=LR)
    lossf = nn.CrossEntropyLoss(ignore_index=-100)

    n = len(xs)
    idx = np.arange(n)
    for ep in range(EPOCHS):
        np.random.shuffle(idx)
        tot = corr = cnt = 0
        for s in range(0, n, BATCH):
            bi = idx[s:s + BATCH]
            xb = torch.from_numpy(np.stack([xs[i][0] for i in bi])).float()
            nhs = np.array([xs[i][1] for i in bi])
            yb = torch.from_numpy(np.array([ys[i] for i in bi])).long()

            ctx = torch.tanh(pol.h2(torch.tanh(pol.h1(xb))))
            latest = xb[:, (STACK - 1) * FRAME:(STACK - 1) * FRAME + FRAME]
            sf = latest[:, G:G + K * F].reshape(-1, K, F)
            ctxk = ctx.unsqueeze(1).expand(-1, K, -1)
            sc = pol.p2(torch.tanh(pol.p1(
                torch.cat([ctxk, sf], dim=-1)))).squeeze(-1)
            # mask slots beyond the tick's hostile count
            mask = torch.arange(K).unsqueeze(0) >= torch.from_numpy(nhs).unsqueeze(1)
            sc = sc.masked_fill(mask, -1e9)
            loss = lossf(sc, yb)
            opt.zero_grad(); loss.backward(); opt.step()
            tot += loss.item() * len(bi)
            corr += (sc.argmax(1) == yb).sum().item()
            cnt += len(bi)
        print(f"epoch {ep}: ce={tot/cnt:.4f} acc={corr/cnt:.3f}")

    np.save(OUT, pol.flat())
    print(f"saved {OUT}")


if __name__ == "__main__":
    main()
