#!/usr/bin/env python3
"""Authoritative domestic repair / tea-service drawings for the Rivet workshop.

Run: python3 tools/mecha/workshop/domestic.py
Writes only assets/domestic beside this script. Uses the existing courier mask
primitives and shared rig: neck32,25; shoulders19/45,29; hips26/38,44; floor61.
The two silhouettes are Bellhop (enamel pressure-service) and Mend (bench repair).
Production copies only the catalog and eight part sheets, checked by test_production.py.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from courier import (Canvas, OUT, SHADOW, STEEL, HI, STEEL_RAMP, rect, rrect,
                     ellipse, circle, capsule, poly, line, mirror, hexc,
                     render_sheet, assemble, leg_state)

ROOT = HERE.parents[2]
ASSETS = HERE / "assets/domestic"
CREAM = tuple(map(hexc, ("A7997D", "E8DABD", "FFF2D5")))
SAGE = tuple(map(hexc, ("405A4F", "79947A", "B8CBA2")))
BRICK = tuple(map(hexc, ("8C443A", "C77457", "F3B281")))
GOLD = hexc("F6C770")
HOT = hexc("FFF2C0")
RUBBER = (OUT, SHADOW, STEEL)
IDLE, DETAIL, LEFT, PASS_A, RIGHT, PASS_B, SLEEP, CHEER = range(8)
ORDER = ("LEGS", "BODY", "ARMS", "HEAD")


def eye(c, x, y, f, width=4):
    c.fill(rect(x, y, x + width - 1, y + 3), OUT)
    if f in (DETAIL, SLEEP):
        c.fill(rect(x, y + 2, x + width - 1, y + 2), SAGE[2] if f == SLEEP else GOLD)
    elif f == CHEER:
        c.fill(line((x, y + 2), (x + width // 2, y)), GOLD)
        c.fill(line((x + width // 2, y), (x + width - 1, y + 2)), GOLD)
    else:
        look = -1 if f == LEFT else 1 if f == RIGHT else 0
        c.fill(rect(x + 1 + max(look, 0), y + 1, x + width - 2 + min(look, 0), y + 2), GOLD)
        c.dot(x + 1 + max(look, 0), y + 1, HOT)


def head_1(c, f):
    """Bellhop: tall tapered enamel cowling; pressure vent becomes a brow."""
    c.piece(rect(29, 21, 35, 26), STEEL_RAMP)
    shell = poly([(26, 6), (37, 6), (40, 10), (42, 20), (39, 23),
                  (24, 23), (22, 20), (23, 11)])
    c.piece(shell, CREAM)
    c.piece(rrect(27, 4, 36, 6, 1), SAGE)
    # A spring-loaded pressure flag, not a hat or a pasted-on kettle.
    c.piece(rect(36, 7 if f != CHEER else 3, 38, 10), STEEL_RAMP)
    c.dot(37, 7 if f != CHEER else 3, BRICK[1])
    c.piece(poly([(24, 11), (39, 11), (40, 13), (23, 13)]), SAGE)
    c.fill(line((25, 10), (35, 10)), CREAM[2])
    eye(c, 25, 15, f, 5)
    eye(c, 33, 15, f, 5)
    c.fill(line((29, 21), (34, 21)), BRICK[0])
    c.dot(35, 20, BRICK[1])
    # Side whistle port is part of the shell's breathing mechanism.
    c.piece(rect(20, 16, 22, 20), BRICK)
    c.dot(20, 17, OUT)
    if f == CHEER:
        c.fill({(18, 11), (18, 10), (19, 9), (18, 8)}, HI)


def head_2(c, f):
    """Mend: low asymmetric timer face with one dial-eye and one slit-eye."""
    c.piece(rect(28, 20, 36, 26), STEEL_RAMP)
    c.piece(poly([(20, 12), (24, 9), (39, 9), (43, 13), (44, 20),
                  (40, 23), (22, 23), (19, 20)]), SAGE)
    c.piece(rect(25, 7, 37, 9), BRICK)
    for x in (27, 30, 33, 36):
        c.dot(x, 8, OUT)
    c.piece(circle(27, 16, 5), CREAM)
    c.piece(circle(27, 16, 3), RUBBER)
    if f == SLEEP:
        c.fill(line((25, 17), (29, 17)), SAGE[2])
    else:
        end = (29, 14) if f in (DETAIL, CHEER) else (28, 14)
        c.fill(line((27, 17), end), GOLD)
        c.dot(27, 17, HOT)
    eye(c, 35, 15, f, 5)
    for x in (34, 36, 38):
        c.fill(rect(x, 21, x, 22), OUT)
    c.piece(rect(43, 15, 45, 19), STEEL_RAMP)
    c.dot(44, 16, BRICK[1])


def socket(c):
    for x in (20, 44):
        c.piece(circle(x, 29, 2.6), STEEL_RAMP)
    c.piece(rrect(24, 40, 40, 44, 2), STEEL_RAMP)
    c.piece(rect(28, 37, 36, 41), SAGE)
    for x in (26, 38):
        c.dot(x, 43, HI)


def body_1(c, f):
    """Bellhop: narrow throat, broad rounded pressure belly and integral apron."""
    socket(c)
    shell = poly([(27, 25), (37, 25), (40, 28), (43, 34), (43, 37),
                  (40, 40), (24, 40), (21, 37), (21, 34), (24, 28)])
    c.piece(shell, CREAM)
    c.piece(rect(28, 24, 36, 26), BRICK)
    c.piece(poly([(28, 29), (36, 29), (39, 37), (25, 37)]), SAGE)
    # The gauge is a functional internal reservoir window, not a carried cup.
    c.piece(rrect(29, 29, 34, 35, 1), RUBBER)
    level = 32 if f in (DETAIL, RIGHT) else 33
    c.fill(rect(30, level, 33, 34), GOLD if f != SLEEP else BRICK[0])
    c.fill(line((30, 30), (30, 31)), HI)
    c.piece(circle(39, 33, 2), BRICK)
    c.fill(line((38, 33), (40, 33)), OUT)
    c.fill(line((25, 38), (39, 38)), BRICK[1])
    c.dot(23, 34, HOT if f == CHEER else CREAM[2])


def body_2(c, f):
    """Mend: broad service cabinet shoulders, inset waist, open tool drawer."""
    socket(c)
    c.piece(poly([(21, 25), (42, 25), (44, 28), (41, 33), (38, 36),
                  (38, 40), (26, 40), (26, 36), (22, 33), (20, 28)]), SAGE)
    c.piece(rect(27, 24, 37, 26), CREAM)
    c.piece(rrect(24, 28, 40, 33, 1), CREAM)
    c.fill(rect(27, 29, 37, 30), OUT)
    c.fill(line((29, 31), (35, 31)), BRICK[1])
    c.piece(rect(28, 35, 36, 38), BRICK)
    c.fill(line((30, 36), (34, 36)), CREAM[2])
    for x in (23, 41):
        c.dot(x, 27, HI)
    c.dot(39, 35, GOLD if f != SLEEP else SHADOW)
    if f == CHEER:
        c.piece(poly([(27, 30), (38, 30), (40, 34), (25, 34)]), CREAM)
        c.fill(rect(28, 31, 36, 32), OUT)
        c.fill(line((30, 31), (30, 32)), GOLD)
        c.fill(line((34, 31), (35, 32)), STEEL)


def arm_pose(f, side):
    shoulder = (19, 29)
    if f == CHEER:
        elbow, wrist = (12, 26), (10, 19)
    elif f == SLEEP:
        elbow, wrist = (16, 36), (19, 40)
    else:
        swing = (f == LEFT and side == 1) or (f == RIGHT and side == 0)
        back = (f == LEFT and side == 0) or (f == RIGHT and side == 1)
        elbow, wrist = (14, 34), ((16, 39) if swing else (11, 40) if back else (12, 41))
    if side:
        return tuple((64 - x, y) for x, y in (shoulder, elbow, wrist))
    return shoulder, elbow, wrist


def tool(c, wrist, elbow, f, kind):
    x, y = wrist
    up = -1 if y < elbow[1] else 1
    if kind == "pincer":
        c.piece(rect(x - 2, y - 1, x + 2, y + 1), BRICK)
        spread = 3 if f in (DETAIL, CHEER) else 2
        for s in (-1, 1):
            c.piece(capsule((x + s * spread, y + up * 2), (x + s * spread, y + up * 5), 1), CREAM)
            c.dot(x + s * (spread - 1), y + up * 5, CREAM[2])
    elif kind == "nozzle":
        c.piece(capsule((x, y), (x, y + up * 3), 2), CREAM)
        c.piece(capsule((x, y + up * 3), (x + 3, y + up * 4), 1), STEEL_RAMP)
        c.dot(x + 4, y + up * 4, OUT)
    elif kind == "driver":
        c.piece(capsule((x, y - up), (x, y + up * 3), 2), BRICK)
        c.piece(line((x, y + up * 4), (x, y + up * 7)), STEEL_RAMP)
        c.fill(rect(x - 1, y + up * 7, x + 1, y + up * 7), HI)
    else:
        c.piece(circle(x, y, 2), STEEL_RAMP)
        for s in (-1, 1):
            c.piece(capsule((x + s * 3, y + up), (x + s * 3, y + up * 4), 1), SAGE)
            c.piece(line((x + s * 3, y + up * 4), (x + s, y + up * 5)), STEEL_RAMP)


def arms_1(c, f):
    """Bellhop: slim hinged serving tongs and a plumbed dispensing palm."""
    for side in (0, 1):
        shoulder, elbow, wrist = arm_pose(f, side)
        c.piece(capsule(shoulder, elbow, 1.3), STEEL_RAMP)
        c.piece(capsule(elbow, wrist, 1.8), SAGE)
        c.piece(circle(*elbow, 2), BRICK)
        c.piece(circle(*shoulder, 3), CREAM)
        tool(c, wrist, elbow, f, "pincer" if side == 0 else "nozzle")


def arms_2(c, f):
    """Mend: big triangular sleeves, telescoping driver and adjustable clamp."""
    for side in (0, 1):
        shoulder, elbow, wrist = arm_pose(f, side)
        c.piece(capsule(shoulder, elbow, 2), SAGE)
        c.piece(capsule(elbow, wrist, 2.5), CREAM)
        c.piece(circle(*elbow, 2), STEEL_RAMP)
        cap = poly([(14, 27), (18, 25), (22, 26), (22, 30), (16, 32), (13, 30)])
        c.piece(mirror(cap) if side else cap, BRICK)
        c.dot(shoulder[0], 28, CREAM[2])
        tool(c, wrist, elbow, f, "driver" if side == 0 else "clamp")


def legs_1(c, f):
    """Bellhop: bent light struts on quiet, long felt-soled slipper feet."""
    for side in (0, 1):
        outward, lift, bend = leg_state(f, side)
        hip = (26, 44)
        knee = (int(25 - bend), 49)
        ankle = (23 - outward, 56 - lift)
        foot = poly([(18 - outward, 55 - lift), (25 - outward, 55 - lift),
                     (28 - outward, 58 - lift), (28 - outward, 60 - lift),
                     (16 - outward, 60 - lift), (16 - outward, 58 - lift)])
        if side:
            hip, knee, ankle = ((64 - x, y) for x, y in (hip, knee, ankle))
            foot = mirror(foot)
        c.piece(capsule(hip, knee, 1.7), STEEL_RAMP)
        c.piece(capsule(knee, ankle, 1.5), BRICK)
        c.piece(circle(*knee, 2), CREAM)
        c.piece(foot, SAGE)
        c.fill(foot & rect(0, 60 - lift, 63, 60 - lift), SHADOW)
        c.dot(hip[0], hip[1], HI)


def legs_2(c, f):
    """Mend: broad telescopic braces and square anti-slip repair stands."""
    for side in (0, 1):
        outward, lift, bend = leg_state(f, side)
        hip = (26, 44)
        knee = (23 - int(bend / 2), 50)
        ankle = (23 - outward, 56 - lift)
        foot = rrect(18 - outward, 57 - lift, 27 - outward, 60 - lift, 1)
        brace = poly([(23, 45), (29, 45), (27, 49), (25, 53), (20, 53), (19, 50)])
        if side:
            hip, knee, ankle = ((64 - x, y) for x, y in (hip, knee, ankle))
            foot, brace = mirror(foot), mirror(brace)
        c.piece(capsule(hip, knee, 2), STEEL_RAMP)
        c.piece(capsule(knee, ankle, 2), STEEL_RAMP)
        c.piece(brace, CREAM)
        c.piece(circle(*knee, 2), BRICK)
        c.dot(knee[0], knee[1], OUT)
        c.piece(foot, BRICK)
        c.fill(foot & rect(0, 60 - lift, 63, 60 - lift), SHADOW)
        c.fill(line((ankle[0] - 2, 57 - lift), (ankle[0] + 2, 57 - lift)), CREAM[2])


PARTS = [
    ("domestic_head_1", "HEAD", "Pressure brow", head_1),
    ("domestic_head_2", "HEAD", "Timer wink", head_2),
    ("domestic_body_1", "BODY", "Service bell", body_1),
    ("domestic_body_2", "BODY", "Mending cabinet", body_2),
    ("domestic_arms_1", "ARMS", "Pour and pinch", arms_1),
    ("domestic_arms_2", "ARMS", "Driver and vice", arms_2),
    ("domestic_legs_1", "LEGS", "Quiet slippers", legs_1),
    ("domestic_legs_2", "LEGS", "Repair stands", legs_2),
]
BG, INK = "#eee7d6", "#263b35"


def combination(h, b, a, legs):
    return dict(zip(("HEAD", "BODY", "ARMS", "LEGS"), (h, b, a, legs)))


def full(n):
    return {slot: f"domestic_{slot.lower()}_{n}" for slot in ORDER}


def review_board(sheets, combos):
    board = Image.new("RGBA", (1192, 1320), BG)
    d = ImageDraw.Draw(board)
    d.text((20, 15), "DOMESTIC / Bellhop & Mend", fill=INK)
    d.text((20, 34), "Enamel pressure-service + bench repair. Original modular pixel art / workshop only.", fill=INK)
    d.text((20, 54), "Idle | detail | left contact | passing | right contact | passing | sleep | celebrate", fill=INK)
    for i, (label, combo) in enumerate(combos):
        y = 85 + i * 165
        d.text((16, y + 5), label, fill=INK)
        for f in range(8):
            sprite = assemble(sheets, combo, f)
            board.alpha_composite(sprite.resize((128, 128), Image.Resampling.NEAREST), (155 + f * 128, y))
        # Deliberately native-size, not a downsample of the enlarged review.
        board.alpha_composite(assemble(sheets, combo, 0), (38, y + 30))
        d.text((38, y + 100), "1x", fill=INK)
    y = 765
    d.text((20, y), "ISOLATED PARTS / idle at 3x with native-size specimen", fill=INK)
    for i, (pid, slot, name, _) in enumerate(PARTS):
        x, top = 20 + (i % 4) * 290, y + 28 + (i // 4) * 246
        d.text((x, top), name, fill=INK)
        d.text((x, top + 15), pid, fill=INK)
        frame = sheets[pid].crop((0, 0, 64, 64))
        board.alpha_composite(frame.resize((192, 192), Image.Resampling.NEAREST), (x, top + 35))
        board.alpha_composite(frame, (x + 202, top + 95))
    return board


def animated_preview(sheets, combos):
    sequence = [(0, 600), (1, 200), (0, 300)] + [(f, 140) for f in (2, 3, 4, 5) * 3] + [(6, 1100), (7, 650), (0, 400)]
    frames = []
    for f, _ in sequence:
        frame = Image.new("RGBA", (1024, 300), BG)
        d = ImageDraw.Draw(frame)
        for i, (label, combo) in enumerate(combos):
            d.text((i * 256 + 14, 12), label, fill=INK)
            bob = -1 if f in (PASS_A, PASS_B) else 0
            sprite = assemble(sheets, combo, f)
            frame.alpha_composite(sprite.resize((256, 256), Image.Resampling.NEAREST), (i * 256, 30 + bob * 4))
        frames.append(frame.convert("P", palette=Image.Palette.ADAPTIVE, colors=128))
    frames[0].save(ASSETS / "preview.gif", save_all=True, append_images=frames[1:],
                   duration=[ms for _, ms in sequence], loop=0, disposal=2)


def main():
    ASSETS.mkdir(parents=True, exist_ok=True)
    sheets = {}
    catalog = []
    for pid, slot, name, draw in PARTS:
        sheets[pid] = render_sheet(draw)
        sheets[pid].save(ASSETS / f"{pid}.png", optimize=True)
        catalog.append(dict(id=pid, slot=slot, family="domestic", name=name,
                            asset=f"mecha/domestic/{pid}.png"))
    (ASSETS / "catalog.json").write_text(json.dumps(catalog, indent=2) + "\n")
    combos = [("Bellhop / service", full(1)), ("Mend / repair", full(2)),
              ("Domestic x yard", combination("domestic_head_1", "yard_body_1", "domestic_arms_2", "yard_legs_1")),
              ("Survey x domestic", combination("survey_head_2", "domestic_body_2", "survey_arms_1", "domestic_legs_1"))]
    for _, combo in combos:
        for pid in combo.values():
            if pid not in sheets:
                family = pid.split("_")[0]
                with Image.open(ROOT / "mecha/src/main/assets/mecha" / family / f"{pid}.png") as source:
                    sheets[pid] = source.convert("RGBA")
    review_board(sheets, combos).save(ASSETS / "board.png")
    animated_preview(sheets, combos)
    print(f"Rendered eight domestic sheets, catalog, board and preview into {ASSETS}")


if __name__ == "__main__":
    main()
