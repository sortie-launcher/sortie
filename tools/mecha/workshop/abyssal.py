#!/usr/bin/env python3
"""Abyssal: pressure-sphere explorer and angler trench scout. Pillow authoring source."""
from __future__ import annotations

import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw

MECHA = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(MECHA))
from courier import (OUT, SHADOW, HI, STEEL_RAMP, rect, rrect, ellipse, circle,
                     capsule, line, poly, mirror, mirror_pt, arm_phase, leg_state,
                     render_sheet, assemble, hexc, DETAIL, SLEEP, CHEER, WALK_L, WALK_R)
from paint_roles import export_part

FAMILY = "abyssal"
DEST = MECHA.parents[1] / "mecha/src/main/assets/mecha" / FAMILY
REVIEW = Path(__file__).resolve().parent / "assets" / FAMILY
PRIMARY = tuple(map(hexc, ("343C72", "59659D", "929CC5")))
SECONDARY = tuple(map(hexc, ("3A757C", "65B0AA", "A6D9C0")))
GLASS, GLASS_HI, GLOW, HOT = map(hexc, ("142E42", "39627A", "AFF3DA", "E7FFF0"))


def eye(c, x, y, f, wide=False):
    if f == SLEEP:
        c.fill(line((x - 2, y + 1), (x + 2, y + 1)), GLASS_HI)
    elif f == CHEER:
        c.fill(line((x - 2, y + 1), (x, y - 1)), HOT)
        c.fill(line((x, y - 1), (x + 2, y + 1)), HOT)
    else:
        look = -1 if f == WALK_L else 1 if f == WALK_R else 0
        c.fill(rect(x - 1 + look, y - 1, x + (2 if wide else 1) + look, y + 1), GLOW)
        c.dot(x + look, y - 1, HOT)


def head_1(c, f):
    # Clear spherical sensor capsule inside an external meridian pressure cage.
    c.piece(rect(29, 20, 35, 26), STEEL_RAMP)
    c.piece(circle(32, 13, 11), PRIMARY)
    c.fill(circle(32, 13, 9), GLASS)
    c.fill(line((25, 8), (28, 5)), GLASS_HI)
    c.fill(line((24, 10), (24, 13)), HOT)
    eye(c, 29, 14, f)
    eye(c, 36, 14, f)
    c.piece(rect(21, 19, 43, 21), SECONDARY)
    c.piece(rect(30, 2, 34, 5), SECONDARY)
    for x in (21, 43):
        c.piece(rect(x - 1, 10, x + 1, 16), SECONDARY)
        c.dot(x, 12, HI)
    c.fill(rect(29, 22, 35, 23), PRIMARY[0])


def head_2(c, f):
    # Low armored fish snout and a curved luminous fishing lure, not a helmet.
    c.piece(rect(29, 21, 35, 26), STEEL_RAMP)
    c.piece(poly([(21, 12), (28, 8), (39, 10), (44, 17), (40, 23), (22, 21), (18, 17)]), PRIMARY)
    c.piece(poly([(21, 15), (37, 13), (41, 17), (37, 20), (22, 19)]), (GLASS, GLASS, GLASS_HI))
    eye(c, 27, 17, f)
    eye(c, 35, 16, f)
    c.piece(poly([(23, 21), (39, 21), (36, 24), (26, 24)]), SECONDARY)
    for x in (27, 31, 35):
        c.dot(x, 22, HI)
    tip_x = 24 if f == SLEEP else 20 if f == DETAIL else 22
    c.piece(line((36, 10), (34, 4)) | line((34, 4), (26, 2)) | line((26, 2), (tip_x, 5)), STEEL_RAMP)
    c.piece(circle(tip_x, 6, 2), (GLASS_HI, GLOW if f != SLEEP else GLASS_HI, HOT))
    c.piece(poly([(42, 12), (46, 9), (45, 18), (42, 20)]), SECONDARY)


def sockets(c):
    for x in (19, 45):
        c.piece(circle(x, 29, 3), STEEL_RAMP)
    c.piece(rrect(24, 40, 40, 45), STEEL_RAMP)


def body_1(c, f):
    # Narrow research gondola with side thruster pods and an instrument drawer.
    sockets(c)
    for x in (21, 43):
        c.piece(rrect(x - 3, 29, x + 3, 39, 2), SECONDARY)
        c.fill(rect(x - 1, 32, x + 1, 36), GLASS)
        c.dot(x, 34, GLASS_HI if f == SLEEP else GLOW)
    c.piece(poly([(28, 25), (36, 25), (40, 32), (37, 42), (27, 42), (24, 32)]), PRIMARY)
    c.piece(rect(28, 28, 36, 32), SECONDARY)
    c.fill(rect(29, 29, 35, 31), GLASS)
    c.fill(line((30, 30), (32 + f % 3, 30)), GLOW if f != SLEEP else GLASS_HI)
    c.piece(rrect(28, 35, 36, 40), STEEL_RAMP)
    c.fill(rect(30, 37, 34, 38), SHADOW)


def body_2(c, f):
    # Broad armored ray with ventral gills and paired fin projections.
    sockets(c)
    c.piece(poly([(21, 25), (32, 27), (43, 25), (46, 34), (40, 41), (32, 44), (24, 41), (18, 34)]), PRIMARY)
    for side in (0, 1):
        fin = poly([(20, 28), (15, 33), (19, 38), (23, 35)])
        c.piece(mirror(fin) if side else fin, SECONDARY)
    c.piece(poly([(26, 28), (38, 28), (40, 32), (32, 36), (24, 32)]), SECONDARY)
    c.fill(rect(27, 30, 37, 31), GLASS)
    c.dot(29 + f % 5, 30, GLASS_HI if f == SLEEP else GLOW)
    for x in (27, 31, 35):
        c.fill(line((x, 37), (x + 2, 40)), SHADOW)


