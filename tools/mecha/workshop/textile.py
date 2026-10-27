#!/usr/bin/env python3
"""Loom bobbin tender and patchwork stitch mechanic on the shared eight-pose rig.

Generate production base sheets, explicit paint masks and catalog; review boards
are written outside production to ~/.cache/sortie-mecha-lab.
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
FAMILY = "textile"
ASSETS = ROOT / "mecha/src/main/assets/mecha" / FAMILY
CACHE = Path.home() / ".cache/sortie-mecha-lab"
PRIMARY = tuple(map(hexc, ("814E62", "BF7E91", "EAB8BC")))
SECONDARY = tuple(map(hexc, ("62724D", "A7B57A", "DDE4AC")))
GLASS = hexc("283B42")
EYE = hexc("8DE0D7")
HOT = hexc("DEFFF0")
THREAD = hexc("F6E9CD")
ORDER = ("LEGS", "BODY", "ARMS", "HEAD")


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


def stitches(c, x0, y, count):
    for x in range(x0, x0 + count * 3, 3):
        c.fill(line((x, y), (x + 1, y + 1)), THREAD)


def head_1(c, f):
    c.piece(rect(29, 21, 35, 26), STEEL_RAMP)
    # A wound bobbin with two wide end flanges, not a hat on a generic head.
    c.piece(rrect(23, 8, 41, 22, 2), PRIMARY)
    for y in (10, 12, 14, 20):
        c.fill(line((24, y), (40, y)), PRIMARY[0])
    c.piece(ellipse(32, 7, 12, 3), SECONDARY)
    c.piece(ellipse(32, 23, 11, 2), SECONDARY)
    c.piece(rect(30, 3, 34, 5), STEEL_RAMP)
    c.fill(rrect(24, 15, 40, 20), GLASS)
    eyes(c, f)
    c.fill(line((24, 9), (28, 9)), PRIMARY[2])


def head_2(c, f):
    c.piece(rect(29, 21, 35, 26), STEEL_RAMP)
    c.piece(poly([(22, 11), (29, 8), (39, 10), (43, 15), (41, 23), (23, 24), (20, 19)]), PRIMARY)
    c.piece(poly([(22, 12), (28, 10), (30, 15), (28, 23), (22, 22)]), SECONDARY)
    c.piece(ellipse(36, 8, 7, 3), PRIMARY)
    # Two blunt pin heads are a tiny built-in pincushion crest.
    for x, y in ((33, 3), (39, 4)):
        c.fill(line((x, y + 1), (36, 8)), STEEL)
        c.piece(circle(x, y, 1), SECONDARY)
    eyes(c, f, 16)
    stitches(c, 24, 21, 5)
    c.fill(line((30, 10), (31, 13)), THREAD)


def body_1(c, f):
    c.piece(rect(27, 40, 37, 46), STEEL_RAMP)
    # Open loom chassis: the warp and shuttle are the belly, not a decal.
    c.piece(rect(22, 27, 26, 43), PRIMARY)
    c.piece(rect(38, 27, 42, 43), PRIMARY)
    c.piece(rrect(24, 25, 40, 28), SECONDARY)
    c.piece(rrect(24, 41, 40, 45), SECONDARY)
    c.fill(rect(27, 29, 37, 40), GLASS)
    for x in (28, 31, 34, 37):
        c.fill(line((x, 29), (x, 40)), SECONDARY[2])
    for y in (36, 38, 40):
        c.fill(line((27, y), (37, y)), PRIMARY[1])
    shuttle = 1 if f in (1, 4, 7) else -1
    c.piece(poly([(27 + shuttle, 32), (35 + shuttle, 32), (37 + shuttle, 34), (29 + shuttle, 34)]), PRIMARY)
    c.dot(24, 30, HI)
    c.dot(40, 30, HI)


def body_2(c, f):
    c.piece(rect(27, 40, 37, 46), STEEL_RAMP)
    c.piece(poly([(25, 26), (40, 27), (43, 33), (40, 44), (25, 44), (21, 36)]), PRIMARY)
    c.piece(poly([(24, 28), (32, 28), (30, 36), (23, 36)]), SECONDARY)
    c.piece(poly([(33, 35), (40, 33), (39, 42), (31, 42)]), SECONDARY)
    stitches(c, 24, 35, 3)
    stitches(c, 32, 41, 3)
    c.fill(line((32, 28), (30, 40)), OUT)
    for y in (29, 32, 35, 38):
        c.fill(line((30, y), (32, y + 1)), THREAD)
    c.piece(circle(37, 30, 2), STEEL_RAMP)
    c.fill({(36, 30), (38, 30)}, OUT)
    c.fill(line((25, 40), (28, 41)), PRIMARY[2])


def arm_pose(f, side):
    sign = -1 if side == 0 else 1
    return (19 if side == 0 else 45), (13 if side == 0 else 51), 39 + (0, 0, -2, -1, 2, 1, 1, -7)[f] * sign


def arms_1(c, f):
    for side in (0, 1):
        sx, hx, hy = arm_pose(f, side)
        c.piece(capsule((sx, 29), (hx, hy), 2), STEEL_RAMP)
        c.piece(ellipse(sx, 29, 4, 3), SECONDARY)
        c.piece(rect(hx - 2, hy - 5, hx + 2, hy + 2), PRIMARY)
        for y in range(hy - 4, hy + 2, 2):
            c.fill(line((hx - 2, y), (hx + 2, y)), PRIMARY[0])
        for y in (hy - 6, hy + 3):
            c.piece(rect(hx - 4, y, hx + 4, y + 1), SECONDARY)
        c.piece(poly([(hx - 1, hy + 5), (hx + 2, hy + 4), (hx + 2, hy + 7), (hx, hy + 8)]), PRIMARY)


def arms_2(c, f):
    for side in (0, 1):
        sx, hx, hy = arm_pose(f, side)
        c.piece(capsule((sx, 29), (hx, hy - 1), 2), STEEL_RAMP)
        c.piece(poly([(sx - 3, 26), (sx + 3, 26), (sx + 4, 32), (sx - 4, 31)]), PRIMARY)
        c.piece(rrect(hx - 3, hy - 4, hx + 3, hy + 1), SECONDARY)
        stitches(c, hx - 2, hy - 2, 2)
        if side == 0:
            # Needle ferrule and an actual hollow needle eye.
            c.piece(rect(hx - 2, hy + 2, hx + 2, hy + 4), PRIMARY)
            c.fill(line((hx, hy + 3), (hx, hy + 10)), HI)
            c.dot(hx, hy + 5, OUT)
        else:
            c.piece(circle(hx - 2, hy + 3, 2), PRIMARY)
            c.piece(circle(hx + 2, hy + 3, 2), PRIMARY)
            c.dot(hx - 2, hy + 3, OUT)
            c.dot(hx + 2, hy + 3, OUT)
            c.fill(line((hx - 1, hy + 5), (hx + 3, hy + 9)), HI)
            c.fill(line((hx + 1, hy + 5), (hx - 3, hy + 9)), STEEL)


def legs_1(c, f):
    for side, hip in enumerate((26, 38)):
        out, lift, bend = leg_state(f, side)
        sign = -1 if side == 0 else 1
        x, floor = hip + sign * (1 + out), 60 - lift
        c.piece(capsule((hip, 44), (x, floor - 3), 2), STEEL_RAMP)
        c.piece(rect(x - 3, floor - 9, x + 3, floor - 2), PRIMARY)
        for y in range(floor - 8, floor - 2, 2):
            c.fill(line((x - 3, y), (x + 3, y)), PRIMARY[0])
        c.piece(ellipse(x, floor - 10, 4, 1), SECONDARY)
        c.piece(rrect(x - 5, floor - 2, x + 5, floor), SECONDARY)


def legs_2(c, f):
    for side, hip in enumerate((26, 38)):
        out, lift, bend = leg_state(f, side)
        sign = -1 if side == 0 else 1
        x, floor = hip + sign * (1 + out), 60 - lift
        c.piece(capsule((hip, 44), (x, floor - 3), 2), STEEL_RAMP)
        c.piece(poly([(x - 3, floor - 12), (x + 3, floor - 12), (x + 3, floor - 4), (x + 5, floor - 3), (x + 5, floor), (x - 4, floor)]), PRIMARY)
        c.piece(rect(x - 2, floor - 9, x + 2, floor - 5), SECONDARY)
        stitches(c, x - 2, floor - 4, 2)
        c.fill(line((x - 3, floor), (x + 4, floor)), SECONDARY[0])


PARTS = [
    ("textile_head_1", "HEAD", "Loom wound bobbin", head_1),
    ("textile_head_2", "HEAD", "Patch pincushion", head_2),
    ("textile_body_1", "BODY", "Loom warp chassis", body_1),
    ("textile_body_2", "BODY", "Patch stitched jacket", body_2),
    ("textile_arms_1", "ARMS", "Loom shuttle spools", arms_1),
    ("textile_arms_2", "ARMS", "Patch needle and shears", arms_2),
    ("textile_legs_1", "LEGS", "Loom bobbin feet", legs_1),
    ("textile_legs_2", "LEGS", "Patch mended boots", legs_2),
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
    d.text((16, 12), "TEXTILE / Loom tender and Patch mechanic / eight shared poses", fill="#18232D")
    for row, (label, variants) in enumerate((("Loom", (1, 1, 1, 1)), ("Patch", (2, 2, 2, 2)), ("Mixed rig", (1, 2, 1, 2)))):
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
