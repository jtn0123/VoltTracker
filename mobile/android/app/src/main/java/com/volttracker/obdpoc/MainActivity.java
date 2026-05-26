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
    private JSONObject lastTelemetry = new JSONObject();
    private JSONObject lastStatus = new JSONObject();
    private JSONObject lastStorage = new JSONObject();
    // Off-UI-thread worker for heavy DB work (storage summary, clearAllData, etc).
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private final BroadcastReceiverGroup broadcastReceivers = new BroadcastReceiverGroup();

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
                        // Bucket 4b (C10): observe the same status broadcast feeding the
                        // dashboard so the notify-when-ready schedule can wake the user as
                        // soon as the adapter responds, then tear itself down.
                        onAdapterStatusForReadyNotify(lastStatus.optString("state", ""));
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
        // Bucket 4b: ensure the OBD notification channel exists before the adapter-ready
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

        WebViewBootstrap.configure(webView, new VoltBridge(this), this::onDashboardReady);

        permissionGate.ensureGranted();
    }

    private void onDashboardReady() {
        pageReady = true;
        publishDeviceList();
        publishStorageSummary();
        publishAppState();
        publishStatus("ready", "Pick a paired OBD adapter to start logging.", false);
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
        // Bucket 4b (C10): the periodic "notify when ready" tick and the test-connection
        // auto-stop both posted Runnables to handlers on this Activity. If we don't drain them
        // before destruction, they can fire on a destroyed Activity context (NPE on localStore /
        // deviceCatalog) and queue more callbacks indefinitely. Drain both queues defensively.
        if (adapterReadyHandler != null) {
            adapterReadyHandler.removeCallbacksAndMessages(null);
        }
        if (testConnectionStopHandler != null) {
            testConnectionStopHandler.removeCallbacksAndMessages(null);
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
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        return adapter != null
                && adapter.isEnabled()
                && deviceCatalog.hasBluetoothConnectPermission();
    }

    @SuppressLint("MissingPermission")
    void startObdService(String action, String address, String name) {
        if (!permissionGate.ensureGranted()) {
            publishStatus("blocked", "Grant Bluetooth permission, then connect again.", true);
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
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

        // Bucket 4b: any pending test-connection auto-stop is now stale — the C10 schedule
        // posts an 8 s stopObdService callback per probe, and if it fires DURING the connect
        // we're about to start (manual user connect, or a re-issued probe), it would tear down
        // the new session. startTestConnectionFromBridge posts its own fresh stop right after
        // calling us, so clearing here is a no-op for that path.
        if (testConnectionStopHandler != null) {
            testConnectionStopHandler.removeCallbacksAndMessages(null);
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

    // ===== Bucket 4a bridge helpers ==========================================
    // Each helper is invoked from VoltBridge's BUCKET 4a region. Kept on the
    // Activity (and not on ObdService) so they have an Activity context for
    // startActivity / system service lookups without binding to the service.

    /**
     * Bucket 4a (C4) — force-stop a competing app surfaced via the `competingApps` status field.
     * Uses {@link android.app.ActivityManager#killBackgroundProcesses(String)} — Android only acts
     * on background-promotable apps but it is the right API to expose. Returns true if the package
     * is installed (so the UI can surface the request honestly).
     */
    boolean forceStopPackageFromBridge(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return false;
        }
        try {
            // Confirm the package exists on this device before issuing the
            // kill — surfaces "false" honestly when the user has uninstalled
            // the competing app since the status payload was generated.
            getPackageManager().getPackageInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException notInstalled) {
            return false;
        }
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am == null) {
                return false;
            }
            am.killBackgroundProcesses(packageName);
            Log.i(TAG, "forceStopPackage: requested kill of " + packageName);
            return true;
        } catch (SecurityException ex) {
            Log.w(TAG, "forceStopPackage denied for " + packageName, ex);
            return false;
        }
    }

    /**
     * Bucket 4a (C6) — forward a cancel request from the dashboard to the running ObdService so its
     * polling engine can bail out of the retry burst. No-op if the service is not bound / running.
     */
    void cancelRetryFromBridge() {
        // Send via Intent so we don't need a service binding; the service
        // exposes a public flag setter that the engine reads.
        Intent service = new Intent(this, ObdService.class);
        service.setAction(ObdService.ACTION_CANCEL_RETRY);
        try {
            startService(service);
        } catch (IllegalStateException ignored) {
            // Service may have stopped between the JS click and the dispatch.
        }
    }

    /**
     * Bucket 4a (A8) — open Android Bluetooth settings so the user can forget the adapter and
     * re-pair. Falls back to a status broadcast if the activity is unavailable on this device.
     */
    void openBluetoothSettingsFromBridge() {
        try {
            Intent intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception ex) {
            Log.w(TAG, "openBluetoothSettings failed", ex);
            publishStatus(
                    "blocked",
                    "Could not open Bluetooth settings. Open Android Settings > Bluetooth manually.",
                    true);
        }
    }

    // ===== end Bucket 4a bridge helpers ======================================

    // ===== Bucket 4b bridge helpers ==========================================

    /**
     * Bucket 4b (C2, C8) — return the last {@code n} session summaries as a JSON array string,
     * newest first. Reads Bucket 3's {@link SessionSummaryStore}; returns {@code "[]"} on any
     * failure so the dashboard's last-connected badge / health pill code paths stay dormant instead
     * of crashing.
     */
    String getRecentSessionsJson(int n) {
        try {
            if (n <= 0) {
                return "[]";
            }
            SessionSummaryStore store = SessionSummaryStore.getInstance(getFilesDir());
            java.util.List<SessionSummary> recent = store.getRecent(n);
            org.json.JSONArray arr = new org.json.JSONArray();
            for (SessionSummary s : recent) {
                arr.put(s.toJson());
            }
            return arr.toString();
        } catch (RuntimeException ex) {
            Log.w(TAG, "getRecentSessionsJson failed", ex);
            return "[]";
        }
    }

    /**
     * Bucket 4b (C7) — bundle recent diagnostics into a zip via Bucket 3's helper and launch the
     * system share sheet. Errors surface as a status broadcast rather than throwing.
     */
    void shareDiagnosticsFromBridge() {
        try {
            Intent share = DiagnosticsShareIntent.buildIntent(this);
            if (share == null) {
                publishStatus(
                        "blocked",
                        "No diagnostics to share yet — connect once and try again.",
                        true);
                return;
            }
            startActivity(Intent.createChooser(share, "Share diagnostics"));
        } catch (RuntimeException ex) {
            Log.w(TAG, "shareDiagnostics failed", ex);
            publishStatus("blocked", "Could not build the diagnostics share.", true);
        }
    }

    /**
     * Bucket 4b (C5) — start a one-shot probe session against the last-known adapter and schedule a
     * stop after {@link #TEST_CONNECTION_DURATION_MS} so we exercise the connect/init path without
     * committing to a full logging session.
     */
    void startTestConnectionFromBridge() {
        JSONObject device = deviceCatalog.getLastOrCandidateDevice();
        final String address = device.optString("address", "");
        final String name = device.optString("name", "Test connection");
        if (address.isEmpty()) {
            publishStatus(
                    "blocked",
                    "No remembered adapter yet — pick one and Connect once first.",
                    true);
            return;
        }
        rememberDevice(address, name);
        // Gate for onAdapterStatusForReadyNotify: the very next "connected" broadcast (within
        // a reasonable window) belongs to this probe, so the schedule should fire its
        // notification. Cleared by the auto-stop callback below or by an explicit cancel.
        probeInFlight = true;
        startObdService(ObdService.ACTION_CONNECT, address, name);
        // Defer the stop onto the main looper so the connect kicks off first. Use a single
        // long-lived Handler and clear any prior pending stop — without this, the C10 periodic
        // tick (every 30 s) would accumulate one independent stop Runnable per probe with no way
        // to cancel them, so a manual full-session connect started between probes could be torn
        // down 8 s after the next tick.
        if (testConnectionStopHandler == null) {
            testConnectionStopHandler = new android.os.Handler(getMainLooper());
        }
        testConnectionStopHandler.removeCallbacksAndMessages(null);
        testConnectionStopHandler.postDelayed(
                () -> {
                    probeInFlight = false;
                    stopObdService();
                },
                TEST_CONNECTION_DURATION_MS);
    }

    /**
     * Bucket 4b (C10) — periodic test-connection probe for {@code mins} minutes; the first status
     * broadcast with {@code state == "connected"} during the window posts a notification and
     * cancels the rest. {@code mins} is already clamped to [1, 30] by {@link
     * VoltBridge#scheduleAdapterReadyNotify(int)}.
     */
    void scheduleAdapterReadyNotifyFromBridge(int mins) {
        adapterReadyDeadlineMs = System.currentTimeMillis() + mins * 60_000L;
        adapterReadyActive = true;
        if (adapterReadyHandler == null) {
            adapterReadyHandler = new android.os.Handler(getMainLooper());
        }
        // Reset any prior schedule and start fresh.
        adapterReadyHandler.removeCallbacksAndMessages(null);
        adapterReadyHandler.post(adapterReadyTick);
    }

    /**
     * Bucket 4b (C10) — cancel a running notify-when-ready schedule. Called from the bridge when
     * the user unchecks the toggle, and internally when the first successful probe lands or the
     * deadline expires.
     */
    void cancelAdapterReadyNotifyFromBridge() {
        adapterReadyActive = false;
        adapterReadyDeadlineMs = 0L;
        // Clear the probe-in-flight gate so a stale "connected" broadcast after a re-enable
        // doesn't mis-fire the notification on a non-probe session.
        probeInFlight = false;
        if (adapterReadyHandler != null) {
            adapterReadyHandler.removeCallbacksAndMessages(null);
        }
    }

    /**
     * Bucket 4b (C10) — called from the status-broadcast receiver. Fires the user-facing "adapter
     * is ready" notification when a probe started by the notify-when-ready schedule reports {@code
     * state == "connected"}, then tears down the schedule. Gated on {@link #probeInFlight} so a
     * "connected" broadcast for an unrelated session (e.g. the user manually started one while the
     * schedule happened to be active) does not fire the notification.
     */
    void onAdapterStatusForReadyNotify(String state) {
        if (!adapterReadyActive || !probeInFlight) {
            return;
        }
        if (!"connected".equals(state)) {
            return;
        }
        // On API 33+ this notification needs runtime POST_NOTIFICATIONS permission. We don't
        // demand it eagerly — if the user denied it, the schedule still works (it just won't
        // notify), and the next time they enter the app the UI shows the connected state.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(
                                this, android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "adapter-ready: POST_NOTIFICATIONS not granted, skipping notification");
            cancelAdapterReadyNotifyFromBridge();
            return;
        }
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                // Tap → open MainActivity. FLAG_IMMUTABLE is required on API 31+; we set it
                // unconditionally to keep the PendingIntent simple across versions.
                Intent open = new Intent(this, MainActivity.class);
                open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                android.app.PendingIntent tap =
                        android.app.PendingIntent.getActivity(
                                this,
                                0,
                                open,
                                android.app.PendingIntent.FLAG_UPDATE_CURRENT
                                        | android.app.PendingIntent.FLAG_IMMUTABLE);
                // The (Context, channelId) Builder constructor is API 26+; the channel-less form
                // is the only legal call on minSdk=23 devices. Mirror the same gate as
                // ObdNotifications.build(...) so this notification path doesn't crash on KitKat
                // -era Androids that we still support.
                android.app.Notification.Builder b =
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                                ? new android.app.Notification.Builder(
                                        this, ObdNotifications.CHANNEL_ID)
                                : new android.app.Notification.Builder(this);
                android.app.Notification n =
                        b.setContentTitle("OBD adapter is responding")
                                .setContentText("Tap to open VoltTracker and start logging.")
                                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                                .setContentIntent(tap)
                                .setAutoCancel(true)
                                .build();
                nm.notify(ADAPTER_READY_NOTIFICATION_ID, n);
            }
        } catch (RuntimeException ex) {
            Log.w(TAG, "adapter-ready notification failed", ex);
        }
        cancelAdapterReadyNotifyFromBridge();
    }

    private static final long TEST_CONNECTION_DURATION_MS = 8_000L;
    private static final long ADAPTER_READY_INTERVAL_MS = 30_000L;
    private static final int ADAPTER_READY_NOTIFICATION_ID = 4711;
    private long adapterReadyDeadlineMs = 0L;
    private volatile boolean adapterReadyActive = false;

    /**
     * Distinguishes a probe-driven "connected" status from a user-initiated session that just
     * happens to be running while the C10 schedule is active. Set in {@link
     * #startTestConnectionFromBridge}, cleared by the auto-stop callback or {@link
     * #cancelAdapterReadyNotifyFromBridge}.
     */
    private volatile boolean probeInFlight = false;

    private android.os.Handler adapterReadyHandler;
    private android.os.Handler testConnectionStopHandler;
    private final Runnable adapterReadyTick =
            new Runnable() {
                @Override
                public void run() {
                    if (!adapterReadyActive
                            || System.currentTimeMillis() >= adapterReadyDeadlineMs) {
                        adapterReadyActive = false;
                        return;
                    }
                    startTestConnectionFromBridge();
                    if (adapterReadyHandler != null && adapterReadyActive) {
                        adapterReadyHandler.postDelayed(this, ADAPTER_READY_INTERVAL_MS);
                    }
                }
            };

    // ===== end Bucket 4b bridge helpers ======================================

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
                    // before the WebView's onPageFinished. Without ALL of these, evaluateJavascript
                    // can fire against a torn-down WebView and crash on some Android builds.
                    if (isFinishing() || isDestroyed() || !pageReady || wv != this.webView) {
                        return;
                    }
                    wv.evaluateJavascript(script, null);
                });
    }

    void publishStorageSummary() {
        // getStorageSummary runs many queries over a large DB; keep it off the UI thread.
        backgroundExecutor.execute(
                () -> {
                    final String storage = getStorageSummaryJson();
                    runOnUiThread(
                            () -> {
                                lastStorage = MainActivityUtils.parseJson(storage);
                                callDashboard("setStorage", storage);
                            });
                });
    }

    String getStorageSummaryJson() {
        if (localStore == null) {
            return "{}";
        }
        try {
            return localStore.getStorageSummary().toString();
        } catch (RuntimeException ex) {
            Log.w(TAG, "getStorageSummary failed; returning empty payload", ex);
            return "{}";
        }
    }

    String getTripsJson() {
        if (localStore == null) {
            return "[]";
        }
        try {
            return localStore.getTripsJson(40).toString();
        } catch (RuntimeException ex) {
            Log.w(TAG, "getTripsJson failed; returning empty list", ex);
            return "[]";
        }
    }

    String getInsightsJson() {
        if (localStore == null) {
            return "{}";
        }
        try {
            return localStore.getInsightsJson().toString();
        } catch (RuntimeException ex) {
            Log.w(TAG, "getInsightsJson failed; returning empty payload", ex);
            return "{}";
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
                lastStatus == null ? "" : lastStatus.optString("state", ""));
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
