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
# grep -c reads everything: an early-exiting grep -q would fail the pipe under pipefail
second_display() { [ "$(sh_ dumpsys display | grep -c 'uniqueId="virtual:com.android.emulator.multidisplay' || true)" -gt 0 ]; }

[ -f "$APK" ] && [ -f "$TEST_APK" ] || { echo "Missing $APK or $TEST_APK: ./gradlew assembleDebug :smoke:assembleDebug" >&2; exit 2; }
adb wait-for-device
[ "$(sh_ getprop ro.kernel.qemu | tr -d '\r')" = 1 ] || [ "$(sh_ getprop ro.boot.qemu | tr -d '\r')" = 1 ] ||
  { echo "Refusing: $(sh_ getprop ro.product.model | tr -d '\r') is not an emulator; this script overwrites the card and HOME" >&2; exit 2; }
for _ in $(seq 120); do [ "$(sh_ getprop sys.boot_completed | tr -d '\r')" = 1 ] && break; sleep 1; done
mkdir -p "$OUT"
adb logcat -b all -c # Sortie crashes and ANRs below are counted from here

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
# Fresh installs: no data from an earlier run, and no clash with an install signed by another machine's debug key.
for pkg in "$PKG.smoke" "$PKG"; do adb uninstall "$pkg" >/dev/null 2>&1 || true; done
adb install -t "$APK"
adb install -t "$TEST_APK"
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
# The first request can answer "MultiDisplayPipe is not up yet" while it starts the guest's display service; repeating the
# same request is harmless (unchanged parameters are ignored), and so is a display left by an earlier run.
for attempt in 1 2 3; do
  second_display && break
  adb emu multidisplay add 1 1240 1080 369 1739 || true
  for _ in $(seq 15); do second_display && break 2; sleep 1; done
  echo "No second display after attempt $attempt"
done
second_display || { echo "The emulator did not add a second display" >&2; exit 1; }
sh_ dumpsys display | grep -E 'DisplayDeviceInfo\{"' || true

echo "== tests"
start=$(date +%s)
sh_ am instrument -w "$PKG.smoke/androidx.test.runner.AndroidJUnitRunner" | tr -d '\r' | tee "$OUT/instrument.txt"
echo "tests took $(($(date +%s) - start)) s"
grep -q '^OK (' "$OUT/instrument.txt"
# Any Sortie crash or ANR fails the run, even one the tests recovered from (its tasks are restarted as HOME).
if adb logcat -d -b crash,system,main | grep -E "Process: $PKG, PID|ANR in $PKG( |$)"; then
  echo "Sortie crashed or stopped responding during the run" >&2
  exit 1
fi
