#!/usr/bin/env python3
"""Generate the yard (industrial salvage) mecha part family.

Writes eight 512x64 RGBA sprite atlases plus catalog.json into
mecha/src/main/assets/mecha/yard/, and a contact board and animated
assembled GIF into ~/.cache/sortie-mecha-lab/.

All art is authored procedurally here as hard-edged pixels: shapes are
sampled at pixel centres, quantised into short colour ramps and outlined
per piece; nothing is resampled or antialiased. Requires Pillow.

Frame order (shared contract): 0 idle, 1 blink/detail, 2 left contact,
3 passing, 4 right contact, 5 passing, 6 sleep, 7 celebrate.
"""
from __future__ import annotations

import json
import math
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

W = 64
FRAMES = 8
ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "mecha/src/main/assets/mecha"
OUT_DIR = ASSETS / "yard"
CACHE = Path.home() / ".cache/sortie-mecha-lab"

# Shared contract colours.
OUT = (0x18, 0x23, 0x2D)
SH = (0x34, 0x46, 0x50)
MID = (0x76, 0x8A, 0x91)
LT = (0xA6, 0xB5, 0xB1)
HI = (0xD7, 0xDF, 0xD5)
STEEL = [SH, MID, LT, HI]

# Yard family: weathered ochre paint over cream enamel.
OCH = [(0x7C, 0x4A, 0x1D), (0xC2, 0x80, 0x28), (0xE4, 0xAD, 0x4C)]
OCH_HI = (0xF4, 0xD2, 0x7C)
CRM = [(0xA9, 0x95, 0x70), (0xDE, 0xCE, 0xA6), (0xF5, 0xEC, 0xD3)]
RUST = (0x5A, 0x2D, 0x1B)
GLASS = (0x21, 0x3A, 0x3D)
GLOW_D = (0x3B, 0x8C, 0x6C)
GLOW = (0x8F, 0xEE, 0xB8)
CORE = (0xEF, 0xFF, 0xE6)
BEACON = [(0x86, 0x2C, 0x1C), (0xDD, 0x5A, 0x2E), (0xFF, 0xA2, 0x62)]

LIGHT = (-0.5, -0.66, 0.56)
_ln = math.sqrt(sum(c * c for c in LIGHT))
LX, LY, LZ = (c / _ln for c in LIGHT)


def tone(ramp, i):
    """Quantise a lighting value in [-1, 1] into a dark-to-light ramp."""
    if len(ramp) == 4:
        cuts = (0.12, 0.58, 0.9)
    elif len(ramp) == 3:
        cuts = (0.18, 0.74)
    else:
        cuts = (0.3,)
    for idx, cut in enumerate(cuts):
        if i < cut:
            return ramp[idx]
    return ramp[-1]


class Piece:
    """One outlined component drawn on its own 64x64 layer."""

    def __init__(self):
        self.im = Image.new("RGBA", (W, W), (0, 0, 0, 0))
        self.p = self.im.load()

    def set(self, x, y, c):
        x, y = int(round(x)), int(round(y))
        if 0 <= x < W and 0 <= y < W and c is not None:
            self.p[x, y] = tuple(c[:3]) + (255,)

    def get(self, x, y):
        if 0 <= x < W and 0 <= y < W:
            v = self.p[x, y]
            return v[:3] if v[3] else None
        return None

    def clear(self, x, y):
        if 0 <= x < W and 0 <= y < W:
            self.p[x, y] = (0, 0, 0, 0)

    def fill(self, box, fn):
        x0, y0, x1, y1 = (int(math.floor(box[0])), int(math.floor(box[1])),
                          int(math.ceil(box[2])), int(math.ceil(box[3])))
        for y in range(max(0, y0), min(W - 1, y1) + 1):
            for x in range(max(0, x0), min(W - 1, x1) + 1):
                c = fn(x, y)
                if c is not None:
                    self.set(x, y, c)

    def recolor(self, x, y, mapping):
        c = self.get(x, y)
        if c is not None and c in mapping:
            self.set(x, y, mapping[c])

    def rect(self, x0, y0, x1, y1, c):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.set(x, y, c)

    def line(self, x0, y0, x1, y1, c):
        n = max(abs(x1 - x0), abs(y1 - y0), 1)
        for k in range(int(round(n)) + 1):
            t = k / n
            self.set(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, c)


def outlined(piece: Piece) -> Image.Image:
    """Return the piece with a one-pixel 4-neighbour dark outline."""
    im = piece.im.copy()
    src = piece.p
    dst = im.load()
    for y in range(W):
        for x in range(W):
            if src[x, y][3]:
                continue
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if 0 <= nx < W and 0 <= ny < W and src[nx, ny][3]:
                    dst[x, y] = OUT + (255,)
                    break
    return im


class Frame:
    def __init__(self):
        self.im = Image.new("RGBA", (W, W), (0, 0, 0, 0))

    def add(self, piece: Piece, outline=True):
        self.im.alpha_composite(outlined(piece) if outline else piece.im)


# ---------------------------------------------------------------- shapes

def region(points):
    m = Image.new("L", (W, W), 0)
    ImageDraw.Draw(m).polygon(points, fill=255, outline=255)
    px = m.load()
    return {(x, y) for y in range(W) for x in range(W) if px[x, y]}


def paint_bevel(piece, cells, ramp, light_edges=("t", "l"), dark_edges=("b", "r"), width=1):
    """Flat panel: lit top/left rim, shaded bottom/right rim, mid face."""
    dirs = {"t": (0, -1), "l": (-1, 0), "b": (0, 1), "r": (1, 0)}
    for (x, y) in cells:
        c = ramp[1]
        for e in dark_edges:
            dx, dy = dirs[e]
            if any((x + dx * k, y + dy * k) not in cells for k in range(1, width + 1)):
                c = ramp[0]
        for e in light_edges:
            dx, dy = dirs[e]
            if any((x + dx * k, y + dy * k) not in cells for k in range(1, width + 1)):
                c = ramp[2]
        piece.set(x, y, c)


def sphere(piece, cx, cy, rx, ry, ramp, spec=True):
    def fn(x, y):
        dx, dy = (x - cx) / rx, (y - cy) / ry
        d2 = dx * dx + dy * dy
        if d2 > 1.0:
            return None
        i = dx * LX + dy * LY + math.sqrt(1 - d2) * LZ
        if not spec and len(ramp) == 4:
            return tone(ramp[:3], i)
        return tone(ramp, i)
    piece.fill((cx - rx - 1, cy - ry - 1, cx + rx + 1, cy + ry + 1), fn)


