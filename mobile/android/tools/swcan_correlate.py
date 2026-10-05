#!/usr/bin/env python3
"""Match SW-CAN (GMLAN low-speed) frame fields against the app's own telemetry.

Feed it on-device session logs (files/obd-logs/session-*.jsonl from a debug build, which logs
every listen window as a `swcan_raw` event) and it prints, per 29-bit frame id:

- how often it was heard and how many distinct payloads it carried,
- what each byte looks like (constant, rolling counter, flag, enum or analog),
- for the fields that move, which telemetry signals they track, with a linear fit.

Ids already in docs/swcan-signal-map.csv are listed by name and skipped unless --all. Payloads of
the ids the map marks `sensitive` (VIN, OnStar hotspot text, GPS), and of unmapped ids whose every
payload is printable text, are never printed, and location telemetry is never fitted, so the output
is safe to paste into the map or a PR.

    python3 tools/swcan_correlate.py ~/car-logs/session-*.jsonl
    python3 tools/swcan_correlate.py --id 106B8040 --changes ~/car-logs/session-*.jsonl
    python3 tools/swcan_correlate.py --self-test

Each listen window is one data point: its frames are reduced to a median and paired with the
nearest telemetry sample (within --max-gap-ms). Windows more than 20 minutes apart start a new
session, and fields are compared WITHIN sessions only (both sides centred on each session's mean).
Otherwise anything that differed between a parked day and a driving day would "track" everything
else that differed. A 20-minute drive gives about 20 points, so a fit is a lead to check, not
proof; combine several drives for more points.
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import statistics
import sys
import tempfile
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_MAP = REPO_ROOT / "mobile" / "android" / "docs" / "swcan-signal-map.csv"

# Telemetry that must never be fitted (a fit's intercept would leak a position) or that only
# counts samples and so tracks every rising counter on the bus.
SKIPPED_SIGNALS = {
    "latitude",
    "longitude",
    "accuracyM",
    "altitudeM",
    "bearingDeg",
    "gpsSpeedKph",
    "updatedAt",
    "sampleCount",
    "backgroundSampleCount",
    "sampleGapCount",
}
MIN_POINTS = 6
SESSION_GAP_MS = 20 * 60_000
COUNTER_SHARE = 0.9
WIDER_MARGIN = 0.0005
ENUM_MAX = 8
TEXT_MIN_CHARS = 3
STATUSES = {
    "decoded",
    "decoded-unchecked",
    "strong",
    "candidate",
    "counter",
    "constant",
    "opaque",
    "heartbeat",
    "sensitive",
    "unknown",
}
# GMLAN parameter ids that carry the VIN, GPS, immobilizer ids and the OnStar Wi-Fi settings.
MUST_BE_SENSITIVE = {
    0x762, 0x764,  # VIN
    0x155, 0x156,  # GPS
    0x160, 0x182, 0x183, 0x184,  # immobilizer id and environment id
    0x474, 0x478, 0x479, 0x47A, 0x480, 0x481, 0x482,  # Wi-Fi settings, name, password
}
TEXT_MAX_DISTINCT = 3


@dataclass
class MapRow:
    key: str
    name: str
    status: str
    byte_spec: str


@dataclass
class SignalMap:
    exact: dict[int, list[MapRow]] = field(default_factory=dict)
    by_arb: dict[int, list[MapRow]] = field(default_factory=dict)
    # Telemetry keys the app fills from SW-CAN itself: a fit against one of these only shows two
    # frames carry the same thing, never that a decode is right.
    swcan_keys: set[str] = field(default_factory=set)

    def rows_for(self, frame_id: int) -> list[MapRow]:
        return self.exact.get(frame_id, []) + self.by_arb.get(arb_of(frame_id), [])

    def sensitive(self, frame_id: int, payloads: list[tuple[int, ...]]) -> bool:
        """Marked sensitive, or not mapped yet and shaped like text (VINs and Wi-Fi names are ASCII)."""
        rows = self.rows_for(frame_id)
        if rows:
            return any(row.status == "sensitive" for row in rows)
        distinct = set(payloads)
        return len(distinct) <= TEXT_MAX_DISTINCT and all(looks_like_text(p) for p in distinct)


def looks_like_text(payload: tuple[int, ...]) -> bool:
    """Printable ASCII, possibly zero-padded, as the VIN and OnStar hotspot frames are."""
    chars = sum(1 for b in payload if 0x20 <= b < 0x7F)
    return chars >= TEXT_MIN_CHARS and all(b == 0 or 0x20 <= b < 0x7F for b in payload)


def arb_of(frame_id: int) -> int:
    """The 13-bit GMLAN parameter id: bits 13..25 of the 29-bit identifier."""
    return (frame_id >> 13) & 0x1FFF


def load_map(path: Path) -> SignalMap:
    signal_map = SignalMap()
    if not path.exists():
        return signal_map
    with path.open(newline="") as handle:
        for raw in csv.DictReader(row for row in handle if not row.startswith("#")):
            key = (raw.get("id") or "").strip()
            row = MapRow(key, raw.get("name", "").strip(), raw.get("status", "").strip(), raw.get("bytes", "").strip())
            signal_map.swcan_keys.update(k.strip() for k in (raw.get("app_key") or "").split(";") if k.strip())
            if key.lower().startswith("arb:"):
                signal_map.by_arb.setdefault(int(key[4:], 16), []).append(row)
            elif key:
                signal_map.exact.setdefault(int(key, 16), []).append(row)
    return signal_map


@dataclass
class Capture:
    # window timestamp -> frame id -> payloads in the order heard
    windows: dict[int, dict[int, list[tuple[int, ...]]]] = field(default_factory=lambda: defaultdict(dict))
    telemetry: list[tuple[int, dict[str, float]]] = field(default_factory=list)

    def session_of(self) -> dict[int, int]:
        """Window timestamp -> session number; a gap over SESSION_GAP_MS starts a new session."""
        sessions: dict[int, int] = {}
        session, last = 0, None
        for ts in sorted(self.windows):
            if last is not None and ts - last > SESSION_GAP_MS:
                session += 1
            sessions[ts], last = session, ts
        return sessions


def parse_frame(line: str) -> tuple[int, tuple[int, ...]] | None:
    tokens = line.strip().upper().split()
    if not tokens or any(not all(c in "0123456789ABCDEF" for c in t) for t in tokens):
        return None
    if len(tokens[0]) == 8:
        head, rest = tokens[0], tokens[1:]
    elif len(tokens) >= 4 and all(len(t) == 2 for t in tokens[:4]):
        head, rest = "".join(tokens[:4]), tokens[4:]
    else:
        return None
    if rest and len(rest[0]) == 1:  # a DLC digit printed by ATD1
        rest = rest[1:]
    if len(rest) > 8 or any(len(t) != 2 for t in rest):
        return None
    return int(head, 16), tuple(int(t, 16) for t in rest)


def read_records(path: Path):
    """Each JSON object in a JSONL log that carries an integer `ts`; torn lines are skipped."""
    with path.open() as handle:
        for line in handle:
            try:
                record = json.loads(line)
            except json.JSONDecodeError:
                continue
            if isinstance(record, dict) and isinstance(record.get("ts"), int):
                yield record


def numeric_fields(payload: dict) -> dict[str, float]:
    return {k: float(v) for k, v in payload.items() if isinstance(v, (int, float)) and not isinstance(v, bool)}


def add_window(capture: Capture, ts: int, text: str) -> None:
    window = capture.windows[ts]
    for raw_line in text.replace("\n", "\r").split("\r"):
        frame = parse_frame(raw_line)
        if frame:
            window.setdefault(frame[0], []).append(frame[1])


def load_logs(paths: list[Path]) -> Capture:
    capture = Capture()
    seen_windows: set[tuple[int, str]] = set()
    seen_samples: set[int] = set()
    for path in paths:
        for record in read_records(path):
            ts = record["ts"]
            payload = record.get("payload") or {}
            if record.get("type") == "telemetry":
                if ts not in seen_samples:
                    seen_samples.add(ts)
                    capture.telemetry.append((ts, numeric_fields(payload)))
            elif payload.get("event") == "swcan_raw":
                text = payload.get("text") or ""
                if (ts, text) not in seen_windows:  # overlapping capture files repeat windows
                    seen_windows.add((ts, text))
                    add_window(capture, ts, text)
    capture.telemetry.sort()
    return capture


def usable_signal(key: str) -> bool:
    return key not in SKIPPED_SIGNALS and not key.endswith("Ms")


def classify(values_by_window: list[list[int]]) -> str:
    flat = [v for window in values_by_window for v in window]
    distinct = len(set(flat))
    if distinct == 1:
        return f"const {flat[0]:02X}"
    steps = Counter((b - a) % 256 for window in values_by_window for a, b in zip(window, window[1:]))
    total = sum(steps.values())
    if total >= 5:
        step, count = steps.most_common(1)[0]
        if step != 0 and count / total >= COUNTER_SHARE:
            return f"counter +{step}"
    if distinct == 2:
        return "flag " + "/".join(f"{v:02X}" for v in sorted(set(flat)))
    if distinct <= ENUM_MAX:
        return f"enum x{distinct}"
    return f"analog x{distinct}"


def nearest_sample(capture: Capture, ts: int, max_gap_ms: int) -> dict[str, float] | None:
    best = None
    for sample_ts, sample in capture.telemetry:
        gap = abs(sample_ts - ts)
        if gap <= max_gap_ms and (best is None or gap < best[0]):
            best = (gap, sample)
    return best[1] if best else None


@dataclass
class Fit:
    signal: str
    r: float
    slope: float
    intercept: float
    points: int
    sessions: int


def fits_for(
    series: list[tuple[int, float]],
    capture: Capture,
    sessions: dict[int, int],
    max_gap_ms: int,
    min_r: float,
) -> list[Fit]:
    samples = [(sessions[ts], value, nearest_sample(capture, ts, max_gap_ms)) for ts, value in series]
    samples = [(session, value, sample) for session, value, sample in samples if sample]
    signals = {k for _, _, sample in samples for k in sample if usable_signal(k)}
    fits = []
    for signal in signals:
        groups: dict[int, list[tuple[float, float]]] = defaultdict(list)
        for session, value, sample in samples:
            if signal in sample:
                groups[session].append((value, sample[signal]))
        fit = within_session_fit(signal, groups)
        if fit and abs(fit.r) >= min_r:
            fits.append(fit)
    return sorted(fits, key=lambda f: -abs(f.r))


def within_session_fit(signal: str, groups: dict[int, list[tuple[float, float]]]) -> Fit | None:
    """Pearson r and slope on values centred per session; the intercept puts the line back."""
    groups = {s: pairs for s, pairs in groups.items() if len(pairs) >= 3}
    points = [pair for pairs in groups.values() for pair in pairs]
    if len(points) < MIN_POINTS or len({x for x, _ in points}) < 3 or len({y for _, y in points}) < 3:
        return None
    centred = []
    for pairs in groups.values():
        mx = statistics.fmean(x for x, _ in pairs)
        my = statistics.fmean(y for _, y in pairs)
        centred += [(x - mx, y - my) for x, y in pairs]
    sxx = sum(x * x for x, _ in centred)
    syy = sum(y * y for _, y in centred)
    if sxx == 0 or syy == 0:
        return None
    sxy = sum(x * y for x, y in centred)
    slope = sxy / sxx
    intercept = statistics.fmean(y for _, y in points) - slope * statistics.fmean(x for x, _ in points)
    return Fit(signal, sxy / (sxx * syy) ** 0.5, slope, intercept, len(points), len(groups))


def field_series(capture: Capture, frame_id: int, start: int, width: int) -> list[tuple[int, float]]:
    series = []
    for ts in sorted(capture.windows):
        payloads = capture.windows[ts].get(frame_id, [])
        values = [int.from_bytes(bytes(p[start : start + width]), "big") for p in payloads if len(p) >= start + width]
        if values:
            series.append((ts, statistics.median(values)))
    return series


def byte_kinds(heard: dict[int, list[tuple[int, ...]]], length: int) -> tuple[list[str], list[int]]:
    """Each byte's kind, and the indexes of the bytes that move (not constant, not a counter)."""
    kinds, moving = [], []
    for i in range(length):
        per_window = [[p[i] for p in heard[ts] if len(p) > i] for ts in sorted(heard)]
        kind = classify([w for w in per_window if w])
        kinds.append(f"b{i} {kind}")
        if not kind.startswith(("const", "counter")):
            moving.append(i)
    return kinds, moving


