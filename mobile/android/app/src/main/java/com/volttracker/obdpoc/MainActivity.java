package com.volttracker.obdpoc;

import android.annotation.SuppressLint;
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
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.volttracker.obdpoc.data.ObdLocalStore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONException;
import org.json.JSONObject;

public class MainActivity extends ComponentActivity {
    static final String TAG = "VoltTracker";
    private static final String PREFS = "volt_obd_prefs";

    private WebView webView;
    private DashboardPublisher dashboardPublisher;
    private SharedPreferences prefs;
    DeviceCatalog deviceCatalog;
    DataBackup dataBackup;
    BackupController backupController;
    PermissionGate permissionGate;
    ObdLocalStore localStore;
    private ActivityResultLauncher<Intent> restoreFilePicker;

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
    // Throttle the per-status-tick storage-summary refresh. The summary runs many
    // queries over the whole DB (see ObdStoreReports/ObdStoreTrips), and a status
    // broadcast fires ~1Hz during a drive — but session/sample counts and DB size
    // are not live telemetry, so recomputing them at most every few seconds is
    // plenty. Transitions (resume, connect, page-ready) bypass this and refresh now.
    private static final long STORAGE_SUMMARY_MIN_INTERVAL_MS = 10_000L;
    private final AtomicLong lastStorageSummaryAtMs = new AtomicLong(0L);
    // Dirty flag for the storage summary. The summary only changes when the DB changes: a sample
    // row is written (telemetry broadcast), a session closes, the retention prune trims rows, or a
    // bridge action mutates the store (clear / remember / restore). During a long idle-connected
    // session the ~1Hz status path would otherwise recompute the full summary every interval even
    // though nothing in it moved. The throttled path skips the recompute while this is clean;
    // forced transition refreshes (page-ready, resume, connect) ignore it and always run.
    // Seeded dirty so the very first throttled tick after launch is allowed to publish once.
    private final AtomicBoolean storageSummaryDirty = new AtomicBoolean(true);

    /** Submits {@code task} to the background executor used for heavy DB work. */
    void runOnBackground(Runnable task) {
        submitBackground(task);
    }

