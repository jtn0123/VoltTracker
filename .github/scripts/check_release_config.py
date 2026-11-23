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
DEPLOY_KEY_SECRET = "RELEASE_PUSH_DEPLOY_KEY"
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
# The key-holding job may only use these actions, and every other step must be
# one of these named steps. A new step there has to be added here on purpose.
DEPLOY_KEY_JOB_ACTIONS = ("actions/checkout", "actions/setup-python")
DEPLOY_KEY_JOB_STEPS = (
    "Install python-semantic-release",
    "Semantic Release — version & publish",
    "Resolve released tag",
    "Warn loudly if no release was cut",
    "Strip build-only files from GitHub release",
)
PIP_INSTALL_RE = re.compile(r"\bpip[0-9.]*\s+install\b")
WRITE_PERMISSION_RE = re.compile(r"(?m)^\s*[a-z-]+:\s*write\b|write-all")


def strip_comment_lines(text: str) -> str:
    """Drop full-line YAML comments so prose cannot satisfy or trip a check."""
    return "\n".join(line for line in text.splitlines() if not line.lstrip().startswith("#"))


def workflow_jobs(workflow_text: str) -> dict[str, str]:
    """Split a workflow's top-level ``jobs:`` mapping into {job id: job text}.

    Full-line comments are dropped so prose cannot satisfy or trip a check.
    """
    lines = strip_comment_lines(workflow_text).splitlines()
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
        if current is not None:
            current.append(line)
    return {name: "\n".join(body) for name, body in jobs.items()}


def job_steps(job_text: str) -> list[str]:
    """Split a job's ``steps:`` list into one text block per step."""
    _, found, steps_text = job_text.partition("\n    steps:\n")
    if not found:
        return []
    return [step for step in re.split(r"(?m)^      - ", steps_text) if step.strip()]


def validate_deploy_key_job_steps(release: str) -> None:
    for step in job_steps(release):
        uses = re.search(r"(?m)^(?:\s*)uses:\s*(\S+)", step)
        if uses:
            action = uses.group(1).split("@", 1)[0]
            if action not in DEPLOY_KEY_JOB_ACTIONS:
                raise ValueError(f"release job holds the deploy key and must not use {action!r}")
            continue
        name = re.search(r"(?m)^(?:\s*)name:\s*(.+?)\s*$", step)
        if not name or name.group(1) not in DEPLOY_KEY_JOB_STEPS:
            label = name.group(1) if name else step.splitlines()[0].strip()
            raise ValueError(f"release job holds the deploy key and has an unexpected step {label!r}")


def validate_release_job_isolation(workflow_text: str) -> None:
    jobs = workflow_jobs(workflow_text)
    if strip_comment_lines(workflow_text).count(DEPLOY_KEY_SECRET) != 1:
        raise ValueError(f"{DEPLOY_KEY_SECRET} must be referenced exactly once, in the release job's checkout")
    key_jobs = [name for name, body in jobs.items() if DEPLOY_KEY_SECRET in body]
    require_equal("jobs holding the release deploy key", key_jobs, ["release"])
    release = jobs["release"]
    for forbidden in DEPLOY_KEY_JOB_FORBIDDEN:
        if forbidden in release:
            raise ValueError(f"release job holds the deploy key and must not run {forbidden.strip()!r}")
    for required in ("needs: preflight", "fetch-depth: 0"):
        if required not in release:
            raise ValueError(f"release job missing {required!r}")
    if RELEASE_PIP_INSTALL not in release:
        raise ValueError(f"release job must install semantic-release with {RELEASE_PIP_INSTALL!r}")
    for line in release.splitlines():
        if PIP_INSTALL_RE.search(line) and "--require-hashes" not in line:
            raise ValueError("every pip install in the release job must use --require-hashes")
    validate_deploy_key_job_steps(release)

    preflight = jobs.get("preflight")
    if preflight is None:
        raise ValueError("release workflow must have a preflight job")
    for required in (
        "./scripts/release-preflight.sh",
        "fetch-depth: 0",
        "persist-credentials: false",
        "contents: read",
    ):
        if required not in preflight:
            raise ValueError(f"preflight job missing {required!r}")
    if "ssh-key:" in preflight:
        raise ValueError("preflight job must not have 'ssh-key:'")
    if WRITE_PERMISSION_RE.search(preflight):
        raise ValueError("preflight job must not have any write permission")


def validate_release_lock_install_on_pr(dry_run_workflow_text: str) -> None:
    """PRs must install the lock the way the release job does, before it reaches main."""
    if RELEASE_PIP_INSTALL not in strip_comment_lines(dry_run_workflow_text):
        raise ValueError(f"release dry run must install the release lock with {RELEASE_PIP_INSTALL!r}")


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
    validate_release_lock_install_on_pr((ROOT / ".github/workflows/release-dry-run.yml").read_text())
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
