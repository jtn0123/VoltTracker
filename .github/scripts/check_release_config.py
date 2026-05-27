#!/usr/bin/env python3
"""Validate the semantic-release version source of truth."""

from __future__ import annotations

import tomllib
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def semantic_release_version_target(pyproject_text: bytes) -> str:
    data = tomllib.loads(pyproject_text.decode())
    target = data["tool"]["semantic_release"]["version_toml"]
    if not isinstance(target, list) or not target:
        raise ValueError("missing semantic-release version_toml setting")
    return str(target[0])


def main() -> int:
    target = semantic_release_version_target((ROOT / "pyproject.toml").read_bytes())
    if target != "pyproject.toml:tool.semantic_release.version":
        raise ValueError(f"unexpected semantic-release version target: {target}")
    if not (ROOT / "VERSION").read_text().strip():
        raise ValueError("VERSION must not be empty")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
