<img src="branding/logo.svg" alt="Sortie" width="420">

**An offline, controller-first game launcher for Android handhelds.** Sortie shows the games already on your device,
starts them in the emulators you have installed, and keeps your notes, backlog and play history beside them. It has
no internet permission and never downloads games, BIOS files, emulators or artwork.

> [!IMPORTANT]
> **Sortie is built in collaboration with AI.** The app's code and its documentation, including this page, are
> written with AI assistance, and so is the optional Field Notes magazine. If that is a dealbreaker for you, it is
> better to know now than after setting anything up.

**Status: beta, not yet released.** No APK has been published, so there is nothing to download from
[Releases](https://github.com/sortie-launcher/sortie/releases) today. See [Installing](#installing) for what that means.

<img src="docs/screenshots/readme/home.png" alt="Sortie's Home screen in the Custom sort: a Continue tile for the last game played, pinned games with box art, and buttons for Apps, Library setup, Search, Magazines, Journal and Settings" width="100%">

<sub>Real app UI with illustrative demo entries, not a supplied or launch-tested game library. Optional artwork and
magazines are configured for these Android emulator captures; fresh libraries start without artwork.
[Capture notes and image credits](docs/screenshots/readme/PROVENANCE.md).</sub>

**New to emulation, launchers or Android sideloading?** Start with the
[Getting started guide](docs/GETTING_STARTED.md). It assumes no terminal, no computer-side setup and no prior experience.

## Is it for you?

Sortie suits you if you:

- have a handheld running Android 11 or newer and game files you already have on it or its memory card;
- use emulators such as RetroArch or PPSSPP, or are willing to install and set them up yourself;
- want a controller-driven front end without accounts, a store or a sync service;
- like keeping a backlog, notes and a record of what you played.

It is probably not for you if you:

- want an app that finds or downloads games, BIOS files, emulators or cover art for you. Sortie never does;
- want a ready-made install from an app store or a release page today. There is none yet;
- need confirmed support for your exact device and emulators. Only a few combinations have been tested
  ([known limitations](docs/RELEASE.md#known-limitations));

Sortie is built first for the AYN Thor, an Android 13 handheld with two screens, where the second screen becomes a
companion panel. It also runs on single-screen handhelds and has been used on the AYANEO Pocket DMG.

## A look around

### Find something to play

<img src="docs/screenshots/readme/library.png" alt="A PlayStation 2 grid of box art with Pick one, Fit 4+, notes, started and on device filters, and controller hints along the bottom" width="100%">

Home shows your systems, and **L1/R1** switch how it is sorted: alphabetically, by game count, by generation, or your
own **Custom** layout of pinned systems, games, apps, smart folders, **Favourites**, **Want to play** and a
**Continue** tile for the last game you played. Each system opens as a list or grid with filters and **Pick one** for a
random choice. Search works without typing: choose a system, tag or genre and see what matches.

For example: on a game, press **R2** to add it to *Want to play*, or hold **A** to favourite it or pin it to Home. On
Home, hold **R1** on a system to jump to a random game in it.

### Open a game's page

<img src="docs/screenshots/readme/game-detail.png" alt="The Armored Core 3 game page: cover art, Play, Launch with…, Add notes, Reference cards, a favourite star, Want to play, Mark finished or shelved, and a Featured in link to a magazine article" width="100%">

A game's page shows its art, description and play time when they are available. **Play** starts the game with that
system's emulator; **Launch with…** picks a different one for this game only. Sortie records each play session, so
play time adds up here and in your weekly **Journal**, where you can add dated notes and pictures you choose.

The same page keeps what you want to remember. Mark a game **Finished** or **Shelved** with a short verdict. Keep
**reference cards**: a typed list of controls, a move list or an imported photo of a map. Everything stays on the
device, and **Export backup** in Settings saves the text to one file.

### Use the second screen

<img src="docs/screenshots/readme/companion.png" alt="Two screens at the same moment: the PlayStation 2 grid on top with Armored Core 3 focused, and below it the companion panel showing that game's art, platform, year, developer, a Want to play button and its description" width="640">

On a two-screen handheld, the other screen follows what you are doing. While you browse, it shows the focused game's
art, description and your notes. While you play, it shows a timer, your *Where was I?* reminder, control notes and
reference cards, all by touch, so the controller stays with the game. It also holds a five-slot app dock and a launcher.

### Read about games

<img src="docs/screenshots/readme/magazine.png" alt="A Field Notes article titled Armored Core 3: Weight, Wages and the Repair Bill, with a credited game screenshot, a standfirst and controller hints for paging, highlighting and clipping" width="100%">

**Field Notes** is an optional offline magazine about games, published separately in
[sortie-zines](https://github.com/sortie-launcher/sortie-zines). Sortie remembers your place in each article, shows
which featured games are on your device and links them to their game pages. Highlight a passage, add a note and keep
it in your scrapbook. Issues are not included by default: copy an issue folder onto your device, or use a build that
bundles them.

### Set up on the handheld

<img src="docs/screenshots/readme/setup.png" alt="Library setup editing a Game Boy Color system: the chosen game folder with one game file found, file types gbc, zip and 7z, and an emulator list where RetroArch with Gambatte shows as installed, noting that its core is set up inside RetroArch" width="100%">

**Library setup** adds systems from a built-in list or names your own, points them at the folders holding your games
and lets you choose installed emulators in fallback order. Each system notes what its emulators need before the first
game. No JSON files, computer or terminal involved. The [Getting started guide](docs/GETTING_STARTED.md) walks through it.

## What you need

**Required**

- **Android 11 or newer.** Sortie does not install on older versions.
- **Your game files**, on internal storage or a memory card. Sortie supplies none.
- **Emulators, installed and set up.** Each emulator's own first-run setup, RetroArch cores and any BIOS files are done
  inside that emulator. Sortie only starts what is already installed.
- **All files access**, an Android permission that lets Sortie read your game folders and keep its settings. Sortie
  explains it on first start and opens the right switch.

**Recommended:** a controller, built in or connected. Touch works too.

**Optional, each for one feature**

- **A second screen** shows the companion panel. Single-screen devices get a single-screen layout.
- **Sortie as default Home app** makes pressing Home return to Sortie (or its optional switcher).
  Switch back to your previous launcher any time from Android Settings.
- **[Shizuku](https://shizuku.rikka.app/)**, a separate app, powers the switcher that shows what is running and moves apps
  between screens, plus the companion's notification bell. The Home key opens the switcher when Sortie is your Home app.
- **Display over other apps** lets the switcher appear over a running game instead of as a normal Sortie screen.
- **Radial keyboard**, a controller-friendly Android keyboard, can be enabled for any text field.
- **A computer** can add cover art, descriptions, curator notes, smart folders and system logos. See
  [Going further](#going-further).

Browsing, library setup and launching work without any of the optional items. The guide has a
[feature-by-feature prerequisite table](docs/GETTING_STARTED.md#optional-features-and-what-they-need).

## How Sortie thinks

Sortie has opinions. Knowing them up front saves surprises.

- **Offline by construction.** No internet permission. Nothing is scraped, synced, checked for updates or reported.
- **Your games, your emulators.** Games, emulators, BIOS files and artwork come from you. Sortie organises and starts what
  is already there, and only knows that an emulator opened, not that the game runs.
- **Your library stays yours.** Removing a system from Sortie never deletes game files, saves or your notes.
  **Export backup** in Settings saves your preferences, notes, shelves and play history to one file. Some state, including
  magazine reading positions and Android shortcut pins, is outside that export; see [updating safely](docs/GETTING_STARTED.md#updating-safely).
- **The device is enough; a computer is optional.** Setup and play happen entirely on the handheld, where games are
  listed by cleaned-up file name. Prepared files add art and descriptions without replacing on-device setup.
- **Use only the integration you want.** Open Sortie as an ordinary app or make it your Home screen. Home role, Shizuku,
  overlay and keyboard are separate opt-ins.
- **It claims only what was tested.** Emulator presets say whether they were tried on a device or only checked against
  the emulator's source.

## Installing

No APK has been published yet. The plan is to attach signed beta APKs to
[GitHub releases](https://github.com/sortie-launcher/sortie/releases); there is no app store listing, account or
automatic updater. To hear when one appears, watch this repository's releases.

The build artifacts on the [Actions](https://github.com/sortie-launcher/sortie/actions/workflows/build.yml) page are test
builds without magazines: a debug-signed APK and an unsigned release APK, which Android will not install. They are not
meant for daily use. Android cannot update a debug-signed install to a future release in place. **Do not uninstall a daily
install to work around a signature mismatch:** ask for an APK signed with the correct key.

Once you have an APK, the [Getting started guide](docs/GETTING_STARTED.md) walks through
[installing it](docs/GETTING_STARTED.md#install-sortie), [setting up an emulator](docs/GETTING_STARTED.md#set-up-and-test-one-emulator),
[adding your first system](docs/GETTING_STARTED.md#add-your-first-system) and
[what each optional feature needs](docs/GETTING_STARTED.md#optional-features-and-what-they-need).
The full [manual](app/src/main/assets/MANUAL.md) is also built into the app: the book button beside Settings on Home.

## Going further

A library prepared on a computer adds what Sortie cannot produce on the device:

- cover art and descriptions from an [ES-DE](https://es-de.org/) scrape;
- a guide file of curator ratings, one-line hooks, tags and control notes ([format](docs/formats/guide.md));
- smart folders built from rules ([format](docs/formats/folders.md)), system logos and custom launch profiles.

The [file formats](docs/formats/) are documented and [examples/card/](examples/card/) is a complete minimal card. Any
desktop pipeline is yours to script; nothing in this repository depends on one. The on-device Library screen changes only
what you edit there, keeps everything else, and refuses to save over files it cannot read or that changed since it read
them, so a prepared library can be edited from either side.

More detail:

- [Manual](app/src/main/assets/MANUAL.md): every screen and control, as bundled in the app
- [docs/formats/](docs/formats/): configuration and content formats, including magazine packs
- [docs/LAUNCHERS.md](docs/LAUNCHERS.md): emulator launch intents and what was verified
- [docs/RELEASE.md](docs/RELEASE.md): beta status, evidence, known limitations, signing and magazine content
- [docs/SPEC.md](docs/SPEC.md): behaviour, controller grammar and implementation notes for contributors
- [docs/NOW_PLAYING.md](docs/NOW_PLAYING.md): scope note for RetroArch companion controls (not built)
- [CHANGELOG.md](CHANGELOG.md)

## Building from source

For developers. JDK 17 and the Android SDK (platform 35). Copy `local.properties.example` to `local.properties` and point
`sdk.dir` at your SDK:

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
live in [sortie-launcher/sortie-zines](https://github.com/sortie-launcher/sortie-zines); clone it alongside the app and
pass its `issues/` folder:

```
git clone https://github.com/sortie-launcher/sortie-zines.git ../sortie-zines
tools/build.sh -PsortieMagazinesDir=../sortie-zines/issues
```

The build verifies the content against the catalog digest pinned in `app/magazines.lock` and stages it under `app/build/`
only. Details, publishing and update behaviour: [docs/RELEASE.md](docs/RELEASE.md#magazine-content-separate-repository).

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
- **Documentation screenshots** contain third-party game art and descriptions, excluded from the MIT licence;
  [capture notes and credits](docs/screenshots/readme/PROVENANCE.md) distinguish illustrative entries from the tested homebrew.
  Complete game-art packs and gamelists are not distributed with the app source.
