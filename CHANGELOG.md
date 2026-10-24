# Changelog

## Unreleased
- On-device library setup (Home → Library setup, Settings → Library and emulators): add built-in or custom systems, several game
  folders each through a controller/touch folder browser, file types, installed emulators in fallback order, BIOS and save
  folders; re-index and remove systems. Saves merge into existing manifests (unknown fields and unedited entries kept), are
  refused when files are unreadable or changed meanwhile, and commit atomically with recovery after interruption. Removing a
  system never touches game files, saves or personal records. Home offers setup when no games are indexed.
- Built-in emulator presets label device-tested versus source-checked launch methods and state core/BIOS prerequisites.
  Sortie never downloads games, BIOS files or emulators.
- The `sortie` folder saved by Library is remembered: missing storage no longer falls through to another volume. A missing or
  unreadable game folder keeps its games (reported, not treated as empty); moved folders refresh stored paths even when file
  size/time are unchanged; an invalid or interrupted configuration can no longer empty the index.
- Failed game, app and shortcut launches show a plain explanation (missing file, emulator not installed, no emulator, refused
  request) instead of failing silently; details remain in Diagnostics.
- All files access screen in plain language with settings fallbacks; overlay, input-method and Home-role entries say where to
  go when Android cannot open the page. Diagnostics lists real displays and offers screen moves only when Shizuku and two
  switched-on displays are available.
- Release tooling: `tools/init-release-key.sh` creates one persistent release key outside the repository;
  `tools/release.sh` builds, verifies and checksums a signed, non-debuggable APK with no debug-key fallback. Validated
  `versionCode`/`versionName` overrides. `tools/build.sh` checks style, runs debug and release unit tests and builds both
  variants without formatting. Signed upgrade and source-blind newcomer exercises passed; tested compatibility, beta
  evidence and remaining key-custody/public-distribution gates are recorded in docs/RELEASE.md.
- Shortcut pin requests from other apps (`requestPinShortcut`) now ask first: a dialog names the shortcut and the requesting
  app/package with Add or Cancel (Cancel focused). Cancel, Back or leaving adds nothing; expired or invalid requests explain why.
- Newcomer refinements: Play action, Library setup label, built-in Favourites shelf, favourite/platform list labels, Fit 4+
  filters, stable More-filter dialog, Continue list badge and no screen-swap hint on single-screen devices. Existing notes
  open with the cursor at the end. Same-day numbered magazine issues show the highest issue first.
- Missing saved settings offer reconnect/restore guidance rather than first-run setup. Preference writes cannot recreate a
  missing pinned folder or overwrite a restored/unreadable user.json with empty defaults. Recovery reloads every store;
  failed preference writes warn on ordinary screens, not only in Backup/Journal.
- New presets do not add an Android wildcard chooser implicitly, and cancelling the chooser records no play session.
  Newly installed emulators become selectable from an already-open game page without restarting Sortie.
- RetroArch presets keep its single-instance intent path instead of clearing its native activity. Background games are not
  killed on focus loss; the emulator's different-content restart limitation is explained before the first game.
- Public-audience Field Notes zines: nine revised issues, 41 articles and 131 images, with portable game references, public
  source provenance and compact rights notices rather than personal library assumptions. The issues now live in a separate
  `sortie-zines` content repository and are bundled only with `-PsortieMagazinesDir=…/sortie-zines/issues`; Gradle verifies
  them against the digest pinned in `app/magazines.lock` and stages them under `app/build/`. Default builds contain no
  issues, in-tree `assets/magazines/` folders are refused, and `tools/publish-magazines.py --out DIR [--pin]` publishes
  into the content repository instead of the app sources.
- Magazine contents list Games mentioned with All/On this device filtering; game pages link back through Featured in.
  Matching respects platforms and editions, offers multiple local copies, and excludes placeholders/missing/arriving files
  from availability. Opening a magazine link never launches a game.
- Versioned magazine publication uses reproducible catalogs, build-time asset verification and staged private extraction.
  Higher revisions win one shelf entry per issue ID; card copies win ties and are never overwritten. Existing external
  version 1 packs remain readable; reading IDs and personal clippings are preserved.
- Touching magazine list entries now places controller focus before acting, so filtering local games does not jump to the
  issue header. Article links from game pages keep the original contents return location.
- Per-game offline reference cards: write Markdown, import text or images, edit/delete, and pan/zoom maps. During gameplay,
  the companion can display and page through cards by touch without taking controller input from the game.
- Magazine highlights, personal notes and a persistent scrapbook: mark paragraphs with Y, clip recommendations with Select,
  reopen their source passages or linked game pages, and keep excerpts readable even when an issue is absent.
- Finished and Shelved are built-in Custom shelves with optional personal verdicts. Status is explicit, mutually exclusive,
  independent of favourites/Want to play, and retained for temporarily missing games.
- Home's weekly Journal combines recorded sessions with dated notes and explicitly selected screenshot attachments.
  Local-day/week boundaries, unknown session lengths, draft cancellation, offline persistence and display swaps are supported.
  New personal metadata is included in JSON backups; reference images and journal screenshots must be copied separately.
