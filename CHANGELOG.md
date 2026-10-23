# Changelog

## Unreleased
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
