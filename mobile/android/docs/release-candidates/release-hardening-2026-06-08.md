# Release Candidate Evidence - Release Hardening

## Candidate Identity

- Branch: main via `codex/main-release-hardening-autoconnect`
- Commit: `3ff180f` PR head; semantic-release will tag the merged `main` commit
- Version / expected tag: `v0.8.1`
- APK under test: debug APK built by `scripts/release-preflight.sh`; signed release APK is built and attached by the Release workflow after the tag is cut
- Phone or emulator: GitHub Actions emulator smoke is required before merge
- WebView version: captured by CI emulator smoke artifacts
- OBD adapter: not attached in this automation pass
- Vehicle state: not attached in this automation pass

## Required Local Proof

- `scripts/release-preflight.sh`: required for release handoff
- `python .github/scripts/semantic_release_dry_run.py` or Release dry run workflow: required
- GitHub PR checks: required before merge to `main`
- Generated dashboard clean: covered by `verifyActiveApp`
- Bundle budget: covered by `verifyActiveApp`
- Dependency audit status: covered by release preflight

## Runtime Proof Reached

- Highest validation level reached: local JVM, dashboard unit/coverage, desktop Playwright, debug APK build, and CI emulator smoke
- Desktop dashboard: covered by dashboard Vitest coverage and Playwright e2e
- Emulator WebView: covered by required GitHub Actions `emulator-smoke`
- Physical phone: not run in this automation pass
- Real adapter: not run in this automation pass
- Real car / OBD: not run in this automation pass

## Evidence To Attach

- JSONL session log: required when real adapter or real-car behavior changed
- SQLite database pull: required when Trips, Map, Charge, Insights, backups, or stored history changed
- Screenshots or screen recording: emulator smoke and dashboard visual artifacts
- Playwright / emulator smoke artifacts: Playwright is covered locally; emulator smoke is covered by CI before merge
- Notes for unreproduced or intentionally skipped checks: emulator, physical phone, adapter, and real-car proof are explicitly skipped unless hardware is attached

## Release Decision

- Ready to tag: yes
- Blocking issues: none recorded in this evidence stub
- Follow-up issues: attach physical-device or real-car evidence when hardware is available; auto-connect behavior is covered here by native unit tests, bridge tests, dashboard tests, and CI emulator smoke
- Reviewer: release owner
- Date: 2026-06-08