def cyl(piece, a, b, r, ramp):
    ax, ay = a
    bx, by = b
    vx, vy = bx - ax, by - ay
    ll = vx * vx + vy * vy or 1e-6
    n = math.sqrt(ll)
    px, py = -vy / n, vx / n
    if px * LX + py * LY < 0:
        px, py = -px, -py
    k = px * LX + py * LY

    def fn(x, y):
        t = max(0.0, min(1.0, ((x - ax) * vx + (y - ay) * vy) / ll))
        qx, qy = ax + vx * t, ay + vy * t
        dx, dy = x - qx, y - qy
        if dx * dx + dy * dy > r * r:
            return None
        s = (dx * px + dy * py) / r
        s = max(-1.0, min(1.0, s))
        return tone(ramp, s * k + math.sqrt(1 - s * s) * LZ)
    piece.fill((min(ax, bx) - r - 1, min(ay, by) - r - 1, max(ax, bx) + r + 1, max(ay, by) + r + 1), fn)


def local(piece, ox, oy, ang, ext, fn):
    """Rasterise fn(u, v, k) in a rotated frame; u points along ang."""
    dx, dy = math.cos(ang), math.sin(ang)
    px, py = -dy, dx
    k = px * LX + py * LY  # light along +v

    def g(x, y):
        rx, ry = x - ox, y - oy
        return fn(rx * dx + ry * dy, rx * px + ry * py, k)
    piece.fill((ox - ext, oy - ext, ox + ext, oy + ext), g)


def band(ramp, v, r, k):
    """Cylinder tone for offset v within half-width r, k = light along +v."""
    s = max(-1.0, min(1.0, v / r))
    return tone(ramp, s * k + math.sqrt(1 - s * s) * LZ)


def scuff(piece, box, seed, count, ox=0, oy=0):
    """Scratch lines and paint chips on ochre/cream plating (stable per seed)."""
    rng = random.Random(seed)
    lighter = {OCH[1]: OCH[2], OCH[0]: OCH[1], OCH[2]: OCH_HI, CRM[1]: CRM[2], CRM[0]: CRM[1]}
    x0, y0, x1, y1 = box
    for n in range(count):
        x = rng.randint(x0, x1) + ox
        y = rng.randint(y0, y1) + oy
        kind = rng.random()
        if kind < 0.62:
            ln = rng.randint(2, 3)
            sx = rng.choice((1, -1))
            for i in range(ln):
                piece.recolor(x + i * sx, y + i, lighter)
        elif kind < 0.85:
            if piece.get(x, y) in (OCH[1], OCH[2]):
                piece.set(x, y, MID)
                piece.recolor(x, y - 1, lighter)
        else:
            if piece.get(x, y) in (OCH[1], OCH[0]):
                piece.set(x, y, RUST)
                piece.recolor(x, y + 1, {OCH[1]: OCH[0], OCH[2]: OCH[1]})


def rivet(piece, x, y, base=OCH):
    if piece.get(x, y) is None:
        return
    piece.set(x, y, base[2] if base is not STEEL else HI)
    if piece.get(x, y + 1) is not None:
        piece.set(x, y + 1, base[0])


