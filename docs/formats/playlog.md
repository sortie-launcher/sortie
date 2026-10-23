# playlog.jsonl

Appended by Sortie, one object per line. Carried to the desktop by Syncthing and consumed by the
guide builder and the taste model.

```json
{"ts":"2026-10-23T22:14:05-04:00","event":"start","id":"ps2/God Hand (USA).iso","profile":"armsx2","display":"top","device":"thor"}
{"ts":"2026-10-23T23:02:41-04:00","event":"end","id":"ps2/God Hand (USA).iso","minutes":48,"device":"thor"}
```

`id` is `<system>/<filename>` for games or `app:<package>` for apps. `end` is written when Sortie
regains the foreground; sessions without an `end` are closed on next start with `minutes: null`.
