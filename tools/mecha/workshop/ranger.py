#!/usr/bin/env python3
"""Original forest lookout and expedition warden sets; no borrowed part silhouettes."""
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

FAMILY = "ranger"
DEST = MECHA.parents[1] / "mecha/src/main/assets/mecha" / FAMILY
REVIEW = Path.home() / ".cache/sortie-mecha-lab" / FAMILY
PRIMARY = tuple(map(hexc, ("354D3D", "638364", "9DB88A")))
SECONDARY = tuple(map(hexc, ("846544", "C39D66", "E7C792")))
GLASS, EYE, HOT = map(hexc, ("213B48", "9DDAE5", "EDFFF8"))


def lens(c, f, x, y, radius):
    c.piece(circle(x, y, radius + 1), SECONDARY)
    c.fill(circle(x, y, radius), GLASS)
    if f in (SLEEP, DETAIL):
        c.fill(line((x - 1, y + 1), (x + 1, y + 1)), EYE)
    elif f == CHEER:
        c.fill(line((x - 1, y + 1), (x, y)), HOT)
        c.dot(x + 1, y + 1, HOT)
    else:
        look = -1 if f == WALK_L else 1 if f == WALK_R else 0
        c.fill(rect(x + look, y - 1, x + look + 1, y + 1), EYE)
        c.dot(x + look, y - 1, HOT)


def head_1(c, f):
    """Broad peaked lookout hat above a pair of round binocular lenses."""
    c.piece(rect(29, 21, 35, 26), STEEL_RAMP)
    c.piece(rrect(23, 13, 41, 23, 2), PRIMARY)
    c.piece(poly([(21, 12), (27, 5), (32, 3), (39, 7), (42, 13)]), PRIMARY)
    c.piece(rect(22, 11, 42, 13), SECONDARY)
    c.piece(poly([(18, 14), (24, 12), (40, 12), (47, 15), (43, 17), (21, 16)]), PRIMARY)
    lens(c, f, 27, 19, 2)
    lens(c, f, 36, 19, 2)
    c.fill(rect(30, 18, 33, 19), SHADOW)
    c.fill(line((31, 7), (33, 9)), SECONDARY[2])


def head_2(c, f):
    """Tall asymmetric weather hood with one large optical port and a wind vane."""
    c.piece(rect(29, 21, 35, 26), STEEL_RAMP)
    c.piece(line((39, 13), (42, 5)), STEEL_RAMP)
    c.piece(poly([(42, 4), (47, 6), (42, 7)]), SECONDARY)
    c.piece(poly([(25, 6), (35, 5), (40, 12), (38, 24), (25, 24), (22, 18), (23, 10)]), SECONDARY)
    c.piece(poly([(26, 10), (34, 9), (38, 13), (36, 21), (26, 21), (24, 17)]), PRIMARY)
    lens(c, f, 30, 15, 3)
    c.piece(rect(35, 17, 38, 21), STEEL_RAMP)
    c.fill(rect(36, 18, 37, 20), SHADOW)
    c.fill(line((26, 7), (33, 6)), SECONDARY[2])


def body_1(c, f):
    """Flared lookout coat, twin patch pockets, central fasteners and leaf badge."""
    c.piece(poly([(25, 25), (39, 25), (41, 33), (44, 44), (34, 45), (32, 41), (30, 45), (20, 44), (23, 33)]), PRIMARY)
    c.piece(poly([(25, 25), (31, 27), (29, 31), (23, 28)]), SECONDARY)
    c.piece(poly([(39, 25), (33, 27), (35, 31), (41, 28)]), SECONDARY)
    c.fill(rect(31, 29, 32, 42), PRIMARY[0])
    for y in (31, 35, 39):
        c.dot(32, y, HI)
    for x in (24, 35):
        c.piece(rect(x, 34, x + 5, 40), SECONDARY)
        c.fill(rect(x + 1, 35, x + 4, 35), PRIMARY[0])
    c.fill(poly([(36, 30), (38, 28), (39, 31), (37, 32)]), SECONDARY[2])


def body_2(c, f):
    """Compact expedition chest with a bedroll, side canister and crossed webbing."""
    c.piece(rrect(20, 25, 44, 29, 2), SECONDARY)
    for x in (24, 39):
        c.fill(rect(x, 25, x + 1, 29), PRIMARY[0])
    c.piece(rrect(21, 30, 26, 42, 2), SECONDARY)
    c.piece(poly([(27, 27), (39, 27), (42, 33), (38, 44), (27, 44), (24, 35)]), PRIMARY)
    c.piece(poly([(27, 28), (29, 28), (39, 40), (37, 42)]), SECONDARY)
    c.piece(poly([(38, 28), (40, 30), (29, 43), (27, 41)]), SECONDARY)
    c.piece(rrect(29, 32, 36, 38), PRIMARY)
    c.piece(circle(32, 35, 2), STEEL_RAMP)
    c.fill(line((32, 35), (33, 33 if f == DETAIL else 34)), EYE)
    c.piece(rect(28, 42, 38, 45), SECONDARY)


