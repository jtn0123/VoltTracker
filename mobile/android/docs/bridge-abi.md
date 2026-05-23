# Bridge ABI

The VoltTracker WebView dashboard talks to the Android side through a manual ABI.
There are **two** call directions:

1. **JS → Java**: dashboard code calls `window.VoltTrackerAndroid.<method>(...)` —
   each method is a `@JavascriptInterface` on `VoltBridge.java`.
2. **Java → JS**: Android code calls `webView.evaluateJavascript("window.VoltTrackerNative.<method>(...)")` —
   each method is a setter the dashboard JS defines.

Drift between either side is caught only at runtime (or by the Vitest smoke
suite under `mobile/android/dashboard-tests/`). Update this doc whenever you
add or rename a bridge method.

## JS → Java (calls into `VoltBridge.java`)

All string arguments are passed through `safe(value, maxLen)` which null-coalesces,
trims, and truncates. The bounds are `MAX_ADDRESS_LEN=64`, `MAX_NAME_LEN=256`,
`MAX_LABEL_LEN=128`, `MAX_DETAIL_LEN=4096`. See `VoltBridge.java:21-26`.

| Method | Args | Returns | Validation | Notes |
|--------|------|---------|------------|-------|
| `listDevices()` | — | `String` (JSON array) | — | Returns `DeviceCatalog.getBondedDevicesJson()`. |
| `requestPermissions()` | — | void | — | Marshals to UI thread; calls `PermissionGate.ensureGranted`. |
| `refreshDevices()` | — | void | — | Re-publishes device list + storage summary on UI thread. |
| `connect(address, name)` | `String, String` | void | `safe(address, 64)`, `safe(name, 256)` | Remembers device, starts `ObdService` with `ACTION_CONNECT`. |
| `scan(address, name)` | `String, String` | void | `safe(address, 64)`, `safe(name, 256)` | Remembers device, starts `ObdService` with `ACTION_SCAN`. |
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
| `demo()` | — | void | — | Starts `ObdService` with `ACTION_DEMO`. |
| `disconnect()` | — | void | — | Calls `MainActivity.stopObdService()`. |
| `logClientError(label, detail)` | `String, String` | void | `safe(label, 128)`, `safe(detail, 4096)` | Writes a single `Log.e` line; the dashboard uses this for window-level errors, unhandled rejections, and CSP violations. |

The JS-side mirror of this list lives in
`mobile/android/dashboard-tests/setup/voltbridge.fixture.js`. The Vitest ABI
smoke test cross-references this fixture against the `@JavascriptInterface`
methods on `VoltBridge.java`.

## Java → JS (setters on `window.VoltTrackerNative`)

The dashboard installs `window.VoltTrackerNative` in
`mobile/android/app/src/main/assets/dashboard/js/actions.js:299-306`. Each setter
receives a **JSON-encoded string** (Android calls `JSONObject.quote(...)` before
`evaluateJavascript`), which the JS side parses with `VD.parsePayload`.

| Method | Arg | Payload shape | Set from (Java) | Handled by (JS) |
|--------|-----|---------------|-----------------|-----------------|
| `setDevices(json)` | `String` (JSON array) | `[{address, name, obdCandidate?}, …]` from `DeviceCatalog.getBondedDevicesJson()` | `MainActivity.publishDeviceList` (`MainActivity.java:226-229`) | `VD.setDevices` (`core.js:378`) |
| `setHistory(json)` | `String` (JSON array) | `[{address, name, lastSeen?, connectCount?, candidate?}, …]` from `DeviceCatalog.getDeviceHistoryJson()` | `MainActivity.publishDeviceList` (`MainActivity.java:230-233`) | `VD.setHistory` (`core.js:406`) |
| `setStatus(json)` | `String` (JSON object) | `{state, detail, blocked, bluetoothReady, lastAddress, lastName}` — assembled in `MainActivity.publishStatus` (`MainActivity.java:236-252`) | also broadcast-mirrored from `ObdService.BROADCAST_STATUS` (`MainActivity.java:68-73`) | `VD.setStatus` (`telemetry.js:40`) |
| `setStorage(json)` | `String` (JSON object) | `{sessionCount, sampleCount, eventCount, pidObservationCount, diagnosticCodeCount, locationSampleCount, tripSegmentCount, chargeSessionCount, batterySnapshotCount, cellSnapshotCount, …}` from `ObdLocalStore.getStorageSummary()` | `MainActivity.publishStorageSummary` (`MainActivity.java:354-365`) | `VD.setStorage` (`panels.js:9`) |
| `setAppState(json)` | `String` (JSON object) | `{app:{version, schemaVersion}, permissions:{bluetooth, location, notifications}, adapter:{name, address, remembered, connected}, session:{mode, state, detail, sampleCount, sessionMs, backgroundSampleCount, sampleGapCount, maxSampleGapMs}, vehicle:{state, confidence, vinStored}, gps:{state, accuracyM?, ageMs?}, lifecycle:{appForeground, foregroundServiceActive, backgroundSampleCount, sampleGapCount, lastSampleGapMs, maxSampleGapMs}, latestTelemetry:{…}, storage:{…}}` — exact shape in `AppStateJson.build` (`AppStateJson.java:15-99`) | `MainActivity.publishAppState` (`MainActivity.java:415-419`) | `VD.setAppState` (`telemetry.js:54`) |
| `updateTelemetry(json)` | `String` (JSON object) | One telemetry sample. Common keys: `source`, `adapter`, `speedKph`, `rpm`, `voltage`, `coolantC`, `loadPct`, `throttlePct`, `soc`, `batteryTemp`, `powerKw`, `vehicleState`, `vehicleStateConfidence`, `latitude`, `longitude`, `accuracyM`, `locationAgeMs`, `appForeground`, `foregroundServiceActive`, `sampleCount`, `sessionMs`, `backgroundSampleCount`, `sampleGapCount`, `lastSampleGapMs`, `maxSampleGapMs`, `updatedAt`. Producer: `ObdService` / `ObdPollingEngine`; consumed via `ObdService.BROADCAST_TELEMETRY` in `MainActivity` | `MainActivity` broadcast receiver (`MainActivity.java:61-67`) | `VD.updateTelemetry` (`telemetry.js:227`) |

All `evaluateJavascript` calls funnel through `MainActivity.callDashboard`
(`MainActivity.java:345-350`), which is a no-op until `pageReady` is true.

## When this drifts

If you add or rename a bridge method:

1. Update both sides:
   - JS → Java: `VoltBridge.java` **and** `dashboard-tests/setup/voltbridge.fixture.js`
     (both the `createVoltBridgeFixture` stubs and the `VOLT_BRIDGE_METHODS` frozen list).
   - Java → JS: the `window.VoltTrackerNative` object literal in `actions.js`
     **and** the `VD.<setter>` implementation in its owning JS file.
2. Update the table in this doc.
3. The smoke test in `dashboard-tests/voltbridge-abi.test.js` will catch missing
   fixture methods at next test run; it will **not** catch missing doc updates.
