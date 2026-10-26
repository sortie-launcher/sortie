# Screenshot provenance

`home.png`, `tonight.png`, `game-detail.png` and `bookmark.png` are physical-device captures from beta 2136.
The other images are earlier Android-emulator captures retained for setup and dual-screen examples. Those older images
predate the simplified Home and bookmarks; follow the guides' text for current steps. No interface elements are drawn or retouched.

## Home, Tonight and bookmarks: Retroid Pocket Nova, 26 October 2026

- **App:** existing official signed Sortie `0.1.0-beta.2136` (versionCode 2136), the Quiet Home release.
  No APK was installed for this capture session.
- **Installed APK SHA-256:** `569792a4aa491f19b09faa9139c413df3732b88860438668e463ecc43c0e8960`.
- **Device:** Retroid Pocket Nova, Android 13, landscape 1280×960 at 320 dpi. Captured through Android's
  `screencap` over USB ADB; PNGs are unedited at native resolution.
- **Shows:** Home in Generation sort with the Split-S header and Rivet; Tonight's God Hand recommendation through
  Field Notes; God Hand's game page; its empty bookmark editor with the Android keyboard dismissed.
  This is the device's existing library, guide and artwork, not a synthetic library. The fit stars come from its
  custom guide, not the bundled catalogue. No game was launched and no bookmark was saved.
- **Coordination:** exclusive lock on the canonical `DEVICE_ACCESS_NOVA.md` held throughout hardware access.
  Nova was asleep with Sortie on top; Thor and DMG were not accessed.
- **Preservation:** `user.json`, `device.json`, `systems.json`, `launchers.json`, `folders.json`, `guide.json` and
  `playlog.jsonl` were byte-identical before and after. Android system, secure and global settings were unchanged.
  Returned to Home in the original Generation sort, then to sleep; lock released.
- These are UI captures, not emulator-compatibility tests.

## Earlier captures: build and device

- **App:** Sortie `0.1.0-beta.6` (versionCode 7), an existing debug APK with the optional Field Notes content.
  It came from the clean `main` checkout at `dbde916` and its build timestamp follows that commit. This source association
  is inferred from the checkout and timestamp, not an embedded revision; the capture session did not rebuild it.
  APK SHA-256: `b60adc2c7a1a85407fc1dc97adb8858db59dfaa5c6d78744bcd9338c94d3a60b`.
  This particular capture build bundled magazines; ordinary local builds do not. Current hosted builds do.
- **Device:** an Android Emulator (Android 13, API 33, Google APIs x86_64 image). The top screen is 1920×1080 at 360 dpi,
  and a second emulator display at 1240×1080 stands in for a dual-screen handheld's bottom panel. This is not an AYN Thor or
  any other physical handheld, and the captures say nothing about performance or compatibility on real hardware.
- Captured on 24 October 2026 with Android's `screencap` and the emulator's display screenshot. The gesture handle at the
  bottom is part of Android's UI.

## Library shown

- **Setup and first launch** (`first-start`, `home-empty`, `add-system`, `folder-picker`, `setup`, `setup-saved`,
  `first-game-page`, `first-game-running`): taken from a fresh install, with no settings pre-written. The path was
  Home → **Set up library** → **Add a system…** → **Game Boy Color** → **Add a folder…** → Internal shared storage ›
  Games › gbc → **Use this folder** → **Save** → *Try a launch* → **ucity** → **Play**. The game is
  [uCity](https://hh.gbdev.io/game/ucity/) by AntonioND (GPL-3.0-or-later), from Homebrew Hub. RetroArch 1.22.2
  opened it with the Gambatte core. The core file was copied in before the launch instead of being downloaded through
  RetroArch's Online Updater. Sortie shows the title as `ucity` because it comes from the file name; no gamelist was used.
- **Demo library** (`library`, `companion`, `magazine`): the other 37 games are illustrative
  stand-in files carrying real names. They are not games, cannot be played, and none was launched. Titles, descriptions
  and art come from ES-DE-style gamelists and scraped media, and the systems were added through Library setup. The pins,
  favourites and Want to play entry are sample actions. Before the Home capture, uCity was given a short gamelist entry
  that we wrote, plus tile art made from a frame of uCity itself, taken during the real launch above.

## Images

| File | Size | Shows |
|---|---|---|
| `home.png` | 1280×960 | Beta 2136 Nova: Generation-sorted Home, current header and Rivet |
| `tonight.png` | 1280×960 | Beta 2136 Nova: God Hand as the From Field Notes pick, with its reason and actions |
| `bookmark.png` | 1280×960 | Beta 2136 Nova: empty bookmark editor, keyboard dismissed, nothing saved |
| `library.png` | 1280×720 | A system grid (PlayStation 2) with filters and the controller focus on one game |
| `companion.png` | 1280×1432 | The same moment on both screens: the grid on top, the companion's art and description for the focused game below |
| `game-detail.png` | 1280×960 | Beta 2136 Nova: God Hand, curated hook, Leave a note, reference cards and verdict controls |
| `magazine.png` | 1280×720 | A Field Notes article opened from that link (optional content build) |
| `setup.png` | 1280×720 | Library setup's system editor: game folder, file types, emulator order and installed status |
| `first-start.png` | 1280×720 | First start: the All files access explanation |
| `home-empty.png` | 1280×720 | Home before any system exists, showing **Set up library** |
| `add-system.png` | 1280×720 | **Add a system…**: built-in systems, each with what is installed |
| `folder-picker.png` | 1280×720 | The folder browser, ready for **Use this folder** |
| `setup-saved.png` | 1280×720 | The result after **Save**: index count and *Try a launch* |
| `first-game-page.png` | 1280×720 | uCity's game page before the first launch |
| `first-game-running.png` | 1280×720 | uCity's title screen in RetroArch (Gambatte), started from Sortie |

## Rights

Box art, screenshots, game descriptions and logos belong to their respective publishers and rights holders. They appear
only to show how Sortie presents a library and are not covered by this repository's MIT licence. The magazine page's
picture credit is shown on screen. uCity is GPL-3.0-or-later software by AntonioND; its graphics and music are
[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/). The game and its credits are linked above.
RetroArch's interface belongs to the RetroArch project.
