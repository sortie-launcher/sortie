#!/usr/bin/env python3
"""Original rail signal conductor and ballast trolley, using the common rig.

Python + Pillow exports production sheets, paint masks and catalog; a review
board of all eight shared poses is written outside the repository.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from courier import (
    OUT, SHADOW, STEEL, HI, STEEL_RAMP, SLEEP, CHEER, DETAIL,
    WALK_R, FORWARD, BACK, arm_phase, leg_state, hexc,
    rect, rrect, poly, circle, capsule, line, mirror, render_sheet, assemble,
)
from paint_roles import export_part

ROOT = Path(__file__).resolve().parents[3]
DEST = ROOT / "mecha/src/main/assets/mecha/rail"
PRIMARY = tuple(map(hexc, ("563346", "975566", "D3949A")))
SECONDARY = tuple(map(hexc, ("285451", "438B7E", "93C2AB")))
GLASS, EYE, RED, GREEN = map(hexc, ("20383E", "F8EBC0", "F26E54", "B4ED9D"))
SLOTS = ("HEAD", "BODY", "ARMS", "LEGS")


def eyes(c, f, y, left=27, right=36):
    for x in (left, right):
        if f in (SLEEP, DETAIL):
            c.fill(line((x - 1, y + 1), (x + 1, y + 1)), EYE)
        elif f == CHEER:
            c.fill({(x - 1, y + 1), (x, y), (x + 1, y + 1)}, EYE)
        else:
            c.fill(rect(x, y, x + 1, y + 2), EYE)


def head_1(c, f):
    # The conductor's peaked cap is deliberately wider than the slim face.
    c.piece(rect(29, 21, 35, 26), STEEL_RAMP)
    c.piece(rrect(24, 12, 39, 23, 2), SECONDARY)
    c.fill(rect(25, 14, 38, 20), GLASS)
    eyes(c, f, 17)
    c.piece(poly([(22, 7), (26, 4), (38, 4), (42, 7), (40, 12), (23, 12)]), PRIMARY)
    c.piece(rect(22, 11, 41, 13), SECONDARY)
    c.piece(poly([(24, 14), (42, 14), (39, 16), (25, 15)]), PRIMARY)
    c.piece(circle(32, 8, 2), STEEL_RAMP)
    c.dot(32, 8, EYE)
    c.fill(line((29, 22), (34, 22)), PRIMARY[0])
    c.piece(rect(21, 16, 23, 19), PRIMARY)


def head_2(c, f):
    # Offset signal mast and low sloping trolley roof, not a second hat.
    c.piece(rect(29, 21, 35, 26), STEEL_RAMP)
    c.piece(rect(42, 5, 43, 20), STEEL_RAMP)
    c.piece(rrect(40, 3, 45, 13), PRIMARY)
    for y, color in ((5, RED), (10, GREEN)):
        c.piece(circle(42, y, 1), (SHADOW, color if f != SLEEP else SHADOW, HI))
    c.piece(poly([(22, 12), (28, 8), (38, 8), (41, 13), (40, 22), (22, 22)]), SECONDARY)
    c.piece(poly([(20, 11), (27, 6), (38, 6), (42, 10), (40, 12)]), PRIMARY)
    c.fill(rect(24, 14, 38, 19), GLASS)
    eyes(c, f, 15)
    c.fill(line((25, 21), (37, 21)), PRIMARY[1])
    c.dot(23, 12, HI)


def body_1(c, f):
    # Tailored jacket silhouette, two lapels and a hanging pocket watch.
    c.piece(poly([(23, 26), (41, 26), (43, 31), (39, 39), (41, 44), (34, 43), (31, 41), (24, 44), (21, 32)]), PRIMARY)
    c.piece(poly([(27, 26), (37, 26), (32, 37)]), SECONDARY)
    c.fill(poly([(27, 27), (30, 32), (28, 34), (25, 27)]), SECONDARY[2])
    c.fill(poly([(37, 27), (33, 32), (35, 34), (39, 27)]), SECONDARY[2])
    c.fill(rect(31, 28, 32, 31), OUT)
    for y in (35, 39):
        c.dot(32, y, HI)
    c.fill(line((36, 32), (38, 36)), HI)
    c.piece(circle(38, 38, 2), STEEL_RAMP)
    c.dot(38, 38, EYE)
    c.piece(rect(24, 36, 28, 39), SECONDARY)
    c.piece(rect(29, 43, 35, 46), STEEL_RAMP)


def body_2(c, f):
    # Trolley's open ballast tray above a wide chevron-shaped plough.
    c.piece(rect(21, 28, 43, 39), SECONDARY)
    c.piece(poly([(19, 27), (45, 27), (42, 33), (22, 33)]), PRIMARY)
    c.fill(rect(23, 28, 41, 30), OUT)
    for x, y in ((24, 29), (28, 28), (31, 30), (34, 28), (38, 29)):
        c.fill({(x, y), (x + 1, y)}, STEEL)
    c.piece(poly([(18, 39), (27, 36), (32, 38), (37, 36), (46, 39), (43, 44), (32, 42), (21, 44)]), SECONDARY)
    c.fill(line((19, 40), (31, 43)), HI)
    c.fill(line((33, 43), (45, 40)), HI)
    c.piece(rect(28, 33, 36, 37), PRIMARY)
    c.fill(rect(31, 34, 33, 36), OUT)
    c.piece(rect(28, 43, 36, 46), STEEL_RAMP)


def arms_1(c, f):
    # A small signalling flag and enclosed railway lantern distinguish hands.
    for side in (0, 1):
        phase = arm_phase(f, side)
        x, y = 14 + (1 if phase == FORWARD else -1 if phase == BACK else 0), 40
        if f == CHEER:
            x, y = 12, 23
        elif f == SLEEP:
            x, y = 17, 42
        def m(shape):
            return mirror(shape) if side else shape
        c.piece(m(capsule((19, 29), (16, 34), 2)), PRIMARY)
        c.piece(m(capsule((16, 34), (x, y), 2)), PRIMARY)
        c.piece(m(rect(x - 2, y - 2, x + 2, y)), SECONDARY)
        c.piece(m(circle(x, y + 2, 2)), STEEL_RAMP)
        if side == 0:
            c.piece(rect(x - 4, y + 4, x + 2, y + 10), SECONDARY)
            c.fill(rect(x - 2, y + 5, x, y + 8), EYE if f != SLEEP else SHADOW)
            c.fill(line((x - 3, y + 2), (x + 1, y + 2)), STEEL)
            c.fill(line((x - 3, y + 2), (x - 3, y + 4)), STEEL)
        else:
            c.piece(m(rect(x, y - 12, x, y + 5)), STEEL_RAMP)
            c.piece(m(poly([(x - 1, y - 12), (x - 8, y - 11), (x - 8, y - 5), (x - 1, y - 6)])), SECONDARY)
            c.fill(m(line((x - 6, y - 10), (x - 3, y - 7))), HI)


def arms_2(c, f):
    # Tamping forks are forked vertical tools, not claws or buckets.
    for side in (0, 1):
        phase = arm_phase(f, side)
        x, y = 12, 38 + (1 if phase == BACK else -1 if phase == FORWARD else 0)
        if f == CHEER:
            x, y = 10, 22
        elif f == SLEEP:
            x, y = 16, 40
        def m(shape):
            return mirror(shape) if side else shape
        c.piece(m(capsule((19, 29), (x + 1, y - 3), 2)), STEEL_RAMP)
        c.piece(m(poly([(16, 26), (22, 27), (21, 32), (15, 32)])), PRIMARY)
        c.piece(m(rect(x - 3, y - 4, x + 3, y + 3)), SECONDARY)
        c.fill(m(rect(x - 1, y - 2, x + 1, y + 1)), PRIMARY[1])
        for tx in (x - 3, x + 3):
            c.piece(m(rect(tx, y + 3, tx + 1, y + 11)), STEEL_RAMP)
            c.fill(m(rect(tx - 1, y + 10, tx + 1, y + 11)), STEEL)


def legs_1(c, f):
    # Slender uniform trousers and polished toe-forward shoes.
    for side in (0, 1):
        out, lift, bend = leg_state(f, side)
        x, floor = 25 - out, 61 - lift
        def m(shape):
            return mirror(shape) if side else shape
        c.piece(m(capsule((26, 44), (x, floor - 4), 2.5)), PRIMARY)
        c.fill(m(line((x - 1, 47), (x - 1, floor - 5))), PRIMARY[2])
        c.piece(m(rect(x - 3, floor - 8, x + 2, floor - 5)), SECONDARY)
        c.piece(m(poly([(x - 2, floor - 4), (x + 2, floor - 4), (x + 3, floor), (x - 6, floor), (x - 7, floor - 2)])), SECONDARY)
        c.fill(m(line((x - 5, floor - 2), (x - 2, floor - 2))), HI)
        c.fill(m(line((x - 6, floor), (x + 3, floor))), OUT)


def legs_2(c, f):
    # Paired visible flanged rail wheels, sprung bogies and no crawler tread.
    for side in (0, 1):
        out, lift, bend = leg_state(f, side)
        x, floor = 23 - out, 61 - lift
        def m(shape):
            return mirror(shape) if side else shape
        c.piece(m(capsule((26, 44), (x, floor - 8), 2)), STEEL_RAMP)
        c.piece(m(rect(x - 4, floor - 11, x + 4, floor - 6)), PRIMARY)
        for y in range(floor - 10, floor - 6, 2):
            c.fill(m(line((x - 2, y), (x + 2, y))), OUT)
        for wx in (x - 4, x + 4):
            c.piece(m(circle(wx, floor - 3, 3)), STEEL_RAMP)
            c.piece(m(circle(wx, floor - 3, 1)), SECONDARY)
            c.fill(m(line((wx - 3, floor), (wx + 3, floor))), HI)
        c.piece(m(rect(x - 6, floor - 7, x + 6, floor - 5)), SECONDARY)
        c.fill(m(line((x - 4, floor - 6), (x + 4, floor - 6))), PRIMARY[1])


PARTS = [
    ("rail_head_1", "HEAD", "Signal conductor cap", head_1),
    ("rail_head_2", "HEAD", "Trolley signal cab", head_2),
    ("rail_body_1", "BODY", "Pocket watch jacket", body_1),
    ("rail_body_2", "BODY", "Ballast plough", body_2),
    ("rail_arms_1", "ARMS", "Flag and lantern", arms_1),
    ("rail_arms_2", "ARMS", "Tamping forks", arms_2),
    ("rail_legs_1", "LEGS", "Conductor shoes", legs_1),
    ("rail_legs_2", "LEGS", "Flanged bogies", legs_2),
]


def main():
    DEST.mkdir(parents=True, exist_ok=True)
    sheets, catalog = {}, []
    for pid, slot, name, draw in PARTS:
        sheets[pid] = render_sheet(draw)
        export_part(sheets[pid], DEST / f"{pid}.png", PRIMARY, SECONDARY)
        catalog.append(dict(id=pid, slot=slot, family="rail", name=name, asset=f"mecha/rail/{pid}.png"))
    (DEST / "catalog.json").write_text(json.dumps(catalog, indent=2) + "\n", encoding="utf-8")
    board = Image.new("RGBA", (8 * 192, 2 * 218), (225, 229, 230, 255))
    d = ImageDraw.Draw(board)
    for variant, title in ((1, "Signal conductor"), (2, "Ballast maintenance trolley")):
        combo = {slot: f"rail_{slot.lower()}_{variant}" for slot in SLOTS}
        d.text((8, (variant - 1) * 218 + 3), title, fill=OUT)
        for f in range(8):
            board.alpha_composite(assemble(sheets, combo, f).resize((192, 192), Image.Resampling.NEAREST), (f * 192, (variant - 1) * 218 + 22))
    cache = Path.home() / ".cache/sortie-mecha-lab"
    cache.mkdir(parents=True, exist_ok=True)
    board.save(cache / "rail-board.png")


if __name__ == "__main__":
    main()
