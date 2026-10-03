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
# live")), whose prefix is the source-of-truth constant MainActivity.DASHBOARD_READY_LOG.
# The grep here must match that constant's value. If that log line is renamed,
# reworded, or removed, this smoke will go PERMANENTLY GREEN while testing nothing —
# the dead-JS regression it exists to catch would sail through. EmulatorSmokeContractTest
# asserts this script references MainActivity.DASHBOARD_READY_LOG so a rename on either
# side fails a fast unit test. DO NOT change the string on either side without updating
# the other in the same commit. See mobile/android/docs/data-model.md ("Test contract")
# for the rationale.
#
# COMPOSE PHASE (runs first — ComposeDashboardActivity is the app's launcher). It opens every
# Compose tab and the Health/Settings routes by intent extras (DebugLaunch, the same ones
# scripts/local-emulator.sh uses) instead of coordinate taps, and requires two debug-build
# VoltStartup marks: StartupTrace.COMPOSE_FIRST_TELEMETRY ("compose_first_telemetry" — demo
# telemetry reached the Compose screen's live store) and StartupTrace.COMPOSE_SCREEN
# ("compose_screen:<view>" — that screen composed). Same contract rule as the handshake:
# EmulatorSmokeContractTest pins these strings on both sides.
set -euo pipefail

# Resolve to mobile/android regardless of caller cwd (scripts/ -> mobile/android).
cd "$(dirname "$0")/.."

# Debug builds install as their own app ID (applicationIdSuffix ".debug"); class names and
# intent actions keep the source package, so they are spelled out in full against CODE_PKG.
PKG="com.volttracker.obdpoc.debug"
CODE_PKG="com.volttracker.obdpoc"
# Debug-only exported alias for the non-exported classic dashboard activity —
# see app/src/debug/AndroidManifest.xml. The smoke installs the debug APK, so
# the alias is always present here.
MAIN_ACTIVITY="$PKG/$CODE_PKG.DebugClassicDashboard"
# The launcher itself (exported). Debug builds read DebugLaunch's vt.* extras from this intent.
COMPOSE_ACTIVITY="$PKG/$CODE_PKG.ComposeDashboardActivity"
# Values of StartupTrace.COMPOSE_SCREEN and StartupTrace.COMPOSE_FIRST_TELEMETRY.
COMPOSE_SCREEN_MARK="compose_screen"
COMPOSE_TELEMETRY_MARK="compose_first_telemetry"
# Full component path: ObdService moved to the service/ subpackage in the A3 restructure.
OBD_SERVICE="$PKG/$CODE_PKG.service.ObdService"
ACTION_DEMO="$CODE_PKG.action.DEMO"
ACTION_DISCONNECT="$CODE_PKG.action.DISCONNECT"
ARTIFACT_DIR="build/emulator-smoke"
SCREENSHOT_DIR="$ARTIFACT_DIR/screenshots"
LOGCAT="build/emulator-smoke-logcat.txt"
# Buttons in the classic bottom nav (data-nav in dashboard-src/index.template.html). This still
# said 7 long after the nav shrank to 6, which put the 4th tap on the Charge/Insights boundary.
NAV_COUNT=6
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

