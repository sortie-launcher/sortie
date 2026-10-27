#!/usr/bin/env python3
"""Regenerate shipped Rivet sheets and masks; optional review art stays outside assets.

python3 tools/mecha/generate.py              # all 25 families / 50 complete sets
python3 tools/mecha/generate.py yard survey --review ~/.cache/sortie-mecha-lab/paint.png
python3 -m unittest discover -s tools/mecha -p 'test_*.py'  # authoring checks
"""
from __future__ import annotations

import argparse
import importlib.util
import json
from pathlib import Path

from PIL import Image, ImageDraw

from paint_roles import export_part, legacy_overrides, role_mask

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
ASSETS = ROOT / "mecha/src/main/assets"
ORIGINAL_FAMILIES = ("yard", "survey", "courier", "foundry", "orbital", "tidal", "domestic")
FAMILIES = ORIGINAL_FAMILIES + (
    "rescue", "medical", "ranger", "harvest", "apiary", "orchard", "abyssal", "salvage", "nautical",
    "observatory", "radio", "satellite", "ceramic", "textile", "clockwork", "quarry", "rail", "demolition",
)
SLOTS = ("HEAD", "BODY", "ARMS", "LEGS")


def author(family):
    if family not in FAMILIES:
        raise ValueError(f"Unknown authored family: {family}")
    path = HERE / f"{family}.py" if family in ORIGINAL_FAMILIES[:3] else HERE / "workshop" / f"{family}.py"
    spec = importlib.util.spec_from_file_location(f"mecha_author_{family}", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def authored_parts(family):
    module = author(family)
    render = module.build_sheet if family == "yard" else module.render_sheet
    primary = getattr(module, "PAINT_PRIMARY", None) or module.PRIMARY
    secondary = getattr(module, "PAINT_SECONDARY", None) or module.SECONDARY
    for pid, slot, name, draw in module.PARTS:
        overrides = module.paint_overrides(pid) if hasattr(module, "paint_overrides") else legacy_overrides(pid) if family in ORIGINAL_FAMILIES else {}
        options = dict(original_material_compat=family in ORIGINAL_FAMILIES, slot=slot, overrides=overrides)
        yield dict(id=pid, slot=slot, family=family, name=name, asset=f"mecha/{family}/{pid}.png"), render(draw), primary, secondary, options


def painted(sheet, mask, primary=(233, 185, 73), secondary=(53, 63, 75)):
    """Static Standard review using the same integer tone ramps as the renderer."""
    pixels = []
    for pixel, semantic in zip(sheet.getdata(), mask.getdata()):
        role, tone = semantic[:2]
        if not role:
            pixels.append(pixel)
            continue
        swatch = primary if role == 1 else secondary
        if tone == 0:
            rgb = tuple(value * 64 // 100 for value in swatch)
        elif tone == 1:
            rgb = swatch
        else:
            blend = 34 if tone == 2 else 64
            rgb = tuple(value + (255 - value) * blend // 100 for value in swatch)
        pixels.append((*rgb, pixel[3]))
    image = Image.new("RGBA", sheet.size)
    image.putdata(pixels)
    return image


def review_board(rows, destination):
    """Original / semantic roles / Hazard / Porcelain, with every complete set."""
    families = list(dict.fromkeys(entry["family"] for entry, _, _ in rows))
    board = Image.new("RGBA", (1100, 48 + len(families) * 2 * 284), (16, 20, 24, 255))
    text = ImageDraw.Draw(board)
    for i, title in enumerate(("Original", "Authored paint roles", "Hazard Standard", "Porcelain Standard")):
        text.text((i * 270 + 14, 15), title, fill=(230, 233, 228, 255))
    lookup = {entry["id"]: (sheet, mask) for entry, sheet, mask in rows}
    for family_index, family in enumerate(families):
        for variant in (1, 2):
            y = 48 + (family_index * 2 + variant - 1) * 284
            text.text((14, y), f"{family} / set {variant}", fill=(230, 233, 228, 255))
            for column in range(4):
                assembled = Image.new("RGBA", (64, 64))
                for slot in ("LEGS", "BODY", "ARMS", "HEAD"):
                    source, mask = lookup[f"{family}_{slot.lower()}_{variant}"]
                    if column == 0:
                        layer = source
                    elif column == 1:
                        layer = Image.new("RGBA", source.size)
                        layer.putdata([(p[0] // 3, p[1] // 3, p[2] // 3, p[3]) if m[0] == 0 else
                                       (245, 179, 52, p[3]) if m[0] == 1 else (79, 191, 214, p[3])
                                       for p, m in zip(source.getdata(), mask.getdata())])
                    elif column == 2:
                        layer = painted(source, mask)
                    else:
                        layer = painted(source, mask, (232, 227, 211), (80, 124, 161))
                    assembled.alpha_composite(layer.crop((0, 0, 64, 64)))
                board.alpha_composite(assembled.resize((256, 256), Image.Resampling.NEAREST), (column * 270 + 10, y + 20))
    destination = Path(destination).expanduser().resolve()
    if destination == ASSETS or ASSETS in destination.parents:
        raise ValueError("Review boards must not be packaged as runtime assets")
    destination.parent.mkdir(parents=True, exist_ok=True)
    board.save(destination)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("families", nargs="*", help="Default: all 25 authored families")
    parser.add_argument("--review", type=Path, help="Optional non-runtime paint review PNG")
    args = parser.parse_args()
    families = args.families or FAMILIES
    rows = []
    for family in families:
        catalog = []
        for entry, sheet, primary, secondary, options in authored_parts(family):
            export_part(sheet, ASSETS / entry["asset"], primary, secondary, **options)
            catalog.append(entry)
            if args.review:
                rows.append((entry, sheet, role_mask(sheet, primary, secondary, **options)))
        (ASSETS / "mecha" / family / "catalog.json").write_text(json.dumps(catalog, indent=2, ensure_ascii=False) + "\n")
        print(f"{family}: exported 2 complete sets, 8 sheets, 8 masks")
    if args.review:
        review_board(rows, args.review)


if __name__ == "__main__":
    main()
