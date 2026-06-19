#!/usr/bin/env python3
"""Build a compact Markdown index for local VoltTracker performance reports."""

from __future__ import annotations

import argparse
import json
import tempfile
from pathlib import Path


def summarize_report(path: Path) -> dict[str, str]:
    if not path.exists():
        return {"status": "missing", "path": str(path)}
    text = path.read_text(encoding="utf-8", errors="replace")
    status = "present"
    for line in text.splitlines():
        if line.startswith("Status:"):
            status = line.split("`", 2)[1] if "`" in line else line.removeprefix("Status:").strip()
            break
    return {"status": status, "path": str(path)}


def write_index(output: Path, entries: list[tuple[str, Path]]) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    rows = [("| Report | Status | Path |"), ("|---|---|---|")]
    payload = {}
    for name, path in entries:
        item = summarize_report(path)
        payload[name] = item
        rows.append(f"| {name} | `{item['status']}` | `{item['path']}` |")
    output.write_text("# Local Performance Report Index\n\n" + "\n".join(rows) + "\n", encoding="utf-8")
    output.with_suffix(".json").write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


def self_test() -> int:
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        done = root / "startup.md"
        missing = root / "missing.md"
        out = root / "index.md"
        done.write_text("# Report\n\nStatus: `completed`\n", encoding="utf-8")
        write_index(out, [("startup", done), ("tabs", missing)])
        text = out.read_text(encoding="utf-8")
        data = json.loads(out.with_suffix(".json").read_text(encoding="utf-8"))
        assert "| startup | `completed` |" in text
        assert data["tabs"]["status"] == "missing"
    print("report_index self-test passed")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=False)
    parser.add_argument("--entry", action="append", default=[], help="name=path")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    if not args.output:
        raise SystemExit("--output is required")
    entries: list[tuple[str, Path]] = []
    for raw in args.entry:
        if "=" not in raw:
            raise SystemExit(f"Invalid --entry {raw!r}; expected name=path")
        name, path = raw.split("=", 1)
        entries.append((name, Path(path)))
    write_index(Path(args.output), entries)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
