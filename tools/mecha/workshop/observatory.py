#!/usr/bin/env python3
"""Author the telescope curator and astrolabe tracker on the shared eight-pose rig.

Writes production sheets, paired paint masks and catalog; review boards go to
~/.cache/sortie-mecha-lab. Primary enamel and secondary brass are paintable;
optics, face lights, mechanical joints and engraved stars stay fixed.
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

FAMILY = 'observatory'
ROOT = Path(__file__).resolve().parents[3]
ASSETS = ROOT / 'mecha/src/main/assets/mecha' / FAMILY
CACHE = Path.home() / '.cache/sortie-mecha-lab'
PRIMARY = tuple(map(hexc, ('244D57', '397D86', '77B7B4')))
SECONDARY = tuple(map(hexc, ('83613F', 'C49B57', 'F0D49B')))
GLASS = tuple(map(hexc, ('172C47', '305178', '6B98B4')))
LIGHT = hexc('FBE5AD')


def face(c, f, left, right, y):
    for x in (left, right):
        if f == SLEEP:
            c.fill(line((x - 1, y + 1), (x + 1, y + 1)), STEEL)
        elif f == CHEER:
            c.fill(line((x - 1, y), (x, y - 1)) | line((x, y - 1), (x + 1, y)), LIGHT)
        else:
            shift = -1 if f == WALK_L else 1 if f == WALK_R else 0
            c.fill(rect(x + shift, y, x + shift + 1, y + (0 if f == DETAIL else 2)), LIGHT)


def head_1(c, f):
    """A long working telescope above an unobscured, bespectacled face."""
    c.piece(rect(29, 21, 35, 26), STEEL_RAMP)
    c.piece(rrect(23, 13, 40, 23, 2), PRIMARY)
    c.piece(rrect(25, 16, 38, 21, 1), GLASS)
    face(c, f, 27, 34, 17)
    c.fill(line((30, 20), (33, 20)), SECONDARY[1])
    c.piece(rect(29, 10, 34, 13), SECONDARY)
    c.piece(poly([(17, 6), (39, 6), (42, 9), (39, 12), (17, 12)]), PRIMARY)
    c.piece(rect(16, 5, 21, 13), SECONDARY)
    c.piece(ellipse(16, 9, 2, 4), GLASS)
    c.dot(15, 7, HI)
    c.piece(rect(39, 8, 45, 10), STEEL_RAMP)
    c.fill(rect(24, 7, 34, 8), PRIMARY[2])
    c.piece(rect(23, 22, 40, 24), SECONDARY)


def head_2(c, f):
    """Open armillary crown around a narrow angular star-tracker face."""
    c.piece(rect(29, 22, 35, 26), STEEL_RAMP)
    ring = ellipse(32, 12, 13, 10) - ellipse(32, 12, 10, 7)
    c.piece(ring, SECONDARY)
    c.fill(line((21, 17), (42, 7)), SECONDARY[2])
    c.piece(rect(31, 1, 33, 5), PRIMARY)
    c.piece(poly([(25, 10), (37, 10), (40, 16), (36, 24), (28, 24), (24, 17)]), PRIMARY)
    c.piece(poly([(27, 14), (36, 14), (37, 19), (27, 20)]), GLASS)
    face(c, f, 28, 33, 16)
    c.fill(rect(29, 22, 34, 22), SECONDARY[1])
    c.dot(21, 10, LIGHT)
    c.dot(42, 15, LIGHT)


def base_body(c):
    for x in (19, 45):
        c.piece(circle(x, 29, 2), STEEL_RAMP)
    c.piece(rrect(24, 39, 40, 44, 1), SECONDARY)
    c.fill(rect(29, 42, 35, 43), PRIMARY[0])


def body_1(c, f):
    """Curator's chart cabinet with open folio and offset focus wheel."""
    base_body(c)
    c.piece(poly([(23, 25), (40, 25), (43, 39), (21, 39)]), PRIMARY)
    c.piece(poly([(24, 28), (31, 27), (32, 29), (33, 27), (39, 28), (39, 37), (33, 36), (31, 37), (24, 36)]), SECONDARY)
    c.fill(rect(26, 29, 30, 34), GLASS[0])
    c.fill(rect(34, 29, 37, 34), GLASS[0])
    c.fill(line((27, 32), (29, 30)) | line((34, 31), (36, 33)), HI)
    for x, y in ((27, 32), (29, 30), (34, 31), (36, 33)):
        c.dot(x, y, LIGHT)
    c.fill(line((32, 29), (32, 35)), SECONDARY[0])
    c.piece(circle(42, 33, 2), SECONDARY)
    c.dot(42, 33, STEEL)
    c.fill(rect(25, 40, 28, 41), PRIMARY[2])


