#!/usr/bin/env python3
"""Original foundry service-bot parts; run with Python 3 + Pillow.

Writes only workshop/assets/foundry: eight RGBA sheets, catalog, review board
and animated preview. Reuses the existing courier pixel primitives and rig,
not its artwork. Catalog asset names follow the shipped five-field contract;
these are review assets, not installed game content.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from courier import (  # noqa: E402
    Canvas, OUT, SHADOW, STEEL, HI, STEEL_RAMP, IDLE, DETAIL,
    WALK_L, PASS_A, WALK_R, PASS_B, SLEEP, CHEER, hexc,
    rect, rrect, circle, capsule, line, poly, mirror, leg_state,
    render_sheet, assemble,
)

ROOT = Path(__file__).resolve().parents[3]
DEST = Path(__file__).resolve().parent / "assets/foundry"
RUST = tuple(map(hexc, ("643B35", "A45A43", "DB9772")))
COAL = tuple(map(hexc, ("273039", "45525A", "81908D")))
BRASS = tuple(map(hexc, ("766142", "BA9560", "E1C899")))
LEATHER = tuple(map(hexc, ("3C3030", "66504A", "9C7863")))
EMBER, FIRE, HOT = map(hexc, ("B65331", "FFAA54", "FFE6A0"))
GLASS = hexc("1C353C")
LENS = hexc("9DCBB8")
BG = hexc("EEE4D2")
INK = hexc("26333A")
SLOTS = ("HEAD", "BODY", "ARMS", "LEGS")


def bolt(c, x, y):
    c.dot(x, y, HI)
    c.dot(x + 1, y + 1, OUT)


def chips(c, points):
    # Authored chips stay fixed to the plating across every animation frame.
    for x, y in points:
        c.fill({(x, y), (x + 1, y)}, SHADOW)
        c.dot(x, y + 1, STEEL)


def head_1(c, f):
    """Welder's hood: broad hinged shield, narrow green filter, leather skirt."""
    c.piece(rect(29, 21, 35, 26), STEEL_RAMP)
    c.piece(poly([(22, 11), (24, 6), (39, 6), (43, 11), (41, 22), (36, 25), (25, 23)]), LEATHER)
    hood = poly([(20, 9), (23, 5), (39, 5), (43, 9), (42, 21), (38, 24), (24, 22), (21, 18)])
    c.piece(hood, RUST)
    c.piece(rect(21, 11, 42, 16), COAL)
    c.fill(rect(24, 12, 38, 15), GLASS)
    if f == SLEEP:
        c.fill(line((25, 14), (36, 14)), STEEL)
    else:
        c.fill(rect(25, 13, 36, 14), LENS)
        c.fill(rect(27 if f != WALK_R else 29, 13, 30 if f != WALK_R else 32, 14), HI)
        if f == DETAIL:
            c.fill(rect(24, 12, 38, 13), SHADOW)
        if f == CHEER:
            c.fill(line((25, 12), (37, 12)), HOT)
    for x in (20, 43):
        c.piece(circle(x, 12, 1.4), BRASS)
        c.dot(x, 12, OUT)
    c.fill(line((25, 18), (35, 18)), RUST[0])
    c.fill(line((27, 20), (33, 20)), COAL[0])
    chips(c, [(24, 8), (37, 19), (24, 20)])
    bolt(c, 39, 8)


def head_2(c, f):
    """Kiln keeper: tall vented cowl, lopsided stack and little inspection eyes."""
    c.piece(rect(29, 21, 35, 26), STEEL_RAMP)
    c.piece(rect(38, 4, 41, 12), COAL)
    c.piece(rect(36, 3, 43, 5), BRASS)
    c.fill(rect(38, 3, 41, 3), OUT)
    c.piece(poly([(25, 5), (35, 5), (40, 10), (40, 22), (36, 24), (24, 23), (22, 19), (22, 10)]), BRASS)
    for y in (8, 10):
        c.fill(line((26, y), (34, y)), OUT)
        c.dot(35, y + 1, BRASS[0])
    c.piece(rrect(23, 13, 38, 20, 1), COAL)
    for x in (27, 34):
        c.piece(circle(x, 16, 2.0), RUST)
        if f == SLEEP:
            c.fill(line((x - 1, 17), (x + 1, 17)), SHADOW)
        elif f == DETAIL and x == 34:
            c.fill(line((x - 1, 16), (x + 1, 16)), FIRE)
        else:
            c.fill(rect(x - 1, 15, x, 17), HOT if f == CHEER else FIRE)
    c.fill(line((28, 22), (33, 22)), OUT)
    bolt(c, 24, 11)
    bolt(c, 37, 11)
    chips(c, [(25, 6), (36, 21)])


