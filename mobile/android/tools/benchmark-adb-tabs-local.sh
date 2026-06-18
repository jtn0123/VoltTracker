#!/usr/bin/env bash
set -euo pipefail

package_name="com.volttracker.obdpoc"
if [[ -n "${VOLTTRACKER_PACKAGE:-}" && "${VOLTTRACKER_PACKAGE}" != "$package_name" ]]; then
  echo "Refusing to benchmark package '${VOLTTRACKER_PACKAGE}'. This script only targets $package_name." >&2
  exit 2
fi

target="${1:-${VOLTTRACKER_ADB_SERIAL:-}}"
runs="${VOLTTRACKER_TAB_RUNS:-${2:-3}}"
tabs_text="${VOLTTRACKER_TAB_TARGETS:-map charge insights diagnostics settings}"
component="${VOLTTRACKER_COMPONENT:-$package_name/.MainActivity}"
ready_text="${VOLTTRACKER_READY_TEXT:-VoltTracker dashboard ready}"
ready_timeout_sec="${VOLTTRACKER_TAB_READY_TIMEOUT_SEC:-30}"
tab_timeout_sec="${VOLTTRACKER_TAB_SWITCH_TIMEOUT_SEC:-10}"
trace_tag="${VOLTTRACKER_STARTUP_TRACE_TAG:-VoltStartup}"
wake_device="${VOLTTRACKER_STARTUP_WAKE_DEVICE:-1}"
tap_strategy="${VOLTTRACKER_TAB_TAP_STRATEGY:-coordinate}"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
android_dir="$(cd "$script_dir/.." && pwd)"
timestamp="$(date -u +"%Y%m%dT%H%M%SZ")"
report_dir="$android_dir/build/reports/adb-tab-benchmark/$timestamp"
raw_dir="$report_dir/raw"
trend_dir="$android_dir/build/reports/performance-trends"

mkdir -p "$raw_dir" "$trend_dir"

read -r -a tabs <<<"$tabs_text"

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

tab_index() {
  case "$1" in
    drive) echo "0" ;;
    map) echo "1" ;;
    charge) echo "2" ;;
    insights) echo "3" ;;
    diagnostics|diag) echo "4" ;;
    settings) echo "5" ;;
    *) return 1 ;;
  esac
}

tab_nav_desc() {
  case "$1" in
    drive) echo "Drive" ;;
    map) echo "Map" ;;
    charge) echo "Charge" ;;
    insights) echo "Insights" ;;
    diagnostics|diag) echo "Diag" ;;
    settings) echo "Settings" ;;
    *) echo "$1" ;;
  esac
}

tab_title() {
  case "$1" in
    drive) echo "Drive" ;;
    map) echo "Map" ;;
    charge) echo "Charge" ;;
    insights) echo "Insights" ;;
    diagnostics|diag) echo "Diagnostics" ;;
    settings) echo "Settings" ;;
    *) echo "$1" ;;
  esac
}

dump_ui() {
  local file="$1"
  mkdir -p "$(dirname "$file")"
  adb -s "$serial" exec-out uiautomator dump /dev/tty 2>/dev/null | tr -d '\r' >"$file"
}

screen_width=""
screen_height=""

center_by_tab_screen() {
  local tab="$1"
  local index
  index="$(tab_index "$tab")" || return 1
  if [[ -z "$screen_width" || -z "$screen_height" ]]; then
    return 1
  fi
  local cx=$(( (screen_width * ((index * 2) + 1) + 6) / 12 ))
  local cy=$(( (screen_height * 954 + 500) / 1000 ))
  echo "$cx $cy"
}

