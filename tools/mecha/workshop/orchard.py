#!/usr/bin/env python3
"""Original pruning picker and irrigation tender on the shared eight-frame rig.

Terracotta shells and sage equipment are the two semantic paint ramps; apples,
leaf indicators, optics, water and steel are fixed authored details. Run directly
for production sheets/masks/catalog and an external assembled review board.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from courier import (OUT, SHADOW, STEEL, HI, STEEL_RAMP, rect, rrect,
                     ellipse, circle, capsule, poly, line, hexc,
                     render_sheet, assemble, leg_state, arm_phase)
from paint_roles import export_part

ROOT = HERE.parents[2]
FAMILY = "orchard"
ASSETS = ROOT / "mecha/src/main/assets/mecha" / FAMILY
PRIMARY = tuple(map(hexc, ("934B38", "C97C55", "F3BA83")))
SECONDARY = tuple(map(hexc, ("43614F", "729875", "B2C89B")))
LAMP = hexc("F9DC84")
HOT = hexc("FFF9D9")
WATER = hexc("9DDBD6")
APPLE = tuple(map(hexc, ("8C3641", "D75D61", "F5A184")))
LEAF = hexc("95B856")
RUBBER = (OUT, SHADOW, STEEL)
ORDER = ("LEGS", "BODY", "ARMS", "HEAD")


def eyes(c, f, y, x1=26, x2=35):
    for x in (x1, x2):
        c.fill(rect(x, y, x + 3, y + 3), OUT)
        if f in (1, 6):
            c.fill(line((x, y + 2), (x + 3, y + 2)), LAMP)
        elif f == 7:
            c.fill({(x, y + 2), (x + 1, y + 1), (x + 2, y + 1), (x + 3, y + 2)}, HOT)
        else:
            look = 0 if f == 2 else 2 if f == 4 else 1
            c.fill(rect(x + look, y + 1, x + min(look + 1, 3), y + 2), LAMP)
            c.dot(x + look, y + 1, HOT)


def head_1(c, f):
    c.piece(rect(29, 21, 35, 26), STEEL_RAMP)
    # Broad picking hood with a leaf-shaped hinged sun shield.
    c.piece(poly([(23, 11), (40, 10), (43, 14), (41, 22), (37, 24),
                  (25, 24), (21, 20), (21, 14)]), PRIMARY)
    c.piece(poly([(18, 12), (23, 8), (35, 6), (42, 10), (45, 13),
                  (38, 13), (32, 11), (24, 14)]), SECONDARY)
    c.fill(line((23, 10), (34, 8)), SECONDARY[2])
    c.piece(line((34, 8), (36, 4)), STEEL_RAMP)
    c.fill({(37, 4), (38, 4), (37, 5)}, LEAF)
    c.fill(rect(24, 15, 39, 19), OUT)
    eyes(c, f, 15)
    c.fill(line((29, 22), (34, 22)), SHADOW)
    c.dot(35, 21, SHADOW)
    c.piece(circle(43, 18, 2), SECONDARY)
    c.dot(43, 18, HI)


def head_2(c, f):
    c.piece(rect(29, 21, 35, 26), STEEL_RAMP)
    # Upright pressure bell, droplet silhouette and side valve ears.
    c.piece(poly([(31, 4), (34, 6), (39, 11), (42, 17), (40, 22),
                  (36, 24), (27, 24), (23, 21), (22, 17), (26, 10)]), SECONDARY)
    c.piece(rect(24, 12, 40, 14), PRIMARY)
    c.piece(circle(21, 18, 2), PRIMARY)
    c.piece(circle(43, 18, 2), PRIMARY)
    c.dot(21, 18, HI)
    c.dot(43, 18, HI)
    c.fill(rect(25, 16, 39, 20), OUT)
    eyes(c, f, 16)
    c.fill(line((30, 22), (34, 22)), WATER)
    c.dot(35, 21, WATER)
    c.fill({(30, 8), (30, 9), (29, 10)}, SECONDARY[2])


def body_1(c, f):
    c.piece(rect(25, 41, 39, 45), STEEL_RAMP)
    # A squat slatted picking crate, not a recoloured barrel or hopper.
    c.piece(poly([(21, 27), (43, 27), (42, 42), (24, 43), (21, 38)]), SECONDARY)
    c.fill(rect(25, 28, 39, 34), OUT)
    for x, y in ((28, 31), (36, 31), (32, 29)):
        c.piece(circle(x, y, 2), APPLE)
        c.dot(x, y - 2, LEAF)
    for y in (34, 38, 42):
        c.piece(rect(22, y, 42, y + 1), PRIMARY)
    c.piece(rect(24, 29, 26, 42), PRIMARY)
    c.piece(rect(38, 29, 40, 42), PRIMARY)
    for x in (25, 39):
        for y in (35, 39):
            c.dot(x, y, HI)
    c.piece(rect(29, 40, 35, 43), SECONDARY)
    c.dot(32, 41, LAMP if f != 6 else SHADOW)


def body_2(c, f):
    c.piece(rect(25, 41, 39, 45), STEEL_RAMP)
    c.piece(ellipse(32, 35, 11, 9), PRIMARY, shade="cyl", band=(24, 26))
    c.piece(rect(23, 28, 41, 30), SECONDARY)
    c.piece(rect(23, 39, 41, 41), SECONDARY)
    # Central sight glass has a fixed water level; metal straps are paintable.
    c.piece(rrect(29, 31, 35, 38, 1), STEEL_RAMP)
    c.fill(rect(31, 32, 33, 37), OUT)
    c.fill(rect(31, 35, 33, 37), WATER)
    c.dot(32, 33 if f == 1 else 34, WATER)
    c.piece(circle(40, 35, 2), SECONDARY)
    c.fill(line((39, 35), (41, 35)), HI)
    c.fill(line((40, 34), (40, 36)), HI)
    c.piece(rect(21, 33, 23, 38), STEEL_RAMP)


def arms(c, f, irrigation):
    for side in (0, 1):
        sign = 1 if side == 0 else -1
        sx = 19 if side == 0 else 45
        phase = arm_phase(f, side)
        shift = 1 if phase == "forward" else -1 if phase == "back" else 0
        ex, ey, wx, wy = sx - sign * 4, 35, sx - sign * (6 + shift), 40
        if f == 6:
            wx, wy = sx - sign * 3, 42
        elif f == 7:
            ex, ey, wx, wy = sx - sign * 4, 23, sx - sign * 7, 17
        c.piece(capsule((sx, 29), (ex, ey), 2), PRIMARY)
        c.piece(capsule((ex, ey), (wx, wy), 2.5 if irrigation else 1.5), SECONDARY)
        c.piece(circle(sx, 29, 3.3 if irrigation else 2.7), SECONDARY)
        c.piece(circle(ex, ey, 2.3), STEEL_RAMP)
        if irrigation:
            # Ribbed hose cuff and fan-shaped brass sprinkler nozzle.
            c.piece(rect(wx - 3, wy, wx + 3, wy + 2), RUBBER)
            c.piece(poly([(wx - 1, wy + 2), (wx + 1, wy + 2),
                          (wx + 4, wy + 6), (wx - 4, wy + 6)]), PRIMARY)
            for x in range(wx - 3, wx + 4, 2):
                c.dot(x, wy + 6, WATER)
        elif side == 0:
            # Safe rounded bypass pruner, opened wider in detail/celebrate.
            gap = 4 if f in (1, 7) else 2
            c.piece(capsule((wx, wy), (wx - gap, wy + 6), 1), STEEL_RAMP)
            c.piece(capsule((wx, wy), (wx + gap, wy + 5), 1), STEEL_RAMP)
            c.piece(circle(wx, wy, 2), PRIMARY)
            c.dot(wx, wy, HI)
        else:
            # Three padded picking fingers, holding fruit only in detail.
            c.piece(rect(wx - 3, wy, wx + 3, wy + 2), PRIMARY)
            for x in (wx - 3, wx + 3):
                c.piece(capsule((x, wy + 2), (x, wy + 6), 1), SECONDARY)
            if f == 1:
                c.piece(circle(wx, wy + 5, 2), APPLE)
                c.dot(wx, wy + 3, LEAF)


def arms_1(c, f):
    arms(c, f, False)


def arms_2(c, f):
    arms(c, f, True)


def legs(c, f, irrigation):
    for side in (0, 1):
        sign = 1 if side == 0 else -1
        hipx = 26 if side == 0 else 38
        out, lift, bend = leg_state(f, side)
        ax, ground = hipx - sign * (2 + out), 60 - lift
        knee = (hipx - sign * bend, 50 - lift // 2)
        c.piece(capsule((hipx, 44), knee, 2.3 if irrigation else 1.5), STEEL_RAMP)
        c.piece(capsule(knee, (ax, ground - 3), 2), PRIMARY)
        c.piece(circle(*knee, 2.5), SECONDARY)
        if irrigation:
            c.piece(rrect(ax - 4, ground - 8, ax + 4, ground, 2), SECONDARY)
            c.piece(rect(ax - 4, ground - 8, ax + 4, ground - 6), PRIMARY)
            c.fill(line((ax - 3, ground), (ax + 3, ground)), SHADOW)
            c.dot(ax - 2, ground - 4, SECONDARY[2])
        else:
            c.piece(poly([(ax - 3, ground - 5), (ax + 2, ground - 5),
                          (ax + 5, ground - 2), (ax + 5, ground), (ax - 5, ground),
                          (ax - 5, ground - 2)]), PRIMARY)
            c.piece(rect(ax - 3, ground - 4, ax + 2, ground - 3), SECONDARY)
            for x in (ax - 4, ax, ax + 4):
                c.dot(x, ground, STEEL)


def legs_1(c, f):
    legs(c, f, False)


def legs_2(c, f):
    legs(c, f, True)


PARTS = [
    ("orchard_head_1", "HEAD", "Leaf picking hood", head_1),
    ("orchard_head_2", "HEAD", "Pressure bell", head_2),
    ("orchard_body_1", "BODY", "Apple picking crate", body_1),
    ("orchard_body_2", "BODY", "Water barrel", body_2),
    ("orchard_arms_1", "ARMS", "Gentle bypass pruners", arms_1),
    ("orchard_arms_2", "ARMS", "Sprinkler nozzles", arms_2),
    ("orchard_legs_1", "LEGS", "Orchard cleats", legs_1),
    ("orchard_legs_2", "LEGS", "Irrigation wellies", legs_2),
]


def main():
    ASSETS.mkdir(parents=True, exist_ok=True)
    sheets, catalog = {}, []
    for pid, slot, name, draw in PARTS:
        sheets[pid] = render_sheet(draw)
        export_part(sheets[pid], ASSETS / f"{pid}.png", PRIMARY, SECONDARY, slot=slot)
        catalog.append(dict(id=pid, slot=slot, family=FAMILY, name=name,
                            asset=f"mecha/{FAMILY}/{pid}.png"))
    (ASSETS / "catalog.json").write_text(json.dumps(catalog, indent=2) + "\n")
    board = Image.new("RGBA", (1240, 450), "#E7DFCB")
    d = ImageDraw.Draw(board)
    d.text((16, 12), "ORCHARD / Pruning picker + Irrigation tender", fill="#28332D")
    for n, title in ((1, "Pruning picker"), (2, "Irrigation tender")):
        combo = {slot: f"{FAMILY}_{slot.lower()}_{n}" for slot in ORDER}
        y = 42 + (n - 1) * 200
        d.text((16, y), title, fill="#28332D")
        board.alpha_composite(assemble(sheets, combo, 0), (20, y + 24))
        for f in range(8):
            board.alpha_composite(assemble(sheets, combo, f).resize((128, 128), Image.Resampling.NEAREST), (180 + f * 128, y + 18))
            d.text((180 + f * 128, y + 155), str(f), fill="#28332D")
    cache = Path.home() / ".cache/sortie-mecha-lab"
    cache.mkdir(parents=True, exist_ok=True)
    board.save(cache / f"{FAMILY}-board.png")
    print(f"Authored {FAMILY}: 8 sheets, 8 masks, catalog; board {cache / (FAMILY + '-board.png')}")


if __name__ == "__main__":
    main()
