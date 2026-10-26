# Now playing: RetroArch and RetroAchievements on the companion (unimplemented proposal)

This is a retained design note, not setup instructions or a committed roadmap. No RetroArch remote-control buttons,
UDP client, achievement badge or log reader are implemented. The proposed `control` profile field below is not part of
the current `launchers.json` schema.

## What exists today

The companion shows the open session's art, title, hook, bookmark, guide control notes and reference cards, with
"Playing · N min" (`ui/CompanionScreen.kt`). That timer follows Sortie's recorded session; it does not query RetroArch
for active content, input or achievement progress. Browsing another game can temporarily replace the panel.

Sortie has no INTERNET permission. Its RetroArch presets send `ROM`, `LIBRETRO`, `CONFIGFILE`, `IME`, `DATADIR`,
`SDCARD` and `EXTERNAL`, with `NEW_TASK|CLEAR_TOP`, not `CLEAR_TASK` (`data/setup/LibraryPresets.kt`,
`launch/IntentBuilder.kt`). Existing custom profiles retain their own flags; see [launch compatibility](LAUNCHERS.md).

## Research retained for a possible extension

These interfaces would need checking against the selected RetroArch build and device before implementation:

- RetroArch's network command interface can be enabled with `network_cmd_enable`, using `network_cmd_port` (normally
  55355). Candidate commands include save/load state, screenshot, quit and status queries. Availability and reply formats
  are not a tested Sortie contract.
- Android sockets, including localhost sockets, require INTERNET permission. A possible offline-only design would send
  commands through the existing Shizuku shell service instead of adding networking to Sortie. Shell UDP tooling and
  command acknowledgement still need verification; Shizuku availability alone does not establish either.
- The Android launch activity receives a complete configuration path through `CONFIGFILE`; a desktop
  `--appendconfig` command-line option is not an Android intent extra.
- Config, save, state, screenshot and badge files are useful only where storage access permits reading them.
  All files access does **not** grant access to another app's private storage or its protected `Android/data` directory.
  In particular, Sortie's default RetroArch `CONFIGFILE` points into that protected directory.

## Possible scope

An initial experiment could expose **Save state · Load state · Screenshot · Quit**, only for an explicitly opted-in
profile and an authorised Shizuku connection. The old suggestion was a `"control": "retroarch-udp"` field on the profile;
adding that field today does nothing. A real implementation would need schema support, failure feedback, safe handling
of Quit and preservation of the companion's controller handback.

Before changing code, validate commands on disposable content with disposable or verified-backed-up saves and states.
Loading a state or quitting can lose unsaved play. No command experiment or device compatibility is established by this note.

Later possibilities were `GET_STATUS` to detect a return to RetroArch's menu, and an offline achievements indicator.
The RetroAchievements web API needs credentials and networking and is outside Sortie's offline scope. RetroArch may
use that service itself; Sortie does not fetch it.

An offline indicator might read explicitly accessible configuration or session log messages, but enabled achievements
are not proof of a loaded achievement set or an unlock. Shared badge caches, log formats and access are unverified, and
there is no supported local achievement database in Sortie. Never read or display RetroAchievements tokens or passwords.
