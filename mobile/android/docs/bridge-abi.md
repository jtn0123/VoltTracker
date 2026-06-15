# Bridge ABI

The VoltTracker WebView dashboard talks to the Android side through a manual ABI.
There are **two** call directions:

1. **Dashboard → Android**: dashboard code calls `window.VoltTrackerAndroid.<method>(...)` —
   each method is a `@JavascriptInterface` on `VoltBridge.kt`.
2. **Android → Dashboard**: Android code calls `webView.evaluateJavascript("window.VoltTrackerNative.<method>(...)")` —
   each method is a setter the dashboard TypeScript defines.

Drift between either side is caught only at runtime (or by the Vitest smoke
suite under `mobile/android/dashboard-tests/`). Update this doc whenever you
add or rename a bridge method.

## Dashboard → Android (calls into `VoltBridge.kt`)

All string arguments are passed through `safe(value, maxLen)` which null-coalesces,
trims, and truncates. The bounds are `MAX_ADDRESS_LEN=64`, `MAX_NAME_LEN=256`,
`MAX_LABEL_LEN=128`, `MAX_DETAIL_LEN=4096`. See `VoltBridge.kt`.

| Method | Args | Returns | Validation | Notes |
|--------|------|---------|------------|-------|
| `listDevices()` | — | `String` (JSON array) | — | Returns `DeviceCatalog.getBondedDevicesJson()`. |
| `requestPermissions()` | — | void | — | Marshals to UI thread; calls `PermissionGate.ensureGranted`. |
| `refreshDevices()` | — | void | — | Re-publishes device list + storage summary on UI thread. |
| `connect(address, name)` | `String, String` | void | `safe(address, 64)`, `safe(name, 256)` | Remembers device, starts `ObdService` with `ACTION_CONNECT`. |
| `scan(address, name)` | `String, String` | void | `safe(address, 64)`, `safe(name, 256)` | Remembers device, starts `ObdService` with `ACTION_SCAN`. |
| `tpmsScan(address, name)` | `String, String` | void | `safe(address, 64)`, `safe(name, 256)` | Remembers device, starts the user-facing Detail Probe flow with `ACTION_TPMS_SCAN`; method name is retained for ABI stability. |
| `getLastDevice()` | — | `String` (JSON object) | — | Returns `DeviceCatalog.getLastDeviceJson()`. |
| `getDeviceHistory()` | — | `String` (JSON array) | — | Returns `DeviceCatalog.getDeviceHistoryJson()`. |
| `getStorageSummary()` | — | `String` (JSON object) | — | Synchronous; calls `MainActivity.getStorageSummaryJson()`. |
| `exportDebugBundle()` | — | `String` (JSON object) | — | Returns `{ok, path}` or `{ok:false, error}` from `DataBackup.exportDebugBundle`. |
| `shareBackup()` | — | void | — | Launches the share intent via `BackupController.launchShare`. |
| `restoreBackup()` | — | void | — | Launches a restore picker via `BackupController.launchRestorePicker`. |
| `getTrips()` | — | `String` (JSON array) | — | Calls `MainActivity.getTripsJson()` (40 most-recent trips). |
| `getInsights()` | — | `String` (JSON object) | — | Calls `MainActivity.getInsightsJson()`. |
| `exportTripGpx(routeKeyOrSessionId)` | `String` | `String` (JSON object) | `safe(routeKey, 128)` | Writes the trip as a GPX 1.1 track to the export cache, records it in `exports`, and launches the OS share sheet. Forwards to `MainActivity.exportTripFromBridge(routeKey, "gpx")` → `TripExportController`. Returns `{ok:true, format:"gpx", fileName, bytes, pointCount}` on success or `{ok:false, error, message}` (e.g. `invalid_id`, `empty_route`, `export_failed`, `share_failed`). The id may be a bare session id **or** a `sessionId:startedAt:endedAt` route key. |
| `exportTripCsv(routeKeyOrSessionId)` | `String` | `String` (JSON object) | `safe(routeKey, 128)` | Same as `exportTripGpx` but writes a CSV sample log (`format:"csv"`). Coordinates and timestamps are full-precision plaintext — see `docs/privacy-data-handling.md`. |
| `exportAllTripsCsv()` | — | `String` (JSON object) | — | Bulk all-trips CSV export (M6): every logged trip's GPS samples in **one** CSV, each row prefixed with a trip-id and (CSV-quoted) trip-label column. Bounded to the 500 most-recent trips × 500 samples/trip. Records the export into `exports` (`export_type:"csv_all"`, session-less), then launches the OS share sheet via the same FileProvider path as single-trip export. Rides the existing `MainActivity.exportTripFromBridge(null, "csv_all")` host seam (the `csv_all` sentinel format dispatches to `TripExportController`'s all-trips path — no new host override). Returns `{ok:true, format:"csv", fileName, bytes, tripCount, pointCount}` on success or `{ok:false, error, message}` (e.g. `storage_unavailable`, `empty_route`, `export_failed`, `share_failed`). |
| `setTripLabel(routeKey, label)` | `String, String` | void | `safe(routeKey, 128)`, `safe(label, ObdTripLabels.MAX_LABEL_LEN=80)` | Sets (or clears, when `label` is blank) the user-facing trip label (M4). Persisted as a `trip_label` `status_events` row keyed by route key (`ObdTripLabels`), then refreshes the dashboard. No confirm dialog — renaming is reversible. Reports outcome via `publishStatus`. |
| `setTripFavorite(routeKey, favorite)` | `String, boolean` | void | `safe(routeKey, 128)` | Sets or clears the user "favorite" flag for a stored trip (M4 favorites half). Persisted as a `trip_favorite` `status_events` row keyed by route key (`ObdTripFavorites`) — **no schema change**; the latest event wins, so un-favoriting writes a `favorite=false` event. The resolved flag is stamped onto each trip's JSON (`trip.favorite`). Background write, then refreshes the dashboard. Reports outcome via `publishStatus`. |
| `addMaintenanceEntry(json)` | `String` (JSON object) | void | `safe(json, 4096)`; per-field caps below | Records a maintenance-log row (M5/M1). `json` carries `type` (≤128), `note` (≤4096), optional `odometerKm` (non-finite/negative dropped), optional `date` (ms epoch; defaults to now), and the optional service interval `intervalKm` / `intervalMonths` (M1/C4; non-finite/≤0 dropped) that drives the dashboard "next due" line. Rejected with a `blocked` status when both `type` and `note` are empty. Writes on a background executor, then refreshes storage + status. |
| `getMaintenanceLog()` | — | `String` (JSON array) | — | Newest-first maintenance log (M5/M1), up to 200 rows, read synchronously. Each item: `{id, createdAtMs, odometerKm (number or null), type, note, intervalKm (number or null), intervalMonths (number or null)}`. Returns `"[]"` when storage is unavailable. |
| `deleteMaintenanceEntry(id)` | `String` | void | `parsePositiveId(id)` | Deletes one maintenance row by id (M5) on a background executor, then refreshes storage + status. `blocked` status when the id is non-positive or already gone. |
| `clearStoredData()` | — | void | native confirmation; refuses while logging is active | Runs `ObdLocalStore.clearAllData()` on a background executor (11 DELETEs in one transaction). Reports status via `publishStatus`. |
| `rememberDevice(address, name)` | `String, String` | void | `safe(address, 64)`, `safe(name, 256)` | Updates `DeviceCatalog` without starting the service. |
| `connectLast()` | — | void | bounds applied to cached values from SharedPreferences | Connects to remembered/candidate adapter; `publishStatus("blocked", …)` if none. |
| `scanLast()` | — | void | bounds applied to cached values from SharedPreferences | Scans against remembered/candidate adapter; `publishStatus("blocked", …)` if none. |
| `tpmsScanLast()` | — | void | bounds applied to cached values from SharedPreferences | Runs the user-facing Detail Probe flow against the remembered/candidate adapter; `publishStatus("blocked", …)` if none. |
| `demo()` | — | void | — | Starts `ObdService` with `ACTION_DEMO`. |
| `disconnect()` | — | void | — | Calls `MainActivity.stopObdService()`. |
| `logClientError(label, detail)` | `String, String` | void | `safe(label, 128)`, `safe(detail, 4096)` | Writes a single `Log.e` line; the dashboard uses this for window-level errors, unhandled rejections, and CSP violations. |
| `getEventNotificationState()` | — | `String` (JSON object) | — | The settings snapshot the dashboard renders its event-notification toggles from (M1). Shape: `{chargeComplete:bool, newDtc:bool, lowSoc:bool, lowSocThresholdPct:number, highPackTemp:bool, highPackTempThresholdC:number, autoScanOnConnect:bool}` from `EventNotificationPrefs.stateJson()`. Returns `{ok:false, error:"event_notifications_unavailable", …}` before prefs are ready. |
| `setChargeCompleteNotify(enabled)` | `boolean` | void | — | Toggles the charge-complete alert (M1). Marshals to the UI thread, writes `EventNotificationPrefs`, republishes app state. |
| `setNewDtcNotify(enabled)` | `boolean` | void | — | Toggles the new-DTC alert. Same threading/persistence as `setChargeCompleteNotify`. |
| `setLowSocNotify(enabled, thresholdPct)` | `boolean, double` | void | — | Toggles the low-SOC alert and stores its threshold percent. Fires once per threshold crossing (hysteresis-armed; see ADR 0007). |
| `setHighPackTempNotify(enabled, thresholdC)` | `boolean, double` | void | — | Toggles the high-pack-temp alert and stores its threshold in °C. Hysteresis-armed like low-SOC. |
| `setChargeTargetSoc(targetPct)` | `double` | void | — | Stores the user charge target % (M2; clamped to [50, 100] in `EventNotificationPrefs`). Feeds the "target reached" notification and the complete-vs-interrupted (M3) branch; the dashboard ETA reads its own `chargeTargetSoc` localStorage mirror. |
| `setAutoScanOnConnect(enabled)` | `boolean` | void | — | Toggles the auto-scan-on-connect behavior (feeds the new-DTC baseline). Persisted in `EventNotificationPrefs`. |
| `openSetupGuide()` | — | void | — | Re-opens the guided first-run setup walkthrough on demand (M7 "Setup guide" affordance). Forwards to `MainActivity.openSetupGuideFromBridge`; the staged dialogs are rendered natively from `OnboardingFlow`. |

