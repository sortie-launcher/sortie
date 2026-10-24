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
- `sortie/magazines/<issue-id>/issue.json`, Markdown articles and local images: offline recommendation issues (format v1,
  see formats/README.md). Reading positions are app-private preferences, separate from card data and exported backups.

Outputs (owned by Sortie, append-only or small):

- `sortie/playlog.jsonl` : one line per session (see formats).
- `sortie/user.json` : favourites, per-game overrides, hidden entries, home sort and custom layout, list/grid choices,
  opted-in launcher shortcuts (see formats/user.md).

Entities: System, Game (a launchable file with metadata), App (an installed Android app treated as
a launchable), Folder (a system folder, a smart folder, or a manual folder), LaunchProfile.

## Feature set

### Core (M1)
- System folders, one per configured system, ordered and named by `systems.json`.
- Game list per folder with cover thumbnail, title, fit stars, players, year, hack/translation badge.
- Search across games with optional text; OR within systems/tags/genres, AND between dimensions. Tags include game-page flags.
  More filters: favourites, Want to play, never launched, fit ≥ 4, on-device-only (default). Filter before paging; show full count.
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

### Dual screen (M3, revised in feedback round 2)
- Roles: top = art and detail, bottom = navigation; swappable. The companion panel is an activity on whichever screen the
  navigation is not on: `CompanionActivity` on the default display, `SecondaryHomeActivity` (SECONDARY_HOME) on the second,
  so the second screen always falls back to Sortie when an emulator exits. Single-screen layout otherwise.
- Launch target display per profile (ActivityOptions.setLaunchDisplayId).
- Controller anchoring: the controller follows the game's display. Companion screens are touch only and hand the controller
  back to the other screen when they appear or are tapped (`AnchorActivity`).
- App switcher (Shizuku): Home while something runs shows each screen's running apps on both screens; apps can be raised,
  moved between screens, swapped or closed. Companion-side carousel is touch, navigation-side is controller.
- Opening the overlay preserves the foreground Sortie surface when Home covers navigation or a companion; otherwise the
  external app is restored beneath it. A primary HOME instance created after X relays to existing navigation rather than
  replacing it, including after swapping back. B leaves that page/display intact.
- Only HOME/SECONDARY_HOME intents marked `android.intent.extra.FROM_HOME_KEY` summon the overlay (or Sortie's explicit
  switcher request); framework fallback launches do not. A temporary primary HOME focuses the existing own task with
  `ActivityManager.AppTask.moveToFront()` or starts its companion before finishing. If neither succeeds, it remains a usable
  Home instead of leaving an empty focused Home task that the framework would continually relaunch.

### Home and browse layout (feedback round 3)
- Home sorts: Alphabetical, Game count and Generation are generated, fixed and systems-only. Custom retains the user's pin
  order and automatically includes every smart folder, Apps and Continue (the last game played, first unless moved).
  By Console shows a console strip and its games. Sort chips select the view.
- "Add to home" / "Remove from home" on systems, games, apps and shortcuts (hold A or long press). Smart folders, Apps and
  Continue always live on Custom and cannot be removed. Every Custom tile supports Move: the lifted tile has a blue glow,
  the D-pad moves it one slot per press, A drops, B cancels; touch drops it on the tapped tile.
- Browse screens (system lists, folders, search, Apps) share one list↔grid setting. Grid tiles are 1:1: cropped cover with
  the title over a bottom gradient, ★ when favourited, focus glow in the cover's palette accent; apps and shortcuts show their
  icon on slate with the label below. Columns = `gridColumns`.
- Launcher shortcuts are opt-in: Apps lists only `user.shortcuts` (pinned to Sortie or added with "Add shortcut from…" are
  opted in automatically; "Browse all shortcuts…" toggles the rest). Smart folders see a shortcut only if it is opted in,
  favourited or placed on home.
- Home has a direct Manual book button beside Settings and a Magazines entry.
- Offline magazines: shelf → contents → article, B one level back; article D-pad scroll, Left/Right page, L1/R1 previous/next
  article, A library page. Saved scroll positions and Continue reading survive reopening; X retains the current article.
  Bad issues are isolated, missing images do not block text, and paths cannot escape an issue. No WebView or remote fetching.
  While an issue is open, the other display shows section jumps/current heading, article selection and previous/next controls.
  The panel follows navigation across X; session-owned actions reject stale taps, and leaving the issue clears the panel.

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

## Controller grammar
A select/launch · hold A context sheet (Favourite · Add to / Remove from home on games, apps and shortcuts; Add to home on
Home's systems and folders; Move · Remove on Custom home tiles) · B back · X swap screen roles · Y favourite toggle (detail) /
search (on Home) / other screen (switcher) · Start search (not available while Sortie is the home app: Android intercepts it) ·
Select launch-profile sheet (detail) / list↔grid (Home, system and folder lists, search, Apps) / close app (switcher) · L1/R1
previous/next system · Home (physical) app switcher when something is running. Moving a Custom home tile: D-pad one slot per
press, A drop, B cancel. Long press = hold A for touch.
