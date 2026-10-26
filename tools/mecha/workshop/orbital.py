#!/usr/bin/env python3
"""Original orbital-maintenance parts; run with Python/Pillow, no shipped writes.

Eight 512x64 RGBA sheets share the courier rig and integer-mask primitives.
Output: workshop/assets/orbital/{orbital_*.png,catalog.json,board.png,preview.gif}.
Frames: idle, detail, left contact, passing, right contact, passing, sleep, cheer.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from courier import (  # Existing rig and hard-edged drawing vocabulary, not existing art.
    Canvas, OUT, SHADOW, STEEL, HI, STEEL_RAMP, RUBBER_RAMP,
    rect, rrect, ellipse, circle, capsule, line, poly, mirror, mirror_pt,
    leg_state, arm_phase, FORWARD, BACK, NEUTRAL, unit, hexc,
    IDLE, DETAIL, WALK_L, PASS_A, WALK_R, PASS_B, SLEEP, CHEER,
    render_sheet, assemble,
)

ROOT = Path(__file__).resolve().parents[3]
ASSETS = Path(__file__).resolve().parent / 'assets/orbital'
CREAM = (hexc('999985'), hexc('DDD8BC'), hexc('FFF0CE'))
VIOLET = (hexc('45415F'), hexc('77718E'), hexc('ADA4BD'))
AMBER = (hexc('A85D35'), hexc('E99C4C'), hexc('FFD68C'))
GLASS = (OUT, SHADOW, STEEL)


def head_1(c, f):
    """Pressure bell: broad sealed bubble visor, offset inspection lamp."""
    c.piece(rect(29, 21, 35, 26), STEEL_RAMP)
    c.piece(ellipse(32, 14, 12, 10), CREAM)
    c.piece(ellipse(30, 13, 8.5, 7), GLASS)
    c.fill(line((24, 10), (26, 8)), HI)
    c.dot(27, 8, CREAM[2])
    if f == SLEEP:
        c.fill(line((27, 16), (33, 16)), AMBER[0])
    elif f == DETAIL:
        c.fill(line((26, 15), (34, 15)), AMBER[1])
    else:
        gaze = -1 if f == WALK_L else 1 if f == WALK_R else 0
        for x in (27 + gaze, 33 + gaze):
            c.fill(rect(x, 13, x + 1, 15), AMBER[2])
            if f == CHEER:
                c.dot(x, 15, SHADOW)
    c.piece(rrect(21, 21, 43, 24, 1), VIOLET)
    c.fill(line((25, 22), (37, 22)), CREAM[1])
    c.piece(rect(40, 11, 44, 17), CREAM)
    c.piece(rect(41, 12, 45, 15), AMBER if f != SLEEP else STEEL_RAMP)
    c.fill(rect(42, 12, 44, 13), CREAM[2] if f != SLEEP else SHADOW)
    c.fill(rect(40, 19, 41, 20), VIOLET[0])


def head_2(c, f):
    """Relay receiver: low radio face with one asymmetric parabolic ear."""
    c.piece(rect(28, 21, 36, 26), STEEL_RAMP)
    c.piece(line((25, 11), (23 if f == SLEEP else 24, 4)), STEEL_RAMP)
    c.dot(24, 4, AMBER[1] if f != SLEEP else STEEL)
    # Dish is an asymmetric open bowl, not a weapon or pointed horn.
    c.piece(line((39, 15), (44, 9)), STEEL_RAMP)
    c.piece(poly([(36, 5), (39, 6), (42, 9), (44, 13), (41, 13), (38, 11), (36, 8)]), CREAM)
    c.fill(line((37, 6), (42, 11)), VIOLET[1])
    c.piece(line((41, 8), (45, 4)), STEEL_RAMP)
    c.dot(45, 4, AMBER[2] if f in (DETAIL, CHEER) else AMBER[1])
    c.piece(rrect(21, 12, 42, 23, 2), VIOLET)
    c.piece(rect(19, 15, 21, 20), CREAM)
    c.piece(rrect(24, 14, 36, 19, 1), CREAM)
    c.fill(rect(25, 15, 35, 18), OUT)
    if f == SLEEP:
        c.fill(line((26, 17), (34, 17)), VIOLET[1])
    else:
        c.fill(line((26, 17), (34, 17)), AMBER[0])
        tick = {DETAIL: 33, WALK_L: 28, WALK_R: 32, CHEER: 30}.get(f, 30)
        c.fill(line((tick, 15), (tick, 18)), AMBER[2])
        if f == CHEER:
            c.fill(line((27, 16), (33, 16)), AMBER[2])
    for x in (26, 29, 32):
        c.dot(x, 21, OUT)
    c.piece(circle(39, 18, 1.6), STEEL_RAMP)
    c.dot(39, 18, AMBER[1])


def base_body(c):
    for x in (20, 44):
        c.piece(circle(x, 29, 2.7), STEEL_RAMP)
    c.piece(rrect(25, 39, 39, 44, 2), VIOLET)
    c.piece(rect(29, 36, 35, 40), STEEL_RAMP)
    for x in (26, 38):
        c.dot(x, 43, HI)


def body_1(c, f):
    """Tether tender: a conspicuous cable reel in a rounded pressure chest."""
    base_body(c)
    c.piece(rrect(21, 25, 43, 39, 4), CREAM)
    c.piece(rect(26, 24, 38, 26), VIOLET)
    c.piece(circle(31, 32, 6), VIOLET)
    c.piece(circle(31, 32, 4.5), AMBER)
    c.piece(circle(31, 32, 2.5), GLASS)
    # Reel spokes rotate by a quarter turn on walk frames.
    diagonal = f in (DETAIL, WALK_L, WALK_R, CHEER)
    for a, b in ([((28, 29), (34, 35)), ((28, 35), (34, 29))] if diagonal
                 else [((27, 32), (35, 32)), ((31, 28), (31, 36))]):
        c.fill(line(a, b), VIOLET[2])
    c.piece(circle(31, 32, 1), STEEL_RAMP)
    # Short retained cable loop and captive snap: never extends outside body rig.
    c.piece(line((36, 33), (39, 36)), RUBBER_RAMP)
    c.piece(line((39, 36), (38, 39)), RUBBER_RAMP)
    c.piece(rrect(36, 37, 39, 40, 1) - rect(37, 38, 38, 39), STEEL_RAMP)
    c.fill(rect(40, 28, 41, 30), AMBER[2] if f != SLEEP else SHADOW)
    c.dot(23, 30, VIOLET[0])
    c.dot(23, 33, VIOLET[0])


def body_2(c, f):
    """Service harness: twin upright supply bottles around a narrow console."""
    base_body(c)
    c.piece(poly([(25, 25), (39, 25), (41, 34), (36, 40), (28, 40), (23, 34)]), VIOLET)
    for x in (23, 41):
        c.piece(rrect(x - 3, 24, x + 3, 37, 2), CREAM)
        c.fill(rect(x - 2, 29, x + 2, 30), AMBER[1])
        c.piece(rect(x - 1, 23, x + 1, 24), STEEL_RAMP)
        c.fill(line((x - 1, 26), (x - 1, 28)), CREAM[2])
    c.piece(rect(28, 25, 36, 35), CREAM)
    c.piece(rect(29, 27, 35, 31), GLASS)
    if f != SLEEP:
        c.fill(line((30, 29), (34, 29)), AMBER[1])
        c.dot(33 if f == DETAIL else 31, 28, AMBER[2])
    c.dot(30, 33, VIOLET[0])
    c.dot(34, 33, AMBER[1])
    c.piece(rect(26, 37, 38, 39), CREAM)
    c.piece(rect(30, 37, 34, 39), AMBER)
    # Umbilical carries from the left bottle to the belt connector.
    c.piece(line((24, 35), (26, 40)), RUBBER_RAMP)
    c.fill(line((24, 35), (26, 40)), STEEL)


def arms(c, f, variant):
    for side in (0, 1):
        phase = arm_phase(f, side)
        elbow, wrist = {
            NEUTRAL: ((15, 36), (14, 42)),
            FORWARD: ((16, 34), (17, 39)),
            BACK: ((14, 35), (12, 41)),
        }[phase]
        if f == SLEEP:
            elbow, wrist = (16, 36), (18, 41)
        elif f == CHEER:
            elbow, wrist = (12, 24), (11, 17)
        shoulder = (19, 29)
        if side:
            shoulder, elbow, wrist = map(mirror_pt, (shoulder, elbow, wrist))
        dx, dy = unit(wrist[0] - elbow[0], wrist[1] - elbow[1])
        px, py = -dy, dx
        if variant == 1:
            # Puffy pressure sleeves, ribbed flex elbow, broad utility mitten.
            c.piece(capsule(shoulder, elbow, 2.8), CREAM)
            c.piece(circle(*elbow, 2.9), VIOLET)
            c.piece(capsule((elbow[0] + dx * 2, elbow[1] + dy * 2), wrist, 2.6), CREAM)
            c.piece(capsule((wrist[0] - px * 2, wrist[1] - py * 2),
                            (wrist[0] + px * 2, wrist[1] + py * 2), .9), AMBER)
            hand = (wrist[0] + dx * 3, wrist[1] + dy * 3)
            c.piece(capsule(wrist, hand, 2.3), VIOLET)
            c.piece(circle(hand[0] + px * 2, hand[1] + py * 2, 1), CREAM)
            c.piece(circle(*shoulder, 3), CREAM)
        else:
            # Slim linkage sleeves with a cuff reel, one cable clamp and one glove.
            c.piece(capsule(shoulder, elbow, 1.7), VIOLET)
            c.piece(circle(*elbow, 2), STEEL_RAMP)
            c.piece(capsule(elbow, wrist, 1.8), CREAM)
            c.piece(circle(*wrist, 3), VIOLET)
            c.piece(circle(*wrist, 1.5), AMBER)
            if side == 0:
                opening = 2 if f in (DETAIL, CHEER) else 1.5
                for s in (-1, 1):
                    a = (wrist[0] + px * s * opening, wrist[1] + py * s * opening)
                    b = (a[0] + dx * 5, a[1] + dy * 5)
                    c.piece(capsule(a, b, .7), STEEL_RAMP)
                    c.fill(capsule(b, (b[0] - px * s, b[1] - py * s), .6), CREAM[2])
            else:
                c.piece(capsule(wrist, (wrist[0] + dx * 4, wrist[1] + dy * 4), 1.7), CREAM)
            c.piece(rrect(round(shoulder[0]) - 2, 26, round(shoulder[0]) + 2, 31, 1), AMBER)


def arms_1(c, f):
    arms(c, f, 1)


def arms_2(c, f):
    arms(c, f, 2)


def legs(c, f, variant):
    for side in (0, 1):
        outward, lift, bend = leg_state(f, side)
        hip = (26, 44)
        ax, ground = 24 - outward, 61 - lift
        ankle = (ax, ground - 5)
        knee = (25 - bend * .6, 50 - lift * .4)
        parts = [
            (circle(*hip, 2.3), STEEL_RAMP),
            (capsule(hip, knee, 2.5 if variant == 1 else 1.6), CREAM if variant == 1 else VIOLET),
            (circle(*knee, 2.5), VIOLET if variant == 1 else AMBER),
            (capsule((knee[0], knee[1] + 1), ankle, 2.5 if variant == 1 else 1.8), CREAM),
        ]
        if variant == 1:
            # Wide rounded overshoes; flexible ankle ring separates the two masses.
            parts += [(rect(ax - 3, ground - 6, ax + 2, ground - 5), AMBER),
                      (rrect(ax - 5, ground - 4, ax + 3, ground, 2), CREAM),
                      (rect(ax - 5, ground - 1, ax + 3, ground), VIOLET)]
        else:
            # Tall angular gaiters and outward magnetic heel/toe pads.
            parts += [(poly([(ax - 2, ground - 9), (ax + 2, ground - 8),
                             (ax + 2, ground - 3), (ax - 3, ground - 3)]), VIOLET),
                      (rect(ax - 2, ground - 7, ax + 1, ground - 6), CREAM),
                      (rrect(ax - 4, ground - 3, ax + 2, ground - 1, 1), CREAM),
                      (rect(ax - 5, ground - 1, ax - 2, ground), AMBER),
                      (rect(ax + 1, ground - 1, ax + 3, ground), STEEL_RAMP)]
        for mask, ramp in parts:
            c.piece(mirror(mask) if side else mask, ramp)


def legs_1(c, f):
    legs(c, f, 1)


def legs_2(c, f):
    legs(c, f, 2)


PARTS = [
    ('orbital_head_1', 'HEAD', 'Pressure bell', head_1),
    ('orbital_head_2', 'HEAD', 'Relay receiver', head_2),
    ('orbital_body_1', 'BODY', 'Tether tender', body_1),
    ('orbital_body_2', 'BODY', 'Service harness', body_2),
    ('orbital_arms_1', 'ARMS', 'Pressure mittens', arms_1),
    ('orbital_arms_2', 'ARMS', 'Cable handlers', arms_2),
    ('orbital_legs_1', 'LEGS', 'Lunar overshoes', legs_1),
    ('orbital_legs_2', 'LEGS', 'Magnetic gaiters', legs_2),
]
SLOTS = ('HEAD', 'BODY', 'ARMS', 'LEGS')
BG, PANEL, INK = '#E8E5D8', '#DAD8CF', '#18232D'


def combo(family, variant):
    return {slot: f'{family}_{slot.lower()}_{variant}' for slot in SLOTS}


def render_board(sheets, combinations):
    width, row, label = 1192, 150, 160
    board = Image.new('RGBA', (width, 250 + (8 + len(combinations)) * row), BG)
    d = ImageDraw.Draw(board)
    d.text((16, 12), 'ORBITAL / service beyond the airlock', fill=INK)
    d.text((16, 30), 'Original modular pixel art - cream pressure shells / faded violet / amber tether fittings', fill=INK)
    d.text((16, 49), '1x native (left) and 2x nearest-neighbour - no weapons, no live inventory changes', fill=INK)
    for i, (title, parts) in enumerate(combinations):
        x = 16 + i * 195
        sprite = assemble(sheets, parts, 0)
        board.alpha_composite(sprite, (x, 88))
        board.alpha_composite(sprite.resize((128, 128), Image.Resampling.NEAREST), (x + 64, 75))
        d.text((x, 205), title, fill=INK)
    frames = ['IDLE', 'DETAIL', 'CONTACT L', 'PASS A', 'CONTACT R', 'PASS B', 'SLEEP', 'CELEBRATE']
    for index, (title, part) in enumerate([(name, pid) for pid, _, name, _ in PARTS] + combinations):
        y = 235 + index * row
        d.rectangle((0, y, width, y + 134), fill=PANEL if index % 2 == 0 else BG)
        d.text((12, y + 12), title, fill=INK)
        d.text((12, y + 28), 'part / 2x' if isinstance(part, str) else 'assembled / 2x', fill=INK)
        for f in range(8):
            sprite = sheets[part].crop((f * 64, 0, (f + 1) * 64, 64)) if isinstance(part, str) else assemble(sheets, part, f)
            board.alpha_composite(sprite.resize((128, 128), Image.Resampling.NEAREST), (label + f * 128, y))
            d.text((label + f * 128 + 4, y + 132), frames[f], fill=INK)
    board.save(ASSETS / 'board.png')


def render_preview(sheets, combinations):
    sequence = [(IDLE, 0, 650), (DETAIL, 0, 180), (IDLE, 0, 400)]
    sequence += [(f, -1 if f in (PASS_A, PASS_B) else 0, 150) for f in (WALK_L, PASS_A, WALK_R, PASS_B) * 3]
    sequence += [(SLEEP, 1, 1200), (CHEER, -2, 400), (CHEER, 0, 200), (IDLE, 0, 450)]
    frames = []
    for f, bob, duration in sequence:
        canvas = Image.new('RGB', (len(combinations) * 216, 258), BG)
        d = ImageDraw.Draw(canvas)
        d.text((12, 8), 'ORBITAL / idle - work walk - sleep - all clear', fill=INK)
        for i, (title, parts) in enumerate(combinations):
            sprite = assemble(sheets, parts, f).resize((192, 192), Image.Resampling.NEAREST)
            canvas.paste(sprite, (i * 216 + 12, 33 + bob * 3), sprite)
            d.text((i * 216 + 12, 237), title, fill=INK)
        frames.append(canvas)
    frames[0].save(ASSETS / 'preview.gif', save_all=True, append_images=frames[1:],
                   duration=[s[2] for s in sequence], loop=0, disposal=2)


def main():
    ASSETS.mkdir(parents=True, exist_ok=True)
    sheets, catalog = {}, []
    for pid, slot, name, draw in PARTS:
        sheets[pid] = render_sheet(draw)
        sheets[pid].save(ASSETS / f'{pid}.png')
        catalog.append({'id': pid, 'slot': slot, 'family': 'orbital', 'name': name,
                        'asset': f'mecha/orbital/{pid}.png'})
    (ASSETS / 'catalog.json').write_text(json.dumps(catalog, indent=2) + '\n')
    combinations = [('Pressure crew', combo('orbital', 1)), ('Relay crew', combo('orbital', 2))]
    for family, variant, swaps, title in [
        ('yard', 1, {'HEAD': 'orbital_head_1', 'ARMS': 'orbital_arms_2'}, 'Yard / bell + cable'),
        ('survey', 2, {'BODY': 'orbital_body_1', 'LEGS': 'orbital_legs_2'}, 'Survey / tether + boots'),
        ('courier', 1, {'HEAD': 'orbital_head_2', 'BODY': 'orbital_body_2'}, 'Courier / relay harness'),
        ('orbital', 1, {'HEAD': 'orbital_head_2', 'LEGS': 'orbital_legs_2'}, 'Orbital / cross-fit'),
    ]:
        parts = combo(family, variant)
        parts.update(swaps)
        combinations.append((title, parts))
        for pid in parts.values():
            if pid not in sheets:
                with Image.open(ROOT / 'mecha/src/main/assets/mecha' / pid.split('_')[0] / f'{pid}.png') as source:
                    sheets[pid] = source.convert('RGBA')
    render_board(sheets, combinations)
    render_preview(sheets, combinations)
    print(f'Rendered 8 sheets, catalog, board and animated preview in {ASSETS}')


if __name__ == '__main__':
    main()
