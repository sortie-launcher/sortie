#!/usr/bin/env python3
"""Author field transmitter and studio reel-tech production art and paint masks.

Both use only the common eight-pose rig. Olive enamel and red fittings are
paintable; speaker mesh, reels, meters, joints and illuminated faces stay fixed.
Review boards are written outside production in ~/.cache/sortie-mecha-lab.
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

FAMILY = 'radio'
ROOT = Path(__file__).resolve().parents[3]
ASSETS = ROOT / 'mecha/src/main/assets/mecha' / FAMILY
CACHE = Path.home() / '.cache/sortie-mecha-lab'
PRIMARY = tuple(map(hexc, ('565B3C', '92966A', 'CDD1A0')))
SECONDARY = tuple(map(hexc, ('743E38', 'B85E4D', 'E49779')))
SCREEN = tuple(map(hexc, ('142D30', '28504B', '50756B')))
LIGHT = hexc('BAF0B7')


def eyes(c, f, y, left=28, right=35):
    for x in (left, right):
        if f == SLEEP:
            c.fill(line((x - 1, y + 1), (x + 1, y + 1)), STEEL)
        elif f == CHEER:
            c.fill(line((x - 1, y), (x, y - 1)) | line((x, y - 1), (x + 1, y)), LIGHT)
        else:
            dx = -1 if f == WALK_L else 1 if f == WALK_R else 0
            c.fill(rect(x + dx, y, x + dx + 1, y + (0 if f == DETAIL else 2)), LIGHT)


def head_1(c, f):
    """A rectangular field radio with tall whip and two readable face windows."""
    c.piece(rect(29, 21, 35, 26), STEEL_RAMP)
    c.piece(line((22, 12), (20, 2)), STEEL_RAMP)
    c.dot(20, 2, SECONDARY[2])
    c.piece(rect(26, 7, 38, 9) - rect(28, 8, 36, 9), SECONDARY)
    c.piece(rrect(21, 11, 42, 23, 1), PRIMARY)
    c.piece(rrect(24, 13, 38, 19, 1), SCREEN)
    eyes(c, f, 15, 26, 33)
    c.fill(line((28, 21), (34, 21)), OUT)
    c.piece(rect(19, 17, 22, 21), SECONDARY)
    c.piece(circle(41, 14, 2), SECONDARY)
    c.dot(41, 14, HI)
    c.fill(rect(39, 19, 40, 21), OUT)


def head_2(c, f):
    """Compact pear-shaped face enclosed by oversized studio headphones."""
    c.piece(rect(29, 21, 35, 26), STEEL_RAMP)
    c.piece(ellipse(32, 14, 11, 10) - ellipse(32, 15, 8, 9), SECONDARY)
    c.piece(poly([(26, 9), (37, 9), (40, 14), (36, 24), (28, 24), (24, 15)]), PRIMARY)
    c.piece(rrect(26, 12, 37, 20, 2), SCREEN)
    eyes(c, f, 14, 28, 33)
    c.fill(line((30, 19), (33, 19)), LIGHT if f == CHEER else STEEL)
    for x in (21, 42):
        c.piece(rrect(x - 2, 12, x + 2, 20, 1), SECONDARY)
        c.fill(rect(x - 1, 14, x, 18), SHADOW)
    c.piece(line((43, 20), (37, 23)), STEEL_RAMP)
    c.piece(rect(34, 22, 37, 24), RUBBER_RAMP)


def base_body(c):
    for x in (19, 45):
        c.piece(circle(x, 29, 2), STEEL_RAMP)
    c.piece(rect(25, 39, 39, 44), SECONDARY)
    c.fill(rect(30, 41, 34, 43), STEEL)


def body_1(c, f):
    """Box transmitter, speaker grille, tuning meter and retained handset."""
    base_body(c)
    c.piece(rect(22, 25, 42, 39), PRIMARY)
    c.piece(rect(24, 27, 34, 37), RUBBER_RAMP)
    for y in (28, 31, 34, 36):
        c.fill(line((25, y), (33, y)), STEEL)
    c.piece(rect(36, 27, 40, 31), SECONDARY)
    c.fill(rect(37, 28, 39, 30), HI)
    c.fill(line((37, 30), (39, 28)), OUT)
    c.piece(circle(38, 35, 2), SECONDARY)
    c.dot(38, 34, HI)
    c.piece(capsule((19, 26), (19, 36), 1.4), SECONDARY)
    c.fill(line((20, 35), (20, 39)) | line((20, 39), (25, 40)), OUT)
    c.fill(line((24, 26), (33, 26)), PRIMARY[2])


def body_2(c, f):
    """Sloped tape deck with twin open reels and a row of transport keys."""
    base_body(c)
    c.piece(poly([(25, 25), (39, 25), (43, 38), (39, 41), (25, 41), (21, 38)]), SECONDARY)
    c.piece(rrect(24, 27, 40, 36, 2), PRIMARY)
    for x in (27, 37):
        c.piece(circle(x, 30, 4), STEEL_RAMP)
        for dx, dy in ((-2, 0), (1, -2), (1, 2)):
            c.dot(x + dx, 30 + dy, OUT)
        c.piece(circle(x, 30, 1), SECONDARY)
    c.fill(line((29, 34), (35, 34)), OUT)
    for x in (26, 29, 32, 35, 38):
        c.fill(rect(x, 37, x + 1, 38), HI if x != 35 else SECONDARY[0])
    c.dot(40, 35, LIGHT if f != SLEEP else SHADOW)


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
        c.piece(capsule(shoulder, elbow, 2.4 if variant == 1 else 1.5), PRIMARY)
        c.piece(circle(*elbow, 2), STEEL_RAMP)
        c.piece(capsule(elbow, wrist, 2.2 if variant == 1 else 1.6), SECONDARY)
        x, y = wrist
        if variant == 1:
            c.piece(rect(shoulder[0] - 3, 27, shoulder[0] + 3, 30), SECONDARY)
            c.piece(rrect(x - 2, y - 1, x + 2, y + 4, 1), PRIMARY)
            # Integral cable-test cuff, its indicator is fixed glass.
            c.fill(rect(x - 1, y, x + 1, y + 1), SCREEN[0])
            c.dot(x, y, LIGHT)
        else:
            c.piece(circle(*shoulder, 2.5), SECONDARY)
            c.piece(circle(x, y, 2), PRIMARY)
            # Delicate three-finger fader hands, not field mittens.
            for dx in (-2, 0, 2):
                c.piece(line((x + dx, y + 2), (x + dx, y + 5)), STEEL_RAMP)
            c.fill(line((x - 1, y - 2), (x + 1, y - 2)), SECONDARY[2])


def arms_1(c, f):
    arms(c, f, 1)


def arms_2(c, f):
    arms(c, f, 2)


def legs(c, f, variant):
    for side in (0, 1):
        outward, lift, bend = leg_state(f, side)
        ax, ground = 24 - outward, 61 - lift
        knee = (25 - bend * .6, 50 - lift * .4)
        parts = [(circle(26, 44, 2), STEEL_RAMP), (capsule((26, 44), knee, 2.3 if variant == 1 else 1.4), PRIMARY), (circle(*knee, 2), SECONDARY)]
        if variant == 1:
            parts += [(poly([(ax - 3, ground - 10), (ax + 2, ground - 10), (ax + 3, ground - 3), (ax - 3, ground - 3)]), PRIMARY), (rect(ax - 3, ground - 7, ax + 2, ground - 6), SECONDARY), (rrect(ax - 5, ground - 3, ax + 3, ground, 1), RUBBER_RAMP)]
        else:
            # Slender studio stand ankles in broad felt-soled slippers.
            parts += [(capsule(knee, (ax, ground - 3), 1), STEEL_RAMP), (rect(ax - 2, ground - 6, ax + 2, ground - 4), SECONDARY), (poly([(ax - 2, ground - 3), (ax + 2, ground - 3), (ax + 4, ground), (ax - 5, ground), (ax - 5, ground - 1)]), PRIMARY), (rect(ax - 5, ground, ax + 4, ground), RUBBER_RAMP)]
        for mask, ramp in parts:
            c.piece(mirror(mask) if side else mask, ramp)


def legs_1(c, f):
    legs(c, f, 1)


def legs_2(c, f):
    legs(c, f, 2)


PARTS = [
    ('radio_head_1', 'HEAD', 'Whip antenna face', head_1),
    ('radio_head_2', 'HEAD', 'Studio headphones', head_2),
    ('radio_body_1', 'BODY', 'Field transmitter', body_1),
    ('radio_body_2', 'BODY', 'Twin reel tape deck', body_2),
    ('radio_arms_1', 'ARMS', 'Signal test mittens', arms_1),
    ('radio_arms_2', 'ARMS', 'Fader fingers', arms_2),
    ('radio_legs_1', 'LEGS', 'Field gaiters', legs_1),
    ('radio_legs_2', 'LEGS', 'Quiet studio slippers', legs_2),
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
    for variant, title in ((1, 'FIELD TRANSMITTER'), (2, 'STUDIO REEL-TECH')):
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
