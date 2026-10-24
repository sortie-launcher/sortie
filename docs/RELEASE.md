# Signed APK releases

Sortie is distributed as a signed APK sent directly to a tester or attached to a GitHub release. There is no Google Play
listing, app store account, update server, automatic updater, telemetry or download service. The goal, acceptance scenarios
and current evidence for the first beta are in [BETA.md](BETA.md); this file is the maintainer's procedure.

## One permanent release key

Android only installs an update over an existing app when both are signed by the **same certificate**. The release key is
therefore permanent for the app id `io.github.esko997.sortie`: losing it means every installed copy can never be updated
again; leaking it means anyone can sign an "update" that Android accepts.

- Create it once with `tools/init-release-key.sh [directory]`. The default directory is
  `${XDG_DATA_HOME:-~/.local/share}/sortie/signing` (or `$SORTIE_SIGNING_DIR`). It must be outside the repository and
  owner-only. The script writes `sortie-release.p12` (PKCS12, RSA 3072, alias `sortie-release`) and `store-password`
  (random; also the key password), prints the certificate SHA-256, and refuses to overwrite or rotate an existing key.
- **Back up both files together**, offline and encrypted (for example an encrypted USB drive kept away from the build
  machine). The keystore without its password file is useless, and so is the password without the keystore.
  Test the backup by reading it back (`keytool -list` against the copy) before relying on it.
- Never commit, upload, paste or send either file. `.gitignore` excludes `*.p12`, `*.jks`, `*.keystore` and `dist/`,
  but the real protection is keeping the files outside the repository.
- Do not rotate the key during the beta. A new key means a different app as far as installed copies are concerned.
- The certificate SHA-256 fingerprint is public information. Record it with the first release so later builds can be
  compared against it.

**Custody status (2026-10-24):** the release key has been created at the default location on the maintainer's build machine.
**No off-machine backup has been made yet.** Make and verify the backup before the first APK leaves the machine.

## Versions

- `versionCode` must increase for every APK that leaves the machine and is never reused. Android refuses to install an
  update with a lower `versionCode` than the installed one.
- `versionName` is the human version, `X.Y.Z` or `X.Y.Z-pre` (for example `0.1.0-beta.2`).
- The defaults live in `app/build.gradle.kts` (currently code `5`, name `0.1.0-beta.4`). Override per build with
  `-PsortieVersionCode=N -PsortieVersionName=X.Y.Z-beta.N`; Gradle rejects malformed values. Bump the defaults when a
  version is released so an ordinary build cannot accidentally reuse a released number.
- Add a `CHANGELOG.md` section for each distributed version.

## Building a release

```
tools/build.sh                 # style check, debug + release unit tests, debug APK and an UNSIGNED release APK
tools/release.sh -PsortieVersionCode=6 -PsortieVersionName=0.1.0-beta.5
```

`tools/release.sh` reads the key from the default signing directory (or `SORTIE_SIGNING_DIR`, or all three of
`SORTIE_RELEASE_KEYSTORE`, `SORTIE_RELEASE_KEYSTORE_PASSWORD_FILE`, `SORTIE_RELEASE_KEY_ALIAS`). It:

- refuses signing files that are missing, inside the repository or readable by other users;
- builds `assembleRelease` without the Gradle daemon, configuration cache or build cache;
- fails instead of falling back to the debug key, and fails if signing is only partly configured;
- verifies the APK has exactly one v2 signer whose certificate matches the release key, the expected package, a valid
  version and no `debuggable` flag, and that the password was not written into build outputs or daemon logs;
- writes `dist/sortie-<versionName>-code<versionCode>.apk` and its `.sha256`, and refuses to replace an existing file of the
  same name with different contents.

It does not format code, upload, tag or publish anything. Publishing is a separate manual step.
Without signing variables `assembleRelease` (including `tools/build.sh` and CI) produces an **unsigned** APK. That is build
evidence only; Android will not install it, and it must not be distributed.

## Distributing

1. Make sure the key backup exists (see above) and `tools/build.sh` passes.
2. Run `tools/release.sh` with a new `versionCode` and `versionName`.
3. Install the APK on an isolated test device (below) over the previous release and check that data survived.
4. Send the APK with its SHA-256 and the changelog section, or attach them to a GitHub release. Name the version in the
   message so the tester can compare it with Android's app info.

Tester instructions: download the APK, check the SHA-256 if you can, open it and allow your browser or file manager to
install unknown apps when Android asks. For an update, open the new APK and choose **Update**; never uninstall first,
because uninstalling removes Sortie's Android-owned and app-private data (see the manual's *Backup and restore*).
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

## Release gates for the beta

Implemented in source on `beta/0.1` (acceptance evidence is tracked in [BETA.md](BETA.md), not here):

- On-device library setup and maintenance (Home → Library setup, Settings → Library and emulators): multiple systems and folders, built-in emulator
  presets with plain prerequisites, BIOS/save folders, re-index, safe removal, existing desktop configurations preserved.
