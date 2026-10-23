# Now playing: RetroArch and RetroAchievements on the companion (scope note, not built)

The companion screen already shows the open play session (art, title, "Playing · N min"). This note scopes what it could
add for RetroArch games without breaking Sortie's rules: offline by construction (no INTERNET permission), no root, device
knowledge in the on-card manifests rather than the APK.

## What RetroArch exposes without root

- **Launch intent** (what Sortie uses today, docs/LAUNCHERS.md): `RetroActivityFuture` with the `ROM`, `LIBRETRO`,
  `CONFIGFILE`, `IME`, `DATADIR`, `SDCARD`, `EXTERNAL` extras and CLEAR_TASK|CLEAR_TOP. `--appendconfig` is the desktop
  command-line flag; the Android activity takes a whole config through `CONFIGFILE`, so per-launch settings mean pointing
  `CONFIGFILE` at a config the desktop writes to the card (gen_manifests.py), not an extra on top of the user's config.
- **Network command interface**: with `network_cmd_enable = "true"` (port `network_cmd_port`, default 55355) RetroArch
  accepts plain-text UDP datagrams on localhost: `SAVE_STATE`, `LOAD_STATE`, `STATE_SLOT_PLUS`/`STATE_SLOT_MINUS`,
  `PAUSE_TOGGLE`, `SCREENSHOT`, `RESET`, `MENU_TOGGLE`, `QUIT`, and queries that reply on the same socket (`GET_STATUS` →
  `GET_STATUS PLAYING <system>,<game>,crc32=…` or `CONTENTLESS`, `VERSION`, `GET_CONFIG_PARAM <key>`).
- **The catch**: opening any socket, even to 127.0.0.1, needs `android.permission.INTERNET`, which Sortie refuses. The shell
  uid has it: send through the existing Shizuku user service (`ShellService.exec`), e.g.
  `echo -n SAVE_STATE | nc -u -w1 127.0.0.1 55355` (toybox `nc`). Without Shizuku the controls simply do not appear.
- **Files**: `retroarch.cfg` (savestate/screenshot dirs, `network_cmd_*`, `cheevos_*` keys) and the save/state/screenshot
  directories are readable with the all-files grant Sortie already holds.

## What RetroAchievements offers, and what is in scope

- The RetroAchievements web API needs a per-user API key and the network. **Live RA data (achievement lists, unlocks,
  rich presence, leaderboards) requires network, so Sortie will never fetch it.** RetroArch itself talks to RA when
  `cheevos_enable` is on; that is RetroArch's business, not Sortie's.
- In scope, offline only:
  - `retroarch.cfg`: `cheevos_enable`, `cheevos_hardcore_mode_enable` → a "Achievements on · hardcore" badge on the
    now-playing panel (never read or show `cheevos_token`/password keys).
  - RetroArch's cheevos log lines (logcat tag `RetroArch`, or `logs/` when `log_to_file` is on): game identified, achievement
    count loaded, "awarded" lines. Reading logcat needs Shizuku (`logcat -d -s RetroArch`) or an adb-granted `READ_LOGS`;
    a once-a-minute read on the existing tick, never a stream.
  - Locally cached badge images, if RetroArch keeps them on shared storage (path to verify on the Thor before relying on it).
    There is no documented local copy of a game's achievement list or unlock state; offline "progress" is limited to what
    this session's log says.

## Recommended minimal first step

Four touch buttons on the now-playing panel: **Save state · Load state · Screenshot · Quit**.

- Shown only when the open session's profile is a RetroArch one. Mark it in the manifest (`"control": "retroarch-udp"` on
  the launch profile in launchers.json) rather than matching `com.retroarch` in code (docs/RELEASE.md rule 3); `SessionEntity`
  already stores `profileId`.
- Shown only when Shizuku is ready; each tap sends one datagram through `ShellService.exec`. Quit needs `quit_press_twice`
  off (or a second send) — check on the device.
- Prerequisite on the card: `network_cmd_enable = "true"` in the config each RetroArch profile's `CONFIGFILE` names.
- A tap hands the controller back to the game (existing companion hand-off), i.e. one anchor blip per tap; acceptable for
  these actions.
- Verify first over adb, before any code: launch a RetroArch game from Sortie, then
  `adb shell 'echo -n SCREENSHOT | nc -u -w1 127.0.0.1 55355'` and the same for SAVE_STATE / LOAD_STATE / QUIT.

Later, if wanted: `GET_STATUS` to confirm content is still running (end the session when RetroArch drops to the menu), and
the cheevos badge from the config and log.
