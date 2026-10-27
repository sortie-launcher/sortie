#!/usr/bin/env python3
"""Pendulum keeper and windup escapement: two authored mechanical silhouettes.

Shared courier rig and poses; production contains only sheets, paint masks and
catalog. Assembled eight-pose review boards go to ~/.cache/sortie-mecha-lab.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from courier import (OUT, SHADOW, STEEL, HI, STEEL_RAMP, rect, rrect, ellipse,
                     circle, capsule, poly, line, hexc, render_sheet, assemble, leg_state)
from paint_roles import export_part

ROOT = HERE.parents[2]
FAMILY = "clockwork"
ASSETS = ROOT / "mecha/src/main/assets/mecha" / FAMILY
CACHE = Path.home() / ".cache/sortie-mecha-lab"
PRIMARY = tuple(map(hexc, ("936136", "CDA15A", "F3D69B")))
SECONDARY = tuple(map(hexc, ("285D59", "498F80", "94C7AC")))
GLASS = hexc("263D45")
EYE = hexc("FFD682")
HOT = hexc("FFF5D9")
ORDER = ("LEGS", "BODY", "ARMS", "HEAD")


def gear(x, y, r):
    shape = circle(x, y, r)
    shape |= rect(x - 1, y - r - 2, x + 1, y + r + 2)
    shape |= rect(x - r - 2, y - 1, x + r + 2, y + 1)
    for sx in (-1, 1):
        for sy in (-1, 1):
            cx, cy = x + sx * (r - 1), y + sy * (r - 1)
            shape |= rect(cx - 1, cy - 1, cx + 1, cy + 1)
    return shape


def eyes(c, f, y=16):
    for x in (26, 35):
        c.fill(rect(x, y, x + 3, y + 3), OUT)
        if f in (1, 6):
            c.fill(line((x, y + 2), (x + 3, y + 2)), EYE)
        elif f == 7:
            c.fill({(x, y + 2), (x + 1, y + 1), (x + 2, y + 1), (x + 3, y + 2)}, EYE)
        else:
            c.fill(rect(x + 1, y + 1, x + 2, y + 2), EYE)
            c.dot(x + 1, y + 1, HOT)


def head_1(c, f):
    c.piece(rect(29, 21, 35, 26), STEEL_RAMP)
    # The arched clock hood itself houses the face and dial markers.
    c.piece(ellipse(32, 14, 11, 10) | rect(21, 14, 43, 23), SECONDARY)
    c.piece(ellipse(32, 14, 8, 8) | rect(24, 14, 40, 22), PRIMARY)
    c.fill(ellipse(32, 15, 6, 6) | rect(25, 15, 39, 21), GLASS)
    for x in (29, 32, 35):
        c.fill(rect(x, 8, x, 10), OUT)
    c.piece(rect(20, 23, 44, 25), PRIMARY)
    eyes(c, f)
    c.fill(line((31, 12), (32, 14)), HI)
    c.fill(line((32, 14), (35 if f == 1 else 34, 12)), HI)
    c.dot(32, 14, EYE)


def head_2(c, f):
    c.piece(rect(29, 20, 35, 26), STEEL_RAMP)
    # Oversize toothed wheel and visible winding key make the round silhouette.
    c.piece(rect(42, 14, 49, 16), STEEL_RAMP)
    c.piece(ellipse(49, 11, 3, 4) - ellipse(49, 11, 1, 2), SECONDARY)
    c.piece(ellipse(49, 20, 3, 4) - ellipse(49, 20, 1, 2), SECONDARY)
    c.piece(gear(32, 15, 10), PRIMARY)
    c.piece(circle(32, 15, 8), SECONDARY)
    c.fill(rrect(24, 13, 40, 20, 2), GLASS)
    eyes(c, f, 15)
    c.fill(line((30, 22), (34, 22)), PRIMARY[2])
    c.piece(circle(32, 8, 2), PRIMARY)
    c.dot(32, 8, OUT)


def body_1(c, f):
    c.piece(rect(27, 40, 37, 46), STEEL_RAMP)
    c.piece(poly([(24, 26), (40, 26), (42, 30), (40, 44), (24, 44), (22, 30)]), SECONDARY)
    c.piece(rect(24, 28, 27, 41), PRIMARY)
    c.piece(rect(37, 28, 40, 41), PRIMARY)
    c.fill(rect(28, 29, 36, 41), GLASS)
    # The brass pendulum is paintable; the window and reflections stay fixed.
    swing = (0, 1, -1, 0, 1, 0, 0, -1)[f]
    c.fill(line((32, 29), (32 + swing, 37)), PRIMARY[2])
    c.piece(circle(32 + swing, 38, 3), PRIMARY)
    c.dot(31 + swing, 37, PRIMARY[2])
    c.fill(line((29, 30), (29, 32)), HI)
    c.piece(rrect(23, 42, 41, 45), PRIMARY)
    for x in (25, 39):
        c.dot(x, 43, OUT)


def body_2(c, f):
    c.piece(rect(27, 40, 37, 46), STEEL_RAMP)
    c.piece(gear(32, 35, 10), PRIMARY)
    c.piece(ellipse(32, 35, 10, 8), SECONDARY)
    c.fill(ellipse(32, 35, 8, 6), GLASS)
    c.piece(gear(29, 33, 3), STEEL_RAMP)
    c.piece(gear(36, 37, 3), PRIMARY)
    c.piece(circle(29, 33, 1), SECONDARY)
    c.dot(36, 37, OUT)
    end = (29, 31) if f in (1, 3, 5, 7) else (31, 33)
    c.fill(line((29, 33), end), HI)
    c.fill(line((34, 37), (36, 39)), PRIMARY[0])
    c.piece(rect(23, 29, 26, 31), PRIMARY)
    c.piece(rect(38, 39, 41, 41), PRIMARY)
    c.dot(24, 30, HI)
    c.dot(40, 40, HI)


def arm_pose(f, side):
    sign = -1 if side == 0 else 1
    return (19 if side == 0 else 45), (13 if side == 0 else 51), 39 + (0, 0, -2, -1, 2, 1, 1, -7)[f] * sign


def arms_1(c, f):
    for side in (0, 1):
        sx, hx, hy = arm_pose(f, side)
        c.piece(capsule((sx, 29), (hx, hy), 2), STEEL_RAMP)
        c.piece(ellipse(sx, 29, 4, 4), SECONDARY)
        c.piece(capsule((sx, 33), (hx, hy - 1), 2), PRIMARY)
        c.piece(rect(hx - 3, hy - 1, hx + 3, hy + 2), SECONDARY)
        c.piece(circle(hx, hy + 5, 3), PRIMARY)
        c.fill(rect(hx, hy + 4, hx + 3, hy + 6), OUT)
        c.dot(hx - 1, hy + 4, PRIMARY[2])


def arms_2(c, f):
    for side in (0, 1):
        sx, hx, hy = arm_pose(f, side)
        c.piece(capsule((sx, 29), (hx, hy - 1), 2), STEEL_RAMP)
        c.piece(gear(sx, 29, 3), PRIMARY)
        c.piece(circle(sx, 29, 2), SECONDARY)
        c.piece(poly([(hx - 3, hy - 5), (hx + 3, hy - 5), (hx + 4, hy), (hx + 2, hy + 3), (hx - 3, hy + 1)]), SECONDARY)
        c.piece(poly([(hx - 3, hy + 2), (hx - 1, hy + 2), (hx - 1, hy + 6), (hx + 1, hy + 6), (hx + 1, hy + 8), (hx - 3, hy + 7)]), PRIMARY)
        c.piece(poly([(hx + 2, hy + 1), (hx + 4, hy + 2), (hx + 4, hy + 6), (hx + 2, hy + 7)]), PRIMARY)
        c.dot(hx, hy - 2, HI)


def legs_1(c, f):
    for side, hip in enumerate((26, 38)):
        out, lift, bend = leg_state(f, side)
        sign = -1 if side == 0 else 1
        x, floor = hip + sign * (1 + out), 60 - lift
        c.piece(capsule((hip, 44), (x, floor - 3), 2), STEEL_RAMP)
        c.piece(rect(x - 2, floor - 12, x + 2, floor - 4), PRIMARY)
        c.piece(circle(x, floor - 10, 3), SECONDARY)
        c.piece(poly([(x - 3, floor - 4), (x + 2, floor - 4), (x + 5, floor - 2), (x + 5, floor), (x - 4, floor)]), SECONDARY)
        c.fill(line((x - 3, floor), (x + 4, floor)), PRIMARY[1])


def legs_2(c, f):
    for side, hip in enumerate((26, 38)):
        out, lift, bend = leg_state(f, side)
        sign = -1 if side == 0 else 1
        x, floor = hip + sign * (2 + out), 60 - lift
        knee = hip + sign * round(bend)
        c.piece(capsule((hip, 44), (knee, 51 - lift // 2), 2), PRIMARY)
        c.piece(capsule((knee, 51 - lift // 2), (x, floor - 2), 2), STEEL_RAMP)
        c.piece(gear(knee, 51 - lift // 2, 2), SECONDARY)
        c.dot(knee, 51 - lift // 2, HI)
        c.piece(poly([(x - 3, floor - 4), (x + 2, floor - 4), (x + 5, floor - 1), (x + 5, floor), (x - 4, floor)]), PRIMARY)
        c.fill(line((x - 3, floor - 1), (x + 3, floor - 1)), SECONDARY[1])


PARTS = [
    ("clockwork_head_1", "HEAD", "Pendulum arched hood", head_1),
    ("clockwork_head_2", "HEAD", "Windup toothed crown", head_2),
    ("clockwork_body_1", "BODY", "Pendulum keeper case", body_1),
    ("clockwork_body_2", "BODY", "Windup escapement", body_2),
    ("clockwork_arms_1", "ARMS", "Pendulum brass fingers", arms_1),
    ("clockwork_arms_2", "ARMS", "Windup ratchet pincers", arms_2),
    ("clockwork_legs_1", "LEGS", "Pendulum case feet", legs_1),
    ("clockwork_legs_2", "LEGS", "Windup lever ankles", legs_2),
]


def main():
    ASSETS.mkdir(parents=True, exist_ok=True)
    CACHE.mkdir(parents=True, exist_ok=True)
    sheets, catalog = {}, []
    for pid, slot, name, draw in PARTS:
        sheets[pid] = render_sheet(draw)
        export_part(sheets[pid], ASSETS / f"{pid}.png", PRIMARY, SECONDARY, slot=slot)
        catalog.append(dict(id=pid, slot=slot, family=FAMILY, name=name,
                            asset=f"mecha/{FAMILY}/{pid}.png"))
    (ASSETS / "catalog.json").write_text(json.dumps(catalog, indent=2) + "\n")
    board = Image.new("RGBA", (1176, 522), "#E7DFCD")
    d = ImageDraw.Draw(board)
    d.text((16, 12), "CLOCKWORK / Pendulum keeper and Windup escapement / eight shared poses", fill="#18232D")
    for row, (label, variants) in enumerate((("Pendulum", (1, 1, 1, 1)), ("Windup", (2, 2, 2, 2)), ("Mixed rig", (1, 2, 1, 2)))):
        combo = {slot: f"{FAMILY}_{slot.lower()}_{v}" for slot, v in zip(ORDER, variants)}
        y = 40 + row * 160
        d.text((16, y + 10), label, fill="#18232D")
        board.alpha_composite(assemble(sheets, combo, 0), (28, y + 38))
        for f in range(8):
            board.alpha_composite(assemble(sheets, combo, f).resize((128, 128), Image.Resampling.NEAREST), (145 + f * 128, y))
            d.text((196 + f * 128, y + 132), str(f), fill="#18232D")
    board.save(CACHE / f"{FAMILY}-board.png")
    print(f"Authored {FAMILY}: eight base sheets, eight role masks, catalog; board {CACHE / (FAMILY + '-board.png')}")


if __name__ == "__main__":
    main()
