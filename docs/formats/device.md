# device.json

```json
{
  "device": "my-handheld",
  "root": "/storage/ABCD-1234",
  "screens": { "count": 2, "navigation": "bottom" },
  "shizuku": true
}
```
`root` is the card path the relative paths in other manifests resolve against. Android names a removable volume after its
filesystem ID (`/storage/XXXX-XXXX`); `ABCD-1234` above is a placeholder, so use the path your own device shows.
Internal storage is `/storage/emulated/0`.