def hazard(x, y, phase=0):
    return OCH[2] if ((x + y + phase) // 2) % 2 == 0 else OUT


# ---------------------------------------------------------------- poses

IDLE, BLINK, LCONTACT, PASS1, RCONTACT, PASS2, SLEEP, CHEER = range(8)
WALK = (LCONTACT, PASS1, RCONTACT, PASS2)
SHOULDER_L = (19, 29)
SHOULDER_R = (45, 29)
HIP_L = (26, 44)
HIP_R = (38, 44)

# (elbow, wrist) for viewer-left and viewer-right arms per frame.
ARM_POSE = {
    IDLE: (((15, 37), (14, 43)), ((49, 37), (50, 43))),
    BLINK: (((15, 37), (15, 43)), ((49, 37), (49, 43))),
    LCONTACT: (((14, 37), (12, 44)), ((49, 35), (47, 40))),
    PASS1: (((15, 37), (14, 43)), ((49, 37), (50, 43))),
    RCONTACT: (((15, 35), (17, 40)), ((50, 37), (52, 44))),
    PASS2: (((15, 37), (14, 43)), ((49, 37), (50, 43))),
    SLEEP: (((17, 38), (18, 45)), ((47, 38), (46, 45))),
    CHEER: (((12, 23), (13, 15)), ((52, 23), (51, 15))),
}

# Foot ground-contact point (centre x, sole fill row, sole tilt) per frame.
# Shared rule: planted soles fill the baseline y61, outline on y62, y63 clear.
# Treads stay level (rotated treads alias badly at 64px); pads may tilt.
TREAD_POSE = {
    IDLE: ((24, 61, 0), (40, 61, 0)),
    BLINK: ((24, 61, 0), (40, 61, 0)),
    LCONTACT: ((23, 61, 0), (41, 59, 0)),
    PASS1: ((24, 61, 0), (39, 58, 0)),
    RCONTACT: ((23, 59, 0), (41, 61, 0)),
    PASS2: ((25, 58, 0), (40, 61, 0)),
    SLEEP: ((25, 61, 0), (39, 61, 0)),
    CHEER: ((22, 61, 0), (44, 55, 0)),
}
PAD_POSE = {
    IDLE: ((24, 61, 0), (40, 61, 0)),
    BLINK: ((24, 61, 0), (40, 61, 0)),
    LCONTACT: ((23, 61, 0), (41, 59, -0.1)),
    PASS1: ((24, 61, 0), (39, 57, 0)),
    RCONTACT: ((23, 59, 0.1), (41, 61, 0)),
    PASS2: ((25, 57, 0), (40, 61, 0)),
    SLEEP: ((21, 61, 0), (43, 61, 0)),
    CHEER: ((24, 61, 0), (45, 54, -0.3)),
}


def knee_for(hip, ankle, side, rest_len, extra=0.0):
    hx, hy = hip
    ax, ay = ankle
    mx, my = (hx + ax) / 2, (hy + ay) / 2
    d = math.hypot(ax - hx, ay - hy)
    bend = min(3.5, max(0.0, rest_len - d))
    return (mx + side * (0.4 + bend * 0.8 + extra), my - bend * 0.3)


# ---------------------------------------------------------------- heads

def neck(fr):
    """Flat-bottomed steel neck post, ribbed, overlapping the body collar."""
    p = Piece()
    cols = {29: MID, 30: LT, 31: HI, 32: LT, 33: MID, 34: MID, 35: SH}
    for y in range(22, 28):
        for x, c in cols.items():
            p.set(x, y, {MID: SH, LT: MID, HI: LT, SH: OUT}[c] if y == 25 else c)
    fr.add(p)


def mono_eye(p, cx, cy, frame, look=(0, 0)):
    """Round lens in a riveted steel bezel; lids for blink/sleep."""
    lens = 3.3

    def fn(x, y):
        d = math.hypot(x - cx, y - cy)
        if d > 4.9:
            return None
        if d > 3.9:
            dx, dy = (x - cx) / 4.9, (y - cy) / 4.9
            return tone(STEEL[:3], dx * LX + dy * LY + 0.35)
        if d > lens:
            return OUT
        return GLASS
    p.fill((cx - 6, cy - 6, cx + 6, cy + 6), fn)
    ex, ey = cx + look[0], cy + look[1]
    big = frame == CHEER
    pr = 2.6 if big else 2.0
    for y in range(cy - 4, cy + 5):
        for x in range(cx - 4, cx + 5):
            if math.hypot(x - cx, y - cy) > lens:
                continue
            d = math.hypot(x - ex, y - ey)
            if d <= 1.0 or (big and d <= 1.6):
                p.set(x, y, CORE)
            elif d <= pr:
                p.set(x, y, GLOW)
            elif d <= pr + 0.9:
                p.set(x, y, GLOW_D)
    p.set(cx - 2, cy - 2, HI)
    p.set(cx - 1, cy - 3, HI)
    if frame == BLINK:
        for y in range(cy - 4, cy + 1):
            for x in range(cx - 4, cx + 5):
                if math.hypot(x - cx, y - cy) <= lens:
                    p.set(x, y, CRM[1] if y < cy - 1 else CRM[0])
        for x in range(cx - 3, cx + 4):
            p.set(x, cy + 1 if abs(x - cx) < 3 else cy, OUT)
    if frame == SLEEP:
        # lid drawn down over an unlit lens; its edge sags into a content "u"
        def edge(x):
            return cy + {0: 2, 1: 2, 2: 1}.get(abs(x - cx), 0)
        for y in range(cy - 4, cy + 5):
            for x in range(cx - 4, cx + 5):
                if math.hypot(x - cx, y - cy) > lens:
                    continue
                if y < edge(x):
                    p.set(x, y, CRM[2] if y < cy - 1 else CRM[1])
                elif y > edge(x):
                    p.set(x, y, GLASS)
        for x in range(cx - 3, cx + 4):
            p.set(x, edge(x), OUT)


def sparkle(fx: Piece, x, y, c=CORE):
    fx.set(x, y, c)
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        fx.set(x + dx, y + dy, GLOW if c == CORE else BEACON[2])


def head_1(f):
    """Lantern helm: welder's-cap box with a rotating roof beacon."""
    fr = Frame()
    neck(fr)
    for a, b, r in (((18, 17), (21, 17), 2.1), ((43, 18), (46, 18), 1.8)):
        e = Piece()
        cyl(e, a, b, r, STEEL)
        e.set(a[0], a[1], SH if a[0] < 30 else MID)
        fr.add(e)
    # beacon base + dome (behind the cap brim)
    bp = Piece()
    paint_bevel(bp, region([(28, 7), (35, 7), (35, 9), (28, 9)]), STEEL[:3])
    fr.add(bp)
    dome = Piece()
    lit = f not in (SLEEP,)
    ramp = BEACON if lit else [BEACON[0], BEACON[0], BEACON[1]]
    sphere(dome, 31.5, 6.2, 2.7, 2.4, ramp)
    if lit:
        spot = {IDLE: 30, BLINK: 31, LCONTACT: 29, PASS1: 31, RCONTACT: 33, PASS2: 31, CHEER: 31}[f]
        dome.set(spot, 5, CORE if f == CHEER else OCH_HI)
    fr.add(dome)
    # shell
    sh = Piece()
    front = region([(21, 13), (24, 10), (40, 10), (41, 11), (41, 23), (39, 25), (24, 25), (21, 22)])
    side = region([(42, 12), (44, 14), (44, 22), (42, 24)])
    paint_bevel(sh, front, OCH, light_edges=("t", "l"), dark_edges=("b",))
    for (x, y) in side:
        sh.set(x, y, OCH[1] if (x, y - 1) not in side else OCH[0])
    for x in range(41, 42):
        for y in range(11, 24):
            sh.set(x, y, OCH[0])
    for y in (15, 19):
        rivet(sh, 43, y)
    # chin vent under the lens
    for x in range(27, 36):
        for y in range(23, 25):
            sh.set(x, y, CRM[0] if y == 24 else CRM[1])
    for x in (28, 30, 32, 34):
        sh.set(x, 23, OUT)
    for (x, y) in ((22, 15), (22, 21), (40, 21), (25, 24), (37, 24)):
        rivet(sh, x, y)
    scuff(sh, (22, 14, 40, 22), 11, 8)
    # welded panel seams: a vertical plate join and a cheek weld bead
    for y in range(14, 21):
        sh.set(38, y, OCH[0])
        sh.set(39, y, OCH[2] if y % 3 == 0 else OCH[1])
    for x in range(22, 26):
        sh.set(x, 19 + (x % 2), OCH[0])
    mono_eye(sh, 31, 18, f, look={LCONTACT: (-1, 0), RCONTACT: (1, 0), CHEER: (0, -1)}.get(f, (0, 0)))
    fr.add(sh)
    # cap brim: cream peak overhanging the eye
    br = Piece()
    paint_bevel(br, region([(22, 10), (41, 10), (43, 12), (43, 13), (20, 13), (20, 12)]), CRM)
    for x in (24, 28, 32, 36, 40):
        br.set(x, 12, CRM[0])
    scuff(br, (21, 10, 42, 13), 12, 4)
    fr.add(br)
    if f == CHEER:
        fx = Piece()
        sparkle(fx, 26, 3, OCH_HI)
        sparkle(fx, 38, 2, OCH_HI)
        fx.set(31, 1, BEACON[2])
        fr.add(fx, outline=False)
    return fr.im


def head_2(f):
    """Jib cab: slant-nosed crane cab, scanning slit eye and dangling hook."""
    fr = Frame()
    neck(fr)
    # counterweight slab hung off the left cheek
    cw = Piece()
    cells = region([(18, 15), (23, 15), (23, 22), (18, 22)])
    for (x, y) in cells:
        cw.set(x, y, hazard(x, y))
    for x in range(18, 24):
        cw.set(x, 15, OCH[2] if x < 22 else OCH[1])
        cw.set(x, 22, OCH[0])
    fr.add(cw)
    # cab body: cream canopy over ochre lower hull
    cab = Piece()
    front = region([(23, 16), (28, 10), (39, 10), (39, 25), (24, 25), (23, 24)])
    side = region([(40, 11), (42, 13), (42, 23), (40, 25)])
    paint_bevel(cab, front, OCH, light_edges=("l",), dark_edges=("b",))
    for (x, y) in front:
        if y <= 13:
            cab.set(x, y, CRM[2] if (x, y - 1) not in front or (x - 1, y) not in front else CRM[1])
    for (x, y) in side:
        cab.set(x, y, CRM[0] if y <= 13 else OCH[0])
    for y in range(10, 26):
        if (39, y) in front:
            cab.set(39, y, CRM[0] if y <= 13 else OCH[0])
    for x in range(24, 40):
        cab.recolor(x, 14, {OCH[1]: OCH[0], OCH[2]: OCH[0]})
    # slit visor
    for y in range(15, 20):
        for x in range(25, 39):
            edge = y in (15, 19) or x in (25, 38)
            cab.set(x, y, OUT if edge else GLASS)
    look = {IDLE: 31, BLINK: 31, LCONTACT: 28, PASS1: 30, RCONTACT: 34, PASS2: 32, SLEEP: 31, CHEER: 31}[f]
    for y in range(16, 19):
        for x in range(26, 38):
            d = math.hypot((x - look) * 0.8, y - 17)
            if d <= 0.8:
                cab.set(x, y, CORE)
            elif d <= 1.9:
                cab.set(x, y, GLOW)
            elif d <= 2.8 or (f == CHEER and d <= 4):
                cab.set(x, y, GLOW_D if f != CHEER else GLOW)
    cab.set(26, 16, HI)
    cab.set(27, 16, LT)
    if f in (BLINK, SLEEP):
        stop = 17 if f == BLINK else 18
        for y in range(16, stop + 1):
            for x in range(26, 38):
                cab.set(x, y, CRM[1] if y < stop else CRM[0])
        if f == SLEEP:
            for x in range(27, 37):
                cab.set(x, 18, OUT if x not in (27, 36) else CRM[0])
    # mouth grille + rivets
    for x in range(29, 35):
        cab.set(x, 21, OUT if x % 2 else SH)
        cab.set(x, 22, OCH[0])
    for (x, y) in ((24, 18), (24, 23), (37, 23), (41, 15), (41, 21)):
        rivet(cab, x, y)
    scuff(cab, (24, 19, 39, 25), 21, 9)
    scuff(cab, (24, 10, 39, 13), 22, 3)
    fr.add(cab)
    # marker lamp on the roof
    lamp = Piece()
    lamp.rect(29, 8, 31, 9, BEACON[2] if f != SLEEP else BEACON[0])
    lamp.set(29, 8, OCH_HI if f != SLEEP else BEACON[1])
    lamp.rect(28, 9, 32, 9, MID)
    fr.add(lamp)
    # lattice jib on a pivot
    ang = math.radians({SLEEP: -8, CHEER: -62}.get(f, -36 + (2 if f in (PASS1, PASS2) else 0)))
    pvx, pvy = 36, 11
    length = 10.5

    def jib(u, v, k):
        if u < -1.5 or u > length:
            return None
        half = 1.9 - 0.06 * max(0.0, u)
        if abs(v) > half:
            return None
        if abs(v) > half - 0.85:
            return OCH[2] if v * k > 0 else OCH[0]
        if u < 1.5:
            return OCH[1]
        brace = (u - (1 if v > 0 else 0) * 1.0) % 2.4
        return OCH[1] if brace < 0.9 else None
    jp = Piece()
    local(jp, pvx, pvy, ang, 12, jib)
    tipx, tipy = pvx + math.cos(ang) * length, pvy + math.sin(ang) * length
    sphere(jp, pvx, pvy, 1.6, 1.6, STEEL[:3])
    sphere(jp, tipx, tipy, 1.3, 1.3, STEEL[:3])
    fr.add(jp)
    # cable + hook
    swing = math.radians({LCONTACT: 22, RCONTACT: -22, PASS1: 6, PASS2: -6, CHEER: 34, SLEEP: 0}.get(f, 0))
    cable = 4 if f != SLEEP else 2
    hx = tipx + math.sin(swing) * cable
    hy = tipy + 1.5 + math.cos(swing) * cable
    cb = Piece()
    cb.line(tipx, tipy + 1.5, hx, hy, OUT)
    fr.add(cb, outline=False)
    blk = Piece()
    hxi, hyi = int(round(hx)), int(round(hy))
    blk.rect(hxi - 1, hyi, hxi + 1, hyi + 1, MID)
    blk.set(hxi - 1, hyi, LT)
    blk.set(hxi + 1, hyi + 1, SH)
    fr.add(blk)
    hook = Piece()
    for (x, y) in ((0, 3), (0, 4), (-1, 5), (-2, 4)):
        hook.set(hxi + x, hyi + y, OUT)
    hook.set(hxi + 1, hyi + 4, OUT)
    fr.add(hook, outline=False)
    if f == CHEER:
        fx = Piece()
        sparkle(fx, 21, 9)
        sparkle(fx, 47, 9)
        fr.add(fx, outline=False)
    return fr.im


# ---------------------------------------------------------------- bodies

def body_1(f):
    """Boiler drum: riveted barrel chest with a pressure gauge belly."""
    fr = Frame()
    skirt = Piece()
    paint_bevel(skirt, region([(23, 40), (41, 40), (40, 44), (24, 44)]), STEEL[:3])
    for x in range(25, 40, 3):
        skirt.set(x, 42, SH)
    fr.add(skirt)
    drum = Piece()
    cx, cy, rx, ry = 31.5, 33.5, 11.6, 9.4

    def fn(x, y):
        dx, dy = (x - cx) / rx, (y - cy) / ry
        if abs(dx) ** 2.6 + abs(dy) ** 2.6 > 1:
            return None
        hoop = y in (27, 28, 39, 40)
        ramp = STEEL[:3] if hoop else OCH
        s = max(-1.0, min(1.0, (x - cx + 1.5) / rx))
        i = s * LX * 1.4 + math.sqrt(max(0.0, 1 - s * s)) * LZ + (-0.2 if y > 37 and not hoop else 0)
        if y <= 25:
            i += 0.4
        return tone(ramp, i)
    drum.fill((19, 23, 45, 45), fn)
    for y in (27, 39):
        for x in range(20, 44):
            if drum.get(x, y) and drum.get(x, y - 1) and drum.get(x, y - 1) not in STEEL:
                pass
    for x in range(22, 43, 3):
        rivet(drum, x, 28, STEEL)
        rivet(drum, x + 1, 40, STEEL)
    # rust weeping from the lower hoop rivets
    for x in (25, 34):
        drum.recolor(x, 41, {OCH[1]: RUST, OCH[0]: RUST, OCH[2]: OCH[0]})
        drum.recolor(x, 42, {OCH[1]: OCH[0], OCH[0]: RUST})
    scuff(drum, (21, 29, 42, 38), 31, 16)
    # neck collar socket
    for x in range(27, 38):
        drum.set(x, 25, SH if 28 <= x <= 36 else MID)
    # gauge
    gx, gy = 30, 33
    for y in range(gy - 5, gy + 6):
        for x in range(gx - 5, gx + 6):
            d = math.hypot(x - gx, y - gy)
            if d <= 3.9:
                drum.set(x, y, CRM[2] if (x - gx) + (y - gy) < 1 else CRM[1])
            elif d <= 4.6:
                drum.set(x, y, OUT)
            elif d <= 5.4:
                drum.set(x, y, LT if (x - gx) + (y - gy) < 0 else MID)
    for a in (-120, -60, 0, 60):
        t = math.radians(a - 90)
        drum.set(gx + round(math.cos(t) * 3), gy + round(math.sin(t) * 3), CRM[0])
    for a in (90, 110):
        t = math.radians(a - 90)
        drum.set(gx + round(math.cos(t) * 3), gy + round(math.sin(t) * 3), BEACON[1])
    needle = {IDLE: -40, BLINK: -28, LCONTACT: -10, PASS1: 6, RCONTACT: -6, PASS2: 10,
              SLEEP: -125, CHEER: 100}[f]
    t = math.radians(needle - 90)
    for r in (1, 2, 3):
        drum.set(gx + round(math.cos(t) * r), gy + round(math.sin(t) * r),
                 BEACON[0] if f == CHEER else OUT)
    drum.set(gx, gy, OUT)
    # pilot lamp and valve wheel
    lamp = {SLEEP: GLOW_D, BLINK: GLOW_D}.get(f, CORE if f == CHEER else GLOW)
    drum.set(24, 30, lamp)
    drum.set(25, 30, lamp)
    drum.set(24, 31, GLOW_D)
    drum.set(25, 31, GLOW_D)
    fr.add(drum)
    valve = Piece()
    vx, vy = 39, 33
    spin = f * 0.45 if f != SLEEP else 0
    for y in range(vy - 3, vy + 4):
        for x in range(vx - 3, vx + 4):
            d = math.hypot(x - vx, y - vy)
            if 1.8 <= d <= 2.8:
                valve.set(x, y, BEACON[1] if (x - vx) + (y - vy) < 0 else BEACON[0])
    for k in range(2):
        a = spin + k * math.pi / 2
        for r in (-2, -1, 1, 2):
            valve.set(vx + round(math.cos(a) * r), vy + round(math.sin(a) * r), BEACON[0])
    valve.set(vx, vy, MID)
    fr.add(valve)
    return fr.im


def body_2(f):
    """Charge crate: tapered chest, lift bar, radiator slats and charge-cell gauge."""
    fr = Frame()
    hip = Piece()
    cells = region([(25, 38), (39, 38), (39, 44), (25, 44)])
    paint_bevel(hip, cells, STEEL[:3])
    for y in range(40, 44):
        for x in range(26, 39, 2):
            hip.set(x, y, SH)
    fr.add(hip)
    chest = Piece()
    front = region([(20, 25), (40, 25), (38, 37), (25, 38), (23, 38)])
    side = region([(41, 26), (44, 28), (42, 36), (39, 37)])
    paint_bevel(chest, front, OCH, dark_edges=("b",))
    for (x, y) in side:
        chest.set(x, y, OCH[0] if (x, y - 1) in side else OCH[1])
    for (x, y) in front:
        if x == 40 or (x + 1, y) not in front and x > 32:
            chest.set(x, y, OCH[0])
    # side vent fan
    fan = [(42, 30), (42, 31), (42, 32), (42, 33), (41, 31), (41, 32)]
    for i, (x, y) in enumerate(fan):
        chest.set(x, y, SH if (i + f) % 2 else MID)
    scuff(chest, (21, 26, 40, 37), 41, 15)
    for (x, y) in ((22, 27), (38, 27), (25, 36), (37, 35)):
        rivet(chest, x, y)
    # radiator slats on the right of the chest
    for y in (28, 30, 32, 34):
        for x in range(32, 39):
            if chest.get(x, y) is not None:
                chest.set(x, y, SH if x < 38 else OUT)
                chest.recolor(x, y + 1, {OCH[1]: OCH[2]})
    fr.add(chest)
    # lift bar across the shoulders
    cage = Piece()
    cyl(cage, (22, 25), (40, 25), 1.1, STEEL[:3])
    fr.add(cage)
    # cream gauge window with three stacked charge cells, filling bottom-up
    win = Piece()
    paint_bevel(win, region([(24, 26), (30, 26), (30, 36), (24, 36)]), CRM)
    lit = {IDLE: 2, BLINK: 3, LCONTACT: 1, PASS1: 2, RCONTACT: 3, PASS2: 2, SLEEP: 1, CHEER: 3}[f]
    for i, (y0, y1) in enumerate(((34, 35), (31, 32), (28, 29))):
        for y in (y0, y1):
            for x in range(25, 30):
                if i < lit:
                    c = CORE if y == y0 else GLOW
                    if f == SLEEP:
                        c = GLOW if y == y0 else GLOW_D
                    elif f == CHEER:
                        c = CORE
                else:
                    c = GLASS if y == y1 else SH
                win.set(x, y, c)
    for y in (27, 30, 33):
        for x in range(25, 30):
            win.set(x, y, CRM[0] if y != 27 else CRM[1])
    fr.add(win)
    # hazard belt
    belt = Piece()
    for (x, y) in region([(23, 37), (41, 37), (40, 40), (24, 40)]):
        belt.set(x, y, hazard(x, y, 0))
    fr.add(belt)
    return fr.im


# ---------------------------------------------------------------- arms

def arm_angle(e, h):
    return math.atan2(h[1] - e[1], h[0] - e[0])


def claw_tool(open_amt):
    def fn(u, v, k):
        if -1.0 <= u <= 3.0 and abs(v) <= 3.9:
            if abs(v) > 3.0:
                return OCH[2] if v * k > 0 else OCH[0]
            if 1.0 <= u <= 1.8:
                return SH
            return band(STEEL[:3], v, 3.9, k)
        if 2.5 < u <= 10.0:
            c = open_amt + 2.0 - 0.1 * (u - 6.0) ** 2
            if abs(abs(v) - c) <= 1.05:
                if u > 8.8:
                    return OCH[2] if v * k > 0 else OCH[1]
                return LT if v * k > 0 else (MID if abs(v) > c else SH)
        return None
    return fn


def arms_1(f):
    """Vise claws: piston arms with ochre forearms and steel pincers."""
    fr = Frame()
    open_amt = {BLINK: 0.4, SLEEP: 0.2, CHEER: 2.4, LCONTACT: 1.0, RCONTACT: 1.0}.get(f, 0.8)
    for side, sh, (el, wr) in ((-1, SHOULDER_L, ARM_POSE[f][0]), (1, SHOULDER_R, ARM_POSE[f][1])):
        up = Piece()
        cyl(up, sh, el, 1.9, STEEL)
        fr.add(up)
        tool = Piece()
        local(tool, wr[0], wr[1], arm_angle(el, wr), 12, claw_tool(open_amt))
        fr.add(tool)
        fa = Piece()
        cyl(fa, el, wr, 3.4, OCH)
        ang = arm_angle(el, wr)
        # cream cuff near the wrist and scrape marks
        for t in (0.7, 0.8):
            cx = el[0] + (wr[0] - el[0]) * t
            cy = el[1] + (wr[1] - el[1]) * t
            for dv in (-2, -1, 0, 1, 2):
                x = cx - math.sin(ang) * dv
                y = cy + math.cos(ang) * dv
                cur = fa.get(int(round(x)), int(round(y)))
                if cur is not None:
                    fa.set(x, y, CRM[1] if cur != OCH[0] else CRM[0])
        mx, my = (el[0] + wr[0]) / 2, (el[1] + wr[1]) / 2
        fa.recolor(int(mx) - 1, int(my) - 1, {OCH[1]: OCH[2], OCH[2]: OCH_HI})
        fa.recolor(int(mx), int(my), {OCH[1]: OCH[2], OCH[2]: OCH_HI})
        fa.recolor(int(mx) + side, int(my) - 2, {OCH[1]: MID})
        fr.add(fa)
        elb = Piece()
        sphere(elb, el[0], el[1], 2.6, 2.6, STEEL)
        fr.add(elb)
        pad = Piece()
        px = sh[0] + side * 1
        sphere(pad, px, sh[1] - 1, 4.8, 4.2, OCH)
        for x in range(int(px) - 4, int(px) + 5):
            pad.recolor(x, sh[1] + 1, {OCH[1]: CRM[1], OCH[0]: CRM[0], OCH[2]: CRM[2]})
        rivet(pad, int(px) - side * 2, sh[1] - 3)
        fr.add(pad)
    return fr.im


def wrench_tool(tilt=0.0):
    def fn(u, v, k):
        if -0.5 <= u <= 3.0 and abs(v) <= 1.7:
            return band(STEEL[:3], v, 1.7, k)
        du, dv = u - 6.8, v + tilt
        d = math.hypot(du, dv)
        if d <= 4.9:
            if du > -0.6 and abs(dv) < 1.6:
                return None
            if d > 4.0:
                return HI if v * k > 1.0 else (MID if d > 4.4 else LT)
            return band(STEEL, v, 4.9, k)
        return None
    return fn


def drill_tool(phase):
    def fn(u, v, k):
        if -1.0 <= u <= 4.0 and abs(v) <= 3.8:
            if 1.0 <= u <= 2.0:
                return hazard(int(u * 2), int(v * 2) + 9)
            return band(OCH, v, 3.8, k)
        if 4.0 < u <= 5.6 and abs(v) <= 2.5:
            return band(STEEL[:3], v, 2.5, k)
        if 5.6 < u <= 14.0:
            half = 2.3 * (14.0 - u) / 8.4 + 0.3
            if abs(v) <= half:
                spiral = (u * 1.1 + v * 1.4 + phase) % 2.6
                if spiral < 0.95:
                    return SH
                return band(STEEL, v, max(half, 0.8), k)
        return None
    return fn


def arms_2(f):
    """Spanner & auger: hose-wrapped arms, open-end wrench and spiral drill."""
    fr = Frame()
    (el_l, wr_l), (el_r, wr_r) = ARM_POSE[f]
    for side, sh, el, wr in ((-1, SHOULDER_L, el_l, wr_l), (1, SHOULDER_R, el_r, wr_r)):
        up = Piece()
        cyl(up, sh, el, 1.6, OCH)
        fr.add(up)
        hose = Piece()
        mx = (sh[0] + el[0]) / 2 + side * 2.2
        my = (sh[1] + el[1]) / 2
        hose.line(sh[0] + side, sh[1] + 1, mx, my, CRM[1])
        hose.line(mx, my, el[0] + side, el[1], CRM[1])
        hose.set(mx, my, CRM[2])
        fr.add(hose)
    # wrench arm (viewer left)
    ang = arm_angle(el_l, wr_l)
    tool = Piece()
    local(tool, wr_l[0], wr_l[1], ang, 12, wrench_tool({BLINK: 0.6, SLEEP: -0.5}.get(f, 0.0)))
    fr.add(tool)
    fa = Piece()
    cyl(fa, el_l, wr_l, 1.6, STEEL[:3])
    cyl(fa, el_l, (el_l[0] + (wr_l[0] - el_l[0]) * 0.6, el_l[1] + (wr_l[1] - el_l[1]) * 0.6), 2.8, OCH)
    fr.add(fa)
    # drill arm (viewer right)
    phase = {SLEEP: 0.0}.get(f, f * 0.9)
    ang = arm_angle(el_r, wr_r)
    tool = Piece()
    local(tool, wr_r[0], wr_r[1], ang, 16, drill_tool(phase))
    fr.add(tool)
    fa = Piece()
    cyl(fa, el_r, wr_r, 2.7, OCH)
    fr.add(fa)
    for el in (el_l, el_r):
        e = Piece()
        sphere(e, el[0], el[1], 2.3, 2.3, STEEL)
        fr.add(e)
    for side, sh in ((-1, SHOULDER_L), (1, SHOULDER_R)):
        b = Piece()
        cx = sh[0] + side * 1
        for y in range(sh[1] - 5, sh[1] + 5):
            for x in range(cx - 5, cx + 5):
                d = math.hypot(x - cx, y - (sh[1] - 0.5))
                if d <= 1.5:
                    b.set(x, y, OCH[2] if x <= cx else OCH[1])
                elif d <= 2.3:
                    b.set(x, y, OCH[0])
                elif d <= 4.2:
                    dx, dy = (x - cx) / 4.2, (y - sh[1]) / 4.2
                    b.set(x, y, tone(STEEL, dx * LX + dy * LY + math.sqrt(max(0, 1 - dx * dx - dy * dy)) * LZ))
        for a in range(0, 360, 60):
            t = math.radians(a + 15)
            b.recolor(round(cx + math.cos(t) * 3.4), round(sh[1] - 0.5 + math.sin(t) * 3.4),
                      {MID: SH, LT: MID, HI: LT})
        fr.add(b)
    return fr.im


# ---------------------------------------------------------------- legs

def tread_block(p, fx, fy, phase, lit):
    """Level mini crawler track: belt with moving teeth, three road wheels."""
    top = fy - 6
    for y in range(top, fy + 1):
        for x in range(fx - 6, fx + 7):
            dx = x - fx
            end = abs(dx) == 6
            if end and y in (top, fy):
                continue
            if y == top:
                c = MID if (x + phase) % 2 else SH
            elif y == fy:
                c = SH if (x - phase) % 2 else MID
            elif end:
                c = MID if dx < 0 and y < fy - 2 else SH
            elif abs(dx) == 5:
                c = OCH[0]
            elif y == top + 1:
                c = OCH[2]
            elif y == fy - 1:
                c = OCH[0]
            else:
                c = OCH[1]
            p.set(x, y, c)
    for wx in (fx - 3, fx, fx + 3):
        wy = fy - 3
        for y in range(wy - 1, wy + 2):
            for x in range(wx - 1, wx + 2):
                if abs(x - wx) + abs(y - wy) == 2:
                    continue
                p.set(x, y, LT if (x - wx) + (y - wy) < 0 else MID)
        p.set(wx, wy, OUT)
    lx = fx - 4 if fx < 32 else fx + 4
    p.set(lx, top + 1, {0: GLOW_D, 1: GLOW, 2: CORE}[lit])


def legs_1(f):
    """Crawler boots: stubby piston legs on mini tank treads."""
    fr = Frame()
    feet = TREAD_POSE[f]
    phase = {LCONTACT: 1, RCONTACT: 1}.get(f, 0)
    for side, hip, (fx, fy, _) in ((-1, HIP_L, feet[0]), (1, HIP_R, feet[1])):
        ankle = (fx + side * 0.5, fy - 6.5)
        knee = knee_for(hip, ankle, side, 11.0, extra=1.6 if f == SLEEP else 0.0)
        th = Piece()
        cyl(th, (hip[0], hip[1] - 1), knee, 2.1, STEEL)
        fr.add(th)
        hb = Piece()
        sphere(hb, hip[0], hip[1] - 1, 2.8, 2.8, STEEL)
        fr.add(hb)
        foot = Piece()
        lit = 0 if f == SLEEP else (2 if f == CHEER else 1)
        tread_block(foot, fx, fy, phase if side < 0 else 1 - phase, lit)
        scuff(foot, (-4, -5, 4, -2), 50 + side, 3, ox=fx, oy=fy)
        fr.add(foot)
        sn = Piece()
        cyl(sn, knee, ankle, 2.6, OCH)
        ax, ay = round(ankle[0]), round(ankle[1])
        for x in range(ax - 2, ax + 3):
            sn.recolor(x, ay - 1, {OCH[1]: CRM[1], OCH[2]: CRM[2], OCH[0]: CRM[0]})
        fr.add(sn)
        kc = Piece()
        kx, ky = round(knee[0]), round(knee[1])
        paint_bevel(kc, region([(kx - 2, ky - 2), (kx + 2, ky - 2), (kx + 2, ky), (kx + 1, ky + 1),
                                (kx - 1, ky + 1), (kx - 2, ky)]), OCH)
        kc.set(kx - 1, ky - 1, OCH_HI)
        kc.set(kx + side, ky, RUST if side > 0 else OCH[0])
        fr.add(kc)
    return fr.im


STRIPE = (1, 2, 3)


def pad_foot(p, fx, fy, tilt, sleep):
    """Flat outrigger pad, hazard-striped in screen space; sole on (fx, fy)."""
    def fn(u, v, k):
        if v > 0.4 or v < -3.6:
            return None
        half = 6.4 + v * 0.75  # wider at the sole
        if abs(u) > half:
            return None
        if v > -0.6:
            return OCH[0]
        if v < -2.6:
            return CRM[2] if u < 0 else CRM[1]
        return STRIPE if not sleep or abs(u) > 2.5 else CRM[1]
    local(p, fx, fy, tilt, 9, fn)
    for y in range(fy - 6, fy + 3):
        for x in range(fx - 9, fx + 10):
            if p.get(x, y) == STRIPE:
                p.set(x, y, hazard(x, y))


def legs_2(f):
    """Jack struts: armoured thigh plates on bare hydraulic rams and outrigger pads."""
    fr = Frame()
    feet = PAD_POSE[f]
    for side, hip, (fx, fy, tilt) in ((-1, HIP_L, feet[0]), (1, HIP_R, feet[1])):
        hx = hip[0]
        ankle = (fx, fy - 4)
        top = (hx + side * 0.5, 47)
        dx, dy = ankle[0] - top[0], ankle[1] - top[1]
        n = math.hypot(dx, dy) or 1
        hose = Piece()
        hose.line(hx + side * 4, 46, ankle[0] + side * 3, ankle[1] - 1, OCH[0])
        fr.add(hose)
        rod = Piece()
        cyl(rod, top, ankle, 1.1, STEEL)
        fr.add(rod)
        sl = Piece()
        cyl(sl, (top[0], 44), (top[0] + dx / n * 6.0, top[1] + dy / n * 6.0), 1.9, STEEL[:3])
        fr.add(sl)
        ft = Piece()
        pad_foot(ft, fx, fy, tilt, f == SLEEP)
        sphere(ft, ankle[0], ankle[1], 1.6, 1.6, STEEL)
        fr.add(ft)
        # tapered thigh plate, flared on the outer side
        sk = Piece()
        o = side
        pts = [(hx - 3, 41), (hx + 3, 41), (hx + 3 + max(o, 0) * 2, 46), (hx + 1 + max(o, 0), 49),
               (hx - 1 - max(-o, 0), 49), (hx - 3 - max(-o, 0) * 2, 46)]
        cells = region(pts)
        paint_bevel(sk, cells, OCH, dark_edges=("b", "r") if side > 0 else ("b",))
        for (x, y) in cells:
            if y == 46 and abs(x - hx) <= 3:
                sk.set(x, y, CRM[2] if x < hx else CRM[1])
            if y == 47 and abs(x - hx) <= 2:
                sk.set(x, y, CRM[0])
        rivet(sk, hx - 1, 43)
        rivet(sk, hx + 1, 43)
        scuff(sk, (hx - 3, 42, hx + 3, 45), 60 + side, 3)
        fr.add(sk)
    return fr.im


# ---------------------------------------------------------------- output

PARTS = [
    ("yard_head_1", "HEAD", "Lantern helm", head_1),
    ("yard_head_2", "HEAD", "Jib cab", head_2),
    ("yard_body_1", "BODY", "Boiler drum", body_1),
    ("yard_body_2", "BODY", "Charge crate", body_2),
    ("yard_arms_1", "ARMS", "Vise claws", arms_1),
    ("yard_arms_2", "ARMS", "Spanner & auger", arms_2),
    ("yard_legs_1", "LEGS", "Crawler boots", legs_1),
    ("yard_legs_2", "LEGS", "Jack struts", legs_2),
]


def build_sheet(fn):
    sheet = Image.new("RGBA", (W * FRAMES, W), (0, 0, 0, 0))
    for f in range(FRAMES):
        sheet.alpha_composite(fn(f), (f * W, 0))
    return sheet


def check_sheet(pid, sheet):
    assert sheet.size == (512, 64) and sheet.mode == "RGBA", pid
    hist = sheet.getchannel("A").histogram()
    assert sum(hist[1:255]) == 0, (pid, "partial alpha")
    # shared baseline: soles fill y61, outline on y62, y63 always clear
    assert sheet.crop((0, 63, sheet.width, 64)).getchannel("A").getextrema()[1] == 0, (pid, "below baseline")
    if pid.startswith("yard_legs"):
        for f in (IDLE, BLINK, SLEEP):
            row = frame_of(sheet, f).crop((0, 62, W, 63)).getchannel("A").getextrema()[1]
            assert row == 255, (pid, f, "planted sole outline not on y62")


def frame_of(sheet, f):
    return sheet.crop((f * W, 0, f * W + W, W))


def assemble(sheets, f, bob=True):
    order = ("LEGS", "BODY", "ARMS", "HEAD")
    im = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    for slot in order:
        im.alpha_composite(frame_of(sheets[slot], f))
    if bob and f in (PASS1, PASS2):
        shifted = Image.new("RGBA", (W, W), (0, 0, 0, 0))
        shifted.alpha_composite(im, (0, -1))
        im = shifted
    return im


def load_family(family):
    """Load another family's sheets by slot (variant 1) if they exist."""
    out = {}
    for slot in ("head", "body", "arms", "legs"):
        path = ASSETS / family / f"{family}_{slot}_1.png"
        if not path.exists():
            return None
        out[slot.upper()] = Image.open(path).convert("RGBA")
    return out


BG = (0xC9, 0xC1, 0xAE)
INK = OUT


def text(draw, xy, s, fill=INK):
    font = ImageFont.load_default_imagefont()
    draw.fontmode = "1"
    draw.text(xy, s, fill=fill, font=font)


def scaled(im, k):
    return im.resize((im.width * k, im.height * k), Image.NEAREST)


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    CACHE.mkdir(parents=True, exist_ok=True)
    sheets = {}
    catalog = []
    for pid, slot, name, fn in PARTS:
        sheet = build_sheet(fn)
        check_sheet(pid, sheet)
        sheet.save(OUT_DIR / f"{pid}.png", optimize=True)
        sheets[pid] = sheet
        catalog.append({"id": pid, "slot": slot, "family": "yard", "name": name,
                        "asset": f"mecha/yard/{pid}.png"})
    (OUT_DIR / "catalog.json").write_text(json.dumps(catalog, indent=2, ensure_ascii=False) + "\n")

    v1 = {s: sheets[f"yard_{s.lower()}_1"] for s in ("HEAD", "BODY", "ARMS", "LEGS")}
    v2 = {s: sheets[f"yard_{s.lower()}_2"] for s in ("HEAD", "BODY", "ARMS", "LEGS")}
    mixed = {"HEAD": sheets["yard_head_2"], "BODY": sheets["yard_body_1"],
             "ARMS": sheets["yard_arms_2"], "LEGS": sheets["yard_legs_1"]}
    builds = [("Yard set 1", v1), ("Yard set 2", v2),
              ("Mixed: jib cab, boiler drum, spanner & auger, crawler boots", mixed)]
    for other in ("survey", "courier"):
        fam = load_family(other)
        if fam:
            cross = {"HEAD": sheets["yard_head_1"], "BODY": fam["BODY"],
                     "ARMS": sheets["yard_arms_1"], "LEGS": fam["LEGS"]}
            builds.append((f"Cross-family: yard head/arms on {other} body/legs", cross))
            cross2 = {"HEAD": fam["HEAD"], "BODY": sheets["yard_body_2"],
                      "ARMS": fam["ARMS"], "LEGS": sheets["yard_legs_2"]}
            builds.append((f"Cross-family: {other} head/arms on yard cage/jacks", cross2))

    # contact board: every sheet at 2x, then assembled sets at 3x
    labels = ["idle", "detail", "L step", "pass", "R step", "pass", "sleep", "cheer"]
    sk, ak = 2, 3
    width = max(512 * sk, 8 * 64 * ak) + 32
    row_h = 64 * sk + 18
    arow_h = 64 * ak + 22
    height = 16 + len(PARTS) * row_h + 14 + len(builds) * arow_h + 8
    board = Image.new("RGB", (width, height), BG)
    d = ImageDraw.Draw(board)
    y = 10
    for pid, slot, name, _ in PARTS:
        text(d, (16, y), f"{pid}  {slot}  {name}")
        board.paste(scaled(sheets[pid], sk), (16, y + 12), scaled(sheets[pid], sk))
        for f in range(1, FRAMES):
            d.line([(16 + f * 64 * sk, y + 12), (16 + f * 64 * sk, y + 12 + 64 * sk - 1)], fill=(0xB8, 0xB0, 0x9C))
        y += row_h
    y += 8
    for title, parts in builds:
        text(d, (16, y), title)
        for f in range(FRAMES):
            im = scaled(assemble(parts, f, bob=False), ak)
            board.paste(im, (16 + f * 64 * ak, y + 12), im)
            text(d, (16 + f * 64 * ak + 4, y + 12 + 64 * ak - 12), labels[f], fill=(0x5E, 0x58, 0x4C))
        y += arow_h
    board.save(CACHE / "yard-board.png")

    # animated assembled GIF: idle, blink, two walk cycles, cheer, sleep
    seq = [IDLE, IDLE, BLINK, IDLE] + list(WALK) * 2 + [IDLE, CHEER, CHEER, IDLE, SLEEP, SLEEP, SLEEP]
    durs = [380, 380, 120, 380] + [150] * 8 + [240, 420, 420, 240, 600, 600, 600]
    gk = 4
    shows = builds[:3]
    gif_frames = []
    for f in seq:
        canvas = Image.new("RGB", (len(shows) * 64 * gk + 16 * (len(shows) + 1), 64 * gk + 16), BG)
        for i, (_, parts) in enumerate(shows):
            im = scaled(assemble(parts, f), gk)
            canvas.paste(im, (16 + i * (64 * gk + 16), 8), im)
        gif_frames.append(canvas.convert("P", palette=Image.ADAPTIVE, colors=255, dither=Image.NONE))
    gif_frames[0].save(CACHE / "yard-assembled.gif", save_all=True, append_images=gif_frames[1:],
                       duration=durs, loop=0, disposal=1, optimize=False)
    print("wrote", OUT_DIR, CACHE / "yard-board.png", CACHE / "yard-assembled.gif")


if __name__ == "__main__":
    main()
