package com.volttracker.obdpoc;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import androidx.core.content.ContextCompat;
import com.volttracker.obdpoc.data.ObdLocalStore;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONException;
import org.json.JSONObject;

public class MainActivity extends Activity {
    static final String TAG = "VoltTracker";
    private static final String PREFS = "volt_obd_prefs";

    /**
     * Whitelist of dashboard entry-point functions {@link #callDashboard} may invoke.
     *
     * <p>Keeping this closed-set + the {@link JSONObject#quote} of the payload makes
     * `evaluateJavascript` structurally incapable of running arbitrary JS even if a caller later
     * passes attacker-controlled input by mistake — the worst a malformed function name does is
     * trigger a no-op + warn log.
     */
    private static final Set<String> ALLOWED_DASHBOARD_FUNCTIONS =
            Set.of(
                    "updateTelemetry",
                    "setStatus",
                    "setDevices",
                    "setHistory",
                    "setStorage",
                    "setAppState");

    private WebView webView;
    private boolean pageReady;
    private SharedPreferences prefs;
    DeviceCatalog deviceCatalog;
    DataBackup dataBackup;
    BackupController backupController;
    PermissionGate permissionGate;
    ObdLocalStore localStore;

    /** Troubleshooter and proactive connection helpers extracted from this Activity. */
    TroubleshooterBridge troubleshooter;

    private JSONObject lastTelemetry = new JSONObject();
    private JSONObject lastStatus = new JSONObject();
    private JSONObject lastStorage = new JSONObject();
    // Off-UI-thread worker for heavy DB work (storage summary, clearAllData, etc).
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private final BroadcastReceiverGroup broadcastReceivers = new BroadcastReceiverGroup();
    private final AtomicBoolean storageSummaryInFlight = new AtomicBoolean(false);
    private final AtomicBoolean storageSummaryQueued = new AtomicBoolean(false);

    /** Submits {@code task} to the background executor used for heavy DB work. */
    void runOnBackground(Runnable task) {
        backgroundExecutor.execute(task);
    }

    private final BroadcastReceiver obdReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    String json = intent.getStringExtra(ObdService.EXTRA_JSON);
                    if (json == null) {
                        json = "{}";
                    }
                    if (ObdService.BROADCAST_TELEMETRY.equals(action)) {
                        lastTelemetry = MainActivityUtils.parseJson(json);
                        callDashboard("updateTelemetry", json);
                        publishAppState();
                    } else if (ObdService.BROADCAST_STATUS.equals(action)) {
                        lastStatus = MainActivityUtils.parseJson(json);
                        callDashboard("setStatus", json);
                        publishStorageSummary();
                        publishAppState();
                        // Observe the same status broadcast feeding the dashboard so the
                        // notify-when-ready schedule can wake the user as soon as the adapter
                        // responds, then tear itself down.
                        onAdapterStatusForReadyNotify(lastStatus);
                    }
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        deviceCatalog = new DeviceCatalog(this, prefs);
        dataBackup = new DataBackup(this);
        backupController = new BackupController(this, dataBackup, backgroundExecutor);
        permissionGate = new PermissionGate(this);
        localStore = new ObdLocalStore(this);
        troubleshooter = new TroubleshooterBridge(this);
        // Ensure the OBD notification channel exists before the adapter-ready
        // notification path (onAdapterStatusForReadyNotify) might try to post — the user can
        // enable notify-when-ready before ever starting a logging session, so we cannot rely
        // on ObdService.onCreate having run first to register the channel.
        ObdNotifications.ensureChannel(this);
        // Trim raw telemetry/location/event/PID rows older than the retention window
        // on every cold start. Cheap (indexed cutoff scan) and runs off the UI thread.
        backgroundExecutor.execute(
                () -> {
                    try {
                        int retentionDays =
                                prefs.getInt(
                                        "raw_retention_days",
                                        ObdLocalStore.DEFAULT_RAW_RETENTION_DAYS);
                        int pruned = localStore.pruneRawDataOlderThan(retentionDays);
                        if (pruned > 0) {
                            Log.i(
                                    TAG,
                                    "Pruned "
                                            + pruned
                                            + " raw rows older than "
                                            + retentionDays
                                            + " days");
                        }
                    } catch (RuntimeException ex) {
                        Log.w(TAG, "Retention prune failed; continuing without it", ex);
                    }
                });
        webView = new WebView(this);
        webView.setLayoutParams(
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(webView);

        WebViewBootstrap.configure(webView, new VoltBridge(this));

        permissionGate.ensureConnectPermissions();
    }

