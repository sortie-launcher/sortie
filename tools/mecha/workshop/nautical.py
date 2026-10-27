#!/usr/bin/env python3
"""Nautical: walking lighthouse keeper and sturdy tugboat deckhand. Pillow source."""
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

FAMILY = "nautical"
DEST = MECHA.parents[1] / "mecha/src/main/assets/mecha" / FAMILY
REVIEW = Path(__file__).resolve().parent / "assets" / FAMILY
PRIMARY = tuple(map(hexc, ("924B4D", "CB7370", "ECA99A")))
SECONDARY = tuple(map(hexc, ("8F947B", "C9CFB2", "EFF1D7")))
GLASS, GLASS_HI, GLOW, HOT = map(hexc, ("253E50", "5E869B", "FFDC8B", "FFF4CC"))


def face(c, x, y, f):
    for dx in (-3, 3):
        if f == SLEEP:
            c.fill(line((x + dx - 1, y + 1), (x + dx + 1, y + 1)), GLASS_HI)
        elif f == CHEER:
            c.fill(line((x + dx - 1, y), (x + dx, y - 1)), HOT)
            c.dot(x + dx + 1, y, HOT)
        else:
            look = -1 if f == WALK_L else 1 if f == WALK_R else 0
            c.fill(rect(x + dx + look - 1, y - 1, x + dx + look, y + 1), GLOW)


def head_1(c, f):
    # Lantern room and broad conical roof: architectural, not a sailor's hat.
    c.piece(rect(29, 20, 35, 26), STEEL_RAMP)
    c.piece(rect(23, 10, 41, 21), SECONDARY)
    c.fill(rect(25, 12, 39, 19), GLASS)
    face(c, 32, 15, f)
    c.fill(rect(31, 11, 32, 20), SECONDARY[0])
    c.piece(poly([(20, 10), (24, 7), (30, 3), (34, 3), (40, 7), (44, 10)]), PRIMARY)
    c.piece(rect(21, 21, 43, 23), PRIMARY)
    c.piece(rect(31, 1, 33, 3), STEEL_RAMP)
    c.dot(32, 1, HOT if f == DETAIL else HI)
    for x in (22, 42):
        c.piece(rect(x, 17, x, 20), STEEL_RAMP)


def head_2(c, f):
    # Wheelhouse with a rear chimney and a wide weather visor.
    c.piece(rect(29, 21, 35, 26), STEEL_RAMP)
    c.piece(rect(39, 3, 43, 13), PRIMARY)
    c.piece(rect(38, 3, 44, 5), STEEL_RAMP)
    c.piece(poly([(22, 10), (37, 10), (41, 14), (41, 23), (22, 23)]), SECONDARY)
    c.piece(rrect(19, 8, 40, 11), PRIMARY)
    c.fill(rect(24, 13, 37, 19), GLASS)
    face(c, 31, 16, f)
    c.fill(rect(25, 21, 36, 22), PRIMARY[0])
    c.piece(circle(42, 20, 3), (OUT, SHADOW, HI))
    c.fill(circle(42, 20, 1), GLASS)
    c.piece(line((22, 8), (21, 3)), STEEL_RAMP)
    c.dot(21, 3, GLOW if f != SLEEP else GLASS_HI)


def sockets(c):
    for x in (19, 45):
        c.piece(circle(x, 29, 3), STEEL_RAMP)
    c.piece(rect(25, 40, 39, 45), STEEL_RAMP)


def body_1(c, f):
    # Tapered lighthouse tower with visible spiral bands and a tiny service hatch.
    sockets(c)
    tower = poly([(27, 25), (37, 25), (42, 43), (22, 43)])
    c.piece(tower, SECONDARY)
    c.fill(poly([(26, 28), (38, 31), (39, 35), (24, 31)]) & tower, PRIMARY[1])
    c.fill(poly([(24, 35), (40, 38), (41, 41), (23, 38)]) & tower, PRIMARY[1])
    c.fill(line((26, 28), (37, 31)), PRIMARY[2])
    c.piece(rrect(29, 36, 35, 43), PRIMARY)
    c.fill(rect(31, 38, 33, 42), GLASS)
    c.dot(33, 40, HI)
    c.piece(rect(22, 43, 42, 45), PRIMARY)
    c.fill(rect(30, 27, 34, 29), GLASS)
    c.dot(32, 28, GLASS_HI if f == SLEEP else GLOW)


def body_2(c, f):
    # Boat hull, raised bow and hanging tire fenders; no torso breastplate.
    sockets(c)
    # Wheelhouse pedestal bolts the shared neck to the recessed working deck.
    c.piece(rrect(28, 24, 36, 31), STEEL_RAMP)
    c.piece(rect(26, 27, 38, 30), SECONDARY)
    c.piece(poly([(18, 28), (26, 31), (38, 31), (46, 28), (42, 40), (37, 44), (27, 44), (22, 40)]), PRIMARY)
    c.piece(poly([(19, 28), (26, 30), (38, 30), (45, 28), (43, 33), (21, 33)]), SECONDARY)
    c.fill(line((24, 39), (40, 39)), SECONDARY[2])
    for x in (23, 41):
        c.piece(ellipse(x, 36, 3, 4), (OUT, SHADOW, HI))
        c.fill(ellipse(x, 36, 1, 2), GLASS)
    c.piece(rrect(28, 32, 36, 37), SECONDARY)
    c.fill(rect(30, 34, 34, 35), GLASS)
    c.dot(31 + f % 3, 34, GLASS_HI if f == SLEEP else GLOW)
    c.fill(line((29, 42), (35, 42)), PRIMARY[0])


