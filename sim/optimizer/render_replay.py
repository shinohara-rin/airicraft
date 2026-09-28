"""Render an episode JSONL into a top-down mp4 replay video.

Viewport follows the player; hostiles are colored dots (type-coded), the
player is a white dot with a facing wedge (ring while using/shielding).
HUD shows tick, HP, hostile count, sampled flags and the outcome banner on
the final frame.

Usage: python3 render_replay.py <episode.jsonl> <out.mp4> [--fps 12 --stride 4]
"""

import argparse
import json
import math
from pathlib import Path

import imageio.v2 as imageio
import numpy as np
from PIL import Image, ImageDraw, ImageFont

VIEW_R = 14.0          # blocks of world visible around the player
SIZE = 512
FONT = ImageFont.load_default()

MOB_COLORS = {
    "zombie": (46, 139, 34), "husk": (140, 120, 60), "drowned": (30, 120, 140),
    "zombie_villager": (60, 160, 90), "skeleton": (200, 200, 200),
    "stray": (160, 190, 210), "pillager": (130, 90, 60), "witch": (120, 60, 140),
    "creeper": (80, 200, 60), "spider": (120, 60, 160),
    "cave_spider": (90, 40, 120), "blaze": (255, 160, 30),
    "wither_skeleton": (60, 60, 60), "piglin": (230, 150, 170),
    "piglin_brute": (200, 90, 120), "zoglin": (180, 60, 80),
    "hoglin": (160, 80, 60), "breeze": (120, 200, 230),
    "magma_cube": (200, 60, 20), "ghast": (240, 240, 240),
    "vindicator": (90, 90, 140), "ravager": (110, 80, 50),
    "evoker": (70, 70, 110), "vex": (170, 190, 230),
    "silverfish": (150, 150, 160), "enderman": (30, 30, 40),
    "slime": (110, 210, 110),
}


def base_type(t):
    return t.rsplit(".", 1)[-1] if t else "other"


def _w2s(x, z, cx, cz):
    """world -> screen px (player at center)."""
    s = SIZE / (2 * VIEW_R)
    return (SIZE / 2 + (x - cx) * s, SIZE / 2 + (z - cz) * s)


