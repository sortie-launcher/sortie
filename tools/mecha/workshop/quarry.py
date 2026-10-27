#!/usr/bin/env python3
"""Authored quarry saw technician and aggregate loader; shared eight-pose rig.

Run with Python + Pillow. Production sheets/masks/catalog go to mecha assets;
assembled review boards go to ~/.cache/sortie-mecha-lab, never production.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from courier import (
    OUT, SHADOW, STEEL, HI, STEEL_RAMP, SLEEP, CHEER, DETAIL,
    WALK_L, WALK_R, FORWARD, BACK, arm_phase, leg_state,
    hexc, rect, rrect, poly, circle, capsule, line, mirror,
    render_sheet, assemble,
)
from paint_roles import export_part

ROOT = Path(__file__).resolve().parents[3]
DEST = ROOT / "mecha/src/main/assets/mecha/quarry"
PRIMARY = tuple(map(hexc, ("8C632B", "CFA34F", "EFCE87")))
SECONDARY = tuple(map(hexc, ("394D65", "627F9A", "A2B5C4")))
GLASS, EYE, LAMP = map(hexc, ("233C42", "D6F1D8", "F7DF91"))
SLOTS = ("HEAD", "BODY", "ARMS", "LEGS")


def face(c, f, x0, y0, x1, y1):
    c.fill(rect(x0, y0, x1, y1), GLASS)
    for x in (x0 + 3, x1 - 3):
        if f in (SLEEP, DETAIL):
            c.fill(line((x - 1, y0 + 3), (x + 1, y0 + 3)), EYE)
        elif f == CHEER:
            c.fill({(x - 1, y0 + 3), (x, y0 + 2), (x + 1, y0 + 3)}, EYE)
        else:
            gaze = 1 if f == WALK_R else 0
            c.fill(rect(x + gaze, y0 + 2, x + gaze + 1, y0 + 4), EYE)


def head_1(c, f):
    # A low helmet and broad ear defenders over a square dust-mask face.
    c.piece(rect(29, 22, 35, 26), STEEL_RAMP)
    c.piece(rrect(23, 10, 40, 23, 2), SECONDARY)
    c.piece(rect(19, 12, 22, 20), PRIMARY)
    c.piece(rect(41, 12, 44, 20), PRIMARY)
    face(c, f, 25, 12, 38, 18)
    c.piece(poly([(21, 10), (23, 5), (39, 5), (42, 10)]), PRIMARY)
    c.piece(rect(19, 10, 44, 11), PRIMARY)
    c.fill(rect(30, 5, 33, 9), SECONDARY[1])
    c.piece(rect(28, 20, 35, 23), STEEL_RAMP)
    for x in (29, 32, 34):
        c.fill(line((x, 21), (x, 22)), OUT)
    c.dot(39, 7, LAMP)


def head_2(c, f):
    # Wide hopper brow, external mirrors and a face behind loader cab glass.
    c.piece(rect(29, 22, 35, 26), STEEL_RAMP)
    c.piece(poly([(21, 10), (25, 7), (40, 7), (43, 10), (41, 23), (23, 23)]), PRIMARY)
    c.piece(rect(19, 12, 21, 16), SECONDARY)
    c.piece(rect(43, 12, 45, 16), SECONDARY)
    face(c, f, 24, 12, 40, 18)
    c.piece(poly([(21, 6), (42, 6), (44, 9), (20, 9)]), SECONDARY)
    c.fill(rect(26, 21, 38, 22), SECONDARY[0])
    for x in (27, 31, 35):
        c.dot(x, 21, HI)
    c.piece(circle(39, 4, 1), STEEL_RAMP)
    c.dot(39, 4, LAMP if f != SLEEP else SHADOW)


def body_1(c, f):
    # Tapered stonecutter's bib with a circular blade spare and water hose.
    c.piece(poly([(22, 26), (42, 26), (44, 33), (39, 44), (25, 44), (20, 33)]), SECONDARY)
    c.piece(poly([(25, 27), (38, 27), (40, 42), (25, 42)]), PRIMARY)
    c.fill(rect(26, 28, 28, 31), SECONDARY[0])
    c.fill(rect(36, 28, 38, 31), SECONDARY[0])
    c.piece(circle(32, 36, 5), STEEL_RAMP)
    c.piece(circle(32, 36, 2), SECONDARY)
    c.dot(32, 36, OUT)
    for x, y in ((27, 35), (30, 31), (36, 34), (34, 40)):
        c.dot(x, y, OUT)
    c.fill(line((22, 31), (22, 40)), HI)
    c.fill(line((22, 40), (26, 43)), HI)
    c.piece(rect(29, 43, 36, 46), STEEL_RAMP)


def body_2(c, f):
    # A genuinely broad aggregate hopper, inset gravel and trapezoid belly.
    c.piece(poly([(18, 28), (46, 28), (42, 43), (23, 43)]), PRIMARY)
    c.piece(poly([(19, 27), (45, 27), (42, 33), (22, 33)]), SECONDARY)
    c.fill(poly([(22, 28), (42, 28), (40, 31), (24, 31)]), OUT)
    for x, y in ((24, 29), (27, 28), (30, 30), (33, 28), (36, 29), (39, 28)):
        c.fill({(x, y), (x + 1, y)}, STEEL)
        c.dot(x, y - 1, HI)
    c.piece(rect(26, 35, 38, 40), SECONDARY)
    for x in (28, 31, 34, 37):
        c.fill(line((x, 36), (x, 39)), OUT)
    c.piece(rect(27, 43, 37, 46), STEEL_RAMP)
    for x in (22, 42):
        c.dot(x, 34, LAMP if f == CHEER else HI)


def arms_1(c, f):
    # Water-fed circular stone saw on left, narrow guiding handle on right.
    for side in (0, 1):
        phase = arm_phase(f, side)
        dx = -1 if phase == BACK else 1 if phase == FORWARD else 0
        x, y = 12 + dx, 40
        if f == CHEER:
            x, y = 11, 20
        elif f == SLEEP:
            x, y = 16, 42
        def m(shape):
            return mirror(shape) if side else shape
        c.piece(m(capsule((19, 29), (15, 34), 2)), STEEL_RAMP)
        c.piece(m(capsule((15, 34), (x, y), 2)), SECONDARY)
        c.piece(m(rrect(16, 27, 21, 31)), PRIMARY)
        if side == 0:
            c.piece(circle(x, y + 3, 6), STEEL_RAMP)
            for ox, oy in ((-6, 0), (6, 0), (0, -6), (0, 6), (-4, -4), (4, 4)):
                c.fill(rect(x + ox, y + 3 + oy, x + ox + 1, y + 4 + oy), STEEL)
            c.piece(poly([(x - 6, y + 2), (x - 4, y - 3), (x + 3, y - 3), (x + 6, y + 2)]), PRIMARY)
            c.piece(circle(x, y + 3, 2), SECONDARY)
            c.dot(x, y + 3, HI)
        else:
            c.piece(m(rrect(x - 2, y - 1, x + 2, y + 4)), PRIMARY)
            c.fill(m(rect(x - 1, y + 1, x + 1, y + 2)), OUT)


def arms_2(c, f):
    # Independent broad scoop buckets with visible cutting teeth.
    for side in (0, 1):
        phase = arm_phase(f, side)
        y = 39 + (1 if phase == BACK else -1 if phase == FORWARD else 0)
        x = 12
        if f == CHEER:
            y = 21
        elif f == SLEEP:
            x, y = 15, 41
        def m(shape):
            return mirror(shape) if side else shape
        c.piece(m(capsule((19, 29), (x + 2, y - 4), 3)), SECONDARY)
        c.piece(m(capsule((17, 30), (x, y - 1), 1)), STEEL_RAMP)
        c.piece(m(circle(19, 29, 3)), PRIMARY)
        c.piece(m(poly([(x - 5, y - 2), (x + 5, y - 2), (x + 4, y + 6), (x - 4, y + 6)])), PRIMARY)
        c.fill(m(rect(x - 3, y, x + 3, y + 3)), SECONDARY[0])
        for tx in (x - 4, x, x + 4):
            c.piece(m(rect(tx, y + 5, tx + 1, y + 8)), STEEL_RAMP)


def legs_1(c, f):
    # Tall dust gaiters above short squared quarry soles.
    for side in (0, 1):
        out, lift, bend = leg_state(f, side)
        x, floor = 24 - out, 61 - lift
        def m(shape):
            return mirror(shape) if side else shape
        c.piece(m(capsule((26, 44), (x, floor - 6), 2)), STEEL_RAMP)
        c.piece(m(poly([(x - 3, 48), (x + 3, 48), (x + 2, floor - 4), (x - 4, floor - 4)])), SECONDARY)
        c.piece(m(rect(x - 3, 48, x + 2, 51)), PRIMARY)
        c.piece(m(poly([(x - 3, floor - 5), (x + 3, floor - 5), (x + 4, floor), (x - 6, floor), (x - 6, floor - 3)])), PRIMARY)
        c.fill(m(line((x - 5, floor), (x + 3, floor))), SHADOW)
        c.dot(64 - x if side else x, floor - 3, HI)


def legs_2(c, f):
    # Low crawler pods, broad fenders and three tiny fixed-metal road wheels.
    for side in (0, 1):
        out, lift, bend = leg_state(f, side)
        x, floor = 23 - out, 61 - lift
        def m(shape):
            return mirror(shape) if side else shape
        c.piece(m(capsule((26, 44), (x, floor - 5), 3)), SECONDARY)
        c.piece(m(rrect(x - 7, floor - 7, x + 6, floor, 2)), STEEL_RAMP)
        c.fill(m(rect(x - 5, floor - 5, x + 4, floor - 2)), OUT)
        for wx in (x - 4, x, x + 4):
            c.piece(m(circle(wx, floor - 3, 1.3)), STEEL_RAMP)
        c.piece(m(poly([(x - 7, floor - 8), (x + 4, floor - 8), (x + 6, floor - 5), (x - 7, floor - 5)])), PRIMARY)
        c.fill(m(rect(x - 3, 48, x + 1, 50)), PRIMARY[1])


PARTS = [
    ("quarry_head_1", "HEAD", "Stone saw helmet", head_1),
    ("quarry_head_2", "HEAD", "Aggregate cab", head_2),
    ("quarry_body_1", "BODY", "Water feed bib", body_1),
    ("quarry_body_2", "BODY", "Gravel hopper", body_2),
    ("quarry_arms_1", "ARMS", "Stone saw and guide", arms_1),
    ("quarry_arms_2", "ARMS", "Aggregate buckets", arms_2),
    ("quarry_legs_1", "LEGS", "Dust gaiters", legs_1),
    ("quarry_legs_2", "LEGS", "Loader crawlers", legs_2),
]


def main():
    DEST.mkdir(parents=True, exist_ok=True)
    sheets, catalog = {}, []
    for pid, slot, name, draw in PARTS:
        sheets[pid] = render_sheet(draw)
        export_part(sheets[pid], DEST / f"{pid}.png", PRIMARY, SECONDARY)
        catalog.append(dict(id=pid, slot=slot, family="quarry", name=name, asset=f"mecha/quarry/{pid}.png"))
    (DEST / "catalog.json").write_text(json.dumps(catalog, indent=2) + "\n", encoding="utf-8")
    board = Image.new("RGBA", (8 * 192, 2 * 218), (225, 229, 230, 255))
    d = ImageDraw.Draw(board)
    for variant, title in ((1, "Stone-saw technician"), (2, "Aggregate loader")):
        combo = {slot: f"quarry_{slot.lower()}_{variant}" for slot in SLOTS}
        d.text((8, (variant - 1) * 218 + 3), title, fill=OUT)
        for f in range(8):
            board.alpha_composite(assemble(sheets, combo, f).resize((192, 192), Image.Resampling.NEAREST), (f * 192, (variant - 1) * 218 + 22))
    cache = Path.home() / ".cache/sortie-mecha-lab"
    cache.mkdir(parents=True, exist_ok=True)
    board.save(cache / "quarry-board.png")


if __name__ == "__main__":
    main()
