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
| `clearStoredData()` | — | void | — | Runs `ObdLocalStore.clearAllData()` on a background executor (11 DELETEs in one transaction). Reports status via `publishStatus`. |
| `rememberDevice(address, name)` | `String, String` | void | `safe(address, 64)`, `safe(name, 256)` | Updates `DeviceCatalog` without starting the service. |
| `connectLast()` | — | void | bounds applied to cached values from SharedPreferences | Connects to remembered/candidate adapter; `publishStatus("blocked", …)` if none. |
| `scanLast()` | — | void | bounds applied to cached values from SharedPreferences | Scans against remembered/candidate adapter; `publishStatus("blocked", …)` if none. |
| `tpmsScanLast()` | — | void | bounds applied to cached values from SharedPreferences | Runs the user-facing Detail Probe flow against the remembered/candidate adapter; `publishStatus("blocked", …)` if none. |
| `demo()` | — | void | — | Starts `ObdService` with `ACTION_DEMO`. |
| `disconnect()` | — | void | — | Calls `MainActivity.stopObdService()`. |
| `logClientError(label, detail)` | `String, String` | void | `safe(label, 128)`, `safe(detail, 4096)` | Writes a single `Log.e` line; the dashboard uses this for window-level errors, unhandled rejections, and CSP violations. |

The JS-side mirror of this list lives in
`mobile/android/dashboard-tests/setup/voltbridge.fixture.js`. The Vitest ABI
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
| `setAppState(json)` | `String` (JSON object) | `{app:{version, schemaVersion}, permissions:{bluetooth, location, notifications}, adapter:{name, address, remembered, connected}, session:{mode, state, detail, sampleCount, sessionMs, backgroundSampleCount, sampleGapCount, maxSampleGapMs}, vehicle:{state, confidence, vinStored}, gps:{state, accuracyM?, ageMs?}, lifecycle:{appForeground, foregroundServiceActive, backgroundSampleCount, sampleGapCount, lastSampleGapMs, maxSampleGapMs}, latestTelemetry:{…}, storage:{…}}` — exact shape in `AppStateJson.build` | `MainActivity.publishAppState` | `VD.setAppState` (`telemetry.ts`) |
| `updateTelemetry(json)` | `String` (JSON object) | One telemetry sample. Common keys: `source`, `adapter`, `speedKph`, `rpm`, `voltage`, `coolantC`, `loadPct`, `throttlePct`, `soc`, `batteryTemp`, `powerKw`, `vehicleState`, `vehicleStateConfidence`, `latitude`, `longitude`, `accuracyM`, `locationAgeMs`, `appForeground`, `foregroundServiceActive`, `sampleCount`, `sessionMs`, `backgroundSampleCount`, `sampleGapCount`, `lastSampleGapMs`, `maxSampleGapMs`, `updatedAt`. Producer: `ObdService` / `ObdPollingEngine`; consumed via `ObdService.BROADCAST_TELEMETRY` in `MainActivity` | `MainActivity` broadcast receiver | `VD.updateTelemetry` (`telemetry.ts`) |

All `evaluateJavascript` calls funnel through `MainActivity.callDashboard`
(`MainActivity.kt`), which is a no-op until `pageReady` is true.

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
