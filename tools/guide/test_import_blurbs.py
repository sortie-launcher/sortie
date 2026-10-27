"""Catalog behavior tests; run with python3 -m unittest discover -s tools/guide."""

import importlib.util
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("catalog_export", Path(__file__).with_name("import-blurbs.py"))
catalog = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(catalog)


def note(filename="Example (USA).gba", system="gba", hook="A compact tactics game about positioning."):
    return {"sys": system, "file": filename, "hook": hook, "flags": [], "fit": 5,
            "device": "private-device", "path": "/home/private/library", "playMinutes": 99}


def export(notes, previous=None, decisions=(), dat=None, evidence=(), inventory=(), refreshed_systems=None):
    return catalog.export(notes, inventory, decisions, previous or {"entries": []}, dat or {},
                          evidence, refreshed_systems)[0]


def fingerprint(crc="12345678"):
    return {"algorithm": "crc32", "value": crc, "representation": "raw", "size": 32,
            "verification": "dat-release", "source": "Example DAT (1)"}


class CatalogExportTest(unittest.TestCase):
    def test_prose_change_preserves_uuid_and_changes_revision(self):
        before = export([note()])
        after = export([note(hook="An updated original note about positioning.")], before)
        self.assertEqual(before["entries"][0]["id"], after["entries"][0]["id"])
        self.assertNotEqual(before["revision"], after["revision"])

    def test_curated_title_changes_display_without_reidentifying_release(self):
        original = note("baccarat.zip", "ngp")
        before = export([original])
        title = "Neo Baccarat (Japan) (Prototype)"
        decision = {"system": "ngp", "alias": "baccarat.zip", "title": title}
        dat = {("ngp", title): [(fingerprint(), {"kind": "dat", "name": "Example DAT"})]}
        after = export([original], before, decisions=[decision], dat=dat)
        entry = after["entries"][0]
        self.assertEqual(title, entry["title"])
        self.assertEqual(before["entries"][0]["id"], entry["id"])
        self.assertEqual(before["entries"][0]["aliases"], entry["aliases"])
        self.assertNotIn(title, entry["aliases"])
        self.assertNotIn("fingerprints", entry)
        self.assertNotEqual(before["revision"], after["revision"])

    def test_curated_title_must_be_nonempty_public_text(self):
        for title in ("", "   ", None, "/home/private/library"):
            with self.subTest(title=title), self.assertRaises(ValueError):
                export([note()], decisions=[{"system": "gba", "alias": "Example (USA).gba", "title": title}])

    def test_input_order_and_reexport_are_deterministic(self):
        notes = [note(), note("Other (Japan).gba")]
        first = export(notes)
        self.assertEqual(first, export(list(reversed(notes))))
        self.assertEqual(first, export(notes, first))

    def test_recognized_extension_chains_are_complete_on_first_export(self):
        base = "saint seiya - ougon densetsu - castellano v1.0"
        for filename in (base + ".nes.zip", base + ".nes.zip.7z"):
            with self.subTest(filename=filename):
                first = export([note(filename, "nes")])
                expected = {base, base + ".nes", base + ".nes.zip"}
                if filename.endswith(".7z"):
                    expected.add(filename)
                self.assertEqual(expected, set(first["entries"][0]["aliases"]))
                second = export([note(filename, "nes")], first)
                self.assertEqual(first, second)
                self.assertEqual(second, export([note(filename, "nes")], second))

    def test_extension_chain_repair_preserves_published_uuid_and_evidence(self):
        base = "Release (Translation v1.0)"
        filename = base + ".nes.zip"
        before = export([note(filename, "nes")])
        entry = before["entries"][0]
        entry["aliases"] = [filename, base + ".nes"]  # Older partial alias chain.
        entry["externalIds"] = {"mame": "published-set-id"}
        entry["fingerprints"] = [dict(fingerprint(), verification="payload-verified")]
        before["revision"] = catalog.revision(before["entries"])
        after = export([note(base + ".nes", "nes")], before)
        self.assertEqual(1, len(after["entries"]))
        repaired = after["entries"][0]
        self.assertEqual(entry["id"], repaired["id"])
        self.assertEqual(entry["externalIds"], repaired["externalIds"])
        self.assertEqual(entry["fingerprints"], repaired["fingerprints"])
        self.assertEqual({filename, base + ".nes", base}, set(repaired["aliases"]))
        self.assertEqual(after, export([note(base + ".nes", "nes")], after))

    def test_extension_chain_stops_at_unknown_suffix_and_preserves_package_paths(self):
        filename = "Release (Hack v1.0).nes.custom.zip"
        document = export([note(filename, "nes")])
        self.assertEqual({filename, "Release (Hack v1.0).nes.custom"},
                         set(document["entries"][0]["aliases"]))
        self.assertEqual(document, export([note(filename, "nes")], document))
        package = "games/Release/Game.nes.zip"
        inventory = [{"s": "nes", "f": package, "t": "Release"}]
        nested = export([note(package, "nes")], inventory=inventory)
        self.assertEqual([package], nested["entries"][0]["aliases"])
        self.assertEqual(nested, export([note(package, "nes")], nested, inventory=inventory))

    def test_missing_container_stems_are_repaired_before_uuid_allocation(self):
        for original, converted in [("Release (USA).ciso", "Release (USA).iso"),
                                    ("Release (Hack v1).cdi", "Release (Hack v1).chd"),
                                    ("Release (Europe).32x", "Release (Europe).bin")]:
            with self.subTest(original=original):
                before = export([note(original)])
                entry = before["entries"][0]
                entry["aliases"] = [original]  # Published by the older extension list.
                before["revision"] = catalog.revision(before["entries"])
                after = export([note(converted)], before)
                self.assertEqual(1, len(after["entries"]))
                self.assertEqual(entry["id"], after["entries"][0]["id"])
                self.assertEqual({original, converted, catalog.stem(converted)},
                                 set(after["entries"][0]["aliases"]))

    def test_stem_repair_does_not_merge_previously_distinct_uuids(self):
        before = export([note("Release.ciso"), note("Other.iso")])
        before["entries"][0]["aliases"] = ["Release.ciso"]
        before["entries"][1]["aliases"] = ["Release.iso"]
        before["revision"] = catalog.revision(before["entries"])
        with self.assertRaises(ValueError):
            export([], before)

    def test_private_fields_are_not_exported(self):
        entry = export([note()])["entries"][0]
        for field in ("fit", "device", "path", "playMinutes"):
            self.assertNotIn(field, entry)
        self.assertFalse(any("/home/" in str(value) for value in entry.values()))

    def test_private_prose_requires_curation(self):
        source = note(hook="This copy is on your library list.")
        with self.assertRaises(ValueError):
            export([source])
        edit = {"system": "gba", "alias": source["file"], "hook": "A public editorial note."}
        self.assertEqual("A public editorial note.", export([source], decisions=[edit])["entries"][0]["hook"])

    def test_platform_regions_and_patch_versions_remain_distinct(self):
        entries = export([note(), note(system="genesis"), note("Example (Japan).gba"),
                          note("Example (USA) (Hack v1.0).gba"), note("Example (USA) (Hack v1.1).gba")])["entries"]
        self.assertEqual(5, len({entry["id"] for entry in entries}))
        self.assertTrue(all("Example" not in entry["aliases"] for entry in entries))
        self.assertTrue(all(len(entry["aliases"]) == 2 for entry in entries))

    def test_nested_packages_keep_exact_paths_not_shared_engine_names(self):
        paths = ["games/Infinity 3.3.4/Infinity 3.3.4/Game.exe", "games/Reborn-19.5.0/Game.ini",
                 "games/Other/Game.exe"]
        notes = [note(path, "rpg-maker") for path in paths]
        inventory = [{"s": "rpg-maker", "f": path, "t": title} for path, title in
                     zip(paths, ["Pokemon Infinity", "Pokemon Reborn", "Other game"])]
        before = export(notes, inventory=inventory)
        self.assertEqual(3, len({entry["id"] for entry in before["entries"]}))
        self.assertEqual(set(paths), {entry["aliases"][0] for entry in before["entries"]})
        self.assertTrue(all(len(entry["aliases"]) == 1 for entry in before["entries"]))
        self.assertTrue(all("fingerprints" not in entry for entry in before["entries"]))
        self.assertEqual({"Pokemon Infinity", "Pokemon Reborn", "Other game"},
                         {entry["title"] for entry in before["entries"]})
        notes[0]["hook"] = "Updated package-specific editorial note."
        after = export(notes, before, inventory=inventory)
        self.assertEqual({entry["id"] for entry in before["entries"]},
                         {entry["id"] for entry in after["entries"]})

    def test_package_aliases_reject_absolute_traversal_and_generic_entrypoints(self):
        for path in ["/games/Title/Game.exe", "C:/games/Title/Game.exe", "../Title/Game.exe",
                     "games/../Title/Game.exe", "games/./Title/Game.exe", "games//Title/Game.exe",
                     "games\\Title\\Game.exe", "games/Title/", "Game.exe", "Game.ini", "Game.rgssad"]:
            with self.subTest(path=path), self.assertRaises(ValueError):
                export([note(path, "rpg-maker")])

    def test_nested_package_requires_explicit_display_identity(self):
        with self.assertRaises(ValueError):
            export([note("games/Title/Game.exe", "rpg-maker")])

    def test_community_entry_and_external_ids_survive_regeneration(self):
        before = export([note(), note("Community (Europe).gba")])
        before["entries"][0]["externalIds"] = {"sortie-legacy-variant": "published-id"}
        before["revision"] = catalog.revision(before["entries"])
        after = export([note()], before)
        self.assertEqual(before, after)

    def test_same_release_across_arcade_folders_has_one_identity(self):
        notes = [note("example.zip", "fbneo"), note("example.zip", "mame")]
        self.assertEqual(1, len(export(notes)["entries"]))
        notes[1]["hook"] = "A conflicting editorial note."
        with self.assertRaises(ValueError):
            export(notes)

    def test_expected_dat_evidence_is_not_payload_verification(self):
        expected = fingerprint()
        dat = {("gba", "Example (USA)"): [(expected, {"kind": "dat", "name": "Example DAT"})]}
        entry = export([note()], dat=dat)["entries"][0]
        self.assertEqual("dat-release", entry["fingerprints"][0]["verification"])
        self.assertFalse(any(source.get("kind") == "payload-verified" for source in entry["sources"]))
        patched = export([note("Example (USA) (Hack v1.0).gba")], dat=dat)["entries"][0]
        self.assertNotIn("fingerprints", patched)

    def test_shared_fingerprint_is_withheld_not_arbitrarily_assigned(self):
        expected, source = fingerprint(), {"kind": "dat", "name": "Example DAT"}
        dat = {("gba", "Example (USA)"): [(expected, source)],
               ("gba", "Example (Japan)"): [(expected, source)]}
        entries = export([note(), note("Example (Japan).gba")], dat=dat)["entries"]
        self.assertTrue(all("fingerprints" not in entry for entry in entries))

    def test_ambiguous_dat_release_has_no_fingerprint(self):
        source = {"kind": "dat", "name": "Example DAT"}
        dat = {("gba", "Example (USA)"): [(fingerprint(), source), (fingerprint("87654321"), source)]}
        self.assertNotIn("fingerprints", export([note()], dat=dat)["entries"][0])

    def test_dat_refresh_retires_previously_accepted_ambiguous_expectation(self):
        source = {"kind": "dat", "name": "Example DAT"}
        first = {("gba", "Example (USA)"): [(fingerprint(), source)]}
        before = export([note()], dat=first)
        ambiguous = {("gba", "Example (USA)"): [(fingerprint(), source), (fingerprint("87654321"), source)]}
        # Also refresh community entries absent from the private notes.
        after = export([], before, dat=ambiguous)
        self.assertNotIn("fingerprints", after["entries"][0])
        self.assertEqual(before["entries"][0]["id"], after["entries"][0]["id"])

    def test_corrected_unique_dat_replaces_expected_but_keeps_payload_verified(self):
        source = {"kind": "dat", "name": "Example DAT"}
        before = export([note()], dat={("gba", "Example (USA)"): [(fingerprint(), source)]})
        verified = dict(fingerprint("abcdef01"), verification="payload-verified", source="Independent payload inspection")
        before["entries"][0]["fingerprints"].append(verified)
        before["revision"] = catalog.revision(before["entries"])
        corrected = fingerprint("87654321")
        after = export([note()], before, dat={("gba", "Example (USA)"): [(corrected, source)]})
        self.assertEqual({catalog.encoded(verified), catalog.encoded(corrected)},
                         {catalog.encoded(fp) for fp in after["entries"][0]["fingerprints"]})

    def test_ambiguous_refresh_preserves_independent_payload_verification(self):
        source = {"kind": "dat", "name": "Example DAT"}
        before = export([note()], dat={("gba", "Example (USA)"): [(fingerprint(), source)]})
        verified = dict(fingerprint("abcdef01"), verification="payload-verified", source="Independent payload inspection")
        before["entries"][0]["fingerprints"].append(verified)
        before["revision"] = catalog.revision(before["entries"])
        dat = {("gba", "Example (USA)"): [(fingerprint(), source), (fingerprint("87654321"), source)]}
        after = export([note()], before, dat=dat)
        self.assertEqual([verified], after["entries"][0]["fingerprints"])

    def test_omitted_dat_retains_fingerprints_and_provenance(self):
        source = {"kind": "dat", "name": "Example DAT"}
        before = export([note()], dat={("gba", "Example (USA)"): [(fingerprint(), source)]})
        self.assertEqual(before, export([note()], before))
        self.assertEqual(before, export([note()], before, refreshed_systems={"gb"}))

    def test_removed_release_in_refreshed_dat_retires_expected_fingerprint(self):
        source = {"kind": "dat", "name": "Example DAT"}
        before = export([note()], dat={("gba", "Example (USA)"): [(fingerprint(), source)]})
        after = export([note()], before, dat={}, refreshed_systems={"gba"})
        self.assertNotIn("fingerprints", after["entries"][0])

    def test_private_evidence_url_is_rejected(self):
        for url in ("http://192.168.1.143/data", "file:///home/private/evidence", "http://localhost/data"):
            with self.assertRaises(ValueError):
                export([note()], evidence=[{"sys": "gba", "file": "Example (USA).gba", "sources": [url]}])

    def test_validation_rejects_alias_collisions_and_parent_cycles(self):
        document = export([note(), note("Other.gba")])
        document["entries"][1]["aliases"] = document["entries"][0]["aliases"]
        document["revision"] = catalog.revision(document["entries"])
        with self.assertRaises(ValueError):
            catalog.validate(document)
        document = export([note()])
        document["entries"][0]["parentId"] = document["entries"][0]["id"]
        document["revision"] = catalog.revision(document["entries"])
        with self.assertRaises(ValueError):
            catalog.validate(document)

    def test_dat_parser_ignores_multi_rom_sets(self):
        data = '''clrmamepro (
 name "Example DAT"
 version "1"
)
game (
 name "Example (USA)"
 rom ( name "Example (USA).gba" size 32 crc 12345678 )
)
game (
 name "Multi (USA)"
 rom ( name "one.gba" size 32 crc 12345678 )
 rom ( name "two.gba" size 32 crc 87654321 )
)
'''
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path / "Nintendo - Game Boy Advance.dat").write_text(data, encoding="utf-8")
            dat, refreshed = catalog.load_dat(path)
            self.assertIn(("gba", "Example (USA)"), dat)
            self.assertNotIn(("gba", "Multi (USA)"), dat)
            self.assertEqual({"gba"}, refreshed)

    def test_gamegear_dat_uses_runtime_system_id(self):
        data = '''clrmamepro (
 name "Sega - Game Gear"
 version "1"
)
game (
 name "Example (USA)"
 rom ( name "Example (USA).gg" size 32 crc 12345678 )
)
'''
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path / "Sega - Game Gear.dat").write_text(data, encoding="utf-8")
            dat, refreshed = catalog.load_dat(path)
            self.assertEqual({"gamegear"}, refreshed)
            self.assertIn(("gamegear", "Example (USA)"), dat)
            self.assertNotIn(("gg", "Example (USA)"), dat)
            entry = export([note("Example (USA).gg", "gamegear")], dat=dat,
                           refreshed_systems=refreshed)["entries"][0]
            self.assertEqual("12345678", entry["fingerprints"][0]["value"])


if __name__ == "__main__":
    unittest.main()
