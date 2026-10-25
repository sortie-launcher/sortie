# Sortie: specification (living document)

## Purpose

Sortie browses and launches a handheld's game library. The library can be set up entirely on the device (Library setup), or
curated on a desktop by the user's own tooling, which then owns naming, metadata, artwork, deduplication, backups and taste
scoring. Either way Sortie reads files and writes very little: its configuration (when set up on the device), a play log and
the user's on-device edits (favourites, overrides), all as plain files that any sync tool can carry back to a desktop.

## Principles

1. **Fast over pretty.** Cold start under two seconds with 8,000 entries. No scanning at launch;
   an incremental index keyed on file path and modification time. Pre-sized thumbnails. Lists
   over carousels on small screens.
2. **Data lives on the card, as text.** Everything Sortie needs is in plain files under one root,
   written by the Library screen or delivered from a desktop, hot-reloaded on change. The app is a renderer.
3. **No gatekeeping of launchers.** Any installed app can be a launcher for any system. Launch
   profiles are user data, not a database the app ships. Unknown emulators work through the
   generic "open with" path on day one.
4. **Controller first, touch second.** One consistent button grammar on every screen.
5. **Offline by construction.** The manifest declares no network permission.
6. **Release-ready hygiene.** Idiomatic Kotlin, ktlint, MIT, no secrets or device paths in code;
   device specifics live in the on-card manifest.

## Data model