- Missing or unreadable storage keeps the previous index and the remembered settings location; failed launches show a message.
- Plain-language All files access screen with fallbacks; overlay/Shizuku/Home role/radial keyboard stay optional.
- Persistent release key tooling, versioning and verified signed APK packaging.

Acceptance and remaining distribution gates:

- [ ] Off-machine, tested backup of the release keystore **and** password file.
- [x] Signed beta.1/code2 updated in place to beta.2/code3 on isolated devices. Eleven durable files were byte-identical
  before reopening (settings, notes, Room database/WAL, completed history and magazine positions); file permission and
  running emulator processes were retained. SQLite's ephemeral shared-memory lock file changed normally.
- [x] A source-blind newcomer configured PSP and Game Boy, added another root, visibly launched both games, saved personal
  state and resumed reading after process restart. Reported usability findings were incorporated.
- [ ] Broader launch-and-return coverage. PPSSPP 1.20.4 ISO and RetroArch 1.22.2/Gambatte are exercised on the API33 AVD;
  see [LAUNCHERS.md](LAUNCHERS.md) for warm-launch behavior and limitations. Other presets retain their explicit untested
  status; a present package does not prove its core, BIOS or game is ready.
- [ ] A rights review of bundled third-party media before any public upload (see the README's *Licence*), including
  `field-notes-009/media/windjammers-disc-duel-beach.webp`, whose upstream source is unknown.
- [ ] Hardware coverage beyond the devices actually tested: Android versions, single/dual screen, touch/controller,
  storage layouts and OEM settings screens. Existing Thor/DMG/emulator evidence is not a general compatibility claim.

Later, for a wider public release:

- Review the published tree and history for personal paths, device identifiers and private content.
- Audit dependency licences and provide notices.
- Test exported components, URI grants, malformed manifests/packs and path containment; release builds must not expose
  debug launch hooks.
- Signed CI automation only after key custody and repository ownership are settled; never expose signing secrets to
  untrusted pull requests.
- App stores are a separate decision: broad file access and installed-app visibility need store-policy review.

Keep minification disabled until release-variant device tests cover serialization, Room, reflection and the Shizuku service.

## Publishing bundled magazine issues

This is content publication, separate from release signing or the setup work above. Public zines are taste-led but written
for a general audience; they do not assume the reader owns a particular library or handheld. Include games outside the
local collection. Keep operational audits and personal data outside published packs.

From the repository root, validate and publish an explicitly chosen authoring directory:

```sh
python3 tools/publish-magazines.py /path/to/issue-sources --check
python3 tools/publish-magazines.py /path/to/issue-sources
```

The directory contains one `<issue-id>/` folder per issue. In the author's workspace it is `../guide/magazines`, but ordinary
builds never read that location. Publication stages a complete snapshot into `app/src/main/assets/magazines/`, including a
reproducible size/SHA-256 catalog. A failed final swap restores the prior output. Review the asset changes with the source
changes; do not hand-edit only the packaged copy.

- Use schema 2, positive `revision`, stable issue/article/reference IDs and portable `games` references. Increment revision
  for a correction; retain the original publication date. Check cross-issue identity and meaningful edition aliases.
- Run the publisher's schema/path/privacy checks. Also review prose and source claims, decode every image, inspect cover
  pixels for private wording, and verify provenance. The publisher does not replace editorial review or pixel inspection.
- Keep a compact rights notice and source credits. Third-party images/manual extracts are not MIT; attribution and
  non-commercial distribution are not grants of permission. The earlier zine pass did not perform separate clearance; review the distribution position before public upload.
- `verifyMagazineAssets` runs before builds and rejects files differing from the catalog. Build from the committed assets
  without network fetching or private paths. `BundledMagazineAssetsTest` also opens every bundled article with the app parser.
- Smoke the installed APK: merged shelf, public text/images/sources, all/local game lists, library links and Featured in,
  B hierarchy, page/shoulder navigation, Continue reading after restart, and dual-screen X/section navigation where available.
- Upgrade existing installations with `install -r`, never uninstall a daily handheld. Verify user data and card packs stay
  untouched. Higher revision wins, card wins a tie, and malformed card copies cannot hide valid bundled editions.

The initial public bundle is Field Notes 001–009, revision 2: 41 articles, 131 images, about 12 MB. The release-signing and
friend-readiness gates above remain separate; a tested debug APK is not a signed public release.

Evidence recorded by the separate magazine integration session on 2026-10-24 (not re-run by the beta integrator on the Thor): `tools/build.sh --no-daemon --max-workers=2` passed style checks,
152 JVM tests per variant, and both APK builds. Both APKs contain the same verified nine-issue catalog. On the AYN Thor,
the debug APK was installed with `-r`; all 41 articles' lead images, prose and offline Sources were opened. Local filtering,
reciprocal links/back focus, paging, article boundaries, section jumps, X swaps and restart/resume were exercised.
Original reading preferences were restored after testing; `user.json` and all nine original card manifests remained
byte-identical. This did not test game emulation or install the unsigned release APK.
