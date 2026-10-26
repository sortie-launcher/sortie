# device.json

```json
{
  "device": "my-handheld",
  "root": "/storage/ABCD-1234",
  "screens": { "count": 2, "navigation": "bottom", "presentationIndex": 0 },
  "shizuku": true,
  "saves": ["RetroArch/saves"],
  "biosDir": "RetroArch/system"
}
```
`root` is required in the JSON, but the loader uses the **parent of the active configuration folder** as the effective
root, overriding a stale value. Relative paths in the other manifests resolve there, not beside `device.json`.
Android names a removable volume after its filesystem ID (`/storage/XXXX-XXXX`); `ABCD-1234` is only an example.
Internal shared storage is `/storage/emulated/0`; a removable card is not required.

| Field | Default | Meaning |
|---|---|---|
| `device` | `"unknown"` | label written to play-log events and backups; not hardware detection |
| `screens.count` | `1` | descriptive count; actual secondary-display availability comes from Android |
| `screens.navigation` | `"bottom"` | with a secondary display, a normal navigation launch using `display: "same"` sends the game to the primary screen |
| `screens.presentationIndex` | `0` | zero-based choice from Android's presentation-display list, used as the secondary screen |
| `shizuku` | `false` | descriptive setting; does not start Shizuku or grant permission |
| `saves` | `[]` | save folders to inspect for the saved-game badge; absolute or relative to the effective root |
| `biosDir` | absent | BIOS folder checked against each system's `bios` list; absolute or relative to the effective root |

Save detection matches game filename stems, not emulator save formats. These paths only supply badges; they do not configure
an emulator, copy saves or verify BIOS contents. Shizuku features depend on the actual running service and permission.
