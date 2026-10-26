# Library configuration and content formats

These files live under the active `sortie/` configuration folder, shown in **Library setup → Where these settings live**.
Before a location is remembered, Sortie looks for an existing `sortie/device.json` on mounted storage; otherwise it uses
`sortie/` in internal shared storage (`/storage/emulated/0/sortie`). Saving Library setup remembers that location. A missing
remembered volume does not cause a silent fallback to a different library.

Game folders can be on internal storage or a removable card independently of the configuration folder. Library setup writes
the core configuration itself; desktop tools are optional. Files are JSON, UTF-8, with no trailing commas. Unknown keys are
ignored so files can evolve. For the normal on-device path, start with the [beginner guide](../GETTING_STARTED.md).

| File | Owner | Purpose |
|---|---|---|
| `device.json` | Library screen or desktop | which roots, screens and emulators this device has |
| `systems.json` | Library screen or desktop | system folders: id, label, roots, extensions, default profile |
| `launchers.json` | Library screen or desktop | launch profiles: how to start a file with an app |
| `folders.json` | desktop | smart and manual folders |
| `guide.json` | desktop (optional) | taste layer: fit, hook, flags, tags per game; the app's bundled curated guide fills its gaps ([guide.md](guide.md)) |
| `user.json` | device | bookmarks, favourites, queue, shelves/verdicts, reference-card metadata, clippings, overrides and interface preferences ([user.md](user.md)) |
| `playlog.jsonl` | device | one JSON object per play session, append-only |
| `backups/pre-restore-<time>.json`, `backups/pre-cocoon-import-<time>.json` | device | safety copy taken before each restore or Cocoon import; same format as an exported backup ([format](user.md#backup-export-and-restore)) |
| `archive/personal-notes-<hash>.md` and `.json` | device | readable and structured archive of notes/journal entries from older versions, written before migration ([user.md](user.md#older-personal-notes-archive)) |
| `logos/<systemId>.png` | desktop (optional) | system logos for the home tiles; transparent PNG, ~512 px wide |
| `magazines/<issue-id>/issue.json` | user/desktop | offline magazine issue, with local `articles/` and `media/` |

See `../../examples/card/sortie/` for a complete minimal card (two systems, four profiles, four folders).

## Modified-game marks

The indexer's `hack` flag drives the **hack** mark on game tiles and list rows. Positive evidence is a guide entry (from
`guide.json` or the [bundled curated guide](guide.md#bundled-curated-guide)) whose `flags` contains `hack`, `translation` or `fan translation` (case-insensitive), the word `hack` in a
bracketed filename qualifier, or `systems.json`'s `library.hackRegex`. The configured expression is also tried after
removing version numbers inside qualifiers, so a rule for `(True Analogs)` covers `(True Analogs v1.03)`.

Descriptions and guide hooks are not classification inputs; unfamiliar names are not assumed to be modified.
Processing tools should record an explicit guide flag when the filename is ambiguous. The index automatically
reclassifies cached entries once after a classification-rule upgrade or a change to `hackRegex`, then returns to
incremental indexing. No ROM rename or manual rescan is required.

## Magazine packs (version 2)

An issue is a self-contained directory named exactly after its stable `id`. The same format is used for APK assets and
user-supplied packs in `sortie/magazines/`. Public issues address a general audience, not one person's library or hardware.

```json
{
  "schemaVersion": 2,
  "id": "field-notes-001",
  "revision": 2,
  "number": 1,
  "title": "Field Notes",
  "subtitle": "Short sorties, deep systems",
  "published": "2026-10-24",
  "cover": "media/cover.webp",
  "editorial": "An original introduction.",
  "rightsNotice": "Game images and quoted material belong to their respective rights holders. Included for commentary and criticism; no endorsement or ownership is implied.",
  "articles": [{
    "id": "a-stable-slug",
    "title": "An article headline",
    "gameTitle": "Dynasty Warriors 5: Xtreme Legends",
    "system": "PS2",
    "games": [{
      "id": "ps2-dynasty-warriors-5-xtreme-legends",
      "title": "Dynasty Warriors 5: Xtreme Legends",
      "systemId": "ps2",
      "edition": "Original PS2 release",
      "matchNames": ["Dynasty Warriors 5 - Xtreme Legends"]
    }],
    "deck": "A standfirst.",
    "body": "articles/a-stable-slug.md",
    "image": "media/a-stable-slug.webp",
    "imageCaption": "Description and source credit.",
    "readingMinutes": 7
  }]
}
```

Required: numeric `schemaVersion: 2`, positive integer `revision`, nonempty issue `id`, `title`, `published`, and a nonempty
`articles` list. Articles require unique `id`, nonempty `title` and a `body` path. Issue/article IDs match
`[A-Za-z0-9][A-Za-z0-9_-]{0,79}`. Use ISO publication dates; retain the original date when correcting an existing issue.
`readingMinutes`, when supplied, is a positive integer. Other fields are optional to the reader; the publication tool
requires a nonempty `rightsNotice` for bundled issues.

`number`, when supplied, is a positive integer shown on the shelf and contents as the issue number ("No. 001"). Without it
the reader uses the trailing digits of `id` (`field-notes-010` → 10), and an id without trailing digits shows no number;
published issues rely on this fallback and are not edited to add the field. The publication tool rejects a malformed
`number` and two issues that would show the same number. The shelf lists the newest `published` date first, then the higher
number on the same date, then the id, so an old issue with a high number never displaces a newer issue as the latest.

### Portable game references

`games` lists featured games and substantive comparisons, whether or not a reader owns them. Each reference requires a
stable `id` matching `[A-Za-z0-9][A-Za-z0-9_-]{0,119}` and a nonempty `title`. Use the same ID, title, platform and edition
across issues; the publisher rejects conflicting identities. Repeated references within an issue combine their `matchNames`.
`systemId`, `edition` and `matchNames` are optional. An absent platform is informational only and never matches a local file.

Platforms match local system IDs case-insensitively. The portable `arcade` platform also covers systems whose generation is
`arcade`; use verified ROM-set aliases such as `wjammers` rather than a private arcade-folder name. Matching compares equal
names after ordinary punctuation, accent, region, language, disc, revision and reprint normalization. Meaningful edition,
hack and translation qualifiers remain distinct. `edition` is a reader-facing description, **not a matching rule**:
put meaningful distinctions in the title or explicit aliases too. There is no fuzzy or series-wide matching.

Scraped titles are only a fallback for opaque serial/title-ID filenames or arcade sets, and never erase hack/edition
qualifiers. Multiple local copies remain explicit choices. Availability checks only candidate files and excludes
placeholders, zero-byte, missing/unreadable and still-copying files. A local match is not an emulator compatibility claim.
The reader exposes All/On this device lists and game pages link back through Featured in; personal library tags are unchanged.

### Files, updates and reading state

**Every path is issue-root-relative**, including Markdown images. No URLs, absolute paths, dot/empty segments, backslashes,
colons, control characters or symlinks escaping the issue. `issue.json` is limited to 256 KiB and each body to 1 MiB.
Unsafe paths and missing/unreadable/oversized required bodies reject the issue. Empty bodies or files removed after loading
fail only that article; missing optional pictures leave text readable.

Markdown supports headings `#`–`###`, paragraphs, `-` bullets, `>` quotes, `---` rules, bold/italic and links rendered with their
URLs as text. Images must occupy their own line: `![Caption and credit](media/picture.webp)`. HTML is plain text, not executed.
Bundle all pictures; there is no WebView, JavaScript or network loading.

Pictures are laid out from their pixel size: 1.6:1 or wider may use the full reading width, narrower ones the text
measure; height stops at 70% of the view (lead `image` 55%). A picture enlarged two or more screen pixels per image pixel
snaps to a whole multiple and is drawn unsmoothed, so supply pixel art at its native size rather than pre-enlarged. The
caption (alt text, or `imageCaption` for the lead) sits under the picture and in the full-screen viewer.

**Picture sequences (experimental).** Two to twelve image lines in a row, with no blank line between, each titled exactly
`sequence`, form one horizontal sequence of captioned panels inside the article:

```markdown
![First panel caption and credit.](media/route-1.webp "sequence")
![Second panel caption and credit.](media/route-2.webp "sequence")
```

A blank line or any other line ends the sequence. Every panel needs its own caption; the title is only the marker and is
never shown. Use a sequence when the order of the pictures is the point (a route, a build, before and after), not as a
gallery. On a screen at least 560 dp wide, Sortie shows one row with the chosen panel centred and its neighbours at the
sides, at up to 55% of the view's height. A swipe selects a panel; a tap selects it and opens the full-screen viewer.
With a controller, L2 browses the sequence most in view: ←/→ choose a panel instead of turning the page, R2 opens it full
screen, ↑/↓ keep scrolling, and L2, B or scrolling the sequence out of view stop browsing. On a narrower screen the panels
stack as ordinary pictures. The chosen panel is remembered with the reading position; browsing mode is not restored.
Readers without sequence support, including Markdown previews, show the same lines as separate captioned pictures, so the pack
stays readable and keeps schema version 2. The publication tool rejects a single panel, more than twelve panels and
panels without captions.

APK packs come from a separate content directory with a reproducible `catalog.json`, bundled only when a build is given
`-PsortieMagazinesDir`; the default build has none. The build checks the catalog against the digest pinned in
`app/magazines.lock`, and the app verifies and extracts the packs into private storage once per catalog digest.
See [magazine content](../RELEASE.md#magazine-content-separate-repository).

The shelf merges bundled and card packs by issue ID: higher revision wins; equal revision prefers the card. A malformed card
copy cannot hide a valid bundled issue. Card files are never overwritten or deleted. Version 1 external packs are still
accepted as revision 1; their exact-filename `gameIds` are converted at the input boundary. New version 2 packs use `games`.

To add a card pack, deploy into a hidden staging folder under `magazines/`, then rename after all files arrive. Hidden
folders are ignored. Re-enter Magazines to reload. Increment `revision` for published corrections and retain issue/article
IDs: reading positions use those IDs in app-private `magazine_reading` preferences, outside current JSON backups.
Saved clippings keep their original text, notes and resolved local game IDs; publication does not rewrite personal data.