- Compact app and shortcut grid tiles are now icon-only, with the cutoff adapting to tile width and font scale.
  Larger tiles and list rows retain labels; accessibility names remain available on icon-only tiles.
- Stress-test fix: opening the Home switcher over Sortie preserves the exact navigation/companion task instead of raising a
  stale background app. Home after an X swap relays to the existing navigation instance, preserving its display, page and filters.
  Only genuine Home-key requests open the overlay; framework Home launches stay quiet. Relays focus the existing Sortie task
  before retiring the temporary Home activity, avoiding a framework Home relaunch loop.
- Search is now a text-optional library explorer: multi-select System, Tags (including game-page flags) and Genre, plus
  favourites, Want to play, unplayed, fit ★★★★+ and on-device filters. Exact labels, combined SQL filtering before paging,
  full result counts, saved browsing state and controller-accessible pickers.
- Home has a direct Manual book button beside Settings.
- Offline recommendation magazines: native issue shelf, contents, illustrated Markdown articles, library links, controller
  reading controls and persisted reading positions. Packs use local files only; malformed issues and unsafe paths are isolated.
- The magazine companion shows tappable section headings with the current section highlighted, issue contents and a folded
  next-article corner. It follows X swaps, preserves article positions, and clears on leaving the issue; single-screen reading is unchanged.
- Held B dismisses the radial keyboard before release without backing out of the app; a short B still dismisses on release.
- D-pad Down from Search's text field focuses game results in grid or list view, including after the keyboard is closed.
- Home's Alphabetical, Game count and Generation sorts show systems only. Custom automatically includes every smart folder,
  Apps and Continue, preserving existing pins and supporting reordering without duplicate tiles.
- The switcher restores pinned/PiP players to fullscreen before raising or moving them, clearing their PiP bounds without
  recreating the task. Internal focus anchors no longer trigger a user-leave hint that can send the player back into PiP.
- Optional Android radial keyboard: transparent app overlay, eight left-stick groups and four right-stick characters plus
  four diagonal editing actions. Supports trigger layers, touch, held delete/space and native editor actions. Enable/select
  through Settings; no global hotkey, network access or typed history. Verified in Chrome and Sortie, including Sortie input
  on both Thor displays. Suppresses the extraction editor even on handheld firmware that forces fullscreen IMEs.
- Want to play is separate from favourites: toggle from a companion hero, a game's page, R2 on a focused game, or its context
  sheet. The built-in folder is always on Custom Home; queue membership persists and is included in backups.
- Companion heroes and game now-playing panels show platform, year, developer and genre below the title, even when a guide
  hook is present. Platform names come from the system manifest, with the system ID as a fallback.
- X screen swaps preserve the current page/back stack, search and filters, grid/list position and controller selection.
  Programmatically showing the companion no longer opens the Home switcher; the Home key still opens it.
- Settings → Backup and restore exports preferences and closed play sessions. Restore previews changes, saves a safety copy,
  replaces preferences and merges history without duplicating sessions or deleting newer history. Android pins and grants
  remain Android-owned; ROMs, artwork and emulator saves are not included.
- Game pages have editable controller requirement badges and mapping notes, plus a personal "Where was I?" reminder.
  Saves are durable; Cancel discards drafts, and Clear all + Save removes the entry without changing desktop guide notes.
  Badges appear in grids/lists; personal notes appear on the companion while browsing and playing.
- Browsing another game updates the companion even while a game session is running in the background, without ending that session.
- Folder and system titles have their own row above the filters. Pick one, fit, notes, started and on-device controls wrap
  as whole buttons on smaller screens, so long names cannot squeeze their labels or hide the last filter.
- Home adapts to smaller displays when screens are swapped: header actions reflow, sorts scroll sideways, and controller
  hints wrap without splitting words. Home/game grids cap density by available space and text scale without changing saved
  preferences or scroll direction; custom-layout D-pad moves follow the actual grid. Icon tiles reserve room for their labels.
- System tiles show the console's logo when `<card>/sortie/logos/<systemId>.png` exists (rendered on a desktop, e.g. from the
  slate-es-de theme, black strokes turned white for dark tiles); the cover stays as a faint backdrop.
- First run with the card on an SD card: once the all-files grant arrives and `device.json` becomes readable, Sortie restarts
  itself (`RestartActivity` in a `:restart` process kills and relaunches the main one — no alarms) so every store binds to the
  card instead of primary storage.
- Apps screen tile density is its own setting (`user.appColumns`, default 8, Settings → "App tiles per row").
- Repo tidy for publishing: personal device identifiers out of the docs, `examples/card/` minimal card, README rewrite.
- Home header shows a 24-hour clock (one recomposition a minute). The companion hero follows the focused game while browsing
  grids and lists (`Modifier.heroOnFocus`), not only the game page. Manual scrolls with the D-pad (focusable list, Up/Down/Page).
- Home: a **Continue** tile (last game played) leads Custom unless moved. Hold R1 on a system or folder tile opens a random
  playable game in it.