def best_field_fit(
    capture: Capture,
    frame_id: int,
    start: int,
    length: int,
    args: argparse.Namespace,
) -> tuple[str, list[Fit]] | None:
    """The 1-3 byte field starting at [start] whose best fit is strongest. A wider field only wins when
    it fits strictly better, so a noisy neighbour byte doesn't widen it."""
    sessions = capture.session_of()
    best: tuple[str, list[Fit]] | None = None
    for width in range(1, min(3, length - start) + 1):
        label = f"b{start}" if width == 1 else f"b{start}-{start + width - 1}"
        fits = fits_for(field_series(capture, frame_id, start, width), capture, sessions, args.max_gap_ms, args.min_r)
        if fits and (best is None or abs(fits[0].r) > abs(best[1][0].r) + WIDER_MARGIN):
            best = (label, fits)
    return best


def fit_line(label: str, fit: Fit, signal_map: SignalMap) -> str:
    derived = "  [app reads this from SW-CAN]" if fit.signal in signal_map.swcan_keys else ""
    return (
        f"  {label:7} ~ {fit.signal} r={fit.r:+.3f}  {fit.signal} ≈ {fit.slope:.6g}·raw "
        f"{'+' if fit.intercept >= 0 else '-'} {abs(fit.intercept):.4g}  "
        f"(n={fit.points}, sessions={fit.sessions}){derived}"
    )


