#!/usr/bin/env bash
set -euo pipefail

package_name="${VOLTTRACKER_PACKAGE:-com.volttracker.obdpoc}"
database_name="${VOLTTRACKER_DB_NAME:-volttracker_obd_poc.db}"
target="${1:-}"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
android_dir="$(cd "$script_dir/.." && pwd)"
timestamp="$(date -u +"%Y%m%dT%H%M%SZ")"
report_dir="$android_dir/build/reports/device-baseline/$timestamp"
raw_dir="$report_dir/raw"
pulled_dir="$report_dir/pulled"

mkdir -p "$raw_dir" "$pulled_dir"

pick_ready_device() {
  adb devices | awk 'NR > 1 && $2 == "device" { print $1; exit }'
}

device_is_ready() {
  [[ -n "$1" ]] && [[ "$(adb -s "$1" get-state 2>/dev/null | tr -d '\r')" == "device" ]]
}

if [[ -n "$target" && "$target" == *:* ]]; then
  adb connect "$target" >"$raw_dir/adb-connect.txt" 2>&1 || true
fi

serial=""
if device_is_ready "$target"; then
  serial="$target"
fi
for _ in 1 2 3 4 5; do
  if [[ -n "$serial" ]]; then
    break
  fi
  serial="$(pick_ready_device)"
  if [[ -n "$serial" ]]; then
    break
  fi
  sleep 1
done
if [[ -z "$serial" && -n "$target" && "$target" != *:* ]]; then
  serial="$target"
fi

if [[ -z "$serial" ]]; then
  cat >"$report_dir/summary.md" <<EOF
# VoltTracker Device Baseline

