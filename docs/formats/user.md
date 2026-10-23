# user.json (device-owned)

Written by Sortie on the device (`<card>/sortie/user.json`, atomically via `user.json.tmp`); the desktop only reads it.
Every key is optional; unknown keys are ignored.

```json
{
  "favourites": ["snes/Super Metroid (USA).sfc", "app:org.mozilla.firefox"],
  "hidden": ["psx/Demo Disc (USA).chd"],
  "profileOverrides": { "psx/Vagrant Story (USA).chd": "duckstation" },
  "homeLayout": "grid",
  "gridColumns": 5,
  "browseLayout": "list",
  "homeSort": "custom",
  "layout": ["system:snes", "folder:unplayed-4", "apps", "ps2/Armored Core 3 (USA).chd", "app:org.ppsspp.ppsspp",
             "shortcut:com.droiddeck.launcher/game-42"],
  "shortcuts": ["shortcut:com.droiddeck.launcher/game-42"]
}
```

| Key | Type | Default | Meaning |
|---|---|---|---|
| `favourites` | ids | `[]` | favourited games (`<system>/<file>`), apps (`app:<pkg>`) and shortcuts (`shortcut:<pkg>/<id>`) |
| `hidden` | ids | `[]` | entries kept out of smart folders |
| `profileOverrides` | id → profile id | `{}` | per-game launch profile chosen in "Launch with…" |
| `homeLayout` | `"list"` \| `"grid"` | `"list"` | home view; Select toggles it on Home |
| `gridColumns` | 2..10 | `5` | columns of every grid (home and browse screens) |
| `browseLayout` | `"list"` \| `"grid"` | `"list"` | view of the browse screens (system lists, folders, search, Apps); Select toggles it there. One setting for all of them, separate from `homeLayout` |
| `homeSort` | `"alpha"` \| `"count"` \| `"generation"` \| `"custom"` | `"alpha"` | home order. The first three are generated and fixed: folders (incl. Apps) first, then systems, by label, by game count (descending) or grouped by the system's `generation` (`8-bit` … `128-bit` ascending, then the rest in `systems.json` order). `custom` shows `layout` |
| `layout` | tile ids, in order | `[]` | the custom home. Ids: `system:<systemId>`, `folder:<folderId>`, `apps` (the built-in Apps folder), a game id, `app:<pkg>`, `shortcut:<pkg>/<shortcutId>`. Only placed tiles show (nothing is auto-added); ids that no longer resolve are skipped when rendering but kept here |
| `shortcuts` | shortcut ids | `[]` | launcher shortcuts listed on the Apps screen. Shortcuts pinned to Sortie or added with "Add shortcut from…" are added automatically; others are opted in from "Browse all shortcuts…". Smart folders see only shortcuts that are opted in, favourited or placed on home |
