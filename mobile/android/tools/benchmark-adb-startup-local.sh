#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source-path=SCRIPTDIR source=benchmark-adb-target.sh
source "$script_dir/benchmark-adb-target.sh"

usage() {
  cat <<'EOF'
usage: tools/benchmark-adb-startup-local.sh [options] [ADB_SERIAL_OR_IP:PORT] [RUNS]

Cold-starts the installed app RUNS times (default 5) with `am start -W` and reports launch,
ready and StartupTrace timings. Never installs, uninstalls, clears app data, or targets
another package.

EOF
  vt_target_usage
}

parse_rc=0
vt_parse_benchmark_args "$@" || parse_rc=$?
if [[ "$parse_rc" -eq 3 ]]; then
  usage
  exit 0
elif [[ "$parse_rc" -ne 0 ]]; then
  usage >&2
  exit 2
fi
package_name="$VT_PACKAGE"
if [[ -n "${VOLTTRACKER_COMPONENT:-}" ]]; then
  vt_check_component_override "$package_name" "$VOLTTRACKER_COMPONENT" || exit 2
fi

target="${VT_ARGS[0]:-${VOLTTRACKER_ADB_SERIAL:-}}"
runs="${VOLTTRACKER_STARTUP_RUNS:-${VT_ARGS[1]:-5}}"
ready_mark="$(vt_ready_mark "$VT_DASHBOARD")"
ready_text="${VOLTTRACKER_READY_TEXT:-$(vt_ready_probe "$VT_DASHBOARD")}"
ready_timeout_sec="${VOLTTRACKER_STARTUP_READY_TIMEOUT_SEC:-30}"
trace_tag="${VOLTTRACKER_STARTUP_TRACE_TAG:-$VT_TRACE_TAG}"
wake_device="${VOLTTRACKER_STARTUP_WAKE_DEVICE:-1}"

android_dir="$(cd "$script_dir/.." && pwd)"
timestamp="$(date -u +"%Y%m%dT%H%M%SZ")"
report_dir="$android_dir/build/reports/adb-startup-benchmark/$timestamp"
raw_dir="$report_dir/raw"
trend_dir="$android_dir/build/reports/performance-trends"

mkdir -p "$raw_dir" "$trend_dir"

pick_ready_device() {
  adb devices | awk 'NR > 1 && $2 == "device" { print $1; exit }'
}

device_is_ready() {
  [[ -n "$1" ]] && [[ "$(adb -s "$1" get-state 2>/dev/null | tr -d '\r')" == "device" ]]
}

now_ms() {
  perl -MTime::HiRes=time -e 'printf "%.0f\n", time() * 1000'
}

wake_for_benchmark() {
  if [[ "$wake_device" != "1" ]]; then
    return
  fi
  adb -s "$serial" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  adb -s "$serial" shell wm dismiss-keyguard >/dev/null 2>&1 || true
}

