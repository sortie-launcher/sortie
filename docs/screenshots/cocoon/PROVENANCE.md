# Import from Cocoon screenshot provenance

Captured on 26 October 2026 through Sortie's actual import interface, using an invented Cocoon library on a disposable
Android emulator. All five PNGs are native 1280×720 screen captures, with no cropping, resizing or retouching.

## Build and device

- **App:** official signed Sortie `0.1.0-beta.2138` (versionCode 2138).
- **APK SHA-256:** `638a5bbad17b5a2460ce05caf199b6103d9052e727283f8413c80ade2aa8667f`.
- **Device:** Android Emulator 35.6.11, Android 13 / API 33, one display at 1280×720 and 240 dpi. This is not a handheld.
- No Sortie configuration existed before this import. RetroArch 1.22.2 was installed; an installed package does not prove
  that every required core is installed or that each system runs. Cocoon itself was not installed.
- This emulator was dedicated to the walkthrough and stopped afterwards. No existing emulator or physical device was reset.

## Invented library

- Three Cocoon-format gamelists: Game Boy Color, Game Boy Advance and SNES. Six game IDs, favourite/hidden flags and
  RetroArch emulator choices; a backup contains Pocket Picks with three members and four sessions totalling 40 minutes.
- uCity is freely distributed homebrew; the other five names and non-playable files are invented. Six generated artwork
  fixtures stand in for Cocoon's downloaded art. No commercial ROM or artwork was supplied for this import.
- The preview lists both `roms/gbc` and `Games/gbc`: the earlier [first-launch walkthrough](../readme/PROVENANCE.md) left
  another copy of uCity in the latter. The duplicate filename resolves to the same Sortie game ID, leaving six matched games.
- The result added three systems, three favourites, one hidden game, one folder, four sessions and six artwork files.
  These are demonstration counts, not a prediction for another library. No DroidDeck shortcut was included.

## Checks run alongside

- **Cancel** from the preview returned to backup selection. The Sortie configuration folder was absent both before and
  after; no claim is made that all app-private data was byte-identical.
- SHA-256 manifests for all 18 source files in the Cocoon folder, its backup and the fixture's ROM folders matched before
  and after the completed import.
- The resulting Home showed Continue, Pocket Picks and Favourites in Custom sort. The preview and result displayed the
  current additions/preservation wording, not the former switch for replacing existing emulator defaults.
- This capture pass exercised one completed import, not a repeat-import, removable-card or cross-device compatibility
  test. Earlier, separately scoped checks remain in [Import from Cocoon](../../COCOON_IMPORT.md#acceptance-check-on-a-device).

## Images

| File | Shows |
|---|---|
| `cocoon-02-backup.png` | Detected backup, Continue without a backup and the typed-backup field |
| `cocoon-05-preview-options.png` | Favourite, hidden, artwork, folder and session switches, with Cancel |
| `cocoon-06-preview-artwork-systems.png` | Import, artwork size/free space and the three systems to add |
| `cocoon-10-result.png` | Added counts, safety-copy path and reminder to verify the library before removing Cocoon |
| `cocoon-12-home-grid.png` | Current Home in Custom sort: Continue, Pocket Picks, Favourites and personal shelves |
