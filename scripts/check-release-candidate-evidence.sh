#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EVIDENCE_DIR="${ROOT}/mobile/android/docs/release-candidates"
REQUIRED="${REQUIRE_RELEASE_CANDIDATE_EVIDENCE:-0}"
REQUIRE_READY_TO_TAG="${REQUIRE_READY_TO_TAG:-${REQUIRED}}"
EVIDENCE_FILE="${RELEASE_CANDIDATE_EVIDENCE:-}"

if [[ -z "${EVIDENCE_FILE}" ]]; then
  if [[ -d "${EVIDENCE_DIR}" ]]; then
    EVIDENCE_FILE="$(find "${EVIDENCE_DIR}" -type f -name '*.md' -print | sort | tail -1)"
  fi
fi

missing() {
  local message="$1"
  if [[ "${REQUIRED}" == "1" ]]; then
    echo "${message}" >&2
    exit 1
  fi
  echo "${message}" >&2
  echo "Set REQUIRE_RELEASE_CANDIDATE_EVIDENCE=1 to make this check fail locally." >&2
  exit 0
}

if [[ -z "${EVIDENCE_FILE}" || ! -f "${EVIDENCE_FILE}" ]]; then
  missing "No release-candidate evidence file found under mobile/android/docs/release-candidates/."
fi

required_patterns=(
  '^- Branch: .+'
  '^- Commit: .+'
  '^- Version / expected tag: .+'
  '^- APK under test: .+'
  '^- Highest validation level reached: .+'
  '^- Ready to tag: .+'
)

for pattern in "${required_patterns[@]}"; do
  if ! grep -Eq "${pattern}" "${EVIDENCE_FILE}"; then
    missing "Release-candidate evidence is incomplete: ${EVIDENCE_FILE} is missing '${pattern}'."
  fi
done

if grep -Eq '^- (Branch|Commit|Version / expected tag|APK under test|Highest validation level reached|Ready to tag):[[:space:]]*$' "${EVIDENCE_FILE}"; then
  missing "Release-candidate evidence has blank required fields: ${EVIDENCE_FILE}."
fi

if ! grep -Eiq '^- Ready to tag:[[:space:]]*(yes|true)$' "${EVIDENCE_FILE}"; then
  if [[ "${REQUIRE_READY_TO_TAG}" == "1" ]]; then
    missing "Release-candidate evidence must say Ready to tag: yes before tagging: ${EVIDENCE_FILE}."
  fi
  echo "Release-candidate evidence is not ready to tag yet: ${EVIDENCE_FILE}." >&2
fi

echo "Release-candidate evidence checked: ${EVIDENCE_FILE#${ROOT}/}"
