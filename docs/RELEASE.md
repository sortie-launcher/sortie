# Friend-ready preview and public release

## Goal and release gate

A new user can install Sortie, select their own games, configure a supported installed emulator, launch a game and return
without editing JSON, using ADB, or depending on the author's desktop ROM pipeline. Updating Sortie must preserve their data.
The first audience is one nontechnical friend, not an app-store launch. Feature breadth is not the release gate.

**Current status (2026-10-24): not yet friend-ready.** The launcher has substantial device testing, but a blank installation
still expects externally authored manifests. Release signing and a safe distribution/update path are not configured.
The wizard described below is planned work, not a shipped feature.

## Stage 1 — first friend preview (required)

### 1. Guided setup and repair

Prefer an **in-app setup wizard**, not a second desktop installer. Keep the existing manifest format as the launch/indexing
contract rather than introducing a competing configuration system.

- [ ] Explain storage access, then choose internal storage or an SD card/library location. Support denial and cancellation.
- [ ] Detect an existing Sortie configuration and offer to use it. Never overwrite a desktop-managed card or user data silently.
- [ ] Select ROM directories, suggest systems from known folder names/extensions, and ask the user to confirm ambiguous matches.
- [ ] Select an installed emulator per supported system; explain missing emulators and required emulator-side setup.
- [ ] Generate a valid configuration and show scan progress, game counts and actionable errors. Artwork/guide data is optional.
- [ ] Offer a real **Test launch** and verify the return-to-Sortie path before calling setup complete.
- [ ] Offer default-Home selection only after a usable library exists; retain a clear route to Android's launcher settings.
- [ ] Make setup available again from Settings: repair moved/missing folders, change emulators, rescan and cancel safely.

Start with a small, explicitly verified emulator/profile set. The existing evidence in `LAUNCHERS.md` is device/version
specific, not a promise that every fork or current release behaves identically. Advanced custom profiles remain available.
Selecting a directory does not configure the emulator's own storage grant, cores or BIOS: those prerequisites need honest
instructions and launch diagnostics, not a fake success indicator. Do not download or bundle ROMs, BIOS, firmware or keys.

Implementation constraints confirmed by source review:

- `Indexer.indexSystem` accepts absolute or card-relative roots, scans immediate files only, and identifies games by
  `system-id/filename`. Duplicate filenames across roots collapse to one entry; do not silently promise recursive discovery
  or distinct copies. Keep ids stable and force a rescan when a root changes: the incremental fast path compares size/mtime,
  not the stored game path.
- `ManifestStore.watch()` does not attach while its directory is absent. A first-run writer must explicitly create/load/watch
  the completed configuration; merely writing files is insufficient. Stage and validate the complete set before publishing,
  so observers/indexing cannot act on half-written setup. Existing empty or malformed files are not permission to overwrite.
- `SortieApp.Graph` binds stores to one configuration directory for the process lifetime. Library selection needs an explicit,
  persisted location/rebind design; the existing volume scan is not an arbitrary-directory preference.
- Missing storage has a gap: the top-level unreadable-root guard preserves the index, but a missing secondary system root is
  skipped before the unreadable flag is set, allowing its games to become stale. Reproduce and fix that case before exposing
  arbitrary/multiple-volume selection. Do not describe all card-removal paths as already safe.
- `SortieViewModel.launch()` currently discards `LaunchManager.launch()`'s failure result. Setup and ordinary launching need
  visible, actionable failure feedback; a diagnostic log alone is not sufficient for a nontechnical user.

**Acceptance:** from a fresh app on a spare device/emulator, install an APK and configure a lawful local test game using only
the touchscreen/controller. Launch it, return, reboot and launch again. Repeat with storage permission denied, no emulator,
an empty folder, a missing card and an existing desktop-managed configuration. No existing library or personal data is lost.
Do not uninstall the daily Thor to simulate a fresh install.

### 2. Stable identity, signing and updates

- [ ] Confirm the distribution application id before the first outside installation. Keep `io.github.esko997.sortie` unless
  there is a deliberate migration decision; do not rename it casually after users have data.
- [ ] Create and securely back up an owner-controlled release signing key outside the repository. Configure release signing
  through private environment/CI secrets; never distribute the key or use a transient developer debug key for friend releases.
- [ ] Define monotonic `versionCode` values and human-readable preview versions; publish changelog entries for each build.
- [ ] Verify an in-place update between two builds signed by the same release key, preserving settings, sessions, notes,
  media, magazine reading state, shortcut ownership and Android grants.
- [ ] Provide a trusted APK download, version and checksum, plus short sideload/update instructions.

A different signing certificate cannot replace an existing installation with the same app id. Current daily debug installs
need a separately planned transition; **uninstalling them is not an acceptable migration shortcut**. JSON backups do not
restore Android shortcut pins/grants or every app-private preference. Do not rotate signing keys mid-preview.
Distribution signing must fail clearly when credentials are missing; never silently fall back to a debug certificate.
An unsigned CI build is useful verification, but must remain visibly labelled and separate from distribution artifacts.

