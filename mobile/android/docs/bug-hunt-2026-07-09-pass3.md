# VoltTracker validated bug hunt — pass 3 (2026-07-09)

## Scope and baseline

This is an analysis-only follow-up to the fix bundle in commit `475f59d5`
(`fix(android): harden runtime and dashboard reliability`). It intentionally does
not mix new implementation changes into that commit.

Baseline validation was green before this pass:

- `PATH=/tmp/volttracker-node22/bin:$PATH ./gradlew verifyActiveApp`
- Android assemble, unit tests, lint, detekt, JaCoCo, privacy scan, generated-asset
  checks, dashboard lint/typecheck, 662 dashboard tests, 54 Playwright tests, and
  bundle budgets all passed.
- Android aggregate coverage was about 94.1% lines / 78.7% branches; dashboard
  coverage was 90.58% lines / 73.92% branches.
- The production tree contains no `TODO`, `FIXME`, or `HACK` markers.

The healthy baseline matters: these are not compiler errors or obvious lint
findings. I evaluated a pool of more than 30 candidates across restore/data
identity, executor shutdown, service/session lifecycle, Bluetooth IO cancellation,
WebView callback delivery, hot-path work, test gaps, and large-module structure.
Exactly 20 survived source tracing and duplicate/stale-candidate removal.

## Validated findings (exactly 20)

