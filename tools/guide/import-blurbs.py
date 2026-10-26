#!/usr/bin/env python3
"""Export original editorial notes into the public, offline game identity catalog.

Private inputs are read-only. Existing catalog UUIDs and community entries survive
regeneration. DAT hashes describe expected releases, never verified local files.
"""

import argparse
import copy
import hashlib
import json
import re
import sys
import uuid
from pathlib import Path
from urllib.parse import urlsplit

REPO = Path(__file__).resolve().parents[2]
CATALOG = REPO / "app/src/main/assets/game-blurbs.json"
CURATION = Path(__file__).with_name("curation.json")
NAMESPACE = uuid.UUID("5ced76a4-4230-5cdb-a657-f975b92eb7ba")
ARCADE = {"arcade", "mame", "fbneo", "neogeo", "cps1", "cps2", "cps3", "toaplan-cave-stg", "stv", "model2", "naomi", "atomiswave"}
PLATFORM_ALIASES = {"tg16": "pcengine", "megadrive": "genesis"}
EXTENSIONS = {"zip", "7z", "iso", "chd", "cso", "ciso", "cdi", "gdi", "pbp", "m3u", "cue", "bin", "gba", "gb", "gbc", "sfc", "smc", "md", "gen", "nes", "fds", "ngp", "ngc", "pce", "sgx", "z64", "n64", "v64", "nds", "3ds", "cci", "rvz", "gcm", "wbfs", "xiso", "vpk", "apk", "gg", "sms", "32x", "a26", "a78", "col", "rom", "lnx", "ws", "wsc"}
DAT_FILES = {
    "gba": ["Nintendo - Game Boy Advance.dat"],
    "gb": ["Nintendo - Game Boy.dat"],
    "gbc": ["Nintendo - Game Boy Color.dat"],
    "snes": ["Nintendo - Super Nintendo Entertainment System.dat"],
    "genesis": ["Sega - Mega Drive - Genesis.dat"],
    "ngp": ["SNK - Neo Geo Pocket.dat", "SNK - Neo Geo Pocket Color.dat"],
    "ngpc": ["SNK - Neo Geo Pocket Color.dat"],
    "pcengine": ["NEC - PC Engine - TurboGrafx 16.dat"],
    "supergrafx": ["NEC - PC Engine SuperGrafx.dat"],
    "gamegear": ["Sega - Game Gear.dat"],
    "mastersystem": ["Sega - Master System - Mark III.dat"],
}
PRIVATE = re.compile(
    r"/(?:var/)?home/|/(?:storage|sdcard)/|\b(?:swatson|Pocket DMG|AYN Thor)\b|"
    r"\byour (?:library|lane|list|genres|tastes?|thor|roms?|fighter shelf)\b|"
    r"\b(?:in|not in) the library\b|\byou (?:also have|said)\b|"
    r"\b(?:on|for|tested on|not tested on) (?:the )?thor\b|"
    r"\b(?:supplied archive|this copy|this archive|pending cleanup|duplicate of the|on this card|tested here)\b|"
    r"\bI (?:could|can|have|had|think|found|was|am|tested|checked|keep)\b|"
    r"\b(?:cracked|raw|unverified) dump\b|\bfile may be\b", re.I
)
PRIVATE_FLAG = re.compile(r"^(?:duplicate|check|disc \d|missing disc.*|.*not in library.*|not tested on thor)$", re.I)
ENTRY_FIELDS = {"id", "system", "title", "aliases", "hook", "controls", "flags", "parentId", "fingerprints", "externalIds", "sources"}
SOURCE_FIELDS = {"kind", "name", "url", "version", "sha256", "release"}
FINGERPRINT_FIELDS = {"algorithm", "value", "representation", "size", "verification", "source"}
SHARED_ENTRYPOINTS = {"game.exe", "game.ini", "game.rgssad", "game.rgss2a", "game.rgss3a", "rpg_rt.exe", "rpg_rt.ldb", "rpg_rt.lmt"}


def canonical(system):
    system = system.lower()
    return "arcade" if system in ARCADE else PLATFORM_ALIASES.get(system, system)


def stem(name):
    base, dot, extension = name.rpartition(".")
    return base if dot and extension.lower() in EXTENSIONS else name


def release_aliases(name):
    """Keep the full name and recognized suffix chain, never package-path stems."""
    aliases = {name}
    if "/" not in name:
        while True:
            shorter = stem(name)
            if shorter == name:
                break
            aliases.add(shorter)
            name = shorter
    return sorted(aliases)


