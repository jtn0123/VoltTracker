# Release Bug Hunt - 2026-06-08

Scope: detached release checkout `b3032cc` (`origin/release`, `release`), commit
`fix(android): make notification pending intents explicit`.

This started as an audit artifact only. Follow-up work in this release branch
has since fixed a small subset of findings and added adjacent import,
auto-connect, and Settings button-flow improvements. Each finding below was
validated against source, release evidence, or local commands. Hardware
validation was not possible in this checkout because no emulator, phone,
adapter, or vehicle was attached.

## Original Validation Notes

- `cd mobile/android && ./gradlew verifyActiveApp`
  - First run: transient `dashboard-tests/startup-budget.test.js` timeout.
  - Follow-up `dashboardTest`: passed.
  - Final rerun: passed.
- `cd mobile/android && ./gradlew :app:testDebugUnitTest`: passed.
- `./scripts/check-release-candidate-evidence.sh`: exited 0 against the current
  evidence file even though it says `Ready to tag: no`.
- `RELEASE_CANDIDATE_EVIDENCE=mobile/android/docs/release-candidates/release-hardening-2026-06-08.md REQUIRE_RELEASE_CANDIDATE_EVIDENCE=1 ./scripts/check-release-candidate-evidence.sh`:
  also exited 0 with `Ready to tag: no`.
- `mobile/android/docs/release-candidates/release-hardening-2026-06-08.md`
  explicitly says emulator, phone, real adapter, and real car proof are pending.

## Current Status After Follow-up Work

Resolved in this branch:

- R01: strict release-candidate evidence now requires `Ready to tag: yes` or
  `Ready to tag: true` for the release preflight/publish path; PR dry runs
  still require the candidate evidence file and required fields while allowing
  honest pending hardware proof.
- R02: the main release workflow now runs `scripts/release-preflight.sh` before
  semantic-release publishes.
- R03: the tagged APK build job now explicitly sets up Node from `.nvmrc`
  before Gradle bundles dashboard JS.
- R04: the rolling `latest-debug` release now waits on `ci-success`, not just
  `unit-tests`.
- R05: `mobile/android/scripts/doctor.sh` now fails when the installed Node
  major does not match `.nvmrc`.
- A01: `MainActivity.startObdService` now catches runtime service-start
  failures, clears pending probe stop state, and publishes a blocked status
  instead of crashing.
- A03: `BackupController.runBackground` now catches rejected/runtime executor
  failures and publishes the unavailable restore/backup status.
- Adjacent import feedback: restore file selection now reports choose/read,
  decrypt/read, validation failure, verified, and cancel states.
- Adjacent connection UX: the Settings primary connection button now transitions
  through Connect/Resume -> Connecting/Scanning -> Disconnect, hides
  Reconnect last while active, and feeds the same status model used elsewhere.
- Adjacent auto-connect: the dashboard and bridge expose a battery-safe
  remembered-adapter auto-connect toggle; the native side reacts to resume,
  dashboard-ready, Bluetooth-on, and matching ACL-connected events without
  continuous discovery.

Still open from this bug report:

- Runtime/service edge cases: A02, A04, A05, A06, A07.
- Restore/data correctness: D01, D02, D03, D04, D05, D06, D07, D08, D09, D10.
- Parser and stale-value accuracy: P01, P02, P03.
- UX follow-ups: U01, U02, U03.

Follow-up validation:

- `bash -n scripts/check-release-candidate-evidence.sh scripts/release-preflight.sh mobile/android/scripts/doctor.sh`: passed.
- `REQUIRE_RELEASE_CANDIDATE_EVIDENCE=1 ./scripts/check-release-candidate-evidence.sh`: failed as expected against the current `Ready to tag: no` evidence file.
- `REQUIRE_RELEASE_CANDIDATE_EVIDENCE=1 REQUIRE_READY_TO_TAG=0 ./scripts/check-release-candidate-evidence.sh`: passed while warning that the candidate is not ready to tag.
- Strict evidence check with a temporary `Ready to tag: yes` fixture: passed.
- `./scripts/release-preflight.sh`: failed as expected at the release-candidate evidence gate before dashboard audits or Gradle release verification.
- `actionlint .github/workflows/release.yml .github/workflows/android.yml .github/workflows/release-dry-run.yml`: passed.
- `cd mobile/android && ./gradlew --no-daemon verifyActiveApp`: passed.
- `git diff --check`: passed.

## Findings

