"""Author-time semantic paint masks; no source-colour inference is needed at runtime.

R stores fixed/primary/secondary (0/1/2), G shadow/base/light/glint (0..3),
B Original coating eligibility (0/1), and A exactly matches the source.
Unlisted colours are fixed. Ambiguous colours must be removed from both ramps
or resolved by a coordinate override, never silently assigned to the last ramp.
"""
from __future__ import annotations

from collections.abc import Mapping, Sequence
from pathlib import Path

from PIL import Image

RGBA = tuple[int, int, int, int]
MECHANICAL_RGB = {(0x18, 0x23, 0x2D), (0x34, 0x46, 0x50),
                  (0x76, 0x8A, 0x91), (0xA6, 0xB5, 0xB1), (0xD7, 0xDF, 0xD5)}


def legacy_overrides(part_id: str) -> dict[tuple[int, int], tuple[int, int]]:
    """Authored exceptions in the original drawings, not a runtime face heuristic.

    These instrument interiors reuse shell pigments for sleeping lids, lamp
    highlights or gauge faces. Keep the entire named interior fixed rather than
    assigning two meanings to the same RGB value. Coordinates are inclusive.
    Their Original coating eligibility is still the independent legacy rule.
    """
    rectangles = {
        "yard_head_1": [(28, 15, 34, 21), (25, 1, 39, 5)],
        "yard_head_2": [(26, 16, 37, 18), (29, 8, 31, 9)],
        "yard_body_1": [(27, 30, 33, 36)],
        "survey_head_1": [(25, 14, 43, 19), (41, 23, 41, 23)],
        "survey_head_2": [(23, 14, 31, 22), (36, 15, 42, 21), (43, 4, 45, 6)],
        "survey_body_2": [(31, 29, 37, 35)],
        "courier_head_1": [(23, 15, 36, 17), (36, 4, 40, 6)],
        "courier_head_2": [(24, 17, 28, 19), (32, 17, 35, 19), (42, 18, 42, 18)],
        "courier_body_2": [(29, 28, 29, 28), (35, 28, 35, 28),
                           (29, 32, 29, 32), (35, 32, 35, 32), (22, 25, 22, 25)],
        "courier_arms_1": [(19, 27, 19, 27), (45, 27, 45, 27)],
        "courier_arms_2": [(17, 27, 17, 27), (47, 27, 47, 27)],
        "foundry_body_2": [(28, 28, 34, 34)],
        "orbital_head_1": [(41, 12, 45, 15), (27, 8, 27, 8)],
        "orbital_head_2": [(25, 15, 35, 18)],
        "orbital_body_1": [(40, 28, 41, 30)],
        "tidal_head_2": [(23, 11, 33, 17), (38, 15, 42, 19)],
        "tidal_body_1": [(27, 28, 33, 34), (40, 29, 40, 29)],
        "tidal_body_2": [(30, 30, 34, 34), (30, 38, 34, 39)],
        "domestic_head_1": [(25, 15, 29, 18), (33, 15, 37, 18)],
        "domestic_head_2": [(24, 13, 30, 19), (35, 15, 39, 18)],
        "domestic_body_1": [(30, 30, 33, 34), (23, 34, 23, 34)],
    }.get(part_id, [])
    result = {(frame * 64 + x, y): (0, 0)
              for frame in range(8) for x0, y0, x1, y1 in rectangles
              for y in range(y0, y1 + 1) for x in range(x0, x1 + 1)}
    if part_id == "tidal_head_1":
        # Exact authored porthole interior; its brass rim remains paintable.
        for frame in range(8):
            for y in range(9, 20):
                for x in range(25, 36):
                    if (x - 30) ** 2 + (y - 14) ** 2 <= 25:
                        result[(frame * 64 + x, y)] = (0, 0)
    return result


def original_coatable(pixel: RGBA, slot: str, x: int, y: int) -> bool:
    """Exact offline equivalent of the seven-family protectedPixel contract."""
    return (pixel[3] == 255 and pixel[:3] not in MECHANICAL_RGB
            and not (slot == "HEAD" and 18 <= x % 64 <= 45 and 12 <= y <= 23)
            and max(pixel[:3]) >= 96)


def role_mask(sheet: Image.Image, primary: Sequence[RGBA], secondary: Sequence[RGBA], *,
              original_material_compat: bool = False, slot: str | None = None,
              overrides: Mapping[tuple[int, int], tuple[int, int]] | None = None) -> Image.Image:
    """Create a mask from explicit ramps and optional (sheet x,y):(role,tone) overrides.

    Overrides describe authored exceptions such as a lamp sharing shell pigment.
    A fixed override must be (0,0). Original compatibility B remains independent
    of these semantic overrides, preserving the exact historical finish visuals.
    """
    if sheet.mode != "RGBA" or sheet.size != (512, 64):
        raise ValueError("Part sheets must be RGBA 512x64")
    if original_material_compat and slot not in {"HEAD", "BODY", "ARMS", "LEGS"}:
        raise ValueError("Original material compatibility requires a valid slot")
    roles = {}
    for role, ramp in enumerate((primary, secondary), 1):
        if len(ramp) not in (3, 4):
            raise ValueError("Each paint ramp needs shadow/base/light[/glint]")
        for tone, raw in enumerate(ramp):
            colour = tuple(raw)
            if len(colour) != 4 or colour[3] != 255 or any(type(v) is not int or not 0 <= v <= 255 for v in colour):
                raise ValueError(f"Paint ramp colour is not opaque RGBA: {colour}")
            if colour in roles:
                raise ValueError(f"Ambiguous paint colour {colour}; use unique ramps and explicit overrides")
            roles[colour] = (role, tone)
    overrides = overrides or {}
    for (x, y), (role, tone) in overrides.items():
        if not (0 <= x < 512 and 0 <= y < 64 and role in (0, 1, 2) and tone in range(4)):
            raise ValueError(f"Invalid authored override {(x, y)}: {(role, tone)}")
        if role == 0 and tone != 0:
            raise ValueError("Fixed pixels have tone zero")
    pixels = []
    for index, pixel in enumerate(sheet.getdata()):
        x, y = index % 512, index // 512
        if pixel[3] == 0:
            pixels.append((0, 0, 0, 0))
            continue
        role, tone = overrides.get((x, y), roles.get(pixel, (0, 0)))
        eligible = original_coatable(pixel, slot, x, y) if original_material_compat else role != 0
        pixels.append((role, tone, int(eligible), pixel[3]))
    mask = Image.new("RGBA", sheet.size)
    mask.putdata(pixels)
    return mask


def export_part(sheet: Image.Image, destination: str | Path,
                primary: Sequence[RGBA], secondary: Sequence[RGBA], *,
                original_material_compat: bool = False, slot: str | None = None,
                overrides: Mapping[tuple[int, int], tuple[int, int]] | None = None) -> None:
    """Export a source sheet plus .paint.png, retaining identical existing PNG bytes."""
    destination = Path(destination)
    if destination.suffix != ".png" or destination.name.endswith(".paint.png"):
        raise ValueError("Destination must be a base .png sheet")
    mask = role_mask(sheet, primary, secondary, original_material_compat=original_material_compat,
                     slot=slot, overrides=overrides)
    destination.parent.mkdir(parents=True, exist_ok=True)
    unchanged = False
    if destination.exists():
        with Image.open(destination) as previous:
            unchanged = previous.mode == sheet.mode and previous.size == sheet.size and previous.tobytes() == sheet.tobytes()
    if not unchanged:
        sheet.save(destination, optimize=True)
    mask.save(destination.with_suffix(".paint.png"), optimize=True)
