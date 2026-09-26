#!/usr/bin/env bash
# Verdict for an `adb shell am instrument -w` transcript (default, non-raw output format).
#
# `am instrument` exits 0 whether the tests pass, fail, or the app process crashes, so the
# transcript is the only reliable signal. The shapes it can take:
#   pass:       "... OK (3 tests)"
#   failures:   "FAILURES!!!" / "Tests run: 3,  Failures: 1"
#   crash:      "INSTRUMENTATION_RESULT: shortMsg=Process crashed." + "INSTRUMENTATION_CODE: 0"
#   bad runner: "INSTRUMENTATION_FAILED: <component>" / "Error: Unable to find instrumentation"
# Pass requires an explicit "OK (N tests)" line with N >= 1 (Gradle's connected task also
# failed on zero tests) AND none of the failure markers.
#
# Usage: check-instrumentation-output.sh <transcript>
set -euo pipefail

transcript="${1:?usage: check-instrumentation-output.sh <transcript>}"
if [ ! -s "$transcript" ]; then
  echo "::error title=Instrumented tests::empty am instrument transcript ($transcript)"
  exit 1
fi

# adb may emit CRLF line endings; normalise before anchoring regexes.
clean="$(tr -d '\r' <"$transcript")"

if grep -qE 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed|shortMsg=|Error: Unable to find instrumentation' <<<"$clean"; then
  echo "::error title=Instrumented tests::am instrument reported a failure or crash (see emulator-instrumentation.txt)"
  exit 1
fi
if ! grep -qE '^OK \([1-9][0-9]* tests?\)$' <<<"$clean"; then
  echo "::error title=Instrumented tests::no 'OK (N tests)' line in the am instrument transcript"
  exit 1
fi
echo "Instrumented tests passed: $(grep -E '^OK \(' <<<"$clean")"
