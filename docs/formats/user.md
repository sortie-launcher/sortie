# user.json (device-owned)

Written by Sortie on the device (`<card>/sortie/user.json`, atomically via `user.json.tmp`); the desktop only reads it.
Every key is optional; unknown keys are ignored.

```json
{
  "favourites": ["snes/Super Metroid (USA).sfc", "app:org.mozilla.firefox"],
  "wantToPlay": ["ps2/Armored Core 3 (USA).chd"],
  "hidden": ["psx/Demo Disc (USA).chd"],
  "profileOverrides": { "psx/Vagrant Story (USA).chd": "duckstation" },
  "homeLayout": "grid",
  "gridColumns": 5,
  "browseLayout": "list",
  "homeSort": "custom",
  "layout": ["system:snes", "folder:unplayed-4", "apps", "ps2/Armored Core 3 (USA).chd", "app:org.ppsspp.ppsspp",
             "shortcut:com.droiddeck.launcher/game-42"],
  "shortcuts": ["shortcut:com.droiddeck.launcher/game-42"]
}
```

| Key | Type | Default | Meaning |
|---|---|---|---|
| `favourites` | ids | `[]` | favourited games (`<system>/<file>`), apps (`app:<pkg>`) and shortcuts (`shortcut:<pkg>/<id>`) |
| `wantToPlay` | game ids | `[]` | the Want to play queue shown by the built-in `want-to-play` folder (title order, hidden games left out), independent of `favourites` and kept after launching. Ids not in the index are kept and show once indexed. The folder is always on the custom home (see `layout`); toggling the queue never changes `layout` |
| `hidden` | ids | `[]` | entries kept out of smart folders |
| `profileOverrides` | id → profile id | `{}` | per-game launch profile chosen in "Launch with…" |
| `homeLayout` | `"list"` \| `"grid"` | `"list"` | home view; Select toggles it on Home |
| `dock` | string[5] | [] | companion dock slots: app/shortcut ids, `""` empty |
| `gridHorizontal` | bool | false | grids scroll sideways as rows of tiles, suited to wide screens |
| `gridRows` | int | 3 | preferred rows in a sideways grid (2–6); more rows = smaller tiles |
| `appColumns` | int | 8 | Apps screen tiles per row (4–14) |
| `gridColumns` | 2..10 | `5` | columns of every grid (home and browse screens) |
| `browseLayout` | `"list"` \| `"grid"` | `"list"` | view of the browse screens (system lists, folders, search, Apps); Select toggles it there. One setting for all of them, separate from `homeLayout` |
| `homeSort` | `"alpha"` \| `"count"` \| `"generation"` \| `"custom"` | `"alpha"` | home order. The first three are generated and fixed and list systems only: by label, by game count (descending) or grouped by the system's `generation` (`8-bit` … `128-bit` ascending, then the rest in `systems.json` order). `custom` shows `layout` |
| `layout` | tile ids, in order | `[]` | the custom home. Ids: `system:<systemId>`, `folder:<folderId>`, `apps` (the built-in Apps folder), `continue` (the last game played), a game id, `app:<pkg>`, `shortcut:<pkg>/<shortcutId>`. Continue, every smart folder (including Want to play) and Apps always show on Custom and cannot be removed: those missing here are shown anyway, Continue first and the rest after the saved ids (folders in `folders.json` order, then Apps). Moving any tile saves the full displayed order here. Ids that no longer resolve are skipped when rendering but kept here |
| `shortcuts` | shortcut ids | `[]` | launcher shortcuts listed on the Apps screen. Shortcuts pinned to Sortie or added with "Add shortcut from…" are added automatically; others are opted in from "Browse all shortcuts…". Smart folders see only shortcuts that are opted in, favourited or placed on home |
| `gameAnnotations` | game id → annotation | `{}` | personal controller requirements, mapping notes and return reminders; empty entries are removed |
| `gameProgress` | game id → progress | `{}` | explicit Finished/Shelved status, date and verdict; independent of favourites and queue |
| `referenceCards` | game id → cards[] | `{}` | personal Markdown/image reference cards; image files live separately under `reference/` |
| `magazineClippings` | clippings[] | `[]` | saved paragraph highlights and article recommendations, including copied excerpts and personal notes |
| `journalEntries` | entries[] | `[]` | dated personal notes and attachment metadata; recorded sessions remain in Room, not this array |

## Personal game notes

Each annotation holds `controller` (`unknown`, `gamepad`, `pointer`, `motion`, `touch`), `mappingNotes` and `returnNote`.
Unknown future controller values read as `unknown`; missing text fields default to empty strings. Notes are trimmed on save.
An annotation with no controller assessment and no text is removed. These device-owned notes never overwrite `guide.json`.

## Finished and Shelved

