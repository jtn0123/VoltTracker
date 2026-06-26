#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
android_dir="$(cd "$script_dir/.." && pwd)"
timestamp="$(date -u +"%Y%m%dT%H%M%SZ")"
report_dir="$android_dir/build/reports/perf-local/$timestamp"
raw_dir="$report_dir/raw"

device="${VOLTTRACKER_ADB_SERIAL:-}"
db_path="${VOLTTRACKER_REAL_DB_PATH:-}"
synthetic_db_path="${VOLTTRACKER_SYNTHETIC_DB_PATH:-}"
synthetic_sessions="${VOLTTRACKER_SYNTHETIC_SESSIONS:-500}"
synthetic_samples="${VOLTTRACKER_SYNTHETIC_SAMPLES_PER_SESSION:-2000}"
run_startup=1
run_tabs=1
run_device_baseline=0
run_real_db=0
run_synthetic_db=0

usage() {
  cat <<'EOF'
usage: tools/perf-local.sh [options]

Options:
  --device SERIAL_OR_IP_PORT    Authorized adb serial or wireless debugging endpoint.
  --db PATH                     Optional local VoltTracker SQLite database copy.
  --synthetic-db PATH           Generate a privacy-safe DB, then benchmark it.
  --synthetic-sessions N        Synthetic DB sessions (default: 500).
  --synthetic-samples N         Synthetic DB samples per session (default: 2000).
  --startup-only                Run startup benchmark only.
  --tabs-only                   Run startup-to-tabs benchmark only.
  --real-db-only                Run real database benchmark only.
  --device-baseline             Run device-baseline capture instead of standalone startup/tabs.
  --all                         Run startup, tabs, optional real DB, and device baseline.
  -h, --help                    Show this help.

The wrapper never installs, uninstalls, clears app data, or targets another package.
Private raw captures stay under build/reports/ and should not be committed.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --device)
      device="${2:-}"
      shift 2
      ;;
    --db)
      db_path="${2:-}"
      run_real_db=1
      shift 2
      ;;
    --synthetic-db)
      synthetic_db_path="${2:-}"
      run_synthetic_db=1
      run_real_db=1
      shift 2
      ;;
    --synthetic-sessions)
      synthetic_sessions="${2:-500}"
      shift 2
      ;;
    --synthetic-samples)
      synthetic_samples="${2:-2000}"
      shift 2
      ;;
    --startup-only)
      run_startup=1
      run_tabs=0
      run_device_baseline=0
      run_real_db=0
      shift
      ;;
    --tabs-only)
      run_startup=0
      run_tabs=1
      run_device_baseline=0
      run_real_db=0
      shift
      ;;
    --real-db-only)
      run_startup=0
      run_tabs=0
      run_device_baseline=0
      run_real_db=1
      shift
      ;;
    --device-baseline)
      run_startup=0
      run_tabs=0
      run_device_baseline=1
      shift
      ;;
    --all)
      run_startup=1
      run_tabs=1
      run_device_baseline=1
      if [[ -n "$db_path" ]]; then
        run_real_db=1
      fi
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

mkdir -p "$raw_dir"

echo "VoltTracker local perf run: $timestamp"
echo "Report dir: $report_dir"
echo "Device: ${device:-"(first ready adb device)"}"
echo "Database: ${db_path:-"(none)"}"
echo "Synthetic database: ${synthetic_db_path:-"(none)"}"

startup_summary=""
tabs_summary=""
real_db_summary=""
synthetic_db_summary=""
device_baseline_summary=""

if [[ "$run_startup" == "1" ]]; then
  set +e
  startup_summary="$(bash "$script_dir/benchmark-adb-startup-local.sh" "$device" 2>&1 | tee "$raw_dir/startup.log" | tail -n 1)"
  startup_rc=${PIPESTATUS[0]}
  set -e
  if [[ "$startup_rc" -ne 0 ]]; then
    echo "startup benchmark exited $startup_rc; see $raw_dir/startup.log"
  fi
fi

if [[ "$run_tabs" == "1" ]]; then
  set +e
  tabs_summary="$(bash "$script_dir/benchmark-adb-tabs-local.sh" "$device" 2>&1 | tee "$raw_dir/tabs.log" | tail -n 1)"
  tabs_rc=${PIPESTATUS[0]}
  set -e
  if [[ "$tabs_rc" -ne 0 ]]; then
    echo "tab benchmark exited $tabs_rc; see $raw_dir/tabs.log"
  fi
fi

if [[ "$run_synthetic_db" == "1" ]]; then
  if [[ -z "$synthetic_db_path" ]]; then
    echo "--synthetic-db requires an output path" >&2
    exit 2
  fi
  set +e
  synthetic_db_summary="$(bash "$script_dir/generate-synthetic-db-local.sh" "$synthetic_db_path" "$synthetic_sessions" "$synthetic_samples" 2>&1 | tee "$raw_dir/synthetic-db.log" | tail -n 1)"
  synthetic_db_rc=${PIPESTATUS[0]}
  set -e
  if [[ "$synthetic_db_rc" -ne 0 ]]; then
    echo "synthetic DB generator exited $synthetic_db_rc; see $raw_dir/synthetic-db.log"
  fi
  db_path="$synthetic_db_path"
fi

if [[ "$run_real_db" == "1" ]]; then
  if [[ -z "$db_path" || ! -f "$db_path" ]]; then
    echo "--db is required for real database benchmark" >&2
    exit 2
  fi
  set +e
  real_db_summary="$(bash "$script_dir/benchmark-real-db-local.sh" "$db_path" 2>&1 | tee "$raw_dir/real-db.log" | tail -n 1)"
  real_db_rc=${PIPESTATUS[0]}
  set -e
  if [[ "$real_db_rc" -ne 0 ]]; then
    echo "real DB benchmark exited $real_db_rc; see $raw_dir/real-db.log"
  fi
fi

if [[ "$run_device_baseline" == "1" ]]; then
  set +e
  device_baseline_summary="$(bash "$script_dir/device-baseline-local.sh" "$device" 2>&1 | tee "$raw_dir/device-baseline.log" | tail -n 1)"
  device_baseline_rc=${PIPESTATUS[0]}
  set -e
  if [[ "$device_baseline_rc" -ne 0 ]]; then
    echo "device baseline exited $device_baseline_rc; see $raw_dir/device-baseline.log"
  fi
fi

python3 "$script_dir/perf/report_index.py" \
  --output "$report_dir/index.md" \
  --entry "startup=${startup_summary:-$report_dir/missing-startup.md}" \
  --entry "tabs=${tabs_summary:-$report_dir/missing-tabs.md}" \
  --entry "synthetic-db=${synthetic_db_summary:-$report_dir/missing-synthetic-db.md}" \
  --entry "real-db=${real_db_summary:-$report_dir/missing-real-db.md}" \
  --entry "device-baseline=${device_baseline_summary:-$report_dir/missing-device-baseline.md}"

echo "$report_dir/index.md"