def arms_1(c, f):
    """Bell-cuffed sleeves, a survey lantern and a short trail-sign paddle."""
    for side in (0, 1):
        phase = arm_phase(f, side)
        x = 14 + (1 if phase == "back" else -1 if phase == "forward" else 0)
        y = 20 if f == CHEER else 40
        shape = lambda m: mirror(m) if side else m
        c.piece(shape(circle(19, 29, 3)), SECONDARY)
        c.piece(shape(capsule((18, 31), (x, y - 3), 2.4)), PRIMARY)
        c.piece(shape(poly([(x - 3, y - 5), (x + 2, y - 5), (x + 4, y), (x - 4, y)])), PRIMARY)
        c.piece(shape(rect(x - 4, y - 1, x + 4, y + 1)), SECONDARY)
        c.piece(shape(circle(x, y + 3, 2)), RUBBER_RAMP)
        if side == 0:
            c.piece(ellipse(x, y + 5, 2, 3) - ellipse(x, y + 5, 1, 2), STEEL_RAMP)
            c.piece(rrect(x - 4, y + 7, x + 4, y + 13), SECONDARY)
            c.fill(rect(x - 2, y + 8, x + 2, y + 11), GLASS if f == SLEEP else EYE)
            c.fill(rect(x, y + 8, x, y + 11), SHADOW)
        else:
            c.piece(shape(rect(x - 1, y - 7, x, y + 6)), STEEL_RAMP)
            c.piece(shape(poly([(x - 4, y - 10), (x + 3, y - 10), (x + 5, y - 7), (x + 3, y - 4), (x - 4, y - 4)])), SECONDARY)
            c.fill(shape(line((x - 2, y - 7), (x + 2, y - 7))), PRIMARY[0])


def arms_2(c, f):
    """Exposed hiking struts, map reader and long basket-tipped trekking pole."""
    for side in (0, 1):
        phase = arm_phase(f, side)
        x = 13 + (2 if phase == "back" else -1 if phase == "forward" else 0)
        y = 20 if f == CHEER else 38
        shape = lambda m: mirror(m) if side else m
        c.piece(shape(circle(19, 29, 2)), PRIMARY)
        elbow = (14, 27 if f == CHEER else 34)
        c.piece(shape(capsule((18, 30), elbow, 1.2)), STEEL_RAMP)
        c.piece(shape(circle(*elbow, 2)), SECONDARY)
        c.piece(shape(capsule(elbow, (x, y), 1.8)), PRIMARY)
        c.piece(shape(circle(x, y + 1, 2)), RUBBER_RAMP)
        if side == 0:
            c.piece(poly([(x - 5, y - 4), (x + 2, y - 5), (x + 5, y), (x - 3, y + 2)]), SECONDARY)
            c.fill(poly([(x - 3, y - 3), (x + 1, y - 4), (x + 3, y - 1), (x - 2, y)]), GLASS)
            c.fill(line((x - 2, y - 2), (x + 1, y - 1)), EYE)
        else:
            c.piece(shape(line((x - 1, y - 8), (x - 5, min(60, y + 20)))), STEEL_RAMP)
            c.piece(shape(rect(x - 2, y - 6, x, y + 1)), SECONDARY)
            c.fill(shape(rect(x - 7, min(57, y + 17), x - 3, min(57, y + 17))), SHADOW)


def legs_1(c, f):
    """Wide coat gaiters and square boots suitable for lookout stairs."""
    for side in (0, 1):
        out, lift, bend = leg_state(f, side)
        x, ground = 25 - out, 61 - lift
        shape = lambda m: mirror(m) if side else m
        c.piece(shape(capsule((26, 44), (x - bend, 50), 2)), STEEL_RAMP)
        c.piece(shape(poly([(x - 3, 48), (x + 2, 48), (x + 4, ground - 3), (x - 5, ground - 3)])), PRIMARY)
        c.piece(shape(rect(x - 5, ground - 5, x + 4, ground - 3)), SECONDARY)
        c.piece(shape(rrect(x - 5, ground - 2, x + 4, ground)), RUBBER_RAMP)
        c.fill(shape(line((x - 3, ground - 2), (x + 2, ground - 2))), STEEL)


def legs_2(c, f):
    """Narrow piston shins with angular kneepads and raised-toe trail boots."""
    for side in (0, 1):
        out, lift, bend = leg_state(f, side)
        x, ground = 25 - out, 61 - lift
        knee = (round(x - bend), 49)
        shape = lambda m: mirror(m) if side else m
        c.piece(shape(capsule((26, 44), knee, 1.3)), STEEL_RAMP)
        c.piece(shape(poly([(knee[0] - 2, 47), (knee[0] + 2, 47), (knee[0] + 3, 50), (knee[0], 52), (knee[0] - 3, 50)])), SECONDARY)
        c.piece(shape(capsule(knee, (x, ground - 3), 1.4)), PRIMARY)
        c.piece(shape(poly([(x - 2, ground - 5), (x + 1, ground - 4), (x + 2, ground - 2), (x + 5, ground - 3), (x + 5, ground), (x - 3, ground)])), SECONDARY)
        c.fill(shape(rect(x - 3, ground, x + 5, ground)), OUT)
        c.fill(shape(line((x - 1, ground - 3), (x + 1, ground - 2))), SHADOW)


PARTS = [
    ("ranger_head_1", "HEAD", "Lookout brim", head_1),
    ("ranger_head_2", "HEAD", "Weather hood", head_2),
    ("ranger_body_1", "BODY", "Lookout coat", body_1),
    ("ranger_body_2", "BODY", "Expedition webbing", body_2),
    ("ranger_arms_1", "ARMS", "Lantern and trail sign", arms_1),
    ("ranger_arms_2", "ARMS", "Map and trekking pole", arms_2),
    ("ranger_legs_1", "LEGS", "Stair gaiters", legs_1),
    ("ranger_legs_2", "LEGS", "Trail pistons", legs_2),
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
    for variant, label in ((1, "Forest lookout"), (2, "Expedition warden")):
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