Status: \`no-ready-device\`

Target: \`${target:-"(first ready adb device)"}\`

No authorized ADB device was available. Refresh Wireless Debugging, pair this
Mac if needed, then rerun:

\`\`\`sh
bash tools/device-baseline-local.sh <ip:port-or-adb-serial>
\`\`\`
EOF
  echo "$report_dir/summary.md"
  exit 2
fi

adb -s "$serial" devices -l >"$raw_dir/adb-devices.txt" 2>&1 || true
adb -s "$serial" shell getprop >"$raw_dir/getprop.txt" 2>&1 || true
adb -s "$serial" shell dumpsys battery >"$raw_dir/dumpsys-battery.txt" 2>&1 || true
adb -s "$serial" shell dumpsys meminfo "$package_name" >"$raw_dir/dumpsys-meminfo.txt" 2>&1 || true
adb -s "$serial" shell dumpsys package "$package_name" >"$raw_dir/dumpsys-package.txt" 2>&1 || true

snapshot_mode="force-stopped"
if [[ "${VOLTTRACKER_KEEP_APP_RUNNING:-0}" == "1" ]]; then
  snapshot_mode="live"
else
  adb -s "$serial" shell am force-stop "$package_name" >"$raw_dir/force-stop.txt" 2>&1 || true
  sleep 1
fi

adb -s "$serial" shell "run-as $package_name sh -c 'ls -lah databases files files/obd-logs 2>/dev/null || true'" \
  >"$raw_dir/run-as-listing.txt" 2>&1 || true

prop() {
  adb -s "$serial" shell getprop "$1" 2>/dev/null | tr -d '\r'
}

pull_run_as_file() {
  local remote_path="$1"
  local dest_path="$2"
  if adb -s "$serial" shell "run-as $package_name sh -c 'test -f \"$remote_path\"'" >/dev/null 2>&1; then
    adb -s "$serial" exec-out run-as "$package_name" cat "$remote_path" >"$dest_path"
    echo "$dest_path"
  fi
}

database_path="$pulled_dir/$database_name"
database_status="not-found"
if pulled_database="$(pull_run_as_file "databases/$database_name" "$database_path")"; then
  if [[ -n "$pulled_database" && -f "$pulled_database" ]]; then
    database_status="pulled"
    pull_run_as_file "databases/$database_name-wal" "$pulled_dir/$database_name-wal" >/dev/null || true
    pull_run_as_file "databases/$database_name-shm" "$pulled_dir/$database_name-shm" >/dev/null || true
  fi
fi

logs_status="not-found"
if adb -s "$serial" shell "run-as $package_name sh -c 'test -d files/obd-logs'" >/dev/null 2>&1; then
  if adb -s "$serial" exec-out run-as "$package_name" sh -c "cd files && tar -cf - obd-logs" \
    >"$pulled_dir/obd-logs.tar" 2>"$raw_dir/obd-logs-tar.stderr"; then
    logs_status="pulled"
  else
    logs_status="failed"
  fi
fi

real_db_status="skipped-no-database"
if [[ "$database_status" == "pulled" ]]; then
  if (cd "$android_dir" && bash tools/benchmark-real-db-local.sh "$database_path") \
    >"$raw_dir/real-db-benchmark.log" 2>&1; then
    real_db_status="completed"
    cp "$android_dir/build/reports/real-db-benchmark/summary.md" "$report_dir/real-db-benchmark-summary.md" 2>/dev/null || true
    cp "$android_dir/build/reports/real-db-benchmark/result.json" "$report_dir/real-db-benchmark-result.json" 2>/dev/null || true
  else
    real_db_status="failed"
  fi
fi

startup_status="skipped-by-env"
if [[ "${VOLTTRACKER_SKIP_STARTUP_BENCHMARK:-0}" != "1" ]]; then
  if (cd "$android_dir" && bash tools/benchmark-adb-startup-local.sh "$serial") \
    >"$raw_dir/startup-benchmark.log" 2>&1; then
    startup_status="completed"
    startup_summary="$(tail -n 1 "$raw_dir/startup-benchmark.log" | tr -d '\r')"
    cp "$startup_summary" "$report_dir/startup-benchmark-summary.md" 2>/dev/null || true
  else
    startup_status="failed"
  fi
fi

tab_status="skipped-by-env"
if [[ "${VOLTTRACKER_SKIP_TAB_BENCHMARK:-0}" != "1" ]]; then
  if (cd "$android_dir" && bash tools/benchmark-adb-tabs-local.sh "$serial") \
    >"$raw_dir/tab-benchmark.log" 2>&1; then
    tab_status="completed"
    tab_summary="$(tail -n 1 "$raw_dir/tab-benchmark.log" | tr -d '\r')"
    cp "$tab_summary" "$report_dir/tab-benchmark-summary.md" 2>/dev/null || true
  else
    tab_status="failed"
  fi
fi

database_bytes="n/a"
if [[ -f "$database_path" ]]; then
  database_bytes="$(wc -c <"$database_path" | tr -d ' ')"
fi

cat >"$report_dir/summary.md" <<EOF
# VoltTracker Device Baseline

Status: \`completed\`

Generated: \`$timestamp\`

Device:

- Serial: \`$serial\`
- Manufacturer: \`$(prop ro.product.manufacturer)\`
- Model: \`$(prop ro.product.model)\`
- Android SDK: \`$(prop ro.build.version.sdk)\`
- Build fingerprint: \`$(prop ro.build.fingerprint)\`

App:

- Package: \`$package_name\`
- Snapshot mode: \`$snapshot_mode\`
- Database: \`$database_status\`
- Database bytes: \`$database_bytes\`
- OBD logs: \`$logs_status\`

Benchmarks:

- Real DB benchmark: \`$real_db_status\`
- Startup benchmark: \`$startup_status\`
- Startup + tabs benchmark: \`$tab_status\`

Key files:

- Raw device facts: \`$raw_dir\`
- Pulled data: \`$pulled_dir\`
- Real DB summary: \`$report_dir/real-db-benchmark-summary.md\`
- Real DB JSON: \`$report_dir/real-db-benchmark-result.json\`
- Startup summary: \`$report_dir/startup-benchmark-summary.md\`
- Startup + tabs summary: \`$report_dir/tab-benchmark-summary.md\`

Notes:

- Source app data is read through \`run-as\`; the device database is not modified.
- By default the app is force-stopped before the database copy so the snapshot is coherent; set \`VOLTTRACKER_KEEP_APP_RUNNING=1\` to skip that.
- Set \`VOLTTRACKER_SKIP_STARTUP_BENCHMARK=1\` to collect data without launching the safe ADB startup benchmark.
- Set \`VOLTTRACKER_SKIP_TAB_BENCHMARK=1\` to collect data without startup-to-tab timing.
- Set \`VOLTTRACKER_PACKAGE\` or \`VOLTTRACKER_DB_NAME\` to override package/database names.
EOF

echo "$report_dir/summary.md"
