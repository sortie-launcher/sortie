#!/usr/bin/env python3
"""Offline, resolved-recipe Rivet assembly workshop. Requires Python 3.10+ and Pillow.

Generate a portable review bundle from the seven shipped catalogs:
  python3 tools/mecha/workshop/procedural/assemble.py generate --seed 2136 --count 12 --out tools/mecha/workshop/procedural/review
Replay without catalogs or seed selection (uses the bundle's digest-locked assets):
  python3 tools/mecha/workshop/procedural/assemble.py replay tools/mecha/workshop/procedural/review/recipes.json --out /tmp/rivet-replayed

Repeat --catalog PATH to REPLACE the default catalog list, including workshop
catalogs explicitly. Each catalog's PNG files must be siblings of catalog.json;
its asset field may be the original Android asset name or a relative filename.
Output directories must be absent or empty; source assets are never overwritten.

Policy v1: exactly two authored family palettes; BODY+ARMS share a chassis family,
HEAD+LEGS share a contrasting trim family. Shared outline, binary alpha, eight
nonempty poses and attachment-zone occupancy are mandatory. Idle head/chassis
and boot/chassis width plus leg/chassis area bounds reject top-heavy/noisy mixes.
No recoloring, stretching, random pixel detail or per-frame shape generation.
The seed chooses among compatible unique assemblies, NOT an identity/save format.

recipes.json records resolved IDs, source catalog and PNG SHA256, pixel SHA256,
rig, layer order, renderer/generator/policy versions, encoding environment and
output hashes. Bundled source PNGs make the recipe independent of live catalogs.
Replay never reruns selection or current generation policy. Unknown recipe/rig/
renderer versions, changed/missing assets or incompatible encoders fail clearly.
PNG/GIF bytes are reproducible in the recorded Pillow/zlib environment; decoded
sprite pixels are version-independent. This is an offline workshop, not an
Android inventory/schema migration. No credits or user state are accessed.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import itertools
import json
from pathlib import Path
import random
import re
import shutil
import sys
import zlib

from PIL import Image, ImageDraw, __version__ as PILLOW_VERSION

ROOT = Path(__file__).resolve().parents[4]
VERSION = "rivet-assembler-1.0.0"
RENDERER = "rivet-sheet-review-1"
POLICY = "paired-families-balanced-mass-1"
RIG = "sortie-64x64-eight-poses-1"
ORDER = ("LEGS", "BODY", "ARMS", "HEAD")
FRAME_NAMES = ("IDLE", "DETAIL", "CONTACT L", "PASS L", "CONTACT R", "PASS R", "SLEEP", "CHEER")
OUTLINE = (24, 35, 45, 255)
BG = (238, 228, 210, 255)
INK = (24, 35, 45, 255)
SAFE_ID = re.compile(r"[a-z][a-z0-9_]*\Z")
# Regions are deliberately wider than exact joints: authored outlines surround them.
JOINTS = {
    "HEAD": ((27, 21, 38, 28),),
    "BODY": ((27, 21, 38, 28), (16, 26, 24, 34), (41, 26, 49, 34), (22, 40, 30, 48), (35, 40, 43, 48)),
    "ARMS": ((16, 26, 24, 34), (41, 26, 49, 34)),
    "LEGS": ((22, 40, 30, 48), (35, 40, 43, 48)),
}


class Refusal(ValueError):
    """Input cannot be rendered without changing its meaning."""


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def canonical(value: object) -> bytes:
    return (json.dumps(value, indent=2, sort_keys=True, ensure_ascii=True) + "\n").encode()


def encoding() -> dict:
    return {"pillow": PILLOW_VERSION, "zlib": zlib.ZLIB_RUNTIME_VERSION,
            "png": "RGBA/compress_level=9/optimize=false", "gif": "global-authored-palette/no-dither/optimize=false"}


def read_json(path: Path):
    try:
        return json.loads(path.read_text())
    except (OSError, ValueError) as exc:
        raise Refusal(f"Cannot read JSON {path}: {exc}") from exc


def image_from(path: Path, slot: str) -> Image.Image:
    try:
        with Image.open(path) as source:
            if source.format != "PNG" or source.mode != "RGBA" or source.size != (512, 64):
                raise Refusal(f"Incompatible asset {path}: require RGBA PNG, 512x64 (eight 64px poses)")
            image = source.copy()
    except OSError as exc:
        raise Refusal(f"Missing/unreadable asset {path}: {exc}") from exc
    if any(image.getchannel("A").histogram()[1:255]):
        raise Refusal(f"Incompatible antialiased/translucent asset: {path}")
    if not any(color == OUTLINE for _, color in image.getcolors(512 * 64)):
        raise Refusal(f"Incompatible outline in {path}: require shared #18232d")
    for frame in range(8):
        alpha = image.crop((frame * 64, 0, (frame + 1) * 64, 64)).getchannel("A")
        if alpha.getbbox() is None:
            raise Refusal(f"Empty pose {frame} in {path}")
        for joint in JOINTS[slot]:
            if alpha.crop(joint).getbbox() is None:
                raise Refusal(f"Incompatible rig in {path}, pose {frame}: missing {slot} attachment zone {joint}")
    return image


@dataclass
class Part:
    entry: dict
    source: Path
    image: Image.Image
    sha: str
    pixels: str
    width: int
    area: int


def load_catalogs(paths: list[Path]) -> tuple[dict[str, Part], list[dict]]:
    parts: dict[str, Part] = {}
    provenance = []
    for path in paths:
        entries = read_json(path)
        if not isinstance(entries, list) or not entries:
            raise Refusal(f"Catalog must be a nonempty list: {path}")
        catalog_sha = digest(path.read_bytes())
        provenance.append({"source": path.name, "sha256": catalog_sha})
        for entry in entries:
            if not isinstance(entry, dict) or set(entry) != {"id", "slot", "family", "name", "asset"}:
                raise Refusal(f"Require five-field id/slot/family/name/asset catalog entries: {path}")
            if not all(isinstance(v, str) and v for v in entry.values()):
                raise Refusal(f"Catalog fields must be nonempty strings: {path}")
            pid, slot, family = entry["id"], entry["slot"], entry["family"]
            if slot not in ORDER or not SAFE_ID.fullmatch(family) or not SAFE_ID.fullmatch(pid):
                raise Refusal(f"Invalid slot/family/id in {path}: {pid}")
            if not pid.startswith(f"{family}_{slot.lower()}_"):
                raise Refusal(f"ID does not match family and slot: {pid}")
            if pid in parts:
                raise Refusal(f"Duplicate part ID across catalogs: {pid}")
            asset = Path(entry["asset"])
            if asset.is_absolute() or ".." in asset.parts or asset.name != f"{pid}.png":
                raise Refusal(f"Asset must name {pid}.png without absolute/traversal paths")
            source = path.parent / asset.name
            image = image_from(source, slot)
            idle = image.crop((0, 0, 64, 64)).getchannel("A")
            box = idle.getbbox()
            parts[pid] = Part({**entry, "catalog_sha256": catalog_sha}, source, image,
                              digest(source.read_bytes()), digest(image.tobytes()),
                              box[2] - box[0], idle.histogram()[255])
    return parts, sorted(provenance, key=lambda p: p["sha256"])


def compatible(combo: dict[str, Part]) -> bool:
    head, body, legs = (combo[s] for s in ("HEAD", "BODY", "LEGS"))
    return (0.65 <= head.width / body.width <= 1.50
            and 0.65 <= legs.width / body.width <= 1.70
            and 0.30 <= legs.area / body.area <= 1.80)


def select(parts: dict[str, Part], seed: int, count: int) -> tuple[list[dict], int]:
    families = sorted({p.entry["family"] for p in parts.values()})
    pools = {(family, slot): sorted((p for p in parts.values()
              if p.entry["family"] == family and p.entry["slot"] == slot), key=lambda p: p.entry["id"])
             for family in families for slot in ORDER}
    candidates = []
    for chassis, trim in itertools.permutations(families, 2):
        choices = [pools[(chassis if s in ("BODY", "ARMS") else trim, s)] for s in ORDER]
        for values in itertools.product(*choices):
            combo = dict(zip(ORDER, values))
            if compatible(combo):
                candidates.append(combo)
    if count > len(candidates):
        raise Refusal(f"Requested {count} unique assemblies; only {len(candidates)} satisfy {POLICY}. Add compatible catalogs or lower --count.")
    # Randomness ends here: the renderer only receives these resolved records.
    picks = random.Random(seed).sample(candidates, count)
    resolved = []
    for index, combo in enumerate(picks, 1):
        layers = [{**combo[s].entry, "sha256": combo[s].sha, "pixel_sha256": combo[s].pixels,
                   "bundle_asset": f"assets/{combo[s].sha}.png"} for s in ORDER]
        resolved.append({"id": f"assembly-{index:03d}", "layers": layers,
                         "idle_metrics": {s: {"width": combo[s].width, "area": combo[s].area} for s in ORDER}})
    return resolved, len(candidates)


def compose(layers: list[dict], images: dict[str, Image.Image]) -> Image.Image:
    sheet = Image.new("RGBA", (512, 64))
    for layer in layers:
        sheet.alpha_composite(images[layer["sha256"]])
    return sheet


def png(image: Image.Image, path: Path) -> None:
    image.save(path, format="PNG", optimize=False, compress_level=9)


def render(recipe: dict, images: dict[str, Image.Image], out: Path) -> dict[str, str]:
    rows = recipe["assemblies"]
    # Eight poses at 2x, plus a true 1x reference. Names carry every resolved ID.
    board = Image.new("RGBA", (1264, 70 + len(rows) * 184), BG)
    draw = ImageDraw.Draw(board)
    draw.text((16, 12), "RIVET / RESOLVED ASSEMBLIES / OFFLINE WORKSHOP", fill=INK)
    draw.text((16, 29), "Two authored palettes. Chassis = body + arms; trim = head + legs. No random paint or geometry.", fill=INK)
    for frame, label in enumerate(FRAME_NAMES):
        draw.text((144 + frame * 128, 52), label, fill=INK)
    draw.text((1184, 52), "1x", fill=INK)
    sheets = []
    outputs = []
    for index, row in enumerate(rows):
        sheet = compose(row["layers"], images)
        sheets.append(sheet)
        name = f"{row['id']}.png"
        png(sheet, out / name)
        outputs.append(name)
        y = 70 + index * 184
        draw.text((12, y + 10), row["id"], fill=INK)
        draw.text((12, y + 28), "CHASSIS", fill=INK)
        draw.text((12, y + 42), row["layers"][1]["family"], fill=INK)
        draw.text((12, y + 62), "TRIM", fill=INK)
        draw.text((12, y + 76), row["layers"][0]["family"], fill=INK)
        for frame in range(8):
            tile = sheet.crop((frame * 64, 0, (frame + 1) * 64, 64))
            board.alpha_composite(tile.resize((128, 128), Image.Resampling.NEAREST), (144 + frame * 128, y))
        board.alpha_composite(sheet.crop((0, 0, 64, 64)), (1184, y + 32))
        for line, slots in enumerate((("HEAD", "BODY"), ("ARMS", "LEGS"))):
            label = "   /   ".join(layer["id"] for slot in slots for layer in row["layers"] if layer["slot"] == slot)
            draw.text((144, y + 136 + line * 16), label, fill=INK)
    png(board, out / "board.png")
    outputs.append("board.png")
    # One palette for the entire animation, preserving authored colors exactly
    # up to GIF's 256-color limit. Larger explicit catalogs share one quantization.
    colors = {BG[:3], INK[:3]}
    for image in images.values():
        colors.update(color[:3] for _, color in image.getcolors(512 * 64) if color[3])
    ordered_colors = sorted(colors)
    if len(ordered_colors) <= 256:
        palette = Image.new("P", (1, 1))
        palette.putpalette([channel for color in ordered_colors for channel in color]
                           + [0] * ((256 - len(ordered_colors)) * 3))
    else:
        swatch = Image.new("RGB", (len(ordered_colors), 1))
        swatch.putdata(ordered_colors)
        palette = swatch.quantize(colors=256, method=Image.Quantize.MEDIANCUT,
                                  dither=Image.Dither.NONE)
    columns = min(4, len(rows))
    grid_rows = (len(rows) + columns - 1) // columns
    frames = []
    sequence = (0, 1, 0, 2, 3, 4, 5, 2, 3, 4, 5, 6, 7)
    durations = (600, 220, 400, 160, 160, 160, 160, 160, 160, 160, 160, 800, 800)
    for pose in sequence:
        canvas = Image.new("RGBA", (columns * 208, grid_rows * 224), BG)
        labels = ImageDraw.Draw(canvas)
        for index, sheet in enumerate(sheets):
            x, y = index % columns * 208, index // columns * 224
            tile = sheet.crop((pose * 64, 0, (pose + 1) * 64, 64))
            canvas.alpha_composite(tile.resize((192, 192), Image.Resampling.NEAREST), (x + 8, y))
            labels.text((x + 8, y + 196), f"{rows[index]['id']} / {FRAME_NAMES[pose]}", fill=INK)
        frames.append(canvas.convert("RGB").quantize(palette=palette, dither=Image.Dither.NONE))
    frames[0].save(out / "preview.gif", save_all=True, append_images=frames[1:], duration=durations,
                   loop=0, optimize=False, disposal=2)
    outputs.append("preview.gif")
    return {name: digest((out / name).read_bytes()) for name in outputs}


def prepare_out(out: Path) -> None:
    if out.exists() and (not out.is_dir() or any(out.iterdir())):
        raise Refusal(f"Output must be absent or empty (will not overwrite): {out}")
    out.mkdir(parents=True, exist_ok=True)
    (out / "assets").mkdir()


def generate(args) -> None:
    catalogs = args.catalog or [ROOT / f"mecha/src/main/assets/mecha/{family}/catalog.json"
                               for family in ("yard", "survey", "courier", "foundry", "orbital", "tidal", "domestic")]
    parts, provenance = load_catalogs(catalogs)
    rows, eligible = select(parts, args.seed, args.count)
    recipe = {"format": "rivet-resolved-assemblies", "version": 1, "generator": VERSION,
              "renderer": RENDERER, "policy": POLICY, "rig": RIG, "seed": args.seed,
              "encoding": encoding(), "layer_order": list(ORDER), "catalogs": provenance,
              "eligible_assemblies": eligible, "assemblies": rows}
    used = {layer["id"] for row in rows for layer in row["layers"]}
    prepare_out(args.out)
    images = {}
    for pid in sorted(used):
        part = parts[pid]
        shutil.copyfile(part.source, args.out / "assets" / f"{part.sha}.png")
        images[part.sha] = part.image
    recipe["outputs"] = render(recipe, images, args.out)
    (args.out / "recipes.json").write_bytes(canonical(recipe))
    print(f"Exported {len(rows)} unique assemblies ({eligible} eligible) to {args.out}")


def replay(args) -> None:
    recipe = read_json(args.recipe)
    if not isinstance(recipe, dict) or recipe.get("format") != "rivet-resolved-assemblies" or recipe.get("version") != 1:
        raise Refusal("Unsupported recipe format/version; require rivet-resolved-assemblies v1")
    if recipe.get("renderer") != RENDERER or recipe.get("rig") != RIG or recipe.get("layer_order") != list(ORDER):
        raise Refusal("Incompatible renderer, rig or layer order; no automatic recipe migration")
    if recipe.get("encoding") != encoding():
        raise Refusal(f"Encoder mismatch: reproduce using recorded Pillow/zlib environment {recipe.get('encoding')}")
    rows = recipe.get("assemblies")
    if not isinstance(rows, list) or not 1 <= len(rows) <= 64:
        raise Refusal("Recipe needs 1..64 resolved assemblies")
    images, sources = {}, {}
    for index, row in enumerate(rows, 1):
        if not isinstance(row, dict) or row.get("id") != f"assembly-{index:03d}":
            raise Refusal("Invalid/nonsequential resolved assembly ID")
        layers = row.get("layers")
        if not isinstance(layers, list) or len(layers) != 4 or any(not isinstance(l, dict) for l in layers):
            raise Refusal(f"Invalid resolved layers in {row['id']}")
        if [layer.get("slot") for layer in layers] != list(ORDER):
            raise Refusal(f"Incompatible layer slots/order in {row['id']}")
        for layer in layers:
            sha = layer.get("sha256", "")
            if not isinstance(sha, str) or not re.fullmatch(r"[0-9a-f]{64}", sha):
                raise Refusal("Invalid asset SHA256")
            if layer.get("bundle_asset") != f"assets/{sha}.png":
                raise Refusal("Invalid bundled asset path; only content-addressed local assets are accepted")
            for field in ("id", "family", "name", "asset", "catalog_sha256", "pixel_sha256"):
                if not isinstance(layer.get(field), str) or not layer[field]:
                    raise Refusal(f"Missing resolved {field} in {row['id']}")
            source = args.recipe.parent / layer["bundle_asset"]
            try:
                actual_sha = digest(source.read_bytes())
            except OSError as exc:
                raise Refusal(f"Missing recipe asset {source}: {exc}") from exc
            if actual_sha != sha:
                raise Refusal(f"Asset digest mismatch: {source}; refusing substituted art")
            # Validate every slot use, even if a malicious recipe reuses one digest.
            image = image_from(source, layer["slot"])
            if digest(image.tobytes()) != layer["pixel_sha256"]:
                raise Refusal(f"Decoded pixel digest mismatch: {source}")
            images[sha], sources[sha] = image, source
    expected = recipe.get("outputs")
    names = {f"{row['id']}.png" for row in rows} | {"board.png", "preview.gif"}
    if not isinstance(expected, dict) or set(expected) != names:
        raise Refusal("Recipe is missing exact rendered output digests")
    prepare_out(args.out)
    for sha, source in sources.items():
        shutil.copyfile(source, args.out / "assets" / f"{sha}.png")
    outputs = render(recipe, images, args.out)
    if outputs != expected:
        raise Refusal("Rendered output digest mismatch; output is NOT a verified replay. Keep the original renderer/encoding environment.")
    (args.out / "recipes.json").write_bytes(canonical(recipe))
    print(f"Replayed {len(rows)} resolved assemblies; every PNG/GIF SHA256 matches. Seed selection was not used. Output: {args.out}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    generate_parser = sub.add_parser("generate", help="Seeded selection and portable recipe export")
    generate_parser.add_argument("--seed", type=int, required=True)
    generate_parser.add_argument("--count", type=int, default=12, help="Unique assemblies, 1..64 (default 12)")
    generate_parser.add_argument("--catalog", type=Path, action="append", help="Explicit catalog path; repeat to replace defaults")
    generate_parser.add_argument("--out", type=Path, required=True)
    replay_parser = sub.add_parser("replay", help="Render a resolved recipe without selecting from seed/catalogs")
    replay_parser.add_argument("recipe", type=Path)
    replay_parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "generate":
            if not 1 <= args.count <= 64:
                raise Refusal("--count must be between 1 and 64")
            generate(args)
        else:
            replay(args)
    except (Refusal, OSError) as exc:
        print(f"Refused: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
