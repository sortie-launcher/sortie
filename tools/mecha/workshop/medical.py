#!/usr/bin/env python3
"""Original diagnostic cart and field medic sets, using the shared eight-frame rig."""
from __future__ import annotations

import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw

MECHA = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(MECHA))
from courier import (  # noqa: E402
    OUT, SHADOW, STEEL, HI, STEEL_RAMP, RUBBER_RAMP,
    rect, rrect, ellipse, circle, capsule, line, poly, mirror,
    arm_phase, leg_state, render_sheet, assemble, hexc,
    DETAIL, SLEEP, CHEER, WALK_L, WALK_R,
)
from paint_roles import export_part  # noqa: E402

FAMILY = "medical"
DEST = MECHA.parents[1] / "mecha/src/main/assets/mecha" / FAMILY
REVIEW = Path.home() / ".cache/sortie-mecha-lab" / FAMILY
PRIMARY = tuple(map(hexc, ("9FAFA9", "DBE6DB", "F8FFF0")))
SECONDARY = tuple(map(hexc, ("316D72", "4D9C9B", "83CFBE")))
GLASS, EYE, HOT, SIGNAL = map(hexc, ("213F50", "8EE2F0", "E4FFFF", "F2B977"))


def face(c, f, x0, x1, y):
    c.fill(rect(x0, y - 2, x1, y + 3), GLASS)
    for x in (x0 + 2, x1 - 3):
        if f in (SLEEP, DETAIL):
            c.fill(rect(x, y + 1, x + 2, y + 1), EYE)
        elif f == CHEER:
            c.fill(line((x, y + 1), (x + 1, y)), HOT)
            c.dot(x + 2, y + 1, HOT)
        else:
            look = -1 if f == WALK_L else 1 if f == WALK_R else 0
            c.fill(rect(x + look, y, x + look + 1, y + 1), EYE)


def pulse(c, x, y, f):
    pts = [(x, y), (x + 2, y), (x + 3, y - 2), (x + 4, y + 2), (x + 5, y), (x + 7, y)]
    if f == SLEEP:
        pts = [(x, y), (x + 7, y)]
    for a, b in zip(pts, pts[1:]):
        c.fill(line(a, b), EYE)


def head_1(c, f):
    """Wide suspended display with an offset sensor puck and deep lower chin."""
    c.piece(rect(29, 20, 35, 26), STEEL_RAMP)
    c.piece(rect(38, 7, 43, 10), STEEL_RAMP)
    c.piece(circle(42, 6, 2), SECONDARY)
    c.piece(rrect(19, 9, 44, 23, 2), PRIMARY)
    c.piece(rect(21, 20, 42, 23), SECONDARY)
    face(c, f, 22, 40, 15)
    c.fill(rect(28, 21, 35, 21), OUT)
    c.dot(41, 21, SIGNAL if f != SLEEP else SHADOW)
    c.dot(21, 10, HI)


def head_2(c, f):
    """Compact field visor under a high folded cap with a side listening horn."""
    c.piece(rect(29, 21, 35, 26), STEEL_RAMP)
    c.piece(poly([(26, 10), (36, 9), (40, 14), (38, 23), (25, 23), (23, 16)]), PRIMARY)
    c.piece(poly([(24, 11), (28, 4), (37, 6), (41, 12), (32, 14)]), SECONDARY)
    c.fill(poly([(28, 5), (32, 11), (38, 7)]), PRIMARY[1])
    c.piece(circle(22, 17, 3), STEEL_RAMP)
    c.fill(circle(22, 17, 1), GLASS)
    face(c, f, 27, 36, 17)
    c.piece(rect(28, 21, 36, 23), SECONDARY)
    c.fill(rect(30, 22, 34, 22), HI)


def body_1(c, f):
    """Clinical equipment cart: three broad trays, control display, specimen drawers."""
    c.piece(rrect(23, 26, 41, 44), SECONDARY)
    c.piece(rect(19, 26, 45, 28), PRIMARY)
    c.piece(rect(20, 34, 44, 36), PRIMARY)
    c.piece(rect(21, 42, 43, 45), PRIMARY)
    c.piece(rrect(25, 29, 38, 33), PRIMARY)
    c.fill(rect(27, 29, 36, 33), GLASS)
    pulse(c, 28, 31, f)
    for x in (25, 34):
        c.piece(rect(x, 37, x + 5, 41), PRIMARY)
        c.fill(rect(x + 1, 38, x + 3, 38), SHADOW)
    c.piece(rect(43, 29, 46, 34), SECONDARY)
    c.dot(44, 31, SIGNAL)


