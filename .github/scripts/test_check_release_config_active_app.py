#!/usr/bin/env python3
"""Tests for the verifyActiveApp release-build guard in check_release_config.py."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from check_release_config import ROOT, validate_active_app_builds_release  # noqa: E402

TASK_HEAD = 'tasks.register("verifyActiveApp") {\n    description = "Runs the suite."\n'


def gradle(depends_on_body: str, extra: str = "") -> str:
    return TASK_HEAD + extra + "    dependsOn(\n" + depends_on_body + ")\n}\n"


class ValidateActiveAppBuildsReleaseTest(unittest.TestCase):
    def test_current_build_gradle_passes(self) -> None:
        validate_active_app_builds_release((ROOT / "mobile/android/build.gradle").read_text())

    def test_dependency_present_passes(self) -> None:
        validate_active_app_builds_release(
            gradle('            ":app:assembleDebug",\n            ":app:assembleRelease",\n            "privacyScan"')
        )

    def test_missing_task_fails(self) -> None:
        with self.assertRaisesRegex(ValueError, "must register verifyActiveApp"):
            validate_active_app_builds_release('tasks.register("doctor") {\n}\n')

    def test_dependency_removed_fails(self) -> None:
        with self.assertRaisesRegex(ValueError, "must depend on :app:assembleRelease"):
            validate_active_app_builds_release(gradle('            ":app:assembleDebug",\n            "privacyScan"'))

    def test_whole_line_comment_fails(self) -> None:
        with self.assertRaises(ValueError):
            validate_active_app_builds_release(
                gradle('            ":app:assembleDebug",\n            // ":app:assembleRelease",\n            "privacyScan"')
            )

    def test_trailing_comment_fails(self) -> None:
        with self.assertRaises(ValueError):
            validate_active_app_builds_release(
                gradle('            ":app:assembleDebug", // ":app:assembleRelease" was here\n            "privacyScan"')
            )

    def test_block_comment_fails(self) -> None:
        with self.assertRaises(ValueError):
            validate_active_app_builds_release(
                gradle('            ":app:assembleDebug",\n            /* ":app:assembleRelease", */\n            "privacyScan"')
            )

    def test_multiline_block_comment_fails(self) -> None:
        with self.assertRaises(ValueError):
            validate_active_app_builds_release(
                gradle(
                    '            ":app:assembleDebug",\n            /*\n            ":app:assembleRelease",\n'
                    '            */\n            "privacyScan"'
                )
            )

    def test_reference_outside_depends_on_fails(self) -> None:
        with self.assertRaises(ValueError):
            validate_active_app_builds_release(
                gradle(
                    '            ":app:assembleDebug",\n            "privacyScan"',
                    extra='    mustRunAfter(":app:assembleRelease")\n',
                )
            )


if __name__ == "__main__":
    unittest.main()
