#!/usr/bin/env python3
"""Courier mecha family generator (nimble delivery, coral/navy).

Draws the eight modular part sheets for the courier family as original pixel
art, following the shared Sortie mecha art contract: each sheet is a 512x64
RGBA strip of eight 64x64 frames (0 idle, 1 idle detail, 2-5 walk, 6 sleep,
7 celebrate) using common attachment points, so courier parts mix with yard
and survey parts.

Outputs (repeatable, overwrites):
  mecha/src/main/assets/mecha/courier/courier_<slot>_<1|2>.png
  mecha/src/main/assets/mecha/courier/catalog.json
  ~/.cache/sortie-mecha-lab/courier-board.png   (review contact sheet)
  ~/.cache/sortie-mecha-lab/courier-walk.gif    (animated assembled review)

Requires Pillow. Run from anywhere: python3 tools/mecha/courier.py
"""
from __future__ import annotations

import json
import math
from pathlib import Path

from PIL import Image, ImageDraw
from paint_roles import export_part, legacy_overrides

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "mecha/src/main/assets/mecha/courier"
CACHE = Path.home() / ".cache/sortie-mecha-lab"
FAMILY = "courier"
SIZE = 64
FRAMES = 8


def hexc(value: str) -> tuple[int, int, int, int]:
    value = value.lstrip("#")
    return (int(value[0:2], 16), int(value[2:4], 16), int(value[4:6], 16), 255)


# Shared contract neutrals.
OUT = hexc("18232D")
SHADOW = hexc("344650")
STEEL = hexc("768A91")
HI = hexc("D7DFD5")
# Courier accents: coral shell, navy livery, warm sorting-lamp glow.
CORAL_D = hexc("A9434B")
CORAL = hexc("E2695B")
CORAL_L = hexc("F7A48A")
NAVY_D = hexc("23375E")
NAVY = hexc("35548C")
NAVY_L = hexc("5F80B6")
GLOW = hexc("FFD27A")
HOT = hexc("FFF4D2")

STEEL_RAMP = (SHADOW, STEEL, HI)
CORAL_RAMP = (CORAL_D, CORAL, CORAL_L)
NAVY_RAMP = (NAVY_D, NAVY, NAVY_L)
RUBBER_RAMP = (OUT, SHADOW, STEEL)
PAPER_RAMP = (STEEL, HI, HI)

PAINT_PRIMARY = CORAL_RAMP
PAINT_SECONDARY = NAVY_RAMP

IDLE, DETAIL, WALK_L, PASS_A, WALK_R, PASS_B, SLEEP, CHEER = range(8)
N4 = ((1, 0), (-1, 0), (0, 1), (0, -1))

Mask = set[tuple[int, int]]

# --------------------------------------------------------------------------
# Mask primitives (all integer pixel sets, no antialiasing)
# --------------------------------------------------------------------------


def rect(x0: int, y0: int, x1: int, y1: int) -> Mask:
    return {(x, y) for x in range(x0, x1 + 1) for y in range(y0, y1 + 1)}


def rrect(x0: int, y0: int, x1: int, y1: int, r: int = 1) -> Mask:
    m = rect(x0, y0, x1, y1)
    for cx, cy, sx, sy in ((x0, y0, 1, 1), (x1, y0, -1, 1), (x0, y1, 1, -1), (x1, y1, -1, -1)):
        for i in range(r):
            for j in range(r - i):
                m.discard((cx + sx * i, cy + sy * j))
    return m


