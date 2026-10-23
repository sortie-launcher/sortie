# Launch profiles that work on the AYN Thor (Android 13), verified 2026-10-23

Reference for `launchers.json` authoring. Placeholders: `${file}` absolute path, `${uri}` Sortie FileProvider content URI,
`${stem}`, `${titleid}`. "Verified" means the game booted and was seen on screen through `adb exec-out screencap`.

| System | Profile | Component / action | Payload | Verified |
|---|---|---|---|---|
| PS2 | armsx2-uri | `come.nanodata.armsx2/com.armsx2.MainActivity`, VIEW | data = `${uri}` + read grant | yes (God Hand). `file://` data only opens the library. ARMSX2 ignores `bootPath`. |
| PSX | duckstation | `com.github.stenzek.duckstation/.EmulationActivity`, MAIN | `--es bootPath ${file}` | yes |
| PSP | ppsspp | `org.ppsspp.ppsspp/.PpssppActivity`, VIEW | data = `file://${file}`, mime application/octet-stream | yes |
| GC/Wii | dolphin | `org.dolphinemu.dolphinemu/.ui.main.MainActivity`, MAIN | `--es AutoStartFile ${file}` | yes |
| DS | melonds | `me.magnum.melonds/.ui.emulator.EmulatorActivity`, `me.magnum.melonds.LAUNCH_ROM` | `--es uri ${uri}` + read grant | yes. `PATH` only works for ROMs already in melonDS's library. |
| 3DS | lime3ds-file | `io.github.lime3ds.android/org.citra.citra_emu.activities.EmulationActivity`, VIEW | data = `file://${file}`, mime application/octet-stream | yes (.3ds and .cci). Content URI failed for an eShop .3ds ("Invalid application format"); a bare `SelectedGame` path fails ("no loader"). |
| N64 | mupen-fz | `org.mupen64plusae.v3.fzurita.pro/paulscode.android.mupen64plusae.SplashActivity`, VIEW | data = `file://${file}` | yes |
| RetroArch systems | ra-<core> | `com.retroarch/com.retroarch.browser.retroactivity.RetroActivityFuture`, MAIN | `ROM`, `LIBRETRO` (/data/user/0/com.retroarch/cores/<core>_libretro_android.so), `CONFIGFILE`, `IME`, `DATADIR`, `SDCARD`, `EXTERNAL`; flags CLEAR_TASK, CLEAR_TOP | yes (fbneo, snes9x, mednafen_ngp, mupen64plus_next, ppsspp) |
| Vita | emucorev | `com.sbro.emucorev/.core.vita.Emulator`, `com.sbro.emucorev.action.LAUNCH` | `--es titleId ${titleid}` and `--esa AppStartParameters -r,${titleid}` | no: the title must be installed inside EmuCoreV and the id supplied. Sortie reads `<file>.titleid` or `<stem>.psvita` sidecars; none exist yet. |

Notes
- `file://` URIs require the app to relax StrictMode's file-URI check (done once in `SortieApp`).
- Every attempt and the exact `am start` equivalent are in Diagnostics and in logcat under the `SortieLaunch` tag.
- Debug builds accept `am start -n io.github.esko997.sortie/.MainActivity --es launch '<systemId>/<file>' [--es profile <id>]`.