def analyse(capture: Capture, frame_id: int, signal_map: SignalMap, args: argparse.Namespace) -> list[str]:
    heard = {ts: window[frame_id] for ts, window in capture.windows.items() if frame_id in window}
    payloads = [p for ts in sorted(heard) for p in heard[ts]]
    length = max(len(p) for p in payloads)
    lines = [
        f"## {frame_id:08X}  arb {arb_of(frame_id):03X}  src {frame_id & 0xFF:02X}  "
        f"frames {len(payloads)}  windows {len(heard)}  distinct {len(set(payloads))}  len {length}"
    ]
    lines += [f"  map: {row.name} [{row.status}] bytes {row.byte_spec}" for row in signal_map.rows_for(frame_id)]
    if signal_map.sensitive(frame_id, payloads):
        # Not even the byte summary: a constant byte's kind names its value.
        return lines + ["  payload: (sensitive, not shown)"]
    lines.append("  e.g. " + " ".join(f"{b:02X}" for b in payloads[-1]))
    kinds, moving = byte_kinds(heard, length)
    lines.append("  bytes: " + ", ".join(kinds))
    for start in moving:
        best = best_field_fit(capture, frame_id, start, length, args)
        if best:
            label, fits = best
            lines += [fit_line(label, fit, signal_map) for fit in fits[: args.top]]
    return lines


