#!/usr/bin/env python3
"""Build Sortie's bundled curated guide from an authored blurbs.jsonl, with every source record accounted for.

    tools/guide/import-blurbs.py SOURCE.jsonl [--private-report FILE] [--check]

SOURCE is the curator's private hand-written guide (one JSON object per line: sys, file, fit, hook, flags, controls; lines
starting with // are comments). It is read, never written. The output is reproducible from SOURCE plus tools/guide/curation.json:

  app/src/main/assets/guide/catalog.json   the bundled catalogue (format 1), shipped in every APK
  tools/guide/import-report.json           public accounting: one entry per source line (line numbers of the snapshot whose
                                           SHA-256 it records); no per-record digests and never file names

What is published, per record: the authored hook, control notes and flags, attached to a portable identity. What is not: file
names, extensions, dump-tool tags, local folder names (arcade folders become the portable "arcade" platform and match by
romset), the curator's fit scores, and automatically generated taste tags (the source has none; build_guide.py adds them).
curation.json holds the editorial decisions, keyed by source line and checked against the public platform/title of
that line and the source snapshot hash: neutral rewording of personal or library-specific sentences, flags that only
made sense in the curator's library, excluded maintenance notes and justified shared work ids. The private report (written
outside the repository) shows original next to published text so the curator can review those edits.

Identity. A variant is one platform/edition of a work and answers to its matchNames, compared by the app with the magazine
rules (GameNames.key: case, punctuation, accents, ", The", region, language, disc, revision and reprint tags are ignored;
edition, hack and translation qualifiers are kept word for word). Source records that share platform and key are one
variant: disc companions and library duplicates merge into the record that describes the game. A work id is the slug of the
title without any tags (arcade: the romset), so editions, patches and translations of one game share it. A title that occurs
on several platforms is never merged by name: each gets a platform-qualified work id unless curation.json names a shared work
with a reason. Ids already present in the existing catalogue are kept, so a later title or alias change never renames them.
Nothing here uses the network.
"""

import argparse
import hashlib
import json
import re
import sys
import unicodedata
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
CATALOG = REPO / "app" / "src" / "main" / "assets" / "guide" / "catalog.json"
CURATION = Path(__file__).resolve().parent / "curation.json"
REPORT = Path(__file__).resolve().parent / "import-report.json"
FORMAT = 1

# Local folders whose files are arcade romsets: published under the portable "arcade" platform (the app's SystemFamilies).
ARCADE_FOLDERS = {"arcade", "mame", "fbneo", "neogeo", "cps1", "cps2", "cps3", "toaplan-cave-stg", "stv", "model2", "naomi", "atomiswave"}
# Dump-tool, container and serial tags that say how a file was made, not which edition it is; never published.
DUMP_TAGS = re.compile(r"\s*(?:\[(?:vita3k|NoNpDrm)\]|\(PC[A-Z]{2}\d{5}\)|\(Unverified\))", re.IGNORECASE)
XISO = re.compile(r"\.xiso$", re.IGNORECASE)
# Distribution tags that are public No-Intro naming: published, plus a match name without them.
DISTRIBUTION_TAGS = re.compile(r"\s*\((?:eShop)\)", re.IGNORECASE)
# Flags that describe the curator's library rather than the game; each must be resolved in curation.json.
PRIVATE_FLAGS = re.compile(r"^(?:duplicate|check|disc \d|missing disc.*|.*not in library.*|not tested on thor)$", re.IGNORECASE)
DISC_COMPANION = re.compile(r"^disc \d+$", re.IGNORECASE)
# Wording that must never ship (published hooks, controls and flags). Same spirit as publish-magazines.py PRIVATE.
PRIVATE_TEXT = [
    (re.compile(r"(?<![\w.])/(?:var/)?home/[^/\s\"]+/"), "a home-directory path"),
    (re.compile(r"(?<![\w.])/(?:storage|sdcard)/"), "a device storage path"),
    (re.compile(r"\byour (?:library|lane|list|genres|tastes?|thor|roms?|fighter shelf)\b", re.IGNORECASE), "personal-library or taste framing"),
    (re.compile(r"\b(?:in|not in) the library\b", re.IGNORECASE), "personal-library framing"),
    (re.compile(r"\byou (?:also have|said)\b", re.IGNORECASE), "personal framing"),
    (re.compile(r"\b(?:on|for|tested on|not tested on) (?:the )?thor\b", re.IGNORECASE), "the curator's device"),
    (re.compile(r"\bI (?:could|can|have|had|think|found|was|am|tested|checked|keep)\b"), "first-person curator voice"),
    (re.compile(r"\b(?:supplied archive|this copy|pending cleanup|duplicate of the|keep (?:it|that one|the v))\b", re.IGNORECASE), "a note about the curator's files"),
    (re.compile(r"\b(?:tested here|on this card)\b", re.IGNORECASE), "the curator's own setup"),
    (re.compile(r"\b(?:cracked|raw|unverified) dump\b|\bfile may be\b", re.IGNORECASE), "a note about the curator's dump"),
]


