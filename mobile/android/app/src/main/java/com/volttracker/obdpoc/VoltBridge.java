package com.volttracker.obdpoc;

import android.util.Log;
import android.webkit.JavascriptInterface;
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

    private final MainActivity activity;

    VoltBridge(MainActivity activity) {
        this.activity = activity;
    }

    /** Null-coalesces, trims, and bounds a bridge string argument. */
    private static String safe(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLen ? trimmed : trimmed.substring(0, maxLen);
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
    public void restoreBackup() {
        activity.runOnUiThread(activity.backupController::launchRestorePicker);
    }

    @JavascriptInterface
    public String getTrips() {
        return activity.getTripsJson();
    }

    @JavascriptInterface
    public String getInsights() {
        return activity.getInsightsJson();
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
        Log.e(
                MainActivity.TAG,
                "dashboard client error ["
                        + safe(label, MAX_LABEL_LEN)
                        + "]: "
                        + safe(detail, MAX_DETAIL_LEN));
    }
}