Canonical inputs (read-only for Sortie; the folder names below are examples, each system's paths come from `systems.json`):

- `roms/<system>/` and similar per-system folders: game files (0-byte placeholders are ignored, in-progress sync temp
  files such as `.syncthing.*.tmp` are shown as "arriving").
- `scraped-esde/gamelists/<system>/gamelist.xml` : ES-DE gamelist (name, desc, rating, releasedate,
  developer, publisher, genre, players). Only `<path>`, `<name>` and the listed fields are used.
- `scraped-esde/downloaded_media/<system>/{covers,screenshots,marquees,...}/<stem>.png|jpg`.
- `sortie/guide.json` : optional taste layer from a desktop guide builder (fit 1-5, hook, flags,
  tags) keyed by system + filename.
- `sortie/systems.json`, `sortie/launchers.json`, `sortie/folders.json`, `sortie/device.json` :
  see docs/formats.
- APK assets at `magazines/<issue-id>/` (only in builds made with a magazine content directory; see RELEASE.md) and
  optional card packs at `sortie/magazines/<issue-id>/`: offline zines with `issue.json`, Markdown and local images
  (format v2; v1 external packs are imported). See formats/README.md.
  Reading positions are app-private preferences, separate from card data and exported backups.

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
- App switcher (Shizuku): Home shows each screen's running apps on both screens, including while reading or when no external
  app is running; apps can be opened, raised, moved between screens, swapped or closed. Companion-side carousel is touch,
  navigation-side is controller. Shizuku must authorize the current Sortie installation, not merely be running.
- Opening the overlay preserves the foreground Sortie surface when Home covers navigation or a companion; otherwise the
  external app is restored beneath it. A primary HOME instance created after X relays to existing navigation rather than
  replacing it, including after swapping back. B leaves that page/display intact.
- Only HOME/SECONDARY_HOME intents marked `android.intent.extra.FROM_HOME_KEY` summon the overlay (or Sortie's explicit
  switcher request); framework fallback launches do not. A temporary primary HOME focuses the existing own task with
  `ActivityManager.AppTask.moveToFront()` or starts its companion before finishing. If neither succeeds, it remains a usable
  Home instead of leaving an empty focused Home task that the framework would continually relaunch.
- AYN panel guard (Thor): the vendor's Thor-button panel leaks one network callback per opening (system uid cap 250, shared
  with system_server). At the cap its next opening crashes inside `onAttachedToWindow`, its crash handler keeps the process,
  and the undrawn window keeps input focus on its display, so Sortie there gets "no focused window" ANRs. With Shizuku,
  when the shell connects and when Sortie returns (at most once a minute), `ScreenMover.guardVendorPanel` restarts it with
  `am crash` at 150 requests while no panel window has a surface, or after seeing its focused, surfaceless window twice.
  See docs/HARDENING.md.

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
  article, A game references/library pages (never launch). Saved scroll positions and Continue reading survive reopening;
  X retains the current article. Issue contents list all mentioned games, with an On this device filter. Game detail's
  Featured in opens exact articles and B returns to the game. References distinguish platforms/editions from local filenames;
  several matches remain choices, while placeholders, missing and arriving files are not presented as available.
  APK packs extract atomically into private storage once per catalog digest. The shelf merges by stable issue ID: higher
  revision wins, card wins ties; no card pack is overwritten. A malformed card copy cannot hide a valid bundled issue.
  Bad issues are isolated, missing optional images do not block text, and paths cannot escape an issue. No WebView or remote fetching.
  Shelf entries and the contents masthead show the issue number large ("No. 010": the pack's `number`, else the id's trailing
  digits, else none); the shelf is newest date first, then number, then id.
  Figures are sized from their pixel dimensions (read before the article is shown, so placeholders already have the final
  size and restored positions never shift): text-measure width, full reading width for wide pictures, at most 70% of the
  viewport tall (lead image 55%), whole-number enlargement and no smoothing for small pixel art, caption at the picture's
  width. A tap, or R2 on the picture most in view (hinted), opens a full-screen viewer over the unmoved article: pinch,
  double tap, A and L2/R2 zoom, D-pad pans when zoomed, Left/Right and L1/R1 step pictures, Y toggles the caption, B/Back/tap
  closes. It is a dialog of the reader's window, so it opens on the reader's display; the companion shows no pictures.
  While an issue is open, the other display shows section jumps/current heading, article selection and previous/next controls.
  The panel follows navigation across X; session-owned actions reject stale taps, and leaving the issue clears the panel.
  Its bottom-left + opens an app picker for that display; launching returns controller focus to the reader. B or Close
  cancels without losing the contents tab or list position. This also works with the reader on the bottom display.

  Thor acceptance: opened Prime Video below the article and Clock above it after X; the article remained on its own
  display. Picker cancellation preserved the Articles tab and scrolled Sections list pixel-for-pixel. Home opened the
  overlay from both reader placements, and B returned to the article; denying overlay permission used the ordinary
  switcher page instead. Video playback/DRM was not exercised. Shizuku authorization had to be renewed after an earlier
  uninstall/reinstall: Android's package-permission listing alone did not establish Shizuku authorization.

### Hangar companion (experimental)

- Lives behind the interface on Home (navigation display) and on the other display's idle panel, never in a strip of its
  own. `MechaStageHost` (in `:mecha`) wraps a window's content: the mech is drawn under it (every tile, control and text
  wins; occlusion makes peeking free) and only a speech bubble is drawn over it, on empty space. Screens only declare
  spots with `Modifier.mechaSpot(id, kind)` / `mechaObstacle(id)`; every declared element is also an obstacle.
  Spot kinds: LEDGE (on an element's top edge), PEEK (head above an element's top edge, the rest clipped away),
  BESIDE_END/START (on its bottom line, just outside it), ROOM (inside empty space), EDGE_START/END (partly off screen at
  the edge, standing on the element: the way in and out). A spot counts only while its element is laid out and whole on
  screen, the exposed art is inside the window and clear of every other element, and at least 40 dp × 40 dp of him shows
  free of controls (so he is always tappable; a peek shows as many rows as that takes: head and shoulders). Scale: the
  smallest whole multiple of the 64 px frame at least 9 mm tall from the display's real dpi (`ydpi`, or the density bucket
  when it reports nonsense): 2× on the Thor, DMG and Nova; one step smaller only if fewer than two spots qualify.
