#!/usr/bin/env bash
# Non-mutating debug/release builds, style checks and unit tests. Formatting is an explicit developer action.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
tasks=(--console=plain ktlintCheck testDebugUnitTest testReleaseUnitTest assembleDebug assembleRelease)
if command -v java >/dev/null 2>&1; then
  exec ./gradlew "${tasks[@]}" "$@"
fi
if ! command -v distrobox >/dev/null 2>&1; then
  printf '%s\n' 'Install JDK 17 and the Android SDK, or provide the Ubuntu distrobox used by this helper.' >&2
  exit 127
fi
exec distrobox enter ubuntu -- bash -c \
  'export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"; cd -- "$1"; shift; exec ./gradlew "$@"' \
  sortie-build "$PWD" "${tasks[@]}" "$@"
