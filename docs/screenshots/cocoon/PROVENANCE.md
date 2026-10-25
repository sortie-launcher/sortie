# Import from Cocoon screenshot provenance

These are real, unretouched Android screen captures of Sortie's **Import from Cocoon…** run against an invented Cocoon
library. The only change is downscaling from 1920×1080 to 1280×720 (ImageMagick `-resize 1280x720 -strip`).

## Build and device

- **App:** Sortie `0.1.0-beta.6` (versionCode 7), debug APK built from `e2018fb` (the merge of the Cocoon import), SHA-256
  `a79bd960d8ac341d6342bb64eaddd7286798f4f0fb195e8b90064a54db1aaaf6`. Fresh install, All files access granted, no library
  before the import.
- **Device:** Android Emulator 35.6.11, Android 13 (API 33, Google APIs x86_64), 1920×1080 at 360 dpi, single display. This
  is not an AYN Thor or other physical handheld. RetroArch 1.22.2 (`com.retroarch`) was installed so one system shows an
  installed emulator; Cocoon itself was not installed.
- Captured 24 October 2026.

## Invented library

Nothing shown is a commercial game or commercial artwork.

- A Cocoon folder in Cocoon 3.07's format: gamelists for Game Boy Color, Game Boy Advance and SNES with favourite and hidden
  flags, play counts and emulator choices, and a backup with one user folder ("Pocket Picks"), four play sessions and a
  per-game emulator choice.
- Game files are placeholders of about 1 KB with homebrew-style or invented names (uCity is a free homebrew game; the other
  titles are made up). Nothing was launched.
- Artwork is placeholder images drawn for this test: a gradient with the title and the words "synthetic cover".

## Checks run alongside

- SHA-256 of every file in the Cocoon folder, backup and game folders was identical before the import, after it, after a
  second import and at the end.
- **Cancel** from the preview left every file on the card and in Sortie's app data unchanged.
- A second import added nothing; only a new safety copy was written.
- Choosing a folder that contained Sortie's own files as Cocoon's folder was refused with nothing changed.

## Images

| File | Shows |
|---|---|
| `cocoon-02-backup.png` | The first import screen with Cocoon's folder detected: the backup step, its detected backup focused, **Continue without a backup** and the typed-backup field |
| `cocoon-05-preview-options.png` | Preview switches: hide, emulators, artwork, folder and play sessions, with **Cancel** |
| `cocoon-06-preview-artwork-systems.png` | **Import**, the artwork block against free space, and the systems to add with their emulator installed or not installed yet |
| `cocoon-10-result.png` | **Import complete**: what was added, the safety-copy path, DroidDeck games to re-pin, and the reminder before uninstalling Cocoon |
| `cocoon-12-home-grid.png` | Home in the Custom sort afterwards: **Continue** from an imported session, the imported folder **Pocket Picks**, and Favourites |
