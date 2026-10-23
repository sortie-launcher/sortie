# Sortie

A fast, offline, controller-first game launcher for Android handhelds, built for a library that is
curated elsewhere. Sortie renders what the desktop pipeline produces (ES-DE gamelists, artwork,
taste scores) and launches games through user-defined launch profiles. It never scrapes, never
phones home, and never decides for you which emulator is allowed.

Primary target: AYN Thor (Android 13, dual screen). Also meant to run on single-screen handhelds.

- Spec: [docs/SPEC.md](docs/SPEC.md)
- Plan and milestones: [docs/PLAN.md](docs/PLAN.md)
- On-card data formats: [docs/formats/](docs/formats/)

## Status

Pre-alpha. Nothing to install yet.

## Building

Requires JDK 17 and the Android SDK (platform 35). Copy `local.properties.example` to
`local.properties` and point `sdk.dir` at your SDK, then:

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Licence

MIT. See [LICENSE](LICENSE).
