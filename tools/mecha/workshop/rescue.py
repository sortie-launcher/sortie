#!/usr/bin/env python3
"""Original brigade and cliff-search sets; regenerate production sheets and cache review."""
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

FAMILY = "rescue"
DEST = MECHA.parents[1] / "mecha/src/main/assets/mecha" / FAMILY
REVIEW = Path.home() / ".cache/sortie-mecha-lab" / FAMILY
PRIMARY = tuple(map(hexc, ("842F36", "CC4F43", "F38663")))
SECONDARY = tuple(map(hexc, ("9D824E", "DEC58B", "FFF0BC")))
GLASS, EYE, HOT = map(hexc, ("243E4B", "91D7E0", "E4FFFF"))


def eyes(c, f, x0, x1, y):
    c.fill(rect(x0, y - 1, x1, y + 2), GLASS)
    if f in (SLEEP, DETAIL):
        c.fill(line((x0 + 1, y + 1), (x1 - 1, y + 1)), EYE)
    elif f == CHEER:
        for x in (x0 + 2, x1 - 2):
            c.fill(line((x - 1, y + 1), (x, y)), HOT)
            c.dot(x + 1, y + 1, HOT)
    else:
        look = -1 if f == WALK_L else 1 if f == WALK_R else 0
        for x in (x0 + 2, x1 - 2):
            c.fill(rect(x + look, y, x + look + 1, y + 1), EYE)


def head_1(c, f):
    """Wide brigade helmet, rear neck guard, crown ridge and smoked face shield."""
    c.piece(rect(29, 21, 35, 26), STEEL_RAMP)
    c.piece(poly([(21, 13), (43, 13), (46, 23), (39, 25), (23, 24)]), PRIMARY)
    c.piece(ellipse(32, 13, 11, 8), PRIMARY)
    c.piece(rect(19, 13, 46, 15), SECONDARY)
    c.piece(poly([(30, 4), (33, 4), (35, 13), (29, 13)]), SECONDARY)
    c.piece(rrect(22, 16, 41, 22), STEEL_RAMP)
    eyes(c, f, 24, 39, 18)
    c.piece(rect(29, 8, 35, 11), PRIMARY)
    c.dot(32, 9, HI)


def head_2(c, f):
    """Narrow climbing shell, offset lamp and exposed cheek respirator."""
    c.piece(rect(29, 22, 35, 26), STEEL_RAMP)
    c.piece(poly([(25, 7), (36, 6), (40, 11), (39, 20), (35, 24), (25, 22), (22, 13)]), SECONDARY)
    c.piece(poly([(24, 8), (28, 5), (35, 5), (39, 9), (38, 12), (23, 12)]), PRIMARY)
    c.piece(circle(39, 10, 3), STEEL_RAMP)
    c.fill(circle(39, 10, 1), SHADOW if f == SLEEP else HOT)
    eyes(c, f, 25, 35, 15)
    c.piece(rrect(27, 20, 34, 23), PRIMARY)
    c.fill(rect(29, 21, 32, 21), SHADOW)
    for x in (27, 31, 35):
        c.dot(x, 8, OUT)


def body_1(c, f):
    """Broad pump housing with side water tanks and hose reel."""
    for x in (19, 45):
        c.piece(rrect(x - 3, 27, x + 3, 42, 2), SECONDARY)
        c.fill(rect(x - 2, 31, x + 2, 32), PRIMARY[0])
        c.fill(rect(x - 2, 38, x + 2, 39), PRIMARY[0])
    c.piece(rrect(22, 25, 42, 44, 2), PRIMARY)
    c.piece(rect(24, 26, 40, 29), SECONDARY)
    c.piece(circle(32, 36, 6), STEEL_RAMP)
    c.piece(circle(32, 36, 4), RUBBER_RAMP)
    c.piece(circle(32, 36, 2), SECONDARY)
    c.fill(line((32, 36), (35, 34 if f == DETAIL else 36)), HI)
    c.piece(rect(24, 42, 40, 45), SECONDARY)
    for x in (25, 39):
        c.dot(x, 31, HI)


def body_2(c, f):
    """Tapered rescue harness with diagonal straps and a hanging carabiner."""
    c.piece(rrect(37, 25, 44, 40, 1), SECONDARY)
    c.piece(poly([(24, 25), (39, 25), (42, 31), (36, 44), (27, 44), (22, 32)]), PRIMARY)
    c.piece(poly([(24, 25), (27, 25), (38, 40), (36, 42)]), SECONDARY)
    c.piece(poly([(38, 25), (40, 27), (27, 42), (25, 40)]), SECONDARY)
    c.piece(rrect(28, 31, 35, 37), PRIMARY)
    c.fill(rect(30, 33, 33, 34), HI)
    c.piece(rect(26, 41, 38, 44), RUBBER_RAMP)
    c.piece(ellipse(32, 42, 2, 3), STEEL_RAMP)
    c.fill(rect(32, 41, 32, 43), OUT)
    c.dot(40, 28, EYE if f != SLEEP else SHADOW)


