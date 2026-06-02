package com.volttracker.obdpoc;

import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.webkit.JavascriptInterface;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/**
 * The {@code @JavascriptInterface} surface the dashboard WebView calls into. Each method is a thin
 * adapter that marshals onto the UI thread and delegates to {@link MainActivity} and its helper
 * objects. Extracted to its own file so the bridge API is isolated from the Activity lifecycle
 * code.
 *
 * <p>The dashboard is loaded same-origin from {@code file:///android_asset/dashboard/} so the JS
 * that reaches this surface is the project's own bundled code, not arbitrary web content. The
 * bridge still validates and bounds every string input — the JS layer is the trust boundary, and a
 * regression there must not be able to crash the native side or fill the logs with megabytes of
 * attacker-controlled text.
 */
public final class VoltBridge {

    /** Defensive caps for bridge string inputs. Generous, but not unbounded. */
    private static final int MAX_ADDRESS_LEN = 64; // MAC address is 17 chars; allow URI forms.

    private static final int MAX_NAME_LEN = 256;
    private static final int MAX_LABEL_LEN = 128;
    private static final int MAX_DETAIL_LEN = 4096;
    private static final int MAX_DTC_LEN = 16;
    private static final int MAX_PASSPHRASE_LEN = 256;

    /**
     * Token-bucket rate limit for {@link #logClientError}. The dashboard JS calls it freely (CSP
     * violations, the global error handler, missing-tile warnings); a buggy or looping client could
     * otherwise spam logcat. Refills one token per {@code 1000/LOG_CLIENT_ERROR_RATE_PER_SEC} ms up
     * to {@code LOG_CLIENT_ERROR_BURST}, so normal volumes pass through untouched and only a
     * runaway caller is throttled.
     */
    private static final int LOG_CLIENT_ERROR_RATE_PER_SEC = 5;

    private static final int LOG_CLIENT_ERROR_BURST = 10;

    private final MainActivity activity;

    // Token-bucket state for logClientError, guarded by clientErrorLock. The bridge can be called
    // from multiple WebView threads, so the accounting must be synchronized.
    private final Object clientErrorLock = new Object();
    private double clientErrorTokens = LOG_CLIENT_ERROR_BURST;
    private long clientErrorLastRefillMs = 0L;
    private long clientErrorDroppedCount = 0L;

    VoltBridge(MainActivity activity) {
        this.activity = activity;
    }

    @JavascriptInterface
    public void dashboardReady() {
        activity.runOnUiThread(activity::onDashboardReady);
    }

