<img src="branding/logo.svg" alt="Sortie" width="420">

**An offline, controller-first game launcher for Android handhelds.** Sortie lists the games already on your device,
starts them in the emulators you've installed, and keeps a bookmark per game, your backlog and play history beside them.
A curated guide of short hand-written game notes ships inside the app, and **Tonight** picks up to five of your own games
each day from your history and library metadata. It has no internet permission and never downloads games, BIOS files,
emulators or artwork.

> [!IMPORTANT]
> **Sortie is built in collaboration with AI.** The code, the documentation and the optional Field Notes magazine are
> written with AI assistance. If that's a dealbreaker, better to know now than after setup.

<img src="docs/screenshots/readme/home.png" alt="Sortie on a Retroid Pocket Nova: Home in By Console mode, a PlayStation 2 game grid with Armored Core 3 focused, the folded header and compact controller hints with a Controls button" width="100%">

<sub>Captured on a Retroid Pocket Nova running signed beta 2115, with an existing library and artwork, before Home's header
gained the Split-S mark and Extras menu.
[Capture notes and image credits](docs/screenshots/readme/PROVENANCE.md).</sub>

### **[Documentation → sortie-launcher.github.io/sortie](https://sortie-launcher.github.io/sortie/)**

- **[Getting started](https://sortie-launcher.github.io/sortie/getting-started/)**: from nothing to your first game; no
  computer or terminal needed.
- **[Coming from Cocoon](https://sortie-launcher.github.io/sortie/getting-started/#coming-from-cocoon)**: import your
  systems, favourites, artwork, folders and play history without changing Cocoon's files.
- **[Manual](https://sortie-launcher.github.io/sortie/manual/)**: every screen and control (also built into the app).
- **[Field Notes](https://sortie-launcher.github.io/sortie-zines/)**: the offline game magazine, readable in a browser.

## Install

Beta. Download the newest `.apk` (and its `.sha256`) from
[GitHub releases](https://github.com/sortie-launcher/sortie/releases): merges to `main` that run and pass the Android build
publish a signed beta release marked Latest, checked by hosted tests and signature verification but not tested on hardware build by build. Needs
Android 11 or newer. Install newer APKs over the old one. **Never uninstall to update**, and don't use the debug-signed
[Actions](https://github.com/sortie-launcher/sortie/actions/workflows/build.yml) artifacts as a daily install
([why](https://sortie-launcher.github.io/sortie/project/release/)).

## Documentation source

The site is generated from this repository's Markdown on every push to `main`: [docs/](docs/), the in-app
[manual](app/src/main/assets/MANUAL.md) and the [changelog](CHANGELOG.md). Edit those files, not the site; `site/` holds
the generator and landing page, and its build fails on any broken link or anchor. Preview with
`cd site && npm ci && npm run dev`.

## Building from source

JDK 17 and Android SDK platform 35. Copy `local.properties.example` to `local.properties` and set `sdk.dir`.

```
tools/build.sh        # ktlintCheck, debug + release unit tests, debug APK and an unsigned release APK (no formatting)
tools/dev.sh install  # adb install -r of the debug APK + grants; SORTIE_HOME=1 also restores the Home role
tools/release.sh      # signed release APK + SHA-256 in dist/, using the key from tools/init-release-key.sh
```

Plain `./gradlew ktlintCheck testDebugUnitTest assembleDebug` works too. The helpers use `java` from `PATH`, falling
back to a distrobox container named `ubuntu` only when there is none. `tools/build.sh` and `tools/release.sh` pass extra
arguments to Gradle; `tools/dev.sh` reads `SORTIE_SERIAL`, `SORTIE_TOP_DISPLAY` and `SORTIE_BOTTOM_DISPLAY`. Debug
builds accept `am start … --es open <route>`, `--es launch '<system>/<file>'`, and `--es builtin magazines|performance --ei display N`.
These debug hooks are disabled in release builds. Releases, signing and versions:
[docs/RELEASE.md](docs/RELEASE.md).

**Magazines are opt-in locally.** Local builds contain no issues and never download any; hosted CI resolves
[sortie-zines](https://github.com/sortie-launcher/sortie-zines) `main` once per build and uses that exact commit and
catalog digest for testing and signing. To bundle a reviewed content checkout yourself:

```
git clone https://github.com/sortie-launcher/sortie-zines.git ../sortie-zines
tools/pin-zines-snapshot.py ../sortie-zines  # build-local lock; do not commit this generated snapshot
tools/build.sh -PsortieMagazinesDir=../sortie-zines/issues
```

## Licence

Code and original text are MIT-licensed ([LICENSE](LICENSE)). Third-party material is not:

- **Magazines** live in the separate `sortie-zines` repository and enter an APK only through `-PsortieMagazinesDir`.
  Their screenshots, manual scans, box art and quotations belong to their rights holders; each issue carries a rights
  notice and credits every picture in `sources.json`.
- **System logos**, for example rendered from the CC-BY-NC-SA [slate-es-de](https://gitlab.com/es-de/themes/slate-es-de)
  theme, aren't in this repository; they live only on a user's card.
- **Documentation screenshots** include third-party game art and descriptions, excluded from MIT; capture notes distinguish
  the Nova's library, illustrative entries and tested homebrew. Game-art packs and gamelists are not distributed with the source.

Branding (the amber Split-S): [branding/](branding/README.md).
