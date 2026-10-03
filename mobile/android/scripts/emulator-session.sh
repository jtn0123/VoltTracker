#!/usr/bin/env bash
# One emulator session: streaming logcat + the shell smoke + the instrumented tests.
#
# WHY THIS IS A FILE AND NOT INLINE YAML
# reactivecircus/android-emulator-runner runs its `script:` input ONE LINE AT A TIME, each
# through a separate `/usr/bin/sh -c`. That has three consequences that are easy to miss:
#   * `sh` is dash on the runners, so bash syntax is not available;
#   * a multi-line construct (function, if, loop) cannot parse — line one is a complete
#     program that ends mid-construct;
#   * no state survives between lines. A background PID captured with `$!` on one line is
#     meaningless on the next, and a `trap` set on one line dies with that line's shell.
# That is why the existing inline script had to repeat `cd mobile/android` on its second
# line. Anything needing a variable, a trap, or a background process has to live in a file
# that runs as a single process, which is this.
#
# WHAT IT CAPTURES
# The smoke script takes `adb logcat -d` snapshots and they all stop when it does, so the
# instrumented-test phase — where the emulator-death flake actually happens — was never
# recorded. Three failures produced no evidence for exactly that reason. This stream runs
# for the whole session and stops when the device goes away, so its last lines are the
# guest's final moments.
#
# The capture is diagnostic only. It must never be the reason the job fails, so its own
# errors are swallowed; the exit status below is the real work's.
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
android_dir="$(cd "$here/.." && pwd)"

# Both paths below must stay under mobile/android/build — that is what the workflow's
# upload-artifact step collects. emulator-smoke.sh gets an absolute path so its own
# `cd "$(dirname "$0")/.."` still lands in mobile/android and its relative LOGCAT resolves
# to the same directory, independent of where this script was invoked from.
build_dir="$android_dir/build"
mkdir -p "$build_dir"
session_logcat="$build_dir/emulator-session-logcat.txt"

adb logcat -v threadtime >"$session_logcat" 2>&1 &
session_logcat_pid=$!

# kill AND reap. SIGTERM only asks; without the wait this script can return while adb
# logcat still holds buffered output and the upload step reads a truncated file. The part
# lost to truncation is the tail, which is the whole point of the capture.
cleanup_session_logcat() {
  kill "$session_logcat_pid" 2>/dev/null || true
  wait "$session_logcat_pid" 2>/dev/null || true
}
trap cleanup_session_logcat EXIT

# The one infra failure the session retries. Twice (runs 36391839841, 36220518426) the
# google_apis image restarted Play services mid-run and Android killed our process with it,
# because the WebView holds Play services' font provider:
#   Killing 5218:com.volttracker.obdpoc.debug/u0a190 (adj 0): depends on provider
#   com.google.android.gms/.fonts.provider.FontsProvider in dying proc com.google.android.gms.persistent
# A phase that fails AND logged a new kill like that gets exactly one more attempt. Any other
# failure, or a second failure, still fails the job.
gms_kill_re='Killing [0-9]+:com\.volttracker\.obdpoc(\.debug)?/.*depends on provider .* in dying proc com\.google\.android\.gms'

gms_kills() {
  grep -cE "$gms_kill_re" "$session_logcat" 2>/dev/null || true
}

run_phase() {
  local name="$1"
  shift
  local before status
  before="$(gms_kills)"
  "$@" && return 0
  status=$?
  # Give the streaming logcat a moment to flush the kill line to disk.
  sleep 3
  if [ "$(gms_kills)" -gt "$before" ]; then
    echo "::warning title=Emulator smoke::$name failed after Play services restarted and took the app process with it (emulator image, not the app). Retrying once."
    "$@"
    return $?
  fi
  return "$status"
}

run_phase "Shell smoke" bash "$android_dir/scripts/emulator-smoke.sh" || exit $?

# Instrumented tests, run straight through adb rather than `gradlew connectedDebugAndroidTest`.
# The workflow's build step already produced both APKs and emulator-smoke.sh installed the app;
# a second Gradle invocation here missed the configuration cache, forked a fresh daemon and
# recompiled/re-dexed/re-packaged both APKs (~2.5 min measured) to run ~15 s of tests. The
# rebuild happened because app/build.gradle's benchmarkDebugTarget switch (meant for the
# startup Macrobenchmark) matches any task name containing "connectedDebugAndroidTest" and
# flips the debug APK to debuggable=false/profileable=true. The tests now run against the SAME
# debuggable debug APK the shell smoke above just exercised (and that ships as latest-debug).
test_apk="$android_dir/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
instrumentation_log="$build_dir/emulator-instrumentation.txt"
runner="com.volttracker.obdpoc.debug.test/androidx.test.runner.AndroidJUnitRunner"

run_instrumented_tests() {
  adb install -r -t "$test_apk" || return $?
  # Match Gradle's fresh start: instrumentation restarts the target process anyway, but stop the
  # demo telemetry the shell smoke left running so the tests begin from a quiet app.
  adb shell am force-stop com.volttracker.obdpoc.debug || true
  # `am instrument` exits 0 even when tests fail or the process crashes, so its exit status is
  # not the verdict — check-instrumentation-output.sh parses the transcript instead.
  adb shell am instrument -w "$runner" 2>&1 | tee "$instrumentation_log"
  local adb_status=${PIPESTATUS[0]}
  if [ "$adb_status" -ne 0 ]; then
    echo "::error title=Instrumented tests::adb shell am instrument exited $adb_status"
    return "$adb_status"
  fi
  bash "$android_dir/scripts/check-instrumentation-output.sh" "$instrumentation_log"
}

run_phase "Instrumented tests" run_instrumented_tests || exit $?
