#!/usr/bin/env python3
"""Survey mecha family: field-science companions in teal and ivory.

Draws the eight 512x64 part sheets (eight 64x64 frames each) into
app/src/main/assets/mecha/survey/, writes catalog.json beside them, and renders
a review board plus an animated assembly GIF into ~/.cache/sortie-mecha-lab/.

Every pixel is placed by hard-edged masks; nothing is antialiased or dithered.
Frames: 0 idle, 1 idle detail, 2 left contact, 3 passing, 4 right contact,
5 passing, 6 sleep, 7 celebrate. Joints follow the shared contract: neck
(32,25), shoulders (19,29)/(45,29), hips (26,44)/(38,44), baseline y61.

Run with any Python that has Pillow: python3 tools/mecha/survey.py
"""
import json
import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/main/assets/mecha/survey"
CACHE = Path.home() / ".cache/sortie-mecha-lab"
FAMILY = "survey"


def hexc(h):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), 255)


# Shared contract colours.
OUT = hexc("18232D")
SH = hexc("344650")
MID = hexc("768A91")
HI = hexc("D7DFD5")
# Survey ramps: teal field enamel, ivory instrument housings, survey glass.
T0, T1, T2, T3 = hexc("1D4F57"), hexc("2A7C7A"), hexc("45A898"), hexc("8AD8C2")
I0, I1, I2, I3 = hexc("A69E82"), hexc("D6CCAA"), hexc("EEE6CA"), hexc("FFFAE6")
G0, G1, G2, GLINT = hexc("143243"), hexc("21627A"), hexc("5CC4D6"), hexc("F2FFFF")
EYE, EYEB = hexc("8EF2D8"), hexc("E2FFF6")
# Single warm survey-stake accent, used sparingly on tips and flags.
A0, A1, A2 = hexc("A9512F"), hexc("EE8A3F"), hexc("FFD27E")

STEEL = (SH, MID, HI)
TEAL = (T0, T1, T2)
IVORY = (I0, I1, I2)
GLASS = (G0, G1, G2)
ORANGE = (A0, A1, A2)

IDLE, DETAIL, LCONTACT, PASS1, RCONTACT, PASS2, SLEEP, CELEBRATE = range(8)


class Frame:
    """One 64x64 RGBA cel with mask-based, outline-first shading."""

    def __init__(self):
        self.img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
        self.px = self.img.load()

    def put(self, x, y, c):
        if 0 <= x < 64 and 0 <= y < 64:
            self.px[x, y] = c

    def solid(self, draw, ramp, outline=True, flat=False, deep=False):
        """Fill a mask drawn by `draw(ImageDraw)` with a lit 3-tone ramp.

        Light comes from the upper left: top/left rims take the light tone on
        the upper-left part of the shape, bottom/right rims take the dark tone.
        The 1px outline overwrites anything already drawn so later pieces read
        as separate plates.
        """
        m = Image.new("1", (64, 64), 0)
        draw(ImageDraw.Draw(m))
        mp = m.load()

        def ins(x, y):
            return 0 <= x < 64 and 0 <= y < 64 and mp[x, y]

        pts = [(x, y) for y in range(64) for x in range(64) if mp[x, y]]
        if not pts:
            return set()
        xs = [p[0] for p in pts]
        ys = [p[1] for p in pts]
        x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
        cx = x0 + (x1 - x0) * 0.62
        cy = y0 + (y1 - y0) * 0.55
        if outline:
            for y in range(64):
                for x in range(64):
                    if not ins(x, y) and (ins(x - 1, y) or ins(x + 1, y) or ins(x, y - 1) or ins(x, y + 1)):
                        self.px[x, y] = OUT
        dark, base, light = ramp[:3]
        for x, y in pts:
            c = base
            if not flat:
                if not ins(x, y + 1) or (deep and not ins(x, y + 2)):
                    c = dark
                elif not ins(x + 1, y) and y > y0 + (y1 - y0) * 0.3:
                    c = dark
                elif not ins(x, y - 1) and x < cx:
                    c = light
                elif not ins(x - 1, y) and y < cy:
                    c = light
            self.px[x, y] = c
        return set(pts)

    def line(self, pts, c):
        """1px hard line in a flat colour (details, seams, needles)."""
        for (ax, ay), (bx, by) in zip(pts, pts[1:]):
            n = max(abs(bx - ax), abs(by - ay), 1)
            for i in range(n + 1):
                self.put(round(ax + (bx - ax) * i / n), round(ay + (by - ay) * i / n), c)


# ---------------------------------------------------------------- primitives

def ell(cx, cy, rx, ry):
    return lambda d: d.ellipse([cx - rx, cy - ry, cx + rx, cy + ry], fill=1)


def rect(x0, y0, x1, y1, r=0):
    if r:
        return lambda d: d.rounded_rectangle([x0, y0, x1, y1], radius=r, fill=1)
    return lambda d: d.rectangle([x0, y0, x1, y1], fill=1)