def arms(c, f, scout):
    for side in (0, 1):
        elbow, wrist = {"neutral": ((15, 35), (12, 41)), "forward": ((16, 34), (16, 39)),
                        "back": ((13, 35), (10, 40))}[arm_phase(f, side)]
        if f == SLEEP:
            elbow, wrist = (16, 36), (17, 42)
        elif f == CHEER:
            elbow, wrist = (13, 24), (11, 17)
        shoulder = (19, 29)
        if side:
            shoulder, elbow, wrist = map(mirror_pt, (shoulder, elbow, wrist))
        c.piece(capsule(shoulder, elbow, 1), STEEL_RAMP)
        c.piece(capsule(elbow, wrist, 1.5 if scout else 2), PRIMARY)
        # Exposed primary shoulder shells remain visible when short forearms fold.
        c.piece(circle(*shoulder, 2.5), PRIMARY)
        c.piece(circle(*elbow, 2), SECONDARY)
        x, y = wrist
        if scout:
            # Hydrofoil fins end in two delicate sensor whiskers.
            c.piece(poly([(x - 3, y - 4), (x + 3, y - 2), (x + 2, y + 3), (x - 3, y + 1)]), SECONDARY)
            c.piece(line((x - 1, y + 2), (x - 3, y + 6)), STEEL_RAMP)
            c.piece(line((x + 1, y + 2), (x + 3, y + 5)), STEEL_RAMP)
        else:
            # Rectangular specimen basket with an open illuminated center.
            c.piece(rrect(x - 3, y - 2, x + 3, y + 5), SECONDARY)
            c.fill(rect(x - 1, y, x + 1, y + 3), GLASS)
            c.dot(x, y + 1, GLASS_HI if f == SLEEP else GLOW)


def arms_1(c, f):
    arms(c, f, False)


def arms_2(c, f):
    arms(c, f, True)


def legs(c, f, scout):
    for side in (0, 1):
        out, lift, bend = leg_state(f, side)
        ax, ground = 24 - out, 61 - lift
        hip, knee, ankle = (26, 44), (25 - out / 2 - bend, 49 - lift / 2), (ax, ground - 4)
        pieces = [(capsule(hip, knee, 1.2), STEEL_RAMP), (circle(*hip, 2), STEEL_RAMP),
                  (capsule(knee, ankle, 1.5), PRIMARY), (circle(*knee, 2), SECONDARY)]
        if scout:
            pieces.append((poly([(ax - 2, ground - 5), (ax + 2, ground - 5), (ax + 6, ground - 1),
                                 (ax + 5, ground), (ax - 7, ground), (ax - 5, ground - 2)]), SECONDARY))
        else:
            # Tripod-edged pressure pads, visibly unlike Tidal's heavy boots.
            pieces += [(ellipse(ax, ground - 2, 5, 2), PRIMARY),
                       (rect(ax - 6, ground - 1, ax - 4, ground), SECONDARY),
                       (rect(ax + 4, ground - 1, ax + 6, ground), SECONDARY)]
        for shape, ramp in pieces:
            c.piece(mirror(shape) if side else shape, ramp)


def legs_1(c, f):
    legs(c, f, False)


def legs_2(c, f):
    legs(c, f, True)


PARTS = [
    ("abyssal_head_1", "HEAD", "Pressure globe", head_1),
    ("abyssal_head_2", "HEAD", "Angler lure", head_2),
    ("abyssal_body_1", "BODY", "Research gondola", body_1),
    ("abyssal_body_2", "BODY", "Trench ray hull", body_2),
    ("abyssal_arms_1", "ARMS", "Sample baskets", arms_1),
    ("abyssal_arms_2", "ARMS", "Whisker probes", arms_2),
    ("abyssal_legs_1", "LEGS", "Pressure pads", legs_1),
    ("abyssal_legs_2", "LEGS", "Silt fins", legs_2),
]


def main():
    DEST.mkdir(parents=True, exist_ok=True)
    REVIEW.mkdir(parents=True, exist_ok=True)
    sheets, catalog = {}, []
    for pid, slot, name, draw in PARTS:
        sheets[pid] = render_sheet(draw)
        export_part(sheets[pid], DEST / f"{pid}.png", PRIMARY, SECONDARY, slot=slot)
        catalog.append(dict(id=pid, slot=slot, family=FAMILY, name=name,
                            asset=f"mecha/{FAMILY}/{pid}.png"))
    (DEST / "catalog.json").write_text(json.dumps(catalog, indent=2) + "\n")
    board = Image.new("RGB", (1536, 450), "#D5E2DF")
    d = ImageDraw.Draw(board)
    for row, title in enumerate(("Pressure-sphere explorer", "Angler trench scout")):
        d.text((12, row * 224 + 8), title, fill="#18232D")
        combo = {s: f"{FAMILY}_{s.lower()}_{row + 1}" for s in ("HEAD", "BODY", "ARMS", "LEGS")}
        for frame in range(8):
            sprite = assemble(sheets, combo, frame).resize((192, 192), Image.Resampling.NEAREST)
            board.paste(sprite, (frame * 192, row * 224 + 28), sprite)
    board.save(REVIEW / "board.png")
    print(f"Authored {FAMILY}: 8 sheets, 8 masks, catalog; review {REVIEW / 'board.png'}")


if __name__ == "__main__":
    main()