    /**
     * Submits to {@link #backgroundExecutor}, tolerating a shutdown executor. onDestroy calls
     * {@code shutdownNow()}, but the WebView's {@code dashboardReady} handshake (and late status
     * broadcasts) can still arrive afterwards and route through {@link #publishStorageSummary()} —
     * which would otherwise throw {@link RejectedExecutionException} and crash the process. When
     * the executor is gone the Activity is tearing down and there is no UI left to refresh, so
     * dropping the task is the correct behaviour.
     */
    private void submitBackground(Runnable task) {
        try {
            backgroundExecutor.execute(task);
        } catch (RejectedExecutionException ex) {
            Log.d(
                    TAG,
                    "background task dropped; executor is shut down (activity tearing down)",
                    ex);
        }
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
                        // Each telemetry sample is persisted as a row by the service, so the
                        // storage summary (row/session counts, DB size) has moved. Mark it dirty
                        // so the next throttled status tick will recompute and publish it.
                        markStorageSummaryDirty();
                        callDashboard("updateTelemetry", json);
                        publishAppState();
                    } else if (ObdService.BROADCAST_STATUS.equals(action)) {
                        lastStatus = MainActivityUtils.parseJson(json);
                        callDashboard("setStatus", json);
                        if ("idle".equals(lastStatus.optString("state", ""))) {
                            // A session that just went idle has finalized its rows (session
                            // row, adapter history, materialized rollups) without any
                            // DB-write path marking the summary dirty. The throttled tick
                            // would skip the recompute on both the dirty gate and the time
                            // interval — and idle is often the last event before the user
                            // disconnects — leaving the storage panel stale. Force an
                            // immediate, non-throttled refresh on the session boundary.
                            publishStorageSummary();
                        } else {
                            publishStorageSummaryThrottled();
                        }
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
        restoreFilePicker =
                registerForActivityResult(
                        new ActivityResultContracts.StartActivityForResult(),
                        this::onRestoreFilePicked);
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
        submitBackground(
                () -> {
                    try {
                        int retentionDays =
                                prefs.getInt(
                                        "raw_retention_days",
                                        ObdLocalStore.DEFAULT_RAW_RETENTION_DAYS);
                        int pruned = localStore.pruneRawDataOlderThan(retentionDays);
                        if (pruned > 0) {
                            // Prune deleted rows — the summary's counts/size moved.
                            markStorageSummaryDirty();
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

        // Apps targeting SDK 35+ are forced edge-to-edge on Android 15+, so the
        // WebView draws under the status and navigation/gesture bars. The WebView's
        // CSS env(safe-area-inset-*) only reflects display cutouts — NOT the
        // gesture bar — so the fixed bottom-nav would sit inside the system
        // gesture zone, where taps are swallowed by the OS (you can still scroll
        // the content above it). Inset the WebView by the system bars so the whole
        // dashboard, including the bottom-nav, stays in the tappable area.
        ViewCompat.setOnApplyWindowInsetsListener(
                webView,
                (view, windowInsets) -> {
                    Insets bars =
                            windowInsets.getInsets(
                                    WindowInsetsCompat.Type.systemBars()
                                            | WindowInsetsCompat.Type.displayCutout());
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                    return windowInsets;
                });
        ViewCompat.requestApplyInsets(webView);

        dashboardPublisher =
                new DashboardPublisher(
                        webView, () -> !isFinishing() && !isDestroyed(), this::runOnUiThread);
        WebViewBootstrap.configure(webView, new VoltBridge(this));

        // Android shows dangerous permissions only at runtime; ask on first launch so GPS-backed
        // route logging is enabled before the user starts a drive.
        permissionGate.ensureGranted();
    }

    void onDashboardReady() {
        // Distinctive, greppable proof that the dashboard's JS chain executed and called back into
        // native — the emulator smoke test asserts this line appears. If the script chain is dead
        // (e.g. the file:// ES-module regression), this handshake never fires and the smoke fails.
        Log.i(TAG, "dashboard handshake received: JS is live");
        if (dashboardPublisher.isPageReady()) {
            return;
        }
        dashboardPublisher.setPageReady(true);
        publishDeviceList();
        publishStorageSummary();
        publishAppState();
        publishStatus("ready", "Pick a paired OBD adapter to start logging.", false);
    }

    boolean isDashboardReadyForTest() {
        return dashboardPublisher != null && dashboardPublisher.isPageReady();
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
            if (!permissionGate.hasBluetoothConnect()) {
                publishStatus(
                        "blocked",
                        "Bluetooth permission is required to talk to the OBD adapter.",
                        true);
            } else if (!permissionGate.hasLocation()) {
                publishStatus(
                        "ready",
                        "Bluetooth permission granted. Location is still off, so trips may not"
                                + " show a route.",
                        false);
            } else if (!permissionGate.hasNotifications()) {
                publishStatus(
                        "ready",
                        "Bluetooth permission granted. Notifications are still off, so background"
                                + " logging may be quieter.",
                        false);
            } else {
                publishStatus(
                        "ready", "Bluetooth permission granted. Pick a paired adapter.", false);
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
        startObdService(action, address, name, null);
    }

    @SuppressLint("MissingPermission")
    void startObdService(String action, String address, String name, String detailStage) {
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
                try {
                    startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                } catch (Exception settingsIgnored) {
                    publishStatus(
                            "blocked", "Open Android Bluetooth settings, then try again.", true);
                }
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
        if (detailStage != null) {
            service.putExtra(ObdService.EXTRA_DETAIL_STAGE, detailStage);
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
        try {
            startService(service);
        } catch (IllegalStateException ignored) {
            publishStatus(
                    "ready",
                    "Stop request noted; reopen the app if logging is still active.",
                    false);
        }
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
     * Invokes {@code window.VoltTrackerNative.<functionName>(<jsonPayload>)} on the dashboard.
     *
     * <p>Delegates to {@link DashboardPublisher#publish}, which enforces the function-name
     * allowlist, JSON-quotes the payload (so injection is structurally impossible), and gates
     * dispatch on page-ready + host-liveness on the UI thread.
     */
    private void callDashboard(String functionName, String jsonPayload) {
        if (dashboardPublisher != null) {
            dashboardPublisher.publish(functionName, jsonPayload);
        }
    }

    /**
     * Refresh the storage summary, but at most once per {@link #STORAGE_SUMMARY_MIN_INTERVAL_MS}.
     * Used by the ~1Hz status-broadcast path; on a skip the next tick past the interval (or any
     * forced transition) brings it current. Eventual consistency within the interval is fine — this
     * is session/sample counts and DB size, not live telemetry.
     */
    void publishStorageSummaryThrottled() {
        long now = System.currentTimeMillis();
        if (now - lastStorageSummaryAtMs.get() < STORAGE_SUMMARY_MIN_INTERVAL_MS) {
            return;
        }
        // The summary only changes when the DB changes. If nothing has marked it dirty since the
        // last refresh, the recompute would return identical numbers — skip the whole query batch.
        if (!storageSummaryDirty.get()) {
            return;
        }
        publishStorageSummary();
    }

    /**
     * Flags the storage summary as stale so the next throttled refresh recomputes it. Called from
     * the DB-mutating paths (telemetry row writes, prune, clear/remember/restore). Forced
     * transition refreshes recompute regardless; this only governs the throttled status-tick path.
     */
    void markStorageSummaryDirty() {
        storageSummaryDirty.set(true);
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
        submitBackground(
                () -> {
                    // Clear before reading so a DB write that lands while this query runs re-marks
                    // the summary dirty and is picked up by the next refresh, rather than being
                    // lost.
                    storageSummaryDirty.set(false);
                    final String storage = getStorageSummaryJson();
                    // getStorageSummaryJson swallows a query failure and returns an error
                    // payload; since we already cleared the dirty flag, a transient failure
                    // would otherwise never be retried by the throttled path until another
                    // write re-marks it. Re-mark dirty on failure so the next tick retries.
                    if (!MainActivityUtils.parseJson(storage).optBoolean("ok", true)) {
                        storageSummaryDirty.set(true);
                    }
                    lastStorageSummaryAtMs.set(System.currentTimeMillis());
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

    String getTripRouteJson(long sessionId) {
        return getTripRouteJson(String.valueOf(sessionId));
    }

    String getTripRouteJson(String routeKey) {
        if (localStore == null) {
            return MainActivityUtils.errorPayload(
                            "storage_unavailable", "Local storage is not ready yet.")
                    .toString();
        }
        try {
            return localStore.getTripRouteJson(routeKey).toString();
        } catch (RuntimeException ex) {
            Log.w(TAG, "getTripRouteJson failed", ex);
            return MainActivityUtils.errorPayload(
                            "trip_route_read_failed", "Could not read the trip route.")
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

    void launchRestoreFilePicker(Intent intent) {
        restoreFilePicker.launch(intent);
    }

    private void onRestoreFilePicked(ActivityResult result) {
        backupController.onRestorePickerResult(result.getResultCode(), result.getData());
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
