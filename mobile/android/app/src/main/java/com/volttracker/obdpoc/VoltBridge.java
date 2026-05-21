package com.volttracker.obdpoc;

import android.util.Log;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

/**
 * The {@code @JavascriptInterface} surface the dashboard WebView calls into. Each method
 * is a thin adapter that marshals onto the UI thread and delegates to {@link MainActivity}
 * and its helper objects. Extracted to its own file so the bridge API is isolated from
 * the Activity lifecycle code.
 */
public final class VoltBridge {

    private final MainActivity activity;

    VoltBridge(MainActivity activity) {
        this.activity = activity;
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
        activity.runOnUiThread(() -> {
            activity.publishDeviceList();
            activity.publishStorageSummary();
        });
    }

    @JavascriptInterface
    public void connect(String address, String name) {
        activity.runOnUiThread(() -> {
            activity.rememberDevice(address, name);
            activity.startObdService(ObdService.ACTION_CONNECT, address, name);
        });
    }

    @JavascriptInterface
    public void scan(String address, String name) {
        activity.runOnUiThread(() -> {
            activity.rememberDevice(address, name);
            activity.startObdService(ObdService.ACTION_SCAN, address, name);
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
        activity.runOnUiThread(() -> {
            try {
                if (activity.localStore != null) {
                    activity.localStore.clearAllData();
                }
            } catch (RuntimeException ignored) {
                activity.publishStatus("blocked", "Could not clear the local OBD database.", true);
                return;
            }
            activity.publishStorageSummary();
            activity.publishStatus("ready", "On-phone OBD database cleared.", false);
        });
    }

    @JavascriptInterface
    public void rememberDevice(String address, String name) {
        activity.runOnUiThread(() -> activity.rememberDevice(address, name));
    }

    @JavascriptInterface
    public void connectLast() {
        JSONObject device = activity.deviceCatalog.getLastOrCandidateDevice();
        String address = device.optString("address", "");
        String name = device.optString("name", "");
        activity.runOnUiThread(() -> {
            if (address == null || address.trim().isEmpty()) {
                activity.publishStatus("blocked", "No remembered adapter yet. Connect once to save it.", true);
                return;
            }
            activity.rememberDevice(address, name);
            activity.startObdService(ObdService.ACTION_CONNECT, address, name);
        });
    }

    @JavascriptInterface
    public void scanLast() {
        JSONObject device = activity.deviceCatalog.getLastOrCandidateDevice();
        String address = device.optString("address", "");
        String name = device.optString("name", "");
        activity.runOnUiThread(() -> {
            if (address == null || address.trim().isEmpty()) {
                activity.publishStatus("blocked", "No remembered adapter yet. Connect once to save it.", true);
                return;
            }
            activity.rememberDevice(address, name);
            activity.startObdService(ObdService.ACTION_SCAN, address, name);
        });
    }

    @JavascriptInterface
    public void demo() {
        activity.runOnUiThread(() -> activity.startObdService(ObdService.ACTION_DEMO, null, "Demo stream"));
    }

    @JavascriptInterface
    public void disconnect() {
        activity.runOnUiThread(activity::stopObdService);
    }

    @JavascriptInterface
    public void logClientError(String label, String detail) {
        Log.e(MainActivity.TAG, "dashboard client error [" + label + "]: " + detail);
    }
}
