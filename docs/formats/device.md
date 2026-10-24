# device.json

```json
{
  "device": "my-handheld",
  "root": "/storage/ABCD-1234",
  "screens": { "count": 2, "navigation": "bottom" },
  "shizuku": true
}
```
`root` is the base folder against which relative paths in other manifests resolve. On-device setup uses the volume
containing the configuration folder. Android names a removable volume after its filesystem ID (`/storage/XXXX-XXXX`);
`ABCD-1234` above is an example, so use the path your own device shows. Internal shared storage is `/storage/emulated/0`;
a removable card is not required.