def draw_frame(rec):
    o = rec["obs"]
    p = o["player"]
    px, py, pz = p["pos"]["x"], p["pos"]["y"], p["pos"]["z"]
    img = Image.new("RGB", (SIZE, SIZE), (28, 30, 34))
    d = ImageDraw.Draw(img)
    # grid every 4 blocks
    s = SIZE / (2 * VIEW_R)
    for gx in range(int(px - VIEW_R) // 4 * 4, int(px + VIEW_R) + 4, 4):
        sx, _ = _w2s(gx, pz, px, pz)
        d.line([(sx, 0), (sx, SIZE)], fill=(40, 43, 48))
    for gz in range(int(pz - VIEW_R) // 4 * 4, int(pz + VIEW_R) + 4, 4):
        _, sy = _w2s(px, gz, px, pz)
        d.line([(0, sy), (SIZE, sy)], fill=(40, 43, 48))

    it = rec.get("intent") or {}
    sm = rec.get("sampled") or {}
    flags = sm.get("flags") or [
        int(it.get("attack", False)), int(bool(it.get("useHand"))),
        int(it.get("sprint", False)), int(it.get("jump", False))]

    for e in o.get("entities", []):
        ep = e["pos"]
        ex, ey = _w2s(ep["x"], ep["z"], px, pz)
        if not (0 <= ex < SIZE and 0 <= ey < SIZE):
            continue
        bt = base_type(e.get("type"))
        if e.get("hostile") or e.get("targetingPlayer"):
            col = MOB_COLORS.get(bt, (220, 80, 80))
            r = 7 if bt in ("ravager", "hoglin", "ghast") else 5
            d.ellipse([ex - r, ey - r, ex + r, ey + r], fill=col,
                      outline=(0, 0, 0))
            mxh = max(e.get("maxHealth", e.get("health", 20)), 1)
            hpf = min(e.get("health", mxh) / mxh, 1.0)
            if hpf < 1.0:
                d.rectangle([ex - 5, ey - r - 4, ex + 5, ey - r - 2],
                            fill=(60, 0, 0))
                d.rectangle([ex - 5, ey - r - 4, ex - 5 + 10 * hpf,
                             ey - r - 2], fill=(60, 220, 60))
            fuz = e.get("fuse") or e.get("litFuse")
            if fuz:
                rr = r + 3 + 4 * min(float(fuz), 1.0)
                d.ellipse([ex - rr, ey - rr, ex + rr, ey + rr],
                          outline=(255, 255, 60), width=2)
        else:
            d.ellipse([ex - 3, ey - 3, ex + 3, ey + 3], fill=(90, 95, 105))

    # player
    yaw = math.radians(p.get("yaw", 0.0))
    fx, fz = -math.sin(yaw), math.cos(yaw)
    cxx, cyy = SIZE / 2, SIZE / 2
    if p.get("usingItem"):
        d.ellipse([cxx - 12, cyy - 12, cxx + 12, cyy + 12],
                  outline=(80, 160, 255), width=3)
    d.ellipse([cxx - 7, cyy - 7, cxx + 7, cyy + 7], fill=(245, 245, 245),
              outline=(0, 0, 0))
    d.line([(cxx, cyy), (cxx + fx * 16, cyy + fz * 16)],
           fill=(255, 255, 255), width=2)
    mv = sm.get("mv") or it.get("moveDir")
    if mv:
        d.line([(cxx, cyy), (cxx + float(mv[0]) * 14, cyy + float(mv[1]) * 14)],
               fill=(255, 200, 40), width=2)

    # HUD
    hp = p.get("health", 20)
    d.rectangle([8, 8, 8 + 104, 8 + 12], fill=(50, 0, 0))
    d.rectangle([8, 8, 8 + 104 * min(hp / 20.0, 1.0), 8 + 12],
                fill=(220, 50, 50))
    nh = sum(1 for e in o.get("entities", [])
             if e.get("hostile") or e.get("targetingPlayer"))
    fl = "".join(c if flags[i] else "-"
                 for i, c in enumerate("ASJU"))
    d.text((8, 26), f"t={o.get('tick', '?')} hp={hp:.0f} mobs={nh} "
                    f"cd={p.get('attackCooldown', 0):.2f} [{fl}]",
           fill=(235, 235, 235), font=FONT)
    return np.asarray(img)


def render(path, out_path, fps=12, stride=4, max_frames=400):
    recs, score = [], None
    for line in open(path):
        try:
            r = json.loads(line)
        except json.JSONDecodeError:
            break
        if r.get("type") == "tick":
            recs.append(r)
        elif r.get("type") == "end":
            score = r.get("score")
    frames = [draw_frame(recs[i]) for i in range(0, len(recs), stride)]
    if score:
        banner = Image.new("RGB", (SIZE, SIZE), (20, 22, 26))
        d = ImageDraw.Draw(banner)
        txt = (f"{score.get('outcome', '?')}   kills={score.get('kills', 0)}"
               f"\nticks={score.get('ticks', 0)}"
               f"  taken={score.get('damageTaken', 0):.1f}"
               f"  dealt={score.get('damageDealt', 0):.1f}")
        d.multiline_text((40, SIZE // 2 - 24), txt, fill=(240, 240, 240),
                         font=FONT, spacing=8)
        frames += [np.asarray(banner)] * int(fps)
    with imageio.get_writer(out_path, fps=fps, codec="libx264",
                            quality=7, macro_block_size=1) as w:
        for fr in frames[:max_frames]:
            w.append_data(fr)
    return out_path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("episode")
    ap.add_argument("out")
    ap.add_argument("--fps", type=int, default=12)
    ap.add_argument("--stride", type=int, default=4)
    a = ap.parse_args()
    render(a.episode, a.out, a.fps, a.stride)


if __name__ == "__main__":
    main()
