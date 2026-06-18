#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 3 ]]; then
  echo "usage: $0 /path/to/out.db [sessions] [samples-per-session]" >&2
  exit 2
fi

out_path="$1"
sessions="${2:-500}"
samples_per_session="${3:-2000}"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
android_dir="$(cd "$script_dir/.." && pwd)"
if [[ "$out_path" != /* ]]; then
  out_path="$android_dir/$out_path"
fi
report_dir="$android_dir/build/reports/synthetic-db-generator"
xml_path="$android_dir/app/build/test-results/testDebugUnitTest/TEST-com.volttracker.obdpoc.data.SyntheticDatabaseGeneratorTest.xml"

mkdir -p "$report_dir" "$(dirname "$out_path")"

cd "$android_dir"
./gradlew --no-daemon \
  :app:testDebugUnitTest \
  --tests 'com.volttracker.obdpoc.data.SyntheticDatabaseGeneratorTest' \
  -PvoltTrackerSyntheticDbPath="$out_path" \
  -PvoltTrackerSyntheticSessions="$sessions" \
  -PvoltTrackerSyntheticSamplesPerSession="$samples_per_session"

python3 - "$xml_path" "$report_dir" <<'PY'
import json
import re
import sys
from pathlib import Path

xml_path = Path(sys.argv[1])
report_dir = Path(sys.argv[2])
text = xml_path.read_text(encoding="utf-8") if xml_path.exists() else ""
match = re.search(r"\[SYNTHETIC_DB_GENERATOR\]\s*(\{.*\})", text, re.S)
if not match:
    summary = report_dir / "summary.md"
    summary.write_text(
        "# Synthetic Database Generator\n\n"
        "Status: `no-result-found`\n\n"
        f"JUnit XML: `{xml_path}`\n\n",
        encoding="utf-8",
    )
    print(summary)
    sys.exit(0)

payload = json.loads(match.group(1))
(report_dir / "result.json").write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
summary = report_dir / "summary.md"
summary.write_text(
    "# Synthetic Database Generator\n\n"
    "Status: `completed`\n\n"
    f"Output: `{payload.get('outputPath')}`\n"
    f"Sessions: `{payload.get('sessions'):,}`\n"
    f"Samples/session: `{payload.get('samplesPerSession'):,}`\n"
    f"Telemetry rows: `{payload.get('telemetryRows'):,}`\n"
    f"Bytes: `{payload.get('bytes'):,}`\n"
    f"Schema version: `{payload.get('databaseVersion')}`\n\n"
    "Next: run `bash tools/benchmark-real-db-local.sh <output>` to benchmark this fixture.\n",
    encoding="utf-8",
)
print(summary)
PY
