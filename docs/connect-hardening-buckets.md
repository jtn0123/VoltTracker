# Connection-hardening mega-PR — bucket contracts

This is the shared contract every bucket codes against. The prep commit on this
branch added the scaffolding (enum, POJO, broadcast-merge, JS-bridge stubs,
empty owned files); each of the 5 agents fills in their bucket without
touching files owned by other buckets.

## Buckets at a glance

| # | Bucket | Items |
|---|---|---|
| 1 | Engine: failure classification & retry intelligence | A1 A2 A3 A4 B3 B6 |
| 2 | BT observability: pre-flight, SDP, OS events | A5 A6 A7 B4 B5 B7 |
| 3 | Logging plumbing: rolling, summary, share | A9 B1 B2 B8 B9 B10 |
| 4a | Error & troubleshooter UX | A8 C1 C3 C4 C6 |
| 4b | Status & proactive tools UX | C2 C5 C7 C8 C9 C10 |

## File ownership (exclusive)

### Bucket 1
- `ObdPollingEngine.java` (full ownership)
- `ElmConnection.java` (full ownership)
- *new* `ConnectionFailureClassifier.java`

### Bucket 2
- `ObdService.java` — **only** the receiver registration in onCreate/onDestroy and the
  three setter calls (`setLastFailureClass` already exists for Bucket 1's use, but
  Bucket 2 owns calls to `setLastVoltage` and `setCompetingApps`)
- *new* `BluetoothStateReporter.java`
- *new* `SdpProbe.java`
- *new* `CompetingAppDetector.java`
- *new* `VoltageProbe.java`
- May add one hook call into `ObdPollingEngine.connectAndInitialize` — coordinate
  with Bucket 1 by adding a single `service.bluetoothObservability.onPreConnect(address)`
  line; do not refactor the surrounding code.

### Bucket 3
- `ObdSessionLog.java` — extend with crash-safe flush hook
- `SessionRecorder.java` — call `SessionSummaryStore.recordEnd(...)` from existing
  `logEvent("session_end")` path; no other edits
- *new* `RollingAppLog.java`
- *new* `SessionSummaryStore.java`
- *new* `DiagnosticsShareIntent.java`
- *new* `LogcatMirror.java`
- *new* `SystemSnapshot.java`

### Bucket 4a
- *new* `partials/troubleshooter.html`
- *new* `js/troubleshooter.js`
- *new* `css/troubleshooter.css`
- `partials/error-banner.html` — error-copy section only
- `js/panels.js` — retry-cancel button binding only
- `VoltBridge.java` — only inside the **BUCKET 4a region** (`forceStopPackage`,
  `cancelRetry`, `tryReconnectNow` bodies)

### Bucket 4b
- *new* `partials/connection-tools.html`
- *new* `js/connection-status.js`
- *new* `js/connection-tools.js`
- *new* `css/status-tools.css`
- `partials/topbar.html` — add badge + health pill
- `partials/settings.html` — add diagnostics share entry
- `VoltBridge.java` — only inside the **BUCKET 4b region** (`getRecentSessions`,
  `shareDiagnostics`, `startTestConnection`, `scheduleAdapterReadyNotify` bodies)

## Shared contracts

### `FailureClass` enum → `failureClass` string field
Bucket 1's `ConnectionFailureClassifier` returns one of (Java enum constants;
wire format is the snake_case `wireName()`):
`INSTANT_DROP | CONNECT_TIMEOUT | SDP_FAILURE | BT_OFF | BOND_LOST | REMOTE_REFUSED | UNKNOWN`

Bucket 1 calls `service.setLastFailureClass(fc)` (already wired) and **also** logs the
class in the existing `reconnect` and `reconnect_exhausted` events as
`"failureClass", fc.wireName()`.

The auto-merge in `ObdService.broadcastStatus` puts it on the status payload as
`"failureClass": "instant_drop"` (snake_case wire format). Bucket 4a reads it
from the status broadcast; Bucket 3 reads it when finalizing a session summary.
Dashboards and tests should compare against the lowercase `wireName()` string,
not the uppercase enum constant name.

### `lastVoltage` number field
Bucket 2's `VoltageProbe` runs Mode 01 PID 42 after init, calls
`service.setLastVoltage(v)`. Auto-merged onto every subsequent status broadcast as
`"lastVoltage": 12.6`. Bucket 4b reads it for the low-voltage hint (C9).

### `competingApps` CSV string field
Bucket 2's `CompetingAppDetector` runs at session_start, calls
`service.setCompetingApps("io.tripovan.voltage,com.foo.bar")`. Auto-merged onto
status broadcasts as `"competingApps": "io.tripovan.voltage,..."`. Bucket 4a
reads it to surface the "force-stop Voltage" button (C4).

### `SessionSummary` + `sessions-summary.jsonl`
Bucket 3's `SessionSummaryStore` appends one JSON line per session end to
`files/obd-logs/sessions-summary.jsonl`. Shape is exactly `SessionSummary.toJson()`.
Bucket 4b reads via `VoltBridge.getRecentSessions(n)` for the "last connected"
badge (C2) and adapter-health pill (C8).

### VoltBridge JS bridge methods
The 7 stubs in the two fenced regions of `VoltBridge.java` are the entire UI ABI.
Other buckets do not add bridge methods. Each UI bucket replaces only its own
stub bodies.

## Cross-bucket coordination rules

1. **No edits outside your file-ownership list.** If you need a helper from
   another bucket, call it through the contract above; don't reach into its file.
2. **No bridge method additions outside the two fenced regions.** Existing
   VoltBridge methods stay as-is.
3. **CSS:** each UI bucket adds to its own stylesheet only. Do not touch
   `components.css`. Namespace your selectors.
4. **JS:** each UI bucket adds to its own JS files only. Listen to the existing
   `BROADCAST_STATUS` payload via the same surface other JS files use; do not
   add new bridge channels.
5. **Build verification:** before committing, run
   `cd mobile/android && ./gradlew.bat :app:assembleDebug :app:testDebugUnitTest`
   and report failures.
6. **Don't rebase or merge** — your worktree forks from the prep commit; the
   parent agent merges everything at the end.