# ---- GameNames port (app/src/main/java/io/github/esko997/sortie/data/magazine/MagazineGames.kt); keep in step with it ----
GROUP = re.compile(r"[(\[]([^()\[\]]*)[)\]]")
ARTICLE = re.compile(r"(.*?),\s+(the|a|an)(?=$|\s+-\s|\s*:)(.*)", re.IGNORECASE)
EXTENSION = re.compile(r"\.[A-Za-z0-9]{1,5}$")
CONTAINER_SUFFIX = re.compile(r"\.(nkit|dec|enc)$", re.IGNORECASE)
APOSTROPHES = re.compile(r"['’`´]")
SPACES = re.compile(r"\s+")
VERSION = re.compile(r"(?:v\d[\w.]*|r\d+|\d+(?:\.\d+)*[a-z]?|\d+(?:st|nd|rd|th)|[a-gijksuw]|m\d+|[bofa]\d+)")
NEUTRAL = set("""usa us america europe eu japan jp jpn world asia korea china taiwan hong kong brazil australia canada france
germany spain italy uk netherlands sweden scandinavia russia en ja fr de es it nl pt sv no da fi zh ko ru pl ntsc pal secam ue
ju jue rev revision ver version alt alternate set disc disk cd side of track greatest hits platinum players player choice best
the playstation classics essentials reprint budget psn jan january feb february mar march apr april may jun june jul july aug
august sep sept september oct october nov november dec december mon tue wed thu fri sat sun""".split())


def _marks(s):
    return "".join(c for c in unicodedata.normalize("NFKD", s) if not unicodedata.category(c).startswith("M"))


def _words(s):
    s = APOSTROPHES.sub("", s.lower().replace("&", " and "))
    return " ".join("".join(c if (c.isalpha() or c.isnumeric()) else " " for c in s).split())


def _reorder(s):
    m = ARTICLE.fullmatch(s)
    return f"{m.group(2)} {m.group(1)}{m.group(3)}" if m else s


def _qualifier(content):
    kept = []
    for part in re.split(r"[,/;]", content):
        token = _words(part)
        if token and any(w not in NEUTRAL and not VERSION.fullmatch(w) for w in token.split(" ")):
            kept.append(token)
    return " ".join(kept) or None


def stem(file_name):
    return CONTAINER_SUFFIX.sub("", EXTENSION.sub("", file_name.strip()))


def key(name):
    qualifiers = set()

    def tag(m):
        q = _qualifier(m.group(1))
        if q:
            qualifiers.add(q)
        return " "

    main = GROUP.sub(tag, _marks(name))
    title = _words(_reorder(SPACES.sub(" ", main).strip()))
    return " ".join(x for x in [title] + sorted(qualifiers) if x)


def display_title(st):
    kept = GROUP.sub(lambda m: " " if _qualifier(m.group(1)) is None else f" {m.group(0)} ", st)
    parts = SPACES.sub(" ", kept).strip()
    first = next((i for i, c in enumerate(parts) if c in "(["), -1)
    head, tail = (parts[:first].strip(), " " + parts[first:]) if first > 0 else (parts, "")
    return (_reorder(head) + tail) or st


def slug(s):
    s = APOSTROPHES.sub("", _marks(s).lower().replace("&", " and "))
    return re.sub(r"[^a-z0-9]+", "-", s).strip("-")
# ---- end of GameNames port ----


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def fail(msg):
    sys.exit(f"import-blurbs: {msg}")


def read_source(path):
    raw = path.read_bytes()
    lines = raw.decode("utf-8").split("\n")
    if lines and lines[-1] == "":
        lines.pop()
    records, comments, blanks = [], 0, 0
    for n, line in enumerate(lines, 1):
        s = line.strip()
        if not s:
            blanks += 1
            continue
        if s.startswith("//"):
            comments += 1
            continue
        try:
            obj = json.loads(s)
        except ValueError as e:
            fail(f"source line {n} is not JSON: {e}")
        if not isinstance(obj, dict) or not isinstance(obj.get("sys"), str) or not isinstance(obj.get("file"), str):
            fail(f"source line {n} has no sys/file")
        records.append({"line": n, "rec": obj})
    return raw, len(lines), comments, blanks, records


