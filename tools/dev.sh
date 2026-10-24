#!/usr/bin/env bash
# Build, install and exercise Sortie on a connected device. Usage: tools/dev.sh [build|install|run|shot|all] [serial]
# Never `adb uninstall` on a daily device to test: it also drops the Shizuku grant (re-grant from Diagnostics).
# Device specifics come from the environment, not from this repo: SORTIE_SERIAL, SORTIE_TOP_DISPLAY, SORTIE_BOTTOM_DISPLAY
# (physical display ids from `dumpsys SurfaceFlinger --display-id`).
set -euo pipefail
cd "$(dirname "$0")/.."
cmd=${1:-all}; serial=${2:-${SORTIE_SERIAL:-}}
ADB=(adb); [ -n "$serial" ] && ADB=(adb -s "$serial")
if ! command -v adb >/dev/null && command -v distrobox >/dev/null; then ADB=(distrobox enter ubuntu -- "${ADB[@]}"); fi
build()   { if command -v java >/dev/null; then ./gradlew -q assembleDebug; else distrobox enter ubuntu -- bash -c 'export ANDROID_HOME=$HOME/Android/Sdk; cd "'"$PWD"'" && ./gradlew -q assembleDebug'; fi; ls -la app/build/outputs/apk/debug/app-debug.apk | awk '{print $5" bytes"}'; }
install() {
  "${ADB[@]}" install -r app/build/outputs/apk/debug/app-debug.apk </dev/null | tail -1
  # a reinstall drops the default-home choice; restore it when Sortie was the home app (SORTIE_HOME=1). The appops survive a
  # reinstall but not an uninstall; granting them again is harmless.
  for op in MANAGE_EXTERNAL_STORAGE SYSTEM_ALERT_WINDOW; do "${ADB[@]}" shell appops set io.github.esko997.sortie $op allow </dev/null >/dev/null || true; done
  [ "${SORTIE_HOME:-0}" = 1 ] && "${ADB[@]}" shell cmd package set-home-activity io.github.esko997.sortie/.MainActivity </dev/null >/dev/null || true
}
run()     { "${ADB[@]}" shell am start -n io.github.esko997.sortie/.MainActivity </dev/null >/dev/null; sleep 4; "${ADB[@]}" logcat -d -t 300 </dev/null | grep -E "FATAL|AndroidRuntime.*sortie" | tail -20 || true; }
shot()    { mkdir -p screenshots; "${ADB[@]}" exec-out screencap ${SORTIE_TOP_DISPLAY:+-d $SORTIE_TOP_DISPLAY} -p </dev/null > screenshots/top.png; [ -n "${SORTIE_BOTTOM_DISPLAY:-}" ] && "${ADB[@]}" exec-out screencap -d "$SORTIE_BOTTOM_DISPLAY" -p </dev/null > screenshots/bottom.png; ls -la screenshots/*.png | awk '{print $5, $9}'; }
case $cmd in build) build;; install) install;; run) run;; shot) shot;; all) build; install; run; shot;; *) echo "unknown: $cmd"; exit 2;; esac