- Home base: when no spot qualifies he stands in empty space the host reserves for him (`mechaBaseSize`,
  `Modifier.mechaHomeBase`): on Home at the end of the hint bar row, on the other display under the bell (the picture keeps
  clear of it). A hidden companion reserves nothing. If even the base cannot hold him he hides; he is never visible and
  untappable.
- Home spots: after the clock/game count, on the header actions when they wrap below the title, after the sort chips,
  over every laid-out tile (in practice the first visible row: elsewhere the head would be inside another tile), on the
  hint bar, both edges along the hint bar. Other display: after the battery, before the bell, on and after **Want to
  play**, over the hero picture (sized to the picture's real aspect ratio), before the **+**, both edges just above the
  bottom. Text, the dock and the detail block are obstacles.
- One mech per process (`MechaPresence`): he is on one display; the latest resumed, allowed stage there draws him. He
  stays on that display while its window is paused, adopts the latest active window when his display has none left, and
  changes display only by walking off one screen's edge into the other's (one stroll in three after two strolls on a
  screen, when the other screen is up and has room; or at once when nothing fits here and the other has room).
  Single-screen devices keep him on Home. The other display's stage is inactive while a game is in front.
- Movement: a stroll every 45–90 s while the stage is resumed: walking (3 frame pixels per 120 ms walk frame) along a
  shared floor up to half the screen, a seven-step hop for a small nearby step, otherwise sinking out of sight through
  his floor line and rising into the next spot. With nothing qualifying he stands at the home base, or stays out of
  sight. A spot that loses its room (scrolling, rewrapping) makes him sink away; he comes back to a qualifying place once
  the layout has been still for 1.2 s. He follows his element while it moves. Motion off: always the first qualifying
  place in declaration order (deterministic for screenshots).
- Reactions: the first look after a play session closes (any session, games or apps) greets the player with a cheer and
  "Welcome back!" plus the store's credit message; other store messages (care, salvage, rename, equipment) show in a
  5 s bubble after the Hangar closes. A bubble only appears beside him on empty space; he moves to a spot with room or
  skips it. Three minutes without key or touch input: he dozes (still SLEEP frame) until the next input. Resting shows
  SLEEP and never strolls; motion off places him still at a spot with no walking or cheering; hidden: nothing roams.
- Cost: positions and frames are snapshot state read only while drawing the layer; movement and cheers are the only
  frame bursts. Idle, dozing, off screen, paused or behind a game: no frames, no timers but the next stroll.
- Access: **Hangar** in Home's header (controller; focus returns to it on close), a tap on the visible mech (watched at the
  host in the Initial pass: a short low-movement tap whose down landed on his target opens the Hangar and consumes only
  its release; drags pass through untouched). The tap target is the exposed art widened to the frame where that touches
  no control (at least 40 dp each way, which every place he stands guarantees). The Hangar holds Animate and Hide/Show companion. Reader
  Select → Visit the hangar keeps its controller access. The Hangar dialog stays mounted while its rename field uses the
  keyboard.
