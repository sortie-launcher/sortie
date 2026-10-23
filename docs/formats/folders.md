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
`hasAll`); `favourite` (bool); `hack` (bool). Combinators: `all`, `any`, `not`. `apps: true`
lets installed apps match (by label/package regex) so a folder can mix games and apps.