# Negative logcat scan: fail the smoke if any of these failure classes appeared.
# One grep alternative per class:
#   "Process: $PKG"
#       AndroidRuntime fatal-crash header ("Process: <pkg>, PID: ..."): any uncaught
#       native/JVM exception that killed the app process.
#   "Unable to start activity ComponentInfo{$PKG"
#       ActivityManager's launch-failure line: MainActivity threw during
#       onCreate/onStart/onResume, so the app never reached a usable state.
#   "chromium.*Uncaught"
#       The WebView's chromium console tag relaying an uncaught JS exception —
#       dashboard script crashed outside any try/catch.
#   "dashboard console:.*(TypeError|ReferenceError|SyntaxError|Unhandled|Uncaught)"
#       WebViewBootstrap.onConsoleMessage mirrors every dashboard console message to
#       logcat with the "dashboard console:" prefix; this catches JS errors that only
#       surface as console output (e.g. unhandled promise rejections the dashboard's
#       error hook logs itself).
# Known looseness, kept deliberately: the unescaped dots in $PKG match any character,
# and "Process: $PKG" also matches non-fatal lines that happen to contain that text.
# Both err toward a false positive (investigated via the uploaded logcat artifact)
# rather than a missed crash — do not tighten in a way that could skip a real failure.
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
  local css_scale=$(((width + 359) / 360))
  # Match `.bottom-nav { width: min(760px, calc(100vw - 24px)); left: 50%;
  # transform: translateX(-50%) }` in physical pixels. The old smoke split the
  # whole screen into seven equal slots, which works on phone-sized emulators but
  # misses the centered pill on wider profiles.
  local nav_width=$((width - 24 * css_scale))
  local nav_max=$((760 * css_scale))
  if [ "$nav_width" -gt "$nav_max" ]; then
    nav_width="$nav_max"
  fi
  local nav_left=$(((width - nav_width) / 2))
  local x=$((nav_left + nav_width * (index * 2 + 1) / (NAV_COUNT * 2)))
  local y=$((height - (NAV_BOTTOM_INSET_PX + NAV_RAIL_HALF_PX) * css_scale))
  echo "Navigating to $label via bottom-nav tap at ${x},${y}."
  # Retry the tap if the view doesn't change. A single adb `input tap` is
  # occasionally dropped on an adb-flaky emulator boot (observed: the identical
  # emulator profile passing all 7 taps in one run while missing one tap in a
  # sibling run whose boot logged repeated adb-daemon connection errors). A lone
  # dropped tap would falsely fail this smoke. Re-tapping the same nav button is
  # idempotent — you stay on that view — so retries can NOT mask a real problem:
  # a genuine geometry/selector break never changes the screen and still fails
  # after every attempt.
  # Detect the view switch by POLLING for the screen to change, rather than sleeping a fixed
  # interval and capturing exactly once. On a slow/loaded CI emulator the WebView re-render lags
  # the tap by many seconds, so a single post-sleep capture often photographs the OLD frame and
  # reports a false "no view change" — the dominant flake this smoke hit on the later tabs, where
  # the emulator is slowest (the required retries grew tab over tab until the budget ran out).
  # Polling breaks within ~2s of the repaint when fast, yet waits patiently when slow. The tap is
  # re-issued a few times because a lone `adb input tap` is occasionally dropped on a flaky boot.
  # Re-tapping is idempotent (you stay on the target view), and the loop only ACCEPTS a real pixel
  # change, so neither the retries nor the generous budget can mask a genuine geometry/selector
  # break: a view that never actually switches changes nothing on screen and still fails.
  local max_taps=3
  local poll_secs=30
  local changed=0
  local tap=1
  while [ "$tap" -le "$max_taps" ]; do
    adb shell input tap "$x" "$y"
    local waited=0
    while [ "$waited" -lt "$poll_secs" ]; do
      sleep 2
      waited=$((waited + 2))
      screenshot "nav-$index-$label"
      if [ "$expect_change" != "1" ] || [ -z "$PREVIOUS_NAV_SCREENSHOT" ] \
        || ! cmp -s "$PREVIOUS_NAV_SCREENSHOT" "$shot"; then
        changed=1
        break
      fi
    done
    if [ "$changed" -eq 1 ]; then
      break
    fi
    echo "  No view change after tapping $label (tap $tap/$max_taps, polled ${poll_secs}s); re-tapping."
    # Observed flake mode: the adb daemon that logged "device offline" during boot keeps dropping
    # input events afterward. Give it a health re-check so the next tap goes to a connected
    # transport instead of the void.
    adb wait-for-device >/dev/null 2>&1 || true
    tap=$((tap + 1))
  done
  if [ "$changed" -ne 1 ]; then
    echo "Dashboard screenshot did not change after tapping bottom-nav $label (after $max_taps taps, ${poll_secs}s polled each)."
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
  # 30 s, not 12: one 2026-09-05 run missed a 12 s window on a slow boot.
  for _ in $(seq 1 30); do
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

# Percent of guest CPU time idle across a 3 s window, from /proc/stat (as local-emulator.sh).
guest_idle_pct() {
  adb shell 'head -1 /proc/stat; sleep 3; head -1 /proc/stat' 2>/dev/null | tr -d '\r' | awk '
    { t=0; for (k=2; k<=NF; k++) t+=$k; idle=$5+$6 }
    NR==1 { t1=t; i1=idle } NR==2 && t>t1 { printf "%d", 100*(idle-i1)/(t-t1) }'
}

# Right after boot the google_apis image is still running dexopt and Play services setup. Twice
# (runs 36391839841, 36220518426) Play services restarted mid-test and Android killed our process
# with it ("depends on provider com.google.android.gms/.fonts.provider.FontsProvider in dying
# proc"). Wait for the guest to go mostly idle first; capped so a busy guest only costs time.
wait_for_settled_guest() {
  local idle=""
  for _ in $(seq 1 30); do
    idle="$(guest_idle_pct)"
    if [ -n "$idle" ] && [ "$idle" -ge 70 ]; then
      echo "Guest settled (${idle}% idle)."
      return 0
    fi
    sleep 1
  done
  echo "::warning title=Emulator smoke::guest still busy (${idle:-?}% idle) after ~2 min; continuing"
}

