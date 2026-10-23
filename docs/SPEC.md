# Sortie: specification (living document)

## Purpose

Sortie is the on-device half of a two-part system. The desktop pipeline (library-tools) owns naming,
metadata, artwork, deduplication, backups and taste scoring; Sortie owns browsing and launching.
Sortie therefore reads files and writes very little: a play log and the user's on-device edits
(favourites, overrides), both as plain files that Syncthing can carry back to the desktop.

## Principles

1. **Fast over pretty.** Cold start under two seconds with 8,000 entries. No scanning at launch;
   an incremental index keyed on file path and modification time. Pre-sized thumbnails. Lists
   over carousels on small screens.
2. **Data lives on the card, as text.** Everything Sortie needs is in plain files under one root,
   authored on the desktop, delivered by Syncthing, hot-reloaded on change. The app is a renderer.
3. **No gatekeeping of launchers.** Any installed app can be a launcher for any system. Launch
   profiles are user data, not a database the app ships. Unknown emulators work through the
   generic "open with" path on day one.
4. **Controller first, touch second.** One consistent button grammar on every screen.
5. **Offline by construction.** The manifest declares no network permission.
6. **Release-ready hygiene.** Idiomatic Kotlin, ktlint, MIT, no secrets or device paths in code;
   device specifics live in the on-card manifest.

## Data model

Canonical inputs (read-only for Sortie):

- `roms/<system>/`, `big-roms/<system>/` : game files (as synced; 0-byte placeholders are ignored,
  `.syncthing.*.tmp` are shown as "arriving").
- `scraped-esde/gamelists/<system>/gamelist.xml` : ES-DE gamelist (name, desc, rating, releasedate,
  developer, publisher, genre, players). Only `<path>`, `<name>` and the listed fields are used.
- `scraped-esde/downloaded_media/<system>/{covers,screenshots,marquees,...}/<stem>.png|jpg`.
- `sortie/guide.json` : taste layer exported by the desktop guide builder (fit 1-5, hook, flags,
  tags) keyed by system + filename.
- `sortie/systems.json`, `sortie/launchers.json`, `sortie/folders.json`, `sortie/device.json` :
  see docs/formats.

Outputs (owned by Sortie, append-only or small):

- `sortie/playlog.jsonl` : one line per session (see formats).
- `sortie/user.json` : favourites, per-game overrides, hidden entries, last-selected positions.

Entities: System, Game (a launchable file with metadata), App (an installed Android app treated as
a launchable), Folder (a system folder, a smart folder, or a manual folder), LaunchProfile.

## Feature set

### Core (M1)
- System folders, one per configured system, ordered and named by `systems.json`.
- Game list per folder with cover thumbnail, title, fit stars, players, year, hack/translation badge.
- Instant fuzzy search across games and apps from any screen.
- Detail view: hero art, description, hook, tags, play time, last played, launch button, overrides.
- Launch through profiles with ordered fallbacks; per-game override; "open with" fallback.
- Favourites folder holding games and apps alike.
- Play log and "Continue" row.
- Diagnostics: launch log with the exact intent and its adb equivalent; "why did this fail".

### Smart folders (M2)
- Rules in `folders.json`: regex over name/path/system; predicates over genre, players, year,
  tags, fit, play time, last played, favourite, flags; AND/OR/NOT; manual include/exclude lists.
- Live counts; "Pick one" (random) scoped to any folder.
- Built-in examples: Unplayed 4+, Recently added, Short sessions, Two-player.

### Dual screen (M3)
- Roles: top = art and detail, bottom = navigation; swappable. Implemented with Presentation on the
  secondary display when present; single-screen layout otherwise.
- Launch target display per profile (ActivityOptions.setLaunchDisplayId).
- Stretch: move a running app between screens via Shizuku (shell privilege granted once per boot).

### Library hygiene (M3)
- Multi-disc grouping (.m3u or disc tags), duplicate-format hiding (PBP next to CHD), hacks
  grouped under base game, badges for missing BIOS, present save, arriving file, placeholder.

### Juice (M4, nice to have)
- Procedural theme engine: derive a palette from the selected game's cover (dominant and accent
  colours, contrast-checked) and apply it to the folder or detail screen. Cached per image.
- Optional HOME-intent mode so Sortie can be the default launcher.
- Per-device manifest for DMG, Vert, Nova (subsets, emulators, screen count).

## Non-goals (for now)
Scraping, cloud sync, RetroAchievements UI, themes marketplace, video previews, any network use.

## Controller grammar (draft)
A select/launch · B back · X context menu · Y favourite toggle · Start search · Select swap screen
roles · L1/R1 previous/next folder · L2/R2 page · Hold A: launch with profile picker.