parse_am_field() {
  local file="$1"
  local field="$2"
  awk -F: -v field="$field" '$1 ~ field { gsub(/[ \r]/, "", $2); print $2; exit }' "$file"
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
# VoltTracker ADB Startup Benchmark

Status: \`no-ready-device\`

Target: \`${target:-"(first ready adb device)"}\`

No authorized ADB device was available.
EOF
  echo "$report_dir/summary.md"
  exit 2
fi

adb -s "$serial" devices -l >"$raw_dir/adb-devices.txt" 2>&1 || true
adb -s "$serial" shell pm path "$package_name" >"$raw_dir/pm-path.txt" 2>&1 || true
if ! component="$(vt_resolve_component "$serial" "$package_name" "$VT_DASHBOARD" "${VOLTTRACKER_COMPONENT:-}" \
  "$raw_dir/resolve-activity.txt" 2>"$raw_dir/resolve-error.txt")"; then
  cat "$raw_dir/resolve-error.txt" >&2
  cat >"$report_dir/summary.md" <<EOF
# VoltTracker ADB Startup Benchmark

Status: \`no-launch-component\`

- Serial: \`$serial\`
- Package: \`$package_name\` ($VT_VARIANT)
- Dashboard: \`$VT_DASHBOARD\`

$(cat "$raw_dir/resolve-error.txt")
EOF
  echo "$report_dir/summary.md"
  exit 2
fi

runs_csv="$report_dir/startup-runs.csv"
marks_csv="$report_dir/startup-marks.csv"
jsonl_file="$report_dir/startup-runs.jsonl"
summary_file="$report_dir/summary.md"
trend_file="$trend_dir/adb-startup-benchmark.jsonl"

printf 'run,amThisTimeMs,amTotalTimeMs,amWaitTimeMs,hostAmStartMs,hostStartToDashboardReadyMs,readyFound,readySource,amStartExitCode\n' >"$runs_csv"
printf 'run,mark,elapsedMs\n' >"$marks_csv"
: >"$jsonl_file"

for run in $(seq 1 "$runs"); do
  adb -s "$serial" logcat -c >/dev/null 2>&1 || true
  wake_for_benchmark
  adb -s "$serial" shell am force-stop "$package_name" >/dev/null 2>&1 || true
  sleep 1

  am_start_file="$raw_dir/run-$run-am-start.txt"
  start_ms="$(now_ms)"
  set +e
  adb -s "$serial" shell am start -W -n "$component" >"$am_start_file" 2>&1
  am_rc="$?"
  set -e
  am_done_ms="$(now_ms)"
  wake_for_benchmark
  tr -d '\r' <"$am_start_file" >"$am_start_file.clean"
  mv "$am_start_file.clean" "$am_start_file"

  ready_found=0
  ready_source="not-found"
  ready_ms=""
  dump_tmp="$raw_dir/run-$run-uiautomator-last.xml"
  deadline_ms=$((start_ms + ready_timeout_sec * 1000))
  while [[ "$(now_ms)" -lt "$deadline_ms" ]]; do
    if adb -s "$serial" logcat -d -s "$trace_tag:I" '*:S' 2>/dev/null |
      grep -F "mark=$ready_mark " >/dev/null; then
        ready_found=1
        ready_source="logcat"
        ready_ms=$(( $(now_ms) - start_ms ))
        break
    fi
    if adb -s "$serial" exec-out uiautomator dump /dev/tty 2>/dev/null | tr -d '\r' >"$dump_tmp"; then
      if grep -F "$ready_text" "$dump_tmp" >/dev/null; then
        ready_found=1
        ready_source="uiautomator"
        ready_ms=$(( $(now_ms) - start_ms ))
        break
      fi
    fi
    sleep 0.25
  done

  sleep 0.5
  log_file="$raw_dir/run-$run-logcat-$trace_tag.txt"
  adb -s "$serial" logcat -d -v time -s "$trace_tag:I" '*:S' >"$log_file" 2>&1 || true
  sed -nE "s/.*mark=([^ ]+) elapsedMs=([0-9]+).*/$run,\1,\2/p" "$log_file" >>"$marks_csv"

  am_this="$(parse_am_field "$am_start_file" ThisTime || true)"
  am_total="$(parse_am_field "$am_start_file" TotalTime || true)"
  am_wait="$(parse_am_field "$am_start_file" WaitTime || true)"
  host_am=$((am_done_ms - start_ms))
  printf '%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
    "$run" "$am_this" "$am_total" "$am_wait" "$host_am" "$ready_ms" "$ready_found" "$ready_source" "$am_rc" >>"$runs_csv"
done

python_bin="${PYTHON:-python3}"
if command -v "$python_bin" >/dev/null 2>&1; then
  "$python_bin" - "$report_dir" "$trend_file" "$timestamp" "$serial" "$package_name" "$component" "$ready_text" "$runs" \
    "$VT_DASHBOARD" "$VT_VARIANT" "$ready_mark" <<'PY'
import csv
import json
import statistics
import sys
from collections import Counter, defaultdict
from pathlib import Path

report_dir = Path(sys.argv[1])
trend_file = Path(sys.argv[2])
timestamp, serial, package_name, component, ready_text, requested_runs, dashboard, variant, ready_mark = sys.argv[3:12]
runs_csv = report_dir / "startup-runs.csv"
marks_csv = report_dir / "startup-marks.csv"
jsonl_file = report_dir / "startup-runs.jsonl"
summary_file = report_dir / "summary.md"

def to_int(value):
    if value is None or value == "":
        return None
    try:
        return int(value)
    except ValueError:
        return None

def stats(values):
    clean = sorted(v for v in values if v is not None)
    if not clean:
        return None
    return {
        "min": clean[0],
        "median": round(statistics.median(clean)),
        "max": clean[-1],
        "count": len(clean),
    }

def stat_cells(values):
    item = stats(values)
    if item is None:
        return ["n/a", "n/a", "n/a", "0"]
    return [str(item["min"]), str(item["median"]), str(item["max"]), str(item["count"])]

run_rows = []
with runs_csv.open(newline="") as handle:
    for row in csv.DictReader(handle):
        run_rows.append(row)

mark_events_by_run = defaultdict(list)
mark_counts_by_run = defaultdict(lambda: defaultdict(int))
with marks_csv.open(newline="") as handle:
    for row in csv.DictReader(handle):
        run = row["run"]
        mark = row["mark"]
        elapsed = to_int(row["elapsedMs"])
        if elapsed is not None:
            mark_events_by_run[run].append((mark, elapsed))
            mark_counts_by_run[run][mark] += 1

marks_first_by_run = defaultdict(dict)
marks_last_by_run = defaultdict(dict)
for run, events in mark_events_by_run.items():
    for mark, elapsed in events:
        marks_first_by_run[run].setdefault(mark, elapsed)
        marks_last_by_run[run][mark] = elapsed

with jsonl_file.open("w") as handle:
    for row in run_rows:
        run = row["run"]
        payload = {
            "run": int(run),
            "amThisTimeMs": to_int(row["amThisTimeMs"]),
            "amTotalTimeMs": to_int(row["amTotalTimeMs"]),
            "amWaitTimeMs": to_int(row["amWaitTimeMs"]),
            "hostAmStartMs": to_int(row["hostAmStartMs"]),
            "hostStartToDashboardReadyMs": to_int(row["hostStartToDashboardReadyMs"]),
            "readyFound": row["readyFound"] == "1",
            "readySource": row.get("readySource", ""),
            "amStartExitCode": to_int(row["amStartExitCode"]),
            "startupMarkEvents": [
                {"mark": mark, "elapsedMs": elapsed}
                for mark, elapsed in mark_events_by_run.get(run, [])
            ],
            "startupMarksFirst": marks_first_by_run.get(run, {}),
            "startupMarksLast": marks_last_by_run.get(run, {}),
        }
        handle.write(json.dumps(payload, sort_keys=True) + "\n")

host_metric_rows = [
    ("amThisTimeMs", "Android reported activity draw time"),
    ("amTotalTimeMs", "Android reported total launch time"),
    ("amWaitTimeMs", "Android reported wait time"),
    ("hostAmStartMs", "Host time spent in am start -W"),
    ("hostStartToDashboardReadyMs", "Host time until dashboard-ready probe"),
]

def matches(mark, name):
    # Session marks append ":<mode>" (obd_first_sample:live, obd_connect_request:obd).
    return mark == name or mark.startswith(name + ":")

def first_mark(run, name):
    for mark, elapsed in mark_events_by_run.get(run, []):
        if matches(mark, name):
            return elapsed
    return None

# StartupTrace.OBD_* marks; only present when the app connects or starts the demo during the run.
obd_metric_rows = [
    ("obdFirstSampleMs", "obd_first_sample", "OBD mark: first sample decoded (live or demo)"),
]
obd_critical_marks = [
    "obd_connect_request",
    "obd_socket_connected",
    "obd_elm_init_start",
    "obd_elm_init_end",
    "obd_first_sample",
]
obd_span_defs = [
    ("OBD connect request to socket", "obd_connect_request", "obd_socket_connected"),
    ("OBD ELM init", "obd_elm_init_start", "obd_elm_init_end"),
    ("OBD connect request to first sample", "obd_connect_request", "obd_first_sample"),
]

if dashboard == "native":
    # ComposeDashboardActivity logs compose_screen:<view> when a screen composes; a fresh start
    # composes Drive. Its first telemetry mark needs a live or demo session.
    app_metric_rows = [
        ("appOnCreateCompleteMs", "app_on_create_complete", "App mark: Application.onCreate finished"),
        ("appDashboardReadyProbeMs", ready_mark, "App mark: native Drive screen composed (ready)"),
        ("appFirstTelemetryMs", "compose_first_telemetry", "App mark: first telemetry reached the native screens"),
    ] + obd_metric_rows
    critical_marks = [
        "reset:app_on_create",
        "app_on_create_complete",
        ready_mark,
        "compose_first_telemetry",
    ] + obd_critical_marks
    span_defs = [
        ("Application onCreate to Drive composed", "app_on_create_complete", ready_mark),
        ("Drive composed to first telemetry", ready_mark, "compose_first_telemetry"),
    ] + obd_span_defs
else:
    app_metric_rows = [
        ("appJsBootstrapStartMs", "js:actions_bootstrap_start", "App mark: dashboard JS started"),
        ("appInitialRenderMs", "js:actions_initial_render_done", "App mark: initial Drive DOM render done"),
        ("appFirstFrameMs", "js:actions_first_frame", "App mark: first dashboard frame completed"),
        ("appDashboardReadyProbeMs", ready_mark, "App mark: native ready probe set"),
        ("appDashboardReadyNativeCompleteMs", "dashboard_ready_native_complete", "App mark: native ready callback returned"),
    ] + obd_metric_rows
    critical_marks = [
        "reset:app_on_create",
        "app_on_create_complete",
        "activity_on_create_start",
        "local_store_open_end",
        "webview_create_end",
        "webview_configure_end",
        "webview_load_url_end",
        "webview_page_started",
        "webview_page_commit_visible",
        "webview_page_finished",
        "js:actions_bootstrap_start",
        "js:actions_bind_listeners_done",
        "js:actions_initial_render_done",
        "bridge_refresh_devices_called",
        "bridge_refresh_devices_ui_end",
        "bridge_dashboard_ready_called",
        ready_mark,
        "dashboard_ready_native_complete",
        "storage_summary_read_start",
        "storage_summary_read_end",
        "storage_summary_publish_end",
    ] + obd_critical_marks
    span_defs = [
        ("Activity onCreate", "activity_on_create_start", "activity_on_create_end"),
        ("Open local SQLite store", "local_store_open_start", "local_store_open_end"),
        ("Create WebView", "webview_create_start", "webview_create_end"),
        ("Configure WebView", "webview_configure_start", "webview_configure_end"),
        ("WebView load to page start", "webview_load_url_start", "webview_page_started"),
        ("WebView load to visible commit", "webview_load_url_start", "webview_page_commit_visible"),
        ("Page start to finish", "webview_page_started", "webview_page_finished"),
        ("Load URL to JS bootstrap", "webview_load_url_start", "js:actions_bootstrap_start"),
        ("JS bootstrap to listeners bound", "js:actions_bootstrap_start", "js:actions_bind_listeners_done"),
        ("JS bootstrap to initial render", "js:actions_bootstrap_start", "js:actions_initial_render_done"),
        ("Initial render to ready bridge", "js:actions_initial_render_done", "bridge_dashboard_ready_called"),
        ("Refresh devices bridge/UI", "bridge_refresh_devices_called", "bridge_refresh_devices_ui_end"),
        ("Publish device list", "publish_device_list_start", "publish_device_list_end"),
        ("Ready bridge to probe", "bridge_dashboard_ready_called", ready_mark),
        ("Ready native completion", "dashboard_ready_bridge_start", "dashboard_ready_native_complete"),
        ("Storage summary read", "storage_summary_read_start", "storage_summary_read_end"),
        ("Storage summary publish", "storage_summary_publish_start", "storage_summary_publish_end"),
    ] + obd_span_defs

span_rows = []
for label, start, end in span_defs:
    values = []
    for events in mark_events_by_run.values():
        pending = []
        for mark, elapsed in events:
            if matches(mark, start):
                pending.append(elapsed)
            elif matches(mark, end) and pending:
                start_elapsed = pending.pop(0)
                values.append(elapsed - start_elapsed)
    item = stats(values)
    if item is not None:
        span_rows.append((label, start, end, item, values))

span_rows.sort(key=lambda row: row[3]["median"], reverse=True)

metric_summary = {}
for name, _note in host_metric_rows:
    metric_summary[name] = stats(to_int(row.get(name)) for row in run_rows)
for name, mark, _note in app_metric_rows:
    metric_summary[name] = stats(first_mark(row["run"], mark) for row in run_rows)
span_summary = {
    label: {
        "marks": [start, end],
        "stats": item,
    }
    for label, start, end, item, _values in span_rows
}

previous = None
requested_runs_int = int(requested_runs)
ready_source_counts = dict(Counter(row.get("readySource", "") for row in run_rows))
if trend_file.exists():
    for line in trend_file.read_text(encoding="utf-8", errors="ignore").splitlines():
        try:
            item = json.loads(line)
        except json.JSONDecodeError:
            continue
        if (
            item.get("serial") == serial and
            item.get("package") == package_name and
            # Trend lines written before the native launcher measured the classic dashboard.
            item.get("dashboard", "classic") == dashboard and
            item.get("requestedRuns") == requested_runs_int and
            sum(item.get("readySourceCounts", {}).values()) == requested_runs_int
        ):
            previous = item

warnings = []
if previous:
    prev_metric_summary = previous.get("metrics", {})
    comparable_metrics = ["amTotalTimeMs", "appFirstFrameMs", "appDashboardReadyProbeMs"]
    if previous.get("readySourceCounts") == ready_source_counts:
        comparable_metrics.append("hostStartToDashboardReadyMs")
    for metric in comparable_metrics:
        current_stat = metric_summary.get(metric)
        prev_stat = prev_metric_summary.get(metric)
        current = current_stat.get("median") if isinstance(current_stat, dict) else None
        old = prev_stat.get("median") if isinstance(prev_stat, dict) else None
        if current is None or old in (None, 0):
            continue
        if current >= old * 1.20 and current - old >= 100:
            warnings.append({
                "metric": metric,
                "previousMedianMs": old,
                "currentMedianMs": current,
                "deltaMs": current - old,
            })

trend_payload = {
    "timestamp": timestamp,
    "serial": serial,
    "package": package_name,
    "variant": variant,
    "dashboard": dashboard,
    "component": component,
    "readyMark": ready_mark,
    "readyText": ready_text,
    "requestedRuns": requested_runs_int,
    "report": str(summary_file),
    "metrics": metric_summary,
    "spans": span_summary,
    "readySourceCounts": ready_source_counts,
    "regressionWarnings": warnings,
}
with trend_file.open("a", encoding="utf-8") as handle:
    handle.write(json.dumps(trend_payload, sort_keys=True) + "\n")

lines = [
    "# VoltTracker ADB Startup Benchmark",
    "",
    "Status: `completed`" if not warnings else "Status: `completed-with-regression-warning`",
    "",
    f"Generated: `{timestamp}`",
    "",
    f"- Serial: `{serial}`",
    f"- Package: `{package_name}` ({variant})",
    f"- Dashboard: `{dashboard}`",
    f"- Component: `{component}`",
    f"- Requested runs: `{requested_runs}`",
    f"- Ready mark: `{ready_mark}`",
    f"- Ready probe: `{ready_text}`",
    "",
    "This benchmark uses `am force-stop`, `am start -W`, `uiautomator dump`, and logcat.",
    "It does not install, uninstall, clear app data, or target any package other than the selected",
    "`com.volttracker.obdpoc` (release) or `com.volttracker.obdpoc.debug` (debug) app.",
    "By default it wakes the device and asks Android to dismiss keyguard before each run; set `VOLTTRACKER_STARTUP_WAKE_DEVICE=0` to skip that.",
    "It checks the in-app `VoltStartup` ready mark before the slower `uiautomator` accessibility dump.",
    "`hostStartToDashboardReadyMs` is host-observed; `app*` metrics are app-reported and are the better optimization target.",
]
if variant == "release":
    lines += [
        "Release builds log no `VoltStartup` marks, so `app*` metrics stay empty and readiness comes",
        "from the accessibility dump alone.",
    ]
lines += [
    "",
    "## Host Run Metrics",
    "",
    "| Metric | Min ms | Median ms | Max ms | Runs | Notes |",
    "|---|---:|---:|---:|---:|---|",
]

for name, note in host_metric_rows:
    values = [to_int(row.get(name)) for row in run_rows]
    cells = stat_cells(values)
    lines.append(f"| `{name}` | {cells[0]} | {cells[1]} | {cells[2]} | {cells[3]} | {note} |")

lines += [
    "",
    "## App-Reported Startup Metrics",
    "",
    "| Metric | Min ms | Median ms | Max ms | Runs | Notes |",
    "|---|---:|---:|---:|---:|---|",
]

for name, mark, note in app_metric_rows:
    values = [first_mark(row["run"], mark) for row in run_rows]
    cells = stat_cells(values)
    lines.append(f"| `{name}` | {cells[0]} | {cells[1]} | {cells[2]} | {cells[3]} | {note} |")

lines += [
    "",
    "## Largest Spans",
    "",
    "| Span | Min ms | Median ms | Max ms | Samples | Marks |",
    "|---|---:|---:|---:|---:|---|",
]
for label, start, end, item, _values in span_rows:
    lines.append(
        f"| {label} | {item['min']} | {item['median']} | {item['max']} | "
        f"{item['count']} | `{start}` -> `{end}` |"
    )

lines += [
    "",
    "## First Startup Marks",
    "",
    "| Mark | Min ms | Median ms | Max ms | Runs |",
    "|---|---:|---:|---:|---:|",
]

all_marks = sorted({mark for events in mark_events_by_run.values() for mark, _elapsed in events})
ordered_marks = []
for name in critical_marks + all_marks:
    for mark in all_marks:
        if matches(mark, name) and mark not in ordered_marks:
            ordered_marks.append(mark)

for mark in ordered_marks:
    values = [marks_first_by_run.get(row["run"], {}).get(mark) for row in run_rows]
    cells = stat_cells(values)
    lines.append(f"| `{mark}` | {cells[0]} | {cells[1]} | {cells[2]} | {cells[3]} |")

repeated_marks = []
for mark in all_marks:
    counts = [mark_counts_by_run.get(row["run"], {}).get(mark, 0) for row in run_rows]
    if any(count > 1 for count in counts):
        item = stats(counts)
        if item is not None:
            repeated_marks.append((mark, item))

if repeated_marks:
    lines += [
        "",
        "## Repeated Marks",
        "",
        "| Mark | Min count | Median count | Max count | Runs |",
        "|---|---:|---:|---:|---:|",
    ]
    for mark, item in repeated_marks:
        lines.append(
            f"| `{mark}` | {item['min']} | {item['median']} | {item['max']} | {item['count']} |"
        )

if warnings:
    lines += [
        "",
        "## Regression Warnings",
        "",
        "| Metric | Previous median ms | Current median ms | Delta ms |",
        "|---|---:|---:|---:|",
    ]
    for warning in warnings:
        lines.append(
            f"| `{warning['metric']}` | {warning['previousMedianMs']} | "
            f"{warning['currentMedianMs']} | {warning['deltaMs']} |"
        )

if previous:
    lines += [
        "",
        "## Previous Comparable Run",
        "",
        f"- Timestamp: `{previous.get('timestamp', 'unknown')}`",
        f"- Report: `{previous.get('report', 'unknown')}`",
    ]

lines += [
    "",
    "## Files",
    "",
    f"- Per-run CSV: `{runs_csv}`",
    f"- Parsed mark CSV: `{marks_csv}`",
    f"- Per-run JSONL: `{jsonl_file}`",
    f"- Trend JSONL: `{trend_file}`",
    f"- Raw command output and logcat: `{report_dir / 'raw'}`",
    "",
]

summary_file.write_text("\n".join(lines), encoding="utf-8")
PY
else
  cat >"$summary_file" <<EOF
# VoltTracker ADB Startup Benchmark

Status: \`completed\`

Generated: \`$timestamp\`

Python was not available, so detailed summary generation was skipped.

- Serial: \`$serial\`
- Package: \`$package_name\` ($VT_VARIANT)
- Dashboard: \`$VT_DASHBOARD\`
- Component: \`$component\`
- Runs CSV: \`$runs_csv\`
- Marks CSV: \`$marks_csv\`
- Raw output: \`$raw_dir\`
EOF
fi

echo "$summary_file"
