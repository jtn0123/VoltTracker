#!/usr/bin/env bash
# Emulator startup smoke: install the debug APK, launch MainActivity, and PROVE the
# dashboard JS actually came alive — not just that the process didn't crash.
#
# The positive check is the point: poll logcat for the JS->native handshake
# ("dashboard handshake received", logged by MainActivity.onDashboardReady). If the
# dashboard's script chain is dead (e.g. the file:// ES-module regression) the app
# still *loads* and logs nothing alarming, so only the absence of that line reveals
# it. A negative scan then rejects any crash or uncaught JS error.
#
# Run by .github/workflows/android-emulator-smoke.yml inside a booted emulator (the
# android-emulator-runner action executes the workflow `script:` line-by-line, hence
# this lives in a file). adb is on PATH in that context.
set -euo pipefail

# Resolve to mobile/android regardless of caller cwd (scripts/ -> mobile/android).
cd "$(dirname "$0")/.."

LOGCAT="build/emulator-smoke-logcat.txt"
mkdir -p build

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb shell am start -n com.volttracker.obdpoc/.MainActivity

ready=0
for _ in $(seq 1 20); do
  adb logcat -d -v time >"$LOGCAT"
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

# Let the app settle, then re-capture: the snapshot above stopped the instant the
# handshake appeared, so a crash or JS error a moment AFTER it would be missed by the
# scan below unless we refresh logcat here.
sleep 3
adb logcat -d -v time >"$LOGCAT"

if grep -E "FATAL EXCEPTION|AndroidRuntime|Unable to start activity|chromium.*Uncaught|dashboard console:.*(TypeError|ReferenceError)" "$LOGCAT"; then
  echo "Startup smoke found an exception in logcat."
  exit 1
fi

echo "Emulator startup smoke passed."