Keep minification disabled initially. It is an optional later optimization, not a public-release requirement; enable it only
with release-variant device tests covering serialization, Room, reflection and the Shizuku service.

### 3. Optional capabilities and safe defaults

- [ ] Ordinary browsing/launching works without Shizuku, overlay access, default-Home status or the radial keyboard.
- [ ] Explain each optional permission at the feature that needs it. Handle revoked grants and Shizuku stopping/rebooting.
- [ ] Replace the first-run ADB instructions with plain-language guidance and handle an unavailable OEM permission-settings
  activity. Connect the overlay setting to Android's permission screen instead of only saving the preference.
- [ ] Single-screen, touch-only and controller navigation remain usable. In particular, `DiagnosticsScreen` still passes
  literal display ids `0` and `4` to `moveTopApp`; use discovered displays and hide actions that cannot be performed.
- [ ] Explain limitations of task moving/switching on unsupported firmware; do not equate missing capability with app failure.
- [ ] Verify that setup, upgrades and normal use never silently change another launcher's settings or close unrelated tasks.

### 4. Data safety and recovery

- [ ] Complete a full export/restore round trip on a spare installation, including the new personal-data features.
- [ ] Provide an understandable attachment-preservation workflow. Current JSON exports include reference/journal metadata,
  **not** `reference/` images or `journal/media/` screenshots; a nontechnical user must not mistake that for a complete backup.
- [ ] Document what stays Android-owned or app-private, including shortcut pins/grants and magazine reading positions.
- [ ] Exercise full/unwritable storage, card removal/reinsertion and interrupted writes without erasing a previously indexed
  library, replacing valid settings with defaults or claiming an unsuccessful save succeeded.
- [ ] Preserve stable game ids when changing configured paths, or show and implement an explicit migration of personal data.

### 5. Supportable friend handoff

- [ ] A short first-run guide: install, grant storage, add one system, test launch, optional Home role, update and recover.
- [ ] An honest compatibility list for the friend's actual device, Android version and emulator versions.
- [ ] Errors explain what to do next: missing emulator/core/BIOS, unreadable folder, invalid config or unsupported launch.
- [ ] A user-initiated diagnostic export that previews/redacts personal paths, filenames, notes and launch arguments.
  Existing Diagnostics/logcat output is useful to developers but is not automatically safe to post publicly.
- [ ] Run one observed setup session with the friend. Record where instructions are needed; fix that friction before adding
  new launchers or distribution channels. Collect feedback without adding telemetry by default.

## Stage 2 — wider public release

- [ ] Review the complete publishable tree and history for personal paths, device identifiers, secrets and private content.
  Ship clean examples rather than the author's real card manifests or backups.
- [ ] Audit dependency licences and provide notices. Separately review system logos, cover art, magazine pictures and article
  sources; an MIT code licence does not grant redistribution rights to those assets. Keep optional content packs separate.
- [ ] Test exported activities/providers/services, URI grants, externally supplied intents, malformed packs/manifests and
  path containment. Release builds must not expose debug launch hooks or be debuggable.
- [ ] Expand the device matrix: supported Android versions, single/dual-screen, controller/touch, font scaling, different
  storage layouts and permission denial. Existing Thor/DMG evidence is not an all-Android compatibility claim.
- [ ] Add signed release automation only after key custody and repository ownership are settled; protect signing secrets from
  untrusted pull requests. Publishing is a separate explicit operation, not part of a normal local build.
- [ ] Establish issue templates, supported-version policy, release notes and an offline/privacy statement.
- [ ] Consider Play Store or other stores separately: broad file access and installed-app visibility need distribution-policy
  review. A direct friend APK does not require solving store approval or an in-app updater first.

## Build verification and evidence

`./tools/build.sh` now checks style, runs JVM tests for **debug and release**, and builds both APK variants. It does not format
source, hide build output or flatten forwarded arguments into shell code. Run formatting explicitly when desired.
CI runs the same task set and keeps the debug APK and **unsigned** release APK as distinct artifacts. An unsigned artifact
is compile/package evidence only: it is not an installable friend release and the workflow does not sign or publish it.

Verified in the isolated current-source snapshot on 2026-10-24:

- [x] Actual `tools/build.sh` completed: debug and unsigned release APKs built; ktlint passed.
- [x] 130 JVM tests passed for each build variant (zero failures, errors or skips).
- [x] APK inspection reports release `debuggable=false`; signature verification confirms it is currently unsigned.
- [x] A deliberate nonexistent Gradle task returns a nonzero helper exit status with the full error visible.
- [x] Building leaves application source unchanged; there is no implicit formatter competing with another agent's edits.
- [ ] Run the workflow on GitHub after a remote exists. Local success is not a claim that hosted CI has run.
- [ ] Exercise a genuinely release-signed APK on a spare device. No release APK was installed on a daily handheld in this pass.