| # | Area | Layman explanation | Evidence | Impact | Ease |
|---:|---|---|---|---|---|---|
| 1 | Backup / vehicle identity | A backup restored after reinstalling or on another phone can make the same VIN look like a new vehicle, splitting history across duplicate vehicle rows. | `VinKeyHasher.kt:15-58` creates a random per-install HMAC secret in SharedPreferences; `AndroidManifest.xml:45` disables Android backup; `DataBackup.kt:191-224,230-275` exports only the SQLite file; `ObdStoreVehicles.kt:15-47` can match only the current HMAC or the old unsalted hash; `DatabaseMerger.kt:212-235` merges vehicles strictly by `vehicle_key`. | High — restored data can silently fork one car into two identities. | Hard — the portable identity format needs a privacy-conscious migration design. |
| 2 | Live map recovery | A mid-drive WebView recreation can permanently show a blank live route if the first asynchronous route read is delayed, empty, invalid, or its callback is lost. | `telemetry.ts:283-345` sets `liveRouteHydrated = true` when the request is merely accepted and again before confirming usable points; later status pushes then refuse to retry. | Medium — an active drive loses its recovered route until the page/session resets. | Easy/Medium — track pending/success separately and retry with a bounded timeout/backoff. |
| 3 | Dashboard async reads | Trips, Insights, storage details, and battery-health history can become permanently “in flight” after a dropped native callback. | `insights-panel.ts:31-155` and `storage-status.ts:24-186,867-1018` clear their booleans only in callbacks or synchronous catches. `VoltBridgeStorage.kt:56-82` catches publish failures after the JavaScript call has already been accepted. There is no watchdog or request token. | Medium — affected panels stop refreshing for the lifetime of that WebView. | Medium — introduce one timeout-aware request helper and ignore stale callback tokens. |
| 4 | Service shutdown / SQLite | Service teardown can close SQLite while persistence tasks are still alive. | `ObdPersistenceWorker.kt:162-176` waits at most 30 seconds per executor but does not `shutdownNow()` or report a timeout; `ObdService.kt:302-310` immediately closes `localStore` after `recorder.shutdown()`. A blocked task can resume against the closed store. | High — late writes can fail and the final session row may not be committed cleanly. | Medium — return a drain result, force-stop timed-out workers, and close the store only after confirmed termination. |
| 5 | Restore cancellation / Activity teardown | Destroying the Activity during a backup Replace/Merge does not reliably stop the worker that is moving database files. | `MainActivity.kt:476-481` calls `shutdownNow()` and immediately closes the store; `BackupController.kt:690-708,810-884` has no disposed/cancellation token; `DataBackup.kt:665-679,694-718` copy loops never check interruption. The worker retains the old Activity and can continue renaming/reopening the DB. | High — a teardown at the wrong moment risks restore failure, stale UI callbacks, or a database race. | Hard — make restore a cancellable application-scoped operation with atomic ownership of the store swap. |
| 6 | Bluetooth IO cancellation | Interrupting an ELM wait can turn its sleep loop into a tight CPU spin until the timeout expires. | `ElmConnection.kt:182-193,232-263,333-338` catches `InterruptedException`, re-sets the interrupt flag, and returns `Unit`; the enclosing loops do not exit. Every later `Thread.sleep` immediately throws again while the deadline loop continues. | Medium — disconnect/teardown can burn CPU and delay cancellation during a wedged adapter read. | Easy — make sleep return success/failure or throw, and break on interruption. |
| 7 | Session crash recovery | A hard process death leaves the database session marked `active` forever. | `ObdStoreWriter.kt:49-58` inserts `STATUS_ACTIVE`; the only production status transition is `finishSession` (`ObdStoreWriter.kt:61-86`). No startup reconciliation exists, while `ObdLocalStore.kt:396-425` explicitly queries the newest active row. | Medium — stale sessions can pollute route/trip projections and make old work look in progress. | Medium — on store startup, finalize implausibly old active rows with a distinct recovered/crashed status. |
| 8 | Telemetry disk hot path | The per-session JSONL logger flushes the file on every telemetry line, on the polling path. | `ObdService.kt:612-621` logs JSON before broadcasting every sample; `ObdSessionLog.kt:60-110` writes under a synchronized method and calls `BufferedWriter.flush()` for every non-durable entry. Driving polls run about every 850 ms (`ObdPollingEngine.kt:874-906`). | Medium — avoidable syscalls and lock hold time can add sampling jitter and battery/storage overhead. | Easy/Medium — batch ordinary telemetry flushes and reserve flush+fsync for lifecycle/durable events. |
| 9 | Telemetry UI hot path | Every driving sample is delivered twice to JavaScript: once as telemetry and again inside a full app-state snapshot. | `ObdService.kt:612-725` sends a package broadcast per sample; `DashboardBroadcastCoordinator.kt:115-120` calls both `updateTelemetry` and `publishAppState`; `AppStatePayload.kt:23-40` embeds the same telemetry as `latestTelemetry`; `DashboardPublisher.kt:57-67` creates a separate `evaluateJavascript` call for each. | Medium — duplicate JSON serialization and main-thread WebView work at roughly 1.2 Hz. | Medium — keep the hot update small and publish app-state only when non-telemetry state changes or on a slower cadence. |
| 10 | Source/tooling integrity | `EventNotifier.kt` contains a literal NUL byte, so normal text tools treat the Kotlin source as binary. | `file EventNotifier.kt` reports `data`; `rg` reports a binary match around offset 12564; `xxd` shows byte `00` inside the `entryId`/`serviceType` delimiter. | Low — searches and source scanners can silently skip this file, hiding future defects. | Easy — replace the raw byte with the escaped source form `\u0000` and add a no-NUL source guard. |
| 11 | Broadcast coordinator tests | The class routing every service telemetry/status broadcast has no direct unit test and the lowest meaningful branch coverage in the app. | No test references `DashboardBroadcastCoordinator`; JaCoCo reports 81.2% lines but only 34.6% branches (9/26) for `DashboardBroadcastCoordinator.kt`. Its untested branches include ready/not-ready and idle/throttled storage publication (`:115-149`). | Medium — regressions can drop dashboard updates despite aggregate coverage staying green. | Easy — test the coordinator against a fake `Seam` and explicit broadcast intents. |
| 12 | ELM cancellation tests | The IO suite never exercises interruption while `wakeNudge`/`transact` is waiting. | `ElmConnectionTest.kt` and `ElmConnectionExtraTest.kt` contain 24 tests, but the only cancellation assertion is `keepWaiting == false`; no test interrupts the waiting thread. | Medium — the busy-spin bug in finding 6 passed the full green gate. | Easy — use a latch/fake clock and assert prompt cancellation plus preserved interrupt status. |
| 13 | Persistence timeout tests | Persistence tests cover normal drain, overflow, failure, and post-shutdown submission, but never the shutdown-timeout branch. | `ObdPersistenceWorkerTest.kt` has 8 deterministic tests; none blocks a worker beyond the shutdown deadline or asserts that store close waits for confirmed executor termination. | High — the data-loss race in finding 4 has no regression alarm. | Medium — inject the drain timeout/executors so the timeout path is fast and deterministic. |
| 14 | Merge failure/atomicity tests | Merge tests are broad on happy-path tables but do not inject a mid-merge failure, malformed donor constraint, or cancellation. | `DatabaseMergerTest.kt` has 24 tests for dedupe, remapping, schema lists, and idempotency, but no rollback/fault-injection case; `DatabaseMerger.kt:49-91` relies on one transaction to protect a large multi-table operation. | Medium — a future table-copy change could partially mutate live data if transaction assumptions regress. | Medium — add a fault seam and assert byte/row equivalence before and after rollback. |
| 15 | Database merge structure | One 1,119-line object manually owns every table’s copy/dedupe/remap rules and column lists. | `DatabaseMerger.kt` has 36 functions, the largest Kotlin source file, plus table-specific branches and column arrays through `:840-1119`. | Medium — schema changes require editing a high-blast-radius file and make restore bugs harder to isolate. | Medium — split table mergers behind a shared merge context while retaining the outer transaction. |
| 16 | Dashboard actions structure | `actions.ts` still combines command dispatch, device actions, trip actions, map bindings, demo controls, modal flows, and listener lifecycle. | `actions.ts` is 1,766 lines with about 81 functions and 10 direct listeners; its branch coverage is 62.85%, second-lowest among real behavior modules. | Medium — unrelated UI changes collide and untested branches accumulate. | Medium — continue the existing extraction pattern (`actions-storage`, `actions-demo`, `actions-signals`) by domain. |
| 17 | Map structure | `map.ts` owns too many independent responsibilities despite being lazy-loaded. | `map.ts` is 2,642 lines with about 72 functions covering Leaflet setup, routes, sessions, markers, overlays, fullscreen, and selection; branch coverage is 67.16%. | Medium — map fixes require navigating a very large shared state surface. | Medium/Hard — split controller, route rendering, overlay/layer, and session-selection modules behind a small map facade. |
| 18 | Storage/Insights structure | `storage-status.ts` is a second dashboard god module and also owns the async states implicated in finding 3. | `storage-status.ts` is 2,327 lines with about 80 functions spanning DB status, storage rollups, SOH charts, charge history, DTC UI, and scan progress. | Medium — callback and rendering state from unrelated panels can interfere and is costly to reason about. | Medium — extract storage fetch state, SOH/charge views, and DTC progress/reporting separately. |
| 19 | Mode 22 decoding structure | A roughly 380-line `when` dispatch hard-codes command-to-parser behavior in one function. | `ObdVoltMode22Decoder.kt:24-403` is a single dispatch block in a 569-line file; changes are repetitive and easy to mis-map even though current coverage is strong. | Low/Medium — future PID additions carry avoidable copy/paste risk. | Medium — represent compatible decoders as typed specifications/tables, leaving special cases explicit. |
| 20 | Package architecture | Most production Kotlin still sits in one root package, weakening module ownership and architecture enforcement. | 101 `.kt` files live directly under `com.volttracker.obdpoc`; only `data` (34), `materialize` (11), `widget` (5), `classify` (5), and `location` (4) are grouped below it. | Medium — dependency direction and domain boundaries remain hard to see, and large classes keep accreting peers. | Hard — migrate one vertical slice at a time and ratchet package dependency tests/detekt rules. |

