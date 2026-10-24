<img src="branding/logo.svg" alt="Sortie" width="420">

**Offline game launcher for Android handhelds.** Sortie shows games on your device, starts them in your emulators, and keeps your notes, backlog and play history beside them. No internet permission, no downloads.

> [!IMPORTANT]
> **Sortie is built with AI assistance.** The app's code, documentation and optional Field Notes magazine are AI-written. If that's a dealbreaker, know now.

**Status: beta, not released.** No APK available yet. See [Installing](#installing) for details.

<img src="docs/screenshots/readme/home.png" alt="Sortie's Home screen with demo entries" width="100%">
<sub>Real app UI with illustrative demo entries. Optional artwork and magazines are configured for these Android emulator captures; fresh libraries start without artwork. [Capture notes](docs/screenshots/readme/PROVENANCE.md).</sub>

**New to emulation?** Start with the [Getting started guide](docs/GETTING_STARTED.md). Assumes no terminal, no computer setup.

## Is it for you?

Sortie suits you if you:
- Have an Android 11+ handheld with game files
- Use emulators (RetroArch, PPSSPP) or will install them
- Want controller-driven access without accounts or sync
- Keep a backlog, notes and play history

Probably not for you if you:
- Want an app that finds/downloads games, BIOS, emulators or art
- Want a ready-made install today (none available)
- Need confirmed support for your exact device/emulators

Built first for AYN Thor (Android 13, dual-screen), also runs on single-screen handhelds.

## A look around

### Find something to play
<img src="docs/screenshots/readme/library.png" alt="PlayStation 2 grid with filters" width="100%">

Home shows systems, **L1/R1** switches sort order. Each system opens as list/grid with filters and **Pick one** for random choice. Search works without typing.

- **R2** on a game: add to *Want to play*
- Hold **A**: favourite or pin to Home
- Hold **R1** on a system: jump to random game

### Open a game's page
<img src="docs/screenshots/readme/game-detail.png" alt="Armored Core 3 game page" width="100%">

Shows art, description, play time. **Play** starts with default emulator; **Launch with…** picks another. Records play sessions for your **Journal**.

Mark **Finished/Shelved** with verdict. Keep **reference cards** (controls, maps) — everything stays on device. **Export backup** saves preferences, notes, shelves and play history.

### Use the second screen
<img src="docs/screenshots/readme/companion.png" alt="Two screens: PS2 grid + companion panel" width="640">

On two-screen devices, the other screen follows what you're doing:
- Browse: shows focused game's art, description, notes
- Play: shows timer, *Where was I?* reminder, controls, reference cards
- Also holds 5-slot app dock and launcher

### Read about games
<img src="docs/screenshots/readme/magazine.png" alt="Field Notes article" width="100%">

**Field Notes** is an optional offline magazine. Sortie remembers your place, shows featured games on device and links them to game pages. Highlight passages, add notes to scrapbook. Issues live in [sortie-zines](https://github.com/sortie-launcher/sortie-zines).

### Set up on the handheld
<img src="docs/screenshots/readme/setup.png" alt="Library setup" width="100%">

**Library setup** adds systems, points to game folders, chooses emulators in fallback order. No JSON files, computer or terminal. [Getting started guide](docs/GETTING_STARTED.md) walks through it.

## What you need

**Required:**
- Android 11+
- Your game files (internal storage or card)
- Emulators, installed and set up
- All files access (Sortie explains on first start)

**Recommended:** controller (built-in or connected)

**Optional (each for one feature):**
- Second screen: companion panel
- Sortie as default Home app (press Home to return)
- Shizuku: powers switcher and notification bell
- Display over other apps: switcher appears over games
- Radial keyboard: controller-friendly text input
- Computer: add cover art, descriptions, curator notes

Browsing, library setup and launching work without optional items. See [feature-by-feature prerequisites](docs/GETTING_STARTED.md#optional-features-and-what-they-need).

## How Sortie thinks

- **Offline by construction.** No internet permission. Nothing scraped, synced, updated or reported.
- **Your games, your emulators.** Sortie organises and starts what's already there. Only knows emulator opened, not game runs.
- **Your library stays yours.** Removing a system never deletes game files, saves or notes.
- **Device is enough; computer optional.** Setup and play happen entirely on handheld.
- **Use only integration you want.** Open as app or make Home. Home role, Shizuku, overlay, keyboard are separate opt-ins.
- **Claims only what was tested.** Emulator presets note device-tested vs source-checked.

## Installing

No APK published yet. Plan: signed beta APKs on [GitHub releases](https://github.com/sortie-launcher/sortie/releases). No app store, account or auto-updater.

Test builds on [Actions](https://github.com/sortie-launcher/sortie/actions/workflows/build.yml) are debug-signed (not daily use) or unsigned (Android won't install). **Do not uninstall daily install for signature mismatch.**

Once you have an APK, [Getting started guide](docs/GETTING_STARTED.md) walks through installation, emulator setup, first system and optional features. Full [manual](app/src/main/assets/MANUAL.md) is built into app (book button beside Settings).

## Going further

Prepared library on computer adds:
- Cover art and descriptions from ES-DE scrape
- Curator ratings, hooks, tags, control notes ([format](docs/formats/guide.md))
- Smart folders ([format](docs/formats/folders.md)), system logos, custom launch profiles

[File formats](docs/formats/) documented. [examples/card/](examples/card/) is complete minimal card. Any desktop pipeline works — Sortie keeps what you edit on device, refuses to save over unreadable files.

More detail:
- [Manual](app/src/main/assets/MANUAL.md): every screen and control
- [docs/formats/](docs/formats/): configuration and content formats
- [docs/LAUNCHERS.md](docs/LAUNCHERS.md): emulator launch intents
- [docs/RELEASE.md](docs/RELEASE.md): beta status, limitations, signing
- [docs/SPEC.md](docs/SPEC.md): behaviour, controller grammar
- [CHANGELOG.md](CHANGELOG.md)

## Building from source

For developers. JDK 17, Android SDK (platform 35). Copy `local.properties.example` to `local.properties` and point `sdk.dir` at your SDK:

```
tools/build.sh        # ktlintCheck, tests, debug + unsigned release APK
tools/dev.sh install # adb install -r debug APK + grants
tools/release.sh      # signed release APK + SHA-256
```

Magazines are opt-in. Normal build contains none. Field Notes live in [sortie-zines](https://github.com/sortie-launcher/sortie-zines). Clone and pass its `issues/` folder:

```
git clone https://github.com/sortie-launcher/sortie-zines.git ../sortie-zines
tools/build.sh -PsortieMagazinesDir=../sortie-zines/issues
```

Build verifies content against catalog digest. Details: [docs/RELEASE.md](docs/RELEASE.md#magazine-content-separate-repository).

## Branding

Amber Split-S is Sortie's mark. [Brand assets](branding/) include mark.svg, logo.svg, icon.svg, social-preview.svg. SVGs self-contained (Lato fonts outlined). Colours: amber `#F2B134`, ink `#101418`, light text `#E6E8EB`.

Android foreground uses same geometry, centered in adaptive icon's 66dp safe circle. Regenerate PNGs with ImageMagick:
```
magick -background none branding/icon.svg branding/icon.png
magick -background none branding/social-preview.svg branding/social-preview.png
```

Upload `branding/social-preview.png` to **Settings → Social preview**.

## Licence

MIT-licensed source code. Does **not** cover:
- Magazines (separate repository, rights notices in each issue)
- System logos (user's card only)
- Documentation screenshots (third-party game art, excluded from MIT)