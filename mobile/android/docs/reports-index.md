# Reports Index

This folder contains both current planning documents and historical audit
passes. Use this index to decide whether a report is active guidance or older
context.

| Report | Date | Status | Scope |
|---|---:|---|---|
| `release.md` | 2026-05-27 | Current release operations | Debug/latest-debug/tagged release APK behavior, signing secrets, and install notes. |
| `dashboard-script-contract.md` | 2026-05-27 | Current dashboard contract | Production dashboard script order and the test guarding generated/template drift. |
| `dashboard-bundle-budget.md` | 2026-05-27 | Current performance snapshot | First-party dashboard JS/CSS size budget and largest assets. |
| `dependency-report-2026-05-26.md` | 2026-05-26 | Current dependency snapshot | Active Android app dependencies, dashboard test tooling, and scheduled snapshot workflow. |
| `archive/debugging-issues-2026-05-26.md` | 2026-05-26 | Historical | Debugging findings from a focused Android pass. Recheck before acting. |
| `archive/android-bug-hunt-third-pass-30.md` | 2026-05-26 | Historical backlog | Third pass bug-hunt findings. Items may already be fixed. |
| `archive/android-polish-second-pass-30.md` | 2026-05-26 | Historical backlog | UI/polish findings from the second pass. Items may already be fixed. |
| `archive/android-polish-30-issues.md` | 2026-05-22 | Historical backlog | Earlier Android polish list. Superseded by later polish passes where duplicated. |
| `field-test-2026-05-19.md` | 2026-05-19 | Field-test reference | Real-world test notes and observations. |

For a fresh graded audit with addressable improvement IDs, run the
`grade-codebase` skill; its report is written to a repo-local, gitignored path.
Historical audit reports live under `archive/` so they stay available without
looking like current implementation guidance.
