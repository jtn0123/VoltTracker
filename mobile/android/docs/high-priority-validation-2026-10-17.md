# High-priority reliability fixes — 2026-10-17

The October 17 audit's top five items C1, A1, F1, C2 and G1 are implemented,
including prerequisite B1. Retention documentation H1 was updated alongside
these changes. The original B− audit remains historical evidence; this is a
fix/validation record, not a new whole-app grade.

Application code validated at `83a4a166f`, stacked above field-fix PR #192
(`4c2a21cba`). The new build was installed on the Android 16 `vt_test` emulator.
The physical phone was not updated and no real-car acceptance was performed.

## Completed work

| Item | Result | Regression evidence |
|---|---|---|
| C1 | Charging before SOC arrives shows a waiting placeholder; the chart recovers when SOC arrives. | Mapper-to-Compose test reproduced the empty-series exception before the fix and passes afterward. |
| A1 | Schema v18 saves authoritative compact trip/charge facts before raw expiry. Hide/restore and remapped backup merges preserve retained history and totals. | Nine retained-history database cases cover hidden siblings, cutoff-spanning sessions, old backup migration, duplicate merges, corrupt-import rollback and inferred-charge boundaries; migration coverage includes v17→v18. |
| B1 | User split/merge decisions survive retention and their embedded split/route keys are remapped on merge. | The retention regression originally deleted all three decisions; it now preserves both active and superseded decisions. Remapper and existing split tests pass. |
| C2 | After ten seconds of silent telemetry, Drive/Charge show a neutral waiting state and clear charger readings, ETA and claimed phase. Fresh classified data resumes the same charge. | Controlled-clock mapper-to-Compose regression, Drive semantics and same-charge recovery pass. Unrelated readings cannot restore a charging claim. |
| F1 | Gradle wrapper and distribution updated to publisher-verified 9.8.1. | Wrapper/JAR checksums, `--version`, dependency-lock verification and normal build checks pass. |
| G1 | Both dashboards use application-owned background maintenance with a persisted 24-hour throttle, idle-only database lease and independent short-lived store. | Native lifecycle scheduling, coalescing, backup/restore/recording exclusion, failure/retry and preference cases pass. Emulator native launch saved a completion timestamp; relaunch preserved it. |

Retained facts are independent of projection-cache versions. Existing pruned
caches are promoted when enough legacy information survives; previously lost
facts cannot be reconstructed. Raw geometry is needed to split/merge trips:
after retention, those attempts fail without appending a misleading decision.
Labels, favorites and hide/restore remain editable. Geometry edits and retention
snapshots use transactions to avoid racing that boundary.

## Validation

- `./gradlew verifyActiveApp :app:verifyReleaseDependencyLock` passed at
  `6fd04364` before the final data-only geometry guard. It covered Android,
  dashboard, browser, coverage, privacy and generated-source checks.
- After that guard, the complete Android suite, Lint, Spotless, Detekt, debug
  assembly, JaCoCo verification, privacy and source/generation guards were rerun
  successfully at `83a4a166f`.
- Android: **3,281 cases, zero failures/errors, one existing opt-in real-database
  benchmark skip**. JaCoCo: **93.1% lines, 81.0% branches**.
- Dashboard: **99 files / 931 tests passed**. Browser: **75 passed**, one existing
  opt-in screenshot-capture skip. Dashboard inputs were unchanged by the final
  native-data guard.
- `./gradlew verifyNativeVisual` passed all 21 cases against committed baselines
  without updating them. A new waiting-state capture was inspected separately
  from those baselines.
- Packaged emulator smoke: native launcher and Charge rendered; installed APK
  SHA-256 matched the built APK. Completion/throttle evidence was read from
  emulator preferences. No AndroidRuntime crash was reported in that smoke.

Local execution logs, per-class totals, coverage counters and APK receipt are
under `.Codex/high-priority-validation/2026-10-17/` in the implementation
worktree. The codebase/UI audit reports in `.Codex/` mark completed IDs and preserve
the original findings and grades.

## Still requires real-car acceptance

- Charging startup before SOC, then normal Waiting→Charging→Complete/unplug.
- Engine running in Park without a cord: no false charging/interrupted-charge
  notification, including the already installed PR #192 fix.
- A safe, stationary telemetry interruption: waiting after ten seconds, then
  recovery without restarting the charge or inventing energy during the gap.
- The field-review checks for wheel mapping, startup freshness and unconfirmed
  PID candidates remain open. Emulator, synthetic and CI evidence do not confirm
  those vehicle readings.

Other audit work remains: inferred-charge provenance (C3), the drive-energy
integration gate (C4), installer failure feedback (B2), broader native visual
coverage (D1), legacy signer preflight (E1), and native phone benchmark tooling
(I1). No merge or phone rollout is implied by these local results.
