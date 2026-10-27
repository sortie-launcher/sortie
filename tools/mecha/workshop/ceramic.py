#!/usr/bin/env python3
"""Glazed teapot artisan and angular kiln porcelain; reproducible eight-pose art.

Run from any directory. Production receives only sheets, role masks and catalog;
assembled review boards go to ~/.cache/sortie-mecha-lab.
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
FAMILY = "ceramic"
ASSETS = ROOT / "mecha/src/main/assets/mecha" / FAMILY
CACHE = Path.home() / ".cache/sortie-mecha-lab"
PRIMARY = tuple(map(hexc, ("9EAA9D", "E2E6D4", "FFFFEA")))
SECONDARY = tuple(map(hexc, ("285D70", "438FA1", "8BC8CE")))
GLASS = hexc("263E49")
EYE = hexc("FFCF7A")
HOT = hexc("FFF2C9")
ORDER = ("LEGS", "BODY", "ARMS", "HEAD")


def eyes(c, f, y=17):
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
    # Porcelain lid, open handle and pouring lip belong to the head silhouette.
    c.piece(rect(29, 21, 35, 26), STEEL_RAMP)
    c.piece(ellipse(43, 16, 6, 7) - ellipse(43, 16, 3, 4), SECONDARY)
    c.piece(poly([(21, 14), (16, 11), (14, 11), (16, 18), (23, 21)]), PRIMARY)
    c.piece(ellipse(31, 17, 11, 8), PRIMARY)
    c.piece(ellipse(31, 10, 12, 3), SECONDARY)
    c.piece(circle(31, 6, 2), PRIMARY)
    c.fill(line((24, 14), (27, 13)), PRIMARY[2])
    eyes(c, f)
    c.fill(line((29, 22), (34, 22)), SECONDARY[0])


def head_2(c, f):
    c.piece(rect(29, 20, 35, 26), STEEL_RAMP)
    c.piece(poly([(22, 11), (28, 5), (36, 5), (42, 11), (41, 22), (35, 25), (25, 23)]), PRIMARY)
    c.piece(poly([(22, 11), (28, 5), (29, 12), (25, 16)]), SECONDARY)
    c.piece(poly([(35, 5), (41, 10), (40, 13), (35, 11)]), SECONDARY)
    c.fill(poly([(25, 14), (39, 14), (39, 21), (25, 21)]), GLASS)
    eyes(c, f, 16)
    c.fill(line((29, 8), (33, 8)), PRIMARY[2])
    for y in (15, 18, 21):
        c.fill(rect(42, y, 44, y), SECONDARY[0])


def body_1(c, f):
    c.piece(rect(27, 40, 37, 46), STEEL_RAMP)
    c.piece(ellipse(32, 34, 13, 11), PRIMARY)
    c.piece(ellipse(32, 26, 10, 2), SECONDARY)
    c.piece(rrect(24, 42, 40, 45), SECONDARY)
    # Cobalt flower inlay is painted ceramic, not a face or light.
    for dx, dy in ((0, -3), (-3, 0), (3, 0), (0, 3)):
        c.fill(circle(32 + dx, 35 + dy, 2), SECONDARY[1])
    c.fill(circle(32, 35, 2), SECONDARY[2])
    c.dot(32, 35, SECONDARY[0])
    c.fill(line((23, 30), (23, 35)), PRIMARY[2])
    c.piece(circle(42, 37, 2), STEEL_RAMP)


def body_2(c, f):
    c.piece(rect(27, 40, 37, 46), STEEL_RAMP)
    c.piece(poly([(24, 26), (39, 26), (44, 32), (41, 43), (25, 45), (20, 37)]), PRIMARY)
    c.piece(poly([(24, 27), (29, 27), (26, 41), (23, 38)]), SECONDARY)
    c.piece(poly([(37, 28), (41, 32), (39, 42), (35, 43)]), SECONDARY)
    c.piece(poly([(29, 31), (35, 30), (38, 34), (36, 39), (29, 40), (27, 36)]), (SHADOW, GLASS, STEEL))
    c.fill(line((29, 34), (33, 32)), HI)
    c.fill(rect(30, 37, 34, 38), EYE)
    c.dot(32, 37, HOT)
    for x in (28, 31, 34, 37):
        c.fill(rect(x, 42, x + 1, 43), OUT)


def arm_pose(f, side):
    sign = -1 if side == 0 else 1
    swing = (0, 0, -2, -1, 2, 1, 1, -7)[f] * sign
    return (19 if side == 0 else 45), (14 if side == 0 else 50), 39 + swing


def arms_1(c, f):
    for side in (0, 1):
        sx, hx, hy = arm_pose(f, side)
        c.piece(capsule((sx, 29), (hx, hy - 3), 2), STEEL_RAMP)
        c.piece(ellipse(sx, 30, 4, 5), PRIMARY)
        c.piece(ellipse(hx, hy - 1, 4, 5), SECONDARY)
        if side == 0:
            c.piece(rect(hx - 2, hy + 3, hx + 2, hy + 7), PRIMARY)
            c.fill(line((hx - 2, hy + 5), (hx + 2, hy + 5)), SECONDARY[0])
        else:
            c.piece(circle(hx, hy + 4, 3) - rect(hx - 1, hy + 3, hx + 3, hy + 6), PRIMARY)


def arms_2(c, f):
    for side in (0, 1):
        sx, hx, hy = arm_pose(f, side)
        c.piece(capsule((sx, 29), (hx, hy), 2), STEEL_RAMP)
        c.piece(poly([(sx - 4, 26), (sx + 3, 26), (sx + 4, 32), (sx - 3, 34)]), SECONDARY)
        c.piece(poly([(hx - 3, hy - 4), (hx + 3, hy - 4), (hx + 4, hy + 2), (hx, hy + 5), (hx - 4, hy + 2)]), PRIMARY)
        c.fill(rect(hx - 2, hy - 2, hx + 2, hy - 1), SECONDARY[1])
        c.fill(rect(hx - 1, hy + 2, hx + 1, hy + 5), OUT)


def legs_1(c, f):
    for side, hip in enumerate((26, 38)):
        out, lift, bend = leg_state(f, side)
        sign = -1 if side == 0 else 1
        x, floor = hip + sign * (1 + out), 60 - lift
        c.piece(capsule((hip, 44), (x, floor - 3), 2), STEEL_RAMP)
        c.piece(ellipse(x, 50 - lift // 2, 4, 5), SECONDARY)
        c.piece(poly([(x - 3, floor - 6), (x + 3, floor - 6), (x + 5, floor - 1), (x + 5, floor), (x - 5, floor), (x - 5, floor - 1)]), PRIMARY)
        c.fill(line((x - 4, floor - 1), (x + 4, floor - 1)), SECONDARY[1])


def legs_2(c, f):
    for side, hip in enumerate((26, 38)):
        out, lift, bend = leg_state(f, side)
        sign = -1 if side == 0 else 1
        x, floor = hip + sign * (1 + out), 60 - lift
        c.piece(capsule((hip, 44), (x, floor - 3), 2), STEEL_RAMP)
        c.piece(poly([(hip - 3, 45), (hip + 3, 45), (x + 3, floor - 4), (x - 2, floor - 3)]), PRIMARY)
        c.piece(poly([(x - 3, floor - 5), (x + 3, floor - 5), (x + 5, floor), (x - 5, floor)]), SECONDARY)
        c.fill(line((x - 1, 49 - lift // 2), (x, floor - 5)), PRIMARY[0])


PARTS = [
    ("ceramic_head_1", "HEAD", "Glaze teapot", head_1),
    ("ceramic_head_2", "HEAD", "Kiln porcelain", head_2),
    ("ceramic_body_1", "BODY", "Glaze flower vessel", body_1),
    ("ceramic_body_2", "BODY", "Kiln firing chamber", body_2),
    ("ceramic_arms_1", "ARMS", "Glaze potter paddles", arms_1),
    ("ceramic_arms_2", "ARMS", "Kiln tile clamps", arms_2),
    ("ceramic_legs_1", "LEGS", "Glaze pedestal cups", legs_1),
    ("ceramic_legs_2", "LEGS", "Kiln porcelain stilts", legs_2),
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
    d.text((16, 12), "CERAMIC / Glaze artisan and Kiln porcelain / eight shared poses", fill="#18232D")
    for row, (label, variants) in enumerate((("Glaze", (1, 1, 1, 1)), ("Kiln", (2, 2, 2, 2)), ("Mixed rig", (1, 2, 1, 2)))):
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