def arms(c, f, tug):
    for side in (0, 1):
        elbow, wrist = {"neutral": ((15, 35), (12, 41)), "forward": ((16, 34), (16, 39)),
                        "back": ((14, 35), (10, 40))}[arm_phase(f, side)]
        if f == SLEEP:
            elbow, wrist = (16, 36), (17, 42)
        elif f == CHEER:
            elbow, wrist = (12, 24), (11, 17)
        shoulder = (19, 29)
        if side:
            shoulder, elbow, wrist = map(mirror_pt, (shoulder, elbow, wrist))
        c.piece(capsule(shoulder, elbow, 1.5), SECONDARY)
        c.piece(capsule(elbow, wrist, 2 if tug else 1), PRIMARY)
        c.piece(circle(*shoulder, 2.5), PRIMARY)
        c.piece(circle(*elbow, 2), STEEL_RAMP)
        x, y = wrist
        if tug:
            # Bollard forks with rope wound around the crossbar.
            c.piece(rect(x - 4, y, x + 4, y + 2) | rect(x - 4, y - 2, x - 2, y + 4) |
                    rect(x + 2, y - 2, x + 4, y + 4), SECONDARY)
            c.fill(rect(x - 1, y, x + 1, y + 2), SHADOW)
            c.fill(line((x - 1, y + 1), (x + 1, y + 1)), HI)
        else:
            # Hanging service lanterns with distinctly open handles.
            c.piece(line((x - 2, y), (x - 2, y + 2)) | line((x + 2, y), (x + 2, y + 2)) |
                    line((x - 2, y), (x + 2, y)), STEEL_RAMP)
            c.piece(rrect(x - 3, y + 2, x + 3, y + 7), PRIMARY)
            c.fill(rect(x - 1, y + 3, x + 1, y + 5), GLASS_HI if f == SLEEP else GLOW)
            c.dot(x, y + 4, GLASS if f == SLEEP else HOT)


def arms_1(c, f):
    arms(c, f, False)


def arms_2(c, f):
    arms(c, f, True)


def legs(c, f, tug):
    for side in (0, 1):
        out, lift, bend = leg_state(f, side)
        ax, ground = 24 - out, 61 - lift
        hip, knee = (26, 44), (25 - out / 2 - bend, 49 - lift / 2)
        pieces = [(circle(*hip, 2), STEEL_RAMP), (capsule(hip, knee, 1), STEEL_RAMP),
                  (capsule(knee, (ax, ground - 3), 2), SECONDARY), (circle(*knee, 2), PRIMARY)]
        if tug:
            pieces += [(poly([(ax - 3, ground - 5), (ax + 2, ground - 5), (ax + 5, ground - 2),
                              (ax + 5, ground), (ax - 5, ground), (ax - 5, ground - 2)]), PRIMARY),
                       (rect(ax - 5, ground - 1, ax + 5, ground), (OUT, SHADOW, HI))]
        else:
            # Split masonry feet echo the tower's broad foundation plinth.
            pieces += [(rect(ax - 3, ground - 5, ax + 3, ground - 2), PRIMARY),
                       (poly([(ax - 4, ground - 2), (ax + 4, ground - 2),
                              (ax + 6, ground), (ax - 6, ground)]), SECONDARY)]
        for shape, ramp in pieces:
            c.piece(mirror(shape) if side else shape, ramp)
        stripe = rect(ax - 1, ground - 4, ax + 1, ground - 3)
        c.fill(mirror(stripe) if side else stripe, HI)


def legs_1(c, f):
    legs(c, f, False)


def legs_2(c, f):
    legs(c, f, True)


PARTS = [
    ("nautical_head_1", "HEAD", "Lantern room", head_1),
    ("nautical_head_2", "HEAD", "Tug wheelhouse", head_2),
    ("nautical_body_1", "BODY", "Striped beacon", body_1),
    ("nautical_body_2", "BODY", "Fender hull", body_2),
    ("nautical_arms_1", "ARMS", "Service lanterns", arms_1),
    ("nautical_arms_2", "ARMS", "Bollard forks", arms_2),
    ("nautical_legs_1", "LEGS", "Foundation feet", legs_1),
    ("nautical_legs_2", "LEGS", "Deck boots", legs_2),
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
    board = Image.new("RGB", (1536, 450), "#DEE4DF")
    d = ImageDraw.Draw(board)
    for row, title in enumerate(("Lighthouse keeper", "Tugboat deckhand")):
        d.text((12, row * 224 + 8), title, fill="#18232D")
        combo = {s: f"{FAMILY}_{s.lower()}_{row + 1}" for s in ("HEAD", "BODY", "ARMS", "LEGS")}
        for frame in range(8):
            sprite = assemble(sheets, combo, frame).resize((192, 192), Image.Resampling.NEAREST)
            board.paste(sprite, (frame * 192, row * 224 + 28), sprite)
    board.save(REVIEW / "board.png")
    print(f"Authored {FAMILY}: 8 sheets, 8 masks, catalog; review {REVIEW / 'board.png'}")


if __name__ == "__main__":
    main()
