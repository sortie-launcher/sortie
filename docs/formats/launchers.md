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
        "LIBRETRO": "/data/user/0/com.retroarch/cores/mednafen_ngp_libretro_android.so",
        "CONFIGFILE": "/storage/emulated/0/Android/data/com.retroarch/files/retroarch.cfg",
        "IME": "", "DATADIR": "/data/user/0/com.retroarch", "SDCARD": "/storage/emulated/0",
        "EXTERNAL": "/storage/emulated/0/Android/data/com.retroarch/files"
      },
      "flags": ["CLEAR_TOP"],
      "display": "same"
    },
    {
      "id": "duckstation",
      "label": "DuckStation",
      "package": "com.github.stenzek.duckstation",
      "activity": "com.github.stenzek.duckstation.EmulationActivity",
      "action": "android.intent.action.MAIN",
      "extras": { "bootPath": "${file}" }
    },
    {
      "id": "open-with",
      "label": "Open with…",
      "generic": true
    }
  ]
}
```

`id` is required; `label` defaults to it. `package` and `activity` select an app/component (an activity beginning with `.`
is relative to the package); without an activity, Android resolves the intent within the package. `action` defaults to
`android.intent.action.MAIN`. `data` and `mimeType` are optional. `extras` holds **strings**, not typed JSON booleans or
numbers; `extrasArray` holds string arrays.

Templates in `data`, `extras` and `extrasArray` accept:

| Placeholder | Value |
|---|---|
| `${file}` | game file path |
| `${uri}` | Sortie FileProvider content URI, or a file URI if one cannot be created |
| `${stem}` / `${name}` | filename without / with extension |
| `${system}` / `${dir}` | system ID / containing directory |
| `${titleid}` | text from a small `<file>.titleid` or `<stem>.psvita` sidecar, otherwise the filename's first four-uppercase-letter/nine-character title ID (for example `PCSE00001`); empty if absent |

Unknown placeholders become empty strings. `NEW_TASK` is always added. Recognised `flags` are `NEW_TASK`, `CLEAR_TOP`,
`CLEAR_TASK`, `SINGLE_TOP`, `NO_HISTORY`, `GRANT_READ_URI_PERMISSION` and `GRANT_WRITE_URI_PERMISSION`; unknown names are
ignored. Sortie adds a read grant when a profile uses a content URI.

`display` is `top`, `bottom` or `same` (default). Top means Android's primary display; bottom means the configured
presentation display. With bottom navigation and a secondary display, a normal `same` launch goes to the primary screen.
A launch from a built-in app such as Magazines uses that window's screen instead. No secondary display means a `bottom`
request falls back to Android's placement.

Normal launches try the per-game override, the per-system user choice, then the system's manifest list, without duplicates;
unknown IDs are skipped. A one-off launch tries only the selected profile. A profile with `generic: true` opens the Android
chooser only when explicitly selected or included in that order; it is not appended implicitly. Cancelling the chooser
does not record a play session.

A normal launch that falls through other configured emulators to Open with… may remember its first selection as the
system's default. Explicit one-off choices, per-game overrides, a system whose first configured profile is generic, and
systems with an existing `systemProfiles` choice never trigger this. The callback rechecks the original configuration
before saving, so a choice made while the chooser was open wins.

Sortie adds a `chosen-<package>` profile (the package lower-cased, other characters replaced by `-`) with the app's label,
`package`, chosen `activity`, action VIEW, `data: "${uri}"`, `mimeType: "*/*"` and flags `NEW_TASK`,
`GRANT_READ_URI_PERMISSION`. An existing profile with that id is preserved. Only user.json's `systemProfiles` selects it;
the system's manifest order is unchanged. **Settings → Default launcher per system → Manifest default** undoes the choice.

The examples use the same intent shapes as the bundled RetroArch and DuckStation presets; install the matching package,
core and BIOS where needed. Other package variants need their own paths. See [launchers](../LAUNCHERS.md) for the dated
device-test evidence, not a compatibility guarantee.

Nothing in this file is validated against a built-in list of emulators. If an app is missing or Android refuses the launch,
the next fallback is tried and Diagnostics records why. Once Android accepts a launch, Sortie does not try another emulator
merely because the game fails inside it.
