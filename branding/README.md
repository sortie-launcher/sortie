# Sortie branding

The amber Split-S is Sortie's mark.

| File | Use |
| --- | --- |
| `mark.svg` | transparent mark |
| `logo.svg` | outlined wordmark |
| `icon.svg` / `icon.png` | square icon; PNG is 512 px |
| `social-preview.svg` / `social-preview.png` | 1280×640 link image |

The SVGs are self-contained, with lettering outlined from Lato Heavy Italic and Lato Regular, so no fonts need to be
installed. Colours: amber `#F2B134`, ink `#101418`, light text `#E6E8EB`.

The Android adaptive-icon foreground uses the same geometry inside the 66dp safe circle, and its alpha shape supplies
Android 13+ themed icons. Home uses a separately cropped vector of the same mark. Keep `mark.svg`,
`app/src/main/res/drawable/ic_launcher_foreground.xml` and `app/src/main/res/drawable/sortie_mark.xml` in sync when changing
the geometry; regenerate the other SVG compositions and PNG exports too.

Regenerate the PNG exports from the repository root with ImageMagick 7:

```
magick -background none branding/icon.svg branding/icon.png
magick -background none branding/social-preview.svg branding/social-preview.png
```

GitHub's link image can only be set by uploading `social-preview.png` under **Settings → Social preview**; there is no
REST, GraphQL or `gh` route. Repositories have no independent avatar: they share the organization's.
[Sortie Zines](https://github.com/sortie-launcher/sortie-zines) uses the Split-S inside a folded page.
