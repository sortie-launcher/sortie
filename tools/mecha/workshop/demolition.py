#!/usr/bin/env python3
"""Authored hydraulic breaker and careful dismantling crew, common eight poses.

Run with Python + Pillow to reproduce shipped sheets, masks and catalog.
Review boards are exported only to ~/.cache/sortie-mecha-lab.
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
DEST = ROOT / "mecha/src/main/assets/mecha/demolition"
PRIMARY = tuple(map(hexc, ("365B51", "6A9B80", "B3D6AB")))
SECONDARY = tuple(map(hexc, ("864C35", "CB7C4F", "EDB783")))
GLASS, EYE, LAMP = map(hexc, ("263944", "E2F6EC", "F2CF72"))
SLOTS = ("HEAD", "BODY", "ARMS", "LEGS")


def eyes(c, f, y, left=27, right=36):
    for x in (left, right):
        if f in (SLEEP, DETAIL):
            c.fill(line((x - 1, y + 1), (x + 1, y + 1)), EYE)
        elif f == CHEER:
            c.fill({(x - 1, y + 1), (x, y), (x + 1, y + 1)}, EYE)
        else:
            gaze = 1 if f == WALK_R else 0
            c.fill(rect(x + gaze, y, x + gaze + 1, y + 2), EYE)


def head_1(c, f):
    # An angular breaker hood, sloping shoulders and a central impact ridge.
    c.piece(rect(29, 21, 35, 26), STEEL_RAMP)
    c.piece(poly([(22, 11), (28, 5), (36, 5), (42, 11), (39, 21), (32, 25), (24, 21)]), PRIMARY)
    c.piece(poly([(29, 5), (34, 5), (36, 11), (28, 11)]), SECONDARY)
    c.fill(poly([(24, 13), (39, 13), (38, 19), (26, 19)]), GLASS)
    eyes(c, f, 14)
    c.piece(rect(19, 12, 22, 18), SECONDARY)
    c.piece(rect(42, 12, 45, 18), SECONDARY)
    c.fill(line((28, 21), (35, 21)), OUT)
    c.dot(32, 23, STEEL)
    c.dot(27, 9, HI)


def head_2(c, f):
    # Small round face, high padded earmuff band and a flip-up magnifier.
    c.piece(rect(29, 21, 35, 26), STEEL_RAMP)
    c.piece(capsule((21, 15), (24, 5), 1), STEEL_RAMP)
    c.piece(capsule((24, 5), (39, 5), 1), STEEL_RAMP)
    c.piece(capsule((39, 5), (43, 15), 1), STEEL_RAMP)
    c.piece(rrect(24, 9, 39, 23, 3), SECONDARY)
    c.piece(rrect(19, 13, 23, 20, 1), PRIMARY)
    c.piece(rrect(40, 13, 44, 20, 1), PRIMARY)
    c.fill(rrect(26, 13, 37, 20, 1), GLASS)
    eyes(c, f, 14, 28, 34)
    c.piece(poly([(25, 7), (37, 7), (39, 11), (24, 11)]), PRIMARY)
    c.piece(rect(30, 6, 35, 9), STEEL_RAMP)
    c.fill(rect(31, 7, 34, 8), GLASS)
    c.dot(31, 7, EYE)
    c.fill(line((29, 22), (34, 22)), SECONDARY[0])


def body_1(c, f):
    # Narrow waist below a heavy V-shaped shock cradle and exposed damper.
    c.piece(poly([(21, 27), (43, 27), (44, 33), (38, 43), (26, 43), (20, 33)]), PRIMARY)
    c.piece(poly([(22, 27), (28, 27), (32, 33), (36, 27), (42, 27), (33, 38), (30, 38)]), SECONDARY)
    c.piece(rect(28, 35, 36, 42), STEEL_RAMP)
    for y in (36, 38, 40):
        c.fill(line((29, y), (35, y)), OUT)
    c.fill(rect(30, 36, 31, 41), HI)
    c.piece(rect(26, 43, 38, 46), SECONDARY)
    c.fill(line((23, 32), (23, 36)), HI)
    c.fill(line((41, 32), (41, 36)), HI)
    c.dot(32, 29, LAMP if f == CHEER else HI)


def body_2(c, f):
    # A slim canvas tool apron, rivet tray and carefully sorted hand tools.
    c.piece(rrect(23, 27, 40, 42, 2), PRIMARY)
    c.piece(poly([(27, 27), (36, 27), (38, 43), (25, 43)]), SECONDARY)
    c.fill(line((27, 28), (25, 35)), HI)
    c.fill(line((36, 28), (38, 35)), HI)
    c.piece(rect(26, 35, 37, 40), PRIMARY)
    c.fill(line((31, 36), (31, 40)), OUT)
    c.fill(line((34, 36), (34, 40)), OUT)
    c.fill(rect(28, 32, 29, 37), STEEL)
    c.fill(rect(32, 32, 32, 37), HI)
    c.fill(rect(35, 33, 36, 37), STEEL)
    c.dot(35, 32, OUT)
    c.piece(rect(22, 37, 24, 42), SECONDARY)
    c.piece(rect(39, 35, 42, 39), SECONDARY)
    c.dot(40, 36, HI)
    c.dot(41, 38, STEEL)
    c.piece(rect(29, 43, 35, 46), STEEL_RAMP)


def arms_1(c, f):
    # Long hydraulic breaker and opposite stabilising brace; exposed pistons.
    for side in (0, 1):
        phase = arm_phase(f, side)
        x, y = 12 + (1 if phase == FORWARD else -1 if phase == BACK else 0), 36
        if f == CHEER:
            x, y = 10, 20
        elif f == SLEEP:
            x, y = 16, 38
        def m(shape):
            return mirror(shape) if side else shape
        c.piece(m(capsule((19, 29), (x, y), 3)), SECONDARY)
        c.piece(m(capsule((17, 29), (x - 1, y - 1), 1)), STEEL_RAMP)
        c.piece(m(circle(19, 29, 3)), PRIMARY)
        if side == 0:
            c.piece(rect(x - 3, y - 2, x + 3, y + 6), PRIMARY)
            c.piece(rect(x - 2, y + 5, x + 2, y + 9), SECONDARY)
            c.piece(poly([(x - 1, y + 10), (x + 1, y + 10), (x + 1, y + 16), (x, y + 18), (x - 1, y + 16)]), STEEL_RAMP)
            c.fill(line((x - 1, y), (x - 1, y + 4)), HI)
            c.fill(line((x + 5, y - 5), (x + 5, y + 3)), OUT)
            c.fill(line((x + 3, y + 3), (x + 5, y + 3)), OUT)
        else:
            c.piece(m(rect(x - 3, y - 1, x + 3, y + 5)), PRIMARY)
            c.piece(m(capsule((x - 2, y + 4), (x - 4, y + 9), 1)), STEEL_RAMP)
            c.piece(m(capsule((x + 2, y + 4), (x + 4, y + 9), 1)), STEEL_RAMP)
            c.fill(m(line((x - 4, y + 9), (x - 1, y + 9))), STEEL)
            c.fill(m(line((x + 1, y + 9), (x + 4, y + 9))), STEEL)


def arms_2(c, f):
    # Thin precise grippers carry a small pry bar and soft lifting sling.
    for side in (0, 1):
        phase = arm_phase(f, side)
        x, y = 14 + (1 if phase == FORWARD else -1 if phase == BACK else 0), 40
        if f == CHEER:
            x, y = 11, 23
        elif f == SLEEP:
            x, y = 17, 41
        def m(shape):
            return mirror(shape) if side else shape
        c.piece(m(capsule((19, 29), (16, 35), 1.5)), PRIMARY)
        c.piece(m(circle(16, 35, 2)), STEEL_RAMP)
        c.piece(m(capsule((16, 35), (x, y), 1.5)), PRIMARY)
        c.piece(m(rect(x - 2, y - 2, x + 2, y)), SECONDARY)
        c.piece(m(capsule((x - 1, y), (x - 2, y + 4), .7)), STEEL_RAMP)
        c.piece(m(capsule((x + 1, y), (x + 2, y + 4), .7)), STEEL_RAMP)
        if side == 0:
            c.piece(capsule((x, y - 7), (x, y + 8), .7), STEEL_RAMP)
            c.fill(line((x, y + 8), (x - 3, y + 9)), HI)
            c.fill(rect(x - 1, y - 4, x + 1, y - 2), SECONDARY[1])
        else:
            c.piece(m(poly([(x - 3, y + 3), (x + 3, y + 3), (x + 5, y + 9), (x, y + 12), (x - 5, y + 9)])), SECONDARY)
            c.fill(m(poly([(x - 1, y + 5), (x + 1, y + 5), (x + 2, y + 8), (x, y + 9), (x - 2, y + 8)])), OUT)


def legs_1(c, f):
    # Splayed hydraulic outriggers end in wide flat stabiliser plates.
    for side in (0, 1):
        out, lift, bend = leg_state(f, side)
        x, floor = 22 - out, 61 - lift
        def m(shape):
            return mirror(shape) if side else shape
        c.piece(m(capsule((26, 44), (x, floor - 4), 2.5)), SECONDARY)
        c.piece(m(capsule((25, 47), (x - 1, floor - 4), 1)), STEEL_RAMP)
        c.piece(m(poly([(23, 46), (28, 47), (26, 52), (21, 51)])), PRIMARY)
        c.piece(m(rect(x - 3, floor - 5, x + 3, floor - 2)), PRIMARY)
        c.piece(m(poly([(x - 6, floor - 2), (x + 5, floor - 2), (x + 7, floor), (x - 7, floor)])), SECONDARY)
        c.fill(m(line((x - 6, floor), (x + 6, floor))), OUT)
        c.dot(64 - x if side else x, floor - 3, HI)


def legs_2(c, f):
    # Light kneepads and rounded soft-soled boots for careful footing.
    for side in (0, 1):
        out, lift, bend = leg_state(f, side)
        x, floor = 25 - out, 61 - lift
        def m(shape):
            return mirror(shape) if side else shape
        c.piece(m(capsule((26, 44), (x, floor - 4), 2)), STEEL_RAMP)
        c.piece(m(rrect(x - 3, 48, x + 2, 52, 1)), SECONDARY)
        c.piece(m(rect(x - 2, floor - 7, x + 2, floor - 4)), PRIMARY)
        c.piece(m(rrect(x - 5, floor - 4, x + 3, floor, 2)), PRIMARY)
        c.fill(m(line((x - 4, floor), (x + 2, floor))), SHADOW)
        c.fill(m(rect(x - 2, floor - 3, x + 1, floor - 2)), SECONDARY[1])
        c.dot(64 - x if side else x, 49, HI)


PARTS = [
    ("demolition_head_1", "HEAD", "Impact hood", head_1),
    ("demolition_head_2", "HEAD", "Dismantler earmuffs", head_2),
    ("demolition_body_1", "BODY", "Shock cradle", body_1),
    ("demolition_body_2", "BODY", "Sorted tool apron", body_2),
    ("demolition_arms_1", "ARMS", "Hydraulic breaker", arms_1),
    ("demolition_arms_2", "ARMS", "Pry bar and lifting sling", arms_2),
    ("demolition_legs_1", "LEGS", "Stabiliser plates", legs_1),
    ("demolition_legs_2", "LEGS", "Soft step boots", legs_2),
]


def main():
    DEST.mkdir(parents=True, exist_ok=True)
    sheets, catalog = {}, []
    for pid, slot, name, draw in PARTS:
        sheets[pid] = render_sheet(draw)
        export_part(sheets[pid], DEST / f"{pid}.png", PRIMARY, SECONDARY)
        catalog.append(dict(id=pid, slot=slot, family="demolition", name=name, asset=f"mecha/demolition/{pid}.png"))
    (DEST / "catalog.json").write_text(json.dumps(catalog, indent=2) + "\n", encoding="utf-8")
    board = Image.new("RGBA", (8 * 192, 2 * 218), (225, 229, 230, 255))
    d = ImageDraw.Draw(board)
    for variant, title in ((1, "Hydraulic breaker"), (2, "Careful dismantling crew")):
        combo = {slot: f"demolition_{slot.lower()}_{variant}" for slot in SLOTS}
        d.text((8, (variant - 1) * 218 + 3), title, fill=OUT)
        for f in range(8):
            board.alpha_composite(assemble(sheets, combo, f).resize((192, 192), Image.Resampling.NEAREST), (f * 192, (variant - 1) * 218 + 22))
    cache = Path.home() / ".cache/sortie-mecha-lab"
    cache.mkdir(parents=True, exist_ok=True)
    board.save(cache / "demolition-board.png")


if __name__ == "__main__":
    main()
