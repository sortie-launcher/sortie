#!/usr/bin/env python3
"""Original hive tender and beekeeper scout, using the shared courier rig.

Honey enamel and mulberry fabric are paintable; mesh, bees, optics and steel
remain fixed. Direct execution reproduces production assets and external board.
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
FAMILY = "apiary"
ASSETS = ROOT / "mecha/src/main/assets/mecha" / FAMILY
PRIMARY = tuple(map(hexc, ("A56B2E", "E2AD52", "FFE3A5")))
SECONDARY = tuple(map(hexc, ("63415C", "976B82", "D0A0AC")))
LAMP = hexc("9EE0E1")
HOT = hexc("EAFFEF")
HONEY = hexc("F9C26D")
MESH = hexc("65716D")
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
    # Stacked hive-skep dome, broad base and small crown.
    c.piece(ellipse(32, 16, 11, 8), PRIMARY)
    c.piece(rrect(26, 6, 38, 12, 3), PRIMARY)
    c.piece(rect(24, 11, 40, 13), SECONDARY)
    c.piece(rect(21, 20, 43, 23), SECONDARY)
    c.fill(rect(24, 15, 40, 19), OUT)
    eyes(c, f, 15)
    c.fill(line((30, 22), (34, 22)), PRIMARY[2])
    c.piece(line((26, 8), (23, 4)), STEEL_RAMP)
    c.piece(line((38, 8), (41, 4)), STEEL_RAMP)
    c.piece(circle(23, 4, 1), PRIMARY)
    c.piece(circle(41, 4, 1), PRIMARY)


def head_2(c, f):
    c.piece(rect(29, 21, 35, 26), STEEL_RAMP)
    # Wide protective sun-brim over a trapezoid veil, eyes through fine mesh.
    c.piece(poly([(23, 11), (40, 11), (43, 23), (38, 25), (25, 25), (21, 22)]), SECONDARY)
    c.fill(poly([(25, 12), (38, 12), (40, 22), (24, 22)]), OUT)
    for x in range(25, 40, 3):
        for y in (13, 16, 19, 22):
            c.dot(x, y, MESH)
    c.piece(rrect(25, 6, 38, 11, 2), PRIMARY)
    c.piece(poly([(19, 10), (43, 10), (46, 12), (43, 14), (19, 14), (17, 12)]), PRIMARY)
    eyes(c, f, 16)
    c.fill(line((30, 22), (34, 22)), HOT)
    c.dot(35, 21, HOT)
    c.fill(rect(27, 8, 36, 9), SECONDARY[1])
    c.dot(38, 11, HONEY)


def hexagon(c, x, y, ramp):
    c.piece(poly([(x - 2, y - 3), (x + 2, y - 3), (x + 4, y),
                  (x + 2, y + 3), (x - 2, y + 3), (x - 4, y)]), ramp)


def body_1(c, f):
    c.piece(rect(25, 41, 39, 45), STEEL_RAMP)
    c.piece(rrect(21, 26, 43, 43, 4), PRIMARY)
    c.piece(rect(22, 28, 42, 30), SECONDARY)
    c.piece(rect(22, 40, 42, 42), SECONDARY)
    # Three recessed comb cells are built into the honey extractor.
    for x, y in ((27, 34), (37, 34), (32, 39)):
        hexagon(c, x, y, SECONDARY)
        c.fill(rect(x - 1, y - 1, x + 1, y + 1), OUT)
        c.dot(x, y, HONEY)
    c.piece(rect(43, 33, 46, 38), STEEL_RAMP)
    c.dot(45, 35, LAMP if f != 6 else SHADOW)


def body_2(c, f):
    c.piece(rect(26, 40, 38, 45), STEEL_RAMP)
    # Light field harness, sloped shoulders and asymmetrical specimen pouch.
    c.piece(poly([(23, 26), (40, 26), (42, 30), (38, 41), (34, 44),
                  (28, 42), (23, 36), (21, 29)]), SECONDARY)
    c.piece(poly([(25, 27), (29, 27), (34, 40), (30, 42)]), PRIMARY)
    c.piece(poly([(37, 27), (40, 29), (28, 40), (25, 37)]), PRIMARY)
    c.piece(rrect(34, 34, 42, 40, 1), PRIMARY)
    c.fill(rect(36, 35, 40, 38), OUT)
    c.dot(37, 36, HONEY)
    c.dot(39, 37, HONEY)
    c.piece(circle(31, 33, 2), STEEL_RAMP)
    c.dot(31, 33, LAMP if f != 6 else SHADOW)
    c.piece(rect(21, 32, 23, 39), STEEL_RAMP)


def arms(c, f, scout):
    for side in (0, 1):
        sign = 1 if side == 0 else -1
        sx = 19 if side == 0 else 45
        phase = arm_phase(f, side)
        shift = 1 if phase == "forward" else -1 if phase == "back" else 0
        ex, ey, wx, wy = sx - sign * 4, 35, sx - sign * (5 + shift), 41
        if f == 6:
            wx, wy = sx - sign * 2, 42
        elif f == 7:
            ex, ey, wx, wy = sx - sign * 4, 23, sx - sign * 7, 17
        c.piece(capsule((sx, 29), (ex, ey), 1.5 if scout else 2.5), SECONDARY)
        c.piece(capsule((ex, ey), (wx, wy), 1.5 if scout else 3), PRIMARY)
        c.piece(circle(sx, 29, 2.5 if scout else 3.5), PRIMARY if scout else SECONDARY)
        c.piece(circle(ex, ey, 2), STEEL_RAMP)
        if scout and side == 0:
            # Gentle soft brush; no floating prop or detached pixels.
            c.piece(rect(wx - 1, wy, wx + 1, wy + 4), STEEL_RAMP)
            c.piece(rect(wx - 4, wy + 4, wx + 4, wy + 6), SECONDARY)
            for x in range(wx - 3, wx + 4, 2):
                c.fill(rect(x, wy + 7, x, wy + 8), HI)
        elif not scout and side == 1:
            # Bellows smoker with a short hooked spout.
            c.piece(rrect(wx - 3, wy, wx + 3, wy + 7, 1), STEEL_RAMP)
            c.piece(rect(wx - 3, wy + 2, wx + 3, wy + 4), SECONDARY)
            c.piece(capsule((wx + 2, wy), (wx + 5, wy - 2), 1), STEEL_RAMP)
            c.dot(wx + 5, wy - 2, OUT)
        else:
            c.piece(rect(wx - 3, wy, wx + 3, wy + 3), PRIMARY)
            c.piece(rect(wx - 3, wy + 3, wx - 2, wy + 6), STEEL_RAMP)
            c.piece(rect(wx + 2, wy + 3, wx + 3, wy + 6), STEEL_RAMP)


def arms_1(c, f):
    arms(c, f, False)


def arms_2(c, f):
    arms(c, f, True)


def legs(c, f, scout):
    for side in (0, 1):
        sign = 1 if side == 0 else -1
        hipx = 26 if side == 0 else 38
        out, lift, bend = leg_state(f, side)
        ax, ground = hipx - sign * (2 + out), 60 - lift
        knee = (hipx - sign * bend, 50 - lift // 2)
        c.piece(capsule((hipx, 44), knee, 1.5 if scout else 2.5), STEEL_RAMP)
        c.piece(capsule(knee, (ax, ground - 3), 2 if scout else 3), SECONDARY)
        c.piece(circle(*knee, 2.1 if scout else 3), PRIMARY)
        if scout:
            c.piece(poly([(ax - 2, ground - 5), (ax + 2, ground - 5),
                          (ax + 4, ground - 2), (ax + 4, ground), (ax - 4, ground)]), PRIMARY)
            c.fill(line((ax - 3, ground), (ax + 3, ground)), SHADOW)
        else:
            c.piece(rrect(ax - 5, ground - 6, ax + 4, ground, 2), PRIMARY)
            c.piece(rect(ax - 4, ground - 4, ax + 3, ground - 2), SECONDARY)
            c.fill(rect(ax - 4, ground, ax + 3, ground), SHADOW)
            c.dot(ax - 2, ground - 3, HI)


def legs_1(c, f):
    legs(c, f, False)


def legs_2(c, f):
    legs(c, f, True)


PARTS = [
    ("apiary_head_1", "HEAD", "Hive dome", head_1),
    ("apiary_head_2", "HEAD", "Scout veil", head_2),
    ("apiary_body_1", "BODY", "Honey extractor", body_1),
    ("apiary_body_2", "BODY", "Bee field harness", body_2),
    ("apiary_arms_1", "ARMS", "Smoker bellows", arms_1),
    ("apiary_arms_2", "ARMS", "Gentle brush", arms_2),
    ("apiary_legs_1", "LEGS", "Hive keepers", legs_1),
    ("apiary_legs_2", "LEGS", "Meadow gaiters", legs_2),
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
    d.text((16, 12), "APIARY / Hive tender + Beekeeper scout", fill="#28332D")
    for n, title in ((1, "Hive tender"), (2, "Beekeeper scout")):
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