### R01 - Release evidence checker accepts "Ready to tag: no"

Severity: High

Evidence:
- `scripts/check-release-candidate-evidence.sh:30-47` requires a non-empty
  `Ready to tag` field, but does not require an affirmative value.
- `mobile/android/docs/release-candidates/release-hardening-2026-06-08.md:39-43`
  says `Ready to tag: no`.
- The checker exits 0 even in `REQUIRE_RELEASE_CANDIDATE_EVIDENCE=1` mode.

Layman impact: A release note can literally say "do not tag this yet" and the
release gate still treats it as acceptable.

Diff to fix:

```diff
diff --git a/scripts/check-release-candidate-evidence.sh b/scripts/check-release-candidate-evidence.sh
@@
 if grep -Eq '^- (Branch|Commit|Version / expected tag|APK under test|Highest validation level reached|Ready to tag):[[:space:]]*$' "${EVIDENCE_FILE}"; then
   missing "Release-candidate evidence has blank required fields: ${EVIDENCE_FILE}."
 fi
+
+if ! grep -Eiq '^- Ready to tag:[[:space:]]*(yes|true)$' "${EVIDENCE_FILE}"; then
+  missing "Release-candidate evidence must say Ready to tag: yes before tagging: ${EVIDENCE_FILE}."
+fi
```

### R02 - Tagging workflow does not run release preflight before publishing

Severity: High

Evidence:
- `.github/workflows/release.yml:49-60` validates release config, installs
  semantic-release, then versions and publishes.
- `scripts/release-preflight.sh:6-31` is the local gate that checks release
  config, semantic-release dry run, evidence, dependency audit, and
  `verifyActiveApp`, but it is not called by the release workflow before tagging.

Layman impact: GitHub can create a real release tag before the same checks that
the repo calls "release preflight" have run.

Diff to fix:

```diff
diff --git a/.github/workflows/release.yml b/.github/workflows/release.yml
@@
       - name: Validate release config
         run: python .github/scripts/check_release_config.py
+
+      - name: Release preflight
+        env:
+          REQUIRE_RELEASE_CANDIDATE_EVIDENCE: "1"
+        run: ./scripts/release-preflight.sh

       - name: Install python-semantic-release
         run: pip install python-semantic-release==9.21.1
```

### R03 - Tagged APK build job lacks Node setup even though APK build bundles dashboard JS

Severity: High

Evidence:
- `.github/workflows/release.yml:151-208` sets up Java and runs
  `./gradlew --no-daemon :app:assembleRelease :app:assembleDebug`, but does not
  set up Node.
- `mobile/android/app/build.gradle:208-212` wires `preBuild` to
  `buildDashboardJs`.
- `mobile/android/build.gradle:39-57` runs `npm ci` and `npm run build` for that
  bundle.
- `.nvmrc:1` and both dashboard package files require Node 20.

Layman impact: The signed APK job depends on whatever Node version happens to be
on the runner. A runner image change can break releases or build with an
unsupported dashboard toolchain.

Diff to fix:

```diff
diff --git a/.github/workflows/release.yml b/.github/workflows/release.yml
@@
       - uses: actions/setup-java@be666c2fcd27ec809703dec50e508c2fdc7f6654
         with:
           distribution: temurin
           java-version: '21'
+
+      - uses: actions/setup-node@49933ea5288caeca8642d1e84afbd3f7d6820020
+        with:
+          node-version-file: .nvmrc
+          cache: npm
+          cache-dependency-path: |
+            mobile/android/dashboard-tests/package-lock.json
+            mobile/android/dashboard-e2e/package-lock.json
```

### R04 - Rolling latest-debug release waits only for unit tests

Severity: High

Evidence:
- `.github/workflows/android.yml:355-383` defines `ci-success` as the aggregate
  gate for unit tests, dashboard tests, e2e, visual, dependency audit, and
  emulator smoke.
- `.github/workflows/android.yml:385-435` publishes the `latest-debug` release
  with `needs: unit-tests` only.

Layman impact: The public "latest debug" APK can be uploaded even if dashboard
tests, visual tests, dependency audit, or emulator smoke fail.

Diff to fix:

```diff
diff --git a/.github/workflows/android.yml b/.github/workflows/android.yml
@@
   publish-debug-release:
@@
-    needs: unit-tests
+    needs: [unit-tests, ci-success]
```

### R05 - Local doctor reports Node versions but does not enforce the supported version

Severity: Medium

