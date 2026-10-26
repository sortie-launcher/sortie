# guide.json and the bundled curated guide

An optional editorial layer: short recommendations, fit scores, cautions, tags and control notes. It has two
sources: the local `guide.json`, which Sortie reads from its active configuration folder but never generates or downloads,
and the [curated guide bundled with the app](#bundled-curated-guide), which needs no local file. Neither is required to browse
or launch games. Here, “authored” and “generated” describe entry classification and precedence, not human versus AI authorship.

`guide.json` entries are keyed by the exact local game ID, `<systemId>/<filename>`, including the filename's extension.

```json
{
  "built": "2026-10-23 01:20",
  "games": {
    "ps2/God Hand (USA).iso": { "fit": 5, "hook": "Clover's brawler…", "flags": ["brutal difficulty"], "tags": ["brawler"], "auto": false }
  }
}
```

## Entry fields

| Field | Meaning |
|---|---|
| `fit` | Curator's 1–5 taste-fit score; `0` (default) means no score. This is not an objective review rating or a compatibility result. Keep values in 0–5; the JSON model itself does not clamp them. |
| `hook` | Short editorial recommendation shown on the game page. |
| `controls` | Optional control notes; see below. |
| `flags` | Short cautions such as pacing, difficulty or unusual controls. |
| `tags` | Editorial labels available to folder rules. |
| `auto` | `false` for an individually authored/curated entry; `true` (the default) for an automatically generated entry. This controls precedence and the notes filter, not whether a human or AI wrote the prose. |

`hook` and `controls` default to empty strings; `flags` and `tags` to empty lists. The index stores flags and tags as
lowercase comma-separated labels, so avoid commas within a label. `built` is optional text (default empty) used in the
index's change stamp, not a required date format.

Bookmarks, controller requirements and Finished/Shelved verdicts belong to the reader's [user data](user.md), not this editorial file.

### `controls`

Optional authored control notes per game (`"controls": "L2/R2 strafe · hold Select = menu"`). Shown on the game page and
on the companion screen while the game is played.

## Sharing a guide.json

A guide can be shared as a file, but its keys are **not portable game identities**. An entry for
`ps2/God Hand (USA).iso` will not attach to a differently named file, a different system ID or a `.chd` copy.
Adapt keys to the receiving library; do not rename somebody's game files just to match a guide.

Sortie reads one `guide.json`, not a stack of independently merged files. Back up an existing file and merge the intended
entries before replacing it. Keep a curator's taste scores distinct from the reader's own preferences, and review any
generated scores or personal wording before sharing. A missing guide leaves only the bundled layer; the launcher does not
need an account, an AI service or a connection to obtain either.

## Bundled curated guide

Every APK carries one public JSON database, `app/src/main/assets/game-blurbs.json`. Its original, AI-assisted editorial
notes belong to stable **game-release identities**, not a private library. The catalog is checked into Git, works offline
and contains no fit scores, taste tags, play history, device names, private paths, ROM bytes or scraper descriptions.
Coverage is selective; an unmatched game simply has no shared editorial note.

### Schema and immutable IDs

The top level has `schemaVersion: 1`, `revision` and `entries`. Revision is the lowercase SHA-256 of the entries encoded
as UTF-8 JSON with sorted object keys, compact separators and unescaped Unicode. Array order is significant; the exporter
sorts entries by system and UUID. There is no build timestamp, so the same inputs and existing identities give the same file.

Each entry has:

| Field | Meaning |
|---|---|
| `id` | Immutable lowercase UUID. Prose, title and alias corrections must keep it. |
| `system` | Canonical platform, independent of the reader's local game ID. |
| `title` | Display name; never an implicit matching alias. |
| `aliases` | Exact approved basenames, explicit extensionless stems, or safe system-relative game-package paths; all release/patch qualifiers retained. |
| `hook` | Original editorial note. |
| `controls`, `flags` | Optional public control advice and cautions; no device-specific setup or private preferences. |
| `parentId` | Optional related catalog UUID; informational, never permission to inherit its prose or fingerprints. |
| `fingerprints` | Optional expected payload identities with explicit representation and evidence; see below. |
| `externalIds` | Optional provider-to-ID cross-references. These are provenance, not a promise that the scanner reads that provider's metadata. |
| `sources` | Public evidence objects: `kind`, `name`, and optional `url`, `version`, `sha256`, `release`. |

Initial UUIDs are UUIDv5 under namespace `5ced76a4-4230-5cdb-a657-f975b92eb7ba`, seeded from canonical system, a newline
and the first exact release alias. That is an allocation rule, **not** an instruction to recalculate an existing ID.
The committed database is the registry: regeneration reuses UUIDs through its existing aliases and preserves entries
absent from the import. Keep the old alias when adding a corrected name. A substantial identity correction or split needs
an explicit editorial review rather than a changed UUID disguised as a spelling fix.

Newly supported container extensions are backfilled as fully qualified stems on existing UUIDs **before** matching new
imports. Thus a known `.ciso`, `.cdi` or `.32x` release keeps its identity when the same qualified release arrives in another
container. A stem repair that would join two already published UUIDs fails for explicit editorial resolution.
New entries and repaired aliases use the same complete recognized suffix chain: `Release (Translation v1.0).nes.zip`
publishes that exact name, `Release (Translation v1.0).nes`, and `Release (Translation v1.0)` on the first export.
Expansion stops at any unknown suffix and never removes release qualifiers or version text. It never shortens
system-relative package paths. Re-exporting therefore cannot progressively broaden an alias one extension at a time.

The previous catalog's work/variant slugs are retained as `sortie-legacy-work` and `sortie-legacy-variant` external IDs.
Those old groups could cover several discs or regional copies; separate exact-release UUIDs can therefore refer to the
same legacy slug. All previously published notes are retained, but the old broad normalization rules are not.

### Matching, platforms and variants

Matching is platform-scoped and conservative: usable payload fingerprints can identify a renamed cartridge; exact curated
aliases cover known release names. There is no fuzzy title search, scraped-title fallback, automatic removal of qualifiers,
or inference from a parent game's name. Filename aliases are case-insensitive, but punctuation and release qualifiers
remain meaningful. An explicitly listed extensionless alias allows a different supported container extension with the same
qualified stem; it does not allow an unknown revision, translation, hack, undub or remake to borrow retail prose.
Ambiguous identities remain unmatched.

Nested game packages can use their complete system-relative entrypoint path, for example
`games/Infinity 3.3.4/Infinity 3.3.4/Game.exe`. These aliases are never shortened to `Game.exe`, `Game.ini` or an
extensionless engine name: unrelated games can share the same executable. Package display titles are explicit inventory
metadata, not matching aliases. Absolute paths, drive paths, backslashes, empty components and `.`/`..` traversal are
rejected. Package entrypoint identity does not authorize hashing shared engine binaries or recursively discovering new
library files; the indexer must actually supply the exact system-relative identity for such an alias to match.

The finite platform mapping folds `tg16` into `pcengine`, `megadrive` into `genesis`, and known arcade system IDs
(`arcade`, `mame`, `fbneo`, `neogeo`, `cps1`, `cps2`, `cps3`, `toaplan-cave-stg`, `stv`, `model2`, `naomi`, `atomiswave`)
into `arcade`. Other IDs remain separate; a label or an “arcade” generation is not sufficient to match an unknown system.
The existing `ngp` catalog includes both monochrome and Color releases; `ngpc` is not automatically folded into it.

Ports, regional revisions and modified editions have independent identities unless documented evidence explicitly justifies
sharing an entry. A `parentId` can record a relationship without merging it. Never strip an unfamiliar bracketed tag or
apply the base game's checksum to a patch. A note may explain the common game while still preserving the exact edition.
The reader's `<systemId>/<filename>` IDs, favourites, bookmarks and play history are unchanged.

### Fingerprints and evidence

A fingerprint has `algorithm: "crc32"`, eight lowercase hexadecimal digits in `value`, a positive byte `size`,
`representation: "raw"` or `"nes-prg-chr"`, `verification: "dat-release"` or `"payload-verified"`, and a nonempty `source`.
CRC32 plus size is a release lookup aid, not a cryptographic authenticity or security guarantee.

- **dat-release** means an authoritative DAT's expected release checksum was attached through an exact, unambiguous
  single-ROM release name. Its source records the DAT name, version, whole-DAT SHA-256, public URL and release name.
  This does **not** mean the curator's ROM was read or checksum-verified.
- **payload-verified** is reserved for evidence that the actual declared payload was inspected and matched. A filename,
  ZIP name, scraper label or matching archive date is not that evidence.
- **raw** means the unmodified cartridge payload bytes. A ZIP member's CRC/size can describe those bytes; the ZIP archive's
  checksum cannot. Multi-file arcade sets and disc containers are not one raw cartridge payload.
- **nes-prg-chr** means exactly the declared NES PRG+CHR bytes, excluding the iNES header and any trainer. Never relabel
  a generic or header-containing NES DAT checksum as this representation. The initial exporter does not infer NES hashes.

Ordinary scanning never hashes discs. Unmatched supported raw cartridges are read on IO with a 32 MiB limit; unsupported
representations such as SMC copier-header images, SMD interleaving and uncertain N64 byte order are skipped. ZIP inspection
uses a bounded central directory and requires exactly one non-directory member with a supported raw cartridge extension.
NES inspection accepts the supported iNES 1 representation only when the declared lengths account for the whole file;
NES 2, unknown or trailing payloads are skipped. Unusable or absent fingerprints leave exact aliases as the conservative
fallback, not a claim of byte verification.

The exporter withholds a fingerprint if the exact DAT name is ambiguous or the same fingerprint would identify multiple
catalog entries. For platforms whose DAT files are explicitly refreshed, it replaces old `dat-release` assertions with
the current unique result, or retires them if the release is absent or ambiguous. Independently `payload-verified` evidence
is preserved; omitting a platform's DAT input preserves its existing evidence. Historical source citations remain
provenance, not active matching fingerprints. This reconciliation also covers retained community entries absent from
the private note import. The exporter never downloads ROMs, contacts a matching service or publishes private evidence paths.

### Local precedence and upgrades

`guide.json` always wins; the bundled catalog only fills gaps, per game:

- an individually authored entry (`"auto": false`) is used exactly as written, blank fields included;
- a generated entry (`"auto": true`, usually a fit score and tags without a hook) keeps every value it has, and the catalog
  supplies only an empty `hook`, `controls` or `flags`. Filling the hook sets `auto` to `false`, so it qualifies for the
  notes filter; filling only controls or flags does not change that classification;
- with no local entry, the shared note has fit 0 and no taste tags.

The app streams the APK JSON and content digest without retaining the complete source text, then shares one immutable
catalog across its application graphs until process exit. Public provenance remains in the asset, not the runtime model.
Its per-system index stamp includes the catalog format, revision and asset digest;
startup checks for a changed stamp on IO. An app/catalog update refreshes or retires obsolete shared prose on the next
normal scan, without a force-rescan or an idle ROM walk when the stamp is unchanged. It rebuilds from the original local
guide and shared catalog rather than mistaking a previous shared hook for a personal override. Nothing is written to the card.

### Exporting and validating

From the repository root:

```sh
python3 tools/guide/import-blurbs.py /path/to/blurbs.jsonl \
  --inventory /path/to/full-inventory.jsonl --dats /path/to/dats \
  --evidence /path/to/public-evidence.jsonl
python3 tools/guide/import-blurbs.py --validate
python3 -m unittest discover -s tools/guide
```

The private source is read-only JSONL (`sys`, `file`, `hook`, optional `controls`, `flags`, `sources`); private fields such
as `fit` are not copied. Inventory may be JSONL or the existing `window.GUIDE` `data.js`. Only identity/display metadata
is considered, never its scraped description or private path/share fields. `--dats` and repeatable `--evidence` are optional;
omitting them preserves already published evidence rather than inventing it. Evidence JSONL uses
`{"sys":"gba","file":"Exact release.gba","sources":["https://public.example/game-reference"]}`.
Source objects may instead provide the fields described above. URLs must be public HTTP(S), not private hosts or file paths.

Reviewed wording changes and exclusions live in `tools/guide/curation.json`, keyed by exact `system` and `alias`.
Decisions can replace the display `title`, `hook`, `controls` or `flags`, add/drop named flags, or exclude an input, with a
reason. A title correction preserves the UUID and existing aliases: it never creates a matching alias or supplies evidence
for a fingerprint. These decisions replace the former line-number/private-snapshot curation format. The exporter imports
no personal scores and rejects obvious private-library wording, ambiguous aliases, duplicate UUIDs, malformed fingerprints
and missing/cyclic parents. Privacy detection is a guardrail, not a substitute for editorial review.

`--check` compares a regeneration with the committed file without writing. `--validate` needs only the public catalog;
contributors do not need the original private library. It checks structure, revision, identity collisions, evidence and
privacy constraints. Entries absent from an import remain in the public catalog, so a smaller private collection cannot
delete community work. Intentional removal must be reviewed in the public JSON itself.

### Writing and contributing notes

Write a short, direct, original note about what a player actually does and what distinguishes this release. **20–45 words
is typical, not a minimum**: a precise shorter note beats padding. Longer prose is justified only by useful distinctions.
Avoid boilerplate summaries, sales language, a universal taste score, device compatibility claims without evidence, and
private ownership, storage, save or play-history details. Controls should describe the game, not somebody's handheld setup.

Consult primary manuals, official release/patch documentation and identifiable gameplay where available. Add public
source URLs and enough edition information for another contributor to check the claim. Sources support facts and identity;
they do not authorize copying their prose. Older migrated notes retain their original editorial provenance but do not
pretend to have independently verified citations for every claim. A cached metadata description is research context,
not publishable original writing or proof that a ROM was verified.

To contribute, edit the public JSON in a branch, retain existing UUIDs, add exact aliases only with identity evidence,
and submit the note, evidence and any intentional variant relationship together. For new identities use the namespace
allocation rule above or a new UUID, never an existing UUID from another game. If changing imported notes, put the same
reviewed override in `curation.json` so regeneration retains the edit. Recompute `revision` from the canonical entries,
run the validator and behavioral tests, then include those results and any unresolved identity doubts in the pull request.
Do not contribute ROM files, copied descriptions, personal fit scores or private evidence URLs. If identity is uncertain,
leave it unmatched rather than making a plausible guess.

Magazine game references remain a separate format and are not accepted as `guide.json` keys.
