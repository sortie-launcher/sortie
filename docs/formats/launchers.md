# launchers.json

A list of launch profiles. A profile describes one way to start a file with one app. Systems and
games reference profiles by `id`; a system lists profiles in fallback order.

```json
{
  "profiles": [
    {
      "id": "retroarch-ngp",
      "label": "RetroArch · Beetle NeoPop",
      "package": "com.retroarch",
      "activity": "com.retroarch.browser.retroactivity.RetroActivityFuture",
      "action": "android.intent.action.MAIN",
      "extras": {
        "ROM": "${file}",
        "LIBRETRO": "/data/data/com.retroarch/cores/mednafen_ngp_libretro_android.so",
        "CONFIGFILE": "/storage/emulated/0/Android/data/com.retroarch/files/retroarch.cfg",
        "IMEPackage": "", "DATADIR": "/data/data/com.retroarch", "SDCARD": "/storage/emulated/0",
        "EXTERNAL": "/storage/emulated/0/Android/data/com.retroarch/files"
      },
      "display": "top"
    },
    {
      "id": "duckstation",
      "label": "DuckStation",
      "package": "com.github.stenzek.duckstation",
      "activity": "com.github.stenzek.duckstation.EmulationActivity",
      "action": "android.intent.action.VIEW",
      "data": "file://${file}",
      "flags": ["NEW_TASK", "GRANT_READ_URI_PERMISSION"]
    },
    {
      "id": "open-with",
      "label": "Open with…",
      "generic": true
    }
  ]
}
```

Placeholders: `${file}` absolute path, `${uri}` content URI via Sortie's FileProvider, `${stem}`
filename without extension, `${system}`, `${dir}`. `display` is `top`, `bottom` or `same` (default
`same`). A profile with `generic: true` opens the Android chooser only when explicitly selected by a game/system override
or included in that system's profile list. It is not appended implicitly. Cancelling the chooser does not record a play session.

The first app picked in the Open with… chooser becomes the system's default: Sortie adds a profile
`chosen-<package>` (the package lower-cased, other characters replaced by `-`) with that app's name as `label`, its
`package` and chosen `activity`, action VIEW, `data: "${uri}"` and flags `NEW_TASK`, `GRANT_READ_URI_PERMISSION`, puts it
first in the system's `profiles` and selects it in user.json's `systemProfiles`. This happens only for a `generic`
profile and only while the system has no `systemProfiles` entry, so a choice made in Library setup or on the game page is
never replaced; an existing profile with the same id is reused unchanged. Change it in Library setup or on the game page.

Nothing in this file is validated against a built-in list of emulators. If a package is not
installed, the profile is skipped and the next fallback is tried; the diagnostics screen says why.