def body_2(c, f):
    """Soft field pack with a diagonal utility belt, hydration pouch and triage panel."""
    c.piece(rrect(21, 29, 27, 40), SECONDARY)
    c.piece(poly([(27, 25), (38, 25), (41, 31), (38, 44), (26, 44), (24, 32)]), PRIMARY)
    c.piece(poly([(25, 26), (28, 25), (39, 42), (36, 44)]), SECONDARY)
    c.piece(rrect(34, 34, 44, 43), SECONDARY)
    c.piece(rect(35, 34, 43, 36), PRIMARY)
    c.fill(rect(38, 36, 39, 39), HI)
    c.piece(rrect(28, 28, 35, 34), PRIMARY)
    c.fill(rect(29, 29, 34, 32), GLASS)
    c.fill(line((30, 31), (32, 29 if f == DETAIL else 30)), EYE)
    c.fill(rect(29, 40, 32, 41), SHADOW)


def arms_1(c, f):
    """Light pantograph arms ending in a sensor wand and precision clamp."""
    for side in (0, 1):
        phase = arm_phase(f, side)
        x = 12 + (2 if phase == "back" else -1 if phase == "forward" else 0)
        y = 20 if f == CHEER else 38
        elbow = (12, 30 if f == CHEER else 34)
        shape = lambda m: mirror(m) if side else m
        c.piece(shape(circle(19, 29, 2)), SECONDARY)
        c.piece(shape(capsule((18, 29), elbow, 1)), STEEL_RAMP)
        c.piece(shape(circle(*elbow, 2)), PRIMARY)
        c.piece(shape(capsule(elbow, (x, y), 1)), STEEL_RAMP)
        if side == 0:
            c.piece(rrect(x - 2, y - 3, x + 2, y + 5), PRIMARY)
            c.piece(rect(x - 3, y - 5, x + 3, y - 3), SECONDARY)
            c.fill(rect(x - 1, y - 5, x + 1, y - 5), EYE if f != SLEEP else GLASS)
            c.fill(line((x + 2, y + 4), (x + 4, y + 7)), SHADOW)
        else:
            c.piece(shape(circle(x, y, 2)), SECONDARY)
            c.piece(shape(poly([(x - 3, y + 1), (x - 1, y + 1), (x - 2, y + 5), (x, y + 6), (x - 3, y + 6)])), PRIMARY)
            c.piece(shape(poly([(x + 2, y + 1), (x + 4, y + 1), (x + 4, y + 6), (x + 1, y + 6), (x + 3, y + 4)])), PRIMARY)


def arms_2(c, f):
    """Padded sleeves, folding brace and a carried field-supply case."""
    for side in (0, 1):
        phase = arm_phase(f, side)
        x = 15 + (1 if phase == "back" else -1 if phase == "forward" else 0)
        y = 21 if f == CHEER else 39
        shape = lambda m: mirror(m) if side else m
        c.piece(shape(poly([(16, 26), (20, 27), (21, 31), (15, 33), (13, 29)])), PRIMARY)
        c.piece(shape(capsule((16, 32), (x, y), 2.5)), SECONDARY)
        c.piece(shape(rect(x - 2, y, x + 2, y + 3)), RUBBER_RAMP)
        if side == 0:
            c.piece(rect(x - 3, y - 6, x - 1, y + 7), PRIMARY)
            c.piece(rect(x + 1, y - 6, x + 3, y + 7), PRIMARY)
            for dy in (-4, 1, 5):
                c.fill(rect(x - 3, y + dy, x + 3, y + dy), SECONDARY[0])
        else:
            c.piece(shape(rrect(x - 3, y + 2, x + 3, y + 5)), STEEL_RAMP)
            c.piece(shape(rrect(x - 6, y + 5, x + 5, y + 12)), SECONDARY)
            c.piece(shape(rect(x - 4, y + 6, x + 3, y + 8)), PRIMARY)
            c.fill(shape(rect(x - 1, y + 8, x, y + 10)), HI)


