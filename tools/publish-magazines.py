#!/usr/bin/env python3
"""Validate offline magazine packs and publish them into the separate magazine content repository.

    tools/publish-magazines.py SOURCE_ROOT --check
    tools/publish-magazines.py SOURCE_ROOT --out DIR [--pin]

SOURCE_ROOT holds one folder per issue (<issue-id>/issue.json, articles/, media/). Every issue must be schema version 2
with a revision and a rights notice; game references must be well formed and every reference id must have the same title,
platform and edition in every article and issue (match names may differ; the app unions them). An optional "number" is a
positive whole number; the number each issue shows (that one, else the id's trailing digits) must be unique. Paths must
stay inside their issue, referenced files must exist and no private machine details may appear in published text.

With --out, the validated packs are staged next to DIR and swapped in only when complete, together with catalog.json:
    {"format": 1, "digest": ..., "issues": [{"id", "revision", "digest", "files": [{"path", "size", "sha256"}]}]}
An issue digest is SHA-256 over its "path<TAB>size<TAB>sha256" lines (sorted by path, joined by newlines); the catalog
digest is SHA-256 over "id<TAB>revision<TAB>digest" lines (sorted by id). The same packs always give the same catalog.
DIR is the issues folder of the content repository (sortie-zines/issues); it must lie outside the Sortie repository, which
never holds magazine content. Builds include DIR only when given -PsortieMagazinesDir=DIR, and only if its catalog digest is
the one pinned in app/magazines.lock. --pin is the explicit author action that writes the newly published digest there;
review and commit the content and the pin together. Hidden files (dot-names) are never published. Nothing here uses the
network.
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import sys
from pathlib import Path

SCHEMA = 2
REPO = Path(__file__).resolve().parent.parent
LOCK = REPO / "app" / "magazines.lock"
SLUG = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]{0,79}\Z")
REF_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]{0,119}\Z")
TRAILING_DIGITS = re.compile(r"[0-9]+\Z")
INT_MAX = 2**31 - 1
MD_IMAGE = re.compile(r"^\s*!\[[^\]]*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)\s*$")
MAX_ISSUE_JSON = 256 * 1024
MAX_ARTICLE = 1024 * 1024
TEXT_SUFFIXES = {".json", ".md", ".txt"}
# Private details that must never ship: personal paths, card/device paths, desktop audit fields, personal framing.
PRIVATE = [
    (re.compile(r"(?<![\w.])/(?:var/)?home/[^/\s\"]+/"), "a home-directory path"),
    (re.compile(r"(?<![\w.])/storage/(?:emulated|[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4})"), "a device storage path"),
    (re.compile(r"(?<![\w.])/sdcard/"), "a device storage path"),
    (re.compile(r"\"(?:libraryEvidence|desktopBytes|thorPath|deviceBytes)\""), "a private library-audit field"),
    (re.compile(r"\byour (?:library|thor|roms?)\b", re.IGNORECASE), "personal-library framing"),
]


def safe_path(p):
    if not isinstance(p, str) or not p or len(p) > 240 or p[0] in "/~":
        return False
    if any(c in "\\:" or ord(c) < 32 or ord(c) == 127 for c in p):
        return False
    return all(seg not in ("", ".", "..") for seg in p.split("/"))


class Problems:
    def __init__(self):
        self.errors = []
        self.warnings = []

    def error(self, where, msg):
        self.errors.append(f"{where}: {msg}")

    def warn(self, where, msg):
        self.warnings.append(f"{where}: {msg}")


def text_field(obj, key, where, probs, required):
    v = obj.get(key)
    if v is None:
        if required:
            probs.error(where, f'"{key}" is required')
        return ""
    if not isinstance(v, str):
        probs.error(where, f'"{key}" must be text')
        return ""
    if required and not v.strip():
        probs.error(where, f'"{key}" must not be empty')
    return v.strip()


def check_file(issue_dir, rel, where, probs, what):
    if not safe_path(rel):
        probs.error(where, f"{what} path {rel!r} must be relative to the issue folder")
        return None
    f = issue_dir / rel
    if not f.is_file():
        probs.error(where, f"{what} {rel} is missing")
        return None
    return rel


def check_refs(games, where, probs, refs_seen):
    if games is None:
        return
    if not isinstance(games, list):
        probs.error(where, '"games" must be a list')
        return
    ids = set()
    for i, g in enumerate(games, 1):
        at = f"{where}, game {i}"
        if not isinstance(g, dict):
            probs.error(at, "must be a JSON object")
            continue
        gid = g.get("id")
        if not isinstance(gid, str) or not REF_ID.match(gid):
            probs.error(at, f"id {gid!r} must be letters, digits, - or _ (at most 120)")
            continue
        if gid in ids:
            probs.error(where, f"game {gid!r} is listed twice")
        ids.add(gid)
        title = text_field(g, "title", f"{where}, game {gid!r}", probs, True)
        system = text_field(g, "systemId", f"{where}, game {gid!r}", probs, False)
        edition = text_field(g, "edition", f"{where}, game {gid!r}", probs, False)
        names = g.get("matchNames", [])
        if names is None:
            names = []
        if not isinstance(names, list) or any(not isinstance(n, str) or not n.strip() for n in names):
            probs.error(f"{where}, game {gid!r}", '"matchNames" must be a list of non-empty text')
        identity = (title, system, edition)
        first = refs_seen.setdefault(gid, (identity, where))
        if first[0] != identity:
            probs.error(
                f"{where}, game {gid!r}",
                f"title/systemId/edition {identity} differ from {first[0]} in {first[1]}",
            )


def scan_private(issue_dir, probs):
    for f in sorted(issue_dir.rglob("*")):
        if f.is_file() and f.suffix.lower() in TEXT_SUFFIXES:
            text = f.read_text(encoding="utf-8", errors="replace")
            for rx, what in PRIVATE:
                m = rx.search(text)
                if m:
                    probs.error(f"{issue_dir.name}/{f.relative_to(issue_dir).as_posix()}", f"contains {what}: {m.group(0)!r}")


def published_files(issue_dir, probs):
    files = []
    for dirpath, dirnames, filenames in os.walk(issue_dir):
        d = Path(dirpath)
        for name in list(dirnames):
            if (d / name).is_symlink():
                probs.error(issue_dir.name, f"{(d / name).relative_to(issue_dir).as_posix()} is a symlink")
        dirnames[:] = sorted(n for n in dirnames if not n.startswith(".") and not (d / n).is_symlink())
        for name in sorted(filenames):
            f = d / name
            rel = f.relative_to(issue_dir).as_posix()
            if name.startswith("."):
                continue
            if f.is_symlink():
                probs.error(issue_dir.name, f"{rel} is a symlink")
                continue
            if not safe_path(rel):
                probs.error(issue_dir.name, f"{rel} is not a publishable path")
                continue
            files.append(rel)
    return sorted(files)


def issue_number(issue, name, probs):
    """The number the reader shows: "number" when given, else the folder id's trailing digits (none for 0 or no digits).
    The app reads 32-bit numbers, so a larger "number" is an error and a longer digit run shows no number."""
    n = issue.get("number")
    if n is not None:
        if not isinstance(n, int) or isinstance(n, bool) or not 1 <= n <= INT_MAX:
            probs.error(name, '"number" must be a positive whole number')
            return None
        return n
    m = TRAILING_DIGITS.search(name)
    derived = int(m.group(0)) if m else 0
    return derived if 1 <= derived <= INT_MAX else None


def validate_issue(issue_dir, probs, refs_seen, numbers_seen):
    name = issue_dir.name
    if not SLUG.match(name):
        probs.error(name, "folder name must be a slug (letters, digits, - or _)")
    j = issue_dir / "issue.json"
    if not j.is_file():
        probs.error(name, "no issue.json")
        return None
    if j.stat().st_size > MAX_ISSUE_JSON:
        probs.error(name, f"issue.json is larger than {MAX_ISSUE_JSON // 1024} KB")
    try:
        issue = json.loads(j.read_text(encoding="utf-8-sig"))
    except (ValueError, UnicodeDecodeError) as e:
        probs.error(name, f"issue.json is not valid JSON: {e}")
        return None
    if not isinstance(issue, dict):
        probs.error(name, "issue.json must be a JSON object")
        return None
    if issue.get("schemaVersion") != SCHEMA or isinstance(issue.get("schemaVersion"), bool):
        probs.error(name, f"schemaVersion must be {SCHEMA}")
    rev = issue.get("revision")
    if not isinstance(rev, int) or isinstance(rev, bool) or rev < 1:
        probs.error(name, '"revision" must be a positive whole number')
        rev = None
    number = issue_number(issue, name, probs)
    if number is not None:
        other = numbers_seen.setdefault(number, name)
        if other != name:
            probs.error(name, f"issue number {number} is already shown by {other}; give one of them a distinct \"number\"")
    if issue.get("id") != name:
        probs.error(name, f"id {issue.get('id')!r} does not match the folder name")
    for key in ("title", "published", "rightsNotice"):
        text_field(issue, key, name, probs, True)
    for key in ("subtitle", "editorial"):
        text_field(issue, key, name, probs, False)
    if issue.get("cover"):
        check_file(issue_dir, issue["cover"], name, probs, "cover")
    articles = issue.get("articles")
    if not isinstance(articles, list) or not articles:
        probs.error(name, '"articles" must list at least one article')
        return rev
    referenced = {"issue.json"}
    if issue.get("cover"):
        referenced.add(issue["cover"])
    seen = set()
    for i, a in enumerate(articles, 1):
        if not isinstance(a, dict):
            probs.error(name, f"article {i} must be a JSON object")
            continue
        aid = a.get("id")
        where = f"{name}, article {aid!r}"
        if not isinstance(aid, str) or not SLUG.match(aid):
            probs.error(name, f"article {i}: id {aid!r} must be a slug")
        elif aid in seen:
            probs.error(name, f"article id {aid!r} is used twice")
        seen.add(aid)
        text_field(a, "title", where, probs, True)
        if "gameIds" in a:
            probs.error(where, '"gameIds" is a version 1 field; list "games" references instead')
        m = a.get("readingMinutes")
        if m is not None and (not isinstance(m, int) or isinstance(m, bool) or m < 1):
            probs.error(where, '"readingMinutes" must be a positive whole number')
        check_refs(a.get("games"), where, probs, refs_seen)
        body = check_file(issue_dir, a.get("body"), where, probs, "body")
        if a.get("image"):
            if check_file(issue_dir, a["image"], where, probs, "image"):
                referenced.add(a["image"])
        if body:
            referenced.add(body)
            f = issue_dir / body
            if f.stat().st_size > MAX_ARTICLE:
                probs.error(where, f"{body} is larger than {MAX_ARTICLE // 1024} KB")
            text = f.read_text(encoding="utf-8")
            if not text.strip():
                probs.error(where, f"{body} is empty")
            for line in text.splitlines():
                im = MD_IMAGE.match(line)
                if im and check_file(issue_dir, im.group(1), f"{where} ({body})", probs, "image"):
                    referenced.add(im.group(1))
    scan_private(issue_dir, probs)
    for rel in published_files(issue_dir, probs):
        if rel.startswith("media/") and rel not in referenced:
            probs.warn(name, f"{rel} is not used by the issue (still published)")
    return rev


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


def catalog_for(root, issues):
    entries = []
    for name, rev in issues:
        d = root / name
        files = [{"path": rel, "size": (d / rel).stat().st_size, "sha256": sha256(d / rel)} for rel in published_files(d, Problems())]
        digest = hashlib.sha256("\n".join(f"{f['path']}\t{f['size']}\t{f['sha256']}" for f in files).encode()).hexdigest()
        entries.append({"id": name, "revision": rev, "digest": digest, "files": files})
    digest = hashlib.sha256("\n".join(f"{e['id']}\t{e['revision']}\t{e['digest']}" for e in entries).encode()).hexdigest()
    return {"format": 1, "digest": digest, "issues": entries}


def publish(source, out, issues):
    out = out.resolve()
    if out.exists() and any(out.iterdir()) and not (out / "catalog.json").is_file():
        sys.exit(f"{out} exists but holds no catalog.json; refusing to replace something this tool did not write")
    out.parent.mkdir(parents=True, exist_ok=True)
    staging = out.parent / f".{out.name}-staging-{os.getpid()}"
    old = out.parent / f".{out.name}-old-{os.getpid()}"
    shutil.rmtree(staging, ignore_errors=True)
    try:
        staging.mkdir()
        for name, _ in issues:
            src = source / name
            for rel in published_files(src, Problems()):
                target = staging / name / rel
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(src / rel, target)
        catalog = catalog_for(staging, issues)
        (staging / "catalog.json").write_text(json.dumps(catalog, indent=1, sort_keys=True) + "\n", encoding="utf-8")
        if out.exists():
            out.rename(old)
        try:
            staging.rename(out)
        except OSError:
            if old.exists() and not out.exists():
                old.rename(out)
            raise
        shutil.rmtree(old, ignore_errors=True)
    finally:
        shutil.rmtree(staging, ignore_errors=True)
    return catalog


def inside(path, folder):
    return path == folder or folder in path.parents


def pin(digest):
    lines = LOCK.read_text(encoding="utf-8").splitlines() if LOCK.is_file() else []
    old = [line.strip() for line in lines if line.strip() and not line.lstrip().startswith("#")]
    tmp = LOCK.with_name(f".{LOCK.name}.tmp-{os.getpid()}")
    tmp.write_text("\n".join([line for line in lines if line.lstrip().startswith("#")] + [digest]) + "\n", encoding="utf-8")
    tmp.replace(LOCK)
    print(f"pinned catalog {digest} in {LOCK.relative_to(REPO)} (was {', '.join(old) or 'unset'})")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("source", type=Path, help="folder holding one sub-folder per issue")
    ap.add_argument("--out", type=Path, help="the content repository's issues folder, outside the Sortie repository")
    ap.add_argument("--check", action="store_true", help="validate only; write nothing")
    ap.add_argument("--pin", action="store_true", help=f"after publishing, pin the new catalog digest in {LOCK.relative_to(REPO)}")
    args = ap.parse_args()
    if args.check == (args.out is not None):
        ap.error("give either --check or --out DIR")
    if args.check and args.pin:
        ap.error("--pin needs a publication (--out DIR)")
    source = args.source.resolve()
    if not source.is_dir():
        sys.exit(f"{source} is not a folder")
    if args.out is not None:
        out = args.out.resolve()
        if inside(out, REPO):
            sys.exit(f"{out} is inside the Sortie repository; publish into the separate content repository (sortie-zines/issues)")
        if inside(out, source) or inside(source, out):
            sys.exit(f"{out} overlaps the source folder {source}; publish into a separate folder")
    probs = Problems()
    refs_seen = {}
    numbers_seen = {}
    issues = []
    for d in sorted(source.iterdir()):
        if d.name.startswith(".") or not d.is_dir():
            continue
        if d.is_symlink():
            probs.error(d.name, "the issue folder is a symlink")
            continue
        rev = validate_issue(d, probs, refs_seen, numbers_seen)
        issues.append((d.name, rev))
    if not issues:
        probs.error(str(source), "no issue folders found")
    for w in probs.warnings:
        print(f"warning: {w}", file=sys.stderr)
    for e in probs.errors:
        print(f"error: {e}", file=sys.stderr)
    if probs.errors:
        sys.exit(f"{len(probs.errors)} problem(s); nothing published")
    if args.check:
        print(f"ok: {len(issues)} issue(s), {len(refs_seen)} game reference(s)")
        return
    catalog = publish(source, out, issues)
    print(f"published {len(issues)} issue(s) to {out} (catalog {catalog['digest']})")
    if args.pin:
        pin(catalog["digest"])
    elif not LOCK.is_file() or catalog["digest"] not in LOCK.read_text(encoding="utf-8").split():
        print(f"note: builds refuse this catalog until it is pinned in {LOCK.relative_to(REPO)} (rerun with --pin after review)")


if __name__ == "__main__":
    main()