Evidence:
- `mobile/android/scripts/doctor.sh:88-94` prints `node --version` and
  `npm --version`.
- `.nvmrc:1`, `mobile/android/dashboard-tests/package.json:5-7`, and
  `mobile/android/dashboard-e2e/package.json:6-8` require Node 20.
- During this audit the local doctor path accepted a non-20 Node runtime instead
  of warning or failing.

Layman impact: A developer can validate or package a release with an unsupported
Node version and not know that their dashboard build environment differs from
the declared one.

Diff to fix:

```diff
diff --git a/mobile/android/scripts/doctor.sh b/mobile/android/scripts/doctor.sh
@@
 if need_cmd node; then
   print_cmd_version node --version
+  node_major="$(node --version | sed -E 's/^v([0-9]+).*/\1/')"
+  if [ "${node_major}" != "20" ]; then
+    missing "Node 20.x is required by .nvmrc and dashboard package engines"
+  fi
 fi
```

### A01 - Activity can crash when Android rejects service start

Severity: High

Evidence:
- `mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/MainActivity.kt:357-413`
  calls `startForegroundService` or `startService` with no runtime exception
  handling after the Bluetooth checks pass.

Layman impact: If Android blocks the foreground service start, the app can crash
instead of showing a useful "blocked" status.

Diff to fix:

```diff
diff --git a/mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/MainActivity.kt b/mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/MainActivity.kt
@@
-        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
-            startForegroundService(service)
-        } else {
-            startService(service)
-        }
+        try {
+            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
+                startForegroundService(service)
+            } else {
+                startService(service)
+            }
+        } catch (ex: RuntimeException) {
+            publishStatus("blocked", "Android blocked OBD logging startup. Check app permissions and try again.", true)
+            troubleshooter?.clearPendingTestConnectionStop()
+        }
```

### A02 - Foreground promotion catches only SecurityException

Severity: High

Evidence:
- `mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/ObdService.kt:501-520`
  catches only `SecurityException` around `startForeground`.

Layman impact: Newer Android foreground-service refusals can still kill the
service if they are thrown as runtime exceptions outside `SecurityException`.

Diff to fix:

```diff
diff --git a/mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/ObdService.kt b/mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/ObdService.kt
@@
-        } catch (ex: SecurityException) {
+        } catch (ex: SecurityException) {
             Log.w(MainActivity.TAG, "startForegroundSession refused", ex)
             foregroundServiceActive = false
             activeForegroundServiceType = 0
             false
+        } catch (ex: RuntimeException) {
+            Log.w(MainActivity.TAG, "startForegroundSession blocked", ex)
+            foregroundServiceActive = false
+            activeForegroundServiceType = 0
+            false
         }
```

### A03 - Backup background executor rejection can crash backup/restore flows

Severity: Medium

Evidence:
- `mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/BackupController.kt:267-277`
  checks only for a null executor before calling `worker.execute(task)`.

Layman impact: If the backup worker is shutting down or saturated, tapping a
backup/restore action can throw instead of showing a "try again" message.

Diff to fix:

```diff
diff --git a/mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/BackupController.kt b/mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/BackupController.kt
@@
-        worker.execute(task)
+        try {
+            worker.execute(task)
+        } catch (ex: RuntimeException) {
+            activity.publishStatus("blocked", unavailableMessage, true)
+        }
```

### A04 - Test connection trusts a non-empty remembered adapter address

Severity: Medium

Evidence:
- `mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/TroubleshooterBridge.kt:205-219`
  only checks `address.isEmpty()` before calling `rememberDevice` and
  `startObdService`.
- Other bridge paths use `VoltBridge.validBluetoothAddress`.

Layman impact: A corrupted or old saved adapter value can kick off a connection
attempt that was never a valid Bluetooth MAC address.

Diff to fix:

```diff
diff --git a/mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/TroubleshooterBridge.kt b/mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/TroubleshooterBridge.kt
@@
-        if (address.isEmpty()) {
+        if (!VoltBridge.validBluetoothAddress(address)) {
             activity.publishStatus(
                 "blocked",
                 "No remembered adapter yet - pick one and Connect once first.",
```

### A05 - Scan-last bypasses Bluetooth address validation

Severity: Medium

Evidence:
- `mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/VoltBridgeConnections.kt:57-62`
  passes `requireValidAddress = false` for `scanLast`.
- `mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/VoltBridgeConnections.kt:135-145`
  treats any non-empty address as valid when that flag is false.

