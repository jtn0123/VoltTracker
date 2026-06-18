#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 /path/to/volttracker_obd_poc.db" >&2
  exit 2
fi

db_path="$1"
if [[ ! -f "$db_path" ]]; then
  echo "database not found: $db_path" >&2
  exit 2
fi
db_dir="$(cd "$(dirname "$db_path")" && pwd)"
db_path="$db_dir/$(basename "$db_path")"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
android_dir="$(cd "$script_dir/.." && pwd)"
report_dir="$android_dir/build/reports/real-db-benchmark"
xml_path="$android_dir/app/build/test-results/testDebugUnitTest/TEST-com.volttracker.obdpoc.data.RealDatabasePerformanceBenchmarkTest.xml"

mkdir -p "$report_dir"

cd "$android_dir"
./gradlew --no-daemon \
  :app:testDebugUnitTest \
  --tests 'com.volttracker.obdpoc.data.RealDatabasePerformanceBenchmarkTest' \
  -PvoltTrackerRealDbPath="$db_path"

python3 - "$xml_path" "$report_dir" "$db_path" <<'PY'
import json
import re
import sys
from pathlib import Path

xml_path = Path(sys.argv[1])
report_dir = Path(sys.argv[2])
db_path = Path(sys.argv[3])
text = xml_path.read_text(encoding="utf-8") if xml_path.exists() else ""
match = re.search(r"\[REAL_DB_BENCHMARK\]\s*(\{.*\})", text, re.S)
if not match:
    summary = report_dir / "summary.md"
    summary.write_text(
        "# Real Database Benchmark\n\n"
        "Status: `no-result-found`\n\n"
        f"Database: `{db_path}`\n\n"
        f"JUnit XML: `{xml_path}`\n\n"
        "The targeted test finished, but no `[REAL_DB_BENCHMARK]` JSON block was found.\n",
        encoding="utf-8",
    )
    print(summary)
    sys.exit(0)

payload = json.loads(match.group(1))
(report_dir / "result.json").write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
timings = payload.get("timingsMs", {})
sizes = payload.get("payloadBytes", {})
overview = payload.get("overview", {})

def row(name, value):
    return f"| {name} | {value if value is not None else 'n/a'} |"

summary_lines = [
    "# Real Database Benchmark",
    "",
    "Status: `completed`",
    "",
    f"Database: `{payload.get('sourcePath', str(db_path))}`",
    f"Source bytes: `{payload.get('sourceBytes', 0):,}`",
    f"Schema version: `{payload.get('databaseVersion', '')}`",
    "",
    "## Timings",
    "",
    "| Read | ms |",
    "|---|---:|",
    row("open copied DB", timings.get("open")),
    row("overview", timings.get("overview")),
    row("details", timings.get("details")),
    row("trips", timings.get("trips")),
    row("route", timings.get("route")),
    row("insights", timings.get("insights")),
    row("battery SOH history", timings.get("batterySohHistory")),
    "",
    "## Payload Sizes",
    "",
    "| Payload | bytes |",
    "|---|---:|",
    row("details", sizes.get("details")),
    row("trips", sizes.get("trips")),
    row("route", sizes.get("route")),
    row("insights", sizes.get("insights")),
    row("battery SOH history", sizes.get("batterySohHistory")),
    "",
    "## Overview Counts",
    "",
    "```json",
    json.dumps(overview, indent=2),
    "```",
    "",
    f"Raw JSON: `{report_dir / 'result.json'}`",
    f"JUnit XML: `{xml_path}`",
    "",
]
summary = report_dir / "summary.md"
summary.write_text("\n".join(summary_lines), encoding="utf-8")
print(summary)
PY
