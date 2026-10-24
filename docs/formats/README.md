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

## Magazine packs (version 1)

Each issue is a self-contained directory named exactly after its `id`. Example:

```json
{
  "schemaVersion": 1,
  "id": "field-notes-001",
  "title": "Field Notes",
  "subtitle": "Short sorties, deep systems",
  "published": "2026-10-24",
  "cover": "media/cover.webp",
  "editorial": "An original introduction.",
  "articles": [{
    "id": "a-stable-slug",
    "title": "An article headline",
    "gameTitle": "A game title",
    "system": "PS2",
    "gameIds": ["ps2/Exact library filename.iso"],
    "deck": "A standfirst.",
    "body": "articles/a-stable-slug.md",
    "image": "media/a-stable-slug.webp",
    "imageCaption": "Description and source credit.",
    "readingMinutes": 7
  }]
}
```

Required: numeric `schemaVersion: 1`; nonempty issue `id`, `title`, `published`; nonempty `articles`; unique article `id`,
nonempty `title` and `body`. IDs match `[A-Za-z0-9][A-Za-z0-9_-]{0,79}`. Use ISO dates so issues sort newest first.
Other fields are optional; `readingMinutes`, when supplied, is a positive integer. Game IDs identify indexed library
entries, not installation guarantees. Missing IDs produce an on-device message rather than a blank game page.

**Every path is issue-root-relative**, including Markdown images. No URLs, absolute paths, dot/empty segments, backslashes,
colons, control characters or symlinks escaping the issue. `issue.json` is limited to 256 KB and each body to 1 MB.
Unsafe manifest paths reject that issue; missing bodies fail only their article; missing optional pictures leave text readable.

Markdown supports headings `#`–`###`, paragraphs, `-` bullets, `>` quotes, `---` rules, bold/italic and links rendered with their
URLs as text. Images must occupy their own line: `![Caption and credit](media/picture.webp)`. HTML is plain text, not executed.
Bundle all pictures; there is no WebView, JavaScript or network loading.

Deploy into a hidden staging folder under `magazines/`, then rename to the final issue ID after all files arrive. Hidden
folders are ignored. Re-enter Magazines to reload the shelf. Reading positions are keyed by issue/article ID in app-private
preferences (`magazine_reading`); retain stable IDs when correcting an issue. They are not in `user.json` or current backups.