Layman impact: The scan button can try to use a bad remembered adapter address
that the normal Connect path would reject.

Diff to fix:

```diff
diff --git a/mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/VoltBridgeConnections.kt b/mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/VoltBridgeConnections.kt
@@
         startLastDeviceAction(
             ObdService.ACTION_SCAN,
             "No remembered adapter yet. Connect once to save it.",
-            requireValidAddress = false,
+            requireValidAddress = true,
         )
```

### A06 - Try-reconnect trusts invalid remembered addresses

Severity: Medium

Evidence:
- `mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/VoltBridgeConnections.kt:94-105`
  blocks only an empty address before reconnecting.

Layman impact: The "try reconnect now" recovery button can keep retrying an
invalid saved adapter instead of telling the user to pick the adapter again.

Diff to fix:

```diff
diff --git a/mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/VoltBridgeConnections.kt b/mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/VoltBridgeConnections.kt
@@
-            if (address.isEmpty()) {
+            if (!VoltBridge.validBluetoothAddress(address)) {
                 activity.publishStatus("blocked", "No remembered adapter yet. Pick one and try Connect.", true)
                 return@runOnUiThread
             }
```

### A07 - Canceling "notify when ready" does not stop an already running probe

Severity: Medium

Evidence:
- `mobile/android/app/src/main/dashboard-src/js/connection-tools.ts:78-80` says
  disabling the toggle cancels probes immediately.
- `mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/TroubleshooterBridge.kt:249-258`
  clears schedule state, but does not stop a probe already launched by
  `startTestConnection`.

Layman impact: A user can turn off the feature and still have the app continue
probing the adapter for the current attempt.

Diff to fix:

```diff
diff --git a/mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/TroubleshooterBridge.kt b/mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/TroubleshooterBridge.kt
@@
     fun cancelAdapterReadyNotify() {
+        if (probeInFlight) {
+            activity.stopObdService()
+        }
         stopAdapterReadySchedule()
         cancelAdapterReadyNotification()
     }
```

### D01 - Re-importing the same backup duplicates field capability rows

Severity: High

Evidence:
- `mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/data/DatabaseMerger.kt:84-93`
  copies `field_capabilities` through the generic child copier.
- `DatabaseMerger.kt:236-251` only skips rows with unmapped `session_id`; this
  table has no `session_id`, so duplicate imports keep inserting rows.
- `mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/data/VoltTrackerSchema.kt:282-298`
  and `:424-428` define only non-unique field-capability lookup indexes.
- `mobile/android/app/src/test/java/com/volttracker/obdpoc/data/DatabaseMergerTest.kt:161-176`
  proves idempotency only for sessions and telemetry.

Layman impact: Restoring the same backup twice can make "learned PIDs" grow
with duplicate rows, making diagnostics counts and storage views misleading.

Diff to fix:

```diff
diff --git a/mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/data/VoltTrackerSchema.kt b/mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/data/VoltTrackerSchema.kt
@@
             "CREATE INDEX IF NOT EXISTS idx_field_capabilities_lookup ON ${VoltTrackerDb.TABLE_FIELD_CAPABILITIES}(adapter_key, protocol, header, command, pid)",
         )
+        db.execSQL(
+            "CREATE UNIQUE INDEX IF NOT EXISTS idx_field_capabilities_unique_lookup ON ${VoltTrackerDb.TABLE_FIELD_CAPABILITIES}(adapter_key, protocol, header, command, pid)",
+        )
```

Also change the merge path to upsert on that key instead of blindly calling
`insertOrThrow`, and add an idempotency assertion for `field_capabilities`.

### D02 - Re-importing the same backup inflates adapter-history counters

Severity: High

Evidence:
- `mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/data/DatabaseMerger.kt:283-324`
  merges adapter history.
- Lines `299-303` sum `connect_count`, `scan_count`, `demo_count`, and
  `sample_count` every time.
- The session idempotency test does not assert adapter-history counters.

Layman impact: Importing the same backup twice can make the app claim the
adapter was connected, scanned, or sampled more times than it really was.

Diff to fix:

```diff
diff --git a/mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/data/DatabaseMerger.kt b/mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/data/DatabaseMerger.kt
@@
                 val cv = readRow(c)
                 val key = cv.getAsString("adapter_key") ?: continue
+                if (cv.getAsLong("last_session_id") != null && !canCopyMappedReference(cv, "last_session_id", sessionMap)) {
+                    continue
+                }
                 val canCopyLastSession = canCopyMappedReference(cv, "last_session_id", sessionMap)
```