The JS-side mirror of this list lives in
`mobile/android/dashboard-tests/setup/voltbridge.fixture.js`. Destructive or
privacy-sensitive methods must also go through a native confirmation/disclosure
path owned by `DashboardHost.confirmBridgeAction`, `BackupController`, or
`TroubleshooterBridge`. The Vitest ABI
smoke test cross-references this fixture against the `@JavascriptInterface`
methods on `VoltBridge.kt`.

## Android → Dashboard (setters on `window.VoltTrackerNative`)

The dashboard installs `window.VoltTrackerNative` in
`mobile/android/app/src/main/dashboard-src/js/actions.ts`. Each setter
receives a **JSON-encoded string** (Android calls `JSONObject.quote(...)` before
`evaluateJavascript`), which the JS side parses with `VD.parsePayload`.

| Method | Arg | Payload shape | Set from (Android) | Handled by (dashboard) |
|--------|-----|---------------|-----------------|-----------------|
| `setDevices(json)` | `String` (JSON array) | `[{address, name, obdCandidate?}, …]` from `DeviceCatalog.getBondedDevicesJson()` | `MainActivity.publishDeviceList` | `VD.setDevices` (`core.ts`) |
| `setHistory(json)` | `String` (JSON array) | `[{address, name, lastSeen?, connectCount?, candidate?}, …]` from `DeviceCatalog.getDeviceHistoryJson()` | `MainActivity.publishDeviceList` | `VD.setHistory` (`core.ts`) |
| `setStatus(json)` | `String` (JSON object) | `{state, detail, blocked, bluetoothReady, lastAddress, lastName}` assembled in `MainActivity.publishStatus` | also broadcast-mirrored from `ObdService.BROADCAST_STATUS` | `VD.setStatus` (`telemetry.ts`) |
| `setStorage(json)` | `String` (JSON object) | `{sessionCount, sampleCount, eventCount, pidObservationCount, diagnosticCodeCount, locationSampleCount, tripSegmentCount, chargeSessionCount, batterySnapshotCount, cellSnapshotCount, …}` from `ObdLocalStore.getStorageSummary()` | `MainActivity.publishStorageSummary` | `VD.setStorage` (`panels.ts`) |
| `setAppState(json)` | `String` (JSON object) | `{app:{version, schemaVersion}, permissions:{bluetooth, bluetoothPermission, bluetoothEnabled, location, notifications}, adapter:{name, address, remembered, connected}, session:{mode, state, detail, sampleCount, sessionMs, backgroundSampleCount, sampleGapCount, maxSampleGapMs}, vehicle:{state, confidence, vinStored}, gps:{state, accuracyM?, ageMs?}, lifecycle:{appForeground, foregroundServiceActive, backgroundSampleCount, sampleGapCount, lastSampleGapMs, maxSampleGapMs}, latestTelemetry:{…}, storage:{…}}` — exact shape in `AppStateJson.build` | `MainActivity.publishAppState` | `VD.setAppState` (`telemetry.ts`) |
| `updateTelemetry(json)` | `String` (JSON object) | One telemetry sample. Common keys: `source`, `adapter`, `speedKph`, `rpm`, `voltage`, `coolantC`, `loadPct`, `throttlePct`, `soc`, `batteryTemp`, `powerKw`, `vehicleState`, `vehicleStateConfidence`, `latitude`, `longitude`, `accuracyM`, `locationAgeMs`, `appForeground`, `foregroundServiceActive`, `sampleCount`, `sessionMs`, `backgroundSampleCount`, `sampleGapCount`, `lastSampleGapMs`, `maxSampleGapMs`, `updatedAt`. Producer: `ObdService` / `ObdPollingEngine`; consumed via `ObdService.BROADCAST_TELEMETRY` in `MainActivity` | `MainActivity` broadcast receiver | `VD.updateTelemetry` (`telemetry.ts`) |

