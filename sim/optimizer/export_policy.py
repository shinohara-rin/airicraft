"""Export a trained egt checkpoint (.npy flat params) as a deployable
neural-policy spec for the real client.

Writes config/airicraft/neural-policy.json:
    {"enabled": true, "spec": { ... spec_of_egt ... }}

The client-side NeuralCombatController (reflex/neural) loads this file at
startup and, when active, drives combat tactics inside the survival reflex.

Usage:
    python export_policy.py /path/to/pol_best.npy -o neural-policy.json
    cp neural-policy.json run/config/airicraft/neural-policy.json
"""
import argparse
import json

import numpy as np

import policy_egt as PE


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("ckpt", help=".npy flat egt params (e.g. pol_best.npy)")
    ap.add_argument("-o", "--out", default="neural-policy.json")
    ap.add_argument("--d-e", type=int, default=PE.D_E)
    ap.add_argument("--d-h", type=int, default=PE.D_H)
    args = ap.parse_args()

    vec = np.load(args.ckpt)
    spec = PE.spec_of_egt(vec, d_e=args.d_e, d_h=args.d_h)
    doc = {"enabled": True, "spec": spec}
    with open(args.out, "w") as f:
        json.dump(doc, f)
    print(f"wrote {args.out} ({len(vec)} params, d_e={args.d_e}, d_h={args.d_h})")


if __name__ == "__main__":
    main()
