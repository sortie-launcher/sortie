# playlog.jsonl

An append-only, best-effort event mirror in the active configuration folder, one JSON object per line. Sortie's private
Room database is authoritative for history and statistics; this file is not read back to restore them. Export backup
includes the database sessions. Optional desktop tools or sync software can consume this sidecar; Sortie does not sync it.

```json
{"ts":"2026-10-23T22:14:05-04:00","event":"start","id":"ps2/God Hand (USA).iso","profile":"armsx2","display":"top","device":"thor"}
{"ts":"2026-10-23T23:02:41-04:00","event":"end","id":"ps2/God Hand (USA).iso","minutes":48,"device":"thor"}
```

`id` is `<system>/<filename>` for games, `app:<package>` for apps or `shortcut:<package>/<shortcutId>` for shortcuts.
`profile` and `display` are present on starts; an external app/shortcut start uses an empty profile and `"same"`.
`display` is the requested profile setting, not an observed Android display ID. `ts` is an ISO timestamp with local offset;
`device` is the configured device label.

On returning to Sortie, a session stays open if the emulator is still visible in Shizuku's task list. Otherwise it ends;
without that task information, returning can end a session even while the emulator still runs. Completed `minutes` are
whole minutes of active time, rounded down: the session counts only while the screen is on and unlocked, Sortie's
navigation is not the activity in front (Android's top resumed activity; its dialogs and the notification shade count as
in front) and the switcher overlay is not up. Time with the screen off or locked, in Sortie's own screens or in the
switcher is left out; the companion screen and built-in apps (Magazines, Performance) count as play. Active time is not
proof of input. If Sortie's process dies mid-session, the interval running at that moment is lost, and the session stays
paused until the screen, the navigation or the switcher overlay next changes. Starting a new session closes any
still-open one normally, with its active minutes. Ends written before active time was recorded hold elapsed wall-clock
minutes, and an `end` with `minutes: null` and no `quickExit` is an older abandoned session. An unavailable configuration
folder or failed append can leave gaps in this mirror without losing the database history.

A game that returns within 3 seconds, with no Home press after launch and no known running emulator task, is classified
as a quick exit: its `end` has `minutes: null` and `"quickExit": true`, and Sortie removes that session from its database.
Treat it as no play; this is a launch-return heuristic, not a test of the game.

```json
{"ts":"2026-10-25T14:33:23.093-04:00","event":"end","id":"snes/F-Zero (USA).sfc","minutes":null,"quickExit":true,"device":"dmg"}
```
