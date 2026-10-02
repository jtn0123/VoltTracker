#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EVIDENCE_DIR="${ROOT}/mobile/android/docs/release-candidates"
REQUIRED="${REQUIRE_RELEASE_CANDIDATE_EVIDENCE:-0}"
REQUIRE_READY_TO_TAG="${REQUIRE_READY_TO_TAG:-${REQUIRED}}"
REQUIRE_END_USER_DOGFOOD="${REQUIRE_END_USER_DOGFOOD:-0}"
EVIDENCE_FILE="${RELEASE_CANDIDATE_EVIDENCE:-}"

if [[ -z "${EVIDENCE_FILE}" ]]; then
  if [[ -d "${EVIDENCE_DIR}" ]]; then
    EVIDENCE_FILE="$(find "${EVIDENCE_DIR}" -type f -name '*.md' -print | sort | tail -1)"
  fi
fi

missing() {
  local message="$1"
  if [[ "${REQUIRED}" == "1" || "${REQUIRE_END_USER_DOGFOOD}" == "1" ]]; then
    echo "${message}" >&2
    exit 1
  fi
  echo "${message}" >&2
  echo "Set REQUIRE_RELEASE_CANDIDATE_EVIDENCE=1 or REQUIRE_END_USER_DOGFOOD=1 to make this check fail locally." >&2
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

if [[ "${REQUIRE_END_USER_DOGFOOD}" == "1" ]]; then
  dogfood_patterns=(
    '^- End-user dogfood participants:[[:space:]]*([3-9]|[1-9][0-9]+)[[:space:]]*$'
    '^- Setup without facilitator help:[[:space:]]*(yes|pass)[[:space:]]*$'
    '^- First drive without facilitator help:[[:space:]]*(yes|pass)[[:space:]]*$'
    '^- Trip review without facilitator help:[[:space:]]*(yes|pass)[[:space:]]*$'
    '^- Charge review without facilitator help:[[:space:]]*(yes|pass)[[:space:]]*$'
    '^- Code scan without facilitator help:[[:space:]]*(yes|pass)[[:space:]]*$'
    '^- Repeated hesitation issues resolved:[[:space:]]*(yes|pass)[[:space:]]*$'
    '^- Dogfood evidence:[[:space:]]*.+$'
  )
  for pattern in "${dogfood_patterns[@]}"; do
    if ! grep -Eiq "${pattern}" "${EVIDENCE_FILE}"; then
      missing "Release-candidate evidence has not passed the end-user dogfood gate: ${EVIDENCE_FILE} is missing '${pattern}'."
    fi
  done

  for runtime_field in "Physical phone" "Real car / OBD"; do
    if ! grep -Eiq "^- ${runtime_field}:[[:space:]]*.+$" "${EVIDENCE_FILE}" || \
       grep -Eiq "^- ${runtime_field}:[[:space:]]*(none|n/a|not tested|skipped)[[:space:]]*$" "${EVIDENCE_FILE}"; then
      missing "End-user dogfood requires real ${runtime_field} evidence in ${EVIDENCE_FILE}."
    fi
  done
fi

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