def read_curation(source_sha, records):
    """Decisions by source line. They apply only to the source snapshot they were written for, and every decision with a public
    identity must name the platform and title its line actually has, so a reordered source cannot redirect one."""
    doc = json.loads(CURATION.read_text(encoding="utf-8"))
    pinned = doc.get("source", {}).get("sha256")
    if pinned != source_sha:
        fail(f"curation.json was written for source {pinned}, not {source_sha}. Review every decision's line against the new "
             "source (excluded lines have no public identity to check), then update source.sha256 in curation.json")
    by_line = {r["line"]: r["rec"] for r in records}
    by = {}
    for d in doc["decisions"]:
        line = d.get("line")
        if line not in by_line:
            fail(f"curation.json names line {line}, which is not a record in this source")
        if "exclude" not in d:
            platform, shown = identity(by_line[line])[:2]
            if (d.get("platform"), d.get("title")) != (platform, shown):
                fail(f"curation.json line {line} expects {d.get('platform')}/{d.get('title')!r}, but that line is {platform}/{shown!r}")
        by.setdefault(line, []).append(d)
    return by


def existing_ids():
    """(platform, key) -> (work id, variant id) from the catalogue being replaced, so published ids never change."""
    if not CATALOG.is_file():
        return {}
    doc = json.loads(CATALOG.read_text(encoding="utf-8"))
    ids = {}
    for w in doc.get("works", []):
        for v in w.get("variants", []):
            for name in v.get("matchNames", []):
                ids[(v["platform"], key(name))] = (w["id"], v["id"])
    return ids