- Roaming acceptance (`0.1.0-mecha-roam` debug on the DMG, `0.1.0-beta.1044-roam.1` on the Thor): DMG Home at the clock,
  on the wrapped header buttons, on the hint bar (list view) and peeking over first-row tiles (grid view); peek-to-peek
  strolls; 0 frames in 10 s windows between strolls, while dozing (only the clock's minute tick) and with motion off; a
  real 70 s game session greeted with "Welcome back!" plus the credit message; Home Hangar button by controller with focus
  returned on B; tap on him opens the Hangar, a tap beside him reaches the row, a drag starting on him scrolls the list;
  hide removes him, show brings him back; name/parts/salvage unchanged. Thor: Mica at scale 2 beside the sort chips and
  over tiles on top; leaving Home sent him to the bottom panel (battery, Want to play, +); two X swaps showed one mech and
  no crash. The bottom panel's rotating hero still redraws on its own timer (not the mech).
- One named companion, cosmetic Bond/Charge/Condition, tune/charge/rest interactions, motion toggle and hide/show.
  No time-away decay, death, streaks, notifications, network calls or requirement to care before playing.
- Three original pixel-art families (yard, survey, courier), two variants for each head/body/arms/legs slot: 24 authored
  parts and 1,296 possible loadouts. Eight 64×64 frames per transparent 512×64 atlas, shared attachment points, four poses.
  Grounded sole fill y61, outline y62, y63 clear. Nearest-neighbour integer display scaling; sheets decoded once off-main.
  Family-local Pillow generators in `tools/mecha/` reproduce the assets (`mecha/src/main/assets/mecha/`) and review boards.
  The companion is the `:mecha` module (see the code map below).
- Twelve starter parts and three clearly labelled starter salvage credits. A deterministic salvage choice finds an unowned
  part for one credit; completed collections cost nothing. Gallery previews all parts but cannot equip unowned ones.
- Room's completed, non-abandoned game sessions after initialization earn one credit per five accumulated whole minutes,
  capped at 120 counted minutes per session. App/shortcut sessions and prior history do not count. Game ID plus start time
  prevents double credit after reopening or re-importing history. This measures recorded session duration, not active input.
  Charge drain uses accumulated-minute boundaries, independent of how Room batches session notifications.
- State, inventory and credited-session identities are atomically saved together in app-private `files/mecha/state.json`.
  Failed writes leave the prior state intact; unreadable saves require explicit recovery and are preserved before starting
  fresh. No writes to Cocoon, library manifests, user preferences or play log come from the companion.
- Limits: a finite authored collection, not arbitrary generated artwork or statistical part affixes; no combat or
  test-course simulation. The existing preference/history backup does **not** include the companion save yet.
  Preserve app data; do not uninstall to change builds.
- DMG acceptance for `0.1.0-mecha-lab.2` (dock era): rename/care/salvage/equipment, unowned-part preview restrictions,
  four animated poses and hide/show persistence exercised on device; reader Select → Hangar → Gallery → B → B preserves the
  article position and resumes D-pad scrolling.
  A real 310-second game session earned exactly one credit; process restart and in-place upgrade preserved inventory and
  did not repeat the reward. Background frame count stayed fixed with motion enabled. Motion-off sprites stayed still.
  Both JVM variants passed 308 tests. Original card configuration/preferences, all 10,154 game/path rows and 130 history
  rows were preserved; the three smoke sessions were the only appended history.

### Library hygiene (M3)
- Multi-disc grouping (.m3u or disc tags), duplicate-format hiding (PBP next to CHD), hacks
  grouped under base game, badges for missing BIOS, present save, arriving file, placeholder.

### Juice (M4, nice to have)
- Procedural theme engine: derive a palette from the selected game's cover (dominant and accent
  colours, contrast-checked) and apply it to the folder or detail screen. Cached per image.
- Optional HOME-intent mode so Sortie can be the default launcher.
- Per-device manifests (subsets, emulators, screen count) for several handhelds sharing one desktop library.

## Non-goals (for now)
Scraping, cloud sync, RetroAchievements UI, themes marketplace, video previews, any network use.