A more durable fix is to track imported backup identity per aggregate source and
only add deltas once.

### D03 - Re-importing the same backup inflates diagnostic-code seen counts

Severity: High

Evidence:
- `mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/data/DatabaseMerger.kt:327-372`
  merges diagnostic codes by `(module_key, dtc, status)`.
- Line `352` sums `seen_count` every import.

Layman impact: The same historical trouble code can look like it happened again
and again just because the same backup was merged repeatedly.

Diff to fix:

```diff
diff --git a/mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/data/DatabaseMerger.kt b/mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/data/DatabaseMerger.kt
@@
-                    sumLong(merged, "seen_count", existing, cv)
+                    merged.put("seen_count", maxLong(existing.getAsLong("seen_count"), cv.getAsLong("seen_count")))
```

If the intended behavior is additive across different backups, add source
tracking so the same backup can be imported only once per aggregate row.

### D04 - Session materialization is not idempotent

Severity: High

Evidence:
- `mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/data/ObdLocalStore.kt:297-305`
  materializes trips and charge sessions for a session.
- `mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/data/ObdStoreMaterialize.kt:117-147`
  inserts trip rows without clearing existing rows.
- `ObdStoreMaterialize.kt:149-197` inserts charge rows without clearing existing
  rows.
- `mobile/android/app/src/test/java/com/volttracker/obdpoc/MaterializerIntegrationDbTest.kt:88-104`
  tests a single materialization pass only.

Layman impact: If closing a drive is retried, Trips and Charge history can show
duplicate rows for the same session.

Diff to fix:

```diff
diff --git a/mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/data/ObdStoreMaterialize.kt b/mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/data/ObdStoreMaterialize.kt
@@
         val db = helper.writableDatabase
         db.transaction {
+            db.delete(VoltTrackerDb.TABLE_TRIP_SEGMENTS, "session_id = ?", arrayOf(sessionId.toString()))
             for (trip in trips) {
@@
         val db = helper.writableDatabase
         db.transaction {
+            db.delete(VoltTrackerDb.TABLE_CHARGE_SESSIONS, "session_id = ?", arrayOf(sessionId.toString()))
             for (session in sessions) {
```

### D05 - Scan telemetry can replace the latest drive telemetry on dashboard summaries

Severity: High

Evidence:
- `mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/DiagnosticScanRunner.kt:119-130`
  broadcasts scan telemetry with `source=scan`, `updatedAt`, and location.
- `mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/SessionRecorder.kt:321-331`
  persists all non-demo telemetry.
- `mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/data/ObdStoreSupport.kt:15-30`
  treats latitude or longitude as useful telemetry.
- `mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/data/ObdStoreReports.kt:700-742`
  selects latest useful telemetry from all sessions without filtering to
  drive/OBD sessions.

Layman impact: Running a diagnostic scan can make the Battery or Overview cards
show scan-only GPS/raw data as the latest vehicle reading, hiding the last real
drive battery/SOC reading.

Diff to fix:

```diff
diff --git a/mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/data/ObdStoreReports.kt b/mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/data/ObdStoreReports.kt
@@
-                .query(
-                    VoltTrackerDb.TABLE_TELEMETRY,
+                .rawQuery(
+                    "SELECT t.captured_at_ms, t.vehicle_state, t.speed_kph, t.rpm, t.voltage, t.soc, t.battery_temp, t.power_kw, t.pack_voltage, t.pack_current_a, t.json " +
+                        "FROM ${VoltTrackerDb.TABLE_TELEMETRY} t JOIN ${VoltTrackerDb.TABLE_SESSIONS} s ON s._id = t.session_id " +
+                        "WHERE s.mode = ? AND ${ObdStoreSupport.USEFUL_TELEMETRY_WHERE.replace("latitude", "t.latitude").replace("longitude", "t.longitude")} " +
+                        "ORDER BY t.captured_at_ms DESC LIMIT 1",
+                    arrayOf(ObdLocalStore.MODE_OBD),
```

Implement this as a helper rather than inline string replacement; the sketch
shows the missing join and mode filter.

### D06 - Overview metrics mix scans, detail probes, and drives

Severity: Medium