def ellipse(cx: float, cy: float, rx: float, ry: float) -> Mask:
    out = set()
    for y in range(int(cy - ry) - 1, int(cy + ry) + 2):
        for x in range(int(cx - rx) - 1, int(cx + rx) + 2):
            if ((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2 <= 1.0:
                out.add((x, y))
    return out


def circle(cx: float, cy: float, r: float) -> Mask:
    return ellipse(cx, cy, r, r)


def capsule(p0: tuple[float, float], p1: tuple[float, float], r: float) -> Mask:
    (x0, y0), (x1, y1) = p0, p1
    dx, dy = x1 - x0, y1 - y0
    length2 = dx * dx + dy * dy or 1e-9
    out = set()
    for y in range(int(min(y0, y1) - r) - 1, int(max(y0, y1) + r) + 2):
        for x in range(int(min(x0, x1) - r) - 1, int(max(x0, x1) + r) + 2):
            t = max(0.0, min(1.0, ((x - x0) * dx + (y - y0) * dy) / length2))
            px, py = x0 + t * dx - x, y0 + t * dy - y
            if px * px + py * py <= r * r:
                out.add((x, y))
    return out


def line(p0: tuple[int, int], p1: tuple[int, int]) -> Mask:
    (x0, y0), (x1, y1) = p0, p1
    steps = max(abs(x1 - x0), abs(y1 - y0), 1)
    return {(round(x0 + (x1 - x0) * i / steps), round(y0 + (y1 - y0) * i / steps)) for i in range(steps + 1)}


def poly(points: list[tuple[int, int]]) -> Mask:
    img = Image.new("L", (SIZE, SIZE), 0)
    ImageDraw.Draw(img).polygon(points, fill=255, outline=255)
    px = img.load()
    return {(x, y) for y in range(SIZE) for x in range(SIZE) if px[x, y]}


def mirror(mask: Mask) -> Mask:
    return {(64 - x, y) for x, y in mask}


def mx(x: float) -> float:
    return 64 - x


# --------------------------------------------------------------------------
# Canvas: pieces drawn back-to-front, each with its own 1px outline
# --------------------------------------------------------------------------


class Canvas:
    def __init__(self) -> None:
        self.img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
        self.px = self.img.load()

    @staticmethod
    def _clip(mask: Mask) -> Mask:
        return {(x, y) for x, y in mask if 0 <= x < SIZE and 0 <= y < SIZE}

    def piece(self, mask: Mask, ramp, outline: bool = True, shade: str = "rim", band: tuple[int, int] | None = None) -> Mask:
        """Outline + fill a shape. shade: rim (top-left lit), flat, or cyl (vertical bands)."""
        mask = self._clip(mask)
        if outline:
            for x, y in mask:
                for dx, dy in N4:
                    q = (x + dx, y + dy)
                    if q not in mask and 0 <= q[0] < SIZE and 0 <= q[1] < SIZE:
                        self.px[q] = OUT
        dark, base, light = ramp
        for x, y in mask:
            if shade == "flat":
                c = base
            else:
                right_open = (x + 1, y) not in mask
                down_open = (x, y + 1) not in mask
                up_open = (x, y - 1) not in mask
                left_open = (x - 1, y) not in mask
                if shade == "cyl" and band is not None:
                    lo, hi = band  # lit stripe columns lo..hi, dark side beyond the far third
                    xs = [p[0] for p in mask if p[1] == y]
                    span_r, width = max(xs), max(xs) - min(xs) + 1
                    if down_open or right_open or (width > 8 and x >= span_r - 2):
                        c = dark
                    elif lo <= x <= hi or up_open:
                        c = light
                    else:
                        c = base
                else:
                    if right_open or down_open:
                        c = dark
                    elif up_open or left_open:
                        c = light
                    else:
                        c = base
            self.px[x, y] = c
        return mask

    def fill(self, mask: Mask, color) -> None:
        for x, y in self._clip(mask):
            self.px[x, y] = color

    def dot(self, x: int, y: int, color) -> None:
        if 0 <= x < SIZE and 0 <= y < SIZE:
            self.px[x, y] = color

    def recolor(self, mask: Mask, src, dst) -> None:
        for x, y in self._clip(mask):
            if self.px[x, y] == src:
                self.px[x, y] = dst


def sparkle(c: Canvas, x: int, y: int) -> None:
    c.fill({(x, y - 1), (x, y + 1), (x - 1, y), (x + 1, y)}, GLOW)
    c.dot(x, y, HOT)


# --------------------------------------------------------------------------
# HEAD 1: "Pillar slot" - a cylindrical post-box dome with one sorting slot
# --------------------------------------------------------------------------


def head_1(c: Canvas, f: int) -> None:
    # Neck collar reaches into the body socket (y24..26).
    c.piece(rect(29, 20, 35, 26), STEEL_RAMP)
    c.fill(line((30, 24), (34, 24)), SHADOW)

    # Antenna with a lamp, behind the dome; it sways on walk frames.
    sway = {WALK_L: -1, WALK_R: 1}.get(f, 0)
    tip = (38 + sway, 5) if f != SLEEP else (40, 7)
    c.piece(line((37, 11), tip), STEEL_RAMP, shade="flat")
    lamp = {SLEEP: SHADOW, CHEER: HOT, DETAIL: GLOW}.get(f, CORAL_L)
    c.piece(circle(tip[0], tip[1], 1.1), (lamp, lamp, lamp), shade="flat")

    # Dome body, cylinder-shaded with a lit stripe on the left.
    dome = (ellipse(31.5, 13.5, 11.5, 8.6) & rect(0, 0, 63, 13)) | rrect(20, 13, 43, 22, 2)
    c.piece(dome, CORAL_RAMP, shade="cyl", band=(22, 25))
    c.fill({(24, 7), (25, 7), (23, 8)}, CORAL_L)
    c.dot(26, 6, HOT)
    # Cap band (the pillar-box brim ring)
    band = rect(19, 11, 44, 12)
    c.piece(band, NAVY_RAMP, shade="cyl", band=(21, 25))
    # Skirt ring at the base of the dome
    c.piece(rect(21, 22, 42, 23), NAVY_RAMP, shade="cyl", band=(22, 25))

    # Mail-slot eye, set left of centre for the three-quarter turn.
    c.piece(rrect(22, 14, 37, 18, 1), STEEL_RAMP)
    recess = rect(23, 15, 36, 17)
    c.fill(recess, OUT)
    if f == DETAIL:  # blink: flap drops halfway
        c.fill(rect(23, 15, 36, 16), CORAL_D)
        c.fill(line((23, 16), (36, 16)), CORAL)
        c.fill(line((25, 17), (34, 17)), GLOW)
    elif f == SLEEP:  # flap fully shut, closed eye seam
        c.fill(recess, CORAL_D)
        c.fill(line((23, 15), (36, 15)), CORAL)
        c.fill({(26, 16), (27, 17), (28, 17), (29, 17), (30, 17), (31, 16)}, OUT)
    elif f == CHEER:  # slot blazes open
        c.fill(recess, GLOW)
        c.fill(rect(25, 16, 34, 16), HOT)
        c.fill(rect(27, 15, 30, 17), HOT)
    else:
        look = {WALK_L: -1, WALK_R: 1}.get(f, 0)
        c.fill(line((24, 16), (35, 16)), GLOW)
        c.fill(rect(27 + look, 15, 29 + look, 17), GLOW)
        c.fill(line((27 + look, 16), (29 + look, 16)), HOT)

    # Collection-times plate under the slot (the mech's "mouth").
    c.piece(rect(25, 19, 32, 21), PAPER_RAMP)
    c.fill(line((26, 20), (28, 20)), SHADOW)
    c.fill(line((30, 20), (31, 20)), CORAL_D)
    # Side rivets on the turned-away face.
    c.dot(41, 16, CORAL_L)
    c.dot(41, 19, CORAL_L)
    if f == CHEER:
        sparkle(c, 19, 7)
        sparkle(c, 44, 15)


# --------------------------------------------------------------------------
# HEAD 2: "Route cap" - flat courier cap, brim, twin slot eyes, pennant mast
# --------------------------------------------------------------------------


def head_2(c: Canvas, f: int) -> None:
    c.piece(rect(28, 20, 36, 26), NAVY_RAMP)
    c.fill(line((29, 24), (35, 24)), NAVY_D)

    # Pennant mast on the near side, drawn behind the cap.
    c.piece(line((20, 12), (20, 4)), STEEL_RAMP, shade="flat")
    c.dot(20, 3, GLOW if f != SLEEP else SHADOW)
    flags = {
        IDLE: [(21, 4), (26, 5), (21, 7)],
        DETAIL: [(21, 4), (25, 6), (21, 7)],
        WALK_L: [(21, 4), (27, 6), (21, 7)],
        PASS_A: [(21, 4), (26, 4), (21, 7)],
        WALK_R: [(21, 4), (27, 3), (21, 7)],
        PASS_B: [(21, 4), (26, 6), (21, 7)],
        SLEEP: [(21, 5), (22, 11), (21, 11)],
        CHEER: [(21, 3), (28, 3), (21, 6)],
    }[f]
    c.piece(poly(flags), CORAL_RAMP)

    # Visor face block; far side darker for the three-quarter turn.
    face = rrect(22, 12, 42, 23, 2)
    c.piece(face, NAVY_RAMP)
    c.fill(rect(40, 14, 41, 22), NAVY_D)
    c.fill(line((39, 14), (39, 22)), NAVY_L)

    # Cap crown and forward brim.
    crown = (ellipse(31, 13, 10.6, 6.4) & rect(0, 0, 63, 12)) | rect(21, 10, 41, 13)
    c.piece(crown, CORAL_RAMP, shade="cyl", band=(23, 26))
    c.fill(line((35, 9), (35, 12)), CORAL_D)
    c.dot(31, 8, CORAL_L)
    c.piece(poly([(17, 14), (19, 12), (38, 12), (39, 14), (37, 15), (19, 15)]), NAVY_RAMP)
    c.fill(line((20, 13), (36, 13)), NAVY_L)
    # Postage-stamp badge on the crown
    c.piece(rect(27, 9, 31, 11), PAPER_RAMP, shade="flat")
    c.fill(rect(28, 10, 30, 10), CORAL)

    # Twin mail-slot eyes, near eye wider than the far one.
    eyes = [(24, 28), (32, 35)]
    for x0, x1 in eyes:
        c.fill(rect(x0, 17, x1, 19), OUT)
        if f == DETAIL:
            c.fill(line((x0 + 1, 19), (x1 - 1, 19)), GLOW)
        elif f == SLEEP:
            c.fill(line((x0, 17), (x1, 17)), NAVY_D)
            c.fill(line((x0 + 1, 18), (x1 - 1, 18)), NAVY_L)
        elif f == CHEER:
            c.fill(rect(x0, 17, x1, 19), GLOW)
            c.fill({(x0, 19), (x1, 19)}, OUT)
            c.fill(line((x0 + 1, 17), (x1 - 1, 17)), HOT)
        else:
            look = {WALK_L: -1, WALK_R: 1}.get(f, 0)
            c.fill(line((x0, 18), (x1, 18)), GLOW)
            c.dot(x0 + 1 + look + (1 if look < 0 else 0), 18, HOT)
            c.dot(x0 + 2 + look, 18, HOT)
    # Speaker grille mouth
    for x in range(27, 34, 2):
        c.dot(x, 21, SHADOW)
    # Radio ear-cup on the turned-away side
    c.piece(circle(42, 18.5, 2.4), STEEL_RAMP)
    c.dot(42, 18, CORAL if f != SLEEP else SHADOW)
    if f == CHEER:
        sparkle(c, 44, 8)


# --------------------------------------------------------------------------
# BODY 1: "Satchel drum" - rounded cargo drum with a sorting hatch
# --------------------------------------------------------------------------


def body_1(c: Canvas, f: int) -> None:
    for cx in (20, 44):
        c.piece(circle(cx, 29, 3), STEEL_RAMP)
    c.piece(rrect(24, 38, 40, 44, 2), STEEL_RAMP)
    for hx in (26, 38):
        c.dot(hx, 42, HI)
    c.piece(rect(27, 35, 37, 40), NAVY_RAMP)
    c.fill(line((28, 38), (36, 38)), NAVY_D)

    torso = rrect(21, 25, 43, 37, 4)
    c.piece(torso, CORAL_RAMP, shade="cyl", band=(22, 24))
    # Navy hoop band around the lower drum
    hoop = torso & rect(0, 34, 63, 35)
    c.fill(hoop, NAVY)
    c.fill(hoop & rect(0, 34, 63, 34), NAVY_L)
    c.fill({(x, y) for x, y in hoop if x >= 40}, NAVY_D)
    c.piece(rrect(27, 24, 37, 26, 1), NAVY_RAMP)

    # Cargo hatch with slot; latch tag swings on walk.
    c.piece(rrect(25, 28, 35, 33, 1), NAVY_RAMP)
    c.fill(line((27, 30), (33, 30)), OUT)
    c.fill(line((27, 31), (33, 31)), NAVY_L)
    tag = {WALK_L: (29, 34), WALK_R: (31, 34), SLEEP: (30, 35)}.get(f, (30, 34))
    c.fill({tag, (tag[0], tag[1] + 1)}, CORAL_L)
    c.dot(30, 33, STEEL)

    # Postage stamp sticker on the turned-away face.
    c.piece(rect(38, 28, 41, 31), PAPER_RAMP, shade="flat")
    c.fill(rect(39, 29, 40, 30), CORAL)

    # Status lamp on the drum lid.
    lamp = {DETAIL: HOT, SLEEP: SHADOW, CHEER: HOT}.get(f, GLOW)
    c.fill({(23, 28), (23, 29)}, lamp)

    if f == CHEER:  # a letter pops out of the slot
        c.piece(rect(27, 26, 33, 30), PAPER_RAMP)
        c.fill({(27, 26), (28, 27), (29, 28), (30, 28), (31, 28), (32, 27), (33, 26)}, STEEL)
        c.dot(30, 29, CORAL)
        c.fill(line((27, 31), (33, 31)), OUT)


# --------------------------------------------------------------------------
# BODY 2: "Locker vest" - V-shaped parcel-locker chest with pauldrons
# --------------------------------------------------------------------------


def body_2(c: Canvas, f: int) -> None:
    for cx in (20, 44):
        c.piece(circle(cx, 29, 2.6), STEEL_RAMP)
    c.piece(rrect(25, 39, 39, 44, 2), NAVY_RAMP)
    for hx in (26, 38):
        c.piece(circle(hx, 43, 1.6), STEEL_RAMP)
    c.piece(rect(28, 35, 36, 40), STEEL_RAMP)
    for y in (36, 38):
        c.fill(line((29, y), (35, y)), SHADOW)

    torso = poly([(22, 25), (42, 25), (43, 27), (39, 37), (25, 37), (21, 27)])
    c.piece(torso, NAVY_RAMP)
    c.fill({(x, y) for x, y in torso if x >= 39 and (x + 1, y) in torso}, NAVY_D)
    c.piece(rrect(27, 24, 37, 26, 1), STEEL_RAMP)

    # 2x2 parcel locker doors
    lights = [(26, 27), (32, 27), (26, 31), (32, 31)]
    lit = {IDLE: {0}, DETAIL: set(), WALK_L: {0}, PASS_A: {1}, WALK_R: {3}, PASS_B: {2}, SLEEP: set(), CHEER: {0, 1, 2, 3}}[f]
    for i, (x, y) in enumerate(lights):
        if f == CHEER and i == 0:
            # door swung open: dark interior with a glowing envelope
            c.piece(rect(x, y, x + 4, y + 2), (OUT, OUT, OUT), shade="flat")
            c.fill(rect(x + 1, y, x + 3, y + 1), HI)
            c.dot(x + 2, y + 1, CORAL)
            c.piece(rect(x - 2, y, x - 2, y + 2), CORAL_RAMP, shade="flat")
            continue
        c.piece(rect(x, y, x + 4, y + 2), CORAL_RAMP)
        c.dot(x + 3, y + 1, GLOW if i in lit else CORAL_D)
    # Belt with buckle and a side pouch
    c.piece(rect(25, 36, 39, 37), CORAL_RAMP, shade="flat")
    c.piece(rect(31, 36, 33, 37), STEEL_RAMP, shade="flat")
    pouch_y = {WALK_L: 37, WALK_R: 39}.get(f, 38)
    c.piece(rrect(38, pouch_y, 41, pouch_y + 3, 1), CORAL_RAMP)

    # Coral pauldrons flare the shoulders
    for side in (0, 1):
        pts = [(18, 27), (21, 24), (26, 24), (25, 27), (21, 30), (18, 30)]
        m = poly(pts)
        c.piece(m if side == 0 else mirror(m), CORAL_RAMP)
    c.dot(20, 26, CORAL_L)
    c.dot(22, 25, HOT if f == CHEER else CORAL_L)


# --------------------------------------------------------------------------
# ARMS
# --------------------------------------------------------------------------

FORWARD, BACK, NEUTRAL = "forward", "back", "neutral"


def arm_phase(f: int, side: int) -> str:
    # Left leg forward (frame 2) swings the right arm forward and the left back.
    if f == WALK_L:
        return BACK if side == 0 else FORWARD
    if f == WALK_R:
        return FORWARD if side == 0 else BACK
    return NEUTRAL


def mirror_pt(p: tuple[float, float]) -> tuple[float, float]:
    return (mx(p[0]), p[1])


def unit(dx: float, dy: float) -> tuple[float, float]:
    n = math.hypot(dx, dy) or 1.0
    return dx / n, dy / n


def arms_1(c: Canvas, f: int) -> None:
    """Slim navy limbs, coral elbows, two-prong letter pincers."""
    poses = {
        NEUTRAL: ((15, 36), (13.5, 42), 1),
        FORWARD: ((16, 35), (16, 40), 1),
        BACK: ((14.5, 35.5), (12, 41.5), 1),
    }
    for side in (0, 1):
        if f == SLEEP:
            elbow, wrist, opening = (16, 37), (17, 43), 0
        elif f == CHEER:
            elbow, wrist, opening = (13, 23), (12, 16), 2
        else:
            elbow, wrist, opening = poses[arm_phase(f, side)]
            if f == DETAIL:
                opening = 2
        shoulder = (19, 29)
        if side == 1:
            shoulder, elbow, wrist = mirror_pt(shoulder), mirror_pt(elbow), mirror_pt(wrist)
        dx, dy = unit(wrist[0] - elbow[0], wrist[1] - elbow[1])
        px, py = -dy, dx
        # Pincer prongs (behind the cuff)
        for s in (-1, 1):
            base = (wrist[0] + px * s * 1.0, wrist[1] + py * s * 1.0)
            tipp = (wrist[0] + dx * 5 + px * s * (opening + 1), wrist[1] + dy * 5 + py * s * (opening + 1))
            c.piece(capsule(base, tipp, 0.7), STEEL_RAMP)
            hook = (tipp[0] - px * s * 1.2 + dx * 0.4, tipp[1] - py * s * 1.2 + dy * 0.4)
            c.fill(capsule(tipp, hook, 0.5), HI if f == CHEER else STEEL)
        c.piece(capsule(elbow, wrist, 1.3), NAVY_RAMP)
        c.piece(circle(wrist[0], wrist[1], 1.6), STEEL_RAMP)
        c.piece(capsule(shoulder, elbow, 1.3), NAVY_RAMP)
        c.piece(circle(elbow[0], elbow[1], 1.8), CORAL_RAMP)
        c.piece(circle(shoulder[0], shoulder[1], 3.1), CORAL_RAMP)
        c.dot(round(shoulder[0]) - 1, 28, CORAL_L)
        c.dot(round(shoulder[0]), 27, HOT if f == CHEER else CORAL_L)


def arms_2(c: Canvas, f: int) -> None:
    """Chunky coral sleeves on a piston, navy forearms, square parcel tongs."""
    poses = {
        NEUTRAL: ((14.5, 36), (13.5, 41), 1),
        FORWARD: ((15.5, 35), (16, 39.5), 1),
        BACK: ((14, 35.5), (12.5, 40.5), 1),
    }
    for side in (0, 1):
        if f == SLEEP:
            elbow, wrist, opening = (15.5, 36.5), (15.5, 41.5), 0
        elif f == CHEER:
            elbow, wrist, opening = (12.5, 23), (12.5, 16.5), 2
        else:
            elbow, wrist, opening = poses[arm_phase(f, side)]
            if f == DETAIL:
                opening = 2
        shoulder = (19, 29)
        if side == 1:
            shoulder, elbow, wrist = mirror_pt(shoulder), mirror_pt(elbow), mirror_pt(wrist)
        dx, dy = unit(wrist[0] - elbow[0], wrist[1] - elbow[1])
        px, py = -dy, dx
        # Tong jaws: flat pads either side of a palm block
        reach = opening + 1.5
        for s in (-1, 1):
            a = (wrist[0] + dx * 2 + px * s * reach, wrist[1] + dy * 2 + py * s * reach)
            b = (a[0] + dx * 4, a[1] + dy * 4)
            c.piece(capsule(a, b, 1.0), STEEL_RAMP)
        c.piece(capsule((wrist[0] - px * reach, wrist[1] - py * reach),
                        (wrist[0] + px * reach, wrist[1] + py * reach), 1.3), RUBBER_RAMP)
        # Forearm: thick navy gauntlet with a coral cuff ring
        c.piece(capsule(elbow, (wrist[0] - dx * 0.5, wrist[1] - dy * 0.5), 2.6), NAVY_RAMP)
        c.piece(capsule((wrist[0] - dx * 1.5 - px * 2, wrist[1] - dy * 1.5 - py * 2),
                        (wrist[0] - dx * 1.5 + px * 2, wrist[1] - dy * 1.5 + py * 2), 0.8), CORAL_RAMP)
        # Upper arm: exposed steel piston under a coral sleeve
        c.piece(capsule(shoulder, elbow, 1.0), STEEL_RAMP)
        sleeve_end = (shoulder[0] + (elbow[0] - shoulder[0]) * 0.6, shoulder[1] + (elbow[1] - shoulder[1]) * 0.6)
        c.piece(capsule(shoulder, sleeve_end, 2.2), CORAL_RAMP)
        c.piece(circle(elbow[0], elbow[1], 2.0), STEEL_RAMP)
        # Flared boxy shoulder cap, wider on the outside
        cap = poly([(13, 27), (15, 25), (22, 25), (23, 27), (22, 32), (14, 32)])
        if side == 1:
            cap = mirror(cap)
        c.piece(cap, NAVY_RAMP)
        sx = round(shoulder[0])
        stripe = cap & rect(0, 29, 63, 29)
        c.fill(stripe, CORAL if f != SLEEP else CORAL_D)
        c.dot(sx - 2 if side == 0 else sx + 2, 27, HOT if f == CHEER else NAVY_L)


# --------------------------------------------------------------------------
# LEGS
# --------------------------------------------------------------------------


def leg_state(f: int, side: int) -> tuple[int, int, float]:
    """Return (ankle x offset outward, lift px, knee bend outward) for one leg."""
    lifts = {
        IDLE: (0, 0), DETAIL: (0, 0),
        WALK_L: (0, 1), PASS_A: (0, 3),
        WALK_R: (1, 0), PASS_B: (3, 0),
        SLEEP: (0, 0), CHEER: (4, 0),
    }[f]
    lift = lifts[side]
    out = 0
    bend = 1.0 + lift * 0.6
    if f == SLEEP:
        bend = 4.0
    if f == CHEER and side == 0:
        out, bend = 1, 2.5
    if f == WALK_L and side == 0 or f == WALK_R and side == 1:
        out = -1  # planted forward foot tucks slightly under the hip
    return out, lift, bend


def legs_1(c: Canvas, f: int) -> None:
    """Slender legs on single-wheel roller boots with a heel brake."""
    for side in (0, 1):
        out, lift, bend = leg_state(f, side)
        hip = (26, 44)
        ax = 23 - out
        ground = 61 - lift  # sole row; its grounded outline lands on y62
        boot_y = ground - 7
        ankle = (ax, boot_y)
        knee = ((hip[0] + ankle[0]) / 2 - bend, (hip[1] + ankle[1]) / 2 + 0.5)
        parts = [
            (circle(*hip, 2.4), STEEL_RAMP),
            (capsule(hip, knee, 1.4), NAVY_RAMP),
            (circle(knee[0], knee[1], 1.7), STEEL_RAMP),
            (capsule((knee[0], knee[1] + 1), ankle, 2.0), CORAL_RAMP),
            (rrect(ax - 4, boot_y, ax + 3, boot_y + 3, 1), NAVY_RAMP),
        ]
        wheel_x = ax
        if side == 1:
            parts = [(mirror(m), r) for m, r in parts]
            wheel_x = 64 - ax
        for m, r in parts:
            c.piece(m, r)
        boot = parts[-1][0]
        bx0, bx1 = min(p[0] for p in boot), max(p[0] for p in boot)
        # Coral toe stripe and a lace hook on the near side of each boot
        c.fill(line((bx0 + 1, boot_y + 1), (bx1 - 1, boot_y + 1)), CORAL if f != SLEEP else NAVY_L)
        c.dot(bx0 + 1, boot_y + 2, HI)
        # Heel brake: tucked up normally, planted on the ground while asleep.
        heel_x = bx0 if side == 0 else bx1 - 1
        brake_y = ground - 1 if f == SLEEP else ground - 3
        c.piece(rect(heel_x, brake_y, heel_x + 1, brake_y + 1), CORAL_RAMP if f == SLEEP else STEEL_RAMP)
        rolling = f in (WALK_L, PASS_A, WALK_R, PASS_B)
        c.piece(circle(wheel_x, ground - 2, 2.5), STEEL_RAMP)
        spokes = [(0, -1), (1, 0), (0, 1), (-1, 0)]
        sx, sy = spokes[(f + side * 2) % 4] if rolling else spokes[0]
        c.dot(wheel_x, ground - 2, OUT)
        c.dot(wheel_x + sx, ground - 2 + sy, HI)


def legs_2(c: Canvas, f: int) -> None:
    """Short stocky legs on mini tread units with coral fenders."""
    for side in (0, 1):
        out, lift, bend = leg_state(f, side)
        if f == CHEER:
            lift = 2 if side == 0 else 2  # little hop on both treads
            out = 1 if side == 0 else 0
        hip = (26, 44)
        ax = 24 - out
        ground = 61 - lift  # sole row; its grounded outline lands on y62
        ankle = (ax, ground - 8)
        knee = ((hip[0] + ankle[0]) / 2 - bend * 0.8, (hip[1] + ankle[1]) / 2)
        top = ground - 6
        parts = [
            (circle(*hip, 2.6), STEEL_RAMP),
            (capsule(hip, knee, 1.9), STEEL_RAMP),
            (capsule(knee, ankle, 2.4), NAVY_RAMP),
            (rrect(round(knee[0]) - 2, round(knee[1]) - 2, round(knee[0]) + 2, round(knee[1]) + 1, 1), CORAL_RAMP),
        ]
        tread = rrect(ax - 5, top + 1, ax + 4, ground, 2)
        fender = rrect(ax - 5, top, ax + 4, top + 2, 1)
        wheels = [circle(ax - 3, ground - 2.5, 1.6), circle(ax + 1, ground - 2.5, 1.6)]
        if side == 1:
            parts = [(mirror(m), r) for m, r in parts]
            tread, fender = mirror(tread), mirror(fender)
            wheels = [mirror(w) for w in wheels]
        for m, r in parts:
            c.piece(m, r)
        c.piece(tread, RUBBER_RAMP)
        # Tread grousers scroll while walking.
        phase = 0 if f in (IDLE, SLEEP) else (f + side) % 2
        xs = sorted({p[0] for p in tread})
        for x in xs:
            if (x + phase) % 2 == 0 and (x, ground) in tread:
                c.dot(x, ground, STEEL)
        for w in wheels:
            c.piece(w, STEEL_RAMP)
            hub_x = round(sum(p[0] for p in w) / len(w))
            c.dot(hub_x, ground - 3 + (phase if f not in (IDLE, SLEEP) else 0), OUT)
        c.piece(fender, CORAL_RAMP)
        fx0 = min(p[0] for p in fender)
        lamp = {SLEEP: CORAL_D, CHEER: HOT}.get(f, GLOW)
        c.dot(fx0 + (1 if side == 0 else 8), top + 1, lamp)


# --------------------------------------------------------------------------
# Catalog and rendering
# --------------------------------------------------------------------------

PARTS = [
    ("courier_head_1", "HEAD", "Pillar slot", head_1),
    ("courier_head_2", "HEAD", "Route cap", head_2),
    ("courier_body_1", "BODY", "Satchel drum", body_1),
    ("courier_body_2", "BODY", "Locker vest", body_2),
    ("courier_arms_1", "ARMS", "Letter pincers", arms_1),
    ("courier_arms_2", "ARMS", "Parcel tongs", arms_2),
    ("courier_legs_1", "LEGS", "Roller boots", legs_1),
    ("courier_legs_2", "LEGS", "Mini treads", legs_2),
]
DRAW_ORDER = ("LEGS", "BODY", "ARMS", "HEAD")


def render_sheet(draw) -> Image.Image:
    sheet = Image.new("RGBA", (SIZE * FRAMES, SIZE), (0, 0, 0, 0))
    for f in range(FRAMES):
        c = Canvas()
        draw(c, f)
        sheet.paste(c.img, (f * SIZE, 0))
    return sheet


def assemble(sheets: dict[str, Image.Image], combo: dict[str, str], f: int) -> Image.Image:
    out = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    for slot in DRAW_ORDER:
        frame = sheets[combo[slot]].crop((f * SIZE, 0, (f + 1) * SIZE, SIZE))
        out.alpha_composite(frame)
    return out


BG = (238, 228, 210, 255)
BG_ALT = (226, 214, 194, 255)
INK = (40, 34, 30, 255)
GUIDE = (196, 120, 60, 255)


def checker(w: int, h: int, cell: int) -> Image.Image:
    img = Image.new("RGBA", (w, h), BG)
    d = ImageDraw.Draw(img)
    for y in range(0, h, cell):
        for x in range(0, w, cell):
            if (x // cell + y // cell) % 2:
                d.rectangle((x, y, x + cell - 1, y + cell - 1), fill=BG_ALT)
    return img


def board(sheets: dict[str, Image.Image], combos: list[tuple[str, dict[str, str]]]) -> Image.Image:
    scale = 3
    label_w = 150
    row_h = SIZE * scale + 18
    rows = len(PARTS) + len(combos)
    width = label_w + SIZE * scale * FRAMES + 16
    height = rows * row_h + 40
    img = Image.new("RGBA", (width, height), BG)
    d = ImageDraw.Draw(img)
    d.text((8, 8), "courier family - frames: idle, detail, walk L, pass, walk R, pass, sleep, celebrate", fill=INK)
    y = 30
    for pid, slot, name, _ in PARTS:
        d.text((8, y + 8), pid, fill=INK)
        d.text((8, y + 22), name, fill=INK)
        tile = checker(SIZE * FRAMES, SIZE, 4)
        tile.alpha_composite(sheets[pid])
        img.paste(tile.resize((SIZE * FRAMES * scale, SIZE * scale), Image.NEAREST), (label_w, y))
        y += row_h
    for label, combo in combos:
        d.text((8, y + 8), label, fill=INK)
        d.text((8, y + 22), "assembled", fill=INK)
        for f in range(FRAMES):
            tile = checker(SIZE, SIZE, 4)
            tile.alpha_composite(assemble(sheets, combo, f))
            img.paste(tile.resize((SIZE * scale, SIZE * scale), Image.NEAREST), (label_w + f * SIZE * scale, y))
        y += row_h
    # frame dividers
    for f in range(1, FRAMES):
        x = label_w + f * SIZE * scale
        d.line((x, 30, x, height - 10), fill=(200, 186, 165, 255))
    return img


def gif(sheets: dict[str, Image.Image], combos: list[tuple[str, dict[str, str]]], path: Path) -> None:
    scale = 4
    # Renderer-side bob only (parts themselves never bob): passing frames rise 1px.
    sequence = (
        [(IDLE, 0, 400), (DETAIL, 0, 160), (IDLE, 0, 400)]
        + [(fr, -1 if fr in (PASS_A, PASS_B) else 0, 130) for fr in (WALK_L, PASS_A, WALK_R, PASS_B) * 3]
        + [(SLEEP, 1, 1200)]
        + [(CHEER, -2, 220), (CHEER, 0, 160), (CHEER, -2, 220), (IDLE, 0, 300)]
    )
    frames, durations = [], []
    for fr, bob, ms in sequence:
        canvas = Image.new("RGBA", (len(combos) * (SIZE + 8) * scale, (SIZE + 8) * scale), BG)
        for i, (_, combo) in enumerate(combos):
            sprite = assemble(sheets, combo, fr)
            cell = Image.new("RGBA", (SIZE + 8, SIZE + 8), BG)
            cell.alpha_composite(sprite, (4, 4 + bob))
            canvas.paste(cell.resize(((SIZE + 8) * scale, (SIZE + 8) * scale), Image.NEAREST), (i * (SIZE + 8) * scale, 0))
        frames.append(canvas.convert("P", palette=Image.ADAPTIVE, colors=64))
        durations.append(ms)
    frames[0].save(path, save_all=True, append_images=frames[1:], duration=durations, loop=0, disposal=1)


def paint_overrides(pid):
    overrides = legacy_overrides(pid)
    if pid == "courier_legs_2":
        # The moving tread lamps use coral shadow when asleep.
        for frame in range(8):
            for side in (0, 1):
                out, lift, _ = leg_state(frame, side)
                if frame == CHEER:
                    lift, out = 2, 1 if side == 0 else 0
                ax, top = 24 - out, 55 - lift
                fender = rrect(ax - 5, top, ax + 4, top + 2, 1)
                if side:
                    fender = mirror(fender)
                x = min(point[0] for point in fender) + (1 if side == 0 else 8)
                overrides[(frame * 64 + x, top + 1)] = (0, 0)
    return overrides


def main() -> None:
    ASSETS.mkdir(parents=True, exist_ok=True)
    CACHE.mkdir(parents=True, exist_ok=True)
    sheets: dict[str, Image.Image] = {}
    catalog = []
    for pid, slot, name, draw in PARTS:
        sheet = render_sheet(draw)
        sheets[pid] = sheet
        export_part(sheet, ASSETS / f"{pid}.png", PAINT_PRIMARY, PAINT_SECONDARY,
                    original_material_compat=True, slot=slot, overrides=paint_overrides(pid))
        catalog.append({"id": pid, "slot": slot, "family": FAMILY, "name": name, "asset": f"mecha/{FAMILY}/{pid}.png"})
    (ASSETS / "catalog.json").write_text(json.dumps(catalog, indent=2) + "\n")

    def combo(h: int, b: int, a: int, lg: int) -> dict[str, str]:
        return {"HEAD": f"courier_head_{h}", "BODY": f"courier_body_{b}", "ARMS": f"courier_arms_{a}", "LEGS": f"courier_legs_{lg}"}

    combos = [
        ("variant 1", combo(1, 1, 1, 1)),
        ("variant 2", combo(2, 2, 2, 2)),
        ("mix h1 b2 a1 l2", combo(1, 2, 1, 2)),
        ("mix h2 b1 a2 l1", combo(2, 1, 2, 1)),
    ]
    board(sheets, combos).save(CACHE / "courier-board.png")
    gif(sheets, combos, CACHE / "courier-walk.gif")


if __name__ == "__main__":
    main()
