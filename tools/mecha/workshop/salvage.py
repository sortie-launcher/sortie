#!/usr/bin/env python3
"""Salvage: magnetic scrapyard retriever and winch wreck-recovery machine."""
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

FAMILY = "salvage"
DEST = MECHA.parents[1] / "mecha/src/main/assets/mecha" / FAMILY
REVIEW = Path(__file__).resolve().parent / "assets" / FAMILY
PRIMARY = tuple(map(hexc, ("854D3F", "BB7651", "E6AA78")))
SECONDARY = tuple(map(hexc, ("53613E", "839064", "B9C28E")))
GLASS, GLASS_HI, GLOW, HOT = map(hexc, ("283640", "577383", "F3D588", "FFF2CE"))


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
    # An open horseshoe flux yoke crowns a broad, low scanning head.
    c.piece(rect(29, 20, 35, 26), STEEL_RAMP)
    c.piece(rrect(21, 12, 43, 23, 2), PRIMARY)
    c.piece(rect(24, 3, 28, 13) | rect(36, 3, 40, 13) | rect(27, 9, 37, 13), SECONDARY)
    c.fill(rect(24, 3, 28, 5) | rect(36, 3, 40, 5), HI)
    c.fill(rect(24, 15, 40, 20), GLASS)
    face(c, 32, 17, f)
    c.piece(rect(19, 15, 21, 20), STEEL_RAMP)
    c.piece(rect(43, 15, 45, 20), STEEL_RAMP)
    c.dot(23, 22, HI)
    c.dot(41, 22, HI)


def head_2(c, f):
    # Asymmetric little crane cab with projecting roof and one signal pennant.
    c.piece(rect(29, 21, 35, 26), STEEL_RAMP)
    c.piece(poly([(22, 10), (35, 7), (41, 12), (41, 23), (23, 23)]), SECONDARY)
    c.piece(poly([(19, 9), (36, 5), (43, 10), (43, 12), (20, 12)]), PRIMARY)
    c.fill(rect(24, 14, 37, 20), GLASS)
    face(c, 31, 17, f)
    c.piece(circle(42, 19, 3), PRIMARY)
    c.fill(line((40, 19), (44, 19)), SHADOW)
    c.piece(line((38, 7), (40, 2)), STEEL_RAMP)
    c.fill(poly([(41, 2), (46, 3), (41, 5)]), GLOW)


def sockets(c):
    for x in (19, 45):
        c.piece(circle(x, 29, 3), STEEL_RAMP)
    c.piece(rect(25, 40, 39, 45), STEEL_RAMP)


def body_1(c, f):
    # Cut-corner scrap hopper, filled with fixed neutral recovered metal.
    sockets(c)
    c.piece(poly([(20, 26), (44, 26), (42, 40), (38, 44), (26, 44), (22, 40)]), PRIMARY)
    c.piece(rect(23, 27, 41, 32), SECONDARY)
    c.fill(rect(25, 28, 39, 30), GLASS)
    c.fill(poly([(26, 30), (28, 27), (31, 29), (33, 27), (36, 30), (39, 28)]), HI)
    c.piece(circle(32, 37, 4), SECONDARY)
    c.fill(rect(30, 35, 31, 39) | rect(33, 35, 34, 39) | rect(31, 38, 33, 39), SHADOW)
    c.dot(23, 35, GLASS_HI if f == SLEEP else GLOW)
    c.dot(41, 35, HI)


def body_2(c, f):
    # Tall gantry framing a wide rope drum; the silhouette is open at the shoulders.
    sockets(c)
    c.piece(rect(23, 24, 26, 42) | rect(38, 24, 41, 42) | rect(24, 24, 40, 27), PRIMARY)
    c.piece(rect(22, 40, 42, 44), SECONDARY)
    c.piece(rect(25, 30, 39, 38), STEEL_RAMP)
    for x in range(27, 38, 2):
        c.fill(line((x, 31), (x, 37)), SHADOW)
    c.piece(rect(22, 29, 25, 39), SECONDARY)
    c.piece(rect(39, 29, 42, 39), SECONDARY)
    c.piece(circle(32, 34, 3), PRIMARY)
    c.fill(line((30, 34), (34, 34)), HI)
    c.dot(32, 33 if f in (DETAIL, WALK_L) else 35, SHADOW)
    c.fill(line((32, 38), (32, 42)), HI)


