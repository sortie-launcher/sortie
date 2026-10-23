# folders.json

Smart and manual folders. A folder is a query over the index plus optional manual edits.

```json
{
  "folders": [
    {
      "id": "unplayed-4plus",
      "label": "Unplayed, ★★★★+",
      "rule": { "all": [ { "fit": { "gte": 4 } }, { "playMinutes": { "eq": 0 } } ] },
      "sort": "fit:desc,title:asc"
    },
    {
      "id": "mechs",
      "label": "Mechs",
      "rule": { "any": [
        { "name": { "regex": "(?i)armored core|gundam|front mission|zone of the enders|virtual-?on|mech" } },
        { "tags": { "has": "mech" } }
      ] },
      "include": [ "ps2/S.L.A.I. - Steel Lancer Arena International (USA).iso" ],
      "exclude": [ "gba/SD Gundam Force (USA).gba" ]
    },
    { "id": "favourites", "label": "Favourites", "rule": { "favourite": true }, "apps": true }
  ]
}
```

Predicates: `name`, `path`, `system` (`regex`, `eq`, `in`); `genre`, `developer` (`regex`);
`players` (`gte`, `lte`); `year` (`gte`, `lte`); `fit` (`gte`, `lte`, `eq`); `playMinutes`,
`daysSincePlayed`, `daysSinceAdded` (`gte`, `lte`, `eq`); `tags`/`flags` (`has`, `hasAny`,
`hasAll`); `favourite` (bool); `hack` (bool); `recentTags` (below). Combinators: `all`, `any`, `not`.

Sort keys: `fit`, `title`, `year`, `lastPlayed`, `playMinutes`, `added`, `system`, each `:asc` (default) or
`:desc`, comma-separated. `random` shuffles the members each time the folder is re-evaluated (library, play
data, favourites or manifests changed); next to other keys it is the final tiebreaker (`fit:desc,random`).

## Recently played tags (`recentTags`)

`{ "recentTags": { "minShared": 1, "window": 3 } }` matches a game whose guide `tags` share at least
`minShared` entries (default 1) with the union of the tags of the `window` (default 3) most recently played
games (latest start in the play log). Only `tags` count, not `genre` text. The recent set is computed once per
evaluation from the same candidates the folder sees, so hidden games do not count, and with nothing played yet
nothing matches. Game-only: apps and shortcuts never match. With `playMinutes` and `fit` it makes the offline
"what next" folder `gen_manifests.py` ships ("Pick one" on it is the recommendation):

```json
{ "id": "play-next", "label": "Play next",
  "rule": { "all": [ { "playMinutes": { "eq": 0 } }, { "fit": { "gte": 4 } }, { "recentTags": { "minShared": 1, "window": 3 } } ] },
  "sort": "random" }
```

## Apps and launcher shortcuts (`apps: true`)

Besides games, a folder can hold installed apps (id `app:<package>`) and launcher shortcuts other apps
handed to Sortie (id `shortcut:<package>/<shortcutId>`, e.g. one DroidDeck game pinned from DroidDeck's
"add to home screen"; shortcuts only exist while Sortie is the default Home app). The rule sees apps and
shortcuts only when the folder sets `apps: true`; `include`/`exclude` take ids of any kind and always win.

Fields for every kind: `name`/`title` (label), `kind` (`game`, `app`, `shortcut`), `package` (the app's
package, or the package that published the shortcut; absent on games), `source` (shortcuts only: the
publishing package), `favourite`, `playMinutes`, `daysSincePlayed`. All other fields are game-only: on an
app or shortcut they are a non-match, not an error, so `{ "hack": false }` excludes apps while
`{ "not": { "hack": true } }` includes them. Sorting by a game-only key (`fit`, `year`, `added`, `system`)
puts apps and shortcuts first ascending, last descending.

```json
{ "id": "droiddeck", "label": "DroidDeck", "rule": { "source": { "eq": "com.droiddeck.launcher" } }, "apps": true }
```

`validate_folders.py` knows these fields but previews games only (there are no apps on the desktop).