## Recommended execution order

1. **Protect data first:** #1, #4, #5, #7.
2. **Fix visible stuck/cancellation behavior:** #2, #3, #6.
3. **Trim the live-session hot path:** #8 and #9; take #10 as a quick adjacent win.
4. **Install focused regression alarms:** #11–#14 alongside the corresponding fixes.
5. **Simplify incrementally:** #15–#20 only in bounded, behavior-preserving slices; do not combine all package/module moves into one rewrite.

## Candidates rejected or merged during validation

These did not make the 20 because they were already fixed, intentional, too weak,
or duplicates of a stronger finding:

- **Auto-DTC runner lacks direct tests:** stale — `AutoDtcScanRunnerTest.kt` now exists.
- **No database migration-chain coverage:** stale — focused version-step tests and
  `BackupMigratorTest` now cover the current chain.
- **`actions-page-scroll` is untested:** stale — current report shows 91.42%
  statements / 80.76% branches.
- **npm audit is not gated:** stale — `dependency-snapshot.yml` runs audit for both
  dashboard dependency sets.
- **Plaintext backup is presented as equally safe:** stale — encrypted export is
  visually preferred and plaintext requires a specific disclosure/confirmation.
- **WebView file access is permissive:** stale — `WebViewBootstrap` explicitly
  disables file/content/universal access and blocks off-origin navigation.
- **Mode-22 pack voltage/current math is wrong:** fixed in the preceding bundle.
- **Response buffering is unbounded:** fixed by `MAX_RESPONSE_CHARS`.
- **Live reconnect permanently exhausts across a long drive:** rejected — the live
  loop deliberately resets its retry budget after each successful connection.
