#!/usr/bin/env python3
"""Fail when private VoltTracker data leaks into tracked files."""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path


PRIVATE_ENDPOINT_RE = re.compile(
    r"\b(?:(?:10|192\.168)\.\d{1,3}\.\d{1,3}\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3}):\d{2,5}\b"
)
VIN_RE = re.compile(r"\b[A-HJ-NPR-Z0-9]{17}\b")
COORD_PAIR_RE = re.compile(
    r"(?P<lat>[+-]?(?:[1-8]?\d(?:\.\d{4,})|90(?:\.0+)?))\s*,\s*"
    r"(?P<lon>[+-]?(?:(?:1[0-7]\d|[1-9]?\d)(?:\.\d{4,})|180(?:\.0+)?))"
)

SKIP_DIR_PARTS = {
    ".git",
    ".gradle",
    "build",
    "node_modules",
    "coverage",
}
SKIP_PATH_PREFIXES = (
    "mobile/android/app/src/main/assets/dashboard/lib/",
    "mobile/android/app/src/main/assets/dashboard/js/",
    "mobile/android/app/src/main/assets/dashboard/css/",
)
TEXT_SUFFIXES = {
    ".gradle",
    ".groovy",
    ".html",
    ".java",
    ".js",
    ".json",
    ".jsonl",
    ".kt",
    ".kts",
    ".md",
    ".py",
    ".sh",
    ".toml",
    ".ts",
    ".txt",
    ".xml",
    ".yml",
    ".yaml",
    ".csv",
    ".gpx",
    ".kml",
    ".geojson",
}
GPS_PATH_HINTS = (
    "gps",
    "location",
    "route",
    "trip",
    "track",
    "baseline",
    "benchmark",
    "fixture",
    "sample",
    "demo",
)


@dataclass(frozen=True)
class Finding:
    path: str
    line: int
    kind: str
    value: str


@dataclass(frozen=True)
class Allowlist:
    path_prefixes: tuple[str, ...]
    line_substrings: tuple[str, ...]

    def allows(self, path: str, line: str) -> bool:
        return any(path == item or path.startswith(item) for item in self.path_prefixes) or any(
            item in line for item in self.line_substrings
        )


def load_allowlist(path: Path) -> Allowlist:
    path_prefixes: list[str] = []
    line_substrings: list[str] = []
    if not path.exists():
        return Allowlist((), ())
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("path:"):
            path_prefixes.append(line[len("path:") :].strip())
        elif line.startswith("line:"):
            line_substrings.append(line[len("line:") :])
        else:
            raise SystemExit(f"Unsupported allowlist entry in {path}: {raw}")
    return Allowlist(tuple(path_prefixes), tuple(line_substrings))


def tracked_files(root: Path) -> list[Path]:
    result = subprocess.run(
        ["git", "ls-files"],
        cwd=root,
        check=True,
        text=True,
        stdout=subprocess.PIPE,
    )
    return [root / line for line in result.stdout.splitlines() if line]


def should_scan(path: Path, root: Path) -> bool:
    rel = path.relative_to(root).as_posix()
    if any(part in SKIP_DIR_PARTS for part in path.parts):
        return False
    if rel.startswith(SKIP_PATH_PREFIXES):
        return False
    return path.suffix in TEXT_SUFFIXES or path.name in {"README", "LICENSE"}


def should_scan_gps(path: str) -> bool:
    lowered = path.lower()
    return any(hint in lowered for hint in GPS_PATH_HINTS)


def scan_paths(root: Path, paths: list[Path], allowlist: Allowlist) -> list[Finding]:
    findings: list[Finding] = []
    for path in paths:
        if not path.exists() or not should_scan(path, root):
            continue
        rel = path.relative_to(root).as_posix()
        try:
            lines = path.read_text(encoding="utf-8").splitlines()
        except UnicodeDecodeError:
            continue
        for line_number, line in enumerate(lines, start=1):
            if allowlist.allows(rel, line):
                continue
            for match in PRIVATE_ENDPOINT_RE.finditer(line):
                findings.append(Finding(rel, line_number, "private-device-endpoint", match.group(0)))
            for match in VIN_RE.finditer(line):
                findings.append(Finding(rel, line_number, "vin-like-token", match.group(0)))
            if should_scan_gps(rel):
                for match in COORD_PAIR_RE.finditer(line):
                    findings.append(Finding(rel, line_number, "gps-coordinate-pair", match.group(0)))
    return findings


def run_self_test() -> int:
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        good = root / "mobile/android/docs/performance-contracts.md"
        bad_ip = root / "mobile/android/docs/performance-baseline-history.md"
        bad_vin = root / "mobile/android/app/src/main/kotlin/com/example/Foo.kt"
        bad_gps = root / "mobile/android/docs/private-route-sample.md"
        allowed = root / "mobile/android/app/src/main/assets/dashboard/demos/efficiency.html"
        for file in [good, bad_ip, bad_vin, bad_gps, allowed]:
            file.parent.mkdir(parents=True, exist_ok=True)
        good.write_text("Use <ip:port-or-adb-serial> when benchmarking.\n", encoding="utf-8")
        bad_ip.write_text("Device serial: `10.27.27.190:34507`\n", encoding="utf-8")
        bad_vin.write_text('const val VIN = "1G1RD6S58HU000001"\n', encoding="utf-8")
        bad_gps.write_text("route: 34.11872,-118.30064\n", encoding="utf-8")
        allowed.write_text("route: 34.11872,-118.30064\n", encoding="utf-8")
        allowlist = Allowlist(("mobile/android/app/src/main/assets/dashboard/demos/efficiency.html",), ("<ip:port",))
        findings = scan_paths(root, [good, bad_ip, bad_vin, bad_gps, allowed], allowlist)
        kinds = sorted(f.kind for f in findings)
        expected = ["gps-coordinate-pair", "private-device-endpoint", "vin-like-token"]
        if kinds != expected:
            print(f"self-test failed: expected {expected}, got {kinds}", file=sys.stderr)
            return 1
    print("privacy-scan self-test passed")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".", help="repository root")
    parser.add_argument(
        "--allowlist",
        default="mobile/android/tools/privacy-scan-allowlist.txt",
        help="allowlist path relative to root",
    )
    parser.add_argument("--self-test", action="store_true", help="run built-in scanner tests")
    args = parser.parse_args(argv)

    if args.self_test:
        return run_self_test()

    root = Path(args.root).resolve()
    allowlist = load_allowlist(root / args.allowlist)
    findings = scan_paths(root, tracked_files(root), allowlist)
    if findings:
        print("Privacy scan failed. Do not commit real VIN, GPS route, or private device endpoint data.")
        for finding in findings:
            print(f"{finding.path}:{finding.line}: {finding.kind}: {finding.value}")
        print("\nIf this is synthetic test data, move it to a clearly synthetic fixture and add a narrow allowlist entry.")
        return 1
    print("Privacy scan passed: no unallowlisted VIN/GPS/private endpoint patterns found.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
