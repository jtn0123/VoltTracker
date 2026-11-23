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
        "./node_modules/.bin/playwright install --with-deps chromium",
        "semantic-release version",
        "semantic-release publish",
        "Warn loudly if no release was cut",
        "Strip build-only files from GitHub release",
        "Build and attach Android APKs",
        "Assemble release and debug APKs",
        "Stage APKs (version-tagged flavor filenames)",
        "Update release install notes",
        # All four signing secrets → stable-key signed APKs; none → dev-mode
        # debug-only fallback; a partial set must stay a hard error.
        "Incomplete release signing secrets",
        "Dev-mode release (no signing secrets)",
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


RELEASE_REQUIREMENTS = ".github/requirements-release.txt"
RELEASE_PIP_INSTALL = f'pip install --only-binary ":all:" --require-hashes -r {RELEASE_REQUIREMENTS}'
# Anything that pulls in or runs third-party build tooling. None of it may run
# in the job that holds the deploy key that can bypass main's ruleset.
DEPLOY_KEY_JOB_FORBIDDEN = [
    "release-preflight.sh",
    "gradlew",
    "npm ",
    "npx ",
    "playwright",
    "actions/setup-node",
    "actions/setup-java",
]


def workflow_jobs(workflow_text: str) -> dict[str, str]:
    """Split a workflow's top-level ``jobs:`` mapping into {job id: job text}.

    Full-line comments are dropped so prose cannot satisfy or trip a check.
    """
    lines = workflow_text.splitlines()
    try:
        start = lines.index("jobs:") + 1
    except ValueError as exc:
        raise ValueError("workflow has no top-level jobs: mapping") from exc
    jobs: dict[str, list[str]] = {}
    current: list[str] | None = None
    for line in lines[start:]:
        if line and not line[0].isspace():
            break
        job = re.match(r"^  ([A-Za-z0-9_-]+):\s*$", line)
        if job:
            current = jobs.setdefault(job.group(1), [])
            continue
        if current is not None and not line.lstrip().startswith("#"):
            current.append(line)
    return {name: "\n".join(body) for name, body in jobs.items()}


def validate_release_job_isolation(workflow_text: str) -> None:
    jobs = workflow_jobs(workflow_text)
    key_jobs = [name for name, body in jobs.items() if "RELEASE_PUSH_DEPLOY_KEY" in body]
    require_equal("jobs holding the release deploy key", key_jobs, ["release"])
    release = jobs["release"]
    for forbidden in DEPLOY_KEY_JOB_FORBIDDEN:
        if forbidden in release:
            raise ValueError(f"release job holds the deploy key and must not run {forbidden.strip()!r}")
    if "needs: preflight" not in release:
        raise ValueError("release job must wait for the preflight job (needs: preflight)")
    if RELEASE_PIP_INSTALL not in release:
        raise ValueError(f"release job must install semantic-release with {RELEASE_PIP_INSTALL!r}")
    if re.search(r"pip install(?![^\n]*--require-hashes)", release):
        raise ValueError("every pip install in the release job must use --require-hashes")

    preflight = jobs.get("preflight")
    if preflight is None:
        raise ValueError("release workflow must have a preflight job")
    for required in ("./scripts/release-preflight.sh", "fetch-depth: 0", "persist-credentials: false"):
        if required not in preflight:
            raise ValueError(f"preflight job missing {required!r}")
    for forbidden in ("ssh-key:", "contents: write"):
        if forbidden in preflight:
            raise ValueError(f"preflight job must not have {forbidden!r}")


def validate_release_requirements(requirements_in: str, requirements_txt: str) -> None:
    """The release requirements lock must pin every package by version and hash."""
    pins = [line.strip() for line in requirements_in.splitlines() if line.strip() and not line.lstrip().startswith("#")]
    if not pins:
        raise ValueError("release requirements input lists no packages")
    for pin in pins:
        if not re.fullmatch(r"[A-Za-z0-9_.-]+==\S+", pin):
            raise ValueError(f"release requirements input must pin an exact version: {pin!r}")
    locked = {}
    for entry in re.split(r"\n(?=[A-Za-z0-9])", requirements_txt):
        first = entry.lstrip("\n").splitlines()[0] if entry.strip() else ""
        if not first or first.startswith("#"):
            continue
        match = re.match(r"^([A-Za-z0-9_.-]+)==(\S+)", first)
        if not match:
            raise ValueError(f"release requirement not pinned with ==: {first!r}")
        if "--hash=sha256:" not in entry:
            raise ValueError(f"release requirement {match.group(1)!r} has no sha256 hash")
        locked[match.group(1).lower()] = match.group(2)
    for pin in pins:
        name, version = pin.split("==", 1)
        if locked.get(name.lower()) != version:
            raise ValueError(f"{RELEASE_REQUIREMENTS} does not lock {pin}; regenerate it with pip-compile")


def validate_android_gradle_signing(build_gradle_text: str) -> None:
    required_snippets = [
        "debug {",
        'if (rootProject.file("keystore.properties").exists())',
        "signingConfig = signingConfigs.release",
    ]
    for snippet in required_snippets:
        if snippet not in build_gradle_text:
            raise ValueError(f"Android build.gradle missing debug signing contract {snippet!r}")


def validate_release_preflight(preflight_text: str) -> None:
    required_snippets = [
        "./gradlew --no-daemon --max-workers=1 verifyActiveApp",
    ]
    for snippet in required_snippets:
        if snippet not in preflight_text:
            raise ValueError(f"release preflight missing {snippet!r}")


def main() -> int:
    config = semantic_release_config((ROOT / "pyproject.toml").read_bytes())
    validate_semantic_release_config(config)
    validate_pr_title_lint((ROOT / ".github/workflows/pr-title-lint.yml").read_text())
    release_workflow = (ROOT / ".github/workflows/release.yml").read_text()
    validate_release_workflow(release_workflow)
    validate_release_job_isolation(release_workflow)
    validate_release_requirements(
        (ROOT / ".github/requirements-release.in").read_text(),
        (ROOT / RELEASE_REQUIREMENTS).read_text(),
    )
    validate_android_gradle_signing((ROOT / "mobile/android/app/build.gradle").read_text())
    validate_release_preflight((ROOT / "scripts/release-preflight.sh").read_text())
    if not (ROOT / "VERSION").read_text().strip():
        raise ValueError("VERSION must not be empty")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
