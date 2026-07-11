#!/usr/bin/env python3
"""Parse VoltStartup obd_* marks from logcat and gate OBD session latency.

Debug builds emit `VoltStartup` logcat marks around every OBD session
(StartupTrace.OBD_* in the Kotlin source): the connect request, socket
connect, ELM327 init, first published sample, and diagnostic-scan stages.
This tool groups those marks into sessions, derives latency spans, compares
them against ceilings, and writes a summary report.

The default ceilings are PROVISIONAL: generous bounds taken from the
performance-contract expectations, meant to catch order-of-magnitude
regressions (and missing marks) until enough clean device captures exist to
set tight SLOs. See docs/performance-baseline-history.md ("OBD connect and
scan latency") for the capture procedure and the ratchet plan.

Typical uses:

    # After the emulator smoke (demo session, no car needed):
    python3 tools/perf/check_obd_latency.py \
        --logcat build/emulator-smoke-logcat.txt --require demo

    # Against a live logcat dump from a connected debug build:
    adb logcat -d | python3 tools/perf/check_obd_latency.py --logcat -
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import tempfile
from pathlib import Path

# Mark names, kept in sync with StartupTrace.OBD_* (ObdLatencyMarksContractTest
# pins both sides). Mode/stage-qualified marks carry a ":<suffix>".
MARK_CONNECT_REQUEST = "obd_connect_request"
MARK_SOCKET_CONNECTED = "obd_socket_connected"
MARK_ELM_INIT_START = "obd_elm_init_start"
MARK_ELM_INIT_END = "obd_elm_init_end"
MARK_FIRST_SAMPLE = "obd_first_sample"
MARK_SCAN_START = "obd_scan_start"
MARK_SCAN_STAGE = "obd_scan_stage"
MARK_SCAN_COMPLETE = "obd_scan_complete"

MARK_LINE = re.compile(r"VoltStartup\s*(?:\(\s*\d+\))?\s*:\s+mark=(\S+)\s+elapsedMs=(\d+)")

# Provisional ceilings (ms). Generous on purpose: a CI emulator or a sleepy
# adapter must not flake the gate, but a hang or a dropped mark must fail it.
DEFAULT_CEILINGS = {
    "demo_first_sample_ms": 15_000,
    "live_first_sample_ms": 45_000,
    "socket_connect_ms": 30_000,
    "elm_init_ms": 25_000,
    "scan_quick_total_ms": 120_000,
    "scan_full_total_ms": 900_000,
}


def parse_marks(text: str) -> list[tuple[str, int]]:
    """All (mark_name, elapsed_ms) pairs, in log order, restricted to obd_* marks."""
    marks = []
    for line in text.splitlines():
        match = MARK_LINE.search(line)
        if match and match.group(1).startswith("obd_"):
            marks.append((match.group(1), int(match.group(2))))
    return marks


def split_mark(name: str) -> tuple[str, str]:
    base, _, suffix = name.partition(":")
    return base, suffix


def group_sessions(marks: list[tuple[str, int]]) -> list[dict]:
    """Group ordered obd_* marks into per-session records keyed by connect request."""
    sessions: list[dict] = []
    current: dict | None = None
    for name, elapsed in marks:
        base, suffix = split_mark(name)
        if base == MARK_CONNECT_REQUEST:
            current = {"mode": suffix or "unknown", "connect_request_ms": elapsed, "marks": []}
            sessions.append(current)
        if current is None:
            # Marks before any connect request (e.g. a logcat that starts
            # mid-session) cannot be attributed; skip them.
            continue
        current["marks"].append((name, elapsed))
    return sessions


def session_spans(session: dict) -> dict:
    """Derive latency spans (ms) for one session from its ordered marks."""
    start = session["connect_request_ms"]
    spans: dict = {"mode": session["mode"]}
    elm_start = None
    scan_start = None
    prev_scan_mark_ms = None
    stages: list[dict] = []
    for name, elapsed in session["marks"]:
        base, suffix = split_mark(name)
        if base == MARK_SOCKET_CONNECTED and "socket_connect_ms" not in spans:
            spans["socket_connect_ms"] = elapsed - start
        elif base == MARK_ELM_INIT_START and elm_start is None:
            elm_start = elapsed
        elif base == MARK_ELM_INIT_END and elm_start is not None and "elm_init_ms" not in spans:
            spans["elm_init_ms"] = elapsed - elm_start
        elif base == MARK_FIRST_SAMPLE and "first_sample_ms" not in spans:
            spans["first_sample_ms"] = elapsed - start
            spans["first_sample_kind"] = suffix or "unknown"
        elif base == MARK_SCAN_START and scan_start is None:
            scan_start = elapsed
            prev_scan_mark_ms = elapsed
            spans["scan_profile"] = suffix or "unknown"
        elif base == MARK_SCAN_STAGE and prev_scan_mark_ms is not None:
            stages.append({"stage": suffix or "unknown", "ms": elapsed - prev_scan_mark_ms})
            prev_scan_mark_ms = elapsed
        elif base == MARK_SCAN_COMPLETE and scan_start is not None and "scan_total_ms" not in spans:
            spans["scan_total_ms"] = elapsed - scan_start
    if stages:
        spans["scan_stages"] = stages
    return spans


def check_ceilings(spans_list: list[dict], ceilings: dict) -> list[str]:
    """Ceiling violations across all sessions. Only spans present are enforced."""
    violations = []
    for i, spans in enumerate(spans_list):
        label = f"session {i + 1} ({spans.get('mode')})"

        def over(span_key: str, ceiling_key: str) -> None:
            value = spans.get(span_key)
            limit = ceilings[ceiling_key]
            if value is not None and value > limit:
                violations.append(f"{label}: {span_key}={value} ms exceeds ceiling {limit} ms ({ceiling_key})")

        if spans.get("first_sample_kind") == "demo":
            over("first_sample_ms", "demo_first_sample_ms")
        elif "first_sample_ms" in spans:
            over("first_sample_ms", "live_first_sample_ms")
        over("socket_connect_ms", "socket_connect_ms")
        over("elm_init_ms", "elm_init_ms")
        if spans.get("scan_profile") == "quick":
            over("scan_total_ms", "scan_quick_total_ms")
        elif "scan_total_ms" in spans:
            over("scan_total_ms", "scan_full_total_ms")
    return violations


def completed_modes(spans_list: list[dict]) -> set[str]:
    """Modes that produced a full connect→first-sample span."""
    return {spans["mode"] for spans in spans_list if "first_sample_ms" in spans}


def render_summary(spans_list: list[dict], violations: list[str], missing_required: list[str]) -> str:
    status = "failed" if (violations or missing_required) else ("passed" if spans_list else "no-sessions")
    lines = [
        "# OBD Latency Check",
        "",
        f"Status: `{status}`",
        "",
        f"Sessions with obd_* marks: {len(spans_list)}",
        "",
        "| Session | Mode | Connect→first sample | Socket connect | ELM init | Scan total |",
        "|---|---|---:|---:|---:|---:|",
    ]

    def fmt(spans: dict, key: str) -> str:
        return f"{spans[key]} ms" if key in spans else "—"

    for i, spans in enumerate(spans_list):
        lines.append(
            f"| {i + 1} | {spans.get('mode')} | {fmt(spans, 'first_sample_ms')} "
            f"| {fmt(spans, 'socket_connect_ms')} | {fmt(spans, 'elm_init_ms')} | {fmt(spans, 'scan_total_ms')} |",
        )
    for spans in spans_list:
        for stage in spans.get("scan_stages", []):
            lines.append(f"- scan stage `{stage['stage']}`: {stage['ms']} ms")
    for message in missing_required:
        lines.append(f"- REQUIRED MISSING: {message}")
    for message in violations:
        lines.append(f"- CEILING VIOLATION: {message}")
    lines.append("")
    lines.append("Ceilings are provisional; see docs/performance-baseline-history.md.")
    return "\n".join(lines) + "\n"


def run_check(text: str, ceilings: dict, require: list[str], output_dir: Path | None) -> int:
    spans_list = [session_spans(session) for session in group_sessions(parse_marks(text))]
    violations = check_ceilings(spans_list, ceilings)
    done = completed_modes(spans_list)
    missing_required = [
        f"no completed {mode} session (connect request + first sample) found in the log" for mode in require if mode not in done
    ]
    summary = render_summary(spans_list, violations, missing_required)
    print(summary)
    if output_dir is not None:
        output_dir.mkdir(parents=True, exist_ok=True)
        (output_dir / "summary.md").write_text(summary, encoding="utf-8")
        payload = {"sessions": spans_list, "violations": violations, "missingRequired": missing_required}
        (output_dir / "result.json").write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    return 1 if (violations or missing_required) else 0


def self_test() -> int:
    demo_log = "\n".join(
        [
            "07-11 10:00:00.000 I/VoltStartup( 1234): mark=app_on_create_complete elapsedMs=40 uptimeMs=40",
            "07-11 10:00:01.000 I/VoltStartup( 1234): mark=obd_connect_request:demo elapsedMs=1000 uptimeMs=1000",
            "07-11 10:00:01.200 I/VoltStartup( 1234): mark=obd_first_sample:demo elapsedMs=1200 uptimeMs=1200",
        ],
    )
    spans = [session_spans(s) for s in group_sessions(parse_marks(demo_log))]
    assert len(spans) == 1 and spans[0]["mode"] == "demo" and spans[0]["first_sample_ms"] == 200, spans
    assert check_ceilings(spans, DEFAULT_CEILINGS) == []
    assert completed_modes(spans) == {"demo"}

    live_log = "\n".join(
        [
            "I/VoltStartup( 99): mark=obd_connect_request:scan elapsedMs=2000 uptimeMs=2000",
            "I/VoltStartup( 99): mark=obd_socket_connected elapsedMs=3500 uptimeMs=3500",
            "I/VoltStartup( 99): mark=obd_elm_init_start elapsedMs=3600 uptimeMs=3600",
            "I/VoltStartup( 99): mark=obd_elm_init_end elapsedMs=9600 uptimeMs=9600",
            "I/VoltStartup( 99): mark=obd_scan_start:quick elapsedMs=9700 uptimeMs=9700",
            "I/VoltStartup( 99): mark=obd_scan_stage:adapter_identity elapsedMs=12000 uptimeMs=12000",
            "I/VoltStartup( 99): mark=obd_scan_stage:protocol_vin elapsedMs=30000 uptimeMs=30000",
            "I/VoltStartup( 99): mark=obd_scan_stage:dtc_reads elapsedMs=40000 uptimeMs=40000",
            "I/VoltStartup( 99): mark=obd_scan_complete:quick elapsedMs=40100 uptimeMs=40100",
        ],
    )
    spans = [session_spans(s) for s in group_sessions(parse_marks(live_log))]
    scan = spans[0]
    assert scan["socket_connect_ms"] == 1500 and scan["elm_init_ms"] == 6000, scan
    assert scan["scan_total_ms"] == 30400 and scan["scan_profile"] == "quick", scan
    assert [s["stage"] for s in scan["scan_stages"]] == ["adapter_identity", "protocol_vin", "dtc_reads"], scan
    assert check_ceilings(spans, DEFAULT_CEILINGS) == []

    slow = dict(DEFAULT_CEILINGS, scan_quick_total_ms=10_000)
    assert len(check_ceilings(spans, slow)) == 1

    # A log with no demo session must fail a --require demo run.
    assert "demo" not in completed_modes(spans)

    with tempfile.TemporaryDirectory() as tmp:
        out = Path(tmp) / "obd-latency"
        code = run_check(demo_log, DEFAULT_CEILINGS, ["demo"], out)
        assert code == 0
        assert "Status: `passed`" in (out / "summary.md").read_text(encoding="utf-8")
        assert json.loads((out / "result.json").read_text(encoding="utf-8"))["violations"] == []
        assert run_check(live_log, DEFAULT_CEILINGS, ["demo"], out) == 1

    print("check_obd_latency self-test passed")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--logcat", help="logcat text file to parse, or - for stdin")
    parser.add_argument("--output-dir", default="build/reports/obd-latency", help="where summary.md/result.json are written")
    parser.add_argument(
        "--require",
        action="append",
        default=[],
        help="fail unless a completed session of this mode exists (e.g. demo); repeatable",
    )
    for key, value in DEFAULT_CEILINGS.items():
        parser.add_argument(f"--{key.replace('_', '-')}", type=int, default=value, help=f"ceiling in ms (default {value})")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    if not args.logcat:
        raise SystemExit("--logcat is required (use - for stdin)")
    text = sys.stdin.read() if args.logcat == "-" else Path(args.logcat).read_text(encoding="utf-8", errors="replace")
    ceilings = {key: getattr(args, key) for key in DEFAULT_CEILINGS}
    return run_check(text, ceilings, args.require, Path(args.output_dir))


if __name__ == "__main__":
    raise SystemExit(main())