    void onDashboardReady() {
        if (pageReady) {
            return;
        }
        pageReady = true;
        publishDeviceList();
        publishStorageSummary();
        publishAppState();
        publishStatus("ready", "Pick a paired OBD adapter to start logging.", false);
    }

    boolean isDashboardReadyForTest() {
        return pageReady;
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(ObdService.BROADCAST_TELEMETRY);
        filter.addAction(ObdService.BROADCAST_STATUS);
        broadcastReceivers.register(this, obdReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        publishDeviceList();
        publishStorageSummary();
        publishAppState();
        reportAppVisibility(true);
    }

    @Override
    protected void onPause() {
        reportAppVisibility(false);
        super.onPause();
        broadcastReceivers.unregisterAll(this);
    }

    @Override
    protected void onDestroy() {
        backgroundExecutor.shutdownNow();
        // The periodic "notify when ready" tick and the test-connection
        // auto-stop both post Runnables to handlers inside TroubleshooterBridge. Drain them
        // here so they can't fire on a destroyed Activity context.
        if (troubleshooter != null) {
            troubleshooter.shutdown();
        }
        if (localStore != null) {
            localStore.close();
            localStore = null;
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PermissionGate.REQUEST_CODE) {
            publishDeviceList();
            if (deviceCatalog.hasBluetoothConnectPermission()) {
                publishStatus(
                        "ready", "Bluetooth permission granted. Pick a paired adapter.", false);
            } else {
                publishStatus(
                        "blocked",
                        "Bluetooth permission is required to talk to the OBD adapter.",
                        true);
            }
        }
    }

    void publishDeviceList() {
        callDashboard("setDevices", deviceCatalog.getBondedDevicesJson());
        callDashboard("setHistory", deviceCatalog.getDeviceHistoryJson());
    }

    void publishStatus(String state, String detail, boolean blocked) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("state", state);
            payload.put("detail", detail);
            payload.put("blocked", blocked);
            payload.put("bluetoothReady", isBluetoothReady());
            payload.put("lastAddress", deviceCatalog.lastAddress());
            payload.put("lastName", deviceCatalog.lastName());
        } catch (JSONException ignored) {
            // Values are local literals.
        }
        callDashboard("setStatus", payload.toString());
        lastStatus = payload;
        publishAppState();
    }

    private boolean isBluetoothReady() {
        BluetoothAdapter adapter = BluetoothAdapters.get(this);
        return adapter != null
                && adapter.isEnabled()
                && deviceCatalog.hasBluetoothConnectPermission();
    }

    @SuppressLint("MissingPermission")
    void startObdService(String action, String address, String name) {
        if (!permissionGate.ensureConnectPermissions()) {
            publishStatus("blocked", "Grant Bluetooth permission, then connect again.", true);
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapters.get(this);
        if (adapter == null) {
            publishStatus("blocked", "This phone does not report Bluetooth support.", true);
            return;
        }
        if (!adapter.isEnabled()) {
            publishStatus("blocked", "Turn on Bluetooth to connect to the OBD adapter.", true);
            try {
                startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            } catch (Exception ignored) {
                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            }
            return;
        }

        // Any pending test-connection auto-stop is now stale — the notify-when-ready schedule
        // posts an 8 s stopObdService callback per probe, and if it fires DURING the connect
        // we're about to start (manual user connect, or a re-issued probe), it would tear down
        // the new session. startTestConnection posts its own fresh stop right after calling
        // us, so clearing here is a no-op for that path.
        if (troubleshooter != null) {
            troubleshooter.clearPendingTestConnectionStop();
        }

        Intent service = new Intent(this, ObdService.class);
        service.setAction(action);
        if (address != null) {
            service.putExtra(ObdService.EXTRA_ADDRESS, address);
        }
        if (name != null) {
            service.putExtra(ObdService.EXTRA_NAME, name);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(service);
        } else {
            startService(service);
        }
    }

    void rememberDevice(String address, String name) {
        String cleanAddress = deviceCatalog.remember(address, name);
        if (cleanAddress.isEmpty()) {
            return;
        }
        String cleanName = name == null ? "" : name.trim();
        try {
            if (localStore != null) {
                localStore.recordAdapterSummary(
                        cleanAddress,
                        cleanName,
                        ObdLocalStore.MODE_OBD,
                        0L,
                        "remembered",
                        0,
                        "",
                        "Remembered adapter");
            }
        } catch (RuntimeException ex) {
            // Preferences remain the source of truth for adapter recall if DB storage fails.
            // Use redactAddress so device identifiers don't end up in logcat in clear text.
            Log.w(
                    TAG,
                    "recordAdapterSummary failed for "
                            + MainActivityUtils.redactAddress(cleanAddress),
                    ex);
        }
        publishDeviceList();
        publishStorageSummary();
        publishStatus(
                "ready",
                "Remembered " + (cleanName.isEmpty() ? cleanAddress : cleanName) + ".",
                false);
    }

    void stopObdService() {
        Intent service = new Intent(this, ObdService.class);
        service.setAction(ObdService.ACTION_DISCONNECT);
        startService(service);
    }

    // ===== Troubleshooter and connection-tool bridge helpers ==================
    // Implementations live in TroubleshooterBridge so this Activity no longer
    // carries the handler state, the constants, or the per-helper logic. The
    // VoltBridge-side API (method names + signatures) stays unchanged, so
    // VoltBridge's existing tests still call through these delegates verbatim.

    boolean forceStopPackageFromBridge(String packageName) {
        return troubleshooter.forceStopPackage(packageName);
    }

    void cancelRetryFromBridge() {
        troubleshooter.cancelRetry();
    }

    void openBluetoothSettingsFromBridge() {
        troubleshooter.openBluetoothSettings();
    }

    String getRecentSessionsJson(int n) {
        return troubleshooter.getRecentSessionsJson(n);
    }

    void shareDiagnosticsFromBridge() {
        troubleshooter.shareDiagnostics();
    }

    void startTestConnectionFromBridge() {
        troubleshooter.startTestConnection();
    }

    void scheduleAdapterReadyNotifyFromBridge(int mins) {
        troubleshooter.scheduleAdapterReadyNotify(mins);
    }

    void cancelAdapterReadyNotifyFromBridge() {
        troubleshooter.cancelAdapterReadyNotify();
    }

    void onAdapterStatusForReadyNotify(JSONObject status) {
        troubleshooter.onAdapterStatusForReadyNotify(status);
    }

    // ===== end bridge delegates ==============================================

    private void reportAppVisibility(boolean foreground) {
        Intent service = new Intent(this, ObdService.class);
        service.setAction(
                foreground ? ObdService.ACTION_APP_FOREGROUND : ObdService.ACTION_APP_BACKGROUND);
        try {
            startService(service);
        } catch (IllegalStateException ignored) {
            // Visibility is diagnostic only; do not interrupt the Activity lifecycle.
        }
    }

    /**
     * Invokes {@code window.VoltTrackerNative.<functionName>(<jsonPayload>)} on the WebView UI
     * thread.
     *
     * <p>The function name must be in {@link #ALLOWED_DASHBOARD_FUNCTIONS} — unknown names are
     * dropped with a warn log rather than executed. The JSON payload is passed as a quoted JS
     * string literal via {@link JSONObject#quote}, so the dashboard receives a single string
     * argument and parses it (matching the existing dashboard ABI). The combination makes it
     * structurally impossible for a caller to inject arbitrary JavaScript.
     *
     * <p>The WebView reference is captured at call time and re-checked inside the UI-thread
     * runnable: if {@link #onDestroy} ran (or replaced the view) in between, the deferred call is a
     * safe no-op rather than a {@code NullPointerException} on a destroyed view.
     */
    private void callDashboard(String functionName, String jsonPayload) {
        if (!ALLOWED_DASHBOARD_FUNCTIONS.contains(functionName)) {
            Log.w(TAG, "callDashboard: refused unknown function name: " + functionName);
            return;
        }
        final WebView wv = this.webView;
        if (!pageReady || wv == null) {
            return;
        }
        final String script =
                "window.VoltTrackerNative."
                        + functionName
                        + "("
                        + JSONObject.quote(jsonPayload == null ? "{}" : jsonPayload)
                        + ");";
        runOnUiThread(
                () -> {
                    // Re-check every tear-down signal inside the runnable. The identity check
                    // catches view replacement; the lifecycle checks catch Activity destruction
                    // where this.webView still points to the same instance (we don't null it in
                    // onDestroy). pageReady catches the brief window after Activity start but
                    // before the dashboard's JS-ready handshake. Without ALL of these,
                    // evaluateJavascript can fire against a torn-down WebView and crash on some
                    // Android builds.
                    if (isFinishing() || isDestroyed() || !pageReady || wv != this.webView) {
                        return;
                    }
                    wv.evaluateJavascript(script, null);
                });
    }

    void publishStorageSummary() {
        // getStorageSummary runs many queries over a large DB; keep it off the UI thread.
        if (!storageSummaryInFlight.compareAndSet(false, true)) {
            storageSummaryQueued.set(true);
            return;
        }
        runStorageSummaryRefresh();
    }

    private void runStorageSummaryRefresh() {
        backgroundExecutor.execute(
                () -> {
                    final String storage = getStorageSummaryJson();
                    runOnUiThread(
                            () -> {
                                lastStorage = MainActivityUtils.parseJson(storage);
                                callDashboard("setStorage", storage);
                            });
                    storageSummaryInFlight.set(false);
                    if (storageSummaryQueued.getAndSet(false)) {
                        publishStorageSummary();
                    }
                });
    }

    String getStorageSummaryJson() {
        if (localStore == null) {
            return MainActivityUtils.errorPayload(
                            "storage_unavailable", "Local storage is not ready yet.")
                    .toString();
        }
        try {
            return StorageSummaryJson.build(localStore.getStorageSummaryRecord()).toString();
        } catch (RuntimeException ex) {
            Log.w(TAG, "getStorageSummary failed", ex);
            return MainActivityUtils.errorPayload(
                            "storage_summary_failed", "Could not read local storage summary.")
                    .toString();
        }
    }

    String getTripsJson() {
        if (localStore == null) {
            return MainActivityUtils.errorPayload(
                            "storage_unavailable", "Local storage is not ready yet.")
                    .toString();
        }
        try {
            return localStore.getTripsJson(40).toString();
        } catch (RuntimeException ex) {
            Log.w(TAG, "getTripsJson failed", ex);
            return MainActivityUtils.errorPayload(
                            "trips_read_failed", "Could not read logged trips.")
                    .toString();
        }
    }

    String getInsightsJson() {
        if (localStore == null) {
            return MainActivityUtils.errorPayload(
                            "storage_unavailable", "Local storage is not ready yet.")
                    .toString();
        }
        try {
            return localStore.getInsightsJson().toString();
        } catch (RuntimeException ex) {
            Log.w(TAG, "getInsightsJson failed", ex);
            return MainActivityUtils.errorPayload(
                            "insights_read_failed", "Could not read vehicle insights.")
                    .toString();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        backupController.onActivityResult(requestCode, resultCode, data);
    }

    /** True while an OBD logging session is connecting or active. */
    boolean isLoggingActive() {
        return MainActivityUtils.isConnectedState(
                        lastStatus == null ? "" : lastStatus.optString("state", ""))
                || ObdService.hasActiveSession();
    }

    private void publishAppState() {
        callDashboard("setAppState", getAppStateJson());
    }

    String getAppStateJson() {
        return AppStateJson.build(
                appVersionName(),
                isBluetoothReady(),
                permissionGate.hasLocation(),
                permissionGate.hasNotifications(),
                deviceCatalog.lastAddress(),
                deviceCatalog.lastName(),
                lastTelemetry,
                lastStatus,
                lastStorage);
    }

    private String appVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ex) {
            return "";
        }
    }
}