Evidence:
- `mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/data/ObdStoreReports.kt:456-475`
  computes `maxSpeedKph`, `avgSampleIntervalMs`, `drivingSamples`,
  `chargingHints`, and `latestTelemetry` across the whole telemetry table.

Layman impact: The Drive overview can count maintenance or scan samples as if
they were driving history.

Diff to fix:

```diff
diff --git a/mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/data/ObdStoreReports.kt b/mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/data/ObdStoreReports.kt
@@
-        payload.put("maxSpeedKph", ObdStoreSupport.maxInt(db, VoltTrackerDb.TABLE_TELEMETRY, "speed_kph"))
+        payload.put("maxSpeedKph", maxDriveTelemetryInt(db, "speed_kph"))
@@
-        payload.put("latestTelemetry", latestTelemetryJson(db))
+        payload.put("latestTelemetry", latestDriveTelemetryJson(db))
```

Add helpers that join telemetry to sessions and filter `s.mode =
ObdLocalStore.MODE_OBD`.

### D07 - Insights counters mix scan/maintenance rows with drive rows

Severity: Medium

Evidence:
- `mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/data/ObdStoreTrips.kt:111-158`
  builds trip rollups, but then uses all-session `sessionCount`, all-useful
  `sampleCount`, and all `locationSampleCount`.

Layman impact: The Insights page can say the user has more sessions, samples,
or GPS points than their actual driving history contains.

Diff to fix:

```diff
diff --git a/mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/data/ObdStoreTrips.kt b/mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/data/ObdStoreTrips.kt
@@
-            payload.put("sessionCount", ObdStoreSupport.countRows(db, VoltTrackerDb.TABLE_SESSIONS))
+            payload.put("sessionCount", countSessionsByMode(db, ObdLocalStore.MODE_OBD))
@@
-                ObdStoreSupport.countRowsWhere(db, VoltTrackerDb.TABLE_TELEMETRY, ObdStoreSupport.USEFUL_TELEMETRY_WHERE, null),
+                countUsefulTelemetryByMode(db, ObdLocalStore.MODE_OBD),
@@
-            payload.put("locationSampleCount", ObdStoreSupport.countRows(db, VoltTrackerDb.TABLE_LOCATION_SAMPLES))
+            payload.put("locationSampleCount", countLocationSamplesByMode(db, ObdLocalStore.MODE_OBD))
```

### D08 - Charge summary counts all telemetry modes

Severity: Medium

Evidence:
- `mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/data/ObdStoreReports.kt:675-688`
  counts `charge_transition_hint` rows and `maxPowerKw` across all telemetry.

Layman impact: A diagnostic or probe sample can influence the Charge card even
when it was not part of a real drive or charge session.

Diff to fix:

```diff
diff --git a/mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/data/ObdStoreReports.kt b/mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/data/ObdStoreReports.kt
@@
-                ).put("maxPowerKw", ObdStoreSupport.maxDouble(db, VoltTrackerDb.TABLE_TELEMETRY, "power_kw"))
+                ).put("maxPowerKw", maxTelemetryDoubleByMode(db, "power_kw", ObdLocalStore.MODE_OBD))
```

Also change `chargingHintCount` to the same mode-filtered join.

### D09 - Last-session review can select scans instead of the last drive

Severity: Medium

Evidence:
- `mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/data/ObdStoreReports.kt:264-297`
  uses `ObdStoreSessionReview.latestReviewableSession` for the storage summary
  review and route.
- `mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/data/ObdStoreSessionReview.kt:14-41`
  picks any recent session with useful telemetry, PID rows, or location rows,
  without checking mode.

Layman impact: After a scan or detail probe, "last session" review can stop
showing the last drive and start showing a maintenance session.

Diff to fix:

```diff
diff --git a/mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/data/ObdStoreSessionReview.kt b/mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/data/ObdStoreSessionReview.kt
@@
         for (session in ObdStoreSupport.getRecentSessions(db, 20)) {
+            if (session.mode != ObdLocalStore.MODE_OBD) {
+                continue
+            }
```

If scan review is useful, expose it separately as "latest diagnostic session"
instead of reusing the drive review slot.

### D10 - Route projection can ignore cleaner location samples when telemetry has more GPS rows

Severity: Low

Evidence:
- `mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/data/ObdStoreRouteProjection.kt:115-147`
  uses `location_samples` only when `locationTotal >= telemetryTotal`; otherwise
  it falls back to telemetry GPS rows.

Layman impact: A drive with dedicated location samples can still render a route
from noisier telemetry-copied GPS if telemetry has more rows.