    /**
     * Null-coalesces, trims, and bounds a bridge string argument. {@link String#substring} cuts on
     * UTF-16 code-unit boundaries, so a naive {@code substring(0, maxLen)} can split a surrogate
     * pair and leave a lone high-surrogate that corrupts every downstream consumer (JSON
     * serializer, SQLite UTF-8 conversion, logcat, share-sheet text). Back the cut up one position
     * when it would land mid-pair.
     */
    private static String safe(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxLen) {
            return trimmed;
        }
        int cut = maxLen;
        if (cut > 0 && Character.isHighSurrogate(trimmed.charAt(cut - 1))) {
            cut -= 1;
        }
        return trimmed.substring(0, cut);
    }

    @JavascriptInterface
    public String listDevices() {
        return activity.deviceCatalog.getBondedDevicesJson();
    }

    @JavascriptInterface
    public void requestPermissions() {
        activity.runOnUiThread(activity.permissionGate::ensureGranted);
    }

    @JavascriptInterface
    public void refreshDevices() {
        activity.runOnUiThread(
                () -> {
                    activity.publishDeviceList();
                    activity.publishStorageSummary();
                });
    }

    @JavascriptInterface
    public void connect(String address, String name) {
        final String cleanAddress = safe(address, MAX_ADDRESS_LEN);
        final String cleanName = safe(name, MAX_NAME_LEN);
        activity.runOnUiThread(
                () -> {
                    activity.rememberDevice(cleanAddress, cleanName);
                    activity.startObdService(ObdService.ACTION_CONNECT, cleanAddress, cleanName);
                });
    }

    @JavascriptInterface
    public void scan(String address, String name) {
        final String cleanAddress = safe(address, MAX_ADDRESS_LEN);
        final String cleanName = safe(name, MAX_NAME_LEN);
        activity.runOnUiThread(
                () -> {
                    activity.rememberDevice(cleanAddress, cleanName);
                    activity.startObdService(ObdService.ACTION_SCAN, cleanAddress, cleanName);
                });
    }

    @JavascriptInterface
    public String getLastDevice() {
        return activity.deviceCatalog.getLastDeviceJson();
    }

    @JavascriptInterface
    public String getDeviceHistory() {
        return activity.deviceCatalog.getDeviceHistoryJson();
    }

    @JavascriptInterface
    public String getStorageSummary() {
        return activity.getStorageSummaryJson();
    }

    @JavascriptInterface
    public String exportDebugBundle() {
        return activity.dataBackup.exportDebugBundle(
                activity.getAppStateJson(), activity.getStorageSummaryJson());
    }

    @JavascriptInterface
    public void shareBackup() {
        activity.runOnUiThread(activity.backupController::launchShare);
    }

    @JavascriptInterface
    public void shareEncryptedBackup(String passphrase) {
        final String cleanPassphrase = safe(passphrase, MAX_PASSPHRASE_LEN);
        activity.runOnUiThread(
                () -> activity.backupController.launchEncryptedShare(cleanPassphrase));
    }

    @JavascriptInterface
    public void restoreBackup() {
        activity.runOnUiThread(activity.backupController::launchRestorePicker);
    }

    @JavascriptInterface
    public void restoreEncryptedBackup(String passphrase) {
        final String cleanPassphrase = safe(passphrase, MAX_PASSPHRASE_LEN);
        activity.runOnUiThread(
                () -> activity.backupController.launchEncryptedRestorePicker(cleanPassphrase));
    }

    @JavascriptInterface
    public String getTrips() {
        return activity.getTripsJson();
    }

    @JavascriptInterface
    public String getInsights() {
        return activity.getInsightsJson();
    }

    /**
     * Full route geometry for a single logged trip, by session id. Lets the Trips screen load the
     * route of any drive on demand — including older drives outside the storage summary's
     * recent-routes window. Non-numeric input yields an empty route payload rather than an error.
     */
    @JavascriptInterface
    public String getTripRoute(String sessionId) {
        long id;
        try {
            id = Long.parseLong(safe(sessionId, MAX_LABEL_LEN));
        } catch (NumberFormatException ex) {
            return "{}";
        }
        return activity.getTripRouteJson(id);
    }

    @JavascriptInterface
    public void clearStoredData() {
        // clearAllData runs 11 DELETEs in one transaction — keep it off the main thread.
        activity.runOnBackground(
                () -> {
                    try {
                        if (activity.localStore != null) {
                            activity.localStore.clearAllData();
                        }
                    } catch (RuntimeException ex) {
                        Log.w(MainActivity.TAG, "clearStoredData failed", ex);
                        activity.runOnUiThread(
                                () ->
                                        activity.publishStatus(
                                                "blocked",
                                                "Could not clear the local OBD database.",
                                                true));
                        return;
                    }
                    activity.runOnUiThread(
                            () -> {
                                activity.publishStorageSummary();
                                activity.publishStatus(
                                        "ready", "On-phone OBD database cleared.", false);
                            });
                });
    }

    @JavascriptInterface
    public void rememberDevice(String address, String name) {
        final String cleanAddress = safe(address, MAX_ADDRESS_LEN);
        final String cleanName = safe(name, MAX_NAME_LEN);
        activity.runOnUiThread(() -> activity.rememberDevice(cleanAddress, cleanName));
    }

    @JavascriptInterface
    public void connectLast() {
        JSONObject device = activity.deviceCatalog.getLastOrCandidateDevice();
        // Cached values come from SharedPreferences-backed JSON — bound them the same
        // way we bound JS-supplied input, so a corrupted store can't blow up the bridge.
        final String address = safe(device.optString("address", ""), MAX_ADDRESS_LEN);
        final String name = safe(device.optString("name", ""), MAX_NAME_LEN);
        activity.runOnUiThread(
                () -> {
                    if (address.isEmpty()) {
                        activity.publishStatus(
                                "blocked",
                                "No remembered adapter yet. Connect once to save it.",
                                true);
                        return;
                    }
                    activity.rememberDevice(address, name);
                    activity.startObdService(ObdService.ACTION_CONNECT, address, name);
                });
    }

    /**
     * Sends OBD-II Mode 04 to the currently-remembered adapter, clearing vehicle DTC memory. The JS
     * layer is responsible for surfacing the warning checkbox confirmation BEFORE calling this
     * method - this bridge does no UI prompt of its own.
     */
    @JavascriptInterface
    public void clearVehicleDtcCodes() {
        JSONObject device = activity.deviceCatalog.getLastOrCandidateDevice();
        final String address = safe(device.optString("address", ""), MAX_ADDRESS_LEN);
        final String name = safe(device.optString("name", ""), MAX_NAME_LEN);
        activity.runOnUiThread(
                () -> {
                    if (address.isEmpty()) {
                        activity.publishStatus(
                                "blocked",
                                "No remembered adapter yet. Connect once to save it.",
                                true);
                        return;
                    }
                    activity.rememberDevice(address, name);
                    activity.startObdService(ObdService.ACTION_CLEAR_DTC, address, name);
                });
    }

    /**
     * Opens a Google search for "{code} Chevy Volt DTC" in the system browser. The DTC string is
     * length-bounded and the URL is constructed locally - the JS layer cannot smuggle an arbitrary
     * URL through this surface.
     */
    @JavascriptInterface
    public void openExternalSearch(String dtc) {
        final String code = safe(dtc, MAX_DTC_LEN);
        if (code.isEmpty()) return;
        activity.runOnUiThread(
                () -> {
                    try {
                        String q =
                                URLEncoder.encode(
                                        code + " Chevy Volt DTC", StandardCharsets.UTF_8.name());
                        Uri uri = Uri.parse("https://www.google.com/search?q=" + q);
                        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        activity.startActivity(intent);
                    } catch (RuntimeException | java.io.UnsupportedEncodingException ex) {
                        Log.w(MainActivity.TAG, "openExternalSearch failed", ex);
                        activity.publishStatus(
                                "blocked", "Could not open the browser for DTC lookup.", true);
                    }
                });
    }

    @JavascriptInterface
    public void scanLast() {
        JSONObject device = activity.deviceCatalog.getLastOrCandidateDevice();
        final String address = safe(device.optString("address", ""), MAX_ADDRESS_LEN);
        final String name = safe(device.optString("name", ""), MAX_NAME_LEN);
        activity.runOnUiThread(
                () -> {
                    if (address.isEmpty()) {
                        activity.publishStatus(
                                "blocked",
                                "No remembered adapter yet. Connect once to save it.",
                                true);
                        return;
                    }
                    activity.rememberDevice(address, name);
                    activity.startObdService(ObdService.ACTION_SCAN, address, name);
                });
    }

    @JavascriptInterface
    public void demo() {
        activity.runOnUiThread(
                () -> activity.startObdService(ObdService.ACTION_DEMO, null, "Demo stream"));
    }

    @JavascriptInterface
    public void disconnect() {
        activity.runOnUiThread(activity::stopObdService);
    }

    @JavascriptInterface
    public void logClientError(String label, String detail) {
        long dropped = acquireClientErrorTokenOrCountDrop();
        if (dropped < 0) {
            // Rate limit exceeded — drop this call. The drop was counted; the next admitted call
            // surfaces the accumulated total so a runaway client is visible without itself
            // spamming.
            return;
        }
        String suffix = dropped > 0 ? " (suppressed " + dropped + " over-rate calls)" : "";
        Log.e(
                MainActivity.TAG,
                "dashboard client error ["
                        + safe(label, MAX_LABEL_LEN)
                        + "]: "
                        + safe(detail, MAX_DETAIL_LEN)
                        + suffix);
    }

    /**
     * Token-bucket gate for {@link #logClientError}. Returns the number of calls dropped since the
     * last admitted one (≥ 0) when a token is available — the caller folds that count into the
     * emitted line — or {@code -1} when no token is available and this call must be dropped.
     */
    private long acquireClientErrorTokenOrCountDrop() {
        synchronized (clientErrorLock) {
            long now = System.currentTimeMillis();
            if (clientErrorLastRefillMs == 0L) {
                clientErrorLastRefillMs = now;
            }
            long elapsed = Math.max(0L, now - clientErrorLastRefillMs);
            if (elapsed > 0L) {
                double refill = (elapsed / 1000.0) * LOG_CLIENT_ERROR_RATE_PER_SEC;
                clientErrorTokens = Math.min(LOG_CLIENT_ERROR_BURST, clientErrorTokens + refill);
                clientErrorLastRefillMs = now;
            }
            if (clientErrorTokens < 1.0) {
                clientErrorDroppedCount++;
                return -1L;
            }
            clientErrorTokens -= 1.0;
            long dropped = clientErrorDroppedCount;
            clientErrorDroppedCount = 0L;
            return dropped;
        }
    }

    // ============================================================================================
    // === Error & troubleshooter UX                                                           ===
    // ============================================================================================

    /**
     * Force-stop a competing app surfaced in {@code competingApps}. Uses {@link
     * android.app.ActivityManager#killBackgroundProcesses(String)}; Android limits this to
     * background-promotable apps but it is the right API to expose, and we surface success/failure
     * honestly via the return value (true if the package is installed, false otherwise).
     */
    @JavascriptInterface
    public boolean forceStopPackage(String packageName) {
        final String pkg = safe(packageName, MAX_NAME_LEN);
        if (pkg.isEmpty()) {
            return false;
        }
        return activity.forceStopPackageFromBridge(pkg);
    }

    /**
     * Cancel an in-flight retry burst. Sets the {@code cancelRetryRequested} flag on {@link
     * ObdService}; the engine reads it between connect attempts and bails out instead of issuing
     * the next retry.
     */
    @JavascriptInterface
    public void cancelRetry() {
        activity.runOnUiThread(activity::cancelRetryFromBridge);
    }

    /**
     * Troubleshooter primary action — re-issue ACTION_CONNECT with the last-known device. Mirrors
     * {@link #connectLast()} but skips the "no remembered adapter" status string so the
     * troubleshooter modal can show its own copy when there is no device on file yet.
     */
    @JavascriptInterface
    public void tryReconnectNow() {
        JSONObject device = activity.deviceCatalog.getLastOrCandidateDevice();
        final String address = safe(device.optString("address", ""), MAX_ADDRESS_LEN);
        final String name = safe(device.optString("name", ""), MAX_NAME_LEN);
        activity.runOnUiThread(
                () -> {
                    if (address.isEmpty()) {
                        activity.publishStatus(
                                "blocked",
                                "No remembered adapter yet. Pick one and try Connect.",
                                true);
                        return;
                    }
                    activity.rememberDevice(address, name);
                    activity.startObdService(ObdService.ACTION_CONNECT, address, name);
                });
    }

    /**
     * Opens the system Bluetooth settings so the user can forget the adapter and re-pair. Delegated
     * to MainActivity (a startActivity call needs the Activity context).
     */
    @JavascriptInterface
    public void openBluetoothSettings() {
        activity.runOnUiThread(activity::openBluetoothSettingsFromBridge);
    }

    // === END Error & troubleshooter UX ==========================================================

    // ============================================================================================
    // === Status & proactive tools                                                             ===
    // ============================================================================================

    /**
     * Returns the last {@code n} session summary rows as a JSON array string, newest first. Reads
     * {@code files/obd-logs/sessions-summary.jsonl} via {@code SessionSummaryStore}.
     */
    @JavascriptInterface
    public String getRecentSessions(int n) {
        return activity.getRecentSessionsJson(n);
    }

    /**
     * Bundles recent session JSONLs + rolling app log + summary into a zip and launches the system
     * share sheet.
     */
    @JavascriptInterface
    public void shareDiagnostics() {
        activity.runOnUiThread(activity::shareDiagnosticsFromBridge);
    }

    /**
     * Runs a quick ATZ + 0100 + voltage probe against the last-known adapter, then disconnects.
     * Surfaces results via the normal status broadcast pipeline.
     */
    @JavascriptInterface
    public void startTestConnection() {
        activity.runOnUiThread(activity::startTestConnectionFromBridge);
    }

    /**
     * Schedules test-connection probes every 30s for {@code mins} minutes (clamped to [1, 30]);
     * posts a notification when the adapter first responds.
     */
    @JavascriptInterface
    public void scheduleAdapterReadyNotify(int mins) {
        final int clamped = Math.max(1, Math.min(30, mins));
        activity.runOnUiThread(() -> activity.scheduleAdapterReadyNotifyFromBridge(clamped));
    }

    /**
     * Cancels a running notify-when-ready schedule. JS calls this when the user unchecks the toggle
     * — without it the periodic probe keeps firing until its deadline regardless of UI state.
     */
    @JavascriptInterface
    public void cancelAdapterReadyNotify() {
        activity.runOnUiThread(activity::cancelAdapterReadyNotifyFromBridge);
    }

    // === END Status & proactive tools ============================================================
}
