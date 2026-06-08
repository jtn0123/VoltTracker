#!/usr/bin/env python3
"""Validate the semantic-release and PR-title release contract."""

from __future__ import annotations

import re
import tomllib
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
EXPECTED_ALLOWED_TAGS = [
    "feat",
    "fix",
    "perf",
    "refactor",
    "docs",
    "style",
    "test",
    "build",
    "ci",
    "chore",
    "revert",
]
EXPECTED_MINOR_TAGS = ["feat"]
EXPECTED_PATCH_TAGS = ["fix", "perf"]


def semantic_release_config(pyproject_text: bytes) -> dict:
    data = tomllib.loads(pyproject_text.decode())
    return data["tool"]["semantic_release"]


def semantic_release_version_target(config: dict) -> str:
    target = config["version_toml"]
    if not isinstance(target, list) or not target:
        raise ValueError("missing semantic-release version_toml setting")
    return str(target[0])


def require_equal(name: str, actual: object, expected: object) -> None:
    if actual != expected:
        raise ValueError(f"unexpected {name}: {actual!r} != {expected!r}")


def validate_semantic_release_config(config: dict) -> None:
    require_equal(
        "semantic-release version target",
        semantic_release_version_target(config),
        "pyproject.toml:tool.semantic_release.version",
    )
    require_equal("semantic-release branch", config.get("branch"), "main")
    require_equal("semantic-release tag_format", config.get("tag_format"), "v{version}")
    require_equal("semantic-release assets", config.get("assets"), ["VERSION"])

    build_command = str(config.get("build_command", ""))
    if "NEW_VERSION" not in build_command or "VERSION" not in build_command:
        raise ValueError("build_command must write NEW_VERSION to VERSION")

    parser_options = config["commit_parser_options"]
    require_equal("allowed_tags", parser_options.get("allowed_tags"), EXPECTED_ALLOWED_TAGS)
    require_equal("minor_tags", parser_options.get("minor_tags"), EXPECTED_MINOR_TAGS)
    require_equal("patch_tags", parser_options.get("patch_tags"), EXPECTED_PATCH_TAGS)


def pr_title_lint_types(workflow_text: str) -> list[str]:
    match = re.search(r"(?ms)^\s+types:\s*\|\n(?P<body>(?:\s{12}\w+\n)+)", workflow_text)
    if not match:
        raise ValueError("could not find semantic-pull-request types block")
    return [line.strip() for line in match.group("body").splitlines() if line.strip()]


def validate_pr_title_lint(workflow_text: str) -> None:
    require_equal("PR title lint types", pr_title_lint_types(workflow_text), EXPECTED_ALLOWED_TAGS)
    for required in ("subjectPattern:", "requireScope: false"):
        if required not in workflow_text:
            raise ValueError(f"PR title lint missing {required}")


def validate_release_workflow(workflow_text: str) -> None:
    required_snippets = [
        "python .github/scripts/check_release_config.py",
        "Install release dashboard e2e deps",
        "Install release Playwright Chromium + OS deps",
        "npx playwright install --with-deps chromium",
        "semantic-release version",
        "semantic-release publish",
        "Warn loudly if no release was cut",
        "Strip build-only files from GitHub release",
        "Build and attach Android APKs",
        "Assemble release and debug APKs",
        "Stage APKs (version-tagged flavor filenames)",
        "Update release install notes",
        "Missing release signing secrets",
        "ANDROID_KEYSTORE_BASE64: ${{ secrets.ANDROID_KEYSTORE_BASE64 }}",
        'printf \'%s\' "${ANDROID_KEYSTORE_BASE64}" | base64 --decode > app/release.keystore',
        "volttracker-${TAG}-release.apk",
        "volttracker-${TAG}-debug.apk",
    ]
    for snippet in required_snippets:
        if snippet not in workflow_text:
            raise ValueError(f"release workflow missing {snippet!r}")

    if "'^v[0-9]+\\.[0-9]+\\.[0-9]+" not in workflow_text:
        raise ValueError("release workflow must resolve vX.Y.Z tags from HEAD")

    if "release-unsigned.apk" in workflow_text:
        raise ValueError("tagged release workflow must not publish unsigned release APKs")


def validate_android_gradle_signing(build_gradle_text: str) -> None:
    required_snippets = [
        "debug {",
        'if (rootProject.file("keystore.properties").exists())',
        "signingConfig = signingConfigs.release",
    ]
    for snippet in required_snippets:
        if snippet not in build_gradle_text:
            raise ValueError(f"Android build.gradle missing debug signing contract {snippet!r}")


def main() -> int:
    config = semantic_release_config((ROOT / "pyproject.toml").read_bytes())
    validate_semantic_release_config(config)
    validate_pr_title_lint((ROOT / ".github/workflows/pr-title-lint.yml").read_text())
    validate_release_workflow((ROOT / ".github/workflows/release.yml").read_text())
    validate_android_gradle_signing((ROOT / "mobile/android/app/build.gradle").read_text())
    if not (ROOT / "VERSION").read_text().strip():
        raise ValueError("VERSION must not be empty")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
