#!/usr/bin/env python3
"""Measure the built dashboard bundle against its size budgets.

Both CI steps that report bundle size used to re-declare the chunk grouping in
their own syntax -- one in Python, one in `find ! -name ...` -- while reading only
the budget NUMBERS from build.gradle. Each claimed in a comment that it therefore
could not drift from the Gradle gate. Both had drifted:

  * `lazy_support` still excluded only insights-panel.js and the DTC pair, so the
    expert (signals-panel, scrubber), detail (charge-history, maintenance-panel,
    dtc-detail) and second panel (connection-tools) chunks were all charged to the
    support allowance. It reported -40294 bytes of headroom against a real +7496.
  * `startup` counted every CSS file, including the two lazy-injected stylesheets
    that build.gradle deliberately excludes, overstating it by ~17 KB and tripping
    the "under 15 KB of budget" warning that exists to flag genuine crowding.

A budget report that cries wolf is worse than no report: it trains readers to skip
the one line that would have caught a real regression.

So this script reads the file GROUPS from build.gradle too, and both steps call it.
There is one implementation and one source of truth. Adding a chunk to a group in
build.gradle moves it here automatically; adding a new chunk to no group leaves it
in the support catch-all, exactly as the Gradle gate treats it.

Usage:
  dashboard_bundle_report.py snapshot --out-dir DIR   # performance-summary.{json,md}
  dashboard_bundle_report.py report                   # per-file table + headroom + warnings

Both subcommands append their markdown to $GITHUB_STEP_SUMMARY when it is set.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path
from typing import NoReturn

# Budget names in build.gradle, paired with the group whose bytes they cap.
BUDGETS = {
    "startup": "dashboardStartupBudgetBytes",
    "lazy_support": "dashboardLazySupportBudgetBytes",
    "lazy_panel": "dashboardLazyPanelBudgetBytes",
    "lazy_expert": "dashboardLazyExpertBudgetBytes",
    "lazy_detail": "dashboardLazyDetailBudgetBytes",
    "lazy_css": "dashboardLazyCssBudgetBytes",
    "dtc_data": "dashboardDtcDataBudgetBytes",
}

# Explicit file lists in build.gradle. Everything under js/ that none of these
# claims (and that is not app.js) is the lazy-support catch-all, mirroring
# dashboardLazySupportAssets.
FILE_GROUPS = {
    "lazy_panel": "dashboardLazyPanelFiles",
    "lazy_expert": "dashboardLazyExpertFiles",
    "lazy_detail": "dashboardLazyDetailFiles",
    "dtc_data": "dashboardDtcDataFiles",
    "lazy_css": "dashboardLazyCssFiles",
}

# Human labels for the headroom table, in report order.
LABELS = [
    ("startup", "startup JS/CSS (app.js + eager CSS)"),
    ("lazy_support", "lazy support JS (unclaimed chunks)"),
    ("lazy_panel", "lazy panel JS"),
    ("lazy_expert", "lazy expert JS"),
    ("lazy_detail", "lazy detail JS"),
    ("lazy_css", "lazy CSS (map + troubleshooter)"),
    ("dtc_data", "DTC data"),
]

WARN_FLOOR = 15000


def fail(title: str, message: str) -> NoReturn:
    print(f"::error title={title}::{message}", file=sys.stderr)
    raise SystemExit(1)


def read_budget(gradle: str, name: str) -> int:
    """`def dashboardStartupBudgetBytes = 412_000L` -> 412000."""
    match = re.search(rf"^def {name} = ([0-9_]+)L", gradle, flags=re.M)
    if not match:
        fail(
            "Dashboard bundle budget",
            f"could not extract {name} from build.gradle (declaration renamed/reformatted?)",
        )
    return int(match.group(1).replace("_", ""))


def read_file_group(gradle: str, name: str) -> list:
    """`def dashboardLazyPanelFiles = ["js/a.js", "js/b.js"]` -> ["js/a.js", "js/b.js"]."""
    match = re.search(rf"^def {name} = \[([^\]]*)\]", gradle, flags=re.M)
    if not match:
        fail(
            "Dashboard bundle budget",
            f"could not extract {name} from build.gradle (declaration renamed/reformatted?)",
        )
    members = re.findall(r'"([^"]+)"', match.group(1))
    if not members:
        fail("Dashboard bundle budget", f"{name} in build.gradle parsed as empty")
    return members


def check_coverage(gradle: str) -> None:
    """Fail if build.gradle declares a budget or file group this script does not know about.

    Reading the groups from build.gradle stops the OLD drift (a chunk moving between
    existing groups). It does not stop a NEW group being added there and silently
    falling into this script's support catch-all -- which is the same bug wearing a
    different hat. So the declaration sets have to match exactly, and adding a budget
    without adding it here is a hard error rather than a quietly wrong number.
    """
    for pattern, known, kind in (
        (r"^def (dashboard\w*BudgetBytes) = ", set(BUDGETS.values()), "budget"),
        (r"^def (dashboard\w*Files) = \[", set(FILE_GROUPS.values()), "file group"),
    ):
        declared = set(re.findall(pattern, gradle, flags=re.M))
        unknown = sorted(declared - known)
        stale = sorted(known - declared)
        if unknown:
            fail(
                "Dashboard bundle budget",
                f"build.gradle declares {kind}(s) {', '.join(unknown)} that "
                f"tools/dashboard_bundle_report.py does not measure -- add them to "
                f"BUDGETS/FILE_GROUPS so the report cannot understate a new bucket",
            )
        if stale:
            fail(
                "Dashboard bundle budget",
                f"tools/dashboard_bundle_report.py expects {kind}(s) {', '.join(stale)} "
                f"that build.gradle no longer declares",
            )


def measure(root: Path, gradle_path: Path) -> dict:
    gradle = gradle_path.read_text(encoding="utf-8")
    check_coverage(gradle)
    budgets = {key: read_budget(gradle, name) for key, name in BUDGETS.items()}
    groups = {key: read_file_group(gradle, name) for key, name in FILE_GROUPS.items()}

    def size(rel: str) -> int:
        path = root / rel
        return path.stat().st_size if path.is_file() else 0

    def group_bytes(key: str) -> int:
        return sum(size(rel) for rel in groups[key])

    js_files = sorted(p.name for p in (root / "js").glob("*.js")) if (root / "js").is_dir() else []
    css_files = sorted(p.name for p in (root / "css").glob("*.css")) if (root / "css").is_dir() else []
    per_file = [(f"js/{name}", size(f"js/{name}")) for name in js_files]
    per_file += [(f"css/{name}", size(f"css/{name}")) for name in css_files]

    claimed = {rel for members in groups.values() for rel in members}
    # Mirror dashboardLazySupportAssets exactly: it excludes app.js plus the panel,
    # expert, detail and DTC lists -- NOT the lazy-CSS list. Subtracting every claimed
    # path here instead would diverge from the Gradle gate the moment a group's name
    # stopped predicting its file extension, which is the failure mode being fixed.
    js_claimed = {
        rel
        for key in ("lazy_panel", "lazy_expert", "lazy_detail", "dtc_data")
        for rel in groups[key]
    }
    css_claimed = set(groups["lazy_css"])

    eager_js = size("js/app.js")
    lazy_js = sum(size(f"js/{name}") for name in js_files if name != "app.js")
    all_css = sum(size(f"css/{name}") for name in css_files)
    eager_css = sum(size(f"css/{name}") for name in css_files if f"css/{name}" not in css_claimed)
    lazy_support = sum(
        size(f"js/{name}")
        for name in js_files
        if name != "app.js" and f"js/{name}" not in js_claimed
    )

    leaflet_dir = root / "lib/leaflet"
    leaflet = (
        sum(p.stat().st_size for p in leaflet_dir.rglob("*") if p.is_file())
        if leaflet_dir.is_dir()
        else 0
    )

    actual = {
        "startup": eager_js + eager_css,
        "lazy_support": lazy_support,
        "lazy_panel": group_bytes("lazy_panel"),
        "lazy_expert": group_bytes("lazy_expert"),
        "lazy_detail": group_bytes("lazy_detail"),
        "lazy_css": group_bytes("lazy_css"),
        "dtc_data": group_bytes("dtc_data"),
    }

    # A group entry naming a file the build no longer emits silently shrinks that
    # group's measured bytes and inflates its headroom -- the same quiet-wrong-number
    # failure this script exists to end. Surface it rather than reporting a fiction.
    missing = sorted(rel for rel in claimed if not (root / rel).is_file())

    return {
        "budgets": budgets,
        "actual": actual,
        "headroom": {key: budgets[key] - actual[key] for key in budgets},
        "eager_js": eager_js,
        "lazy_js": lazy_js,
        "css": all_css,
        "eager_css": eager_css,
        "leaflet": leaflet,
        "html": size("index.html"),
        "per_file": per_file,
        "missing": missing,
    }


def emit_step_summary(lines: list) -> None:
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not path:
        return
    with open(path, "a", encoding="utf-8") as fh:
        fh.write("\n".join(["", *lines]) + "\n")


def warn_missing(m: dict) -> None:
    for rel in m["missing"]:
        print(
            f"::warning title=Dashboard bundle budget::{rel} is listed in a build.gradle "
            "budget group but was not emitted by the build (stale entry?)"
        )


def headroom_table(m: dict) -> list:
    lines = [
        "| Bundle | Bytes | Budget | Headroom |",
        "|--------|------:|-------:|---------:|",
    ]
    for key, label in LABELS:
        lines.append(f"| {label} | {m['actual'][key]} | {m['budgets'][key]} | {m['headroom'][key]} |")
    return lines


def cmd_report(m: dict) -> int:
    lines = ["## Dashboard bundle size", "", "| Path | Bytes |", "|------|-------|"]
    lines += [f"| {rel} | {size} |" for rel, size in m["per_file"]]
    lines += ["", "### Budget headroom", "", *headroom_table(m)]

    # Advisory only. The Gradle verifyDashboardBundleSize task is the hard gate; this
    # step never fails the build, it just says how much room is left.
    warnings = [
        (label, m["headroom"][key], m["budgets"][key])
        for key, label in LABELS
        if m["headroom"][key] < WARN_FLOOR
    ]
    for label, headroom, budget in warnings:
        print(
            f"::warning title=Dashboard bundle headroom::{label} headroom is {headroom} bytes "
            f"(under 15 KB of the {budget}-byte budget)"
        )
    if warnings:
        lines.append("")
        for label, headroom, _ in warnings:
            lines.append(f"WARNING: {label} headroom is {headroom} bytes (under 15 KB).")
    warn_missing(m)
    emit_step_summary(lines)
    return 0


def cmd_snapshot(m: dict, out_dir: Path) -> int:
    out_dir.mkdir(parents=True, exist_ok=True)
    payload = {
        "run_id": os.environ.get("GITHUB_RUN_ID", ""),
        "run_attempt": os.environ.get("GITHUB_RUN_ATTEMPT", ""),
        "sha": os.environ.get("GITHUB_SHA", ""),
        "startup_probe": {
            "macrobenchmark_task": "./gradlew verifyStartupPerformance --no-configuration-cache",
            "ready_description": "VoltTracker dashboard ready",
            "device_required": True,
        },
        "asset_bytes": {
            "eager_js": m["eager_js"],
            "lazy_js": m["lazy_js"],
            "css": m["css"],
            "eager_css": m["eager_css"],
            "leaflet": m["leaflet"],
            "generated_html": m["html"],
            "startup_js_css": m["actual"]["startup"],
            "lazy_support_js": m["actual"]["lazy_support"],
            "lazy_panel_js": m["actual"]["lazy_panel"],
            "lazy_expert_js": m["actual"]["lazy_expert"],
            "lazy_detail_js": m["actual"]["lazy_detail"],
            "lazy_css": m["actual"]["lazy_css"],
            "dtc_data": m["actual"]["dtc_data"],
        },
        "budgets": dict(m["budgets"]),
        "headroom": dict(m["headroom"]),
    }
    (out_dir / "performance-summary.json").write_text(
        json.dumps(payload, indent=2) + "\n", encoding="utf-8"
    )

    lines = [
        "### Performance trend snapshot",
        "",
        "| Metric | Bytes |",
        "|---|---:|",
        f"| Eager JS | {m['eager_js']} |",
        f"| Lazy JS | {m['lazy_js']} |",
        f"| CSS (all) | {m['css']} |",
        f"| Leaflet assets | {m['leaflet']} |",
        f"| Generated HTML | {m['html']} |",
        "",
        "#### Budget headroom",
        "",
        *headroom_table(m),
    ]
    (out_dir / "performance-summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    warn_missing(m)
    emit_step_summary(lines)
    return 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["report", "snapshot"])
    parser.add_argument(
        "--root",
        type=Path,
        default=Path("app/src/main/assets/dashboard"),
        help="Built dashboard asset dir, relative to mobile/android",
    )
    parser.add_argument(
        "--gradle", type=Path, default=Path("build.gradle"), help="Path to mobile/android/build.gradle"
    )
    parser.add_argument("--out-dir", type=Path, default=Path("build/reports/performance-trends"))
    args = parser.parse_args()

    measured = measure(args.root, args.gradle)
    raise SystemExit(
        cmd_report(measured) if args.command == "report" else cmd_snapshot(measured, args.out_dir)
    )
