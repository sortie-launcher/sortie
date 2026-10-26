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
  "browseChrome": "auto",
  "homeSort": "custom",
  "layout": ["system:snes", "folder:unplayed-4", "apps", "ps2/Armored Core 3 (USA).chd", "app:org.ppsspp.ppsspp",
             "shortcut:com.droiddeck.launcher/game-42"],
  "shortcuts": ["shortcut:com.droiddeck.launcher/game-42"]
}
```

| Key | Type | Default | Meaning |
|---|---|---|---|
| `favourites` | ids | `[]` | favourited games (`<system>/<file>`), apps (`app:<pkg>`) and shortcuts (`shortcut:<pkg>/<id>`), shown by the built-in `favourites` folder (title order, hidden entries left out; always on the custom home). Ids not in the index or not installed are kept and show once available |
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
| `browseChrome` | `"auto"` \| `"expanded"` \| `"compact"` | `"auto"` | header and controller hints on Home and every browse screen (system lists, folders, search, Apps); one setting for all of them, cycled in Settings → Header and hints. `auto` is compact when the window itself is under 600 dp tall or wide, divided by a font scale above 1 (measured, whatever the device), otherwise expanded. Compact folds the header into a strip while browsing (tap, pull down or Up from the first row reveals it) and shows one row of hints with a Controls button listing every command. A missing or unknown value reads as `auto`; Sortie and backup restore save only the three known values |
| `homeSort` | `"alpha"` \| `"count"` \| `"generation"` \| `"custom"` \| `"console"` | `"alpha"` | home order. The first three are generated and fixed and list systems only: by label, by game count (descending) or grouped by the system's `generation` (`8-bit` … `128-bit` ascending, then the rest in `systems.json` order). `custom` shows `layout`; `console` is By Console (a console strip, that console's games below). Unknown values read as `alpha` |
| `layout` | tile ids, in order | `[]` | the custom home. Ids: `system:<systemId>`, `folder:<folderId>`, `apps` (the built-in Apps folder), `continue` (the last game played), a game id, `app:<pkg>`, `shortcut:<pkg>/<shortcutId>`. Continue, every smart folder (including Want to play) and Apps always show on Custom and cannot be removed: those missing here are shown anyway, Continue first and the rest after the saved ids (folders in `folders.json` order, then Apps). Moving any tile saves the full displayed order here. Ids that no longer resolve are skipped when rendering but kept here |
| `shortcuts` | shortcut ids | `[]` | launcher shortcuts listed on the Apps screen. Shortcuts pinned to Sortie or added with "Add shortcut from…" are added automatically; others are opted in from "Browse all shortcuts…". Smart folders see only shortcuts that are opted in, favourited or placed on home |
| `systemProfiles` | system id → profile id | `{}` | per-system default launcher chosen in Settings → Default launcher per system (or remembered from a fallthrough chooser pick, see LAUNCHERS.md); overrides the system's `profiles` order. "Manifest default" removes the entry |
| `heroRotation` | bool | true | the idle companion screen rotates suggested games (and the ambient tint they drive) |
| `overlaySwitcher` | bool | true | the Home-key switcher draws overlay windows over running games (needs *Display over other apps*); false or no grant uses Sortie's normal switcher screen, which has no Perf page |
| `deviceStats` | `"off"` \| `"companion"` \| `"overlay"` \| `"both"` | `"off"` | where device stats (CPU, GPU, RAM, temperatures, fan) show: companion-screen side strips, the switcher overlay's title line, both or nowhere. Unknown values read as `off` |
| `deviceStatsInterval` | `2` \| `5` \| `10` | `5` | seconds between device-stats reads while a stats surface is on a lit screen; other values read as 5 |
| `perfInterval` | `1` \| `2` | `2` | seconds between reads while a Perf view (Performance app, switcher overlay page or companion Perf mode) is shown; other values read as 2 |
| `companionView` | `"companion"` \| `"perf"` | `"companion"` | dual-screen devices: the other screen shows its usual panel or the Perf view. Unknown values read as `companion` |
| `gameAnnotations` | game id → annotation | `{}` | personal controller requirement (`controller`); entries without one are removed |
| `bookmarks` | game id → bookmark | `{}` | the one text bookmark per game: where the player is, for when they return; blank bookmarks are removed |
| `bookmarkInvitation` | bool | true | after a game Sortie saw end, offer to leave its bookmark (see [Bookmarks](#bookmarks)) |
| `gameProgress` | game id → progress | `{}` | explicit Finished/Shelved status, date and verdict; independent of favourites and queue |
| `referenceCards` | game id → cards[] | `{}` | personal Markdown/image reference cards; image files live separately under `reference/` |
| `magazineClippings` | clippings[] | `[]` | saved paragraph highlights and article recommendations, including copied excerpts and personal notes |
| `cocoonDir` | path \| absent | absent | Cocoon directory last imported from by "Import from Cocoon…"; offered first when the import is run again. Sortie only reads that directory |

## Controller requirement

Each annotation holds `controller` (`unknown`, `gamepad`, `pointer`, `motion`, `touch`). Unknown future values read as
`unknown`; an annotation that is `unknown` is removed. It never overwrites the guide's `controls`.

## Bookmarks

`bookmarks[id]` holds `text` (trimmed, 1–2,000 characters) and `savedAt` (epoch milliseconds of the save; `0` when unknown,
as for a bookmark migrated from an older `returnNote`). A game has at most one bookmark: Save replaces the previous text,
Cancel/B leaves it unchanged, and saving an empty draft (Clear, then Save) removes the entry. Longer drafts are refused, never
truncated. Bookmarks are personal and separate from the guide's authored blurbs, which they never change. The game page and
the companion screen show the bookmark; the browse lists' ✍ notes filter includes games that have one.

With `bookmarkInvitation` on, a small nonmodal card (*Leave a text note about where you are in the game for when you
return?* — **Leave a note** / **Not now**, L2 leaves a note) appears over Sortie's navigation after a game Sortie launched has
really ended. It takes no focus and opens no keyboard until **Leave a note**, which opens that game's bookmark editor. It is
shown once per ended session, only on the focused, resumed navigation with no switcher open (never on the companion, a
built-in app or a temporary Home), and counts as **Not now** after 45 seconds on screen or when anything else is launched.
A later launch Sortie cannot watch (an app, a shortcut, a game opened through Android's chooser) ends the earlier game's watch.

"Really ended" needs Shizuku. Shortly after a launch Sortie records the game's own task (the visible task of the launch
profile's package); the game has ended when a later, successfully read task list (one that shows Sortie's own task) no
longer holds that task. Returning to Home or the switcher while it runs, a failed or empty task read, a launch that never
produced a visible task, a quick exit (closed within 3 seconds) and sessions without Shizuku never produce the card: a
missed invitation is preferred to a wrong one. Nothing about the invitation is persisted.

## Older personal notes (archive)

Versions before bookmarks stored a weekly journal (`journalEntries`) and per-game `mappingNotes` and `returnNote` in this
file. When such a `user.json` loads, or a backup holding them is restored, Sortie first writes all of that writing to
`sortie/archive/personal-notes-<hash>.md` (readable: every note in full, journal entries with dates, linked game and session,
screenshot paths and sizes) and `personal-notes-<hash>.json` (`format: "sortie-personal-notes-archive"`, `version` 1,
`source`, `archivedAt`, plus the exact `gameNotes` and `journalEntries` values, unknown fields included). Both are fsynced;
the hash is of the content, so the same writing is archived once. Only then is the migrated file written: `returnNote`
becomes the game's bookmark (an existing bookmark wins), and `mappingNotes` and `journalEntries` are removed. If the archive
cannot be written, `user.json` is not written at all (saves fail and say so), and backup export, restore and Cocoon import
(whose safety copies would leave that writing out) are refused, until it can. Journal screenshot files under
`journal/media/` are never moved or deleted.

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

## Backup export and restore

Settings exports a JSON document through Android's file picker. The required fields are `format: "sortie-backup"`,
`version` (2; files of version 1 are still read), `createdAt` (epoch milliseconds), `user` (the full preferences object above)
and `sessions` (an array, possibly empty). `device` and `appVersion` identify the source. `openSessionExcluded` optionally
describes a running session omitted from export.

Version 2 adds `mecha`: the Hangar companion's whole save (`files/mecha/state.json`, with its own `version`, 1: name,
enabled/motion, fitted and owned parts, salvage, bond, charge, condition, credited-session keys `<gameId>@<startedAt>`), or
absent when no hangar was open. A `mecha` section is checked against this build's parts catalog (owned and fitted parts
exist, counters in range) before the restore is offered; a bad one rejects the whole file. The pet rate-limit time
(`files/mecha/pet.txt`) is not backed up.

Each closed session contains `gameId`, `profileId`, `startedAt`, `endedAt` and `abandoned`. Identity is `(gameId, startedAt)`,
not a database row ID. Unfinished sessions are never imported. Files over 64 MB, unsupported formats/versions, missing required
sections or invalid session timestamps are rejected before any writes; a missing `user` object is never interpreted as defaults.

Restore requires confirmation and no open play session. It first fsyncs the current preferences, history and companion to
`<card>/sortie/backups/pre-restore-<yyyyMMdd-HHmmss>.json` in the same format. Failure to save that copy prevents the restore.
Then the companion is restored, before any history is merged: a backup's `mecha` replaces the device's save, keeping every
session either side already credited as credited; a backup without one keeps the device's companion and marks the sessions it
merges as credited (they earned their salvage where they were played). No session earns salvage twice. If a later step fails,
the companion is put back.
Import from Cocoon writes the same kind of safety copy under its own prefix (`pre-cocoon-import-<yyyyMMdd-HHmmss>.json`);
restoring one brings the preferences from before the import back. It does not undo the import's other changes: added
systems, launch profiles and folders, copied artwork and imported sessions stay.
New sessions are inserted transactionally; existing device sessions, including newer ones, are kept unchanged. Preferences
are then replaced durably and published only after the write succeeds. If preference writing fails, imported rows are removed;
if that compensation also fails, the result reports the remaining imported sessions. Session logging is paused during restore.

Restoring the same backup twice adds no duplicate history. Restoring a safety copy recovers prior preferences, but cannot
remove sessions merged by an earlier restore. Room is authoritative for play statistics; the append-only `playlog.jsonl`
is never modified by restore.

Bookmarks, controller requirements, Finished/Shelved, reference cards and clippings are included in `user`. Reference images
(`reference/`), the personal-notes archive (`archive/`) and old journal screenshots (`journal/media/`) are **not embedded**:
copy these directories alongside the JSON, retaining their paths beneath `sortie/`. Missing reference images are reported by
the viewer; their text/metadata is not silently discarded.

A backup made by an older Sortie may still hold `journalEntries`, `mappingNotes` and `returnNote` in `user`. Its preview says
so; restoring it first archives that writing as described under [Older personal notes](#older-personal-notes-archive) (failure
to archive prevents the restore, nothing changed) and restores `returnNote` as the bookmark. Backups written now never hold
these fields.

Excluded: ROMs, artwork, emulator saves, the rebuilt game index, desktop manifests, `playlog.jsonl`, Android shortcut pins,
permissions and default-home selection. Stored app/shortcut IDs are retained, but may require reinstalling or re-pinning
their source before they resolve on another device.
