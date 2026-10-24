# guide.json

An optional editorial layer: short recommendations, fit scores, cautions, tags and control notes. Sortie reads this file from
its active configuration folder; it does not generate or download it. No guide is required to browse or launch games.

Entries are keyed by the exact local game ID, `<systemId>/<filename>`, including the filename's extension.

```json
{
  "built": "2026-10-23 01:20",
  "games": {
    "ps2/God Hand (USA).iso": { "fit": 5, "hook": "Clover's brawler…", "flags": ["brutal difficulty"], "tags": ["brawler"], "auto": false }
  }
}
```

## Entry fields

| Field | Meaning |
|---|---|
| `fit` | Curator's 1–5 taste-fit score; `0` means no score. This is not an objective review rating or a compatibility result. |
| `hook` | Short editorial recommendation shown on the game page. |
| `controls` | Optional control notes; see below. |
| `flags` | Short cautions such as pacing, difficulty or unusual controls. |
| `tags` | Editorial labels available to folder rules. |
| `auto` | `false` for an individually authored entry; `true` (the default) for an automatically generated entry. |

Personal notes and Finished/Shelved verdicts belong to the reader's [user data](user.md), not this editorial file.

### `controls`

Optional hand-written control notes per game (`"controls": "L2/R2 strafe · hold Select = menu"`). Shown on the game page and
on the companion screen while the game is played.

## Sharing a guide today

A guide can be shared as a file, but its keys are **not portable game identities**. An entry for
`ps2/God Hand (USA).iso` will not attach to a differently named file, a different system ID or a `.chd` copy.
Adapt keys to the receiving library; do not rename somebody's game files just to match a guide.

Sortie currently reads one `guide.json`, not a stack of independently merged catalogues. Back up an existing file and merge
the intended entries before replacing it. Keep a curator's taste scores distinct from the reader's own preferences, and
review any generated scores or personal wording before sharing. A missing guide simply leaves the editorial layer empty;
the launcher does not need an account, an AI service or a connection to obtain one.

The [portable game references used by magazines](README.md#portable-game-references) are a different, already-supported
format. They are not accepted as guide keys. A shared, automatically matched blurb catalogue bundled with APK updates is
not implemented yet.
