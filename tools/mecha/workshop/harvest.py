#!/usr/bin/env python3
"""Original combine-thresher and seed-planter sets on the shared eight-pose rig.

Run directly to reproduce production sheets/masks/catalog and an external board.
Paint roles: ochre machine enamel, moss service panels; optics/steel stay fixed.
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
FAMILY = "harvest"
ASSETS = ROOT / "mecha/src/main/assets/mecha" / FAMILY
PRIMARY = tuple(map(hexc, ("9F642D", "DCA34C", "FFE097")))
SECONDARY = tuple(map(hexc, ("465C3B", "748454", "B0BC7D")))
LAMP = hexc("B8F2DA")
HOT = hexc("F1FFE8")
GRAIN = hexc("E8C08A")
RUBBER = (OUT, SHADOW, STEEL)
ORDER = ("LEGS", "BODY", "ARMS", "HEAD")


def eyes(c, f, y, left=26, right=35):
    for x in (left, right):
        c.fill(rect(x, y, x + 4, y + 3), OUT)
        if f in (1, 6):
            c.fill(line((x + 1, y + 2), (x + 3, y + 2)), LAMP)
        elif f == 7:
            c.fill({(x + 1, y + 2), (x + 2, y + 1), (x + 3, y + 2)}, HOT)
        else:
            dx = -1 if f == 2 else 1 if f == 4 else 0
            c.fill(rect(x + 2 + dx, y + 1, x + 3 + dx, y + 2), LAMP)
            c.dot(x + 2 + dx, y + 1, HOT)


def head_1(c, f):
    c.piece(rect(29, 21, 35, 26), STEEL_RAMP)
    # High exhaust and a wide, low combine cab give a tractor-like brow.
    c.piece(rect(40, 6, 42, 15), STEEL_RAMP)
    c.piece(rect(39, 5, 43, 7), SECONDARY)
    c.piece(poly([(22, 11), (25, 8), (38, 8), (43, 12), (43, 21),
                  (39, 24), (24, 24), (20, 20), (20, 14)]), PRIMARY)
    c.piece(poly([(19, 11), (23, 8), (41, 8), (45, 12), (45, 14), (19, 14)]), SECONDARY)
    c.fill(rect(23, 15, 40, 20), OUT)
    eyes(c, f, 16, 25, 34)
    c.fill(line((29, 22), (34, 22)), SHADOW)
    c.dot(35, 21, SHADOW)
    c.dot(23, 11, HI)
    c.dot(42, 12, GRAIN)


def head_2(c, f):
    c.piece(rect(29, 20, 35, 26), STEEL_RAMP)
    # Upright seed-bin head, with a real funnel lid rather than another cab.
    c.piece(poly([(25, 9), (39, 9), (41, 12), (39, 22), (36, 24),
                  (27, 24), (24, 21), (23, 12)]), SECONDARY)
    c.piece(poly([(23, 8), (27, 5), (37, 5), (41, 8), (40, 11), (24, 11)]), PRIMARY)
    c.piece(rect(30, 3, 34, 5), STEEL_RAMP)
    c.fill(rect(26, 13, 38, 19), OUT)
    eyes(c, f, 14, 26, 34)
    c.fill(line((29, 21), (34, 21)), PRIMARY[2])
    c.dot(35, 20, PRIMARY[2])
    c.piece(circle(41, 17, 2), PRIMARY)
    c.dot(41, 17, HI)


def body_1(c, f):
    c.piece(rect(25, 41, 39, 45), STEEL_RAMP)
    c.piece(poly([(20, 26), (44, 26), (46, 31), (42, 41), (38, 44),
                  (25, 44), (20, 39), (18, 30)]), PRIMARY)
    c.piece(rect(23, 28, 41, 32), SECONDARY)
    # A recessed threshing drum with moving steel teeth.
    c.piece(rrect(22, 34, 41, 40, 2), RUBBER)
    for x in range(24, 41, 4):
        c.fill(rect(x, 35, x + 1, 39), STEEL)
        c.dot(x, 35 + f % 3, HI)
    c.piece(rect(27, 41, 37, 43), SECONDARY)
    for x in (21, 43):
        c.dot(x, 30, GRAIN)


def body_2(c, f):
    c.piece(rect(26, 39, 38, 45), STEEL_RAMP)
    c.piece(poly([(21, 27), (43, 27), (40, 36), (35, 42), (29, 42), (24, 36)]), SECONDARY)
    c.piece(rect(20, 26, 44, 29), PRIMARY)
    c.piece(poly([(27, 30), (37, 30), (36, 37), (32, 40), (28, 37)]), PRIMARY)
    c.fill(rect(29, 31, 35, 35), OUT)
    for x, y in ((30, 32), (33, 33), (31, 34), (34, 32)):
        c.dot(x, y, GRAIN)
    c.piece(rect(30, 40, 34, 44), STEEL_RAMP)
    c.dot(32, 42, LAMP if f != 6 else SHADOW)
    c.piece(rect(22, 33, 24, 38), PRIMARY)
    c.piece(rect(40, 33, 42, 38), PRIMARY)


def arms(c, f, planter):
    for side in (0, 1):
        sign = 1 if side == 0 else -1
        sx = 19 if side == 0 else 45
        phase = arm_phase(f, side)
        dx = 1 if phase == "forward" else -1 if phase == "back" else 0
        ex, ey = sx - sign * 4, 35
        wx, wy = sx - sign * (6 + dx), 41
        if f == 6:
            wx, wy = sx - sign * 3, 42
        elif f == 7:
            ex, ey, wx, wy = sx - sign * 5, 23, sx - sign * 7, 17
        c.piece(capsule((sx, 29), (ex, ey), 1.3), STEEL_RAMP)
        c.piece(capsule((ex, ey), (wx, wy), 2 if planter else 3), SECONDARY)
        # Planter shoulder guards carry its moss livery even when raised tools
        # overlap the slim forearms in the celebration pose.
        c.piece(circle(sx, 29, 2.5 if planter else 4), SECONDARY if planter else PRIMARY)
        c.piece(circle(ex, ey, 2), PRIMARY)
        c.dot(ex, ey, HI)
        if planter:
            # Narrow dibber and opposing seed-delivery jaw.
            c.piece(poly([(wx - 2, wy), (wx + 2, wy), (wx + 1, wy + 6), (wx, wy + 8)]), STEEL_RAMP)
            c.piece(capsule((wx + sign * 3, wy), (wx + sign * 3, wy + 4), 1), PRIMARY)
            c.dot(wx, wy + 3, GRAIN)
        else:
            # Broad three-tooth rake jaws echo the threshing drum.
            c.piece(rect(wx - 4, wy, wx + 4, wy + 2), PRIMARY)
            for x in (wx - 3, wx, wx + 3):
                c.piece(rect(x, wy + 3, x + 1, wy + 6), STEEL_RAMP)


def arms_1(c, f):
    arms(c, f, False)


def arms_2(c, f):
    arms(c, f, True)


def legs(c, f, planter):
    for side in (0, 1):
        sign = 1 if side == 0 else -1
        hipx = 26 if side == 0 else 38
        out, lift, bend = leg_state(f, side)
        ax = hipx - sign * (2 + out)
        ground = 60 - lift
        knee = (hipx - sign * bend, 50 - lift // 2)
        c.piece(capsule((hipx, 44), knee, 2), SECONDARY)
        c.piece(capsule(knee, (ax, ground - 4), 1.5), STEEL_RAMP)
        c.piece(circle(*knee, 2.5), PRIMARY)
        if planter:
            c.piece(poly([(ax - 3, ground - 7), (ax + 3, ground - 7),
                          (ax + 4, ground), (ax - 5, ground), (ax - 5, ground - 2)]), SECONDARY)
            c.fill(rect(ax - 3, ground - 5, ax + 2, ground - 4), PRIMARY[1])
            c.fill(line((ax - 4, ground), (ax + 3, ground)), STEEL)
        else:
            c.piece(rrect(ax - 6, ground - 6, ax + 5, ground, 2), RUBBER)
            c.piece(rrect(ax - 4, ground - 7, ax + 3, ground - 3, 1), PRIMARY)
            for x in (ax - 4, ax, ax + 4):
                c.fill(line((x, ground - 1), (x, ground)), STEEL)


def legs_1(c, f):
    legs(c, f, False)


def legs_2(c, f):
    legs(c, f, True)


PARTS = [
    ("harvest_head_1", "HEAD", "Combine cab", head_1),
    ("harvest_head_2", "HEAD", "Seed funnel", head_2),
    ("harvest_body_1", "BODY", "Thresher drum", body_1),
    ("harvest_body_2", "BODY", "Planter hopper", body_2),
    ("harvest_arms_1", "ARMS", "Harvest rakes", arms_1),
    ("harvest_arms_2", "ARMS", "Seed dibbers", arms_2),
    ("harvest_legs_1", "LEGS", "Field cleats", legs_1),
    ("harvest_legs_2", "LEGS", "Furrow walkers", legs_2),
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
    d.text((16, 12), "HARVEST / Combine thresher + Seed planter", fill="#28332D")
    for n, title in ((1, "Combine thresher"), (2, "Seed planter")):
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
