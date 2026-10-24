# Screenshot provenance

These are real Android screen captures of Sortie and, in `first-game-running.png`, the game opened in RetroArch.
Presentation changes are downscaling to 1280 px wide, palette compression and, for `companion.png`, stacking two displays
from one moment. No interface elements are drawn or retouched.

## Build and device

- **App:** Sortie `0.1.0-beta.6` (versionCode 7), an existing debug APK with the optional Field Notes content.
  It came from the clean `main` checkout at `dbde916` and its build timestamp follows that commit. This source association
  is inferred from the checkout and timestamp, not an embedded revision; the capture session did not rebuild it.
  APK SHA-256: `b60adc2c7a1a85407fc1dc97adb8858db59dfaa5c6d78744bcd9338c94d3a60b`.
  Normal builds and CI artifacts contain no magazines.
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
- **Demo library** (`home`, `library`, `game-detail`, `companion`, `magazine`): the other 37 games are illustrative
  stand-in files carrying real names. They are not games, cannot be played, and none was launched. Titles, descriptions
  and art come from ES-DE-style gamelists and scraped media, and the systems were added through Library setup. The pins,
  favourites and Want to play entry are sample actions. Before the Home capture, uCity was given a short gamelist entry
  that we wrote, plus tile art made from a frame of uCity itself, taken during the real launch above.

## Images

| File | Size | Shows |
|---|---|---|
| `home.png` | 1280×720 | Home, Custom sort, with Settings → Grid columns (downward) set to 7: Continue (uCity, focused), pinned games, and the Favourites, Want to play, Finished, Shelved and Apps tiles |
| `library.png` | 1280×720 | A system grid (PlayStation 2) with filters and the controller focus on one game |
| `companion.png` | 1280×1432 | The same moment on both screens: the grid on top, the companion's art and description for the focused game below |
| `game-detail.png` | 1280×720 | A game page: cover, Play / Launch with…, notes, reference cards, Want to play, and a *Featured in* magazine link |
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
