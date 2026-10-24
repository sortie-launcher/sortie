# Sortie

<img src="branding/logo.svg" alt="Sortie — amber Split-S logo" width="480">

An offline, controller-first game launcher for Android handhelds. Point it at the game folders already on your device and
the emulators you have installed, or feed it a library curated on a desktop (ES-DE gamelists and artwork, hand-written notes
and taste scores). It never scrapes, has no network permission, and never downloads or bundles games, BIOS files or emulators.

Primary target: the AYN Thor (Android 13, dual screen — the bottom screen becomes a companion panel, dock and app switcher).
Also runs on single-screen handhelds (tested on the AYANEO Pocket DMG). Status: beta 0.1, planned for direct signed-APK
distribution; no APK has been published yet. Goal, evidence and known limitations are in
[docs/RELEASE.md](docs/RELEASE.md#beta-01). Compatibility beyond the devices and emulator versions actually tested is not
claimed.

![home](docs/screenshots/m0-top.png)

## What it does

- **Library setup** (on Home, or Settings → Library and emulators): add systems from a built-in list or name your own, choose one
  or more game folders per system, pick installed emulators in fallback order, set BIOS/save folders, re-index and remove
  systems — no JSON editing or computer needed. Removing a system never deletes game files, saves or personal records.
- Five Home sorts: alphabetical, game count and generation show systems only; Custom holds every smart folder, Apps, your
  pins and a **Continue** tile; By Console shows a console's games. Grids scroll sideways or down, with configurable density.
- Lists with filters (Fit 4+, hand-written notes, started/has a save, on device), a random pick; Search combines systems,
  tags/flags, genres, favourites, Want to play and unplayed, with optional text and a full match count.
- Game pages with cover, hook, description, play stats, control notes; **Launch with…** picks a launcher per game; a default
  launcher per system in Settings; fallback follows the configured order. Android's "Open with…" chooser is explicit, not
  silently added when an emulator is missing; cancelling it does not create a play session.
- Smart folders from JSON rules (tags, fit, play time, days since played, recent-tags similarity …), plus a built-in
  **Favourites** shelf for games, apps and shortcuts on Home → Custom.
- Personal Finished and Shelved folders with verdicts, separate from favourites and the Want to play queue.
- Per-game offline reference cards: Markdown notes and zoomable maps/images, available on the companion during gameplay.
- A weekly gaming journal built from real play sessions, with dated notes and explicitly picked screenshot attachments.
- Installed apps and launcher shortcuts as first-class items (favourite them, add them to home, pin them to the dock).
- Dual screen: the companion shows the focused game's art and notes while you browse, what's playing while you play, a
  5-slot dock and a touch launcher; Home key opens a switcher that moves running apps between screens (needs Shizuku).
- Zero idle cost: nothing animates, polls or ticks while you're not pressing anything (one recomposition a minute for the clock).
- Play log on the card (`playlog.jsonl`), favourites and preferences in `user.json` — plain files you own.
- Offline illustrated magazines: shelf, contents, articles, sources and remembered reading positions. Issues come from
  packs on the card or, in builds made with the separate zine content (see *Building*), bundled in the APK; the
  default build bundles none. Games mentioned are independent of ownership; filter those present locally or follow
  Featured in links from game pages. Higher issue revisions replace older shelf entries without overwriting card packs.
  Highlight passages, add notes and clip recommendations into a persistent scrapbook. Dual-screen companions offer
  section jumps and article controls.

A full user manual is bundled: the book button beside Settings on Home, or Settings → Help → Manual (`app/src/main/assets/MANUAL.md`).

## How it fits together

Sortie keeps its settings and your personal data in a `sortie/` folder: on the memory card when a desktop-prepared card is
found there, otherwise on internal storage. Game folders can be anywhere Android lets Sortie read, including another volume.

| File | Who writes it | What |
|---|---|---|
| `device.json` | Library screen or desktop | root path, screen layout, save/BIOS dirs |
| `systems.json` | Library screen or desktop | systems: folders, extensions, gamelist/media paths, launch profiles, colour |
| `launchers.json` | Library screen or desktop | launch profiles: package, activity, action, extras, display |
| `folders.json` | desktop | smart folders (rules) and manual folders |
| `guide.json` | desktop | per-game fit, hook, flags, tags, control notes |
| `logos/` | desktop | optional system logos for the home tiles |
| `magazines/<issue-id>/` | user/desktop | optional offline issues; merged with APK-bundled issues by ID and revision |
| `user.json`, `playlog.jsonl` | device | preferences, personal shelves/notes/clippings/reference metadata, play sessions |
| `reference/`, `journal/media/` | device | copied reference images and journal screenshots (separate from JSON backups) |

The Library screen edits only the systems, profiles and BIOS/save paths it is asked to change, keeps every other entry and
unknown field, refuses to overwrite files it cannot parse or that changed since it read them, and writes the set atomically.
A desktop-generated configuration therefore keeps working and can be edited from either side. Once you save in Library,
Sortie remembers that `sortie/` folder and keeps using it: if its storage is missing, Sortie does not switch to another
volume or start a fresh library, and the existing game index is kept until the storage is reconnected and re-indexed.
A missing or unreadable game folder likewise keeps its games and is reported, rather than treated as empty.

Formats are documented in [docs/formats/](docs/formats/); [examples/card/](examples/card/) is a complete minimal card.
A desktop pipeline (manifests from your ROM tree, ES-DE scraping, logos) is optional and yours to script; nothing in this
repository depends on one.
[docs/LAUNCHERS.md](docs/LAUNCHERS.md) lists emulator intents verified on one device; the Library presets label which
profiles were device-tested and which were only checked against the emulator's source.

## Requirements

- Android 11+ (API 30). **All files access** is required to read your game folders and keep Sortie's settings; Sortie
  explains it on first start and opens Android's switch for it (it restarts once if it then finds a desktop-prepared card).
- The emulators themselves, installed separately and set up inside each emulator: its own first-run setup, RetroArch cores
  (Online Updater), and BIOS/firmware where the system needs it. Sortie only starts what is already installed.
- Optional: [Shizuku](https://shizuku.rikka.app/) for the app switcher, screen moves and the notification bell; "Display
  over other apps" for the overlay switcher; the default Home role for the Home-key switcher and second-screen companion;
  the radial keyboard. Browsing, setup and launching work without them.

## Building

JDK 17 and the Android SDK (platform 35). Copy `local.properties.example` to `local.properties`, point `sdk.dir` at your SDK:

```
tools/build.sh        # ktlintCheck, debug + release unit tests, debug APK and an unsigned release APK (no formatting)
tools/dev.sh install  # adb install -r of the debug APK + grants; SORTIE_HOME=1 also restores the Home role
tools/release.sh      # signed release APK + SHA-256 in dist/, using the key from tools/init-release-key.sh
```

`./gradlew ktlintCheck testDebugUnitTest assembleDebug` works as well. The helpers use the `java` on your `PATH`; only when
there is none do they fall back to a distrobox container named `ubuntu` holding the JDK and SDK. `tools/build.sh` and
`tools/release.sh` pass extra arguments to Gradle. `tools/dev.sh` takes the adb serial and display ids from
`SORTIE_SERIAL`, `SORTIE_TOP_DISPLAY` and `SORTIE_BOTTOM_DISPLAY`. Debug builds accept `am start … --es open <route>` and
`--es launch '<system>/<file>'` for scripting. Release signing, versions and the debug/release signature mismatch are
covered in [docs/RELEASE.md](docs/RELEASE.md); never uninstall a daily install to get a differently signed build onto it.

**Magazines are opt-in.** A normal build contains no magazine issues and never downloads any. The Field Notes issues
live in [sortie-launcher/sortie-zines](https://github.com/sortie-launcher/sortie-zines); clone it alongside the app and pass its `issues/` folder:

```
git clone https://github.com/sortie-launcher/sortie-zines.git ../sortie-zines
tools/build.sh -PsortieMagazinesDir=../sortie-zines/issues
```

The build verifies the content against the catalog digest pinned in `app/magazines.lock` and stages it under `app/build/`
only. Details, publishing and update behaviour: [docs/RELEASE.md](docs/RELEASE.md#magazine-content-separate-repository).

## Docs

- [Manual](app/src/main/assets/MANUAL.md) — the user manual bundled in the app
- [docs/SPEC.md](docs/SPEC.md) — behaviour, controller grammar and implementation notes for contributors
- [docs/formats/](docs/formats/) — on-card file formats, including magazine packs
- [docs/LAUNCHERS.md](docs/LAUNCHERS.md) — emulator launch intents and what was verified
- [docs/RELEASE.md](docs/RELEASE.md) — signing key custody, versions, signed APKs, beta status and magazine content
- [docs/NOW_PLAYING.md](docs/NOW_PLAYING.md) — scope note for RetroArch companion controls (not built)
- [CHANGELOG.md](CHANGELOG.md)

## Branding

The amber Split-S is Sortie's mark. [Brand assets](branding/) include a transparent `mark.svg`, an outlined
`logo.svg` wordmark, a square `icon.svg` / 512px `icon.png`, and a 1280×640 `social-preview.svg` / `social-preview.png`.
The SVGs are self-contained; lettering is outlined from Lato Heavy Italic and Lato Regular, so no fonts need to be installed.
Colours: amber `#F2B134`, ink `#101418`, and light text `#E6E8EB`.

The Android foreground uses the same geometry, centered inside the adaptive icon's 66dp safe circle. Its alpha shape
also supplies Android 13+ themed icons. Keep the SVG mark and Android vector in sync when changing the geometry.
Regenerate the PNG exports with ImageMagick, for example `magick -background none branding/icon.svg branding/icon.png`
and `magick -background none branding/social-preview.svg branding/social-preview.png`.

To set GitHub's repository-specific link image, upload `branding/social-preview.png` under **Settings → Social preview**.
GitHub does not expose this upload through the public REST/GraphQL APIs or `gh`; repositories also have no independent
avatar (the owner or organization avatar is shared). The matching [Sortie Zines](https://github.com/sortie-launcher/sortie-zines)
identity uses the Split-S inside a folded page.

## Licence

Sortie's source code is MIT-licensed; see [LICENSE](LICENSE). The MIT licence covers the code and original text written
for this project. It does **not** cover third-party material:

- **Magazines** are not in this repository. The separate `sortie-zines` content repository holds the Field Notes issues,
  which an APK includes only when built with `-PsortieMagazinesDir`. Their screenshots, manual scans, box art and quotations
  belong to their respective rights holders and are not licensed under MIT; each issue carries a compact rights notice and
  credits every picture in `sources.json`.
- **System logos** (for example rendered from the [slate-es-de](https://gitlab.com/es-de/themes/slate-es-de) theme,
  CC-BY-NC-SA) are not part of this repository; they live only on a user's card.
- Covers, screenshots and gamelists a user scrapes with ES-DE stay on their card and are not part of this repository.
