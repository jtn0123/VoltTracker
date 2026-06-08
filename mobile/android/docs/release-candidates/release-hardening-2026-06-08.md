# Release Candidate Evidence - Release Hardening

## Candidate Identity

- Branch: release
- Commit: final release-hardening commit on `release`
- Version / expected tag: semantic-release dry run predicts the tag before release
- APK under test: debug APK built by `scripts/release-preflight.sh`
- Phone or emulator: not attached in this local automation pass
- WebView version: not captured locally; attach emulator/device smoke artifacts when available
- OBD adapter: not attached in this automation pass
- Vehicle state: not attached in this automation pass

## Required Local Proof

- `scripts/release-preflight.sh`: required for release handoff
- `python .github/scripts/semantic_release_dry_run.py` or Release dry run workflow: required
- Generated dashboard clean: covered by `verifyActiveApp`
- Bundle budget: covered by `verifyActiveApp`
- Dependency audit status: covered by release preflight

## Runtime Proof Reached

- Highest validation level reached: local JVM, dashboard unit/coverage, desktop Playwright, and debug APK build
- Desktop dashboard: covered by dashboard Vitest coverage and Playwright e2e
- Emulator WebView: pending CI `emulator-smoke` or local `RUN_EMULATOR_SMOKE=1`
- Physical phone: not run in this automation pass
- Real adapter: not run in this automation pass
- Real car / OBD: not run in this automation pass

## Evidence To Attach

- JSONL session log: required when real adapter or real-car behavior changed
- SQLite database pull: required when Trips, Map, Charge, Insights, backups, or stored history changed
- Screenshots or screen recording: emulator smoke and dashboard visual artifacts
- Playwright / emulator smoke artifacts: Playwright is covered locally; emulator smoke is pending CI or attached hardware
- Notes for unreproduced or intentionally skipped checks: emulator, physical phone, adapter, and real-car proof are explicitly skipped unless hardware is attached

## Release Decision

- Ready to tag: no, not until the final commit has workflow dry-run plus any required emulator/device/runtime artifacts
- Blocking issues: none recorded in this evidence stub
- Follow-up issues: attach physical-device or real-car evidence when runtime behavior changes
- Reviewer: release owner
- Date: 2026-06-08
