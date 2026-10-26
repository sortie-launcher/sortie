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
    }
  ]
}
```

`id` is required; `label` defaults to it. Without `rule`, a folder contains only its `include` list. An empty rule object
matches every eligible candidate. `exclude` wins over both the rule and `include`; neither can bring back hidden or
unavailable entries. `limit` defaults to `0` (unlimited); a positive value caps the sorted result.

Predicates:

- Text: `name`/`title`, `path`, `file`, `system`, `genre`, `developer`, `publisher`, `desc`, `kind`, `package`, `source`.
  All support `regex`, `eq`, `in` and `contains`. Equality, membership and substring tests ignore case; regexes need
  `(?i)` for that.
- Numbers: `players`, `year`, `fit`, scraped `rating`, `playMinutes`, `daysSincePlayed`, `daysSinceAdded`.
  All support `gte`, `lte`, `gt`, `lt` and `eq`. Never-played entries have no `daysSincePlayed` value and do not match it.
- Lists: `tags`/`flags` support `has`, `hasAny`, `hasAll`; use lowercase guide labels.
- Booleans: `favourite`, `hack`, `handWritten`. `handWritten` means the effective guide entry has `auto: false`,
  not that its prose was written by a human, and does not test for a personal bookmark.
- `recentTags`: see below.

Combinators are `all`, `any` and `not`; multiple keys in one object are implicitly ANDed. Unknown fields/operators and
invalid regexes are reported as rule problems and their predicates do not match.

Sort keys: `fit`, `title`, `year`, `lastPlayed`, `playMinutes`, `added`, `system`, each `:asc` (default) or
`:desc`, comma-separated. `random` shuffles the members each time the folder is re-evaluated (library, play
data, favourites or manifests changed); next to other keys it is the final tiebreaker (`fit:desc,random`).

The ids `favourites`, `want-to-play`, `finished` and `shelved` are reserved for Sortie's built-in personal shelves. They use
`favourites`, `wantToPlay` and `gameProgress` in [user.json](user.md), not rules from this file. A `folders.json` entry with a
reserved id is ignored and reported on the built-in folder. These folders always appear on Custom Home, are title-sorted and
omit hidden entries. Favourites holds favourited games, installed apps and shortcuts; the other three hold games only. Ids
temporarily absent from the game index (or apps not installed) are retained in the user's data and return when available.

Cocoon import can remove the obsolete default definition `{ "id": "favourites", "label": "Favourites",
"rule": { "favourite": true }, "apps": true, "sort": "title:asc" }` after showing the cleanup in its preview and only
with Favourites enabled. This removes a redundant rule, not anyone's favourites. Custom rules, memberships, limits,
labels and unknown fields prevent automatic removal; those conflicts remain reported for deliberate correction.
A Cocoon user folder merely named "Favourites" keeps its separate `cocoon-<id>` identity.

## Recently played tags (`recentTags`)

`{ "recentTags": { "minShared": 1, "window": 3 } }` matches a game whose guide `tags` share at least
`minShared` entries (default 1) with the union of the tags of the `window` (default 3) most recently played
games (latest session start in Sortie's database). Only `tags` count, not `genre` text. The recent set is computed once per
evaluation from the same candidates the folder sees, so hidden games do not count, and with nothing played yet
nothing matches. Game-only: apps and shortcuts never match. With `playMinutes` and `fit` it makes an offline
"what next" folder ("Pick one" on it is the recommendation):

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

Desktop tools that preview folder rules against a library should treat these fields the same way; a desktop library usually
has no apps or shortcuts, so such a preview covers games only.
