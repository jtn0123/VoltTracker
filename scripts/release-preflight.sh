#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "== release config contract =="
python "${ROOT}/.github/scripts/check_release_config.py"

echo "== semantic release dry run =="
python "${ROOT}/.github/scripts/semantic_release_dry_run.py"

echo "== release candidate evidence =="
REQUIRE_RELEASE_CANDIDATE_EVIDENCE="${REQUIRE_RELEASE_CANDIDATE_EVIDENCE:-1}" \
  REQUIRE_READY_TO_TAG="${REQUIRE_READY_TO_TAG:-1}" \
  "${ROOT}/scripts/check-release-candidate-evidence.sh"

echo "== dashboard dependency audits =="
(
  cd "${ROOT}/mobile/android/dashboard-tests"
  npm ci
  npm audit --audit-level=high
)
(
  cd "${ROOT}/mobile/android/dashboard-e2e"
  npm ci
  npm audit --audit-level=high
)

echo "== active app verification =="
(
  cd "${ROOT}/mobile/android"
  ./gradlew --no-daemon verifyActiveApp
)

if [[ "${RUN_EMULATOR_SMOKE:-0}" == "1" ]]; then
  echo "== local emulator smoke =="
  "${ROOT}/mobile/android/scripts/run-emulator-smoke-local.sh"
else
  echo "== local emulator smoke skipped =="
  echo "Set RUN_EMULATOR_SMOKE=1 when an emulator/device is available."
fi