def legs_1(c, f):
    """Splayed caster forks, service housings and small swivel wheels."""
    for side in (0, 1):
        out, lift, bend = leg_state(f, side)
        x, ground = 23 - out, 61 - lift
        shape = lambda m: mirror(m) if side else m
        c.piece(shape(capsule((26, 44), (x, 51), 2)), STEEL_RAMP)
        c.piece(shape(poly([(x - 3, 48), (x + 3, 48), (x + 2, ground - 6), (x - 4, ground - 6)])), PRIMARY)
        c.piece(shape(rect(x - 4, ground - 7, x + 3, ground - 5)), SECONDARY)
        c.piece(shape(rect(x - 3, ground - 5, x - 1, ground - 1)), STEEL_RAMP)
        c.piece(shape(circle(x + 1, ground - 2, 2.5)), RUBBER_RAMP)
        c.dot(64 - (x + 1) if side else x + 1, ground - 2, HI)


def legs_2(c, f):
    """Field gaiters, articulated knees and high rubber overshoes."""
    for side in (0, 1):
        out, lift, bend = leg_state(f, side)
        x, ground = 25 - out, 61 - lift
        shape = lambda m: mirror(m) if side else m
        c.piece(shape(capsule((26, 44), (x - bend, 50), 2)), SECONDARY)
        c.piece(shape(circle(x - bend, 49, 2)), STEEL_RAMP)
        c.piece(shape(rrect(x - 3, 51, x + 2, ground - 2)), PRIMARY)
        c.fill(shape(rect(x - 3, 53, x + 2, 54)), SECONDARY[1])
        c.piece(shape(poly([(x - 3, ground - 4), (x + 2, ground - 4), (x + 4, ground - 1), (x + 4, ground), (x - 4, ground)])), RUBBER_RAMP)
        c.fill(shape(rect(x - 3, ground - 1, x + 2, ground - 1)), PRIMARY[1])


PARTS = [
    ("medical_head_1", "HEAD", "Diagnostic display", head_1),
    ("medical_head_2", "HEAD", "Field medic cap", head_2),
    ("medical_body_1", "BODY", "Clinical cart", body_1),
    ("medical_body_2", "BODY", "Triage pack", body_2),
    ("medical_arms_1", "ARMS", "Probe and clamp", arms_1),
    ("medical_arms_2", "ARMS", "Brace and supply case", arms_2),
    ("medical_legs_1", "LEGS", "Swivel casters", legs_1),
    ("medical_legs_2", "LEGS", "Field gaiters", legs_2),
]


def main():
    DEST.mkdir(parents=True, exist_ok=True)
    REVIEW.mkdir(parents=True, exist_ok=True)
    sheets, catalog = {}, []
    for pid, slot, name, draw in PARTS:
        sheet = sheets[pid] = render_sheet(draw)
        export_part(sheet, DEST / f"{pid}.png", PRIMARY, SECONDARY, slot=slot)
        catalog.append(dict(id=pid, slot=slot, family=FAMILY, name=name, asset=f"mecha/{FAMILY}/{pid}.png"))
    (DEST / "catalog.json").write_text(json.dumps(catalog, indent=2) + "\n")
    board = Image.new("RGBA", (8 * 192, 2 * 222), (227, 222, 207, 255))
    draw = ImageDraw.Draw(board)
    for variant, label in ((1, "Diagnostic cart"), (2, "Field medic")):
        combo = {slot: f"{FAMILY}_{slot.lower()}_{variant}" for slot in ("HEAD", "BODY", "ARMS", "LEGS")}
        frames = []
        for f in range(8):
            bot = assemble(sheets, combo, f).resize((192, 192), Image.Resampling.NEAREST)
            board.alpha_composite(bot, (f * 192, (variant - 1) * 222 + 24))
            draw.text((f * 192 + 6, (variant - 1) * 222 + 6), f"{label} / {f}", fill=OUT)
            frames.append(bot)
        frames[0].save(REVIEW / f"set-{variant}.png")
        frames[0].save(REVIEW / f"set-{variant}.gif", save_all=True, append_images=frames[1:], duration=220, loop=0, disposal=2)
    board.convert("RGB").save(REVIEW / "board.png")
    print(f"{FAMILY}: 8 base sheets + 8 masks at 512x64; review {REVIEW / 'board.png'}")


if __name__ == "__main__":
    main()
