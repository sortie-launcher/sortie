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
`same`). A profile with `generic: true` opens the Android chooser.

Nothing in this file is validated against a built-in list of emulators. If a package is not
installed, the profile is skipped and the next fallback is tried; the diagnostics screen says why.
