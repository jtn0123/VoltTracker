# WebView Bridge Threat Model

VoltTracker's dashboard is a local Android WebView loaded from
`file:///android_asset/dashboard/index.html`. The bridge is intentionally small
enough to audit, but it is powerful: JavaScript can ask native code to start
services, export data, restore backups, delete stored data, open system screens,
and force-stop known competing OBD apps.

## Trust Boundaries

| Boundary | Direction | Current guard |
|---|---|---|
| Dashboard -> Android | `window.VoltTrackerAndroid.*` calls `VoltBridge.kt` methods annotated with `@JavascriptInterface`. | Exact ABI pinned by `VoltBridgeTest`; string inputs are trimmed/truncated; Bluetooth addresses and row IDs are validated before native actions. |
| Android -> Dashboard | Native calls `window.VoltTrackerNative.*` through `DashboardPublisher`. | Function-name allowlist, page-ready/liveness gates, and `JSONObject.quote` payload escaping. |
| WebView navigation | Main-frame navigation away from `file:///android_asset/dashboard/`. | `WebViewBootstrap` blocks off-origin navigation. External DTC search uses an Android `ACTION_VIEW` intent, not WebView navigation. |
| Resource loads | Scripts, styles, images, and tile fetches. | CSP meta allows first-party scripts/styles and only the documented tile hosts for remote map tiles. |

## High-Risk Bridge Methods

| Method | Risk | Required precondition / guard |
|---|---|---|
| `clearStoredData()` | Destructive local SQLite delete. | Refuses while logging is active; shows a native Android confirmation; work happens on background executor; status is republished after completion. |
| `shareBackup()` / `shareEncryptedBackup(passphrase)` | Exports full local database, including GPS/OBD history. | Refuses while logging is active; encrypted path requires passphrase; `BackupController` shows a disclosure before sharing. |
| `restoreBackup()` / `restoreEncryptedBackup(passphrase)` | Replaces or merges local database content. | Refuses while logging is active; restore file is staged, size-capped, schema-checked, migrated, then user chooses merge/replace. |
| `forceStopPackage(packageName)` | Requests Android kill of another package. | Shows a native Android confirmation first; only allowlisted known OBD packages are stopped; rejects own package and uninstalled packages; user-triggered only. |
| `clearVehicleDtcCodes()` | Sends a real vehicle command through the remembered adapter. | Requires a remembered valid Bluetooth adapter address; shows a native Android confirmation; runs through the foreground service path. |
| `openExternalSearch(dtc)` | Opens a browser to search a diagnostic code. | Truncates code input and URL-encodes the query; leaves the WebView. |
| `logClientError(label, detail)` | Writes untrusted dashboard text into logcat. | Truncates inputs and token-bucket rate limits bursty callers. |

## Review Rules

When adding or changing a bridge method:

1. Update `bridge-abi.md` and dashboard fixture coverage.
2. Add the method to `VoltBridgeTest.EXPECTED_BRIDGE_METHODS`; that test should fail on unpinned bridge drift.
3. Classify the method in this threat model when it can start services, mutate storage, export data, restore data, open external intents, or affect other apps.
4. Add a focused unit test for the cheapest guard: invalid input, missing precondition, allowlist rejection, logging-active rejection, or no-throw lifecycle behavior.
5. Keep bridge payloads narrow. Prefer native-side validation over trusting dashboard strings.

## Current Residual Risk

The dashboard is local-only and navigation-guarded, so remote content should not
reach `VoltTrackerAndroid`. High-impact methods now also require a native
confirmation or disclosure, so a dashboard-only bug cannot silently clear data,
clear vehicle codes, or force-stop another app. The practical residual risk is
accidental expansion: a future method can become destructive or privacy-sensitive
without the same preconditions as the existing methods. The ABI drift test and
this document are the guardrails; bridge minimization should remain a regular
review target.