- **Widget double `AtomicReference.get()` is an immediate NPE:** rejected — the
  scheduled 500 ms consumer means the observed race is not presently reproducible;
  using one local value is still cleaner but not a top-20 defect.
- **Always-on stale heartbeat is a major background drain:** downgraded — the
  Activity pauses the WebView; visibility-aware scheduling is polish, not a current
  high-confidence defect.
- **`dtc-lookup.ts` / `dtc-causes.ts` are god modules:** rejected — most of their
  line count is generated/reference data, not intertwined behavior.
- **`EngineHost` exposes concrete collaborators/raw locking:** real debt but merged
  below the stronger package/large-module simplification priorities for this pass.
- **All CI jobs lack timeouts:** valid DevEx hardening, but outside the app/runtime
  focus and lower priority than the retained findings.

## Deep fuzz/fault-injection implementation pass

The follow-up implementation pass exercised the high-risk seams instead of starting another
quota-driven list. It added fixed-seed fuzzers and deterministic fault injection, then fixed each
reproduced failure before continuing:

- **Protocol parsing:** 10,000 arbitrary adapter-response cases plus 10,000 valid-marker Mode 22
  payloads. All parsers stayed bounded, shape-safe, and finite after the existing response caps.
- **WebView callback ABI:** 2,000 randomized JSON roots/field shapes plus 1,000 truncated or invalid
  JSON strings across all 12 native callbacks. This reproduced unsafe null/row handling in device
  history, status/telemetry, current-route, battery-SOH, and restore-progress callbacks; the
  callbacks now validate/filter at their boundary and fail soft.
- **Database merge:** randomized duplicate sessions/telemetry were merged twice to prove
  idempotence, and a listener-thrown mid-merge failure proved every touched table rolls back.
- **Cancellation/lifecycle:** a real interrupted ELM wait reproduced the busy spin; an
  interruption-ignoring persistence task exercised the shutdown timeout; backup copies now stop at
  the next chunk and merge progress checks cancellation so SQLite can roll back.
- **Source integrity:** three literal NUL bytes were replaced with escaped source literals and a
  `verifyNoNulSourceBytes` build gate now covers Kotlin, Java, TypeScript, JavaScript, HTML, CSS,
  and XML app/test sources.

### Recommendation status after implementation

- Fixed: #2, #3, #4, #6, #7, #8, #9, #10, #11, #12, #13, and #14.
- Materially hardened: #5 now signals disposal before executor interruption, suppresses late UI
  callbacks, interrupts stream copies, and cancels/rolls back merge work. Moving the operation to a
  fully application-scoped owner remains a larger architectural follow-up.
- Still open by design: #1 needs a privacy-reviewed portable vehicle-identity format; it should not
  be improvised inside a reliability bundle.
- Incremental simplification started for #15-#20: the repeated async-read booleans were replaced by
  one timeout-aware request gate, malformed device/history handling shares one predicate, and crash
  recovery was extracted into a single-purpose database component. The large-module/package moves
  remain bounded refactors rather than a risky all-at-once rewrite.

The implementation pass also removed the duplicate full app-state WebView publish on every
telemetry sample, batches ordinary JSONL telemetry flushes (lifecycle/error records still flush
immediately), finalizes process-death sessions at their last persisted timestamp, and refuses to
close SQLite underneath a persistence executor that survived force-stop.

### Final verification

- `PATH=/tmp/volttracker-node22/bin:$PATH ./gradlew --no-daemon --configuration-cache verifyFast`
  passed after the implementation and extraction work.
- `PATH=/tmp/volttracker-node22/bin:$PATH ./gradlew --no-daemon --configuration-cache verifyActiveApp`
  passed in 3m 8s: debug assemble, 1,636 Android JVM/Robolectric tests, lint, detekt, JaCoCo,
  privacy/source/generated-asset checks, 677 dashboard tests, and 54 Chromium E2E tests.
- Dashboard coverage finished at 90.84% lines, 74.63% branches, 89.0% functions, and 87.56%
  statements.

## Bottom line

Another pass was worthwhile, but finding 20 defensible items is now materially
harder. The easy correctness/lint layer is largely exhausted; the remaining value is
concentrated in lifecycle races, asynchronous callback failure, restore identity,
hot-path cost, and a few large ownership boundaries. After this set, the next useful
pass should be narrower and evidence-heavy (device fault injection, forced process
death, restore interruption, and adapter-response fuzzing), not another broad request
for an arbitrary count.
