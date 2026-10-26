# guide.json and the bundled curated guide

An optional editorial layer: short recommendations, fit scores, cautions, tags and control notes. It has two sources:
the card's `guide.json`, which Sortie reads from its active configuration folder but never generates or downloads, and the
[curated guide bundled with the app](#bundled-curated-guide), which needs no file at all. Neither is required to browse or
launch games.

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
| `fit` | Curator's 1–5 taste-fit score; `0` means no score. This is not an objective review rating or a compatibility result. |
| `hook` | Short editorial recommendation shown on the game page. |
| `controls` | Optional control notes; see below. |
| `flags` | Short cautions such as pacing, difficulty or unusual controls. |
| `tags` | Editorial labels available to folder rules. |
| `auto` | `false` for an individually authored/curated entry; `true` (the default) for an automatically generated entry. This controls precedence and the notes filter, not whether a human or AI wrote the prose. |

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

Every APK carries `assets/guide/catalog.json`: AI-assisted editorial hooks, control notes and cautions keyed by
**portable identities** instead of file names, so they attach to matching copies without a local `guide.json`.
Coverage is selective, not a description of every game, and carries no fit scores or taste tags.

```json
{
  "format": 1,
  "source": { "sha256": "9c67…", "records": 1102, "published": 1012, "edited": 62, "merged": 22, "excluded": 6 },
  "works": [
    { "id": "armored-core-2", "title": "Armored Core 2", "variants": [
      { "id": "ps2-armored-core-2", "platform": "ps2", "title": "Armored Core 2", "matchNames": ["Armored Core 2"], "hook": "Martian campaign…" },
      { "id": "ps2-armored-core-2-true-analogs-v1-0", "platform": "ps2", "title": "Armored Core 2 (True Analogs v1.0)",
        "edition": "True Analogs v1.0", "matchNames": ["Armored Core 2 (True Analogs v1.0)"], "hook": "…", "flags": ["hack"] }
    ] }
  ]
}
```

A **work** is one game across its versions, regions and editions; its `id` never changes once published, even if a title or
alias later does. A **variant** is one platform/edition of a work: `platform` is a system ID (`arcade` covers every
arcade-generation system, and arcade variants answer to romset names), `edition` is a reader-facing description
and `matchNames` are the names it answers to. Variant IDs follow the magazine reference convention
(`<platform>-<slug>`). Different games that share a name on different platforms are separate works; a work spans platforms
only when the source prose itself establishes the identity.

Matching is the [magazine reference rule](README.md#portable-game-references): same platform, equal names after
punctuation, accent, region, language, disc, revision and reprint normalisation, with edition, hack and translation
qualifiers kept word for word. So `Alundra (USA) (Rev 1).chd` takes the Alundra prose and `Alundra (USA) (Un-Working
Designs).chd` takes only the Un-Working Designs prose; a hack, a translation, another patch version or another platform
never inherits a retail entry. Disc 2 of a set shares the game's prose. Scraped titles count only for serial-named files and
arcade sets, never for hacks. A file that two variants could claim gets neither unless exactly one matches its file name.

### Precedence

`guide.json` always wins; the bundled guide only fills gaps, per game:

- an individually authored entry (`"auto": false`) is used exactly as written, blank fields included;
- a generated entry (`"auto": true`, usually a fit score and tags without a hook) keeps every value it has, and the bundled
  guide supplies only an empty `hook`, `controls` or `flags`. Filling the hook sets `auto` to `false`, so it qualifies
  for the notes filter; filling only controls or flags does not change that classification;
- with no entry, the bundled one is used with fit 0 and no tags.

An app update that changes the bundled catalogue changes its digest, which is part of the indexer's per-system stamp, so the
next ordinary scan re-indexes cached games with the new prose. Nothing is written to the card.

### Building the catalogue

`tools/guide/import-blurbs.py SOURCE.jsonl` rebuilds `app/src/main/assets/guide/catalog.json` and
`tools/guide/import-report.json` from the curator's private authored source (read-only) and the editorial decisions in
`tools/guide/curation.json`; `--check` verifies that the committed files are current. Every source record is accounted for
in the report by its line in the source snapshot, never by file name or a per-record digest (a digest of one short record
would let anyone confirm guesses about its private fields): published as written, edited (with the reason), merged into the
variant whose prose describes the game (disc companions, extra copies) or excluded (with the reason). Only the whole
source's SHA-256 and counts are recorded. Curation decisions name a source line plus that line's public platform and
title; the importer refuses decisions written for another source snapshot or whose line no longer has that identity. It
never publishes file names, dump-tool or dump-status tags, local folder names, fit scores or generated taste tags, and it
refuses personal or library-specific wording; `--private-report FILE` (outside the repository) shows original and
published text side by side for review. Existing work and variant IDs are kept on rebuild.

Magazine game references remain a separate format and are not accepted as `guide.json` keys.