def body_2(c, f):
    """An annular mechanical sky computer with visible transverse scales."""
    base_body(c)
    c.piece(poly([(26, 25), (38, 25), (43, 31), (39, 40), (25, 40), (21, 31)]), SECONDARY)
    c.piece(circle(32, 32, 8), PRIMARY)
    c.piece(circle(32, 32, 6), GLASS)
    c.fill(ellipse(32, 32, 5, 2) - ellipse(32, 32, 4, 1), SECONDARY[1])
    c.fill(line((29, 28), (35, 36)), SECONDARY[2])
    c.fill(line((28, 35), (36, 29)), SECONDARY[1])
    c.piece(circle(32, 32, 1), STEEL_RAMP)
    for x, y in ((32, 26), (26, 32), (38, 32), (32, 38)):
        c.dot(x, y, LIGHT)
    c.piece(rect(29, 24, 35, 26), PRIMARY)


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
        c.piece(capsule(shoulder, elbow, 2 if variant == 1 else 1.3), PRIMARY)
        c.piece(circle(*elbow, 2), STEEL_RAMP)
        c.piece(capsule(elbow, wrist, 2.5 if variant == 1 else 1.5), SECONDARY)
        x, y = wrist
        c.piece(rrect(x - 2, y - 1, x + 2, y + 3, 1), PRIMARY)
        if variant == 1:
            # Lens-cleaning paddles, distinct from the tracker's open calipers.
            c.piece(rect(x - 3, y + 3, x + 3, y + 5), SECONDARY)
            c.fill(rect(x - 2, y + 5, x + 2, y + 5), HI)
            c.piece(circle(*shoulder, 3), PRIMARY)
        else:
            c.piece(circle(*shoulder, 3) - circle(*shoulder, 1), SECONDARY)
            for dx in (-3, 3):
                c.piece(line((x + dx, y + 1), (x + dx, y + 5)), STEEL_RAMP)
                c.dot(x + dx, y + 5, SECONDARY[2])


def arms_1(c, f):
    arms(c, f, 1)


def arms_2(c, f):
    arms(c, f, 2)


def legs(c, f, variant):
    for side in (0, 1):
        outward, lift, bend = leg_state(f, side)
        ax, ground = 24 - outward, 61 - lift
        knee = (25 - bend * .6, 50 - lift * .4)
        parts = [(circle(26, 44, 2), STEEL_RAMP), (capsule((26, 44), knee, 1.8), PRIMARY), (circle(*knee, 2), SECONDARY)]
        if variant == 1:
            parts += [(capsule(knee, (ax, ground - 4), 2.2), PRIMARY), (rrect(ax - 4, ground - 4, ax + 3, ground, 1), SECONDARY), (rect(ax - 4, ground, ax + 3, ground), RUBBER_RAMP)]
        else:
            # Split leveling feet and exposed long screw-jacks.
            parts += [(capsule(knee, (ax, ground - 3), 1), STEEL_RAMP), (rect(ax - 2, ground - 8, ax + 2, ground - 6), SECONDARY), (poly([(ax, ground - 4), (ax - 5, ground), (ax - 2, ground), (ax, ground - 1), (ax + 2, ground), (ax + 4, ground)]), PRIMARY)]
        for mask, ramp in parts:
            c.piece(mirror(mask) if side else mask, ramp)


def legs_1(c, f):
    legs(c, f, 1)


def legs_2(c, f):
    legs(c, f, 2)


PARTS = [
    ('observatory_head_1', 'HEAD', 'Curator telescope', head_1),
    ('observatory_head_2', 'HEAD', 'Armillary crown', head_2),
    ('observatory_body_1', 'BODY', 'Open star atlas', body_1),
    ('observatory_body_2', 'BODY', 'Astrolabe computer', body_2),
    ('observatory_arms_1', 'ARMS', 'Lens care paddles', arms_1),
    ('observatory_arms_2', 'ARMS', 'Sky calipers', arms_2),
    ('observatory_legs_1', 'LEGS', 'Quiet gallery shoes', legs_1),
    ('observatory_legs_2', 'LEGS', 'Leveling jack feet', legs_2),
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
    for variant, title in ((1, 'TELESCOPE CURATOR'), (2, 'ASTROLABE TRACKER')):
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