def poly(pts):
    return lambda d: d.polygon([(round(x), round(y)) for x, y in pts], fill=1)


def capsule(a, b, w):
    r = w / 2.0

    def draw(d):
        d.line([a, b], fill=1, width=w)
        for (x, y) in (a, b):
            d.ellipse([x - r + 0.5, y - r + 0.5, x + r - 0.5, y + r - 0.5], fill=1)
    return draw


def rot_ellipse(cx, cy, rx, ry, ang, n=28):
    a = math.radians(ang)
    ca, sa = math.cos(a), math.sin(a)
    pts = []
    for i in range(n):
        t = 2 * math.pi * i / n
        x, y = rx * math.cos(t), ry * math.sin(t)
        pts.append((cx + x * ca - y * sa, cy + x * sa + y * ca))
    return pts


def unit(a, b):
    dx, dy = b[0] - a[0], b[1] - a[1]
    n = math.hypot(dx, dy) or 1.0
    return dx / n, dy / n


def mirror(p):
    return (64 - p[0], p[1])


def lens(fr, cx, cy, r, state, ring=STEEL):
    """Round survey lens. state: open, focus, blink, sleep, bright."""
    fr.solid(ell(cx, cy, r, r), ring)
    gr = r - 1
    if state in ("blink", "sleep"):
        if state == "blink":
            fr.solid(ell(cx, cy, gr, gr), (SH, MID, MID), outline=False)
            fr.line([(cx - gr + 1, cy), (cx + gr - 1, cy)], OUT)
        else:
            # Teal eyelid shutter with a contented closed-eye curve.
            fr.solid(ell(cx, cy, gr, gr), (T0, T1, T2), outline=False)
            fr.line([(cx - gr + 1, cy), (cx - gr + 2, cy + 1), (cx + gr - 2, cy + 1), (cx + gr - 1, cy)], OUT)
        return
    fr.solid(ell(cx, cy, gr, gr), GLASS, outline=False)
    if state == "focus":
        fr.solid(ell(cx, cy, max(gr - 3, 1), max(gr - 3, 1)), (G1, G1, G2), outline=False)
        fr.put(cx, cy, EYEB)
    elif gr >= 3:
        fr.solid(ell(cx, cy, gr - 2, gr - 2), (G1, G2, G2), outline=False)
    if state == "bright":
        for dx in (0, 1):
            for dy in (0, 1):
                fr.put(cx - 1 + dx, cy + dy - 1, EYEB)
        fr.put(cx, cy + 1, EYE)
        fr.put(cx + 1, cy, EYE)
    elif state == "open":
        fr.put(cx, cy, EYE)
        fr.put(cx + 1, cy, EYE if gr >= 3 else G2)
    fr.put(cx - gr + 1, cy - gr + 1 if gr > 2 else cy - 1, GLINT)


def sparkle(fr, x, y, c=EYEB):
    fr.put(x, y, c)
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        fr.put(x + dx, y + dy, A2 if c == EYEB else c)


def eye_state(f):
    return {DETAIL: "blink", SLEEP: "sleep", CELEBRATE: "bright"}.get(f, "open")


# ---------------------------------------------------------------- heads

