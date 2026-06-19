# Reports Index

This folder contains both current planning documents and historical audit
passes. Use this index to decide whether a report is active guidance or older
context.

| Report | Date | Status | Scope |
|---|---:|---|---|
| `high-value-reliability-2026-06-05.md` | 2026-06-05 | Current implementation tracker | Highest-ROI post-migration work: runtime smoke, validation docs, and reliability follow-up priorities. |
| `validation-matrix.md` | 2026-06-05 | Current validation guide | Defines what each local, desktop, emulator, physical phone, and real-car validation level proves. |
| `s24-emulator-profile.md` | 2026-06-05 | Current emulator guide | Android 16/API 36 AVD setup matching Galaxy S24 display geometry for local WebView dogfooding. |
| `field-test-checklist.md` | 2026-06-05 | Current field-test guide | Repeatable real-car/adapter checklist for producing useful logs, screenshots, and fixture follow-up. |
| `release-candidate-checklist.md` | 2026-06-08 | Current release checklist | Release-candidate identity, preflight, runtime proof, and evidence checklist before tagging. |
| `performance-contracts.md` | 2026-06-17 | Current performance guide | Load, dashboard bundle, SQLite, route, OBD, and scan performance contracts plus validation commands. |
| `performance-playbook.md` | 2026-06-18 | Current performance recipe | Quick local recipes for startup, tabs, large DB, scan/OBD latency, and release-candidate performance checks. |
| `dependencies.md` | 2026-06-17 | Current dependency policy | Runtime pins, lockfile regeneration, audit commands, and dependency review rules. |
| `bridge-threat-model.md` | 2026-06-05 | Current security guide | WebView/native bridge trust boundaries, high-risk methods, and review rules for future bridge changes. |
| `language-migration.md` | 2026-06-04 | Current planning + tracker | Living plan/checklist for Kotlin adoption (Android) and TypeScript/build hardening (dashboard). Update in the same PR as the work. |
| `bug-hunt-conversion-2026-06-05.md` | 2026-06-05 | Current conversion audit | First post-migration bug hunt covering Kotlin/TypeScript guardrails, stale source contracts, and type-safety seams. |
| `bug-hunt-conversion-second-pass-2026-06-05.md` | 2026-06-05 | Current conversion audit | Second pass closing dashboard indexed-access risks surfaced by a stricter TypeScript probe. |
| `release.md` | 2026-05-27 | Current release operations | Debug/latest-debug/tagged release APK behavior, signing secrets, and install notes. |
| `dashboard-script-contract.md` | 2026-05-27 | Current dashboard contract | Production dashboard script order and the test guarding generated/template drift. |
| `dashboard-bundle-budget.md` | 2026-05-27 | Historical snapshot | First-party dashboard JS/CSS size budget and largest assets from the early budget split. Current numbers live in `bundle-budget.md`. |
| `dependency-report-2026-05-26.md` | 2026-05-26 | Historical snapshot | Android/dashboard dependency snapshot from 2026-05-26. Current policy and commands live in `dependencies.md`. |
| `archive/debugging-issues-2026-05-26.md` | 2026-05-26 | Historical | Debugging findings from a focused Android pass. Recheck before acting. |
| `archive/android-bug-hunt-third-pass-30.md` | 2026-05-26 | Historical backlog | Third pass bug-hunt findings. Items may already be fixed. |
| `archive/android-polish-second-pass-30.md` | 2026-05-26 | Historical backlog | UI/polish findings from the second pass. Items may already be fixed. |
| `archive/android-polish-30-issues.md` | 2026-05-22 | Historical backlog | Earlier Android polish list. Superseded by later polish passes where duplicated. |
| `field-test-2026-05-19.md` | 2026-05-19 | Field-test reference | Real-world test notes and observations. |

For a fresh graded audit with addressable improvement IDs, run the
`grade-codebase` skill; its report is written to repo-local, gitignored
`.Codex/grade-report.md`. Historical audit reports live under `archive/` so
they stay available without looking like current implementation guidance.