## Controller grammar
A select/launch · hold A context sheet (Favourite · Add to / Remove from home on games, apps and shortcuts; Add to home on
Home's systems and folders; Move · Remove on Custom home tiles) · B back · X swap screen roles · Y favourite toggle (detail) /
search (on Home) / other screen (switcher) · Start search (not available while Sortie is the home app: Android intercepts it) ·
Select launch-profile sheet (detail) / list↔grid (Home, system and folder lists, search, Apps) / close app (switcher) · L1/R1
previous/next system · Home (physical) app switcher when Shizuku is available, including while reading. Moving a Custom home tile: D-pad one slot per
press, A drop, B cancel. Long press = hold A for touch.

## Implementation notes for contributors

Code map (`app/src/main/java/io/github/esko997/sortie/`):

- `SortieApp.kt` service locator (`graph`): manifest/user/play-log stores, Room database, indexer, launcher, folders, apps,
  device state and the Shizuku screen mover. The StrictMode file-URI check is relaxed here because emulators need `file://`.
- `MainActivity.kt` navigation, HOME role, debug-only intent hooks and X screen swap (the saved state is handed to the new
  instance in process memory). `SecondaryHomeActivity.kt` / `CompanionActivity.kt` host the other-screen companion;
  `AnchorActivity.kt` moves controller focus to a display; `ShortcutPinActivity.kt` confirms `requestPinShortcut`;
  `RestartActivity.kt` restarts the main process once storage access arrives.
- `data/manifest/` JSON models and the hot-reloading `ManifestStore`; `data/setup/` on-device Library setup (presets,
  merge-preserving transactional manifest commits); `data/index/` incremental indexer, disc/duplicate variants and BIOS/save
  state; `data/db/` Room; `data/folders/` smart-folder rule engine; `data/user/` `user.json` and `playlog.jsonl`;
  `data/backup/` export/restore; `data/magazine/`, `data/reference/`, `data/journal/` the reading, reference-card and
  journal features; `data/apps/` installed apps and launcher shortcuts.
- `launch/` intent building and launching with fallbacks; `shell/` the Shizuku user service, screen mover and app switcher;
  `ui/` Compose screens (`ManualScreen` renders `assets/MANUAL.md`).
- `design/` library module (`:design`, still package `io.github.esko997.sortie.ui`): theme colours, `SortieTheme` and the
  controller-focus building blocks (`focusGlow`, `keyLongPress`, `FocusRow`) shared by the app and feature modules.
- `mecha/` library module (`:mecha`, package `io.github.esko997.sortie.mecha`): the Hangar companion's rules, save,
  parts catalog and art (`mecha/src/main/assets/mecha/`), roaming (`MechaRoam` geometry/choices, `ui/MechaStage` host
  and spot modifiers, `ui/MechaDirector` behaviour, `ui/MechaPresence` one mech across displays) and Hangar UI. It knows
  no Room, app or navigation types: `SortieApp` maps sessions to `PlaySession` for `MechaStore`, `SortieNav` and
  `CompanionScreen` host stages and declare spots, and the view model reports returns from play.
- `app/src/test/`, `mecha/src/test/` JVM unit tests. Device paths, serials and emulator package names belong in the on-card
  manifests or environment variables (`tools/dev.sh` reads `SORTIE_SERIAL`, `SORTIE_TOP_DISPLAY`, `SORTIE_BOTTOM_DISPLAY`),
  never in code.

Android behaviour that shaped the design (observed on Android 13 dual-screen hardware):

- A Presentation cannot target the default display, an app cannot move its task between displays, and a HOME task ignores
  `moveTaskToBack`. Hence the companion activity, relaunch-to-swap and finishing on launch from the second screen.
  Compose does not save focus across that relaunch or a different row count, hence `GridMemory`.
- Controller focus follows the display that last started an activity or was last touched. `am task focus` raises a task
  without moving display focus; starting an activity on a display does (`AnchorActivity`). Guard against two companions
  anchoring back at each other.
- `am stack remove` accepts leaf task ids; `cmd activity display move-stack` needs the root task id. Removing a root HOME
  task removes Sortie too. Never remove the system launcher's own tasks.
- Leaving the default display empty makes the system start HOME again; cover it with the companion first.
- Android 13 rejects singleTask/singleInstance activities for SECONDARY_HOME.
- Full-table Room reads concurrent with indexer writes can fail with "Couldn't read row N from CursorWindow": read inside
  a transaction, debounce and catch.
- Reinstalling an APK drops the default-home choice (`SORTIE_HOME=1 tools/dev.sh install` restores it).
- Idle cost is checked with `dumpsys gfxinfo` (no new frames while idle), `dumpsys meminfo` and `top`; covers go through
  `CoverImage` (constraint-sized, hardware bitmaps) with a fixed 96 MB memory cache and no disk cache.
