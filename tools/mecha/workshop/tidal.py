#!/usr/bin/env python3
"""Original Tidal salvage-diver parts; rerun with Python + Pillow from anywhere.

Only writes workshop/assets/tidal. Shares the shipped courier raster primitives,
neutral outlines and attachment rig, never its authored part silhouettes.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw

MECHA = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(MECHA))
from courier import (  # noqa: E402
    Canvas, OUT, SHADOW, STEEL, HI, STEEL_RAMP, RUBBER_RAMP,
    rect, rrect, ellipse, circle, capsule, line, poly, mirror, mirror_pt,
    unit, arm_phase, leg_state, render_sheet, assemble, hexc,
    DETAIL, SLEEP, CHEER, WALK_L, WALK_R, PASS_A, PASS_B,
)

DEST = Path(__file__).resolve().parent / "assets/tidal"
ROOT = MECHA.parents[1]
TEAL_D, TEAL, TEAL_L = map(hexc, ("294F56", "467C7B", "80AAA0"))
BRASS_D, BRASS, BRASS_L = map(hexc, ("786345", "AD9161", "DEC99A"))
GLASS, GLOW, HOT = map(hexc, ("203B49", "A2D5BB", "E0EBD0"))
MARINE = (TEAL_D, TEAL, TEAL_L)
BRONZE = (BRASS_D, BRASS, BRASS_L)


def glass(c, x, y, radius, f):
    c.piece(circle(x, y, radius + 2), BRONZE)
    c.piece(circle(x, y, radius), (GLASS, GLASS, TEAL_D))
    if f == SLEEP:
        c.fill(line((x - radius + 1, y + 1), (x + radius - 1, y + 1)), TEAL_L)
    elif f == DETAIL:
        c.fill(rect(x - radius + 1, y - 1, x + radius - 1, y), GLOW)
    elif f == CHEER:
        c.fill(line((x - 2, y + 1), (x, y - 1)), HOT)
        c.fill(line((x, y - 1), (x + 2, y + 1)), HOT)
    else:
        look = -1 if f == WALK_L else 1 if f == WALK_R else 0
        c.fill(rect(x - 1 + look, y - 1, x + 1 + look, y + 1), GLOW)
        c.dot(x + look, y - 1, HOT)
    c.dot(x - radius + 1, y - 1, TEAL_L)


def head_1(c, f):
    """Brass bell: broad old pressure helmet with one thick porthole."""
    c.piece(rect(29, 21, 35, 26), STEEL_RAMP)
    dome = ellipse(32, 14, 11, 10) | rrect(21, 15, 43, 21, 1)
    c.piece(dome, BRONZE)
    # Short pressure relief valve is structural, not an antenna.
    c.piece(rect(40, 10, 45, 14), STEEL_RAMP)
    c.fill(rect(43, 11, 45, 12), TEAL_D)
    c.piece(rect(21, 21, 43, 23), MARINE)
    glass(c, 30, 14, 5, f)
    for x, y in ((30, 6), (22, 14), (38, 14), (30, 22)):
        c.dot(x, y, HI)
    c.fill(line((37, 8), (39, 10)), BRASS_L)
    c.fill(rect(39, 16, 41, 18), BRASS_D)


def head_2(c, f):
    """Depth recorder: tall offset instrument hood and side pressure gauge."""
    c.piece(rect(29, 20, 35, 26), STEEL_RAMP)
    c.piece(poly([(25, 5), (36, 5), (39, 8), (39, 22), (23, 22), (23, 8)]), MARINE)
    c.piece(rect(25, 3, 35, 5), BRONZE)
    c.piece(rrect(21, 9, 35, 19, 1), STEEL_RAMP)
    c.fill(rect(23, 11, 33, 17), GLASS)
    if f == SLEEP:
        c.fill(line((24, 15), (31, 15)), TEAL_L)
    elif f == CHEER:
        for x in (25, 30):
            c.fill(line((x - 1, 14), (x, 12)), HOT)
            c.dot(x + 1, 14, HOT)
    else:
        y = 15 if f == DETAIL else 13
        c.fill(rect(25, y, 26, 15), GLOW)
        c.fill(rect(30, y, 31, 15), GLOW)
    c.piece(circle(40, 17, 4), BRONZE)
    c.fill(circle(40, 17, 2), HI)
    end = (41, 16) if f in (DETAIL, WALK_L, PASS_A, CHEER) else (39, 16)
    c.fill(line((40, 18), end), OUT)
    c.fill(rect(27, 20, 33, 20), BRASS)
    c.dot(27, 7, HI)


def sockets(c):
    for x in (20, 44):
        c.piece(circle(x, 29, 3), STEEL_RAMP)
    c.piece(rrect(25, 39, 39, 44, 1), STEEL_RAMP)


def body_1(c, f):
    """Bathysphere: squat spherical pressure vessel, equatorial belt."""
    sockets(c)
    c.piece(ellipse(32, 33, 12, 10), MARINE)
    c.piece(rect(27, 24, 37, 26), BRONZE)
    c.piece(rect(21, 35, 43, 37), BRONZE)
    c.piece(circle(30, 31, 5), BRONZE)
    c.fill(circle(30, 31, 3), GLASS)
    c.fill(line((28, 32), (32, 32)), TEAL_L if f == SLEEP else GLOW)
    if f != SLEEP:
        c.dot(30 + (f % 3) - 1, 30, HOT)
    for x in (24, 33, 40):
        c.dot(x, 36, HI)
    c.fill(rect(27, 40, 35, 40), TEAL_D)
    c.piece(rect(39, 28, 41, 32), STEEL_RAMP)
    c.dot(40, 29, GLOW if f == CHEER else BRASS_L)


def body_2(c, f):
    """Twin tanks: narrow tapered harness between two tall air bottles."""
    sockets(c)
    for x in (24, 40):
        c.piece(rrect(x - 3, 25, x + 3, 40, 3), BRONZE)
        c.fill(rect(x - 2, 30, x + 2, 31), SHADOW)
        c.fill(rect(x - 2, 37, x + 2, 38), SHADOW)
        c.piece(rect(x - 1, 23, x + 1, 25), STEEL_RAMP)
    c.piece(poly([(28, 25), (36, 25), (38, 31), (36, 42), (28, 42), (26, 31)]), MARINE)
    c.piece(rect(28, 24, 36, 26), STEEL_RAMP)
    c.piece(circle(32, 32, 3), STEEL_RAMP)
    c.fill(circle(32, 32, 2), BRASS_L)
    c.fill(line((32, 33), (31 if f == SLEEP else 33, 31)), OUT)
    c.fill(rect(30, 38, 34, 39), GLASS)
    c.dot(31 + f % 3, 38, TEAL_D if f == SLEEP else GLOW)


def arms(c, f, heavy):
    """Opposed rig swing, not free-floating or tentacle locomotion."""
    for side in (0, 1):
        phase = arm_phase(f, side)
        elbow, wrist = {
            "neutral": ((15, 36), (13, 42)),
            "forward": ((16, 35), (16, 40)),
            "back": ((14, 35), (11, 41)),
        }[phase]
        if f == SLEEP:
            elbow, wrist = (16, 37), (17, 43)
        elif f == CHEER:
            elbow, wrist = (12, 23), (12, 16)
        shoulder = (19, 29)
        if side:
            shoulder, elbow, wrist = map(mirror_pt, (shoulder, elbow, wrist))
        dx, dy = unit(wrist[0] - elbow[0], wrist[1] - elbow[1])
        px, py = -dy, dx

        def point(forward, across):
            return (wrist[0] + dx * forward + px * across,
                    wrist[1] + dy * forward + py * across)

        opening = 1 if f == SLEEP else 3 if f in (DETAIL, CHEER) else 2
        for sign in (-1, 1):
            if heavy:
                # Broad concave salvage jaws: short squared shovel tips.
                jaw = poly([tuple(map(round, point(a, b * sign))) for a, b in
                            ((0, 1), (2, opening + 1), (6, opening + 1),
                             (6, opening - 1), (4, opening - 1), (3, 1))])
                c.piece(jaw, STEEL_RAMP)
            else:
                # Fine specimen hooks, with visibly hooked inward tips.
                tip = point(5, opening * sign)
                c.piece(capsule(point(0, sign), tip, 0.7), BRONZE)
                c.piece(capsule(tip, point(6, (opening - 1) * sign), 0.6), STEEL_RAMP)
        c.piece(capsule(shoulder, elbow, 1.5 if heavy else 1), STEEL_RAMP)
        c.piece(capsule(elbow, wrist, 2.6 if heavy else 1.5), MARINE)
        c.piece(circle(*elbow, 2 if heavy else 1.6), BRONZE)
        c.piece(circle(*wrist, 1.5), STEEL_RAMP)
        if heavy:
            cap = poly([(14, 26), (21, 25), (23, 29), (21, 32), (14, 31)])
            c.piece(mirror(cap) if side else cap, MARINE)
            c.dot(45 if side else 19, 27, HI)
        else:
            c.piece(circle(*shoulder, 3), BRONZE)
            c.dot(round(shoulder[0]), 28, HI)


def arms_1(c, f):
    arms(c, f, False)


def arms_2(c, f):
    arms(c, f, True)


def legs(c, f, tall):
    for side in (0, 1):
        out, lift, bend = leg_state(f, side)
        hip = (26, 44)
        ax, ground = 24 - out, 61 - lift
        ankle = (ax, ground - (6 if tall else 4))
        knee = ((hip[0] + ax) / 2 - bend, 49 - lift / 2)
        masks = [
            (circle(*hip, 2.5), STEEL_RAMP),
            (capsule(hip, knee, 1.6), STEEL_RAMP),
            (capsule(knee, ankle, 2.5 if tall else 1.6), MARINE),
            (circle(*knee, 2), BRONZE),
        ]
        if tall:
            # Tall bellows gaiter above a short, thick lead-toed diving boot.
            masks += [(rrect(ax - 3, ground - 8, ax + 3, ground - 3, 1), MARINE),
                      (poly([(ax - 3, ground - 4), (ax + 3, ground - 4),
                             (ax + 5, ground - 2), (ax + 5, ground),
                             (ax - 4, ground), (ax - 4, ground - 2)]), BRONZE)]
        else:
            # Low, broad ballast shoe with raised toe and exposed ankle rod.
            masks += [(poly([(ax - 6, ground - 3), (ax - 3, ground - 5),
                             (ax + 3, ground - 4), (ax + 5, ground - 2),
                             (ax + 5, ground), (ax - 6, ground)]), MARINE)]
        for mask, ramp in masks:
            c.piece(mirror(mask) if side else mask, ramp)
        tread = rect(ax - (4 if tall else 6), ground - 1, ax + 5, ground)
        strap = rect(ax - 2, ground - 3, ax, ground - 2)
        c.fill(mirror(tread) if side else tread, SHADOW)
        c.fill(mirror(strap) if side else strap, HI if tall else BRASS_L)
        if tall:
            for y in (ground - 7, ground - 5):
                seam = line((ax - 2, y), (ax + 2, y))
                c.fill(mirror(seam) if side else seam, TEAL_D)


def legs_1(c, f):
    legs(c, f, True)


def legs_2(c, f):
    legs(c, f, False)


PARTS = [
    ("tidal_head_1", "HEAD", "Brass bell", head_1),
    ("tidal_head_2", "HEAD", "Depth recorder", head_2),
    ("tidal_body_1", "BODY", "Bathysphere", body_1),
    ("tidal_body_2", "BODY", "Twin tanks", body_2),
    ("tidal_arms_1", "ARMS", "Specimen hooks", arms_1),
    ("tidal_arms_2", "ARMS", "Salvage jaws", arms_2),
    ("tidal_legs_1", "LEGS", "Lead-toe gaiters", legs_1),
    ("tidal_legs_2", "LEGS", "Ballast shoes", legs_2),
]
BG, PANEL, INK = "#E3E9E1", "#CAD7D0", "#203B49"


def tile(sprite, scale):
    backing = Image.new("RGBA", (64, 64), PANEL)
    backing.alpha_composite(sprite)
    return backing.resize((64 * scale, 64 * scale), Image.Resampling.NEAREST)


def render_review(sheets, combos):
    board = Image.new("RGB", (1200, 1720), BG)
    d = ImageDraw.Draw(board)
    d.text((20, 16), "TIDAL / deep-sea salvage workshop / original pixel parts", fill=INK)
    d.text((20, 35), "Brass, sea glass and weathered marine enamel. Same biped rig; no new locomotion.", fill=INK)
    for i, (name, combo) in enumerate(combos):
        x = 20 + i * 295
        d.text((x, 62), name, fill=INK)
        sprite = assemble(sheets, combo, 0)
        board.paste(tile(sprite, 3), (x, 82))
        board.paste(tile(sprite, 1), (x + 205, 82))
        d.text((x + 205, 153), "1x", fill=INK)
        board.paste(tile(sprite, 1).resize((32, 32), Image.Resampling.NEAREST), (x + 205, 180))
        d.text((x + 205, 220), "0.5x", fill=INK)
    labels = ("idle", "detail", "contact L", "passing A", "contact R", "passing B", "sleep", "celebrate")
    for f, label in enumerate(labels):
        d.text((170 + f * 128, 296), label, fill=INK)
    for row, (pid, _, name, _) in enumerate(PARTS):
        y = 320 + row * 140
        d.text((15, y + 20), name, fill=INK)
        d.text((15, y + 38), pid, fill=INK)
        for f in range(8):
            frame = sheets[pid].crop((f * 64, 0, (f + 1) * 64, 64))
            board.paste(tile(frame, 2), (170 + f * 128, y))
    for row in range(2):
        y = 1450 + row * 130
        d.text((15, y + 25), "Set %d / all poses" % (row + 1), fill=INK)
        for f in range(8):
            board.paste(tile(assemble(sheets, combos[row][1], f), 2), (170 + f * 128, y))
    board.save(DEST / "board.png")
    sequence = ([(0, 700), (1, 200), (0, 400)] +
                [(f, 150) for f in (2, 3, 4, 5) * 3] +
                [(6, 1000), (7, 350), (0, 300), (7, 350), (0, 500)])
    frames = []
    for f, _ in sequence:
        im = Image.new("RGB", (1152, 328), BG)
        draw = ImageDraw.Draw(im)
        for i, (name, combo) in enumerate(combos):
            draw.text((i * 288 + 16, 12), name, fill=INK)
            cell = Image.new("RGBA", (72, 72), BG)
            bob = -1 if f in (PASS_A, PASS_B) else 1 if f == SLEEP else -2 if f == CHEER else 0
            cell.alpha_composite(assemble(sheets, combo, f), (4, 4 + bob))
            im.paste(cell.resize((288, 288), Image.Resampling.NEAREST), (i * 288, 32))
        frames.append(im)
    frames[0].save(DEST / "preview.gif", save_all=True, append_images=frames[1:],
                   duration=[ms for _, ms in sequence], loop=0, disposal=2)


def main():
    DEST.mkdir(parents=True, exist_ok=True)
    sheets, catalog = {}, []
    for pid, slot, name, draw in PARTS:
        sheets[pid] = render_sheet(draw)
        sheets[pid].save(DEST / (pid + ".png"))
        catalog.append(dict(id=pid, slot=slot, family="tidal", name=name,
                            asset=f"mecha/tidal/{pid}.png"))
    (DEST / "catalog.json").write_text(json.dumps(catalog, indent=2) + "\n")
    slots = ("HEAD", "BODY", "ARMS", "LEGS")
    combos = [("Tidal %d" % variant, {s: f"tidal_{s.lower()}_{variant}" for s in slots})
              for variant in (1, 2)]
    combos += [
        ("Yard salvage", dict(HEAD="tidal_head_1", BODY="yard_body_2", ARMS="tidal_arms_2", LEGS="yard_legs_1")),
        ("Survey diver", dict(HEAD="survey_head_2", BODY="tidal_body_2", ARMS="survey_arms_1", LEGS="tidal_legs_2")),
    ]
    for _, combo in combos:
        for pid in combo.values():
            if pid not in sheets:
                family = pid.split("_")[0]
                with Image.open(ROOT / "mecha/src/main/assets/mecha" / family / (pid + ".png")) as im:
                    sheets[pid] = im.convert("RGBA")
    render_review(sheets, combos)
    print(f"Rendered 8 sheets, catalog, board and animated preview in {DEST}")


if __name__ == "__main__":
    main()
