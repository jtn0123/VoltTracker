#!/usr/bin/env bash
# Emulator runtime smoke: install the debug APK, launch MainActivity, and PROVE the
# dashboard JS actually came alive and keeps working through a few native WebView
# interactions — not just that the process didn't crash.
#
# The positive check is the point: poll logcat for the JS->native handshake
# ("dashboard handshake received", logged by MainActivity.onDashboardReady). If the
# dashboard's script chain is dead (e.g. the file:// ES-module regression) the app
# still *loads* and logs nothing alarming, so only the absence of that line reveals
# it. A negative scan then rejects any crash or uncaught JS error. After the
# handshake, the script starts demo telemetry through the native service action,
# taps through the real bottom-nav WebView surfaces, and captures screenshots for
# review. That gives CI proof that native -> WebView broadcasts and real Android
# rendering survived startup.
#
# Run by .github/workflows/android-emulator-smoke.yml inside a booted emulator (the
# android-emulator-runner action executes the workflow `script:` line-by-line, hence
# this lives in a file). adb is on PATH in that context.
#
# ⚠️ TEST CONTRACT — the logcat string "dashboard handshake received" below is the
# entire positive signal of this smoke. It is emitted by
# MainActivity.onDashboardReady (Log.i(TAG, "dashboard handshake received: JS is
# live")). The grep here matches the "dashboard handshake received" prefix. If that
# log line is renamed, reworded, or removed, this smoke will go PERMANENTLY GREEN
# while testing nothing — the dead-JS regression it exists to catch would sail
# through. DO NOT change the string on either side without updating the other in the
# same commit. See mobile/android/docs/data-model.md ("Test contract") for the
# rationale.
set -euo pipefail

# Resolve to mobile/android regardless of caller cwd (scripts/ -> mobile/android).
cd "$(dirname "$0")/.."

PKG="com.volttracker.obdpoc"
MAIN_ACTIVITY="$PKG/.MainActivity"
OBD_SERVICE="$PKG/.ObdService"
ACTION_DEMO="$PKG.action.DEMO"
ACTION_DISCONNECT="$PKG.action.DISCONNECT"
ARTIFACT_DIR="build/emulator-smoke"
SCREENSHOT_DIR="$ARTIFACT_DIR/screenshots"
LOGCAT="build/emulator-smoke-logcat.txt"
NAV_COUNT=7
# Vertical geometry of the floating bottom-nav pill (css/screens.css). It renders with
# `bottom: max(10px, env(safe-area-inset-bottom) + 10px)` and a ~58 px-tall rail
# (button `min-height: 54px` + 6px padding), so the rail's vertical CENTER sits only
# ~39 CSS px above the screen bottom (= 10 inset + 29 half-rail). The tap Y below is
# `height - (10 + 29) * css_scale`, which lands dead-center on the pill.
#   ⚠️ These were 64 + 36 = 100, calibrated for the OLD full-height bottom bar. After
#   the dashboard refactor that tapped ~90 px ABOVE the pill, so every nav tap missed
#   and the first asserted tap ("trips") failed with "screenshot did not change."
#   Verified against the CI pixel_6 emulator (1080x2400): nav center ≈ physical y 2283,
#   and `height - 39 * css_scale` = 2400 - 117 = 2283. Keep these in sync with the CSS.
NAV_BOTTOM_INSET_PX=10
NAV_RAIL_HALF_PX=29
PREVIOUS_NAV_SCREENSHOT=""

rm -rf "$ARTIFACT_DIR"
mkdir -p "$SCREENSHOT_DIR" build

capture_logcat() {
  adb logcat -d -v time >"$LOGCAT"
}

check_logcat() {
  local label="$1"
  capture_logcat
  if grep -E "Process: $PKG|Unable to start activity ComponentInfo\\{$PKG|chromium.*Uncaught|dashboard console:.*(TypeError|ReferenceError|SyntaxError|Unhandled|Uncaught)" "$LOGCAT"; then
    echo "Emulator smoke found an exception in logcat during: $label"
    exit 1
  fi
}

screenshot() {
  local label="$1"
  # Keep screenshots as advisory artifacts. A capture failure should not mask the
  # stronger logcat/handshake assertions this smoke is built around.
  adb exec-out screencap -p >"$SCREENSHOT_DIR/$label.png" || true
}

grant_runtime_permission() {
  local permission="$1"
  adb shell pm grant "$PKG" "$permission" >/dev/null 2>&1 || true
}

grant_runtime_permissions() {
  grant_runtime_permission "android.permission.BLUETOOTH_CONNECT"
  grant_runtime_permission "android.permission.BLUETOOTH_SCAN"
  grant_runtime_permission "android.permission.ACCESS_FINE_LOCATION"
  grant_runtime_permission "android.permission.ACCESS_COARSE_LOCATION"
  grant_runtime_permission "android.permission.POST_NOTIFICATIONS"
}