def sockets(c):
    for x in (20, 44):
        c.piece(circle(x, 29, 2.5), STEEL_RAMP)
    c.piece(rrect(25, 39, 39, 44, 1), COAL)
    c.fill(line((27, 43), (37, 43)), STEEL)


def body_1(c, f):
    """Hearth chest: a riveted square fire door, latch and refractory apron."""
    sockets(c)
    c.piece(poly([(23, 24), (41, 24), (44, 28), (43, 39), (40, 42), (23, 42), (20, 38), (20, 28)]), RUST)
    c.piece(rect(24, 26, 40, 39), BRASS)
    c.piece(rrect(26, 28, 38, 37, 1), COAL)
    c.fill(rect(27, 29, 37, 36), OUT)
    for x, top in ((28, 32), (30, 30), (32, 32), (34, 31), (36, 33)):
        y = top + (1 if f == DETAIL else 0)
        c.fill(rect(x, y, x, 35), EMBER if f == SLEEP else FIRE)
        if f not in (SLEEP, DETAIL):
            c.dot(x, 35, HOT)
    for x in (29, 32, 35):
        c.fill(line((x, 29), (x, 36)), COAL[1])
    for y in (29, 35):
        c.piece(rect(23, y, 25, y + 1), STEEL_RAMP)
    c.piece(rect(39, 31, 41, 34), STEEL_RAMP)
    c.dot(40, 32, HOT if f == CHEER else OUT)
    for x, y in ((22, 27), (41, 27), (23, 39), (40, 39)):
        bolt(c, x, y)
    c.fill(line((27, 41), (35, 41)), BRASS[1])
    chips(c, [(22, 33), (37, 25), (25, 40)])


def body_2(c, f):
    """Draft bellows: tapered concertina body, bronze ribs, pressure dial."""
    sockets(c)
    c.piece(capsule((42, 28), (44, 38), 1.2), BRASS)
    c.piece(poly([(24, 25), (39, 25), (42, 29), (37, 40), (27, 40), (21, 29)]), LEATHER)
    for y, lo, hi in ((28, 22, 41), (31, 23, 40), (34, 24, 39), (37, 26, 38)):
        c.piece(poly([(lo, y), (hi, y), (hi - 1, y + 1), (lo + 1, y + 1)]), BRASS)
        c.fill(line((lo + 2, y + 2), (hi - 2, y + 2)), OUT)
    c.piece(poly([(22, 24), (40, 24), (43, 27), (21, 27)]), RUST)
    c.piece(rect(27, 40, 38, 42), RUST)
    c.piece(circle(31, 31, 4), COAL)
    c.fill(circle(31, 31, 2.8), HI)
    end = (29, 32) if f == SLEEP else (33, 29) if f == CHEER else (32, 29) if f == DETAIL else (31, 29)
    c.fill(line((31, 32), end), RUST[0])
    c.dot(31, 32, OUT)
    c.piece(rect(38, 34, 42, 37), COAL)
    c.dot(40, 35, EMBER if f == SLEEP else FIRE)
    bolt(c, 24, 25)
    bolt(c, 38, 25)
    chips(c, [(29, 41), (35, 24)])


def arm_points(f, side):
    # Same shoulder anchors and counter-swing convention as the shipped rig.
    e, w = (15, 35), (13, 40)
    if f in (WALK_L, WALK_R):
        forward = (f == WALK_R) == (side == 0)
        e, w = ((16, 34), (16, 38)) if forward else ((14, 35), (11, 40))
    elif f in (PASS_A, PASS_B):
        e, w = (15, 35), (14, 41)
    elif f == SLEEP:
        e, w = (16, 36), (18, 40)
    elif f == CHEER:
        e, w = (13, 24), (11, 18)
    pts = ((19, 29), e, w)
    return pts if side == 0 else tuple((64 - x, y) for x, y in pts)


