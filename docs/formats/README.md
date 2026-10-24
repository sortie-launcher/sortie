# On-card formats

All files live under `<card>/sortie/` and are authored on the desktop (library-tools) unless noted.
JSON, UTF-8, trailing commas not allowed. Unknown keys are ignored so files can evolve.

| File | Owner | Purpose |
|---|---|---|
| `device.json` | desktop | which roots, screens and emulators this device has |
| `systems.json` | desktop | system folders: id, label, roots, extensions, default profile |
| `launchers.json` | desktop | launch profiles: how to start a file with an app |
| `folders.json` | desktop | smart and manual folders |
| `guide.json` | desktop (build_guide.py) | taste layer: fit, hook, flags, tags per game |
| `user.json` | device | favourites, overrides, hidden entries, home sort and custom layout, list/grid choices, opted-in shortcuts ([user.md](user.md)) |
| `playlog.jsonl` | device | one JSON object per play session, append-only |
| `logos/<systemId>.png` | desktop (gen_logos.py) | optional system logos for the home tiles; transparent PNG, ~512 px wide |

See `../../examples/card/sortie/` for a complete minimal card (two systems, four profiles, four folders).