screen_size() {
  adb shell wm size \
    | tr -d '\r' \
    | sed -n 's/.*: \([0-9][0-9]*\)x\([0-9][0-9]*\).*/\1 \2/p' \
    | tail -1
}

tap_bottom_nav() {
  local index="$1"
  local label="$2"
  local width="$3"
  local height="$4"
  local expect_change="${5:-1}"
  local shot="$SCREENSHOT_DIR/nav-$index-$label.png"
  # X splits the FULL screen width into NAV_COUNT slots. The pill caps at
  # min(760px, 100vw-24px) and centers, so on a phone profile (CI default: pixel_6,
  # near-full-width pill) each slot center is well within its button. On a wide/tablet/
  # landscape profile (CSS width > ~784px) the pill is narrower than the screen and the
  # outer tabs' slot centers would fall outside it — derive X from the centered pill box
  # (left = (width - nav_w)/2; x = left + nav_w*(2*index+1)/(NAV_COUNT*2)) before running
  # this smoke on anything but a phone profile.
  local x=$((width * (index * 2 + 1) / (NAV_COUNT * 2)))
  local css_scale=$(((width + 359) / 360))
  local y=$((height - (NAV_BOTTOM_INSET_PX + NAV_RAIL_HALF_PX) * css_scale))
  echo "Navigating to $label via bottom-nav tap at ${x},${y}."
  adb shell input tap "$x" "$y"
  sleep 1
  screenshot "nav-$index-$label"
  if [ "$expect_change" = "1" ] && [ -n "$PREVIOUS_NAV_SCREENSHOT" ] && cmp -s "$PREVIOUS_NAV_SCREENSHOT" "$shot"; then
    echo "Dashboard screenshot did not change after tapping bottom-nav $label."
    echo "This usually means the tap target missed the WebView nav or the view did not switch."
    exit 1
  fi
  PREVIOUS_NAV_SCREENSHOT="$shot"
  check_logcat "bottom-nav $label"
}

start_service_as_app() {
  local action="$1"
  if adb shell run-as "$PKG" am start-foreground-service --user 0 -n "$OBD_SERVICE" -a "$action" >/dev/null 2>&1; then
    return 0
  fi
  adb shell run-as "$PKG" am startservice --user 0 -n "$OBD_SERVICE" -a "$action" >/dev/null
}

stop_service_as_app() {
  adb shell run-as "$PKG" am startservice --user 0 -n "$OBD_SERVICE" -a "$ACTION_DISCONNECT" >/dev/null 2>&1 || true
}

wait_for_demo_telemetry() {
  for _ in $(seq 1 12); do
    if adb shell run-as "$PKG" grep -R source files/obd-logs 2>/dev/null \
      | grep -q '"source":"demo"'; then
      return 0
    fi
    sleep 1
  done
  echo "Demo telemetry never reached the app's private OBD log."
  echo "This usually means the smoke did not really start the non-exported service as the app UID."
  return 1
}

adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
adb shell run-as "$PKG" rm -rf files/obd-logs >/dev/null 2>&1 || true
grant_runtime_permissions
adb logcat -c
adb shell am start -n "$MAIN_ACTIVITY"

ready=0
for _ in $(seq 1 20); do
  capture_logcat
  if grep -q "dashboard handshake received" "$LOGCAT"; then
    ready=1
    break
  fi
  sleep 2
done

if [ "$ready" -ne 1 ]; then
  echo "Dashboard never signaled ready: its JS handshake did not reach native in time."
  echo "This is the signature of dead dashboard JS (scripts not executing on-device)."
  exit 1
fi
echo "Dashboard handshake received — JS is live."

sleep 2
screenshot "startup-ready"
check_logcat "startup ready"

echo "Starting demo telemetry through the native service action."
start_service_as_app "$ACTION_DEMO"
wait_for_demo_telemetry
screenshot "demo-started"
check_logcat "demo telemetry"
stop_service_as_app
sleep 1
check_logcat "demo disconnect before navigation"

size="$(screen_size)"
if [ -z "$size" ]; then
  echo "Could not read emulator screen size; using Pixel-profile fallback."
  size="1080 2400"
fi
read -r width height <<<"$size"

tap_bottom_nav 0 drive "$width" "$height" 0
tap_bottom_nav 1 trips "$width" "$height"
tap_bottom_nav 2 map "$width" "$height"
tap_bottom_nav 3 charge "$width" "$height"
tap_bottom_nav 4 insights "$width" "$height"
tap_bottom_nav 5 settings "$width" "$height"
tap_bottom_nav 6 signals "$width" "$height"

stop_service_as_app
sleep 1
check_logcat "disconnect cleanup"

echo "Emulator runtime smoke passed. Screenshots: $SCREENSHOT_DIR"