def changes(capture: Capture, frame_id: int, signal_map: SignalMap) -> list[str]:
    lines = [f"## changes {frame_id:08X}"]
    heard = [p for window in capture.windows.values() for p in window.get(frame_id, [])]
    if signal_map.sensitive(frame_id, heard):
        return lines + ["  (sensitive, not shown)"]
    start = min(capture.windows) if capture.windows else 0
    last = None
    for ts in sorted(capture.windows):
        for payload in capture.windows[ts].get(frame_id, []):
            if payload != last:
                lines.append(f"  +{(ts - start) / 1000:7.1f}s  " + " ".join(f"{b:02X}" for b in payload))
                last = payload
    return lines


def checked_path(raw: str) -> Path:
    """Resolves a path given on the command line. Only files under the home directory, the temp
    directory or this repo are read, so a stray argument can't point the tool anywhere else."""
    path = os.path.realpath(raw)
    roots = {os.path.realpath(root) for root in (Path.home(), tempfile.gettempdir(), "/tmp", REPO_ROOT)}
    for root in roots:
        if os.path.commonpath([root, path]) == root:
            return Path(path)
    raise SystemExit(f"{raw}: only files under your home directory, the temp directory or the repo are read")


def run(args: argparse.Namespace) -> int:
    signal_map = load_map(checked_path(args.map))
    capture = load_logs([checked_path(p) for p in args.logs])
    if not capture.windows:
        print("No swcan_raw windows in these logs. Raw windows are only logged by debug builds.")
        return 1
    ids = Counter(frame_id for window in capture.windows.values() for frame_id in window)
    frames = sum(len(p) for window in capture.windows.values() for p in window.values())
    print(
        f"# {len(capture.windows)} windows, {frames} frames, {len(ids)} ids, "
        f"{len(capture.telemetry)} telemetry samples"
    )
    wanted = [int(i, 16) for i in args.id] if args.id else [frame_id for frame_id, _ in ids.most_common()]
    for frame_id in wanted:
        if frame_id not in ids:
            print(f"\n## {frame_id:08X} not heard")
            continue
        if args.changes:
            print("\n".join(["", *changes(capture, frame_id, signal_map)]))
            continue
        rows = signal_map.rows_for(frame_id)
        if rows and not args.all and not args.id:
            names = "; ".join(f"{row.name} [{row.status}]" for row in rows)
            print(f"\n{frame_id:08X} known: {names}")
            continue
        print("\n".join(["", *analyse(capture, frame_id, signal_map, args)]))
    return 0