def tool_arm(c, f, side, variant):
    s, e, w = arm_points(f, side)
    x, y = w
    c.piece(capsule(s, e, 1.5), STEEL_RAMP)
    c.piece(capsule(e, w, 2.1), LEATHER if variant == 2 else RUST)
    c.piece(circle(*e, 2.0), BRASS)
    c.dot(e[0], e[1], OUT)
    c.piece(circle(*s, 3.0), RUST if variant == 1 else COAL)
    bolt(c, s[0] - 1, s[1] - 1)
    c.piece(rect(x - 2, y - 1, x + 2, y + 1), BRASS)
    direction = -1 if f == CHEER else 1
    t = y + direction * 5
    c.piece(capsule((x, y), (x, t), 0.7), STEEL_RAMP)
    if variant == 1 and side == 0:
        # Cross-peen hammer, asymmetric head and bright dressed striking face.
        c.piece(poly([(x - 4, t - 2), (x + 2, t - 2), (x + 4, t), (x + 2, t + 2), (x - 4, t + 2)]), COAL)
        c.fill(rect(x - 4, t - 1, x - 3, t + 1), HI)
    elif variant == 1:
        # Open forging tongs: genuinely empty gap, not a mitten.
        opening = 3 if f == CHEER else 2
        for sign in (-1, 1):
            c.piece(capsule((x, y + direction * 2), (x + sign * opening, t), 0.6), STEEL_RAMP)
            c.fill(line((x + sign * opening, t), (x + sign, t + direction * 2)), HI)
    elif side == 0:
        # Bent cutting torch, distinct copper nozzle; pilot extinguished asleep.
        c.piece(capsule((x, t - direction), (x - 3, t), 1), BRASS)
        c.piece(rect(x - 4, t, x - 3, t + 1), COAL)
        if f != SLEEP:
            c.fill({(x - 5, t), (x - 5, t + 1)}, FIRE)
            c.dot(x - 6, t, HOT)
            if f == CHEER:
                c.dot(x - 7, t - 1, HOT)
        c.fill(line((e[0] + 1, e[1]), (x + 1, y)), RUST[2])
    else:
        # Wide replaceable wire-brush head with separated steel bristles.
        c.piece(rect(x - 3, t - 1, x + 3, t + 1), RUST)
        for dx in (-3, -1, 1, 3):
            c.fill(line((x + dx, t + direction), (x + dx, t + direction * 3)), HI)


def arms_1(c, f):
    for side in (0, 1):
        tool_arm(c, f, side, 1)


def arms_2(c, f):
    for side in (0, 1):
        tool_arm(c, f, side, 2)