def visor_eyes(fr, f, near, far):
    """Two glowing pill eyes inside a glass visor; near eye wider (three-quarter)."""
    st = eye_state(f)
    for (ex, w) in ((near, 3), (far, 2)):
        if st == "open":
            for x in range(ex, ex + w):
                for y in range(15, 19):
                    fr.put(x, y, EYEB if y == 15 else EYE)
            fr.put(ex + w - 1, 18, T2)
        elif st == "blink":
            fr.line([(ex, 17), (ex + w - 1, 17)], T2)
        elif st == "sleep":
            fr.line([(ex - 1, 16), (ex, 17), (ex + w - 1, 17), (ex + w, 16)], T1)
        else:
            pts = [(ex - 1, 18), (ex, 17), (ex + w // 2, 15 + (0 if w > 2 else 1)), (ex + w - 1, 17), (ex + w, 18)]
            fr.line(pts, EYEB)


def head_dish(fr, f):
    """Dish crown: ivory dome, glass visor with pill eyes, tilting parabolic dish."""
    fr.solid(rect(29, 21, 35, 27), STEEL)
    fr.put(30, 22, HI)
    # Whip antenna with a survey bead, behind the shell.
    sway = {LCONTACT: 1, RCONTACT: -1, SLEEP: 2}.get(f, 0)
    bx, by = 45 + sway, 3 + (3 if f == SLEEP else 0)
    fr.solid(capsule((41, 11), (bx, by + 1), 1), STEEL)
    fr.solid(rect(bx - 1, by - 1, bx, by), ORANGE)
    if f in (DETAIL, CELEBRATE):
        fr.put(bx - 1, by - 1, A2)
        fr.put(bx, by - 1, A2)
    # Dome shell with a side plane on the screen-left cheek.
    fr.solid(rect(19, 8, 45, 25, r=9), IVORY, deep=True)
    for y in range(13, 22):
        fr.put(22, y, I0)
    for y in (15, 17, 19):
        fr.put(20, y, OUT)
        fr.put(21, y, I0)
    fr.line([(31, 9), (37, 9)], I3)
    # Glass visor, set right of centre for the three-quarter turn.
    fr.solid(rect(24, 12, 44, 21, r=3), (G0, G0, G1))
    fr.line([(26, 13), (41, 13)], G1 if f != SLEEP else G0)
    if f in (LCONTACT, PASS1, RCONTACT, PASS2):
        gx = {LCONTACT: 33, PASS1: 35, RCONTACT: 41, PASS2: 39}[f]
        fr.put(gx, 19, G2)
        fr.put(gx + 1, 19, G1)
    visor_eyes(fr, f, 28, 38)
    # Chin grille and status pip.
    for x in (28, 30, 32, 34):
        fr.put(x, 23, I0)
    fr.put(41, 23, EYE if f != SLEEP else I0)
    # Parabolic dish on a swivel: sweeps while walking, folds to sleep, lifts to cheer.
    ang, lift = {IDLE: (-22, 0), DETAIL: (-22, 0), LCONTACT: (-34, 0), PASS1: (-20, 0),
                 RCONTACT: (-6, 0), PASS2: (-20, 0), SLEEP: (8, 3), CELEBRATE: (-38, -1)}[f]
    dcx, dcy = 24, 5 + lift
    fr.solid(capsule((27, 10), (dcx + 2, dcy + 3), 2), STEEL)
    fr.solid(poly(rot_ellipse(dcx + 1, dcy + 1.8, 7.5, 3.4, ang)), TEAL)
    fr.solid(poly(rot_ellipse(dcx, dcy, 7.5, 3.2, ang)), IVORY)
    # Concave face: shaded opposite to the convex shell.
    fr.solid(poly(rot_ellipse(dcx, dcy, 5.6, 1.9, ang)), (I2, I1, I0), outline=False)
    a = math.radians(ang)
    nx, ny = math.sin(a), -math.cos(a)
    tip = (round(dcx + nx * 4), round(dcy + ny * 4))
    if f != SLEEP:
        ca = math.cos(a)
        sa = math.sin(a)
        for r in (-4, 4):
            fr.line([(round(dcx + ca * r), round(dcy + sa * r)), tip], MID)
        fr.put(tip[0], tip[1], A2 if f in (DETAIL, CELEBRATE) else A1)
        fr.put(tip[0] + 1, tip[1], A0)
    if f == CELEBRATE:
        sparkle(fr, 13, 9)
        sparkle(fr, 50, 10)


def head_binocular(fr, f):
    """Twin-lens scope: teal housing, protruding binocular barrels, pennant mast."""
    st = eye_state(f)
    fr.solid(rect(29, 21, 35, 27), STEEL)
    fr.put(30, 22, HI)
    # Tall survey mast with a pennant that flutters while walking.
    lean = {SLEEP: 3, CELEBRATE: -1}.get(f, 0)
    top = (19 + lean, 2 + (2 if f == SLEEP else 0))
    fr.solid(capsule((23, 12), top, 1), STEEL)
    tx, ty = top
    flags = {
        IDLE: [(tx + 1, ty), (tx + 7, ty + 2), (tx + 1, ty + 4)],
        DETAIL: [(tx + 1, ty), (tx + 7, ty + 2), (tx + 1, ty + 4)],
        LCONTACT: [(tx + 1, ty), (tx + 7, ty + 1), (tx + 5, ty + 3), (tx + 1, ty + 4)],
        PASS1: [(tx + 1, ty), (tx + 6, ty + 3), (tx + 1, ty + 4)],
        RCONTACT: [(tx + 1, ty), (tx + 5, ty + 1), (tx + 7, ty + 3), (tx + 1, ty + 4)],
        PASS2: [(tx + 1, ty), (tx + 6, ty + 3), (tx + 1, ty + 4)],
        SLEEP: [(tx + 1, ty), (tx + 3, ty + 5), (tx + 1, ty + 5)],
        CELEBRATE: [(tx + 1, ty - 1), (tx + 8, ty + 1), (tx + 1, ty + 4)],
    }[f]
    fr.solid(poly(flags), ORANGE)
    # Short ball-tipped receiver on the far side.
    fr.solid(capsule((41, 12), (44, 6), 1), STEEL)
    fr.solid(ell(44, 5, 1, 1), (T1, T2, T3) if f != SLEEP else TEAL)
    if f in (DETAIL, CELEBRATE):
        fr.put(44, 5, EYEB)
    # Housing and hinge bridge.
    fr.solid(rect(20, 11, 44, 24, r=4), TEAL, deep=True)
    fr.line([(23, 12), (37, 12)], T3)
    fr.solid(rect(17, 14, 20, 20), STEEL)
    fr.put(18, 15, HI)
    fr.solid(rect(24, 8, 40, 12, r=2), IVORY)
    fr.line([(25, 9), (33, 9)], I3)
    # Focus wheel: knurls step round while walking.
    fr.solid(ell(32, 6, 3, 2), STEEL)
    for i, x in enumerate(range(30, 35)):
        if (i + f) % 2 == 0:
            fr.put(x, 7, SH)
    # Barrels: near (screen-left) larger, far smaller. The detail frame
    # racks focus (irises tighten) instead of blinking.
    bst = "focus" if f == DETAIL else st
    lens(fr, 27, 18, 6, bst, ring=IVORY)
    lens(fr, 39, 18, 5, bst, ring=IVORY)
    fr.put(24, 14, I3)
    fr.put(37, 15, I3)
    # Grille between barrels.
    fr.put(33, 22, OUT)
    fr.put(33, 20, OUT)
    if f == CELEBRATE:
        sparkle(fr, 49, 4)
        sparkle(fr, 13, 11)


# ---------------------------------------------------------------- bodies

def shoulder_nubs(fr):
    fr.solid(rect(19, 27, 22, 31, r=1), STEEL)
    fr.solid(rect(42, 27, 45, 31, r=1), STEEL)


def body_vest(fr, f):
    """Sampler vest: faceted ivory chest, teal instrument panel, vial belt."""
    shoulder_nubs(fr)
    fr.solid(rect(25, 40, 39, 45, r=2), STEEL)
    fr.solid(poly([(23, 24), (41, 24), (44, 29), (41, 41), (23, 41), (20, 29)]), IVORY, deep=True)
    # Side plane and collar.
    fr.line([(23, 27), (22, 31), (24, 39)], I0)
    fr.solid(rect(27, 23, 37, 26, r=1), STEEL)
    fr.line([(28, 24), (31, 24)], HI)
    fr.line([(24, 25), (33, 25)], I3)
    # Instrument panel with scrolling trace.
    fr.solid(rect(27, 28, 39, 36, r=1), TEAL)
    fr.solid(rect(29, 29, 37, 34), (G0, G0, G0), outline=False)
    if f == SLEEP:
        fr.line([(29, 32), (37, 32)], G1)
        fr.put(36, 30, T2)
        fr.put(35, 31, T2)
    elif f == CELEBRATE:
        # Tiny star readout.
        for x, y in ((33, 29), (32, 30), (33, 30), (34, 30), (31, 31), (32, 31), (33, 31),
                     (34, 31), (35, 31), (32, 32), (34, 32), (31, 33), (35, 33)):
            fr.put(x, y, EYEB)
    else:
        wave = [0, -1, -2, -1, 0, 1, 2, 1]
        for i in range(9):
            y = 32 + wave[(i + f * 2) % 8] // (1 if f in (LCONTACT, PASS1, RCONTACT, PASS2) else 2)
            fr.put(29 + i, y, EYE)
    fr.put(38, 35, A1 if f in (IDLE, LCONTACT, RCONTACT, CELEBRATE) else A0)
    # Sample vials clipped on the near side.
    for vx, liquid in ((22, T2), (25, A1)):
        fr.solid(rect(vx, 33, vx + 1, 38), (G1, G2, G2))
        fr.put(vx, 36, liquid)
        fr.put(vx + 1, 36, liquid)
        fr.put(vx, 37, liquid)
        fr.put(vx + 1, 37, liquid)
        fr.put(vx, 33, MID)
        fr.put(vx + 1, 33, MID)
    # Belt with buckle and a pouch on the far side.
    fr.solid(rect(23, 38, 41, 41), TEAL)
    fr.solid(rect(30, 38, 33, 41), STEEL)
    fr.put(31, 39, HI)
    fr.solid(rect(37, 36, 41, 40, r=1), IVORY)
    fr.line([(37, 37), (41, 37)], I0)


def body_drum(fr, f):
    """Sounding drum: rounded teal barrel, ivory gauge band, sweeping dial."""
    shoulder_nubs(fr)
    fr.solid(rect(26, 40, 38, 45, r=2), STEEL)
    fr.solid(ell(32, 34, 12, 10), TEAL, deep=True)
    fr.solid(rect(28, 23, 36, 26, r=1), STEEL)
    fr.put(29, 24, HI)
    # Ivory band with survey ticks wrapping round the barrel.
    fr.solid(poly([(21, 37), (43, 37), (42, 40), (22, 40)]), IVORY, outline=True)
    for x in range(23, 42, 3):
        fr.put(x, 38, I0)
    fr.put(23 + (f % 6) * 3, 39, A1)
    # Highlights on the barrel's upper-left.
    fr.line([(24, 28), (26, 27)], T3)
    fr.put(23, 30, T3)
    fr.line([(22, 32), (22, 35)], T0)
    # Dial gauge, slightly right of centre, clear of the head overlap.
    gx, gy = 34, 32
    fr.solid(ell(gx, gy, 4, 4), STEEL)
    fr.solid(ell(gx, gy, 3, 3), (I1, I2, I3), outline=False)
    fr.put(gx - 2, gy - 2, I3)
    needle = {IDLE: -120, DETAIL: -100, LCONTACT: -150, PASS1: -90, RCONTACT: -30, PASS2: -90,
              SLEEP: 150, CELEBRATE: -20}[f]
    a = math.radians(needle)
    fr.line([(gx, gy), (round(gx + math.cos(a) * 3), round(gy + math.sin(a) * 3))],
            A0 if f == SLEEP else OUT)
    fr.put(gx, gy, A1)
    # Vent slats on the far side.
    for y in (28, 30, 32):
        fr.line([(40, y), (42, y)], T0)
    # Cable reel on the near hip.
    fr.solid(ell(24, 41, 2, 2), IVORY)
    spin = [(24, 40), (25, 41), (24, 42), (23, 41)][f % 4]
    fr.put(spin[0], spin[1], OUT)
    if f == CELEBRATE:
        fr.put(30, 27, EYEB)


# ---------------------------------------------------------------- arms

SHOULDER = (19, 29)
ARM_POSES = {
    "idle": ((15, 36), (14, 43)),
    "lift": ((15, 36), (16, 42)),
    "fwd": ((16, 36), (17, 42)),
    "back": ((14, 36), (11, 43)),
    "sleep": ((17, 37), (20, 45)),
    "cheer": ((12, 22), (13, 14)),
}
ARM_FRAMES = {
    IDLE: ("idle", "idle"), DETAIL: ("lift", "idle"), LCONTACT: ("back", "fwd"),
    PASS1: ("idle", "idle"), RCONTACT: ("fwd", "back"), PASS2: ("idle", "idle"),
    SLEEP: ("sleep", "sleep"), CELEBRATE: ("cheer", "cheer"),
}


def arm_joints(side, pose):
    e, h = ARM_POSES[pose]
    s = SHOULDER
    if side > 0:
        s, e, h = mirror(s), mirror(e), mirror(h)
    return s, e, h


def tool_point(h, d, n):
    return (round(h[0] + d[0] * n), round(h[1] + d[1] * n))


def arms_probe(fr, f):
    """Probe & caliper: slim jointed arms, scanning wand and caliper grip."""
    for side, pose in ((-1, ARM_FRAMES[f][0]), (1, ARM_FRAMES[f][1])):
        s, e, h = arm_joints(side, pose)
        d = unit(e, h)
        fr.solid(capsule(e, h, 5), TEAL)
        # Ivory cuff band near the wrist.
        cw = tool_point(e, d, max(1, math.hypot(h[0] - e[0], h[1] - e[1]) - 2))
        fr.line([(round(cw[0] - d[1] * 2), round(cw[1] + d[0] * 2)),
                 (round(cw[0] + d[1] * 2), round(cw[1] - d[0] * 2))], I1)
        fr.solid(capsule(s, e, 5), IVORY)
        ud = unit(s, e)
        fr.line([tool_point(s, ud, 2), tool_point(s, ud, 5)], I3 if side < 0 else I2)
        fr.solid(rect(e[0] - 1, e[1] - 1, e[0], e[1]), STEEL, outline=False)
        fr.put(e[0] - 1, e[1] - 1, HI)
        if side < 0:
            # Scanning wand: rod with a pulsing sensor bulb.
            tip = tool_point(h, d, 7)
            fr.solid(capsule(h, tip, 1), STEEL)
            lit = f in (IDLE, LCONTACT, RCONTACT, CELEBRATE)
            fr.solid(ell(tip[0], tip[1], 1, 1), (G1, EYE, EYEB) if lit and f != SLEEP else GLASS)
            if lit and f == CELEBRATE:
                sparkle(fr, tip[0] - 2 * round(d[0] or 0), tip[1] - 3)
        else:
            # Caliper jaws open wider while celebrating.
            spread = 0.9 if f == CELEBRATE else 0.45
            for sgn in (-1, 1):
                ca, sa = math.cos(spread * sgn), math.sin(spread * sgn)
                jd = (d[0] * ca - d[1] * sa, d[0] * sa + d[1] * ca)
                fr.solid(capsule(h, tool_point(h, jd, 5), 1), STEEL)
        fr.solid(ell(h[0], h[1], 2, 2), STEEL)
        fr.put(h[0] - 1, h[1] - 1, HI)
        # Teal shoulder cap with a steel pivot bolt.
        fr.solid(ell(s[0], s[1], 3, 3), TEAL)
        fr.put(s[0] - 1, s[1] - 2, T3)
        fr.put(s[0], s[1], MID)
        fr.put(s[0] + 1, s[1] + 1, SH)


def arms_scoop(fr, f):
    """Scoop & loupe: bellows upper arms, ivory forearms, trowel and magnifier."""
    for side, pose in ((-1, ARM_FRAMES[f][0]), (1, ARM_FRAMES[f][1])):
        s, e, h = arm_joints(side, pose)
        d = unit(e, h)
        fr.solid(capsule(s, e, 5), STEEL)
        # Bellows ribs across the upper arm.
        ud = unit(s, e)
        L = math.hypot(e[0] - s[0], e[1] - s[1])
        for t in range(3, int(L) - 1, 2):
            cxp, cyp = s[0] + ud[0] * t, s[1] + ud[1] * t
            fr.line([(round(cxp - ud[1] * 1.5), round(cyp + ud[0] * 1.5)),
                     (round(cxp + ud[1] * 1.5), round(cyp - ud[0] * 1.5))], SH)
        fr.solid(capsule(e, h, 6), IVORY)
        fr.put(e[0] - 1, e[1] + 1, I3)
        fr.solid(ell(e[0], e[1], 2, 2), TEAL)
        if side < 0:
            # Trowel: broad steel spade with a tan wrap.
            p1 = tool_point(h, d, 2)
            p2 = tool_point(h, d, 8)
            nx, ny = -d[1], d[0]
            fr.solid(poly([(p1[0] + nx * 2, p1[1] + ny * 2), (p2[0] + nx * 1.5, p2[1] + ny * 1.5),
                           (p2[0] + d[0] * 2, p2[1] + d[1] * 2),
                           (p2[0] - nx * 1.5, p2[1] - ny * 1.5), (p1[0] - nx * 2, p1[1] - ny * 2)]), STEEL)
            fr.line([p1, tool_point(h, d, 6)], SH)
            fr.put(p1[0], p1[1], HI)
            if f == CELEBRATE:
                fr.put(p2[0] - 1, p2[1] - 2, A1)
                fr.put(p2[0] + 1, p2[1] - 3, A2)
        else:
            # Magnifier loupe ring with glass.
            c = tool_point(h, d, 5)
            fr.solid(capsule(h, tool_point(h, d, 2), 2), STEEL)
            fr.solid(ell(c[0], c[1], 3, 3), STEEL)
            fr.solid(ell(c[0], c[1], 2, 2), (G1, G2, GLINT) if f == CELEBRATE else GLASS, outline=False)
            fr.put(c[0] - 1, c[1] - 1, GLINT)
            if f == CELEBRATE:
                sparkle(fr, c[0] + 3, c[1] - 4)
        fr.solid(ell(h[0], h[1], 2, 2), IVORY)
        fr.put(h[0] - 1, h[1] - 1, I3)
        # Domed teal pauldron covering the shoulder joint.
        fr.solid(lambda dr, s=s: dr.chord([s[0] - 4, s[1] - 4, s[0] + 4, s[1] + 4], 160, 380, fill=1), TEAL)
        fr.put(s[0] - 2, s[1] - 3, T3)
        fr.put(s[0] - 1, s[1] - 3, T3)


# ---------------------------------------------------------------- legs

HIP = (26, 44)
# Shared grounding rule: solid sole fill rests on baseline y61, the grounded
# outline row is y62, and y63 stays clear.
BASE_FILL = 61
LEG_POSES = {
    # knee, ankle, foot lift, foot width delta
    "stand": ((25, 51), (24, 56), 0, 0),
    "fwd": ((24, 51), (23, 56), 0, 1),
    "back": ((26, 50), (26, 55), 1, -2),
    "lift": ((27, 48), (27, 52), 4, -2),
    "squat": ((20, 51), (23, 56), 0, 0),
    "brace": ((24, 51), (22, 56), 0, 0),
    "kick": ((22, 49), (18, 52), 4, -1),
}
LEG_FRAMES = {
    IDLE: ("stand", "stand"), DETAIL: ("stand", "stand"), LCONTACT: ("fwd", "back"),
    PASS1: ("stand", "lift"), RCONTACT: ("back", "fwd"), PASS2: ("lift", "stand"),
    SLEEP: ("squat", "squat"), CELEBRATE: ("brace", "kick"),
}


def leg_joints(side, pose):
    k, a, lift, dw = LEG_POSES[pose]
    hp = HIP
    if side > 0:
        hp, k, a = mirror(hp), mirror(k), mirror(a)
    return hp, k, a, lift, dw


def legs_snowshoe(fr, f):
    """Snowshoe struts: slim articulated legs with knee discs and wide pads."""
    order = (1, -1)  # far leg first so the near leg overlaps it
    for side in order:
        pose = LEG_FRAMES[f][0 if side < 0 else 1]
        hp, k, a, lift, dw = leg_joints(side, pose)
        bottom = BASE_FILL - lift
        # Rear damper rod.
        fr.solid(capsule((hp[0] + side * 2, hp[1] + 1), (a[0] + side, a[1] - 2), 1), STEEL)
        fr.solid(capsule(hp, k, 4), STEEL)
        fr.solid(capsule(k, a, 5), IVORY)
        fr.line([(k[0] - 1, k[1] + 2), (a[0] - 1, a[1] - 1)], I3 if side < 0 else I2)
        # Teal shin guard.
        fr.solid(poly([(k[0] - 2, k[1] + 2), (k[0] + 2, k[1] + 2), (a[0] + 2, a[1] - 1), (a[0] - 2, a[1] - 1)]), TEAL)
        # Wide snowshoe pad.
        hw = 6 + dw
        fx = a[0] - side * 1
        fr.solid(capsule((a[0], a[1]), (a[0], bottom - 2), 3), STEEL)
        fr.solid(rect(fx - hw, bottom - 2, fx + hw, bottom, r=1), TEAL)
        fr.line([(fx - hw + 1, bottom - 2), (fx + hw - 2, bottom - 2)], T3)
        for x in range(fx - hw + 2, fx + hw - 1, 3):
            fr.put(x, bottom, OUT if lift else T0)
        # Knee disc.
        fr.solid(ell(k[0], k[1], 2, 2), TEAL)
        fr.put(k[0], k[1], I2 if f != CELEBRATE else A2)
        fr.solid(ell(hp[0], hp[1], 2, 2), STEEL)


def legs_tritoe(fr, f):
    """Tri-toe gaiters: stout legs, flared teal gaiters, splayed claw feet."""
    order = (1, -1)
    for side in order:
        pose = LEG_FRAMES[f][0 if side < 0 else 1]
        hp, k, a, lift, dw = leg_joints(side, pose)
        bottom = BASE_FILL - lift
        fr.solid(capsule(hp, k, 6), IVORY)
        fr.put(hp[0] - 2, hp[1] + 2, I3)
        # Splayed toes: centre toe toward viewer, two outriggers.
        spread = 4 + dw // 2
        toes = [(a[0] - spread - 1, bottom), (a[0], bottom), (a[0] + spread + 1, bottom)]
        for tx, ty in toes:
            fr.solid(capsule((a[0], bottom - 3), (tx, ty - 1), 2), STEEL)
        fr.solid(rect(a[0] - 2, bottom - 3, a[0] + 2, bottom - 1, r=1), STEEL)
        for tx, ty in toes:
            fr.put(tx, ty, SH if not lift else MID)
        # Flared gaiter from knee to ankle.
        fr.solid(poly([(k[0] - 3, k[1]), (k[0] + 3, k[1]), (a[0] + 4, a[1] + 1), (a[0] - 4, a[1] + 1)]), TEAL, deep=True)
        fr.line([(k[0] - 2, k[1] + 1), (a[0] - 3, a[1])], T3 if side < 0 else T2)
        fr.line([(a[0] - 3, a[1] - 1), (a[0] + 3, a[1] - 1)], T0)
        # Knee piston cap with stake-orange pip.
        fr.solid(ell(k[0], k[1] - 1, 3, 2), STEEL)
        fr.put(k[0] - 1, k[1] - 2, HI)
        fr.put(k[0] + 1, k[1] - 1, A1 if f != SLEEP else A0)
        fr.solid(ell(hp[0], hp[1], 2, 2), STEEL)


# ---------------------------------------------------------------- catalog

PARTS = [
    ("survey_head_1", "HEAD", "Dish crown", head_dish),
    ("survey_head_2", "HEAD", "Twin-lens scope", head_binocular),
    ("survey_body_1", "BODY", "Sampler vest", body_vest),
    ("survey_body_2", "BODY", "Sounding drum", body_drum),
    ("survey_arms_1", "ARMS", "Probe and caliper", arms_probe),
    ("survey_arms_2", "ARMS", "Scoop and loupe", arms_scoop),
    ("survey_legs_1", "LEGS", "Snowshoe struts", legs_snowshoe),
    ("survey_legs_2", "LEGS", "Tri-toe gaiters", legs_tritoe),
]


def render_sheet(fn):
    sheet = Image.new("RGBA", (512, 64), (0, 0, 0, 0))
    for f in range(8):
        fr = Frame()
        fn(fr, f)
        sheet.paste(fr.img, (f * 64, 0))
    return sheet


# ---------------------------------------------------------------- previews

BG = hexc("2E3A3F")
BG2 = hexc("38464B")
INK = hexc("E9E2C8")
POSE_NAMES = ["idle", "detail", "L contact", "pass", "R contact", "pass", "sleep", "celebrate"]


def assemble(sheets, ids, f):
    """Draw order LEGS, BODY, ARMS, HEAD at shared anchors."""
    out = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    for slot in ("legs", "body", "arms", "head"):
        cel = sheets[ids[slot]].crop((f * 64, 0, f * 64 + 64, 64))
        out.alpha_composite(cel)
    return out


def font(size):
    for path in ("/usr/share/fonts/google-noto/NotoSans-Regular.ttf",
                 "/usr/share/fonts/dejavu-sans-fonts/DejaVuSans.ttf"):
        if Path(path).exists():
            return ImageFont.truetype(path, size)
    return ImageFont.load_default(size)


def board(sheets, combos):
    S = 3
    fnt, small = font(18), font(13)
    rows = len(PARTS) + len(combos)
    W = 512 * S + 220
    H = 40 + rows * (64 * S + 12) + 20
    img = Image.new("RGBA", (W, H), BG)
    d = ImageDraw.Draw(img)
    for f in range(8):
        d.text((220 + f * 64 * S + 4, 12), f"{f} {POSE_NAMES[f]}", fill=INK, font=small)
    y = 40
    entries = [(pid, name, sheets[pid]) for pid, _, name, _ in PARTS]
    entries += [(label, " + ".join(v.split("_", 1)[1] for v in ids.values()), _strip(sheets, ids))
                for label, ids in combos]
    for pid, name, strip in entries:
        for f in range(8):
            if f % 2 == 0:
                d.rectangle([220 + f * 64 * S, y, 220 + (f + 1) * 64 * S - 1, y + 64 * S - 1], fill=BG2)
        img.alpha_composite(strip.resize((512 * S, 64 * S), Image.NEAREST), (220, y))
        d.text((10, y + 8), pid, fill=INK, font=fnt)
        d.text((10, y + 32), name, fill=T3, font=small)
        y += 64 * S + 12
    return img


def _strip(sheets, ids):
    strip = Image.new("RGBA", (512, 64), (0, 0, 0, 0))
    for f in range(8):
        strip.paste(assemble(sheets, ids, f), (f * 64, 0))
    return strip


def rgb_pixels(img):
    b = img.tobytes()
    return zip(b[0::3], b[1::3], b[2::3])


def gif(sheets, combos, path):
    S = 4
    seq = []
    for _ in range(2):
        seq += [(IDLE, 0)] * 4 + [(DETAIL, 0)] * 1 + [(IDLE, 0)] * 3
    for _ in range(4):
        seq += [(LCONTACT, 0), (PASS1, -1), (RCONTACT, 0), (PASS2, -1)]
    seq += [(SLEEP, 0), (SLEEP, 0), (SLEEP, 1), (SLEEP, 1)] * 3
    seq += [(IDLE, 0)] * 2
    for _ in range(3):
        seq += [(CELEBRATE, -2), (CELEBRATE, -3), (CELEBRATE, -2), (CELEBRATE, 0)]
    frames = []
    pal_colors = {}
    W = len(combos) * 64 * S
    for f, bob in seq:
        canvas = Image.new("RGBA", (len(combos) * 64, 72), BG)
        for i, (_, ids) in enumerate(combos):
            canvas.alpha_composite(assemble(sheets, ids, f), (i * 64, 4 + bob))
        canvas = canvas.resize((W, 72 * S), Image.NEAREST).convert("RGB")
        frames.append(canvas)
        for c in rgb_pixels(canvas):
            pal_colors.setdefault(c, len(pal_colors))
    if len(pal_colors) > 256:
        raise SystemExit(f"GIF needs {len(pal_colors)} colours; keep the family palette under 256")
    palette = [v for c in sorted(pal_colors, key=pal_colors.get) for v in c]
    pframes = []
    for fr in frames:
        p = Image.new("P", fr.size)
        p.putpalette(palette)
        p.putdata([pal_colors[c] for c in rgb_pixels(fr)])
        pframes.append(p)
    pframes[0].save(path, save_all=True, append_images=pframes[1:], duration=125, loop=0, optimize=False)


def main():
    ASSETS.mkdir(parents=True, exist_ok=True)
    CACHE.mkdir(parents=True, exist_ok=True)
    sheets = {}
    catalog = []
    for pid, slot, name, fn in PARTS:
        sheet = render_sheet(fn)
        assert sheet.size == (512, 64) and sheet.mode == "RGBA"
        assert sheet.crop((0, 63, 512, 64)).getbbox() is None, f"{pid} draws on reserved row y63"
        sheet.save(ASSETS / f"{pid}.png", optimize=True)
        sheets[pid] = sheet
        catalog.append({"id": pid, "slot": slot, "family": FAMILY, "name": name,
                        "asset": f"mecha/{FAMILY}/{pid}.png"})
    (ASSETS / "catalog.json").write_text(json.dumps(catalog, indent=2) + "\n")
    combos = [
        ("set 1", {"legs": "survey_legs_1", "body": "survey_body_1", "arms": "survey_arms_1", "head": "survey_head_1"}),
        ("set 2", {"legs": "survey_legs_2", "body": "survey_body_2", "arms": "survey_arms_2", "head": "survey_head_2"}),
        ("mix A", {"legs": "survey_legs_1", "body": "survey_body_2", "arms": "survey_arms_1", "head": "survey_head_2"}),
        ("mix B", {"legs": "survey_legs_2", "body": "survey_body_1", "arms": "survey_arms_2", "head": "survey_head_1"}),
    ]
    board(sheets, combos).save(CACHE / "survey-board.png")
    gif(sheets, combos, CACHE / "survey-assembled.gif")
    print("sheets:", ASSETS)
    print("board:", CACHE / "survey-board.png")
    print("gif:", CACHE / "survey-assembled.gif")


if __name__ == "__main__":
    main()