def arms_1(c, f):
    """Heavy gauntlets, left water nozzle and right rescue axe."""
    for side in (0, 1):
        phase = arm_phase(f, side)
        x = 13 + (2 if phase == "back" else -1 if phase == "forward" else 0)
        y = 21 if f == CHEER else 41 + (1 if phase == "back" else -1 if phase == "forward" else 0)
        shape = lambda m: mirror(m) if side else m
        c.piece(shape(circle(19, 29, 4)), SECONDARY)
        c.piece(shape(capsule((18, 31), (x, y - 4), 3)), PRIMARY)
        c.piece(shape(rrect(x - 4, y - 3, x + 3, y + 2)), SECONDARY)
        c.piece(shape(rect(x - 3, y + 1, x + 2, y + 4)), RUBBER_RAMP)
        if side == 0:
            c.piece(rect(x - 7, y - 1, x - 2, y + 2), STEEL_RAMP)
            c.fill(rect(x - 7, y, x - 6, y + 1), OUT)
        else:
            c.piece(shape(rect(x - 1, y - 8, x, y + 6)), STEEL_RAMP)
            c.piece(shape(poly([(x - 5, y - 8), (x + 3, y - 8), (x + 4, y - 4), (x - 4, y - 5)])), PRIMARY)


def arms_2(c, f):
    """Slim articulated climber arms, rope coil and open grappling hook."""
    for side in (0, 1):
        phase = arm_phase(f, side)
        x = 14 + (2 if phase == "back" else -2 if phase == "forward" else 0)
        y = 19 if f == CHEER else 40
        shape = lambda m: mirror(m) if side else m
        c.piece(shape(circle(19, 29, 2)), STEEL_RAMP)
        c.piece(shape(capsule((18, 30), (x + 1, 34 if f != CHEER else 25), 1.7)), SECONDARY)
        c.piece(shape(capsule((x + 1, 34 if f != CHEER else 25), (x, y), 2)), PRIMARY)
        c.piece(shape(circle(x, y + 1, 2)), RUBBER_RAMP)
        if side == 0:
            coil = ellipse(x - 2, y + 4, 4, 5) - ellipse(x - 2, y + 4, 2, 3)
            c.piece(coil, SECONDARY)
            c.fill(line((x - 4, y + 8), (x - 5, y + 11)), SECONDARY[0])
        else:
            hook = capsule((x, y - 2), (x, y - 8), 1) | line((x - 4, y - 6), (x, y - 9)) | line((x, y - 9), (x + 4, y - 6))
            c.piece(shape(hook), STEEL_RAMP)


def legs_1(c, f):
    """Braced fire boots with broad reinforced toes and reflective shin bands."""
    for side in (0, 1):
        out, lift, bend = leg_state(f, side)
        x, ground = 25 - out, 61 - lift
        shape = lambda m: mirror(m) if side else m
        c.piece(shape(capsule((26, 44), (x - bend, 50), 2.5)), STEEL_RAMP)
        c.piece(shape(rrect(x - 4, 49, x + 3, ground - 3)), PRIMARY)
        c.piece(shape(rect(x - 4, 51, x + 3, 53)), SECONDARY)
        c.piece(shape(poly([(x - 4, ground - 5), (x + 3, ground - 5), (x + 5, ground - 1), (x + 5, ground), (x - 6, ground), (x - 6, ground - 3)])), RUBBER_RAMP)
        c.fill(shape(rect(x - 4, ground - 2, x + 3, ground - 2)), STEEL)


def legs_2(c, f):
    """Long climbing shanks, kneecaps and narrow serrated crampon soles."""
    for side in (0, 1):
        out, lift, bend = leg_state(f, side)
        x, ground = 25 - out, 61 - lift
        shape = lambda m: mirror(m) if side else m
        knee = (x - bend, 50)
        c.piece(shape(capsule((26, 44), knee, 1.5)), RUBBER_RAMP)
        c.piece(shape(circle(*knee, 2.4)), PRIMARY)
        c.piece(shape(capsule(knee, (x, ground - 3), 1.8)), SECONDARY)
        c.piece(shape(poly([(x - 2, ground - 5), (x + 2, ground - 4), (x + 3, ground - 1), (x - 4, ground - 1)])), PRIMARY)
        c.piece(shape(rect(x - 4, ground, x + 3, ground)), STEEL_RAMP)
        for dx in (-3, 0, 3):
            c.dot(64 - (x + dx) if side else x + dx, ground + 1, OUT)


PARTS = [
    ("rescue_head_1", "HEAD", "Brigade shield", head_1),
    ("rescue_head_2", "HEAD", "Cliff lamp", head_2),
    ("rescue_body_1", "BODY", "Pump station", body_1),
    ("rescue_body_2", "BODY", "Search harness", body_2),
    ("rescue_arms_1", "ARMS", "Nozzle and axe", arms_1),
    ("rescue_arms_2", "ARMS", "Rope and grapple", arms_2),
    ("rescue_legs_1", "LEGS", "Brigade boots", legs_1),
    ("rescue_legs_2", "LEGS", "Cliff crampons", legs_2),
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
    for variant, label in ((1, "Fire brigade"), (2, "Cliff search")):
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
