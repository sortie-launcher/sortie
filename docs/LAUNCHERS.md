# Emulator launch compatibility

Reference for `launchers.json` authoring. Placeholders: `${file}` absolute path, `${uri}` Sortie FileProvider content URI,
`${stem}`, `${titleid}`. "Verified" means the game booted and was seen on screen through `adb exec-out screencap`.

## Historical Thor checks

The table below records AYN Thor / Android 13 checks from 2026-10-23, not a compatibility guarantee for every emulator
version. On-device Library setup preserves existing custom profiles; its current presets may use a newer launch method.

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
| Vita | emucorev | `com.sbro.emucorev/.core.vita.Emulator`, `com.sbro.emucorev.action.LAUNCH` | `--es titleId ${titleid}` and `--esa AppStartParameters -r,${titleid}` | yes (Shiren the Wanderer, 2026-10-23). The id comes from a `<file>.titleid` sidecar next to the bundle; the desktop writes them from the bundle's `ux0/app/<TITLEID>` folder. The title must already be installed in EmuCoreV. |

Notes
- `file://` URIs require the app to relax StrictMode's file-URI check (done once in `SortieApp`).
- Every attempt and the exact `am start` equivalent are in Diagnostics and in logcat under the `SortieLaunch` tag.
- Debug builds accept `am start -n io.github.esko997.sortie/.MainActivity --es launch '<systemId>/<file>' [--es profile <id>]`.
- **Remembered chooser pick:** only a normal system launch falling through other configured emulators to Open with… may
  remember its first app selection. Per-game overrides, explicit one-off launches, systems starting with Open with…,
  and systems with a default already chosen in Settings are left alone. The callback rechecks the configuration before
  saving. Sortie adds a `chosen-<package>` profile (explicit component, VIEW, `${uri}`, MIME `*/*`, read grant) and selects
  it in **Default launcher per system**, without reordering the manifest. Choose **Manifest default** there to undo it.
  An app that only accepts a file path or its own intent still needs a dedicated profile.

## Fresh beta setup checks — 2026-10-24

On a disposable Android 13/API 33 x86_64 emulator, systems and folders were configured through Sortie's Library UI:

- **PPSSPP 1.20.4:** the current preset sends `${uri}` with a read grant and MIME `application/octet-stream`. PPSSPP's
  public cube demo, packaged as a homebrew ISO, visibly booted. A raw `file://` launch failed scoped-storage access on this
  fresh installation. Loose ELF homebrew failed inside PPSSPP with a single-file content URI; open such homebrew inside
  PPSSPP instead. Gold shares the upstream intent contract but was not installed or exercised.
- **RetroArch 1.22.2 / Gambatte:** the GB Assembly Tutorial's Hello World ROM visibly booted with the current preset.
  Returning to Sortie and choosing the same game resumed it in the same RetroArch process. The old `CLEAR_TASK` flag
  stalled in native activity destruction; new presets use `CLEAR_TOP` without `CLEAR_TASK`.
  A different content path caused RetroArch to exit on the first attempt and boot on the second, matching its upstream
  `onNewIntent` implementation. This limitation is stated in setup and the manual. Quit RetroArch after core configuration
  and save before changing games. Sortie does not force-stop the emulator or add `QUITFOCUS`: switching apps must not kill
  an unsaved game. The first test emulator downloaded Gambatte through RetroArch's UI; the second reused that core binary
  as a controlled fixture.
- **Explicit Android chooser:** Back/cancel left the play log unchanged. Selecting PPSSPP visibly booted the ISO and
  recorded one session. New system presets no longer silently append a wildcard chooser when no emulator is installed.

Existing desktop profiles are deliberately not rewritten by setup. For a custom RetroArch profile, remove `CLEAR_TASK`
from its flags to use the current handoff; keep `CLEAR_TOP`. The historical table above records the older configuration.

Fixtures came from [PPSSPP's public demo](https://www.ppsspp.org/legacybuilds) and the
[GB Assembly Tutorial](https://gbdev.io/gb-asm-tutorial/part1/hello_world.html). No commercial ROM, BIOS or emulator is
bundled with Sortie. Other presets retain their stated source-checked or device-tested status; an installed package does
not prove that its core, BIOS, storage permission or game is ready.

## RetroArch content switches and the quick-exit notice — 2026-10-25

On the AYANEO Pocket DMG (Android 13) with **RetroArch 1.22.2**, Sortie's preset (`NEW_TASK|CLEAR_TOP`, no `CLEAR_TASK`):

- Gambatte running *Metal Gear Solid* (GBC), Home, then Play on *F-Zero* (Snes9x): RetroArch's window died about 0.2 s
  after the start and Sortie came back. A different game on the **same** core (*Moonrise Regalia*, Gambatte) did the same.
  The next Play started the requested game normally. Same game, same core resumes it, as above.
- Sortie now tells the player instead of silently showing the game page again. When a game's emulator is gone within
  3 s of starting and no Home press brought Sortie back, the game's page shows a notice next to Play (a toast when that
  page is not on screen): *RetroArch closed while switching cores — press Play again*, *… while switching games …*, or
  the app's name with *closed right after starting* (e.g. *DuckStation closed right after starting*) for any other
  emulator, or when Sortie did not see what the app held before (it remembers only the libretro core and file it last sent
  to each RetroArch build in the current process). Sortie never retries by itself. Such a non-start leaves no session in
  the play history (no time, count or "last played"); the `playlog.jsonl` copy records its `end` with `quickExit: true`.
- Telling it apart from the player leaving: with Shizuku, an emulator that still has a task means Home only summoned
  Sortie over it. Without Shizuku (the DMG), the HOME intent of a Home key press carries `FROM_HOME_KEY`, while the one
  Android sends after an app's last window closes does not; a Home press after the launch makes a quick return a normal
  session end. On the DMG, Home 2.2 s after starting Gambatte ended the session normally with no notice.
- Also seen on the DMG: with Snes9x and Genesis Plus GX (Vulkan driver) RetroArch stopped responding about 5 s after Home
  (ANR waiting for `PointerCaptureChangedEvent`), and a later Play of a different game then did not close it. Gambatte did
  not hang. Force-stop RetroArch in that state.

## Beginner-guide walkthrough — 2026-10-24

The [getting-started path](GETTING_STARTED.md) was exercised in a fresh, isolated Android 13/API 33 x86_64 emulator:
**Set up library → Add a system… → Game Boy Color → Add a folder… → Save → Try a launch → Play**.
[uCity](https://hh.gbdev.io/game/ucity/) reached its title screen in **RetroArch 1.22.2 / Gambatte**. Without a gamelist,
Sortie displayed the file-derived title `ucity`, as shown in the guide.

The Gambatte core was preinstalled from a cached binary, so this pass did not exercise RetroArch's Online Updater.
This proves the illustrated setup-to-launch path, not game performance, physical-controller behaviour or compatibility
on a particular handheld. The [capture record](screenshots/readme/PROVENANCE.md) identifies the APK and fixtures and
distinguishes this actual homebrew launch from the non-playable demo entries used for the README tour.