def identity(rec):
    """(platform, display title, work title, edition, match names) for one source record. No file name survives."""
    folder = rec["sys"]
    st = stem(rec["file"])
    if folder in ARCADE_FOLDERS:
        return "arcade", st, st, "", [st]
    clean = SPACES.sub(" ", DUMP_TAGS.sub("", XISO.sub("", st))).strip()
    shown = display_title(clean)
    work_title = _reorder(SPACES.sub(" ", GROUP.sub(" ", clean)).strip())
    edition = ", ".join(m.group(1).strip() for m in GROUP.finditer(shown))
    names = [shown]
    plain = SPACES.sub(" ", DISTRIBUTION_TAGS.sub("", clean)).strip()
    if plain != clean:
        names.append(display_title(plain))
    return folder, shown, work_title, edition, names


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("source", type=Path)
    ap.add_argument("--private-report", type=Path, help="original-vs-published review file; keep it outside the repository")
    ap.add_argument("--check", action="store_true", help="verify that the committed outputs are current; write nothing")
    a = ap.parse_args()
    if a.private_report and REPO in a.private_report.resolve().parents:
        fail("the private report must not be written inside the repository")

    raw, n_lines, comments, blanks, records = read_source(a.source)
    curation = read_curation(sha256(raw), records)
    pinned = existing_ids()

    # 1. Each record: decisions, published fields, identity.
    rows = []
    for r in records:
        rec, decisions = r["rec"], curation.get(r["line"], [])
        row = {"line": r["line"], "rec": rec, "decisions": decisions, "reasons": [], "edited": []}
        excluded = [d["exclude"] for d in decisions if "exclude" in d]
        if excluded:
            row["disposition"], row["reasons"] = "excluded", excluded
            rows.append(row)
            continue
        hook, controls = rec.get("hook", "").strip(), rec.get("controls", "").strip()
        flags = [f.strip() for f in rec.get("flags", []) if isinstance(f, str) and f.strip()]
        work_override = None
        for d in decisions:
            if "hook" in d:
                hook = d["hook"]
                row["edited"].append("hook")
            if "controls" in d:
                controls = d["controls"]
                row["edited"].append("controls")
            for f in d.get("dropFlags", []):
                if f not in flags:
                    fail(f"line {r['line']}: dropFlags names {f!r}, which the record does not have")
                flags.remove(f)
                row["edited"].append(f"flag -{f}")
            for f in d.get("addFlags", []):
                flags.append(f)
                row["edited"].append(f"flag +{f}")
            if "work" in d:
                work_override = d["work"]
            if "reason" in d:
                row["reasons"].append(d["reason"])
        platform, shown, work_title, edition, names = identity(rec)
        row.update(hook=hook, controls=controls, flags=flags, platform=platform, shown=shown, work_title=work_title,
                   edition=edition, names=names, key=key(names[0]), work_override=work_override)
        rows.append(row)

    # 2. Records with the same platform and key are one variant; the describing record is primary.
    groups = {}
    for row in rows:
        if row.get("disposition") != "excluded":
            groups.setdefault((row["platform"], row["key"]), []).append(row)
    conflicts = []
    for (platform, k), members in groups.items():
        def companion(row):
            return any(f.lower() == "duplicate" or DISC_COMPANION.match(f) for f in row["rec"].get("flags", []))
        primary = next((m for m in members if not companion(m)), members[0])
        primary["disposition"] = "edited" if primary["edited"] else "published"
        for m in members:
            if m is primary:
                continue
            why = "disc companion" if any(DISC_COMPANION.match(f) for f in m["rec"].get("flags", [])) else "library copy of the same edition"
            m["disposition"], m["mergedInto"] = "merged", primary["line"]
            m["reasons"].append(f"Same platform and match key as line {primary['line']} ({why}); that record's prose describes the game.")
        if len(members) > 1:
            hooks = {m["rec"].get("hook", "").strip() for m in members}
            conflicts.append({
                "platform": platform, "matchKey": k, "records": [m["line"] for m in members], "primary": primary["line"],
                "distinctSourceHooks": len(hooks), "resolution": "primary record's hook published; others merged",
            })

    # 3. Ids: pinned ids first, then work slugs (platform-qualified when a title spans platforms without a curated work).
    variants = []
    for (platform, k), members in groups.items():
        p = next(m for m in members if m["disposition"] in ("published", "edited"))
        variants.append(p)
    by_slug = {}
    for v in variants:
        v["work_slug"] = v["work_override"] or slug(v["work_title"])
        by_slug.setdefault(v["work_slug"], set()).add(v["platform"])
    for v in variants:
        pin = next((pinned[(v["platform"], key(n))] for n in v["names"] if (v["platform"], key(n)) in pinned), None)
        if pin:
            v["work_id"], v["variant_id"] = pin
            continue
        spans = len(by_slug[v["work_slug"]]) > 1 and not v["work_override"]
        v["work_id"] = f"{v['work_slug']}-{slug(v['platform'])}" if spans else v["work_slug"]
        v["variant_id"] = f"{slug(v['platform'])}-{slug(v['shown'])}"
    seen = {}
    for v in variants:
        if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,119}", v["variant_id"]) or not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,119}", v["work_id"]):
            fail(f"line {v['line']}: unusable id {v['work_id']} / {v['variant_id']}")
        if v["variant_id"] in seen:
            fail(f"lines {seen[v['variant_id']]} and {v['line']} both produce variant id {v['variant_id']}")
        seen[v["variant_id"]] = v["line"]
        if not v["hook"]:
            fail(f"line {v['line']}: no hook to publish")
        for f in v["flags"]:
            if PRIVATE_FLAGS.match(f):
                fail(f"line {v['line']}: library-relative flag {f!r} needs a decision in curation.json")
        for text in [v["hook"], v["controls"], *v["flags"]]:
            for rx, what in PRIVATE_TEXT:
                if rx.search(text):
                    fail(f"line {v['line']}: published text contains {what}: {text!r}")
    # The app's "arcade" covers every arcade-generation system, so it overlaps those platforms as well as itself.
    arcade_family = ARCADE_FOLDERS | {"neogeocd"}

    def overlap(a, b):
        return a == b or (a == "arcade" and b in arcade_family) or (b == "arcade" and a in arcade_family)

    claims = {}
    for v in variants:
        for n in v["names"]:
            for other in claims.setdefault(key(n), []):
                if other["variant_id"] != v["variant_id"] and overlap(other["platform"], v["platform"]):
                    fail(f"match name {n!r} answers to both {other['variant_id']} and {v['variant_id']}")
            claims[key(n)].append(v)

    # 4. Catalogue: works sorted by id, variants by id; no file names, fit scores or generated tags.
    works = {}
    for v in sorted(variants, key=lambda v: v["variant_id"]):
        w = works.setdefault(v["work_id"], {"id": v["work_id"], "title": v["work_title"], "variants": []})
        entry = {"id": v["variant_id"], "platform": v["platform"], "title": v["shown"]}
        if v["edition"]:
            entry["edition"] = v["edition"]
        entry["matchNames"] = v["names"]
        entry["hook"] = v["hook"]
        if v["controls"]:
            entry["controls"] = v["controls"]
        if v["flags"]:
            entry["flags"] = v["flags"]
        w["variants"].append(entry)
    for w in works.values():
        titles = {x["title"] for x in w["variants"]}
        if len({x["platform"] for x in w["variants"]}) > 1 or len(titles) > 1:
            # the shortest title, preferring a real title over an arcade romset name
            w["title"] = min(((v["platform"] == "arcade", len(v["work_title"]), v["work_title"]) for v in variants if v["work_id"] == w["id"]))[2]
    for v in variants:
        if v["work_override"] and sum(1 for x in variants if x["work_id"] == v["work_id"]) < 2:
            fail(f"line {v['line']}: curated work {v['work_id']} joins nothing; drop the decision or fix the id")
    counts = {d: sum(1 for r in rows if r["disposition"] == d) for d in ("published", "edited", "merged", "excluded")}
    if sum(counts.values()) != len(records):
        fail("accounting does not add up")
    source = {
        "sha256": sha256(raw), "bytes": len(raw), "lines": n_lines, "commentLines": comments, "blankLines": blanks,
        "records": len(records), **counts,
    }
    catalog = {"format": FORMAT, "source": source, "works": [works[k] for k in sorted(works)]}
    catalog_text = json.dumps(catalog, ensure_ascii=False, indent=1) + "\n"
    repeated = {}
    for v in variants:
        repeated.setdefault(v["hook"], []).append(v["variant_id"])

    report = {
        "format": 1,
        "about": "Accounting for every authored source record by its line in the source snapshot named by 'source' (whole-file SHA-256); "
                 "no per-record digests and never file names. "
                 "published: hook, controls and flags as authored. edited: published after the curation.json decision "
                 "named by 'reasons'. merged: same platform and match key as 'mergedInto', whose prose describes the game. "
                 "excluded: not published, for the stated reason. Fit scores are never published.",
        "source": source,
        "catalog": {"works": len(works), "variants": len(variants), "sha256": sha256(catalog_text.encode("utf-8"))},
        # several source records that became one variant: which line's prose was published, and whether the merged hooks differed
        "sharedVariants": sorted(conflicts, key=lambda c: c["primary"]),
        # identical published hooks on different variants (short genre notes such as "Football."): kept, each variant is its own game
        "repeatedHooks": sorted(
            ({"hook": h, "variants": sorted(ids)} for h, ids in repeated.items() if len(ids) > 1),
            key=lambda g: (-len(g["variants"]), g["hook"]),
        ),
        "records": [{
            "line": r["line"], "disposition": r["disposition"],
            **({"variant": next(v["variant_id"] for v in variants
                                if (v["platform"], v["key"]) == (r["platform"], r["key"]))} if r["disposition"] != "excluded" else {}),
            **({"mergedInto": r["mergedInto"]} if "mergedInto" in r else {}),
            **({"edited": r["edited"]} if r["edited"] else {}),
            **({"reasons": r["reasons"]} if r["reasons"] else {}),
        } for r in rows],
    }
    report_text = json.dumps(report, ensure_ascii=False, indent=1) + "\n"

    if a.check:
        stale = [p for p, t in ((CATALOG, catalog_text), (REPORT, report_text)) if not p.is_file() or p.read_text(encoding="utf-8") != t]
        if stale:
            fail("out of date: " + ", ".join(str(p.relative_to(REPO)) for p in stale))
        print(f"current: {len(variants)} variants in {len(works)} works from {len(records)} records")
        return
    CATALOG.parent.mkdir(parents=True, exist_ok=True)
    CATALOG.write_text(catalog_text, encoding="utf-8")
    REPORT.write_text(report_text, encoding="utf-8")
    if a.private_report:
        private = [{
            "line": r["line"], "disposition": r["disposition"], "sys": r["rec"]["sys"], "file": r["rec"]["file"],
            "originalHook": r["rec"].get("hook", ""), "originalControls": r["rec"].get("controls", ""), "originalFlags": r["rec"].get("flags", []),
            **({"publishedHook": r["hook"], "publishedControls": r["controls"], "publishedFlags": r["flags"]} if r["disposition"] == "edited" else {}),
            "reasons": r["reasons"],
        } for r in rows if r["disposition"] != "published"]
        a.private_report.parent.mkdir(parents=True, exist_ok=True)
        a.private_report.write_text(json.dumps(private, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"{len(records)} records: {counts['published']} published, {counts['edited']} edited, {counts['merged']} merged, "
          f"{counts['excluded']} excluded -> {len(variants)} variants in {len(works)} works")


if __name__ == "__main__":
    main()