Diff to fix:

```diff
diff --git a/mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/data/ObdStoreRouteProjection.kt b/mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/data/ObdStoreRouteProjection.kt
@@
-        if (locationTotal > 0 && locationTotal >= telemetryTotal) {
+        if (locationTotal > 0) {
@@
-            if (locationPoints.length() >= 2 || telemetryTotal == 0L) {
+            if (locationPoints.length() >= 2 || telemetryTotal == 0L) {
                 return locationPoints
             }
```

### P01 - VIN parsing lacks ISO-TP multi-frame reassembly

Severity: High

Evidence:
- `mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/ObdProtocol.kt:337-372`
  strips non-hex characters and reads 17 VIN bytes after `490201` or `4902`.
- `mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/ObdProtocol.kt:481-506`
  has continuation-frame parsing for DTCs, but VIN does not use it.
- `mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/ObdPollingEngine.kt:427-433`
  disables headers with `ATH0`, but ISO-TP VIN replies still include first-frame
  and consecutive-frame PCI bytes.
- `mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/DiagnosticScanRunner.kt:43-45`
  and `ObdPollingEngine.kt:491-510` rely on `parseVin` to persist vehicle
  identity.

Layman impact: Many real adapters return VIN over multiple CAN frames. The app
can fail to identify the vehicle even though the adapter gave a valid VIN.

Diff to fix:

```diff
diff --git a/mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/ObdProtocol.kt b/mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/ObdProtocol.kt
@@
     fun parseVin(response: String?): String? {
@@
-        val hex = response.uppercase(Locale.US).replace(Regex("[^0-9A-F]"), "")
+        val hex = reassembleMode09VinPayload(response) ?: response.uppercase(Locale.US).replace(Regex("[^0-9A-F]"), "")
@@
     }
+
+    private fun reassembleMode09VinPayload(response: String?): String? {
+        // Reassemble ISO-TP first frame + consecutive frames, then return
+        // the service 49 PID 02 payload without PCI bytes.
+        TODO("add headers-off and headers-on ISO-TP VIN tests first")
+    }
```

Add tests for headers-off `10 14 49 02 01 ... 21 ... 22 ...` and header-on
`7E8 10 14 ...` shapes.

### P02 - Charger power stale age tracks only current, not voltage plus current

Severity: Medium

Evidence:
- `mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/LiveSampleReader.kt:507-514`
  derives `chargerPowerKw` from voltage command `22436B` and current command
  `22436C`.
- `LiveSampleReader.kt:335-339` marks `chargerPowerStaleMs` from `22436C` only.
- `LiveSampleReader.kt:307-320` handles the pack-power equivalent correctly by
  taking max stale age across voltage and current.

Layman impact: The app can show charger power as fresh when half of the formula
comes from an old voltage reading.

Diff to fix:

```diff
diff --git a/mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/LiveSampleReader.kt b/mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/LiveSampleReader.kt
@@
-        putStaleMsForPresentValue(sample, "chargerPowerKw", "chargerPowerStaleMs", "22436C", now)
+        if (sample.has("chargerPowerKw")) {
+            val voltageStaleMs = pidPolling.staleMsFor("22436B", now)
+            val currentStaleMs = pidPolling.staleMsFor("22436C", now)
+            if (voltageStaleMs != null && currentStaleMs != null) {
+                sample.put("chargerPowerStaleMs", maxOf(voltageStaleMs, currentStaleMs))
+            }
+        }
```

### P03 - Motor voltage and motor power values do not get stale markers

Severity: Medium

Evidence:
- `mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/LiveSampleReader.kt:291-296`
  emits motor current, voltage, and derived power for motors A and B.
- `LiveSampleReader.kt:356-358` emits stale markers only for current fields.

Layman impact: The dashboard can show motor voltage/power without telling the
user whether those values are fresh or stale.

Diff to fix:

```diff
diff --git a/mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/LiveSampleReader.kt b/mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/LiveSampleReader.kt
@@
         putStaleMsForPresentValue(sample, "motorACurrentA", "motorAStaleMs", "222883", now)
         putStaleMsForPresentValue(sample, "motorBCurrentA", "motorBStaleMs", "222884", now)
+        putStaleMsForPresentValue(sample, "motorAVoltage", "motorAVoltageStaleMs", "222885", now)
+        putStaleMsForPresentValue(sample, "motorBVoltage", "motorBVoltageStaleMs", "222886", now)
+        putDerivedPowerStaleMs(sample, "motorAPowerKw", "motorAPowerStaleMs", "222885", "222883", now)
+        putDerivedPowerStaleMs(sample, "motorBPowerKw", "motorBPowerStaleMs", "222886", "222884", now)
```

