#!/usr/bin/env bash
# Build + lint + unit tests inside the Ubuntu distrobox when the host has no JDK. Exit code is the Gradle exit code.
cd "$(dirname "$0")/.."
if command -v java >/dev/null; then ./gradlew -q ktlintFormat assembleDebug ktlintCheck testDebugUnitTest "$@"; else
  distrobox enter ubuntu -- bash -c 'export ANDROID_HOME=$HOME/Android/Sdk; cd "'"$PWD"'" && ./gradlew -q ktlintFormat assembleDebug ktlintCheck testDebugUnitTest '"$*"' 2>&1 | sed "s/\x1b\[[0-9;]*m//g" | grep -E "^e:|Execution failed|\.kt:[0-9]+:[0-9]+:|FAILED" | head -8; exit ${PIPESTATUS[0]}'
fi
