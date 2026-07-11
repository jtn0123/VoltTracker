# Reports Index

This folder contains both current planning documents and historical audit
passes. Use this index to decide whether a report is active guidance or older
context. Dated one-shot reports get moved to `archive/` once their findings
are landed or superseded.

## Current guides and contracts

| Report | Status | Scope |
|---|---|---|
| `validation-matrix.md` | Current validation guide | Defines what each local, desktop, emulator, physical phone, and real-car validation level proves. |
| `s24-emulator-profile.md` | Current emulator guide | Android 16/API 36 AVD setup matching Galaxy S24 display geometry for local WebView dogfooding. |
| `field-test-checklist.md` | Current field-test guide | Repeatable real-car/adapter checklist for producing useful logs, screenshots, and fixture follow-up. |
| `release-candidate-checklist.md` | Current release checklist | Release-candidate identity, preflight, runtime proof, and evidence checklist before tagging. |
| `release.md` | Current release operations | Debug/latest-debug/tagged release APK behavior, signing secrets, and install notes. |
| `performance-contracts.md` | Current performance guide | Load, dashboard bundle, SQLite, route, OBD, and scan performance contracts plus validation commands. |
| `performance-playbook.md` | Current performance guide | How to measure, budget, and regress-check performance changes. |
| `performance-baseline-history.md` | Current performance log | Rolling history of measured performance baselines. |
| `dependencies.md` | Current dependency policy | Runtime pins, lockfile regeneration, audit commands, and dependency review rules. |
| `bundle-budget.md` / `dashboard-bundle-budget.md` | Current performance snapshot | First-party dashboard JS/CSS size budgets and largest assets. |
| `bridge-abi.md` | Current bridge contract | WebView/native bridge method surface and compatibility rules. |
| `bridge-threat-model.md` | Current security guide | WebView/native bridge trust boundaries, high-risk methods, and review rules for future bridge changes. |
| `dashboard-script-contract.md` | Current dashboard contract | Production dashboard script order and the test guarding generated/template drift. |
| `data-model.md` | Current data reference | SQLite schema, materialized views, and data lifecycle. |
| `privacy-data-handling.md` | Current privacy reference | What data the app stores, where, and how exports/backups handle it. |
| `glossary.md` | Current reference | Project-specific terms (PIDs, modes, session types). |
| `language-migration.md` | Living tracker | Kotlin adoption (Android) and TypeScript/build hardening (dashboard). Update in the same PR as the work. |
| `mobile-architecture-roadmap.md` | Living roadmap | Longer-horizon architecture direction for the Android app. |
| `dashboard-ux-plan.md` | Active plan (2026-07-04) | Dashboard UX polish workstream. |
| `enhanced-discovery-tracker.md` | Living field ledger | What detailed-signal probes already succeeded/failed on the real car. Update after every real-car probe run. |
| `sensor-expansion-plan-2026-06-09.md` | Active plan | Sensor expansion approach; pairs with the discovery tracker. |
| `obd-field-capture-handoff.md` | Current field guide | How to capture and hand off real-car OBD logs. |
| `pid-validation-2026-06-03.md` | Current PID reference | Which Volt PIDs are validated against the real car (referenced from decoder source). |
| `volt-pid-research-2026-05-20.md` / `volt-pids-community-sheet.csv` | Reference | Community PID research inputs. |
| `field-test-2026-05-19.md` | Field-test reference | Real-world test notes and observations (referenced from TripMaterializer source). |

## Recent audit passes (still being worked)

| Report | Date | Scope |
|---|---:|---|
| `bug-hunt-2026-07-09-pass3.md` | 2026-07-09 | Third July bug-hunt pass. |
| `bug-hunt-2026-07-06.md` / `bug-hunt-2026-07-06-pass2.md` | 2026-07-06 | July bug-hunt passes. |
| `code-audit-findings-2026-07-04.md` | 2026-07-04 | Code audit findings. |

## Historical (see `archive/`)

Landed or superseded one-shot reports: earlier bug-hunt passes (2026-05-22
through 2026-06-08), conversion audits, the 2026-05-26 dependency snapshot,
and the post-migration high-value reliability tracker (all items landed).
Recheck against current code before acting on anything in `archive/`.

For a fresh graded audit with addressable improvement IDs, run the
`grade-codebase` skill; its report is written to repo-local, gitignored
`.claude/grade-report.md`. Historical audit reports live under `archive/` so
they stay available without looking like current implementation guidance.