xml_query() {
  local mode="$1"
  local file="$2"
  local arg="$3"
  python3 - "$mode" "$file" "$arg" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

mode, file_name, arg = sys.argv[1:4]
text = Path(file_name).read_text(errors="ignore")
end = text.find("</hierarchy>")
if end >= 0:
    text = text[: end + len("</hierarchy>")]

try:
    root = ET.fromstring(text)
except ET.ParseError:
    sys.exit(1)

def bounds(node):
    raw = node.attrib.get("bounds", "")
    nums = [int(item) for item in re.findall(r"-?\d+", raw)]
    if len(nums) != 4:
        return None
    x1, y1, x2, y2 = nums
    if x2 <= x1 or y2 <= y1:
        return None
    return x1, y1, x2, y2

if mode == "center-by-desc":
    candidates = []
    for node in root.iter("node"):
        if node.attrib.get("content-desc") != arg:
            continue
        if node.attrib.get("clickable") != "true":
            continue
        box = bounds(node)
        if not box:
            continue
        x1, y1, x2, y2 = box
        candidates.append((y1, x1, (x1 + x2) // 2, (y1 + y2) // 2))
    if not candidates:
        sys.exit(1)
    _y, _x, cx, cy = sorted(candidates)[0]
    print(f"{cx} {cy}")
elif mode == "center-by-tab":
    order = {
        "drive": 0,
        "map": 1,
        "charge": 2,
        "insights": 3,
        "diagnostics": 4,
        "diag": 4,
        "settings": 5,
    }
    if arg not in order:
        sys.exit(1)
    root_box = bounds(root.find("node")) or (0, 0, 1440, 3120)
    x1, y1, x2, y2 = root_box
    width = x2 - x1
    height = y2 - y1
    cx = x1 + round(width * ((order[arg] * 2) + 1) / 12)
    cy = y1 + round(height * 0.954)
    print(f"{cx} {cy}")
elif mode == "visible-title":
    for node in root.iter("node"):
        if node.attrib.get("text") != arg:
            continue
        box = bounds(node)
        if not box:
            continue
        x1, y1, x2, y2 = box
        if y1 <= 500:
            print("1")
            sys.exit(0)
    sys.exit(1)
else:
    sys.exit(2)
PY
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
# VoltTracker ADB Tab Benchmark

Status: \`no-ready-device\`

Target: \`${target:-"(first ready adb device)"}\`

No authorized ADB device was available.
EOF
  echo "$report_dir/summary.md"
  exit 2
fi

screen_size="$(adb -s "$serial" shell wm size 2>/dev/null | tr -d '\r' | sed -nE 's/.*Physical size: ([0-9]+)x([0-9]+).*/\1 \2/p' | tail -n 1 || true)"
screen_width="${screen_size%% *}"
screen_height="${screen_size##* }"
if [[ "$screen_width" == "$screen_height" || -z "$screen_width" || -z "$screen_height" ]]; then
  screen_width=""
  screen_height=""
fi

adb -s "$serial" devices -l >"$raw_dir/adb-devices.txt" 2>&1 || true
adb -s "$serial" shell pm path "$package_name" >"$raw_dir/pm-path.txt" 2>&1

runs_csv="$report_dir/tab-runs.csv"
marks_csv="$report_dir/tab-marks.csv"
jsonl_file="$report_dir/tab-runs.jsonl"
summary_file="$report_dir/summary.md"
trend_file="$trend_dir/adb-tab-benchmark.jsonl"

printf 'target,run,amThisTimeMs,amTotalTimeMs,amWaitTimeMs,hostAmStartMs,hostStartToDashboardReadyMs,readyFound,readySource,readyToTapMs,tapHostMs,tapToTabReadyMs,hostStartToTabReadyMs,tabReadyFound,tabReadySource,tapX,tapY,amStartExitCode\n' >"$runs_csv"
printf 'target,run,mark,elapsedMs\n' >"$marks_csv"
: >"$jsonl_file"

for tab in "${tabs[@]}"; do
  nav_desc="$(tab_nav_desc "$tab")"
  title="$(tab_title "$tab")"
  for run in $(seq 1 "$runs"); do
    adb -s "$serial" logcat -c >/dev/null 2>&1 || true
    wake_for_benchmark
    adb -s "$serial" shell am force-stop "$package_name" >/dev/null 2>&1 || true
    sleep 1

    mkdir -p "$raw_dir"
    safe_tab="${tab//[^A-Za-z0-9_.-]/_}"
    am_start_file="$raw_dir/$safe_tab-run-$run-am-start.txt"
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
    ready_abs_ms=""
    ready_dump="$raw_dir/$safe_tab-run-$run-ready.xml"
    deadline_ms=$((start_ms + ready_timeout_sec * 1000))
    while [[ "$(now_ms)" -lt "$deadline_ms" ]]; do
      if adb -s "$serial" logcat -d -s "$trace_tag:I" '*:S' 2>/dev/null |
        grep -F "mark=dashboard_ready_content_description " >/dev/null; then
          ready_found=1
          ready_source="logcat"
          ready_abs_ms="$(now_ms)"
          ready_ms=$((ready_abs_ms - start_ms))
          if [[ "$tap_strategy" == "accessibility" ]]; then
            dump_ui "$ready_dump" || true
          fi
          break
      fi
      if dump_ui "$ready_dump"; then
        if grep -F "$ready_text" "$ready_dump" >/dev/null; then
          ready_found=1
          ready_source="uiautomator"
          ready_abs_ms="$(now_ms)"
          ready_ms=$((ready_abs_ms - start_ms))
          break
        fi
      fi
      sleep 0.25
    done

    tap_x=""
    tap_y=""
    ready_to_tap=""
    tap_host=""
    tap_to_tab=""
    tab_ready_ms=""
    tab_found=0
    tab_source="not-found"

    if [[ "$ready_found" == "1" ]]; then
      if [[ "$tab" == "drive" ]]; then
        tap_start_ms="$(now_ms)"
        if [[ ! -s "$ready_dump" ]]; then
          dump_ui "$ready_dump" || true
        fi
        if xml_query "visible-title" "$ready_dump" "$title" >/dev/null 2>&1; then
          tab_found=1
          tab_source="already-visible"
          tab_ready_abs_ms="$tap_start_ms"
          tap_to_tab=0
          tab_ready_ms=$((tab_ready_abs_ms - start_ms))
        fi
        ready_to_tap=$((tap_start_ms - ready_abs_ms))
        tap_host=0
      else
        center=""
        if [[ "$tap_strategy" == "coordinate" ]]; then
          center="$(center_by_tab_screen "$tab" 2>/dev/null || true)"
        fi
        if [[ -z "$center" ]]; then
          if [[ ! -s "$ready_dump" ]]; then
            dump_ui "$ready_dump" || true
          fi
          center="$(xml_query "center-by-desc" "$ready_dump" "$nav_desc" 2>/dev/null || true)"
        fi
        if [[ -z "$center" ]]; then
          center="$(xml_query "center-by-tab" "$ready_dump" "$tab")"
        fi
        tap_x="${center%% *}"
        tap_y="${center##* }"
        tap_start_ms="$(now_ms)"
        ready_to_tap=$((tap_start_ms - ready_abs_ms))
        adb -s "$serial" shell input tap "$tap_x" "$tap_y" >/dev/null 2>&1 || true
        tap_done_ms="$(now_ms)"
        tap_host=$((tap_done_ms - tap_start_ms))
        tab_deadline_ms=$((tap_start_ms + tab_timeout_sec * 1000))
        tab_dump="$raw_dir/$safe_tab-run-$run-tab.xml"
        tab_paint_mark="mark=js:tab:$tab:paint "
        while [[ "$(now_ms)" -lt "$tab_deadline_ms" ]]; do
          if adb -s "$serial" logcat -d -s "$trace_tag:I" '*:S' 2>/dev/null |
            grep -F "$tab_paint_mark" >/dev/null; then
              tab_found=1
              tab_source="logcat:tab-paint"
              tab_ready_abs_ms="$(now_ms)"
              tap_to_tab=$((tab_ready_abs_ms - tap_start_ms))
              tab_ready_ms=$((tab_ready_abs_ms - start_ms))
              break
          fi
          if dump_ui "$tab_dump"; then
            if xml_query "visible-title" "$tab_dump" "$title" >/dev/null 2>&1; then
              tab_found=1
              tab_source="screen-title:$title"
              tab_ready_abs_ms="$(now_ms)"
              tap_to_tab=$((tab_ready_abs_ms - tap_start_ms))
              tab_ready_ms=$((tab_ready_abs_ms - start_ms))
              break
            fi
          fi
          sleep 0.1
        done
      fi
    fi

    sleep 0.25
    log_file="$raw_dir/$safe_tab-run-$run-logcat-$trace_tag.txt"
    mkdir -p "$raw_dir" "$(dirname "$marks_csv")"
    adb -s "$serial" logcat -d -v time -s "$trace_tag:I" '*:S' >"$log_file" 2>&1 || true
    sed -nE "s/.*mark=([^ ]+) elapsedMs=([0-9]+).*/$tab,$run,\1,\2/p" "$log_file" >>"$marks_csv"

    am_this="$(parse_am_field "$am_start_file" ThisTime || true)"
    am_total="$(parse_am_field "$am_start_file" TotalTime || true)"
    am_wait="$(parse_am_field "$am_start_file" WaitTime || true)"
    host_am=$((am_done_ms - start_ms))
    printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
      "$tab" "$run" "$am_this" "$am_total" "$am_wait" "$host_am" "$ready_ms" \
      "$ready_found" "$ready_source" "$ready_to_tap" "$tap_host" "$tap_to_tab" \
      "$tab_ready_ms" "$tab_found" "$tab_source" "$tap_x" "$tap_y" "$am_rc" >>"$runs_csv"
  done
done

python3 - "$report_dir" "$trend_file" "$timestamp" "$serial" "$package_name" "$component" "$ready_text" "$runs" "$tabs_text" "$tap_strategy" "${screen_width}x${screen_height}" <<'PY'
import csv
import json
import statistics
import sys
from collections import Counter, defaultdict
from pathlib import Path

report_dir = Path(sys.argv[1])
trend_file = Path(sys.argv[2])
timestamp, serial, package_name, component, ready_text, requested_runs, tabs_text, tap_strategy, screen_size = sys.argv[3:12]
runs_csv = report_dir / "tab-runs.csv"
marks_csv = report_dir / "tab-marks.csv"
jsonl_file = report_dir / "tab-runs.jsonl"
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

rows = []
with runs_csv.open(newline="") as handle:
    for row in csv.DictReader(handle):
        rows.append(row)

marks_by_target_run = defaultdict(list)
with marks_csv.open(newline="") as handle:
    for row in csv.DictReader(handle):
        elapsed = to_int(row["elapsedMs"])
        if elapsed is not None:
            marks_by_target_run[(row["target"], row["run"])].append({"mark": row["mark"], "elapsedMs": elapsed})

with jsonl_file.open("w") as handle:
    for row in rows:
        payload = {key: row[key] for key in row}
        for key in (
            "run",
            "amThisTimeMs",
            "amTotalTimeMs",
            "amWaitTimeMs",
            "hostAmStartMs",
            "hostStartToDashboardReadyMs",
            "readyToTapMs",
            "tapHostMs",
            "tapToTabReadyMs",
            "hostStartToTabReadyMs",
            "tapX",
            "tapY",
            "amStartExitCode",
        ):
            payload[key] = to_int(payload.get(key))
        payload["readyFound"] = row.get("readyFound") == "1"
        payload["tabReadyFound"] = row.get("tabReadyFound") == "1"
        payload["startupMarkEvents"] = marks_by_target_run.get((row["target"], row["run"]), [])
        handle.write(json.dumps(payload, sort_keys=True) + "\n")

targets = []
for row in rows:
    if row["target"] not in targets:
        targets.append(row["target"])

previous = None
requested_runs_int = int(requested_runs)
targets_requested = tabs_text.split()
if trend_file.exists():
    for line in trend_file.read_text(encoding="utf-8", errors="ignore").splitlines():
        try:
            item = json.loads(line)
        except json.JSONDecodeError:
            continue
        if (
            item.get("serial") == serial and
            item.get("package") == package_name and
            item.get("requestedRuns") == requested_runs_int and
            item.get("targetsRequested") == targets_requested and
            item.get("tapStrategy") == tap_strategy and
            item.get("screenSize") == screen_size
        ):
            previous = item

target_summary = {}
for target in targets:
    target_rows = [row for row in rows if row["target"] == target]
    js_switch_values = []
    js_end_elapsed_values = []
    js_paint_values = []
    js_paint_elapsed_values = []
    js_data_request_values = []
    js_data_received_values = []
    js_data_rendered_values = []
    app_ready_values = []
    start_mark = f"js:tab:{target}:start"
    end_mark = f"js:tab:{target}:end"
    paint_mark = f"js:tab:{target}:paint"
    data_request_mark = f"js:{target}_data_request"
    data_received_mark = f"js:{target}_data_received"
    data_rendered_mark = f"js:{target}_data_rendered"
    for row in target_rows:
        events = marks_by_target_run.get((row["target"], row["run"]), [])
        first_by_mark = {}
        for event in events:
            first_by_mark.setdefault(event["mark"], event["elapsedMs"])
        app_ready_values.append(first_by_mark.get("dashboard_ready_content_description"))
        tab_start_elapsed = first_by_mark.get(start_mark)
        if tab_start_elapsed is not None:
            for mark, values in [
                (data_request_mark, js_data_request_values),
                (data_received_mark, js_data_received_values),
                (data_rendered_mark, js_data_rendered_values),
            ]:
                elapsed = first_by_mark.get(mark)
                if elapsed is not None:
                    values.append(elapsed - tab_start_elapsed)
        tab_started_at = None
        for event in events:
            if event["mark"] == start_mark:
                tab_started_at = event["elapsedMs"]
            elif event["mark"] == end_mark and tab_started_at is not None:
                js_switch_values.append(event["elapsedMs"] - tab_started_at)
                js_end_elapsed_values.append(event["elapsedMs"])
            elif event["mark"] == paint_mark and tab_started_at is not None:
                js_paint_values.append(event["elapsedMs"] - tab_started_at)
                js_paint_elapsed_values.append(event["elapsedMs"])
                break
    target_summary[target] = {
        "runs": len(target_rows),
        "successfulReadyRuns": sum(1 for row in target_rows if row.get("readyFound") == "1"),
        "successfulTabRuns": sum(1 for row in target_rows if row.get("tabReadyFound") == "1"),
        "readySourceCounts": dict(Counter(row.get("readySource", "") for row in target_rows)),
        "tabSourceCounts": dict(Counter(row.get("tabReadySource", "") for row in target_rows)),
        "amTotalTimeMs": stats(to_int(row.get("amTotalTimeMs")) for row in target_rows),
        "hostStartToDashboardReadyMs": stats(to_int(row.get("hostStartToDashboardReadyMs")) for row in target_rows),
        "readyToTapMs": stats(to_int(row.get("readyToTapMs")) for row in target_rows),
        "tapHostMs": stats(to_int(row.get("tapHostMs")) for row in target_rows),
        "tapToTabReadyMs": stats(to_int(row.get("tapToTabReadyMs")) for row in target_rows),
        "hostStartToTabReadyMs": stats(to_int(row.get("hostStartToTabReadyMs")) for row in target_rows),
        "appDashboardReadyMs": stats(app_ready_values),
        "jsTabSwitchMs": stats(js_switch_values),
        "jsTabEndElapsedMs": stats(js_end_elapsed_values),
        "jsTabPaintMs": stats(js_paint_values),
        "jsTabPaintEndElapsedMs": stats(js_paint_elapsed_values),
        "jsTabDataRequestMs": stats(js_data_request_values),
        "jsTabDataReceivedMs": stats(js_data_received_values),
        "jsTabDataRenderedMs": stats(js_data_rendered_values),
    }

warnings = []
if previous:
    prev_targets = previous.get("targets", {})
    for target, item in target_summary.items():
        prev = prev_targets.get(target, {})
        comparable_metrics = ["appDashboardReadyMs", "jsTabPaintMs", "jsTabPaintEndElapsedMs", "jsTabDataRenderedMs"]
        if prev.get("tabSourceCounts") == item.get("tabSourceCounts"):
            comparable_metrics.extend(["tapToTabReadyMs", "hostStartToTabReadyMs"])
        for metric in comparable_metrics:
            current_stat = item.get(metric)
            prev_stat = prev.get(metric)
            current = current_stat.get("median") if isinstance(current_stat, dict) else None
            old = prev_stat.get("median") if isinstance(prev_stat, dict) else None
            if current is None or old in (None, 0):
                continue
            if current >= old * 1.20 and current - old >= 100:
                warnings.append({
                    "target": target,
                    "metric": metric,
                    "previousMedianMs": old,
                    "currentMedianMs": current,
                    "deltaMs": current - old,
                })

trend_payload = {
    "timestamp": timestamp,
    "serial": serial,
    "package": package_name,
    "component": component,
    "readyText": ready_text,
    "requestedRuns": requested_runs_int,
    "targetsRequested": targets_requested,
    "tapStrategy": tap_strategy,
    "screenSize": screen_size,
    "report": str(summary_file),
    "targets": target_summary,
    "regressionWarnings": warnings,
}
with trend_file.open("a", encoding="utf-8") as handle:
    handle.write(json.dumps(trend_payload, sort_keys=True) + "\n")

def fmt_stat(item, key="median"):
    if not isinstance(item, dict) or item.get(key) is None:
        return "n/a"
    return str(item[key])

lines = [
    "# VoltTracker ADB Tab Benchmark",
    "",
    "Status: `completed`" if not warnings else "Status: `completed-with-regression-warning`",
    "",
    f"Generated: `{timestamp}`",
    "",
    f"- Serial: `{serial}`",
    f"- Package: `{package_name}`",
    f"- Component: `{component}`",
    f"- Requested runs per tab: `{requested_runs}`",
    f"- Targets: `{tabs_text}`",
    f"- Ready probe: `{ready_text}`",
    f"- Tap strategy: `{tap_strategy}`",
    f"- Screen size: `{screen_size}`",
    "",
    "This benchmark fresh-starts `com.volttracker.obdpoc`, waits for the dashboard-ready probe,",
    "taps each requested tab, and waits for the app-reported tab paint mark.",
    "Data hydration columns use app marks from tab start to data request/receipt/render.",
    "It does not install, uninstall, clear data, or target any alternate package.",
    "The benchmark checks in-app `VoltStartup` marks before slower UiAutomator title polling.",
    "",
    "## Summary",
    "",
    "| Target | Runs | Ready runs | Tab runs | App ready ms | JS switch ms | JS paint ms | Data request ms | Data received ms | Data rendered ms | App start -> tab paint ms | Host ready ms | Host tap -> tab ms | Host start -> tab ms | Ready source | Tab source |",
    "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|",
]

for target in targets:
    item = target_summary[target]
    ready_sources = ", ".join(f"{k}:{v}" for k, v in sorted(item["readySourceCounts"].items()))
    tab_sources = ", ".join(f"{k}:{v}" for k, v in sorted(item["tabSourceCounts"].items()))
    lines.append(
        f"| `{target}` | {item['runs']} | {item['successfulReadyRuns']} | {item['successfulTabRuns']} | "
        f"{fmt_stat(item['appDashboardReadyMs'])} | {fmt_stat(item['jsTabSwitchMs'])} | "
        f"{fmt_stat(item['jsTabPaintMs'])} | {fmt_stat(item['jsTabDataRequestMs'])} | "
        f"{fmt_stat(item['jsTabDataReceivedMs'])} | {fmt_stat(item['jsTabDataRenderedMs'])} | "
        f"{fmt_stat(item['jsTabPaintEndElapsedMs'])} | "
        f"{fmt_stat(item['hostStartToDashboardReadyMs'])} | {fmt_stat(item['tapToTabReadyMs'])} | "
        f"{fmt_stat(item['hostStartToTabReadyMs'])} | "
        f"{ready_sources} | {tab_sources} |"
    )

if warnings:
    lines += [
        "",
        "## Regression Warnings",
        "",
        "| Target | Metric | Previous median ms | Current median ms | Delta ms |",
        "|---|---|---:|---:|---:|",
    ]
    for warning in warnings:
        lines.append(
            f"| `{warning['target']}` | `{warning['metric']}` | {warning['previousMedianMs']} | "
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
    f"- Raw command output and UI dumps: `{report_dir / 'raw'}`",
    "",
]

summary_file.write_text("\n".join(lines), encoding="utf-8")
PY

echo "$summary_file"
