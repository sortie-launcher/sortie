# Changelog

## Unreleased
- A held L1/R1 never pages on release, on any tile (system and folder tiles included), so hold-R1 is safe everywhere.
- Round 4c: status bar hidden over Sortie (swipe down still shows it); hold R1 on any game/app/shortcut tile or row toggles its
  favourite (`keyLongPress` handles R1; L1/R1 short presses now fire on key-up so a hold does not also page); on Home L1/R1 page
  the sort modes and a new "By Console" sort shows a console strip (L1/R1 page consoles, B returns to the previous sort) with
  the chosen console's games below; the companion idle panel has a battery gauge (sticky broadcast, no polling) and a bell that
  opens the system notification shade over Shizuku — Sortie does not read notifications (that needs a permanent
  NotificationListenerService). Companion '+' picker: the pending hand-off from the tap that opened it is cancelled.
- Tile/row text is white again over the ambient gradient (the transparent Surface had lost its content colour). The companion
  '+' picker keeps the controller while open (`CompanionHost.keepController`): a tap on its search field no longer hands the
  display away and closes the keyboard; closing the picker hands it back.
- Overlay picker search: the touch-only overlay window becomes focusable while its app picker is open (`SwitcherOverlay.
  allowTextInput`), so the IME stays attached; restored and the controller re-anchored when the picker closes. Known wart: Gboard
  uses its fullscreen extract UI on that window while typing (not on Sortie's own screens); Done/Back returns to the filtered grid.
- Title cleaning moved to `data/index/Titles.kt` `cleanTitle` with tests: revision markers need a digit or a lone letter after a
  space, so Revenge/Revelations/Revolution keep their words.
- Round 4b: `AppPicker` (search field + icon grid) opens any installed app or opted-in shortcut on a chosen display — the
  companion '+' launcher, and "Open app…" in both switcher sides (the switcher closes and the controller follows). Titles drop
  file-name noise everywhere (rows, tiles, detail header; the file name stays on its own line). `AmbientBackground`: the
  navigation screen's gradient is tinted by the companion's hero/selected game and blends over 2.5 s at 20 Hz when it changes,
  static otherwise; hero rotation slowed to 20 s. `UserStore` keeps a pending save in a conflated channel (an edit before the
  writer coroutine ran was dropped); deterministic test with a gated dispatcher.
- "Play next" smart folder (offline recommendation): new rule field `recentTags {minShared, window}` (tags shared with the last
  K played games, computed once per evaluation from the subjects' last-played times) and `sort: random`; `gen_manifests.py`
  ships `play-next` (unplayed · fit ≥ 4 · recentTags 1/3), `validate_folders.py` mirrors both from the pulled play log.
- user.json writes are serialised (one collector writes the newest state); concurrent per-update writers could leave an older
  state on disk after a burst of layout moves or favourites. Regression test `UserStoreTest`.
- Small fixes (feedback round 4): hold-A works on the controller (`Modifier.keyLongPress`: the first key repeat is the long
  press; `combinedClickable` only hears touch) — context sheets on tiles, rows, apps; the home grid is focusable only while a tile
  is lifted (a permanently focusable grid had swallowed the tile outline and D-pad up/down); hero and Now-playing titles drop
  file-name noise (`displayTitle`: regions, `[tags]`, versions, revisions) and tapping the card opens the game on the other
  screen; the switcher has a "Sortie" entry (raise Sortie's navigation where it runs, controller follows); Diagnostics has a
  Wireless debugging button (Developer options scrolled to the toggle). docs/RECOMMENDATIONS.md files the offline Play-next idea
  as a smart-folder rule, not an engine.
- Home and browse layout (feedback round 3): home Sort chips — Alphabetical, Game count, Generation (generated) and Custom
  (`user.layout`: systems, folders, Apps, games, apps, shortcuts, exactly as placed); hold A → Move (lifted tile, D-pad one slot,
  A drop, B cancel) · Remove on Custom tiles; "Add to / Remove from home" in a hold-A context sheet on every openable item
  (apps keep Favourite there). System lists, folders, search and Apps share a Select list↔grid toggle (`user.browseLayout`)
  with 1:1 cropped-cover tiles and palette focus glow. Shortcuts are opt-in (`user.shortcuts`): pinned / "Add shortcut from…"
  ones are opted in automatically, "Browse all shortcuts…" toggles the rest, folders only see opted-in, favourited or placed
  ones. Hotkey claims are reference-counted so a screen entering during a transition keeps Select/Y. docs/formats/user.md.
- Image memory (feedback round 3): every cover goes through `ui/Images.kt` `CoverImage` (decode sized to the layout, INEXACT,
  hardware bitmap, no crossfade); `SortieApp` is Coil's `SingletonImageLoader.Factory` with a fixed 96 MB memory cache and no
  disk cache (covers are local files). Palette extraction keeps its own 1/8-scale software decode and recycles it.
- Companion screen (feedback round 3): a corner '+' on the idle panel opens a touch grid of apps and opted-in shortcuts that
  start on that display (`LaunchManager.launchExternal(displayId = …)`: logged, no play session, Sortie stays put; the
  controller is handed back to the other screen). While a play session is open (`PlayLog.current`) the panel shows the game
  (or app) with "Playing · N min", ticking once a minute; the hero rotation runs only when nothing is being played.
  Scope note for RetroArch/RetroAchievements companion controls: docs/NOW_PLAYING.md (not built).
- Switcher overlay (feedback round 3): with `appops set <pkg> SYSTEM_ALERT_WINDOW allow` the Home-key switcher is a pair of
  `TYPE_APPLICATION_OVERLAY` windows (`shell/SwitcherOverlay`, `ui/SwitcherHost`) over the running games instead of Sortie's
  screens: controller carousel on the display that had the controller, touch carousel on the other, dark scrim, games keep
  running underneath (Home on the top screen also resumes Sortie's HOME activity; the game is raised again over it). B closes;
  A (to front) and Quick swap act and close. Y moves the focused app to the other screen and keeps the switcher open: the
  screen it left is marked freed and its carousel offers every running window; A on one brings it there and closes (the
  controller follows that choice). Y/Select go to the overlay only, never to Sortie's screens below. Without the grant the
  activity route is used as before. `tools/dev.sh install` grants both appops.
- Idle cost (feedback round 3): the focus glow pulses for 3 s after focus lands and then holds (an endless frame-clock pulse
  kept Sortie redrawing at 120 Hz, ~70% of a core while idle; the blurred glow is rasterised once per size). Measured: home
  idle 0% CPU and 0 frames; companion visible while a game runs: ~3 frames per hero change, none while "Now playing".
  Context sheets and the lifted-tile move take key focus reliably (keyboard input mode requested, retried until the dialog
  window has focus; the grid holds focus while a tile is lifted). Switcher seeds focus once so its buttons are reachable.
- Feedback round 2 (2026-10-23):
  - App switcher (needs Shizuku): Home while something runs opens it on both screens — controller carousels per screen on the
    navigation screen (A to front / bring to a free screen, Y other screen, Select close app, Quick swap, Controller → top/bottom),
    touch carousel on the companion screen. `shell/TaskSwitcher` + `ScreenMover` (`am task focus`, `am stack remove`).
  - Controller anchoring: companion screens hand the controller back to the other screen when they appear or are tapped
    (`AnchorActivity`), so tapping the bottom panel no longer steals the gamepad from the game.
  - Second-screen home: `SecondaryHomeActivity` (SECONDARY_HOME, singleTop) is the bottom screen's fallback, so an exiting
    emulator lands on Sortie's companion panel instead of Launcher3 or leftover Cocoon tasks; the Presentation path is gone.
    Stale tasks from other launchers are swept over Shizuku (connect, foreground, after launch); never Launcher3's own.
  - X swap no longer bounces back: the companion covers the default display before the navigation task leaves it.
  - Index is fixed: no walk on activity start; only first run, a manifest deploy (hot reload) or Diagnostics re-index.
  - Focus glow (`Modifier.focusGlow`): 3dp border, pulsing outer glow, slight scale on every row/tile; detail buttons wrap and
    the favourite toggle is a star icon button.
- Launcher shortcuts and mixed folders: Sortie confirms `requestPinShortcut` (ShortcutPinActivity, e.g. DroidDeck "add to
  home") and pulls shortcuts via "Add shortcut from…" on the Apps screen; favourites and `apps: true` folders now hold apps
  and shortcuts next to games (rule fields `kind`, `package`, `source`); apps and shortcuts launch with the game display rule.
- Feedback round 1: Apps folder (launch installed apps, favourite them), controller hints on every screen, home grid layout
  with 1:1 tiles washed with a representative cover (Select toggles list/grid, columns in user.json), hero cards on the idle
  companion screen, debug `--es open <route>`. Design note for recommendation snapshots (docs/RECOMMENDATIONS.md).
- Vita launches through EmuCoreV using `<file>.titleid` sidecars written by the desktop; indexer ignores sidecar files.
- Shizuku-backed screen mover (capability-gated) with Diagnostics controls; debug `--es open <route>` hook; lower Gradle and
  Kotlin daemon memory limits.
- M4 (in progress): palette theming on the detail screen, per-share absolute roots, manifests for DMG/Vert/Nova, GitHub
  Actions debug build, release checklist, BIOS-missing and save-present badges; fixed a CursorWindow race that crash-looped the
  folder evaluator during indexing.
- M3 (in progress): companion panel on the other screen, X swaps screens, launches target the top screen when navigating
  from the bottom, HOME role, single-instance guard, disc grouping and duplicate-format hiding (schema v3).
- M2: smart folders from folders.json (rule engine with tests, repository, home counts, folder screen, Pick one, Select
  context, hide); desktop validator sortie-card/validate_folders.py; first-seen time from file mtime.
- Project skeleton, spec, plan and on-card data formats.
- M0 probe: Compose app with a Presentation on the secondary display; verified on the AYN Thor (Android 13).
- tools/dev.sh: build, install, run, screenshot.
- M1 (in progress): Room index from ES-DE gamelists + guide.json, home/system/detail/search/diagnostics screens, launch
  profiles with fallbacks and logging, play log, adb launch hook. Launch verified for 10 emulator paths on the Thor (docs/LAUNCHERS.md).
- Review pass: safe incremental indexer with per-system meta, chunked SQL, abandoned-session handling, debounced manifest
  reloads, debug-only adb launch hook, edge-to-edge insets, all-files permission screen, scoped FileProvider, rule engine + tests.
