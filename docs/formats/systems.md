# systems.json

```json
{
  "systems": [
    {
      "id": "ps2",
      "label": "PlayStation 2",
      "roots": ["big-roms/ps2"],
      "extensions": [".iso", ".chd", ".cso"],
      "gamelist": "scraped-esde/gamelists/ps2/gamelist.xml",
      "media": "scraped-esde/downloaded_media/ps2",
      "profiles": ["armsx2", "open-with"],
      "colour": "#4b6cff",
      "order": 12
    }
  ]
}
```

`roots`, `gamelist` and `media` are relative to the device root declared in `device.json`.
`profiles` is the fallback order for this system. `colour` seeds generated tiles and the theme engine.