def legs(c, f, variant):
    for side in (0, 1):
        outward, lift, bend = leg_state(f, side)
        ax, ground = 24 - outward, 61 - lift
        hip = (26, 44)
        knee = (round(24 - bend), 49 - lift // 2)
        ankle = (ax, ground - 5)
        def piece(mask, ramp):
            c.piece(mirror(mask) if side else mask, ramp)
        def fill(mask, color):
            c.fill(mirror(mask) if side else mask, color)
        piece(circle(*hip, 2), STEEL_RAMP)
        piece(capsule(hip, knee, 1.4), COAL)
        piece(capsule(knee, ankle, 1.3), STEEL_RAMP)
        if variant == 1:
            piece(poly([(knee[0] - 3, knee[1] - 2), (knee[0] + 3, knee[1] - 2), (ax + 3, ground - 4), (ax - 4, ground - 4)]), RUST)
            fill(rect(ax - 3, ground - 7, ax + 2, ground - 6), BRASS[1])
            piece(poly([(ax - 4, ground - 5), (ax + 3, ground - 5), (ax + 4, ground - 1), (ax + 4, ground), (ax - 7, ground), (ax - 7, ground - 2)]), COAL)
            fill(line((ax - 6, ground - 2), (ax - 2, ground - 2)), HI)
            fill(line((ax - 5, ground), (ax + 3, ground)), STEEL)
            fill({(knee[0] - 1, knee[1]), (knee[0], knee[1])}, HI)
        else:
            # Exposed ram and accordion knee gaiter, then a split refractory shoe.
            piece(circle(*knee, 2.2), BRASS)
            for dy in (1, 3, 5):
                piece(rect(ax - 2, knee[1] + dy, ax + 2, knee[1] + dy), LEATHER)
            piece(rect(ax - 3, ground - 4, ax + 2, ground - 2), RUST)
            for dx in (-4, 1):
                piece(rrect(ax + dx, ground - 2, ax + dx + 2, ground, 1), BRASS)
            fill(line((ax - 1, knee[1] + 2), (ax - 1, ground - 4)), HI)


def legs_1(c, f):
    legs(c, f, 1)


def legs_2(c, f):
    legs(c, f, 2)


PARTS = [
    ("foundry_head_1", "HEAD", "Welder's hood", head_1),
    ("foundry_head_2", "HEAD", "Kiln keeper", head_2),
    ("foundry_body_1", "BODY", "Hearth chest", body_1),
    ("foundry_body_2", "BODY", "Draft bellows", body_2),
    ("foundry_arms_1", "ARMS", "Hammer and tongs", arms_1),
    ("foundry_arms_2", "ARMS", "Torch and brush", arms_2),
    ("foundry_legs_1", "LEGS", "Slag sabatons", legs_1),
    ("foundry_legs_2", "LEGS", "Split-toe gaiters", legs_2),
]


def full(family, variant):
    return {slot: f"{family}_{slot.lower()}_{variant}" for slot in SLOTS}


def combos():
    return [
        ("01 / HEARTH HAND", full("foundry", 1)),
        ("02 / DRAFT KEEPER", full("foundry", 2)),
        ("03 / YARD REPAIR", {**full("yard", 1), "HEAD": "foundry_head_1", "ARMS": "foundry_arms_2"}),
        ("04 / COURIER STOKER", {**full("courier", 2), "BODY": "foundry_body_1", "LEGS": "foundry_legs_2"}),
        ("05 / SURVEY HEAT CREW", {**full("foundry", 2), "HEAD": "survey_head_1", "ARMS": "survey_arms_1"}),
    ]


def review_board(sheets, sets):
    board = Image.new("RGBA", (1200, 1680), BG)
    d = ImageDraw.Draw(board)
    d.text((24, 18), "FOUNDRY / THE NIGHT SHIFT", fill=INK, font_size=26)
    d.text((24, 54), "Original modular pixel art. Furnace doors, leather bellows, repaired steel. Workshop review only.", fill=INK)
    for i, (label, combo) in enumerate(sets):
        x = 16 + i * 236
        d.text((x, 92), label, fill=INK)
        board.alpha_composite(assemble(sheets, combo, IDLE).resize((192, 192), Image.Resampling.NEAREST), (x + 12, 114))
        board.alpha_composite(assemble(sheets, combo, IDLE), (x + 40, 318))
        board.alpha_composite(assemble(sheets, combo, SLEEP), (x + 116, 318))
    d.text((24, 401), "1x idle / sleep above. All eight frames below: idle, detail, contact, passing, contact, passing, sleep, celebrate.", fill=INK)
    for row, (pid, slot, name, _) in enumerate(PARTS):
        y = 435 + row * 148
        d.text((20, y + 6), slot, fill=INK)
        d.text((20, y + 24), name.replace(" and ", "\n+ "), fill=INK)
        tile = Image.new("RGBA", (1024, 128), hexc("DED5C5"))
        tile.alpha_composite(sheets[pid].resize((1024, 128), Image.Resampling.NEAREST))
        board.alpha_composite(tile, (166, y))
        for frame in range(1, 8):
            d.line((166 + frame * 128, y, 166 + frame * 128, y + 127), fill=BG)
    d.text((24, 1640), "Fixed rig: neck 32,25 / shoulders 19,29 + 45,29 / hips 26,44 + 38,44 / ground 61. No antialiasing.", fill=INK)
    return board


def preview(sheets, sets):
    sequence = [(IDLE, 600), (DETAIL, 180), (IDLE, 300)]
    sequence += [(f, 150) for f in (WALK_L, PASS_A, WALK_R, PASS_B) * 3]
    sequence += [(SLEEP, 1100), (CHEER, 350), (IDLE, 220), (CHEER, 350)]
    frames = []
    for frame, _ in sequence:
        canvas = Image.new("RGBA", (len(sets) * 76, 80), BG)
        for i, (_, combo) in enumerate(sets):
            bob = -1 if frame in (PASS_A, PASS_B, CHEER) else 0
            canvas.alpha_composite(assemble(sheets, combo, frame), (i * 76 + 6, 7 + bob))
        frames.append(canvas.resize((len(sets) * 228, 240), Image.Resampling.NEAREST).convert("RGB"))
    # One shared exact palette prevents the colour pumping of per-frame quantisation.
    colors = sorted({color for frame in frames for _, color in frame.getcolors(frame.width * frame.height)})
    if len(colors) > 256:
        raise ValueError("Review palette exceeds GIF's exact colour capacity")
    palette = Image.new("P", (1, 1))
    palette.putpalette([channel for color in colors for channel in color] + [0] * (768 - len(colors) * 3))
    paletted = [frame.quantize(palette=palette, dither=Image.Dither.NONE) for frame in frames]
    paletted[0].save(DEST / "preview.gif", save_all=True, append_images=paletted[1:], duration=[ms for _, ms in sequence], loop=0, disposal=2)


def main():
    DEST.mkdir(parents=True, exist_ok=True)
    sheets = {}
    catalog = []
    for pid, slot, name, draw in PARTS:
        sheets[pid] = render_sheet(draw)
        sheets[pid].save(DEST / f"{pid}.png", optimize=True)
        catalog.append({"id": pid, "slot": slot, "family": "foundry", "name": name, "asset": f"mecha/foundry/{pid}.png"})
    (DEST / "catalog.json").write_text(json.dumps(catalog, indent=2) + "\n", encoding="utf-8")
    sets = combos()
    for _, combo in sets:
        for pid in combo.values():
            if pid not in sheets:
                family = pid.split("_", 1)[0]
                with Image.open(ROOT / "mecha/src/main/assets/mecha" / family / f"{pid}.png") as image:
                    sheets[pid] = image.convert("RGBA")
    review_board(sheets, sets).save(DEST / "board.png", optimize=True)
    preview(sheets, sets)
    print(f"Rendered eight sheets, catalog.json, board.png and preview.gif in {DEST}")


if __name__ == "__main__":
    main()