- Lists: **💾 started** chip — games with a save or state on the card (`DeviceState.saveStems`).
- Game page: "Played N min · S sessions · last <when>" (`PlayStat.sessions`), plus hand-written control notes.
- Guide: new `controls` field per game in `guide.json` (written by the desktop guide builder); DB v4 adds `games.controls`.
- Companion: while a game is selected, the panel mirrors its play stats, control notes, description and tags under the art
  (scrollable); while playing, the control notes sit under the timer. A bottom-left dot unfurls a 5-slot **dock** up the left
  edge (`user.dock`): A/tap an empty slot to pin an app/shortcut, A/tap to start it on that screen, hold A/long-press to unpin,
  B folds it. The open dock keeps the controller on the companion (`CompanionHost.keepController`) and focuses slot 0; it folds
  when the panel changes.
- Settings → Help: a bundled **Manual** (`assets/MANUAL.md`, rendered by `ui/ManualScreen.kt`: headings, bullets, bold/italic).
- Grids can scroll sideways (`user.gridHorizontal`, Settings → "Grids scroll sideways"): rows of square tiles, `user.gridRows`
  (2–5) sets density. Home and browse share `ui/Tiles.kt` `ItemGrid`; section headers become rail columns; list view unchanged.
  The Apps screen keeps its vertical grid (its expanders/management rows are full-width).
- Settings screen (home header, Diagnostics moved under it): per-system default launcher (`user.systemProfiles`, between the
  per-game override and the manifest order), home/browse grid, grid columns, hero rotation on/off (`user.heroRotation`), overlay
  switcher on/off (`user.overlaySwitcher`). Directories and manifests stay desktop-authored. Resolution order lives in
  `launch/ProfileOrder.kt` (tested); verified on the DMG: set CPS-3 → Open with… (launch uses it), per-game override wins,
  Manifest default restores ra-fbneo.
- Launch display "same" always resolves to an explicit display: without one Android reused the emulator's existing task on
  whichever screen the switcher had moved it to.
- A play session now ends only when its app is really gone (checked over Shizuku on foreground): Home summoning the switcher
  resumes Sortie's HOME activity while the game runs underneath and used to stop the timer and Now playing. Switcher cards have
  a ✕ (touch) to close that app.
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
  K played games, computed once per evaluation from the subjects' last-played times) and `sort: random`; a desktop-generated
  `play-next` folder (unplayed · fit ≥ 4 · recentTags 1/3) uses both.
- user.json writes are serialised (one collector writes the newest state); concurrent per-update writers could leave an older
  state on disk after a burst of layout moves or favourites. Regression test `UserStoreTest`.
- Small fixes (feedback round 4): hold-A works on the controller (`Modifier.keyLongPress`: the first key repeat is the long
  press; `combinedClickable` only hears touch) — context sheets on tiles, rows, apps; the home grid is focusable only while a tile
  is lifted (a permanently focusable grid had swallowed the tile outline and D-pad up/down); hero and Now-playing titles drop
  file-name noise (`displayTitle`: regions, `[tags]`, versions, revisions) and tapping the card opens the game on the other
  screen; the switcher has a "Sortie" entry (raise Sortie's navigation where it runs, controller follows); Diagnostics has a
  Wireless debugging button (Developer options scrolled to the toggle). The offline Play-next idea is filed as a smart-folder
  rule, not an engine.
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
  companion screen, debug `--es open <route>`. Design note for recommendation snapshots.
- Vita launches through EmuCoreV using `<file>.titleid` sidecars written by the desktop; indexer ignores sidecar files.
- Shizuku-backed screen mover (capability-gated) with Diagnostics controls; debug `--es open <route>` hook; lower Gradle and
  Kotlin daemon memory limits.
- M4 (in progress): palette theming on the detail screen, per-share absolute roots, manifests for DMG/Vert/Nova, GitHub
  Actions debug build, release checklist, BIOS-missing and save-present badges; fixed a CursorWindow race that crash-looped the
  folder evaluator during indexing.
- M3 (in progress): companion panel on the other screen, X swaps screens, launches target the top screen when navigating
  from the bottom, HOME role, single-instance guard, disc grouping and duplicate-format hiding (schema v3).
- M2: smart folders from folders.json (rule engine with tests, repository, home counts, folder screen, Pick one, Select
  context, hide); a desktop validator mirrors the rules; first-seen time from file mtime.
- Project skeleton, spec, plan and on-card data formats.
- M0 probe: Compose app with a Presentation on the secondary display; verified on the AYN Thor (Android 13).
- tools/dev.sh: build, install, run, screenshot.
- M1 (in progress): Room index from ES-DE gamelists + guide.json, home/system/detail/search/diagnostics screens, launch
  profiles with fallbacks and logging, play log, adb launch hook. Launch verified for 10 emulator paths on the Thor (docs/LAUNCHERS.md).
- Review pass: safe incremental indexer with per-system meta, chunked SQL, abandoned-session handling, debounced manifest
  reloads, debug-only adb launch hook, edge-to-edge insets, all-files permission screen, scoped FileProvider, rule engine + tests.
