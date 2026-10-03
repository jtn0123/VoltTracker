#!/usr/bin/env bash
# Keep one open "CI failing on main: <workflow>" issue per broken main-branch workflow.
#
# Called by .github/workflows/ci-failure-alert.yml for every completed run of a watched
# workflow on main. A failing run opens the issue (or comments on the open one); the next
# healthy run closes it. Locally: DRY_RUN=1 WORKFLOW=... RUN_ID=... GH_REPO=owner/name
# prints what it would do without touching any issue.
#
# Inputs (env): WORKFLOW, RUN_ID, RUN_URL, CONCLUSION, EVENT, HEAD_SHA, GH_REPO, DRY_RUN.
set -euo pipefail

: "${WORKFLOW:?}" "${RUN_ID:?}" "${CONCLUSION:?}" "${GH_REPO:?}"
RUN_URL="${RUN_URL:-https://github.com/${GH_REPO}/actions/runs/${RUN_ID}}"
EVENT="${EVENT:-push}"
HEAD_SHA="${HEAD_SHA:-unknown}"
DRY_RUN="${DRY_RUN:-0}"
LABEL="ci-failure"
TITLE="CI failing on main: ${WORKFLOW}"

failed_jobs=""
if [[ "$CONCLUSION" != "success" ]]; then
  failed_jobs="$(gh api "repos/${GH_REPO}/actions/runs/${RUN_ID}/jobs?per_page=100" \
    --jq '.jobs[] | select(.conclusion == "failure" or .conclusion == "timed_out") | .name')"
  # A run that never started a job (e.g. startup_failure) still means main is broken.
  if [[ -z "$failed_jobs" ]]; then
    failed_jobs="(no job failed; run concluded ${CONCLUSION})"
  fi
fi

existing="$(gh issue list --repo "$GH_REPO" --label "$LABEL" --state open --limit 100 \
  --json number,title | jq -r --arg t "$TITLE" 'map(select(.title == $t)) | .[0].number // empty')"

run() {
  if [[ "$DRY_RUN" = "1" ]]; then
    printf 'DRY_RUN:'
    printf ' %q' "$@"
    printf '\n'
  else
    "$@"
  fi
}

if [[ -n "$failed_jobs" ]]; then
  # shellcheck disable=SC2016 # the backticks are literal Markdown code spans, not expansions
  jobs_md="$(printf '%s\n' "$failed_jobs" | sed 's/^/- `/; s/$/`/')"
  # shellcheck disable=SC2016 # literal Markdown backticks again
  body="$(printf '[%s run](%s) on `%s` (%s) failed.\n\nFailed jobs:\n%s\n' \
    "$WORKFLOW" "$RUN_URL" "${HEAD_SHA:0:7}" "$EVENT" "$jobs_md")"
  if [[ -n "$existing" ]]; then
    run gh issue comment "$existing" --repo "$GH_REPO" --body "Still failing. ${body}"
  else
    run gh label create "$LABEL" --repo "$GH_REPO" --color B60205 --force \
      --description "A workflow on main is failing (opened and closed by ci-failure-alert)"
    run gh issue create --repo "$GH_REPO" --title "$TITLE" --label "$LABEL" \
      --body "$(printf '%s\n\nThis issue closes itself when the next %s run on main passes.\n' "$body" "$WORKFLOW")"
  fi
elif [[ -n "$existing" ]]; then
  run gh issue close "$existing" --repo "$GH_REPO" \
    --comment "Passing again: [${WORKFLOW} run](${RUN_URL}) on \`${HEAD_SHA:0:7}\`."
else
  echo "${WORKFLOW} is healthy on main; no open ${LABEL} issue to close."
fi