def arms(c, f, winch):
    for side in (0, 1):
        elbow, wrist = {"neutral": ((14, 35), (12, 41)), "forward": ((16, 34), (16, 39)),
                        "back": ((13, 34), (10, 40))}[arm_phase(f, side)]
        if f == SLEEP:
            elbow, wrist = (16, 36), (17, 42)
        elif f == CHEER:
            elbow, wrist = (12, 24), (11, 17)
        shoulder = (19, 29)
        if side:
            shoulder, elbow, wrist = map(mirror_pt, (shoulder, elbow, wrist))
        c.piece(capsule(shoulder, elbow, 2.3 if winch else 1.5), PRIMARY)
        c.piece(capsule(elbow, wrist, 1), STEEL_RAMP)
        c.piece(circle(*shoulder, 3), SECONDARY)
        c.piece(circle(*elbow, 2), SECONDARY)
        x, y = wrist
        if winch:
            # Pulley cheeks and suspended open hook, rather than humanoid fists.
            c.piece(circle(x, y, 3), PRIMARY)
            c.fill(circle(x, y, 1), SHADOW)
            c.piece(line((x, y + 2), (x, y + 5)) | line((x, y + 5), (x + 3, y + 6)) |
                    line((x + 3, y + 6), (x + 4, y + 3)), STEEL_RAMP)
        else:
            c.piece(rect(x - 4, y - 2, x + 4, y + 1) |
                    rect(x - 4, y, x - 2, y + 5) | rect(x + 2, y, x + 4, y + 5), PRIMARY)
            c.fill(rect(x - 4, y + 4, x - 2, y + 5) | rect(x + 2, y + 4, x + 4, y + 5), HI)
            c.fill(rect(x - 2, y - 2, x + 2, y - 1), SECONDARY[1])


def arms_1(c, f):
    arms(c, f, False)


def arms_2(c, f):
    arms(c, f, True)


def legs(c, f, winch):
    for side in (0, 1):
        out, lift, bend = leg_state(f, side)
        ax, ground = 24 - out, 61 - lift
        hip, knee = (26, 44), (25 - out / 2 - bend, 49 - lift / 2)
        pieces = [(circle(*hip, 2), STEEL_RAMP), (capsule(hip, knee, 1.5), STEEL_RAMP),
                  (capsule(knee, (ax, ground - 3), 2 if winch else 2.5), PRIMARY),
                  (circle(*knee, 2), SECONDARY)]
        if winch:
            pieces += [(poly([(ax - 2, ground - 5), (ax + 2, ground - 5), (ax + 5, ground),
                              (ax - 6, ground), (ax - 6, ground - 2)]), SECONDARY),
                       (rect(ax - 6, ground - 1, ax + 5, ground), STEEL_RAMP)]
        else:
            # Short individual track shoes still lift with the shared biped gait.
            pieces += [(rrect(ax - 6, ground - 5, ax + 5, ground, 2), (OUT, SHADOW, HI)),
                       (rect(ax - 4, ground - 4, ax + 3, ground - 2), SECONDARY)]
        for shape, ramp in pieces:
            c.piece(mirror(shape) if side else shape, ramp)
        if not winch:
            for dx in (-3, 0, 3):
                shape = rect(ax + dx, ground - 3, ax + dx + 1, ground - 2)
                c.fill(mirror(shape) if side else shape, SHADOW)


def legs_1(c, f):
    legs(c, f, False)


def legs_2(c, f):
    legs(c, f, True)


PARTS = [
    ("salvage_head_1", "HEAD", "Flux scanner", head_1),
    ("salvage_head_2", "HEAD", "Crane lookout", head_2),
    ("salvage_body_1", "BODY", "Scrap hopper", body_1),
    ("salvage_body_2", "BODY", "Recovery winch", body_2),
    ("salvage_arms_1", "ARMS", "Magnetic yokes", arms_1),
    ("salvage_arms_2", "ARMS", "Pulley hooks", arms_2),
    ("salvage_legs_1", "LEGS", "Crawler shoes", legs_1),
    ("salvage_legs_2", "LEGS", "Outrigger feet", legs_2),
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
    board = Image.new("RGB", (1536, 450), "#E2DFD3")
    d = ImageDraw.Draw(board)
    for row, title in enumerate(("Magnetic scrapyard retriever", "Winch wreck-recovery")):
        d.text((12, row * 224 + 8), title, fill="#18232D")
        combo = {s: f"{FAMILY}_{s.lower()}_{row + 1}" for s in ("HEAD", "BODY", "ARMS", "LEGS")}
        for frame in range(8):
            sprite = assemble(sheets, combo, frame).resize((192, 192), Image.Resampling.NEAREST)
            board.paste(sprite, (frame * 192, row * 224 + 28), sprite)
    board.save(REVIEW / "board.png")
    print(f"Authored {FAMILY}: 8 sheets, 8 masks, catalog; review {REVIEW / 'board.png'}")


if __name__ == "__main__":
    main()
