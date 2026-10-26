# Device captures

Historical shell output recorded on real hardware, replayed by `Replay*Test.kt` and `DeviceStatsProbeTest.kt` in
`app/src/test/java/io/github/esko997/sortie/shell/`. ScreenMover and TaskSwitcher use a fake shell that records their
commands; stats tests parse captured probe readings. These are parser/decision fixtures, not proof that the current APK
was tested on each device. UI screenshot goldens are separate, under `app/src/testDebug/screenshots/`.

Devices, all Android 13 (SDK 33):

- `thor/`: AYN Thor. Display 0 is the top screen (1920×1080), display 4 the bottom one (1240×1080).
- `dmg/`: AYANEO Pocket DMG, display 0. Its native panel is 1080×1240; the task capture is rotated to 1240×1080 landscape.
- `nova/`: Retroid Pocket Nova, a single 1280×960 screen (device stats only).

## Captures

All `stack-list.txt` files are `adb shell cmd activity stack list`, verbatim apart from the renames below.

| Capture | What the device showed |
| --- | --- |
| `thor/games-both-screens` | RetroArch on top, a PSX emulator on the bottom; Sortie's navigation hidden on the bottom. |
| `thor/sortie-top` | Sortie as HOME on top; its secondary home on the bottom over Launcher3's, in one root task. |
| `thor/sortie-bottom` | Sortie's navigation swapped to the bottom screen; an emulator on top. |
| `thor/switcher-overlay` | A stream on top under Sortie's switcher overlay (a window, not a task); the secondary home on the bottom. |
| `thor/recents` | Launcher3's recents on top; the secondary home on the bottom over a hidden app. |
| `thor/panel-stuck` | The AYN panel lock-up (docs/HARDENING.md): Sortie's navigation on the bottom with ANRs, Thor button dead. |
| `thor/release-1033` | `windows.txt` only: a healthy window dump with no panel window (the panel had not been opened). |
| `dmg/home-app-under-game` | RetroArch running; Sortie and another HOME app's leftover task share the home root task under it. |
| `thor/device-stats` | `probe-1.txt`, `probe-2.txt`: the device-stats probe (`DeviceStatsProbe.SCRIPT`, before it named the cores) run twice, two seconds apart, idle with the fan stopped. `probe-detail.txt`: the Perf view's probe (`DETAIL_SCRIPT`, every thermal zone), idle, fan at 300. |
| `dmg/device-stats` | `probe.txt`: the same probe on the DMG, which has no fan node. `probe-detail.txt`: the detail probe. |
| `nova/device-stats` | `probe-detail.txt`: the detail probe on the Retroid Pocket Nova; it reports the same fan node used on Thor, stopped in this sample. |

`thor/panel-stuck` also has what the panel probe (`ScreenMover.PANEL_PROBE`) reads:

- `pidof.txt`: the panel's pid, from `dumpsys activity processes` of the same moment.
- `connectivity.txt`: only the `Network Requests:` section of `dumpsys connectivity`, each request cut after its type and
  id, plus the `mNetworkRequestInfoLogs` header the probe checks for. Networks, addresses and requesting packages are gone.
- `displays.txt`: `dumpsys window displays`, only its header and each display's `Display:`, `mCurrentFocus=` and
  `mFocusedApp=` lines.
- `windows.txt`: `dumpsys window windows`, only its header, each `Window #` line with its `mDisplayId=` and `mHasSurface=`
  lines, and the two-space summary lines at the end (`mTopFocusedDisplayId=` …), in their original order.

The `device-stats` captures are the probe's output verbatim (`adb shell` with the script as the command); they hold only
kernel counters, clocks, thermal-zone names and readings. `DeviceStatsProbeTest` parses them and also runs the real script
in a local `sh` against a fake `/proc` and `/sys`.

`thor/home-activities.txt` and `dmg/home-activities.txt` are not captures: they list the HOME and SECONDARY_HOME activities
the package manager would report, reconstructed from the components the captures show and Sortie's manifest.

## Sanitizing

Third-party apps are renamed to neutral `example.*` packages (for instance `example.psx/example.psx.MainActivity`, keeping a
`$` where the original activity name had one). Kept as recorded because the behaviour depends on them: Sortie, Android's own
packages (`com.android.*`, Launcher3), the vendor's `com.odin.*` and `com.retroarch`. No serials, addresses, account data or
file paths are in any capture.

## Adding a capture

Use an authorised, idle device and follow the workspace's exclusive device-access locking procedure, including for
read-only ADB. Preserve the existing fixtures as historical evidence; a changed firmware or state warrants a new fixture.

1. Record the state on the selected device, e.g. `adb -s SERIAL shell cmd activity stack list > stack-list.txt`.
   For a panel state, also capture `dumpsys connectivity`, `dumpsys window displays`, `dumpsys window windows` and
   `pidof com.odin.dualscreen.assistant`. Record the date, Android/firmware version, display orientation and relevant app
   version alongside the new fixture's description.
2. Put it in `<device>/<what-it-shows>/` and trim it to what the code reads, as above.
3. Rename third-party packages to `example.*`, reusing the names already here for the same app.
4. Review every retained line for private data, then run this supplementary check from the repository root; it must print nothing:

   ```sh
   grep -rniE --include='*.txt' '([0-9]{1,3}\.){3}[0-9]{1,3}|/home/|/storage/|@[a-z0-9-]+\.[a-z]|serial' app/src/test/resources/device-captures
   ```

5. Add a row to the table above and a test that states what Sortie must do in that state.
