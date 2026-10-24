# On-card formats

All files live under `<card>/sortie/` and are authored on the desktop (library-tools) unless noted.
JSON, UTF-8, trailing commas not allowed. Unknown keys are ignored so files can evolve.

| File | Owner | Purpose |
|---|---|---|
| `device.json` | desktop | which roots, screens and emulators this device has |
| `systems.json` | desktop | system folders: id, label, roots, extensions, default profile |
| `launchers.json` | desktop | launch profiles: how to start a file with an app |
| `folders.json` | desktop | smart and manual folders |
| `guide.json` | desktop (build_guide.py) | taste layer: fit, hook, flags, tags per game |
| `user.json` | device | favourites, overrides, hidden entries, home sort and custom layout, list/grid choices, opted-in shortcuts ([user.md](user.md)) |
| `playlog.jsonl` | device | one JSON object per play session, append-only |
| `backups/pre-restore-<time>.json` | device | safety copy taken before each restore; same format as an exported backup ([format](user.md#backup-export-and-restore)) |
| `logos/<systemId>.png` | desktop (gen_logos.py) | optional system logos for the home tiles; transparent PNG, ~512 px wide |
| `magazines/<issue-id>/issue.json` | desktop | offline recommendation issue, with local `articles/` and `media/` |

See `../../examples/card/sortie/` for a complete minimal card (two systems, four profiles, four folders).

## Magazine packs (version 2)

An issue is a self-contained directory named exactly after its stable `id`. The same format is used for APK assets and
user-supplied packs in `sortie/magazines/`. Public issues address a general audience, not one person's library or hardware.

```json
{
  "schemaVersion": 2,
  "id": "field-notes-001",
  "revision": 2,
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

APK packs live in `app/src/main/assets/magazines/` with a reproducible `catalog.json`. The app verifies and stages their
extraction into private storage once per catalog digest. Builds use repository assets, never a private authoring directory.
See [release publication instructions](../RELEASE.md#publishing-bundled-magazine-issues).

The shelf merges bundled and card packs by issue ID: higher revision wins; equal revision prefers the card. A malformed card
copy cannot hide a valid bundled issue. Card files are never overwritten or deleted. Version 1 external packs are still
accepted as revision 1; their exact-filename `gameIds` are converted at the input boundary. New version 2 packs use `games`.

To add a card pack, deploy into a hidden staging folder under `magazines/`, then rename after all files arrive. Hidden
folders are ignored. Re-enter Magazines to reload. Increment `revision` for published corrections and retain issue/article
IDs: reading positions use those IDs in app-private `magazine_reading` preferences, outside current JSON backups.
Saved clippings keep their original text, notes and resolved local game IDs; publication does not rewrite personal data.