`gameProgress[id]` has `status` (`finished` or `shelved`), `since` (local ISO `yyyy-MM-dd`) and `verdict` (trimmed, at most
280 characters). Changing status sets a new date; editing only the verdict keeps it. Clearing status removes the record
and verdict. Unknown future statuses are preserved but do not enter either shelf. No completion is inferred from play time.

## Reference cards

Each card has a stable `id`, `title`, Markdown `body`, nullable `image`, imported filename `source`, and `created`/`updated`
epoch milliseconds. `source` is informational, never reopened. Empty per-game lists are removed from the map.
Images use paths relative to `sortie/`: `reference/<game-key>/<card-id>.<png|jpg|webp|gif>`, where `game-key` is the first
20 lowercase hex characters of the game id's SHA-1. Readers and writers reject paths outside this shape and symlink escapes.
Metadata commits precede deletion of owned images; original picker files are never deleted.

Limits: 200 cards per game, 120 title characters, 100,000 body characters, 256 KiB UTF-8 Markdown imports and 24 MiB images
(maximum 16,384 per edge and 64 million pixels). Display decoding has a separate pixel budget.

## Magazine scrapbook

Each clipping has `id`, `kind` (`highlight` by default, or `article`), stable `issueId`/`articleId`, copied `issueTitle`,
`issuePublished`, `articleTitle`, `gameTitle`, `system`, `gameIds`, `deck`, excerpt `text`, personal `note`, a `block` hint
and `created`/`updated` epoch milliseconds. `shortened` marks an excerpt capped at 20,000 characters; notes over 4,000
characters are refused rather than truncated. Article clippings retain the first paragraph, not the complete article.

The saved text is the source locator: `block` alone never selects a changed paragraph. The scrapbook remains readable
without its issue pack; returning to an absent/changed source reports that condition. Pack files remain read-only.

## Weekly journal

`JournalEntry` fields: UUID `id`, local ISO `date`, `text`, nullable `gameId` and `sessionStartedAt`, `attachments`, and
`createdAt`/`updatedAt` epoch milliseconds. A linked session uses `(gameId, sessionStartedAt)`, never a Room row id.
Invalid dates remain visible as undated entries. Entries may be dated independently of when they were written.

Each attachment records `path`, `width`, `height`, `bytes` and original display `name`. The path is relative to `sortie/`:
`journal/media/<entry-id>/<file-id>.<png|jpg|webp>`. Only explicitly picked pictures are imported, at most 12 per entry;
path containment and symlink checks apply to reads, writes and deletion. Newly imported draft files are removed on cancel.
Images are orientation-corrected and resized as needed, to at most 3,840 pixels per edge.

The weekly view reads actual Room sessions, splitting valid durations at local midnight/week boundaries (including DST).
Open, abandoned or reversed-clock sessions remain identifiable but contribute no guessed duration. Editing/deleting
a journal entry never changes session history.

## Backup export and restore

Settings exports a JSON document through Android's file picker. The required fields are `format: "sortie-backup"`,
`version: 1`, `createdAt` (epoch milliseconds), `user` (the full preferences object above) and `sessions` (an array, possibly empty).
`device` and `appVersion` identify the source. `openSessionExcluded` optionally describes a running session omitted from export.

Each closed session contains `gameId`, `profileId`, `startedAt`, `endedAt` and `abandoned`. Identity is `(gameId, startedAt)`,
not a database row ID. Unfinished sessions are never imported. Files over 64 MB, unsupported formats/versions, missing required
sections or invalid session timestamps are rejected before any writes; a missing `user` object is never interpreted as defaults.

Restore requires confirmation and no open play session. It first fsyncs the current preferences and history to
`<card>/sortie/backups/pre-restore-<yyyyMMdd-HHmmss>.json` in the same format. Failure to save that copy prevents the restore.
New sessions are inserted transactionally; existing device sessions, including newer ones, are kept unchanged. Preferences
are then replaced durably and published only after the write succeeds. If preference writing fails, imported rows are removed;
if that compensation also fails, the result reports the remaining imported sessions. Session logging is paused during restore.

Restoring the same backup twice adds no duplicate history. Restoring a safety copy recovers prior preferences, but cannot
remove sessions merged by an earlier restore. Room is authoritative for play statistics; the append-only `playlog.jsonl`
is never modified by restore.

The four personal-data fields above are included in `user`. Reference images (`reference/`) and journal screenshots
(`journal/media/`) are **not embedded**: copy these directories alongside the JSON, retaining their paths beneath `sortie/`.
Missing attachments are reported by the viewers; their text/metadata is not silently discarded.

Excluded: ROMs, artwork, emulator saves, the rebuilt game index, desktop manifests, `playlog.jsonl`, Android shortcut pins,
permissions and default-home selection. Stored app/shortcut IDs are retained, but may require reinstalling or re-pinning
their source before they resolve on another device.
