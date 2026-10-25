#!/usr/bin/env python3
"""Pin app/magazines.lock to one sortie-zines checkout for a single CI build (.github/workflows/build.yml, emulator.yml).

    tools/pin-zines-snapshot.py CHECKOUT [--expect-commit SHA --expect-digest DIGEST]

CHECKOUT is a clean git checkout of the content repository; its issues/catalog.json is what -PsortieMagazinesDir then
bundles. Prints commit=<full commit SHA> and digest=<catalog digest> (lines for $GITHUB_OUTPUT) and rewrites
app/magazines.lock to that digest, so Gradle's stageMagazineAssets verifies every catalogued file of this snapshot exactly
as it does for a hand-pinned catalog. With both expectations a different commit or digest is refused: the signing job
restores the snapshot its build job tested, never a newer one. The rewritten lock belongs to that one build; never commit
it. Only catalog.json is read (content is data, never run) and nothing here uses the network.
"""

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
LOCK = REPO / "app" / "magazines.lock"
COMMIT = re.compile(r"[0-9a-f]{40}\Z")
DIGEST = re.compile(r"[0-9a-f]{64}\Z")


def git(checkout, *args):
    result = subprocess.run(["git", "-C", str(checkout), *args], capture_output=True, text=True)
    if result.returncode != 0:
        sys.exit(f"{checkout}: git {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("checkout", type=Path, help="git checkout of sortie-launcher/sortie-zines")
    ap.add_argument("--expect-commit", help="the full commit SHA the checkout must be at")
    ap.add_argument("--expect-digest", help="the catalog digest the checkout must hold")
    args = ap.parse_args()
    if (args.expect_commit is None) != (args.expect_digest is None):
        ap.error("give both --expect-commit and --expect-digest, or neither")
    if args.expect_commit is not None and not COMMIT.match(args.expect_commit):
        sys.exit(f"expected commit {args.expect_commit!r} is not a full lowercase commit SHA")
    if args.expect_digest is not None and not DIGEST.match(args.expect_digest):
        sys.exit(f"expected digest {args.expect_digest!r} is not 64 lowercase hex characters")

    checkout = args.checkout.resolve()
    commit = git(checkout, "rev-parse", "--verify", "HEAD^{commit}").strip()
    if not COMMIT.match(commit):
        sys.exit(f"{checkout}: HEAD resolved to {commit!r}, not a full commit SHA")
    if git(checkout, "status", "--porcelain", "--untracked-files=all"):
        sys.exit(f"{checkout}: the checkout differs from commit {commit}; use a clean checkout")

    catalog_file = checkout / "issues" / "catalog.json"
    if catalog_file.is_symlink() or not catalog_file.is_file():
        sys.exit(f"{catalog_file} is missing or not a plain file")
    try:
        catalog = json.loads(catalog_file.read_text(encoding="utf-8-sig"))
    except (UnicodeDecodeError, ValueError) as e:
        sys.exit(f"{catalog_file} is not valid JSON: {e}")
    if not isinstance(catalog, dict) or type(catalog.get("format")) is not int or catalog["format"] != 1:
        sys.exit(f"{catalog_file} is not a format 1 catalog")
    digest = catalog.get("digest")
    if not isinstance(digest, str) or not DIGEST.match(digest):
        sys.exit(f"{catalog_file} has no 64 lowercase hex character digest")

    if args.expect_commit is not None and commit != args.expect_commit:
        sys.exit(f"{checkout} is at {commit}, not the expected {args.expect_commit}")
    if args.expect_digest is not None and digest != args.expect_digest:
        sys.exit(f"{catalog_file} has digest {digest}, not the expected {args.expect_digest}")

    tmp = LOCK.with_name(f".{LOCK.name}.tmp-{os.getpid()}")
    tmp.write_text(
        f"# Build-local snapshot of sortie-launcher/sortie-zines@{commit} written by tools/pin-zines-snapshot.py; never commit.\n"
        f"{digest}\n",
        encoding="utf-8",
    )
    tmp.replace(LOCK)
    print(f"commit={commit}")
    print(f"digest={digest}")


if __name__ == "__main__":
    main()