### U01 - Test-connection button stays busy even when native immediately blocks

Severity: Medium

Evidence:
- `mobile/android/app/src/main/dashboard-src/js/connection-tools.ts:40-57`
  unconditionally disables the button for 25.5 seconds after
  `safeCall("startTestConnection")`.
- `mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/TroubleshooterBridge.kt:205-215`
  can immediately publish `blocked` when no adapter is remembered.

Layman impact: A user can tap "Test connection", get no actual probe, and still
wait with a disabled "Probing..." button.

Diff to fix:

```diff
diff --git a/mobile/android/app/src/main/dashboard-src/js/connection-tools.ts b/mobile/android/app/src/main/dashboard-src/js/connection-tools.ts
@@
-    safeCall("startTestConnection");
+    const started = safeCall("startTestConnection");
+    if (started === false) {
+      setButtonBusy(btn, false, original);
+      return;
+    }
```

Change the native bridge method to return `false` for blocked/no-adapter and
`true` when a probe actually starts.

### U02 - Force-stop competing app button says "Sent" even when native returns false

Severity: Low

Evidence:
- `mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/TroubleshooterBridge.kt:76-97`
  returns `false` for empty, unknown, not-installed, or denied force-stop calls.
- `mobile/android/app/src/main/dashboard-src/js/troubleshooter.ts:377-391`
  ignores the return value and leaves the button disabled with `Sent` unless the
  bridge throws.

Layman impact: The app can tell the user it sent a force-stop request even when
the native side rejected it.

Diff to fix:

```diff
diff --git a/mobile/android/app/src/main/dashboard-src/js/troubleshooter.ts b/mobile/android/app/src/main/dashboard-src/js/troubleshooter.ts
@@
-          bridge.forceStopPackage(pkg);
+          const sent = bridge.forceStopPackage(pkg);
+          if (sent === false) {
+            button.disabled = false;
+            button.textContent = "Force-stop";
+          }
```

### U03 - Scan-complete status is treated as active live telemetry

Severity: Medium

Evidence:
- `mobile/android/app/src/main/dashboard-src/js/telemetry.ts:96-103` accepts
  non-demo telemetry while status is active or the sample is younger than 30s.
- `telemetry.ts:105-108` treats `scan-complete` as active.
- `mobile/android/app/src/main/kotlin/com/volttracker/obdpoc/DiagnosticScanRunner.kt:119-134`
  broadcasts a scan sample and then status `scan-complete`.

Layman impact: Immediately after a scan ends, the dashboard can keep treating a
scan result as live telemetry instead of a completed diagnostic result.

Diff to fix:

```diff
diff --git a/mobile/android/app/src/main/dashboard-src/js/telemetry.ts b/mobile/android/app/src/main/dashboard-src/js/telemetry.ts
@@
-    return ["connected", "connecting", "initializing", "scanning", "scan-complete", "demo"].includes(status);
+    return ["connected", "connecting", "initializing", "scanning", "demo"].includes(status);
```

If `scan-complete` should keep the raw scan visible, route that payload to a
scan-specific UI state instead of the live telemetry state.

## Recommended Fix Order

1. Runtime/service edge cases: A02, A04, A05, A06, A07.
2. Data correctness: D01, D02, D03, D04, D05.
3. Mode-filtering UI/reporting cleanup: D06, D07, D08, D09, U03.
4. Adapter/probe polish: U01, U02.
5. Parser/staleness accuracy: P01, P02, P03.

## Test Coverage To Add

- `DatabaseMergerTest`: repeated import assertions for `field_capabilities`,
  `adapter_history`, and `diagnostic_codes`.
- `MaterializerIntegrationDbTest`: call `materializeSession` twice and assert
  one trip row and one charge row remain.
- `ObdStoreReportsDbTest` / `ObdStoreTripsDbTest`: create mixed `obd`, `scan`,
  and detail sessions, then assert dashboard summaries count only the intended
  modes.
- `ObdProtocolTest`: real ISO-TP VIN examples with headers off and headers on.
- Dashboard Vitest: `scan-complete` should not continue feeding live telemetry;
  blocked test-connection should re-enable the button immediately.