All `evaluateJavascript` calls funnel through `MainActivity.callDashboard`
(`MainActivity.kt`), which is a no-op until `pageReady` is true.

> **`permissions.notifications` vs. notification settings.** The boolean
> `permissions.notifications` in `setAppState` is only the **`POST_NOTIFICATIONS`
> runtime-grant** flag (Android 13+). The event-notification *toggles and
> thresholds* are **not** in the app-state payload — the dashboard pulls them on
> demand via the `getEventNotificationState()` Dashboard → Android call above.
> Also note `app.schemaVersion` here is the **app-state payload version**
> (currently `4`, hardcoded in `AppStatePayload.appJson`), unrelated to the
> SQLite `DATABASE_VERSION` documented in `docs/data-model.md`.

## When this drifts

If you add or rename a bridge method:

1. Update both sides:
   - Dashboard → Android: `VoltBridge.kt` **and** `dashboard-tests/setup/voltbridge.fixture.js`
     (both the `createVoltBridgeFixture` stubs and the `VOLT_BRIDGE_METHODS` frozen list).
   - Android → Dashboard: the `window.VoltTrackerNative` object literal in `actions.ts`
     **and** the `VD.<setter>` implementation in its owning TypeScript file.
2. Update the table in this doc.
3. The smoke test in `dashboard-tests/voltbridge-abi.test.js` will catch missing
   fixture methods at next test run; it will **not** catch missing doc updates.
