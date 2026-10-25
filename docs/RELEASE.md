# Releases: signing, versions, beta status and magazine content

Sortie is distributed as signed APKs on [GitHub releases](https://github.com/sortie-launcher/sortie/releases): every
successful push to `main` publishes an automated beta prerelease. A signed APK can also be built locally for isolated
testing. There is no Google Play listing, app store account, update server, in-app updater, telemetry or download service.
This file is the maintainer's procedure plus the current beta's goal, acceptance criteria, evidence and known limitations.

## Publishing the repositories

The application and `sortie-zines` content are independent Git repositories. The application's publication history has
been rewritten to remove private operational notes, personal machine identifiers and magazine media; author metadata
uses a GitHub noreply address. Original development checkouts and recovery bundles are private backups, not publication
sources. Do not merge their old branches into this history or push them with `--mirror`; transfer later work as reviewed
patches so removed material cannot return through ancestry.

Private maintainer notes belong under ignored `.local/`, not `docs/`. Keep signing files outside both repositories; the
only hosted copy is the pair of encrypted environment secrets described in [Automatic beta releases](#automatic-beta-releases).
The repositories are [sortie-launcher/sortie](https://github.com/sortie-launcher/sortie) and
[sortie-launcher/sortie-zines](https://github.com/sortie-launcher/sortie-zines). Their SSH origins are configured on the
sanitized checkouts. Changes reach `main` through reviewed pull requests; never force-push it. **A push to `main` is a
release:** once the hosted build passes, the workflow signs and publishes a prerelease of that commit. Local verification
is not a hosted CI result.

## One permanent release key

Android only installs an update over an existing app when both are signed by the **same certificate**. The release key is
therefore permanent for the app id `io.github.esko997.sortie`: losing it means every installed copy can never be updated
again; leaking it means anyone can sign an "update" that Android accepts.

- Create it once with `tools/init-release-key.sh [directory]`. The default directory is
  `${XDG_DATA_HOME:-~/.local/share}/sortie/signing` (or `$SORTIE_SIGNING_DIR`). It must be outside the repository and
  owner-only. The script writes `sortie-release.p12` (PKCS12, RSA 3072, alias `sortie-release`) and `store-password`
  (random; also the key password), prints the certificate SHA-256, and refuses to overwrite or rotate an existing key.
- Back up **both files together** to another machine and test recovery before distributing an APK. The keystore without
  its password file is useless, and so is the password without the keystore. Read the stored copies back, verify their
  hashes, and prove the password opens the private key matching the release certificate.
- An additional offline, encrypted copy (for example an encrypted USB drive) is recommended. Online machine backups
  protect against losing the build machine, but are not an offline recovery copy.
- Never commit, paste or send either file, and never put either in source, logs, build artifacts or caches. The one
  approved exception is the encrypted GitHub environment-secret copy used by the release job (see
  [Secrets and the `release` environment](#secrets-and-the-release-environment)). `.gitignore` excludes `*.p12`, `*.jks`,
  `*.keystore`, `store-password` and `dist/`, but the real protection is keeping the files outside the repository.
- Do not rotate the key during the beta. A new key means a different app as far as installed copies are concerned.
- The certificate SHA-256 fingerprint is public information. The release workflow pins it, refuses an APK signed by
  anything else and prints it in every release's notes so downloads can be compared against it.

**Custody status:** the release key remains outside the repositories. Its certificate SHA-256 is
`c4fc71bdee0987939c7af166465241c9c1bd3fc6c0a486d9dd76546975414cf4`; key creation refuses to overwrite it.
Both signing files have verified owner-only backups on two separate machines: directory mode `0700`, files `0600`.
Each stored copy was reopened, its bytes checked against the source, and its password used to recover a private key
matching that certificate. Temporary transfer authorization was removed. Hostnames and paths are recorded privately.
These are online backups; remote disk encryption and an additional offline copy have not been verified.
On 2026-10-24 the owner approved a further copy of both files as encrypted secrets in the `main`-only `release`
environment, accepting that GitHub and anyone able to change `main` are then inside the key's trust boundary (see
[Who can sign](#who-can-sign)). That copy is not a backup: secret values cannot be read back, so recovery still depends on
the machine backups.

## Versions

- `versionCode` must increase for every APK that leaves the machine and is never reused. Android refuses to install an
  update with a lower `versionCode` than the installed one.
- `versionName` is the human version, `X.Y.Z` or `X.Y.Z-pre` (for example `0.1.0-beta.2`).
- **Hosted releases** use code `1000 + run number` of the `build` workflow, name `0.1.0-beta.<code>` and tag
  `v0.1.0-beta.<code>` (run 5 would be code 1005, tag `v0.1.0-beta.1005`). The run number counts every run of
  `.github/workflows/build.yml`, pull requests included, so codes increase with gaps. A re-run keeps its run number and
  therefore its version; it never replaces a published release.
- **Keep the workflow's identity.** Renaming, moving or recreating the workflow can reset its run number, after which
  releases with reused or older codes will be refused or skipped. If a reset is unavoidable, raise the `1000` base in the
  same change above the highest published code.
- **Local builds:** the defaults in `app/build.gradle.kts` (currently code `9`, name `0.1.0-beta.8`) name the last
  manually built signed preview. Hosted releases override both and do not commit new defaults. Override per build with
  `-PsortieVersionCode=N -PsortieVersionName=X.Y.Z-beta.N`; Gradle rejects malformed values.
- **Codes below 1000 are reserved for manual signed builds.** Give each manual APK that leaves the machine the next unused
  code and raise the defaults to match, so an ordinary build cannot reuse it. Every hosted release is above them, so a
  release-signed manual install updates in place to any hosted release; the reverse is a downgrade Android refuses.
  Never hand-build in the hosted range or create a `vX.Y.Z-beta.N` release or tag by hand: a higher manual number makes
  the workflow skip every hosted release below it.
- Record user-visible changes under `## Unreleased` in `CHANGELOG.md` in the same pull request. Hosted release notes are
  generated from merged pull requests; releases do not edit the changelog.

## Building a release

```
tools/build.sh                 # style check, debug + release unit tests, debug APK and an UNSIGNED release APK
tools/release.sh -PsortieVersionCode=10 -PsortieVersionName=0.1.0-beta.9
```

Both helpers forward extra arguments to Gradle, so a release that bundles magazines adds
`-PsortieMagazinesDir=/path/to/sortie-zines/issues` (see [Magazine content](#magazine-content-separate-repository)).
Without it, the APK contains no magazine issues.

`tools/release.sh` reads the key from the default signing directory (or `SORTIE_SIGNING_DIR`, or all three of
`SORTIE_RELEASE_KEYSTORE`, `SORTIE_RELEASE_KEYSTORE_PASSWORD_FILE`, `SORTIE_RELEASE_KEY_ALIAS`). It:

- refuses signing files that are missing, inside the repository or readable by other users;
- builds `assembleRelease` without the Gradle daemon, configuration cache or build cache;
- fails instead of falling back to the debug key, and fails if signing is only partly configured;
- verifies the APK has exactly one v2 signer whose certificate matches the release key, the expected package, a valid
  version and no `debuggable` flag, and that the password was not written into build outputs or daemon logs;
- writes `dist/sortie-<versionName>-code<versionCode>.apk` and its `.sha256`, and refuses to replace an existing file of the
  same name with different contents.

It does not format code, upload, tag or publish anything; the release workflow runs it and publishes separately.
Without signing variables `assembleRelease` (including `tools/build.sh` and the workflow's build job) produces an
**unsigned** APK. That is build evidence only; Android will not install it, and it must not be distributed.

## Automatic beta releases

### What runs

`.github/workflows/build.yml` has two jobs:

- **`build-apks`** runs for every pull request, every push to `main` and every manual run. It checks out the commit and the
  pinned `sortie-zines` commit, assigns the run's version, runs `ktlintCheck` plus debug and release unit tests, builds both
  variants with the magazines bundled, and uploads the debug-signed and unsigned-release APKs as artifacts. Its token is
  read-only and it receives no secrets.
- **`release`** runs only after `build-apks` succeeds, only in `sortie-launcher/sortie`, and only for a push to `main` or
  a manual run on `main`; never for pull requests. It uses the `release` environment and a `contents: write` token, and:
  1. lists existing releases. This tag already published for this commit: nothing to do (a re-run). This tag as a draft or
     for another commit: fail for [inspection](#recovering-a-failed-release). Any `vX.Y.Z-beta.N` release with `N` above
     this run's code: skip, this run is older.
  2. checks out the same commit and content pin again, decodes the key into an owner-only directory under the runner's
     temporary directory (outside the checkout) and runs `tools/release.sh` with the run's version and the pinned
     magazines. No Gradle cache is restored or saved in this job.
  3. requires the APK's signer certificate SHA-256 to be the permanent one above and its `.sha256` to verify.
  4. creates a draft with the APK, its `.sha256`, generated notes and a fixed header (commit, version code, "update, do not
     uninstall", content commit, magazine media rights, certificate fingerprint, not hardware-tested per build), then
     publishes it as a prerelease not marked Latest.
  5. deletes the signing directory on exit and again in a step that runs even after failure.

Release jobs share one concurrency group and never cancel a running one. GitHub keeps only the newest *waiting* job in a
group, so when several merges land quickly an intermediate release can be cancelled; that merge gets no release of its own
and the next one supersedes it.

Automatic releases are prereleases, not stable releases. Their gate is the hosted style check, unit tests, signature check
and checksum; they are not installed on hardware one by one. Device evidence in [Beta 0.1](#beta-01) applies only to the
builds it names.

### Secrets and the `release` environment

Repository Settings → Environments → `release`, with deployment branches limited to `main`. It holds two environment
secrets (not repository secrets):

- `SORTIE_RELEASE_KEYSTORE_BASE64`: `sortie-release.p12`, base64-encoded on one line.
- `SORTIE_RELEASE_KEYSTORE_PASSWORD`: the password line of `store-password` without a trailing newline. The job writes
  the file back with one; a stored extra newline makes `tools/release.sh` refuse it.

Set them from the signing directory without displaying either value:

```sh
base64 -w0 sortie-release.p12 | gh secret set SORTIE_RELEASE_KEYSTORE_BASE64 --env release --repo sortie-launcher/sortie
tr -d '\n' < store-password | gh secret set SORTIE_RELEASE_KEYSTORE_PASSWORD --env release --repo sortie-launcher/sortie
```

Never echo them, enable shell tracing around them, upload or cache the signing directory, or write it into the checkout;
GitHub's log masking is a backstop, not the control. Missing or empty secrets fail the job. There is no unsigned or
debug-signed fallback.

### Who can sign

The signing step runs the merged commit's Gradle build scripts, plugins and dependencies, `tools/release.sh` and the
workflow itself while the key and password are on disk. Anyone who can get code onto `main`, by merging, pushing or
editing the workflow, can therefore use or extract the key, and an extracted key signs updates every installed copy
accepts. Consequently:

- Review every merge to `main` as code that handles the signing key, especially `.github/workflows/`, Gradle build files
  and wrapper, `tools/` and new or changed dependencies. Keep `main` protected and repository write access minimal.
- Pull requests, including forks, run only `build-apks`: no secrets, read-only token. The environment's branch rule keeps
  other branches out even if their workflow names `release`.
- Third-party actions are pinned to commit SHAs. Magazine content is data checked against `app/magazines.lock`, not
  executed.
- If the key may have leaked, delete both environment secrets (or disable the workflow) first, then assess. Installed
  copies cannot move to a new key without uninstalling (see [One permanent release key](#one-permanent-release-key)).

### Pinned magazine content

Hosted builds bundle exactly `sortie-launcher/sortie-zines` at the commit in `ZINES_COMMIT` at the top of `build.yml`
(currently `65c179e913b30e643879d59325d676b99459a1a0`), and Gradle verifies that content against `app/magazines.lock`.
To ship new or corrected issues:

1. Publish into a `sortie-zines` checkout with `tools/publish-magazines.py … --pin` (see
   [Publishing issue packs](#publishing-issue-packs)), review it and push `sortie-zines` `main`.
2. In one app pull request, set `ZINES_COMMIT` to the full SHA of that pushed commit and commit the updated
   `app/magazines.lock`.
3. The pull request's `build-apks` run proves the two pins agree; merging publishes a release with the new content.

If they disagree the build fails; there is no fallback to other content or to none.

### Recovering a failed release

- **Build, tests or signing failed:** nothing was published. Re-running the failed jobs reuses the same version and suits
  transient failures; otherwise fix forward, and the next merge releases under a new code.
- **Failed after the draft was created** (asset upload or the publish edit): the release exists as a draft and every
  re-run stops with "Existing release is a draft or belongs to another commit". Inspect it: its target must be that run's
  commit, with exactly the APK and its `.sha256` attached. Then either
  - delete it (`gh release delete <tag>`; if `git ls-remote --tags origin <tag>` still lists the tag, delete that too) and
    re-run the failed `release` job, which rebuilds and re-signs the same version; or
  - download both assets, check the checksum and `apksigner verify --print-certs` against the certificate above, and only
    then publish it with `gh release edit <tag> --draft=false --prerelease --latest=false`.

  When in doubt, delete and re-run. Never publish unverified assets or attach a different APK to an existing tag.
- **Tag belongs to another commit:** something created that tag by hand or reused it. Stop and investigate; do not delete
  a published release testers may have installed.
- A re-run of an already published release changes nothing. Published releases are never replaced; ship fixes as a new
  merge.

## Distributing

Normal path: merge to `main`. When the run passes, the release appears on the Releases page as a prerelease; check that
it carries exactly the APK and its `.sha256`.

Manual signed builds from `tools/release.sh` are for an isolated test device or for preparing a device before hosted
releases; never attach them to GitHub releases. Before one leaves the machine: the key backup exists, `tools/build.sh`
passes, it has a new manual version (see [Versions](#versions)), and it was installed over the previous release on an
isolated test device (below) with data surviving.

Tester instructions: download the newest prerelease's `.apk` (and its `.sha256`) from the
[Releases page](https://github.com/sortie-launcher/sortie/releases). Check the SHA-256 if you can, open the APK and allow
your browser or file manager to install unknown apps when Android asks. For an update, open the new APK and choose
**Update**; never uninstall first, because uninstalling removes Sortie's Android-owned and app-private data (see the
manual's *Backup and restore*). Android's app info shows the version, which matches the release name. Any copy signed with
the release key, manual or hosted, updates in place to a newer hosted release; a debug-signed copy cannot (below).
If Android says the app is not compatible or the package conflicts, stop and report it. Signature, version or device
compatibility can cause installation failures; uninstalling is not a safe diagnostic step.

## Debug and release signatures

Debug and release builds share the app id but not the certificate: `tools/build.sh` and `tools/dev.sh` produce
debug-key-signed builds, `tools/release.sh` release-key-signed ones. Android therefore refuses to install a release APK over
a debug install (and the reverse), usually as "App not installed" or `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

**Never uninstall a daily-use debug installation to get past this error.** Uninstalling deletes Sortie's app database
(play history not yet exported), magazine reading positions, the remembered library location, shortcut pins, granted
permissions and the Home role. Instead:

- Keep daily development devices on debug builds, updated with `tools/dev.sh install` (`adb install -r`).
- Exercise release builds and release-to-release upgrades on an **isolated test device**: an Android emulator (AVD) or a
  spare handheld that holds no one's daily setup. A second Android user on the same device is not isolation: Android
  requires every user's copy of a package to have the same signer.
- Moving a daily device from debug to release signing is a deliberate migration, not a fix: it requires an uninstall, so do
  it only with the owner's agreement, after a verified backup export and a copy of the card's `sortie/` folder, and only
  once the release update path has been proven on the test device.

## Beta 0.1

### Goal

A nontechnical Android-handheld user can use Sortie as their regular launcher: configure and maintain multiple systems and
folders, choose installed emulators, browse/search/save personal state, launch games and return, understand failures,
recover from missing storage/permissions, and install updates without data loss. Rough edges may remain if documented;
blank setup, silent errors, destructive recovery and a broken update path are not acceptable beta limitations.
No accounts, server, app store, automatic updater, ROM/BIOS downloads or telemetry are required.

### Acceptance scenarios

1. Fresh installation: grant storage through Android UI, add at least two systems from different directories, select usable
   emulator profiles and launch/return. No JSON editing or ADB configuration. Explain emulator/core/BIOS prerequisites.
2. Daily maintenance: add a root, edit a system without losing custom fields, change emulator, rescan, remove a library entry
   without deleting ROMs or personal notes, and continue using favourites/Want to play/Finished/Shelved/search.
3. Existing library: opening setup never resets a desktop-generated configuration. Invalid/concurrently edited files cannot
   be overwritten as if empty. Interrupted setup leaves a recoverable complete configuration, not a mixed set.
4. Recovery: missing/unreadable volumes preserve useful library state; restored or moved roots resolve the correct paths;
   failed launches and unavailable Android settings screens explain the problem instead of silently doing nothing/crashing.
5. Usability: touch and controller paths are usable on small and large surfaces, dialogs/drafts behave predictably on Back,
   optional features do not demand Shizuku/overlay/IME/Home permissions, and a newcomer can find library management.
6. Upgrade: two APK versions signed with the same beta key update in place on an isolated install, preserving representative
   preferences, notes and session data. Release build is not debuggable. Signature mismatch must never be solved by uninstalling
   the user's existing daily installation.
7. Integrated regression: magazine reading/navigation and the journal/reference/shelf features remain functional.

### Implemented

- On-device library setup and maintenance (Home → Library setup, Settings → Library and emulators): multiple systems and
  folders, built-in emulator presets with plain prerequisites, BIOS/save folders, re-index, safe removal, existing desktop
  configurations preserved (unknown fields kept, unreadable or concurrently changed files refused, atomic commit with
  interrupted-write recovery, remembered configuration location).
- Missing or unreadable storage keeps the previous index and the remembered settings location; moved roots refresh paths;
  failed game/app/shortcut launches show a message.
- Plain-language All files access screen with fallbacks; overlay/Shizuku/Home role/radial keyboard stay optional.
- Persistent release key tooling, versioning and verified signed APK packaging.
- Automatic signed prerelease publication from `main` with pinned magazine content (see
  [Automatic beta releases](#automatic-beta-releases)).

### Evidence (2026-10-24)

Signed candidates were built locally with `tools/release.sh`; none has been published. The recorded beta acceptance runs
cover version codes 2–7, with the focused retests identified below. The latest local candidate,
`sortie-0.1.0-beta.6-code7.apk` (SHA-256 `727c6e4dbd6f616458a146ebc72d6bad98497cd02259ac8af00d5f1b0ea067e0`),
verifies with `apksigner` as a single v2 signer with the certificate above.

- `tools/release.sh ktlintCheck test --continue`: style, 204 JVM tests per variant with no failures or skips, one expected
  v2 signer and a non-debuggable APK. Partial signing configuration is rejected.
- Disposable Android 13 (API 33, x86_64, 1280×800) emulator: file-access permission, folder browsing, two-system setup,
  index results and controller selection through the UI, without writing configuration from ADB. PPSSPP 1.20.4 ran its
  public cube demo packaged as a homebrew ISO; RetroArch 1.22.2/Gambatte ran the GB Assembly Tutorial's Hello World ROM.
  Fixtures and launcher details are in [LAUNCHERS.md](LAUNCHERS.md).
- A newcomer given only the app and user goals (no source or configuration walkthrough) configured PSP and Game Boy, added
  another root, launched both games, saved favourites/queue/a note and resumed a magazine article after process restart.
  Their usability findings were fixed.
- Signed code 2 → code 3 upgrade in place: all durable files (settings, user data, Room database/WAL, play log, reading
  preferences) byte-identical before reopening; only SQLite's shared-memory lock file changed. File permission and running
  emulator processes survived. Later code 3 → code 6 → code 7 upgrades also preserved personal metadata, database/WAL and
  reading/settings files before reopening, with PPSSPP still running. Code 7 survived reboot with preferences unchanged
  and usable Home; code 5 ignored a valid debug launch extra.
- Recovery: a missing pinned settings folder is not recreated by preference writes; stale empty state cannot overwrite a
  restored `user.json`; retry reloads every store once. Malformed `user.json`/`systems.json` are reported and never
  overwritten; a concurrent external edit rejects a stale draft and Reload preserves the external unknown field. Missing
  game roots keep indexed games and give launch errors; revoking and re-granting file access restores the library.
- Removing the last system leaves no stale indexed game and returns to the setup welcome; ROM files stay on disk.
- Shortcut pin requests show the requesting app and shortcut; Cancel/Back add nothing, Add pins exactly one shortcut.
- Daily-use pass: 98 navigation checkpoints across 1280×800, 800×480 and 480×800 with a virtual gamepad, no Sortie crash or
  ANR; Finished/Shelved verdicts, reference cards, journal notes and reading positions survived restart. Compact-layout and
  controller-focus findings were fixed in later candidates.
- Installed code 6 retests: final note/verdict/reference fields reached Save with Down/A at 1280×800 and 800×480;
  over-limit drafts retained focus, cancellation preserved data, clipping/journal traversal worked, and backup summaries
  counted shelf entries correctly.
- Installed code 7 retests: repeated list/grid and folder/back navigation retained selection; deliberate Up still reached
  the header. Compact Home/Search at 800×480 and portrait Home at 480×800 remained usable by pad and touch. Search opened
  its keyboard after touch and Down reached filters/results. Temporary personal edits were removed after the checks.
- Setup/cancel/edit/remove, multiple folders, malformed/external changes, missing storage, unavailable emulator,
  permission denial, process restart and update-preservation acceptance scenarios were completed for the tested fixtures.
- Backup export, replace preview, safety copy, repeat restore and cross-device session deduplication behaved as documented.

### Known limitations

- RetroArch 1.22.2 exits on the first attempt to open *different* content and boots it on the second; same-game resume
  works. Sortie does not force-stop it. Old custom profiles keep `CLEAR_TASK` until edited (see LAUNCHERS.md); one long run
  hit an external RetroArch ANR with that legacy flag, and a separate background RetroArch native crash was seen on the
  x86_64 emulator with unestablished cause.
- Loose PSP ELF homebrew does not start from Sortie; homebrew ISOs do.
- Only the emulator versions and devices named here and in LAUNCHERS.md were exercised. Other presets carry their stated
  untested/source-checked status; an installed package does not prove its core, BIOS or game is ready.

### Remaining gates

- [x] Off-machine, tested backups of the release keystore **and** password file on two separate machines.
- [ ] Broader launch-and-return coverage beyond PPSSPP 1.20.4 and RetroArch 1.22.2/Gambatte on API 33.
- [ ] Hardware coverage beyond the devices actually tested: Android versions, single/dual screen, touch/controller,
  storage layouts and OEM settings screens.
- [x] Hosted GitHub CI passed after source publication: [build run 40135126335](https://github.com/sortie-launcher/sortie/actions/runs/40135126335)
  checked style, ran both unit-test variants and built/uploaded debug and unsigned-release artifacts. CI does not sign
  releases or bundle zines by default.
  That was the state at source publication; the workflow has since been extended to bundle the pinned zines and to sign
  and publish prereleases from `main` (see [Automatic beta releases](#automatic-beta-releases)).

After changing signing or publication automation, download its first hosted release, verify its checksum and certificate,
and install it over a release-signed copy on an isolated test device. Record the result in that release's notes; workflow
success alone does not prove the installed upgrade path.

Later, for a wider public release:

- Audit dependency licences and provide notices.
- Test exported components, URI grants, malformed manifests/packs and path containment; release builds must not expose
  debug launch hooks.
- Revisit hosted key custody if more people get write access: anyone who can change `main` can sign (see
  [Who can sign](#who-can-sign)).
- App stores are a separate decision: broad file access and installed-app visibility need store-policy review.

Keep minification disabled until release-variant device tests cover serialization, Room, reflection and the Shizuku service.

## Magazine content (separate repository)

The Field Notes zines are not part of this repository. Their issue packs live in a separate content repository,
`sortie-zines`, whose `issues/` directory holds `catalog.json` plus one `<issue-id>/` folder per issue. Clone it next to
this repository (or anywhere else) yourself; the build never downloads it. Keeping the two apart means the MIT-licensed
code can be cloned, built and distributed without third-party magazine media.

- **Default local build: no zines.** Without `sortieMagazinesDir`, `tools/build.sh`, `tools/release.sh` and plain Gradle
  builds bundle no issues and Gradle never fetches anything. The generated asset folder is emptied on every such build, so
  content staged by an earlier build cannot leak into a later one. The Magazines shelf starts empty and still reads card
  packs. The hosted workflow is the exception: it checks out the pinned content commit itself and passes it in (see
  [Pinned magazine content](#pinned-magazine-content)).
- **Opt-in bundle:** pass the content directory explicitly. Relative paths resolve against this repository's root; Gradle's
  standard `ORG_GRADLE_PROJECT_sortieMagazinesDir` environment variable works too. An empty value is an error.

  ```sh
  tools/build.sh -PsortieMagazinesDir=../sortie-zines/issues
  tools/release.sh -PsortieVersionCode=10 -PsortieVersionName=0.1.0-beta.9 -PsortieMagazinesDir=../sortie-zines/issues
  ```

  `:app:stageMagazineAssets` verifies the directory and copies it into
  `app/build/generated/assets/stageMagazineAssets/magazines/`, which the APK packages as `assets/magazines/`. The build
  fails, rather than shipping different content, when the path is missing, not a folder or inside `app/`; contains a
  symlink or a visible file absent from the catalog; when any size, SHA-256, issue digest or the catalog digest does not
  match; when an `issue.json` has an invalid id, revision or schema; or when the catalog digest differs from the pin.
  Dot-files are ignored and never copied. There is no fallback content.
- **No in-tree zines:** the build also refuses any `app/src/{main,debug,release}/assets/magazines/` folder; those paths are
  git-ignored. Issues reach an APK only through `sortieMagazinesDir`.
- **Pinning:** `app/magazines.lock` holds `#` comment lines and exactly one 64-hex catalog digest (currently
  `aacc00fc7f6bc8bcf44f9f68475fbd7f24e4de2e6c5ec61ecedb94c3c382975c`). Change it only deliberately, together with the
  content-repository commit it should ship (see `--pin` below) and the workflow's `ZINES_COMMIT`.
- **Update preservation:** bundled issues are extracted into app-private storage once per catalog digest. On the shelf,
  issues merge by stable issue ID: the higher revision wins and a card copy wins a tie. Card packs in `sortie/magazines/`
  are never overwritten or deleted. Reading positions and clippings are keyed by issue/article IDs; clipped text and notes
  stay readable even when an issue is no longer present, for example after installing a build without zines.

### Publishing issue packs

Public zines are taste-led but written for a general audience; they do not assume the reader owns a particular library or
handheld. Keep operational audits and personal data out of published packs. Validate and publish authored issues into the
content repository with:

```sh
python3 tools/publish-magazines.py /path/to/issue-sources --check
python3 tools/publish-magazines.py /path/to/issue-sources --out ../sortie-zines/issues --pin
```

The source directory contains one `<issue-id>/` folder per issue. `--out` is required to publish and must be outside this
repository; the tool never writes into `app/src/main/assets`. Publication stages a complete snapshot including a
reproducible size/SHA-256 `catalog.json`; a failed final swap restores the prior output. `--pin` then writes the new catalog
digest into `app/magazines.lock`. Review and commit both repositories together; do not hand-edit only the published copy.

- Use schema 2, positive `revision`, stable issue/article/reference IDs and portable `games` references. Increment revision
  for a correction; retain the original publication date. Check cross-issue identity and meaningful edition aliases.
- Run the publisher's schema/path/privacy checks. Also review prose and source claims, decode every image and inspect cover
  pixels for private wording. The publisher does not replace editorial review.
- Keep each issue's compact rights notice and `sources.json` credits. Third-party screenshots, box art, manual extracts and
  quotations belong to their rights holders and are not covered by this repository's MIT licence.
- `BundledMagazineAssetsTest` opens every staged article with the app parser when built with `-PsortieMagazinesDir`
  (for example `tools/build.sh -PsortieMagazinesDir=../sortie-zines/issues`); without staged content it is skipped.
- Smoke an installed APK built with the content directory: merged shelf, text/images/sources, all/local game lists, library
  links and Featured in, B hierarchy, page/shoulder navigation, Continue reading after restart, and dual-screen section
  navigation where available. Upgrade with `install -r`; verify user data and card packs stay untouched.

The current content set is Field Notes 001–009, revision 2: 41 articles, 131 images, about 12 MB. A debug APK built with
it was installed in place on an AYN Thor and every article's lead image, prose and offline sources were opened; local
filtering, reciprocal links, paging, section jumps, screen swaps and restart/resume were exercised, and the existing user
data and card packs were unchanged. That pass did not test emulation or install a release APK.

### Publication verification — 2026-10-24

- Built fresh application and content clones with cached dependencies and Gradle `--offline`: style checks, debug/release
  unit tests and both APK variants passed. With content, each variant ran 205 tests with no failures, errors or skips.
  Without content, 204 tests passed and the one bundled-content test was intentionally skipped.
- Inspected both APKs: the bundled builds contain the pinned nine issues, 41 articles and 131 images, and every catalogued
  file matches its recorded size/SHA-256. Default builds contain no magazine assets. Building with content and then without
  it in the same checkout, without cleaning, removed all magazine assets and reproduced the original no-content APK bytes.
- Actual Gradle invocations refused missing directories, an incorrect pin, corrupted files, symlinks, uncatalogued files,
  content inside the app module (including a bind-mount alias), old source-asset folders and an empty property.
- The publishing CLI validated all nine issues/125 portable game identities, reproduced the catalog in an external
  directory, updated the pin explicitly, and refused omitted, in-repository and overlapping output directories.
- Audited reachable Git objects for the known private identifiers and removed paths. Offline credential scans of historical
  text and clean clones produced only reviewed public content/schema hashes and a deterministic test fixture; no credential
  finding remained. This is bounded scanning evidence, not a claim of an exhaustive security audit.

This pass exercised build/publication behavior and APK contents, not a new device installation. The reader smoke above
predates the repository separation and used the same unchanged content. Release APKs from `tools/build.sh` remain unsigned;
the separately verified signed beta candidate and its custody status are documented above.