def self_test() -> int:
    """Synthetic windows: a speed-like word, a rolling counter, a constant and a sensitive id."""
    capture = Capture()
    for n in range(12):
        # Two sessions an hour apart; stepSignal only differs BETWEEN them, as a parked day and a
        # driving day do, while the frame's byte 2 jumps with it. That must not read as a fit.
        ts = 1_000_000 + n * 45_000 + (3_600_000 if n >= 6 else 0)
        speed = 10.0 * n
        raw = int(speed * 32)
        capture.windows[ts] = {
            0x106B8040: [(raw >> 8, raw & 0xFF, 0x11, (n * 2 + k) % 256, 0x20 if n >= 6 else 0x10) for k in range(3)],
            0x10EC4040: [(0x31, 0x47, n % 256)],
            0x108F0097: [(0x56, 0x6F, 0x6C, 0x74, 0, 0, 0, 0)],
        }
        capture.telemetry.append((ts + 500, {"speedKph": speed, "latitude": 1.0 * n, "stepSignal": 5.0 if n >= 6 else 1.0}))
    signal_map = SignalMap()
    signal_map.exact[0x10EC4040] = [MapRow("10EC4040", "VIN part 1", "sensitive", "0-7")]
    args = argparse.Namespace(max_gap_ms=15_000, min_r=0.8, top=3)
    report = "\n".join(analyse(capture, 0x106B8040, signal_map, args))
    hidden = "\n".join(analyse(capture, 0x10EC4040, signal_map, args) + changes(capture, 0x10EC4040, signal_map))
    text = "\n".join(analyse(capture, 0x108F0097, signal_map, args) + changes(capture, 0x108F0097, signal_map))
    checks = {
        "byte 2 is constant": "b2 const 11" in report,
        "byte 3 is a rolling counter": "b3 counter +1" in report,
        "the word tracks speed at 1/32": "b0-1    ~ speedKph r=+1.000  speedKph ≈ 0.03125·raw" in report,
        "a session-level step is not a fit": "stepSignal" not in report,
        "location is never fitted": "latitude" not in report,
        "a sensitive payload is hidden": "(sensitive, not shown)" in hidden
        and not any(token in hidden for token in ("31", "47", "const")),
        "an unmapped text-like frame is hidden": "(sensitive, not shown)" in text and "56 6F" not in text,
    }
    checks.update(map_checks(load_map(DEFAULT_MAP)))
    for name, ok in checks.items():
        print(("ok   " if ok else "FAIL ") + name)
    if not all(checks.values()):
        print(report)
    return 0 if all(checks.values()) else 1


def map_checks(signal_map: SignalMap) -> dict[str, bool]:
    """The checked-in map parses, uses known statuses and still hides the personal frames."""
    rows = [row for rows in [*signal_map.exact.values(), *signal_map.by_arb.values()] for row in rows]
    return {
        "the map has rows": bool(rows),
        "every map status is known": all(row.status in STATUSES for row in rows),
        "VIN, GPS, immobilizer and Wi-Fi frames are sensitive": all(
            any(row.status == "sensitive" for row in signal_map.by_arb.get(arb, [])) for arb in MUST_BE_SENSITIVE
        ),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("logs", nargs="*", help="session-*.jsonl logs pulled from the phone")
    parser.add_argument("--map", default=str(DEFAULT_MAP), help="signal map CSV (default: docs/swcan-signal-map.csv)")
    parser.add_argument("--id", action="append", help="only these 29-bit ids (hex); repeatable")
    parser.add_argument("--all", action="store_true", help="also analyse ids the map already names")
    parser.add_argument("--changes", action="store_true", help="print each payload change instead of fits")
    parser.add_argument("--min-r", type=float, default=0.8, help="smallest |r| to report (default 0.8)")
    parser.add_argument("--top", type=int, default=3, help="fits to show per field (default 3)")
    parser.add_argument("--max-gap-ms", type=int, default=15_000, help="window-to-sample pairing limit")
    parser.add_argument("--self-test", action="store_true", help="run the built-in checks and exit")
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    if not args.logs:
        parser.error("give at least one session log, or --self-test")
    return run(args)


if __name__ == "__main__":
    sys.exit(main())
