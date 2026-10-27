#!/usr/bin/env python3
"""Author solar technician and foil-insulated relay on the shared eight-pose rig.

Solar cells are dark fixed glass; all joints, eyes and telemetry lights remain
fixed. Painted blue shell and ochre foil are explicit primary/secondary ramps.
No pressure bubbles, receiver dishes or orbital-family silhouettes are reused.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path
from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from courier import (
    OUT, SHADOW, STEEL, HI, STEEL_RAMP, RUBBER_RAMP, hexc,
    rect, rrect, ellipse, circle, capsule, line, poly, mirror, mirror_pt,
    leg_state, arm_phase, FORWARD, BACK, NEUTRAL, SLEEP, CHEER, DETAIL,
    WALK_L, WALK_R, render_sheet, assemble,
)
from paint_roles import export_part

FAMILY = 'satellite'
ROOT = Path(__file__).resolve().parents[3]
ASSETS = ROOT / 'mecha/src/main/assets/mecha' / FAMILY
CACHE = Path.home() / '.cache/sortie-mecha-lab'
PRIMARY = tuple(map(hexc, ('414B70', '737FA5', 'B3C1DE')))
SECONDARY = tuple(map(hexc, ('967340', 'D9AE60', 'F6DEA1')))
CELL = tuple(map(hexc, ('182844', '294462', '4D738E')))
LIGHT = hexc('A9EDF2')


def face(c, f, y, left=28, right=34):
    for x in (left, right):
        if f == SLEEP:
            c.fill(line((x - 1, y + 1), (x + 1, y + 1)), STEEL)
        elif f == CHEER:
            c.fill(line((x - 1, y), (x, y - 1)) | line((x, y - 1), (x + 1, y)), LIGHT)
        else:
            dx = -1 if f == WALK_L else 1 if f == WALK_R else 0
            c.fill(rect(x + dx, y, x + dx + 1, y + (0 if f == DETAIL else 2)), LIGHT)


def panel(c, x0, y0, x1, y1):
    """Paintable frame surrounds fixed photovoltaic cells and fine busbars."""
    c.piece(rect(x0, y0, x1, y1), PRIMARY)
    c.fill(rect(x0 + 1, y0 + 1, x1 - 1, y1 - 1), CELL[0])
    for x in range(x0 + 3, x1, 3):
        c.fill(line((x, y0 + 1), (x, y1 - 1)), CELL[2])
    for y in range(y0 + 3, y1, 3):
        c.fill(line((x0 + 1, y), (x1 - 1, y)), CELL[1])


def head_1(c, f):
    """Flat sensor brow and winged solar sunshade, not a pressure helmet."""
    c.piece(rect(29, 22, 35, 26), STEEL_RAMP)
    c.piece(poly([(25, 11), (38, 11), (40, 21), (35, 24), (28, 24), (23, 20)]), SECONDARY)
    c.piece(rrect(25, 14, 37, 21, 1), CELL)
    face(c, f, 16)
    c.fill(line((29, 21), (34, 21)), STEEL)
    c.piece(rect(25, 9, 39, 12), PRIMARY)
    panel(c, 17, 6, 27, 10)
    panel(c, 36, 6, 46, 10)
    c.piece(rect(29, 5, 34, 10), SECONDARY)
    c.fill(rect(30, 6, 33, 8), CELL[0])
    c.dot(31, 6, HI)


def head_2(c, f):
    """Folded insulation hood with paired square telemetry aerials."""
    c.piece(rect(29, 21, 35, 26), STEEL_RAMP)
    for x in (23, 41):
        c.piece(line((x, 13), (x, 4)), STEEL_RAMP)
        c.piece(rect(x - 2, 2, x + 2, 5), PRIMARY)
        c.dot(x, 3, LIGHT if f != SLEEP else STEEL)
    c.piece(poly([(26, 7), (37, 7), (44, 14), (41, 23), (32, 25), (22, 22), (19, 14)]), SECONDARY)
    c.fill(poly([(21, 14), (27, 9), (25, 20)]), SECONDARY[2])
    c.fill(poly([(37, 9), (42, 14), (39, 22)]), SECONDARY[0])
    c.piece(poly([(27, 12), (36, 12), (39, 17), (35, 22), (28, 22), (24, 17)]), PRIMARY)
    c.piece(rrect(27, 14, 36, 19, 1), CELL)
    face(c, f, 15, 28, 33)
    c.fill(rect(30, 21, 33, 21), LIGHT if f == CHEER else STEEL)


def base_body(c):
    for x in (19, 45):
        c.piece(circle(x, 29, 2), STEEL_RAMP)
    c.piece(rrect(25, 40, 39, 44, 1), PRIMARY)
    c.fill(rect(29, 42, 35, 43), SECONDARY[1])


def body_1(c, f):
    """Solar bus with narrow central chassis and visible rectangular wings."""
    base_body(c)
    panel(c, 18, 26, 25, 38)
    panel(c, 39, 26, 46, 38)
    c.piece(rect(27, 25, 37, 40), SECONDARY)
    c.piece(rect(29, 27, 35, 35), PRIMARY)
    c.fill(rect(30, 28, 34, 31), CELL[0])
    c.fill(line((31, 30), (33, 28)), LIGHT if f != SLEEP else STEEL)
    c.piece(circle(32, 37, 2), STEEL_RAMP)
    c.fill(line((26, 33), (28, 33)) | line((36, 33), (38, 33)), HI)


def body_2(c, f):
    """Diamond-fold quilt around an exposed narrow signal backbone."""
    base_body(c)
    c.piece(poly([(25, 25), (39, 25), (44, 31), (40, 40), (32, 43), (23, 39), (20, 31)]), SECONDARY)
    for pts, color in [([(22, 31), (27, 27), (28, 38)], SECONDARY[2]), ([(28, 38), (32, 41), (26, 39)], SECONDARY[0]), ([(37, 27), (42, 31), (37, 38)], SECONDARY[0]), ([(33, 40), (37, 36), (39, 39)], SECONDARY[2])]:
        c.fill(poly(pts), color)
    c.piece(rect(29, 25, 35, 39), PRIMARY)
    c.fill(rect(30, 27, 34, 34), CELL[0])
    for y in (28, 31, 34):
        c.fill(rect(31, y, 33, y), LIGHT if f != SLEEP else STEEL)
    c.piece(rect(30, 37, 34, 39), STEEL_RAMP)


def arms(c, f, variant):
    for side in (0, 1):
        elbow, wrist = {NEUTRAL: ((15, 35), (14, 41)), FORWARD: ((16, 34), (18, 39)), BACK: ((14, 35), (12, 40))}[arm_phase(f, side)]
        if f == SLEEP:
            elbow, wrist = (16, 35), (19, 40)
        elif f == CHEER:
            elbow, wrist = (13, 25), (12, 18)
        shoulder = (19, 29)
        if side:
            shoulder, elbow, wrist = map(mirror_pt, (shoulder, elbow, wrist))
        c.piece(capsule(shoulder, elbow, 1.4 if variant == 1 else 2.6), SECONDARY)
        c.piece(circle(*elbow, 2), STEEL_RAMP)
        c.piece(capsule(elbow, wrist, 1.5 if variant == 1 else 2.8), SECONDARY)
        x, y = wrist
        if variant == 1:
            # Stiff panel forearm and precision two-jaw connector hand.
            panel(c, x - 3, y - 4, x + 3, y)
            c.piece(rect(x - 2, y + 1, x + 2, y + 2), PRIMARY)
            for dx in (-2, 2):
                c.piece(line((x + dx, y + 2), (x + dx, y + 5)), STEEL_RAMP)
            c.piece(rect(shoulder[0] - 2, 27, shoulder[0] + 2, 30), PRIMARY)
        else:
            c.piece(poly([(x - 3, y - 3), (x, y - 5), (x + 3, y - 2), (x + 2, y + 2), (x - 2, y + 2)]), SECONDARY)
            c.fill(line((x - 2, y - 2), (x + 1, y + 1)), SECONDARY[2])
            c.piece(rrect(x - 2, y + 2, x + 2, y + 5, 1), PRIMARY)
            c.piece(poly([(shoulder[0] - 3, 27), (shoulder[0], 25), (shoulder[0] + 3, 28), (shoulder[0] + 2, 32), (shoulder[0] - 2, 31)]), SECONDARY)


def arms_1(c, f):
    arms(c, f, 1)


def arms_2(c, f):
    arms(c, f, 2)


def legs(c, f, variant):
    for side in (0, 1):
        outward, lift, bend = leg_state(f, side)
        ax, ground = 24 - outward, 61 - lift
        knee = (25 - bend * .6, 50 - lift * .4)
        parts = [(circle(26, 44, 2), STEEL_RAMP), (capsule((26, 44), knee, 1.5 if variant == 1 else 2.5), SECONDARY), (circle(*knee, 2), PRIMARY)]
        if variant == 1:
            parts += [(capsule(knee, (ax, ground - 4), 1), STEEL_RAMP), (rect(ax - 2, ground - 8, ax + 2, ground - 4), PRIMARY), (rect(ax - 1, ground - 7, ax + 1, ground - 5), CELL), (poly([(ax - 2, ground - 3), (ax + 2, ground - 3), (ax + 4, ground), (ax - 4, ground)]), SECONDARY)]
        else:
            # Wrinkled angular insulation boots with narrow dark sole clamps.
            parts += [(poly([(ax - 3, ground - 10), (ax + 3, ground - 9), (ax + 2, ground - 6), (ax + 4, ground - 3), (ax + 3, ground), (ax - 4, ground), (ax - 5, ground - 4)]), SECONDARY), (poly([(ax - 3, ground - 9), (ax + 1, ground - 6), (ax - 3, ground - 4)]), (SECONDARY[2],) * 3), (rect(ax - 4, ground - 1, ax + 3, ground), PRIMARY)]
        for mask, ramp in parts:
            c.piece(mirror(mask) if side else mask, ramp)


def legs_1(c, f):
    legs(c, f, 1)


def legs_2(c, f):
    legs(c, f, 2)


PARTS = [
    ('satellite_head_1', 'HEAD', 'Solar sunshade', head_1),
    ('satellite_head_2', 'HEAD', 'Folded relay hood', head_2),
    ('satellite_body_1', 'BODY', 'Solar bus wings', body_1),
    ('satellite_body_2', 'BODY', 'Quilted relay core', body_2),
    ('satellite_arms_1', 'ARMS', 'Panel connector claws', arms_1),
    ('satellite_arms_2', 'ARMS', 'Foil relay sleeves', arms_2),
    ('satellite_legs_1', 'LEGS', 'Solar strut feet', legs_1),
    ('satellite_legs_2', 'LEGS', 'Folded foil boots', legs_2),
]


def main():
    ASSETS.mkdir(parents=True, exist_ok=True)
    CACHE.mkdir(parents=True, exist_ok=True)
    sheets, catalog = {}, []
    for pid, slot, name, draw in PARTS:
        sheets[pid] = render_sheet(draw)
        export_part(sheets[pid], ASSETS / f'{pid}.png', PRIMARY, SECONDARY)
        catalog.append(dict(id=pid, slot=slot, family=FAMILY, name=name, asset=f'mecha/{FAMILY}/{pid}.png'))
    (ASSETS / 'catalog.json').write_text(json.dumps(catalog, indent=2) + '\n')
    board = Image.new('RGBA', (1056, 380), '#E8E5D8')
    d = ImageDraw.Draw(board)
    for variant, title in ((1, 'SOLAR TECHNICIAN'), (2, 'FOIL-INSULATED RELAY')):
        y = 20 + (variant - 1) * 180
        d.text((16, y), f'{FAMILY.upper()} / {title}', fill='#18232D')
        parts = {slot: f'{FAMILY}_{slot.lower()}_{variant}' for slot in ('HEAD', 'BODY', 'ARMS', 'LEGS')}
        for frame in range(8):
            sprite = assemble(sheets, parts, frame).resize((128, 128), Image.Resampling.NEAREST)
            board.alpha_composite(sprite, (16 + frame * 128, y + 22))
            d.text((16 + frame * 128, y + 153), ('idle', 'detail', 'left', 'pass', 'right', 'pass', 'sleep', 'cheer')[frame], fill='#18232D')
    board.save(CACHE / f'{FAMILY}-board.png')
    print(f'Authored {FAMILY}: 8 sheets, 8 masks, catalog; board {CACHE / (FAMILY + "-board.png")}')


if __name__ == '__main__':
    main()