def encoded(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def revision(entries):
    return hashlib.sha256(encoded(entries)).hexdigest()


def read_jsonl(path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip() and not line.lstrip().startswith("//")]


def public_text(text):
    if not isinstance(text, str) or PRIVATE.search(text):
        raise ValueError("non-text or private/library-specific wording requires editorial curation")
    return text.strip()


def exact_alias(value):
    public_text(value)
    if (not value or value != value.strip() or "\\" in value or re.match(r"^[A-Za-z]:", value)
            or "://" in value or any(ord(char) < 32 for char in value)
            or any(part in {"", ".", ".."} for part in value.split("/"))):
        raise ValueError("aliases must be exact safe system-relative paths, never absolute paths or traversal")
    if value.lower() in SHARED_ENTRYPOINTS:
        raise ValueError("shared engine entrypoints require an exact game-package relative path")
    return value


def public_url(url):
    parsed = urlsplit(url)
    host = parsed.hostname or ""
    if parsed.scheme not in {"http", "https"} or not host or parsed.username or parsed.password:
        raise ValueError("evidence URL must be a public HTTP(S) URL")
    import ipaddress
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        if host == "localhost" or "." not in host or host.endswith((".local", ".lan", ".internal")):
            raise ValueError("private evidence URL")
    else:
        if not address.is_global:
            raise ValueError("private evidence URL")
    return url


def sources_from(values):
    result = []
    for value in values:
        source = {"kind": "research", "name": "Editorial research", "url": value} if isinstance(value, str) else dict(value)
        if not source.get("kind") or not source.get("name"):
            raise ValueError("source kind and name are required")
        if set(source) - SOURCE_FIELDS:
            raise ValueError("unsupported source fields (do not publish private evidence paths)")
        for key, text in source.items():
            public_url(text) if key == "url" else public_text(text)
        result.append(source)
    return result


def unique(values):
    return [json.loads(value) for value in sorted({encoded(v).decode("utf-8") for v in values})]


def load_dat(directory):
    """Return exact release records and systems whose DAT evidence was refreshed."""
    result, refreshed_systems = {}, set()
    if directory is None:
        return result, refreshed_systems
    for system, names in DAT_FILES.items():
        for filename in names:
            path = directory / filename
            if not path.is_file():
                continue
            refreshed_systems.add(system)
            raw = path.read_bytes()
            text = raw.decode("utf-8-sig")
            header = text.split("game (", 1)[0]
            version_match = re.search(r'\bversion\s+"([^"]+)"', header)
            version = version_match.group(1) if version_match else "unspecified"
            source = {"kind": "dat", "name": path.stem, "version": version,
                      "sha256": hashlib.sha256(raw).hexdigest(),
                      "url": "https://github.com/robloach/libretro-dats"}
            for block in re.findall(r"^game \(\s*\n(.*?)^\)", text, re.M | re.S):
                name = re.search(r'^\s*name\s+"([^"]+)"', block, re.M)
                roms = re.findall(r"^\s*rom \( (.*) \)\s*$", block, re.M)
                if not name or len(roms) != 1:
                    continue
                fields = dict((key, quoted or bare) for key, quoted, bare in re.findall(r'(\w+)\s+(?:"([^"]*)"|([^\s()]+))', roms[0]))
                if not re.fullmatch(r"[0-9a-fA-F]{8}", fields.get("crc", "")) or not fields.get("size", "").isdigit():
                    continue
                if int(fields["size"]) <= 0 or fields.get("status") in {"baddump", "nodump"}:
                    continue
                fingerprint = {"algorithm": "crc32", "value": fields["crc"].lower(), "representation": "raw",
                               "size": int(fields["size"]), "verification": "dat-release",
                               "source": f"{path.stem} ({version})"}
                evidence = dict(source, release=name.group(1))
                for alias in {name.group(1), fields.get("name", "")} - {""}:
                    result.setdefault((system, alias), []).append((fingerprint, evidence))
    return result, refreshed_systems


def validate(document):
    if set(document) != {"schemaVersion", "revision", "entries"} or document["schemaVersion"] != 1:
        raise ValueError("unsupported catalog schema")
    entries = document["entries"]
    if document["revision"] != revision(entries):
        raise ValueError("catalog revision does not describe its content")
    ids, aliases, fingerprints = set(), {}, {}
    for entry in entries:
        if set(entry) - ENTRY_FIELDS:
            raise ValueError("unsupported/private catalog field")
        identity = entry["id"]
        if str(uuid.UUID(identity)) != identity or identity in ids:
            raise ValueError("invalid or duplicate UUID")
        ids.add(identity)
        system = entry["system"]
        if not re.fullmatch(r"[a-z0-9][a-z0-9-]*", system) or canonical(system) != system:
            raise ValueError("noncanonical platform")
        for key in ("title", "hook"):
            if not public_text(entry[key]):
                raise ValueError(f"empty {key}")
        public_text(entry.get("controls", ""))
        for flag in entry.get("flags", []):
            if not public_text(flag) or PRIVATE_FLAG.fullmatch(flag):
                raise ValueError("private or empty flag")
        if not entry["aliases"] or len(set(entry["aliases"])) != len(entry["aliases"]):
            raise ValueError("missing or repeated aliases")
        for alias in entry["aliases"]:
            exact_alias(alias)
            key = system, alias.lower()
            if key in aliases and aliases[key] != identity:
                raise ValueError(f"ambiguous alias: {system}/{alias}")
            aliases[key] = identity
        if not entry.get("sources"):
            raise ValueError("provenance is required")
        sources_from(entry["sources"])
        for fp in entry.get("fingerprints", []):
            if set(fp) != FINGERPRINT_FIELDS or fp["algorithm"] != "crc32" or not re.fullmatch(r"[0-9a-f]{8}", fp["value"]):
                raise ValueError("unsupported fingerprint")
            if fp["representation"] not in {"raw", "nes-prg-chr"} or fp["verification"] not in {"dat-release", "payload-verified"}:
                raise ValueError("fingerprint representation/evidence is required")
            if type(fp["size"]) is not int or fp["size"] <= 0 or not public_text(fp["source"]):
                raise ValueError("fingerprint size/source is required")
            key = system, fp["representation"], fp["value"], fp["size"]
            if key in fingerprints and fingerprints[key] != identity:
                raise ValueError(f"ambiguous fingerprint: {system}/{fp['value']}")
            fingerprints[key] = identity
        for provider, value in entry.get("externalIds", {}).items():
            if not public_text(provider) or not public_text(value):
                raise ValueError("invalid external identifier")
    parents = {e["id"]: e.get("parentId") for e in entries}
    for identity, parent in parents.items():
        seen = {identity}
        while parent:
            if parent not in ids or parent in seen:
                raise ValueError("missing/cyclic variant parent")
            seen.add(parent)
            parent = parents[parent]


def export(notes, inventory, decisions, previous, dat, evidence=(), refreshed_systems=None):
    metadata = {(r.get("s", r.get("sys")), r.get("f", r.get("file"))): r for r in inventory}
    edits = {(r["system"], r["alias"]): r for r in decisions}
    if len(edits) != len(decisions):
        raise ValueError("duplicate curation identity")
    research = {}
    for row in evidence:
        research.setdefault((row["sys"], row["file"]), []).extend(row.get("sources", []))
    entries = {e["id"]: copy.deepcopy(e) for e in previous.get("entries", [])}
    # Backfill newly supported container stems before consulting any new input.
    # This is an alias repair on the published UUID, never a second allocation.
    by_alias = {}
    for entry in entries.values():
        entry["aliases"] = sorted({candidate for alias in entry["aliases"]
                                   for candidate in release_aliases(alias)})
        for alias in entry["aliases"]:
            key = entry["system"], alias.lower()
            if key in by_alias and by_alias[key] != entry["id"]:
                raise ValueError(f"stem repair joins published identities: {key}")
            by_alias[key] = entry["id"]
    seen, updated, excluded = set(), set(), 0
    for note in sorted(notes, key=lambda r: (r["sys"], r["file"])):
        local = note["sys"], note["file"]
        if local in seen:
            raise ValueError(f"duplicate input identity: {local}")
        seen.add(local)
        edit = edits.get(local, {})
        system, filename = canonical(note["sys"]), note["file"]
        if edit.get("exclude"):
            excluded += 1
            continue
        exact_alias(filename)
        nested = "/" in filename
        aliases = release_aliases(filename)
        pinned = {by_alias[(system, a.lower())] for a in aliases if (system, a.lower()) in by_alias}
        if len(pinned) > 1:
            raise ValueError(f"aliases join multiple identities: {local}")
        identity = next(iter(pinned), str(uuid.uuid5(NAMESPACE, system + "\n" + filename)))
        old = entries.get(identity, {})
        flags = edit.get("flags", [f for f in note.get("flags", []) if f not in edit.get("dropFlags", [])] + edit.get("addFlags", []))
        meta = metadata.get(local, {})
        # Display titles, including reviewed corrections, never create aliases.
        title = edit.get("title", meta.get("t") if system == "arcade" or nested else stem(filename))
        if "title" in edit and not public_text(title):
            raise ValueError(f"curated title must not be empty: {local}")
        if nested and not title:
            raise ValueError(f"nested game package needs an explicit display title: {local}")
        entry = dict(old, id=identity, system=system, title=title or stem(filename),
                     aliases=sorted(set(old.get("aliases", []) + aliases)),
                     hook=edit.get("hook", note.get("hook", "")).strip())
        for key, value in (("controls", edit.get("controls", note.get("controls", "")).strip()), ("flags", sorted(set(flags)))):
            if value:
                entry[key] = value
            else:
                entry.pop(key, None)
        sources = old.get("sources", []) + [
            {"kind": "editorial", "name": "Sortie contributors: original AI-assisted editorial note"},
            {"kind": "alias", "name": ("Exact game-package relative path; not an engine fingerprint" if nested
                                      else "Exact authored release basename; not a local checksum verification")},
        ]
        sources += sources_from(note.get("sources", []) + research.get(local, []))
        identity_source = meta.get("identity_source", "")
        if identity_source.startswith(("https://", "http://")):
            sources += [{"kind": "identity", "name": "Release identity reference", "url": public_url(identity_source)}]
        entry["sources"] = unique(sources)
        if identity in updated:
            if any(entries[identity].get(k) != entry.get(k) for k in ("hook", "controls", "flags")):
                raise ValueError(f"conflicting notes for one exact release: {local}; curate explicitly")
        entries[identity] = entry
        updated.add(identity)
        for alias in entry["aliases"]:
            by_alias[system, alias.lower()] = identity
    # Refresh every retained entry on the platforms actually supplied, including
    # community entries absent from the private import. Historical citations stay
    # as provenance, but superseded expected checksums must not remain matchable.
    if refreshed_systems is None:
        refreshed_systems = {system for system, _ in dat}
    for entry in entries.values():
        if entry["system"] not in refreshed_systems:
            continue
        matches = unique([{"fingerprint": fp, "source": src} for alias in entry["aliases"]
                          for fp, src in dat.get((entry["system"], alias), [])])
        payloads = {(m["fingerprint"]["algorithm"], m["fingerprint"]["representation"],
                     m["fingerprint"]["value"], m["fingerprint"]["size"]) for m in matches}
        retained = [fp for fp in entry.get("fingerprints", []) if fp["verification"] != "dat-release"]
        if len(payloads) == 1:
            retained += [m["fingerprint"] for m in matches]
        if retained:
            entry["fingerprints"] = unique(retained)
        else:
            entry.pop("fingerprints", None)
        entry["sources"] = unique(entry["sources"] + [m["source"] for m in matches])
    # Equal hashes on separately identified releases are deliberately not usable.
    owners = {}
    for entry in entries.values():
        for fp in entry.get("fingerprints", []):
            owners.setdefault((entry["system"], fp["representation"], fp["value"], fp["size"]), set()).add(entry["id"])
    for entry in entries.values():
        usable = [fp for fp in entry.get("fingerprints", []) if len(owners[entry["system"], fp["representation"], fp["value"], fp["size"]]) == 1]
        if usable:
            entry["fingerprints"] = usable
        else:
            entry.pop("fingerprints", None)
    ordered = sorted(entries.values(), key=lambda e: (e["system"], e["id"]))
    document = {"schemaVersion": 1, "revision": revision(ordered), "entries": ordered}
    validate(document)
    return document, excluded


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", nargs="?", type=Path)
    parser.add_argument("--inventory", type=Path, help="full inventory JSONL, or window.GUIDE data.js")
    parser.add_argument("--dats", type=Path, help="optional directory of supported clrmamepro DATs")
    parser.add_argument("--evidence", type=Path, action="append", default=[], help="JSONL with sys/file/sources")
    parser.add_argument("--output", type=Path, default=CATALOG)
    parser.add_argument("--curation", type=Path, default=CURATION)
    parser.add_argument("--check", action="store_true", help="compare regenerated content without writing")
    parser.add_argument("--validate", action="store_true", help="validate committed catalog without private inputs")
    args = parser.parse_args()
    previous = json.loads(args.output.read_text(encoding="utf-8")) if args.output.exists() else {"entries": []}
    if args.validate:
        validate(previous)
        print(f"Valid catalog: {len(previous['entries'])} entries")
        return
    if args.source is None or args.inventory is None:
        parser.error("source and --inventory are required unless --validate is used")
    if previous["entries"]:
        validate(previous)
    text = args.inventory.read_text(encoding="utf-8")
    inventory = json.loads(text.removeprefix("window.GUIDE=").rstrip(";\n"))["games"] if text.startswith("window.GUIDE=") else read_jsonl(args.inventory)
    decisions = json.loads(args.curation.read_text(encoding="utf-8"))["decisions"]
    evidence = [row for path in args.evidence for row in read_jsonl(path)]
    dat, refreshed_systems = load_dat(args.dats)
    document, excluded = export(read_jsonl(args.source), inventory, decisions, previous, dat, evidence, refreshed_systems)
    content = json.dumps(document, ensure_ascii=False, indent=2) + "\n"
    if args.check:
        if not args.output.exists() or args.output.read_text(encoding="utf-8") != content:
            raise ValueError("catalog differs; regenerate and review")
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(content, encoding="utf-8")
    print(f"Catalog: {len(document['entries'])} identities; {sum(bool(e.get('fingerprints')) for e in document['entries'])} fingerprinted; {excluded} excluded; revision {document['revision']}")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, TypeError) as error:
        sys.exit(f"import-blurbs: {error}")
