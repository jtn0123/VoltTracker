#!/usr/bin/env python3
"""Predict the semantic-release bump without publishing artifacts."""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import tomllib
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
TAG_RE = re.compile(r"^v(?P<version>\d+\.\d+\.\d+(?:[-.][0-9A-Za-z.-]+)?)$")
SUBJECT_RE = re.compile(r"^(?P<type>[a-z]+)(?:\([^)]+\))?(?P<breaking>!)?:")


@dataclass(frozen=True)
class Commit:
    sha: str
    subject: str
    body: str


def run_git(args: list[str], *, allow_failure: bool = False) -> str:
    result = subprocess.run(
        ["git", *args],
        cwd=ROOT,
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if result.returncode != 0:
        if allow_failure:
            return ""
        raise RuntimeError(result.stderr.strip() or f"git {' '.join(args)} failed")
    return result.stdout.strip()


def semantic_release_config() -> dict:
    data = tomllib.loads((ROOT / "pyproject.toml").read_text())
    return data["tool"]["semantic_release"]


def latest_release_tag() -> str:
    tags = run_git(["tag", "--list", "v[0-9]*"], allow_failure=True).splitlines()
    valid = [tag for tag in tags if TAG_RE.match(tag)]
    if not valid:
        return ""
    return run_git(["tag", "--sort=-v:refname", "--list", *valid]).splitlines()[0]


def commits_since(tag: str) -> list[Commit]:
    rev_range = f"{tag}..HEAD" if tag else "HEAD"
    raw = run_git(["log", rev_range, "--pretty=format:%H%x1f%B%x1e"], allow_failure=True)
    commits: list[Commit] = []
    for entry in raw.split("\x1e"):
        entry = entry.strip()
        if not entry:
            continue
        sha, _, message = entry.partition("\x1f")
        lines = message.strip().splitlines()
        subject = lines[0] if lines else ""
        body = "\n".join(lines[1:])
        commits.append(Commit(sha=sha[:12], subject=subject, body=body))
    return commits


def bump_for_commit(commit: Commit, config: dict) -> str:
    match = SUBJECT_RE.match(commit.subject)
    body_breaking = re.search(r"(?m)^BREAKING CHANGE:", commit.body) is not None
    if body_breaking or (match and match.group("breaking")):
        return "major"
    if not match:
        return ""
    commit_type = match.group("type")
    options = config["commit_parser_options"]
    if commit_type in options.get("minor_tags", []):
        return "minor"
    if commit_type in options.get("patch_tags", []):
        return "patch"
    return ""


def strongest_bump(commits: list[Commit], config: dict) -> tuple[str, list[tuple[Commit, str]]]:
    ranked = {"": 0, "patch": 1, "minor": 2, "major": 3}
    selected = ""
    bumping: list[tuple[Commit, str]] = []
    for commit in commits:
        bump = bump_for_commit(commit, config)
        if bump:
            bumping.append((commit, bump))
        if ranked[bump] > ranked[selected]:
            selected = bump
    return selected, bumping


def bump_version(version: str, bump: str) -> str:
    major, minor, patch = [int(part) for part in version.split(".")[:3]]
    if bump == "major":
        return f"{major + 1}.0.0"
    if bump == "minor":
        return f"{major}.{minor + 1}.0"
    if bump == "patch":
        return f"{major}.{minor}.{patch + 1}"
    return version


def current_version(config: dict) -> str:
    version = str(config.get("version", "")).strip()
    if not re.match(r"^\d+\.\d+\.\d+", version):
        raise ValueError(f"Unsupported semantic-release version: {version!r}")
    return version


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--summary-file", default=os.getenv("GITHUB_STEP_SUMMARY", ""))
    args = parser.parse_args()

    config = semantic_release_config()
    tag = latest_release_tag()
    commits = commits_since(tag)
    bump, bumping = strongest_bump(commits, config)
    current = current_version(config)
    predicted = bump_version(current, bump)
    tag_format = str(config.get("tag_format", "v{version}"))
    predicted_tag = tag_format.format(version=predicted)

    lines = [
        "## Semantic release dry run",
        "",
        f"- Current configured version: `{current}`",
        f"- Latest release tag: `{tag or 'none'}`",
        f"- Commits inspected: `{len(commits)}`",
    ]
    if bump:
        lines.append(f"- Predicted bump: `{bump}` -> `{predicted_tag}`")
    else:
        lines.append("- Predicted bump: `none` (no release would be cut)")
    lines.append("")
    lines.append("| Commit | Bump | Subject |")
    lines.append("|---|---|---|")
    for commit, commit_bump in bumping[:20]:
        subject = commit.subject.replace("|", "\\|")
        lines.append(f"| `{commit.sha}` | `{commit_bump}` | {subject} |")
    if not bumping:
        lines.append("| - | - | No bumpable commits found. |")
    output = "\n".join(lines) + "\n"
    print(output)
    if args.summary_file:
        with open(args.summary_file, "a", encoding="utf-8") as handle:
            handle.write(output)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"semantic release dry run failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
