# Screenshot provenance

Captured on 26 October 2026. The feature tour uses real Nova and Thor libraries; the setup walkthrough uses a disposable
Android emulator and a freely distributed homebrew game. All screen captures are native-resolution and unretouched.
The dual-screen composite only places the two original captures on a plain background.

## Feature tour: Retroid Pocket Nova

- **App:** existing official signed Sortie `0.1.0-beta.2136` (versionCode 2136). No APK was installed.
- **Installed APK SHA-256:** `569792a4aa491f19b09faa9139c413df3732b88860438668e463ecc43c0e8960`.
- **Device:** Android 13, landscape 1280×960 at 320 dpi; Android `screencap` over USB ADB.
- **Shows:** Generation-sorted Home, a Game Boy Color grid with both existing artwork and plain tiles, Tonight's God Hand
  recommendation through Field Notes, God Hand's game page and empty bookmark editor, Field Notes 011's *Counting the
  Directory*, and the Hangar Workshop. The companion is named Mica on this device.
- This is the device's existing library, guide and artwork. Fit stars come from its custom guide, not the bundled catalogue.
  No game was launched, bookmark saved, part purchased or companion renamed.
- **Coordination:** the canonical `DEVICE_ACCESS_NOVA.md` was exclusively locked throughout each hardware session.
- **Preservation:** `user.json`, `device.json`, `systems.json`, `launchers.json`, `folders.json`, `guide.json` and
  `playlog.jsonl` were byte-identical before and after each session. Android system, secure and global settings were unchanged.
  Home's original Generation sort was restored and the device returned to sleep. Normal magazine navigation can update
  app-private reading memory; whole-app-data equivalence is not claimed. Both leases were released.

These are UI captures, not emulator-compatibility tests.

## Dual-screen example: AYN Thor

- **App:** existing signed beta 2136, with the same APK hash as the Nova. No APK was installed.
- **Device:** physical AYN Thor running Android 13; primary display 1920×1080, companion display 1240×1080.
- **Shows:** the PS2 grid focused on Armored Core 3 and its companion panel. The capture commands ran concurrently at
  16:21:13 local time with a stable selection and no intervening input; this is not a claim of frame-locked capture.
- `companion-top.png` and `companion-bottom.png` are the native originals. The website uses them directly.
  `companion.png` places the top at `(0, 0)` and the bottom at `(340, 1104)` on a 1920×2184 background, RGB `(8, 10, 11)`.
  The gap is 24 pixels; neither screen is cropped, scaled or retouched.
- **Coordination:** exclusive lock on the canonical `DEVICE_ACCESS_THOR.md` throughout hardware access.
- **Preservation:** the same seven configuration/play-log files and all three Android settings namespaces were unchanged.
  The original Drakengard 2 selection, grid position and controller focus on display 0 were restored. The device remained
  awake as found. No game was launched or log cleared; the lease was released.

## First setup and launch: disposable Android emulator

- **App:** official signed `0.1.0-beta.2138` (versionCode 2138), released from `f1fe05dd69878538dd20e67711a78572e498e271`.
- **APK SHA-256:** `638a5bbad17b5a2460ce05caf199b6103d9052e727283f8413c80ade2aa8667f`.
- **Device:** Android Emulator 35.6.11, Android 13 / API 33, single display at 1280×720 and 240 dpi. This is not a handheld.
- Fresh-install path exercised through the actual interface: All files access → **Set up library** → **Add a system…** →
  **Game Boy Color** → **Add a folder…** → Internal shared storage › Games › gbc → **Use this folder** → **Save** →
  *Try a launch* → **ucity** → **Play**.
- [uCity](https://hh.gbdev.io/game/ucity/) by AntonioND was launched through Sortie into RetroArch 1.22.2 with Gambatte;
  its animated title screen was observed. The Gambatte core was copied into this disposable emulator before the launch,
  rather than downloaded through RetroArch's Online Updater. No gamelist or cover art was supplied: `ucity` is the filename.
- **uCity ROM SHA-256:** `0d608db175dd8ba22780be5df5fc17bf135876f6964f58ee141e4f8d81c62947`.
- This dedicated emulator was stopped afterwards. No existing emulator or handheld was reset or used for this walkthrough.
  The separate [Cocoon walkthrough](../cocoon/PROVENANCE.md) records its invented import fixture and checks.

## Images

| File | Size | Shows |
|---|---|---|
| `home.png` | 1280×960 | Nova: Generation-sorted Home, current header and Rivet |
| `library.png` | 1280×960 | Nova: Game Boy Color grid and browse filters |
| `tonight.png` | 1280×960 | Nova: God Hand as the From Field Notes pick, with reason and actions |
| `game-detail.png` | 1280×960 | Nova: God Hand, guide hook, bookmark, reference-card and verdict controls |
| `bookmark.png` | 1280×960 | Nova: empty bookmark editor, keyboard dismissed, nothing saved |
| `magazine.png` | 1280×960 | Nova: Counting the Directory, Field Notes 011 |
| `hangar.png` | 1280×960 | Nova: Workshop with Mica, parts and salvage |
| `companion-top.png` | 1920×1080 | Thor: PS2 grid focused on Armored Core 3 |
| `companion-bottom.png` | 1240×1080 | Thor: companion for the same selection |
| `companion.png` | 1920×2184 | Unscaled composite of those two native captures |
| `first-start.png` | 1280×720 | Emulator: All files access explanation |
| `home-empty.png` | 1280×720 | Emulator: empty Home with Set up library |
| `add-system.png` | 1280×720 | Emulator: system presets and installed-emulator status |
| `folder-picker.png` | 1280×720 | Emulator: game folder ready for Use this folder |
| `setup.png` | 1280×720 | Emulator: folder, file types and emulator order |
| `setup-saved.png` | 1280×720 | Emulator: saved system, index count and Try a launch |
| `first-game-page.png` | 1280×720 | Emulator: uCity before the first launch |
| `first-game-running.png` | 1280×720 | Emulator: uCity's title screen in RetroArch / Gambatte |

## Rights

Game art, descriptions and logos belong to their respective rights holders; they illustrate the interface and are not
covered by this repository's MIT licence. Field Notes credits and sources are published with each issue.
uCity's code is GPL-3.0-or-later and its graphics and music are [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/),
by AntonioND. RetroArch's interface belongs to the RetroArch project.
