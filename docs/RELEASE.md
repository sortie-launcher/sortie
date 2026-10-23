# Release checklist (for the day Sortie goes public)

Nothing here is needed while Sortie is a one-person project; it exists so the code does not box that day in.

1. **Package id cut-over.** The app id is `io.github.esko997.sortie` (GitHub convention, no domain). Before any build reaches
   another person, decide the final id (own domain reversed, or keep this one) and change `namespace`/`applicationId` in
   `app/build.gradle.kts` plus the FileProvider authority suffix (`${applicationId}.files` follows automatically). Installed
   data does not survive an id change; that is why this happens before, not after, the first outside user.
2. **Signing.** Create a release keystore outside the repo, wire `signingConfigs.release` through environment variables or
   `local.properties` (both gitignored), and set `isMinifyEnabled = true` with tested ProGuard rules for Room, kotlinx.serialization
   and Coil.
3. **Device knowledge stays out of the APK.** Grep the sources for `/storage/`, serials, `com.retroarch` and emulator package names
   before tagging: all of that belongs in the on-card manifests (`sortie-card/` on the desktop) and docs only.
4. **Docs.** README install steps (all-files access grant, where to put `sortie/*.json`), the formats in `docs/formats`, and
   `docs/LAUNCHERS.md` as the verified-profile reference. Replace the Thor-specific examples with placeholders.
5. **CI.** `.github/workflows/build.yml` already builds, lints and tests on every push; add a tag-triggered job that signs and
   attaches the APK to a GitHub release.
6. **Versioning.** Bump `versionCode`/`versionName`, move CHANGELOG "Unreleased" under the version, tag `vX.Y.Z`.
