# systems.json

```json
{
  "systems": [
    {
      "id": "ps2",
      "label": "PlayStation 2",
      "roots": ["big-roms/ps2"],
      "extensions": [".iso", ".chd", ".cso"],
      "gamelist": "scraped-esde/gamelists/ps2/gamelist.xml",
      "media": "scraped-esde/downloaded_media/ps2",
      "profiles": ["armsx2-uri", "open-with"],
      "colour": "#4b6cff",
      "order": 12
    }
  ]
}
```

`id` and `roots` are required. Game IDs are `<id>/<filename>`: changing an ID or filename disconnects exact-ID guide entries,
personal records and history. Keep IDs stable. The index scans files directly inside each root, not nested directories.
Copies with the same filename in two roots share one ID, not two library entries.

`roots`, `gamelist` and `media` may be absolute paths (starting with `/`, including another volume) or relative to the
effective device root: the parent of the active configuration folder. For example,
`/storage/emulated/0/ES-DE/downloaded_media/ps2` can be used when the configuration lives on a card.

| Field | Default | Meaning |
|---|---|---|
| `folder` | `id` | source-folder identity used by the index and import; does not replace `roots` |
| `label` | `id` | displayed system name |
| `generation` | `"other"` | grouping for Home's Generation order; `"arcade"` also enables arcade portable-reference matching |
| `extensions` | `[]` | case-insensitive suffixes, including the dot; empty allows all types except the indexer's excluded helper files |
| `gamelist`, `media` | absent | optional scraped metadata XML and artwork directory |
| `profiles` | `[]` | fallback profile IDs from `launchers.json`; user game/system choices take precedence |
| `colour` | `"#64748b"` | system tile and theme colour |
| `order` | `0` | ascending manifest order, also used within Home generation groups |
| `bios` | `[]` | filenames relative to `device.biosDir`; missing files produce a badge, not a launch block |

The optional top-level `library` object holds `skipFiles` (case-insensitive exact filenames to leave out, default `[]`) and
`hackRegex` (optional filename expression for [modified-game marks](README.md#modified-game-marks)). For example:

```json
{ "systems": [], "library": { "skipFiles": ["neogeo.zip"], "hackRegex": "(?i)\\(True Analogs\\)" } }
```

Profile IDs must exist in `launchers.json`; the example does not create them or install an emulator. Empty or unknown profile
lists do not implicitly add Android's chooser.
