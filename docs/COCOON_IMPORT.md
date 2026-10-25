# Import from Cocoon…

Settings → **Import from Cocoon…** brings a library set up in the Cocoon launcher (`rip.moth.cocoonshell`, format observed
in Cocoon 3.07) into Sortie: systems and their ROM folders, emulators, favourites, hidden games and downloaded artwork, plus
your own folders, real play sessions and per-game emulator choices from a Cocoon backup if you pick one. It needs only the
all-files access Sortie already uses; Cocoon does not have to be installed or running, and no root is needed.

Step-by-step pictures for first-time users: [Getting started → Coming from Cocoon](GETTING_STARTED.md#coming-from-cocoon).

**Tested:** on an Android 13 emulator with an invented Cocoon library (fresh install, import with a backup, a second run,
Cancel, and a refused overlapping folder; Cocoon's files were byte-identical throughout). Physical-device results are not
claimed here. Capture details: [screenshots/cocoon/PROVENANCE.md](screenshots/cocoon/PROVENANCE.md).

Code: `app/src/main/java/io/github/esko997/sortie/data/cocoon/` and `ui/CocoonImportScreen.kt`; tests and fixtures under
`app/src/test/.../data/cocoon/` and `app/src/test/resources/cocoon/`.

## Using it

1. **Choose Cocoon's folder.** Sortie lists every folder it finds that holds Cocoon's `gamelists/` and selects the one used
   last time, or the one it found; when a folder is selected the screen opens at the backup step, with the **Cocoon folder**
   section below it. You can also type a path (absolute, or relative to the card root) or pick one with the file browser;
   Sortie reads the picked folder directly and keeps no access grant. A folder without a Cocoon library is refused with
   nothing changed.
2. **Optionally choose a backup** (make one in Cocoon → Settings), or continue without. Sortie offers the backups in the
   Cocoon folder (newest first) and any you type or pick under **Another backup…**: one `cocoon_backup_…` folder, a folder
   holding backups, or another Cocoon folder, on any storage (for example a copy in Download or on a memory card). A
   location without a usable backup is reported and nothing changes. Only a backup carries your own folders, play sessions
   and per-game emulator choices; anything made in Cocoon after the backup's date is not in it.
3. **Review the preview.** It lists systems to add (with their folders and emulator, and whether that emulator is installed),
   existing systems that only get a media folder, and additions versus entries already here for favourites, hidden games,
   per-game emulator choices, folders and sessions. Artwork is checked against free space on its volume. Nothing has been
   written yet; **Cancel** changes nothing. Broken library configuration must be repaired first. If the configuration changes
   after this preview, Import refuses it and asks you to review a fresh plan.
   If `folders.json` still holds Sortie's old default Favourites folder (see "Old Favourites folder entry"), the Favourites
   switch also removes that entry, even when there are no favourites to add.
4. **Import.** Sortie saves a safety copy, adds systems and re-indexes, then adds missing per-game launch profiles and saves
   favourites, hidden games and per-game choices (and, with Favourites on, removes the old default Favourites entry), adds sessions and folders, and copies
   artwork last. Only the artwork copy can be stopped; files already copied stay, and running the import again copies the
   rest. Leaving the screen does not interrupt the import while Sortie keeps running; if Android closes Sortie, run the
   import again and it continues where it stopped.
5. **Read the result.** It says what was added and what was skipped, what stays only in Cocoon, and, on its own line, how
   many old Favourites entries were removed. Running the import again adds nothing twice and finds no entry left to remove.

## What is imported

| From Cocoon | Becomes in Sortie | Needs a backup |
|---|---|---|
| Platforms with ROMs on this device | systems in `systems.json` (from Sortie's preset where one exists, otherwise a custom system), with the ROM folders found | no |
| Platform emulator (`<cocoon:defaultplayerid>`) | first in a newly created system's `profiles`; existing systems' defaults and profile order are untouched | no |
| Favourite / hidden flags | `favourites` / `hidden` in `user.json` (added, never removed). Hidden games stay in their system's game list; `hidden` keeps them out of folders and shelves, and Sortie has no unhide button yet | no |
| Downloaded box art, logos, gameplay screenshots | copies in the system's media folder as `covers/`, `marquees/`, `screenshots/` | no |
| User-made folders | `folders.json` entries `cocoon-<n>` with the matched games (a folder named "Favourites" too: it stays a user folder and does not favourite its games) | yes |
| Play sessions | sessions in Sortie's play database, with their real start and end times | yes |
| Per-game emulator choices | `profileOverrides` in `user.json`, only where you have none | yes |

Not imported: Cocoon's lifetime play counts and play time (shown in the preview only; see "Play history"), themes,
wallpapers, widgets, grid placement, custom display names and per-game "launch on external display". DroidDeck pins belong
to the launcher that requested them, so the result lists the DroidDeck games to pin again from DroidDeck once Sortie is the
home app.

## What it never changes

- **Cocoon's folder and the chosen backup are read-only.** Nothing in them is created, renamed, rewritten or deleted;
  artwork is copied, never moved or linked. Before the first write, `CocoonWriteGuard` checks every place the import
  writes (Sortie's folder, the safety-copy folder, media folders, the files it replaces such as the manifests,
  `user.json` and artwork targets, and their temporary files) with canonical paths and same-file checks against Cocoon's
  folder, the backup and the source files. It also refuses when a file Sortie writes in place (manifest staging copies,
  `user.json.tmp`, `playlog.jsonl`, leftover safety-copy or artwork `.tmp` files) is a symbolic or hard link, or its link
  count can't be read. If anything resolves into Cocoon's files this way (for example through `/sdcard` vs
  `/storage/emulated/0`, a bind mount or a link), the whole import is refused and nothing is written; replace linked files
  with plain ones or move Sortie's folders.
  The check assumes other processes do not replace destination paths during the import; it is not a filesystem lock.
- **Cocoon itself is left alone.** No intents are sent to it; it is not started, stopped or reconfigured, and its home or
  notification-listener roles are untouched. Its private app data is never read.
- **Your existing setup wins.** Existing systems are not renamed, extended, re-rooted or given a different emulator default.
  One may gain a media folder when it has none and artwork is copied for it; that targeted edit preserves other fields,
  including an intentionally empty emulator list. Existing launch profiles, artwork, sessions and customised folders are kept.
  With Favourites on, the redundant old default Favourites entry is removed as described below; actual favourite flags stay.
- **Artwork goes to each system's media folder**, which on a card prepared for ES-DE is that system's
  `scraped-esde/downloaded_media/<system>/` tree rather than `sortie/`. Tools that own that tree will see the copies.
- **No cross-device rewriting.** Cocoon records artwork as storage URIs tied to a device's volumes. A reference to a volume
  that is not mounted, or to a file that no longer exists, is counted as unavailable and skipped; paths are never rewritten
  to guess where another device kept a file.

## Old Favourites folder entry

Older Sortie setups wrote a `folders.json` entry with the id `favourites` (every favourite, apps included, by title). The
built-in Favourites folder now has that id and lists the same favourites from `user.json`, so Sortie ignores the entry
and reports it as a folder rule problem. When the preview finds entries that only repeat that old default (the store's
`LibraryConfiguration.legacyFavourites` count), the Favourites switch removes them after the write guard and the safety
copy, through `LibrarySetupStore.removeLegacyFavourites` on a freshly loaded configuration: one fingerprint-checked
`ManifestCommit` that keeps every other folder and unknown key. With the switch off, nothing is removed. A `folders.json`
that changed meanwhile or can't be read is left as it is and the import stops with an error. Favourite flags in `user.json` are never
changed by this. Entries that use a built-in folder's id (`favourites`, `want-to-play`, `finished`, `shelved`) but differ
from the old default are customised: the import never removes them, the preview lists them in its notes, and Sortie keeps
reporting them on that built-in folder until they get another id. `folders.json` is not in the safety copy; the removed
entry only repeated the built-in folder.

## Safety copy and undo

Before writing, Sortie saves `<card>/sortie/backups/pre-cocoon-import-<yyyyMMdd-HHmmss>.json`. It holds **your preferences
(`user.json`) and play sessions only**, in the Backup and restore format; if it cannot be written, the import does not start.
Restoring it (Settings → Backup and restore) brings back the preferences from before the import: favourites, hidden games,
emulator choices and per-game overrides. It is not a full rollback:

- systems and launch profiles added to `systems.json`/`launchers.json`, media folders set on existing systems, and
  `cocoon-<n>` folders in `folders.json` stay; remove them in Library setup or by editing those files. A removed old
  default Favourites entry is not brought back, and is not needed;
- copied artwork stays in the media folders;
- imported play sessions stay, because a restore only adds sessions and never deletes existing ones.

There is no complete rollback. A copy of the card's `sortie/` folder made before the import restores configuration
(`systems.json`, `launchers.json`, `folders.json`, `user.json`; re-index afterwards), but imported sessions are stored in
Sortie's app-private play database (Room, `sortie.db`) and copied artwork may sit outside `sortie/` (for example in an
ES-DE `downloaded_media` tree). Neither is removed by restoring files or a backup. To keep those irreversible parts out,
turn off the preview's play-session and artwork switches.

## Cocoon's folder after importing

Imported artwork is copied, but the import is not a complete migration or an audit of pre-existing paths into Cocoon.
Before uninstalling Cocoon or deleting its folder, check in Sortie that your games, favourites, folders and play history
came over. Entries that were not matched or were ambiguous, custom titles, themes, artwork that was stopped, failed or
skipped, and anything played after the backup was made stay only in Cocoon, and re-running the import needs Cocoon's
files. Sortie does not check what uninstalling Cocoon does to that folder on your device; keep a Cocoon backup and the
folder until you are sure.

## Play history

Sortie derives "last played", play counts and the journal's per-day play time from individual sessions
(`GameDao.playStats()` uses `MAX(startedAt)`; `JournalWeek` spreads a session's time over the days it covered). Cocoon's
gamelists only hold lifetime totals (`playcount`, `lastplayed`, `cocoon:playtime`); a session made up from them would move
"last played", put all historic minutes on one day and collapse the play count to 1. So the totals are shown in the preview
and never imported. Real sessions come only from a backup's play history, deduplicated by game and start time, with the
launch profile matched by emulator package where Sortie has one.

## How it works (developers)

### Finding Cocoon's folder

Candidates are `<volume>/Cocoon` for `/storage/emulated/0` and every readable `/storage/<volume-id>`, then any directory up
to two levels deep on each volume (skipping `Android/` and dot directories). A candidate counts only when one of its
`gamelists/<platform>/gamelist.xml` files starts with a `<cocoon:` tag: an ES-DE tree has the same layout with plain files
and must not be offered. A tree picked with `OpenDocumentTree` on `com.android.externalstorage.documents` is converted to a
path (`primary:` → `/storage/emulated/0`, `<volume-id>:` → `/storage/<volume-id>`) and read with plain file APIs. The folder
used is stored as `cocoonDir` in `user.json` ([formats/user.md](formats/user.md)).

### Cocoon's files

- `gamelists/<platform>/gamelist.xml`: ES-DE gamelists with `cocoon:`-prefixed extension tags (prefix undeclared) and, in
  some files, unescaped `&` in `<path>` or names, which a strict XML parser rejects. Sortie's strict `GamelistParser` is
  unchanged. `CocoonGamelistReader` parses each file as written and, only if that fails, repairs text and attribute
  ampersands in memory. XML's five built-in entities and numeric references, CDATA, comments and processing instructions
  are preserved. The parser accepts the undeclared prefix without changing it. It reads `favorite`, `hidden`, `playcount`,
  `lastplayed`, `cocoon:playtime`, `cocoon:boxartlocal`,
  `cocoon:logolocal`, `cocoon:screenshotgameplaylocal` and the file-level `<cocoon:defaultplayerid>`. A file that still
  fails (for example a raw `<` in text) is listed in the preview and the others load. `pod` (Cocoon's own screens) is
  skipped and `shortcut` (DroidDeck pins) becomes re-pin advice.
- `downloaded_media/<platform>/{icon,logo,screenshot_gameplay,…}/<rom stem>__cocoon_edit_<n>_<hash>.<ext>`: copied to
  `<media>/covers|marquees|screenshots/<rom stem>.<ext>` so Sortie's exact-stem media lookup finds them. Sortie has no hero
  slot, so `hero/` is not copied. Art Cocoon references inside an ES-DE `downloaded_media` tree that the system already uses
  is not copied again.
- A backup folder (`cocoon_backup_<date>/`, found under `<cocoon>/backups/`, in the Cocoon folder itself, or under a
  location the user gives) with `manifest.json` `format: "cocoon_unified_backup"`:
  `categories/layout/grid_v1.json` (folders with `is_smart_folder: false`, their `membership`, `game_flags.is_hidden`),
  `categories/play_history/play_history_v1.jsonl` (`session` records; `aggregate` records are ignored) and
  `categories/metadata/metadata_v1.jsonl` (`fields.playerId` per-game emulator, `fields.favorite`, and
  `identity.normalized_document_id`, used as a root hint). Other categories are ignored.
- `permissions_debug.txt`, if present, lists Cocoon's persisted folder grants; they are used as root hints only.

`<cocoon:defaultplayerid>` maps to Sortie profiles in `CocoonAliases`: `<plat>.ra.<core>` → `ra-<core>`,
`<plat>.ra64.<core>` → `ra64-<core>`, `<plat>.<package>` → the preset profile for that package, plus aliases (for example
`azahar` → Lime3DS's package, `mamearcade` → RetroArch's `mame` core). The platform prefix is ignored because it is not
always the folder name. RetroArch cores without a preset profile get one from `LibraryPresets.retroArch`; `__none__` and
apps no preset describes produce no profile, and the preview says to choose one in Library setup.

### Roots, systems and matching

Cocoon records each game as `(platform, ./file)` relative to a root it does not store, and one platform can span several
folders (`tg16` = `pcengine` + `tg16`; `fbneo` ↔ `arcade`; `ngpc` ↔ `ngp`). `RootProbe` lists directories up to three levels
deep on each volume (skipping `Android/`, dot directories and Cocoon's folder) and keeps, per platform, hint directories
(Cocoon's folder grants, the backup's document ids and configured systems' roots within the platform's alias group) with at
least one of its file names, and other directories holding at least 10 % of them (1 when it has fewer than 10).

`CocoonPlanner` then:

1. maps a found directory already in a configured system to that system, unchanged, but only when that system's `id` or
   `folder` is in the platform's alias group (a system `md` rooted at `roms/megadrive` is not recognised for `genesis`, and
   that platform's entries are then reported as not found);
2. lets an unowned directory be claimed by the platform with most games there, only when the directory is named for the
   platform's alias group, or at least half its files are that platform's games and its name is not another ES-DE system
   name (the list in ES-DE's Android `es_systems.xml`). Shared romset names (ZX Spectrum ports of arcade games, Game Gear
   and Genesis titles) therefore cannot hand a whole folder to the wrong system;
3. creates one system per claimed id: the preset id from the alias table, else the platform id, else `<platform>-cocoon`
   when that id is taken. Presets keep their profiles with Cocoon's emulator first and add matched file extensions, such
   as `.7z`, so the index can retain those games. To avoid exposing individual disc tracks as games, track types are not
   added when the system's folders contain disc descriptors or playlists; those skipped matches are listed in the preview.
   This is a conservative system-wide check, not a parser of each descriptor's references. Custom systems use the matched
   files' extensions and Cocoon's emulator, or Open with… when there is none. `media` is an existing ES-DE
   `scraped-esde/downloaded_media/<name>` under the card root when present, else `sortie/media/<systemId>`; `gamelist` is
   set only when an ES-DE gamelist exists there (Cocoon's own gamelists are not used as Sortie gamelists);
4. matches each entry only within its platform's alias group, then by exact case. The platform's own folders and those of
   its preset alias are equal candidates (a file in both `tg16/` and `pcengine/` is ambiguous). Several equal candidates
   make an entry ambiguous; ambiguous and missing entries are listed and their flags dropped, never guessed.

Android apps from a backup become `app:<package>` ids. Ids are checked against Sortie's index after the systems step, so
Sortie's extension and skip rules apply.

For new systems, the preview reports an installed equivalent RetroArch build when Cocoon's requested package is absent.
The core name is unchanged, but installed does not prove that the core, BIOS or saves are available in that build.
Per-game backup choices can add a missing RetroArch profile as well; the definition is saved before the override and only
when that category is enabled. Existing profile definitions and per-game choices are preserved.

### Apply order

`CocoonImporter.apply`: configuration fingerprint/blocking check → write guard → safety copy → systems and targeted media
folders (fingerprint-checked `LibrarySetupStore` edits, atomic `ManifestCommit`) → index → missing per-game profiles →
one `user.json` commit (favourites ∪, hidden ∪, per-game overrides only where absent, `cocoonDir`) → old default Favourites entries (`LibrarySetupStore.removeLegacyFavourites`, only with
Favourites on and a count in the preview; a refused save stops the import) → sessions (`planSessionMerge`,
inserted while the play log is paused) → folders (`LibrarySetupStore.saveFolders`; an existing folder of the same id keeps
its name and rules and gains members) → artwork copy (temp file then rename per file, existing targets kept, stoppable
between files) → re-index. Everything before the artwork copy runs to completion even if the screen is left; a failure
stops the import and reports what was already done.

### Acceptance check on a device

Hash file contents, not metadata: `find <cocoon> -type f -exec sha256sum {} \; | sort -k2` before and after an import of
every category (and a re-run) must match, and Cocoon must still open its library. While Cocoon is installed its own process
appends to `cocoon-database-log.txt` and `scan_debug.log` (its catalogue sync reacts to any package install, including
Sortie's), so exclude those two. Use `-exec … \;`: some Android `find` builds fail with `-exec … +` or `xargs` on many file
names containing spaces.

The legacy Favourites cleanup was exercised on an isolated Android 13 emulator with a synthetic Cocoon export containing
no new favourites: the preview still offered cleanup, opting out left `folders.json` byte-identical, opting in removed
only the redundant default, and repeating the import made no further folder changes. Existing favourite flags, a custom
folder, unknown root fields and all source XML/artwork bytes were preserved. The accompanying build passed style checks
and 261 JVM tests in each variant, including partial configurations, customised entries, concurrent edits and write failures.

The subsequent import-safety fixes passed style checks and 289 JVM tests in each variant. On a single-screen Android
handheld, an in-place debug upgrade and real-library import/re-import preserved existing system defaults and showed
already-imported favourites, hidden entries and folders correctly. A separate, disposable library exercised:

- an existing emulator default that differs from Cocoon's, without a new Settings override;
- a preset N64 `.7z` entry retained by the real index, including its favourite, folder membership and play session;
- artwork for an existing system with `profiles: []`, preserving that list and unknown manifest fields;
- a missing per-game RetroArch core profile, and an installed equivalent build for an absent requested package;
- stale root ownership requiring a fresh preview and explicit confirmation, and malformed launchers blocking the import
  before preferences or a safety copy were written;
- accurate first-import and zero-addition repeat counts, with three copied images matching their source bytes;
- real Android chooser picks for explicit, per-game and deliberately chooser-only launches leaving defaults alone.
  A fallback through an unavailable emulator saved only a Settings default, preserved the wildcard MIME on relaunch,
  and was undone through **Manifest default**. A manifest edit while the chooser was open prevented a stale pick from
  becoming a default.

The chooser recipient was a file manager, not an emulator: these checks prove dispatch and persistence, not game/core
compatibility. The test library was removed and the original card configuration, preferences and private database restored.
All 1,338 original Cocoon files remained present; only Cocoon's own database log changed. Real artwork references to an
absent volume could not be copied, so artwork copying used the isolated fixture. Removable-card mapping and dual-display
behavior still need a dual-screen device check; the production handheld was not used for this regression round.