# Number of VoltStartup lines for an exact mark in the current logcat snapshot.
count_marks() {
  capture_logcat
  grep -cF "mark=$1 " "$LOGCAT" || true
}

# Wait for one more "mark=<name>" line than the caller counted before its action.
wait_for_mark() {
  local mark="$1" before="$2" what="$3"
  for _ in $(seq 1 30); do
    if [ "$(count_marks "$mark")" -gt "$before" ]; then
      return 0
    fi
    sleep 2
  done
  echo "Compose $what never logged VoltStartup mark=$mark within 60s."
  exit 1
}

# Open the Compose launcher on a tab (and optional route) by intent, then require the screen mark.
open_compose() {
  local label="$1" view="$2"
  shift 2
  local before out
  before="$(count_marks "$COMPOSE_SCREEN_MARK:$view")"
  out="$(adb shell am start --activity-clear-top -W -n "$COMPOSE_ACTIVITY" "$@" | tr -d '\r')"
  if grep -q "Error" <<<"$out"; then
    echo "$out"
    echo "Could not start the Compose dashboard for $label."
    exit 1
  fi
  wait_for_mark "$COMPOSE_SCREEN_MARK:$view" "$before" "screen $label"
  echo "Compose $label shown ($(grep -E 'TotalTime' <<<"$out" | tr -s ' ' || echo 'no timing'))."
  sleep 1
  screenshot "compose-$label"
  check_logcat "compose $label"
}

wait_for_settled_guest
adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
adb install -r app/build/outputs/apk/debug/app-debug.apk
# AOT-compile so the first launch isn't an interpreted run starving the main thread on a slow,
# software-rendered emulator. Best effort: a failure here only costs launch speed.
adb shell cmd package compile -m speed -f "$PKG" || true
adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
adb shell run-as "$PKG" rm -rf files/obd-logs >/dev/null 2>&1 || true
grant_runtime_permissions

echo "Compose phase: launching the launcher activity with demo telemetry."
adb logcat -c
open_compose drive drive --ez vt.demo true
wait_for_mark "$COMPOSE_TELEMETRY_MARK" 0 "live telemetry"
echo "Demo telemetry reached the Compose dashboard."
sleep 2
screenshot "compose-drive-live"
check_logcat "compose demo telemetry"
open_compose trips map --es vt.tab trips
open_compose charge charge --es vt.tab charge
open_compose insights insights --es vt.tab insights
open_compose car diagnostics --es vt.tab car
open_compose health diagnostics --es vt.tab car --es vt.route health
open_compose settings settings --es vt.tab drive --es vt.route settings
stop_service_as_app
sleep 1
check_logcat "compose demo disconnect"
cp "$LOGCAT" "$ARTIFACT_DIR/compose-logcat.txt"

echo "Classic phase: WebView dashboard handshake, demo telemetry and bottom-nav taps."
# Start the classic phase cold and clean: the Compose demo session already wrote demo samples
# and VoltStartup obd_* marks that would otherwise satisfy this phase's checks.
adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
adb shell run-as "$PKG" rm -rf files/obd-logs >/dev/null 2>&1 || true
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
# OBD latency gate: the debug build emits VoltStartup obd_* marks around every
# session (StartupTrace.OBD_*). The demo session just exercised the same
# connect-request -> first-sample path a real adapter uses, so parse the marks
# and enforce the (provisional, generous) ceilings. --require demo also makes
# this a mark-PRESENCE gate: if the marks are renamed or dropped, this fails
# rather than silently measuring nothing. check_logcat above already refreshed
# $LOGCAT with the demo session's lines.
echo "Checking VoltStartup obd_* latency marks for the demo session."
python3 tools/perf/check_obd_latency.py \
  --logcat "$LOGCAT" \
  --require demo \
  --output-dir "$ARTIFACT_DIR/obd-latency"
stop_service_as_app
sleep 1
check_logcat "demo disconnect before navigation"

size="$(screen_size)"
if [ -z "$size" ]; then
  echo "Could not read emulator screen size; using Pixel-profile fallback."
  size="1080 2400"
fi
read -r width height <<<"$size"

# One tap per data-nav button, in template order (EmulatorSmokeContractTest pins the list).
tap_bottom_nav 0 drive "$width" "$height" 0
tap_bottom_nav 1 map "$width" "$height"
tap_bottom_nav 2 charge "$width" "$height"
tap_bottom_nav 3 insights "$width" "$height"
tap_bottom_nav 4 diagnostics "$width" "$height"
tap_bottom_nav 5 settings "$width" "$height"

stop_service_as_app
sleep 1
check_logcat "disconnect cleanup"

echo "Emulator runtime smoke passed. Screenshots: $SCREENSHOT_DIR"
