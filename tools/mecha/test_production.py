"""Authored-art checks: run with unittest discovery from tools/mecha (Pillow required)."""
from collections import Counter
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from PIL import Image

from generate import ASSETS, FAMILIES, HERE, ORIGINAL_FAMILIES, SLOTS, authored_parts, painted
from paint_roles import export_part, role_mask


class ProductionArtTest(unittest.TestCase):
    def test_all_fifty_complete_sets_are_reproducible_and_packaged_without_review_art(self):
        self.assertEqual(25, len(FAMILIES))
        all_ids, unique_sheets = set(), set()
        for family in FAMILIES:
            catalog_path = ASSETS / "mecha" / family / "catalog.json"
            catalog = json.loads(catalog_path.read_text())
            expected_ids = {f"{family}_{slot.lower()}_{variant}" for slot in SLOTS for variant in (1, 2)}
            self.assertEqual(expected_ids, {entry["id"] for entry in catalog}, family)
            self.assertEqual(8, len(catalog), family)
            expected_files = {"catalog.json"} | {f"{pid}{suffix}" for pid in expected_ids for suffix in (".png", ".paint.png")}
            self.assertEqual(expected_files, {p.name for p in catalog_path.parent.iterdir()}, family)
            regenerated = list(authored_parts(family))
            self.assertEqual(catalog, [entry for entry, *_ in regenerated], family)
            for entry, drawn, primary, secondary, options in regenerated:
                pid = entry["id"]
                with self.subTest(part=pid):
                    self.assertNotIn(pid, all_ids)
                    all_ids.add(pid)
                    self.assertLessEqual(len(entry["name"]), 32)
                    path = ASSETS / entry["asset"]
                    with Image.open(path) as source, Image.open(path.with_suffix(".paint.png")) as mask:
                        self.assertEqual((512, 64), source.size)
                        self.assertEqual("RGBA", source.mode)
                        self.assertEqual(source.size, mask.size)
                        self.assertEqual("RGBA", mask.mode)
                        self.assertEqual(drawn.tobytes(), source.tobytes(), "base authoring drift")
                        expected_mask = role_mask(drawn, primary, secondary, **options)
                        self.assertEqual(expected_mask.tobytes(), mask.tobytes(), "semantic authoring drift")
                        digest = hashlib.sha256(source.tobytes()).hexdigest()
                        self.assertNotIn(digest, unique_sheets, "duplicate artwork is not another part")
                        unique_sheets.add(digest)
                        self.check_mask(source, mask, entry)
        self.assertEqual(200, len(all_ids))

    def check_mask(self, source, mask, entry):
        old = entry["family"] in ORIGINAL_FAMILIES
        for index, (pixel, semantic) in enumerate(zip(source.getdata(), mask.getdata())):
            role, tone, eligibility, alpha = semantic
            self.assertIn(pixel[3], (0, 255))
            self.assertEqual(pixel[3], alpha)
            self.assertIn(role, (0, 1, 2))
            self.assertIn(tone, (0, 1, 2, 3))
            self.assertIn(eligibility, (0, 1))
            if alpha == 0:
                self.assertEqual((0, 0, 0, 0), semantic)
            if role == 0:
                self.assertEqual(0, tone)
            if old:
                # Independent transcription of the removed runtime protectedPixel predicate.
                x, y = index % 64, index // 512
                rgb = pixel[0] << 16 | pixel[1] << 8 | pixel[2]
                protected = (pixel[3] != 255 or rgb in (0x18232D, 0x344650, 0x768A91, 0xA6B5B1, 0xD7DFD5)
                             or entry["slot"] == "HEAD" and 18 <= x <= 45 and 12 <= y <= 23
                             or max(pixel[:3]) < 96)
                self.assertEqual(int(not protected), eligibility, f"legacy finish at {index}")
            else:
                self.assertEqual(int(role != 0), eligibility)
        for frame in range(8):
            roles = Counter(pixel[0] for pixel in mask.crop((frame * 64, 0, frame * 64 + 64, 64)).getdata() if pixel[3])
            self.assertGreater(roles[1] + roles[2], 0, "no painted region")
            if not old:
                self.assertGreaterEqual(roles[1], 4, "primary must be a meaningful region in every pose")
                self.assertGreaterEqual(roles[2], 4, "secondary must be a meaningful region in every pose")
        for p, m, output in zip(source.getdata(), mask.getdata(), painted(source, mask).getdata()):
            self.assertEqual(p[3], output[3])
            if m[0] == 0:
                self.assertEqual(p, output, "fixed details must survive paint")

    def test_original_fifty_six_base_pngs_retain_exact_bytes(self):
        originals = json.loads((HERE / "original-sheets.json").read_text())
        self.assertEqual(56, len(originals))
        for path, digest in originals.items():
            with self.subTest(path=path):
                self.assertEqual(digest, hashlib.sha256((ASSETS / path).read_bytes()).hexdigest())


class PaintRoleAuthoringTest(unittest.TestCase):
    primary = ((64, 32, 16, 255), (128, 64, 32, 255), (192, 128, 64, 255))
    secondary = ((16, 32, 64, 255), (32, 64, 128, 255), (64, 128, 192, 255))

    def sheet(self):
        image = Image.new("RGBA", (512, 64))
        for x, colour in enumerate(self.primary + self.secondary):
            image.putpixel((x, 0), colour)
        image.putpixel((6, 0), (10, 20, 30, 255))
        return image

    def test_explicit_roles_tones_fixed_and_overrides(self):
        mask = role_mask(self.sheet(), self.primary, self.secondary, overrides={(1, 0): (0, 0)})
        self.assertEqual((1, 0, 1, 255), mask.getpixel((0, 0)))
        self.assertEqual((0, 0, 0, 255), mask.getpixel((1, 0)))
        self.assertEqual((1, 2, 1, 255), mask.getpixel((2, 0)))
        self.assertEqual((2, 0, 1, 255), mask.getpixel((3, 0)))
        self.assertEqual((2, 2, 1, 255), mask.getpixel((5, 0)))
        self.assertEqual((0, 0, 0, 255), mask.getpixel((6, 0)))
        self.assertEqual((0, 0, 0, 0), mask.getpixel((7, 0)))

    def test_ambiguous_ramps_and_invalid_overrides_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "Ambiguous"):
            role_mask(self.sheet(), self.primary, self.primary)
        with self.assertRaisesRegex(ValueError, "Ambiguous"):
            role_mask(self.sheet(), (self.primary[0],) * 3, self.secondary)
        with self.assertRaisesRegex(ValueError, "Fixed"):
            role_mask(self.sheet(), self.primary, self.secondary, overrides={(0, 0): (0, 1)})
        with self.assertRaisesRegex(ValueError, "valid slot"):
            role_mask(self.sheet(), self.primary, self.secondary, original_material_compat=True)

    def test_export_preserves_existing_identical_source_png_encoding(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "part.png"
            sheet = self.sheet()
            sheet.save(target, compress_level=0)
            before = target.read_bytes()
            export_part(sheet, target, self.primary, self.secondary)
            self.assertEqual(before, target.read_bytes())
            with Image.open(target.with_suffix(".paint.png")) as mask:
                self.assertEqual(role_mask(sheet, self.primary, self.secondary).tobytes(), mask.tobytes())


if __name__ == "__main__":
    unittest.main()
