#!/usr/bin/env bash
# Tier 4 smoke tests: prepares a disposable Android 13 emulator as a two-screen Sortie device and runs the instrumented tests in
# smoke/ (a separate test APK, so Sortie itself runs uninstrumented). Build first:
#   ./gradlew assembleDebug :smoke:assembleDebug [-PsortieMagazinesDir=...]
# adb must reach exactly one device or ANDROID_SERIAL must name it. See docs/HARDENING.md, "Testing".
#
# The device is overwritten: /sdcard/sortie and /sdcard/roms are replaced by tools/emulator/card, Sortie becomes HOME and the
# display size changes. It therefore refuses anything that is not an emulator.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.."
PKG=io.github.esko997.sortie
APK=${SORTIE_APK:-app/build/outputs/apk/debug/app-debug.apk}
TEST_APK=${SORTIE_TEST_APK:-smoke/build/outputs/apk/debug/smoke-debug.apk}
OUT=${SORTIE_SMOKE_OUT:-smoke/build/emulator-smoke}
adb() { command adb "$@" </dev/null; }
sh_() { adb shell "$@"; }
displays() { sh_ dumpsys display | grep -cE '^\s+mDisplayId=' || true; }

[ -f "$APK" ] && [ -f "$TEST_APK" ] || { echo "Missing $APK or $TEST_APK: ./gradlew assembleDebug :smoke:assembleDebug" >&2; exit 2; }
adb wait-for-device
[ "$(sh_ getprop ro.kernel.qemu | tr -d '\r')" = 1 ] || [ "$(sh_ getprop ro.boot.qemu | tr -d '\r')" = 1 ] ||
  { echo "Refusing: $(sh_ getprop ro.product.model | tr -d '\r') is not an emulator; this script overwrites the card and HOME" >&2; exit 2; }
for _ in $(seq 120); do [ "$(sh_ getprop sys.boot_completed | tr -d '\r')" = 1 ] && break; sleep 1; done
mkdir -p "$OUT"

collect() {
  sh_ cmd activity stack list >"$OUT/stack.txt" 2>&1 || true
  sh_ dumpsys window displays >"$OUT/window-displays.txt" 2>&1 || true
  sh_ dumpsys display >"$OUT/display.txt" 2>&1 || true
  sh_ dumpsys input >"$OUT/input.txt" 2>&1 || true
  adb exec-out screencap -p >"$OUT/primary.png" 2>/dev/null || true
  adb logcat -d -v threadtime >"$OUT/logcat.txt" 2>&1 || true
}
trap collect EXIT

echo "== primary screen (Thor top: 1920x1080 at 369 dpi, 833x421 dp)"
sh_ wm size 1920x1080
sh_ wm density 369
sh_ svc power stayon true
sh_ settings put system screen_off_timeout 2147483647
sh_ input keyevent KEYCODE_WAKEUP
sh_ wm dismiss-keyguard || true

echo "== install"
adb install -r -t "$APK"
adb install -r -t "$TEST_APK"
for op in MANAGE_EXTERNAL_STORAGE SYSTEM_ALERT_WINDOW; do sh_ appops set "$PKG" "$op" allow; done

echo "== card"
sh_ rm -rf /sdcard/sortie /sdcard/roms
adb push tools/emulator/card/sortie tools/emulator/card/roms /sdcard/ >/dev/null
sh_ ls -R /sdcard/sortie /sdcard/roms

echo "== home"
previous=$(sh_ cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r' | tail -1)
sh_ cmd package set-home-activity "$PKG/.MainActivity"
# The old launcher's tasks would share Sortie's HOME root task (and its visibility) on the primary display.
if [[ $previous == */* && ${previous%%/*} != "$PKG" ]]; then sh_ am force-stop "${previous%%/*}"; fi
sh_ input keyevent KEYCODE_HOME

# Second screen (Thor bottom: 1240x1080, 538x468 dp), added after Sortie is HOME so Android itself starts its SECONDARY_HOME
# there. The emulator's own virtual display (console `multidisplay`) is public and trusted with system decorations;
# 1739 adds FLAG_PRESENTATION, which Sortie looks for, to the emulator's default flags (public, presentation, own content
# only, touch, rotates with content, system decorations, trusted). An overlay display (settings overlay_display_devices)
# would not do: Android leaves overlay displays out of accessibility, so no test could read the second screen.
echo "== second screen"
before=$(displays)
adb emu multidisplay add 1 1240 1080 369 1739
for _ in $(seq 30); do [ "$(displays)" -gt "$before" ] && break; sleep 1; done
[ "$(displays)" -gt "$before" ] || { echo "The emulator did not add a second display" >&2; exit 1; }
sh_ dumpsys display | grep -E 'DisplayDeviceInfo\{"' || true

echo "== tests"
start=$(date +%s)
sh_ am instrument -w "$PKG.smoke/androidx.test.runner.AndroidJUnitRunner" | tr -d '\r' | tee "$OUT/instrument.txt"
echo "tests took $(($(date +%s) - start)) s"
grep -q '^OK (' "$OUT/instrument.txt"
