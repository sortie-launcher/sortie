# Changelog

## Unreleased
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
