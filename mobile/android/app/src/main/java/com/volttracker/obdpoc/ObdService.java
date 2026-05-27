package com.volttracker.obdpoc;

import android.Manifest;
import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.ServiceCompat;
import com.volttracker.obdpoc.data.ObdLocalStore;
import com.volttracker.obdpoc.location.LocationManagerTracker;
import com.volttracker.obdpoc.location.LocationTracker;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Foreground service that owns an OBD logging session: Android lifecycle, the session start/stop,
 * the foreground notification, GPS tracking, and status broadcasts to the dashboard. The adapter IO
 * runs in {@link ObdPollingEngine}; the per-session record (field log + database) lives in {@link
 * SessionRecorder}.
 */
public class ObdService extends Service {
    public static final String ACTION_CONNECT = "com.volttracker.obdpoc.action.CONNECT";
    public static final String ACTION_SCAN = "com.volttracker.obdpoc.action.SCAN";
    public static final String ACTION_DEMO = "com.volttracker.obdpoc.action.DEMO";
    public static final String ACTION_DISCONNECT = "com.volttracker.obdpoc.action.DISCONNECT";
    public static final String ACTION_APP_FOREGROUND =
            "com.volttracker.obdpoc.action.APP_FOREGROUND";
    public static final String ACTION_APP_BACKGROUND =
            "com.volttracker.obdpoc.action.APP_BACKGROUND";
    // Bucket 4a (C6) — VoltBridge.cancelRetry() forwards through MainActivity
    // as this intent so the service can flip {@link #cancelRetryRequested}
    // without needing a bound-service handle on the Activity side.
    public static final String ACTION_CANCEL_RETRY = "com.volttracker.obdpoc.action.CANCEL_RETRY";
    public static final String BROADCAST_TELEMETRY = "com.volttracker.obdpoc.broadcast.TELEMETRY";
    public static final String BROADCAST_STATUS = "com.volttracker.obdpoc.broadcast.STATUS";
    public static final String EXTRA_ADDRESS = "address";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_JSON = "json";
    private static final AtomicBoolean SESSION_ACTIVE = new AtomicBoolean(false);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService competingAppExecutor = Executors.newSingleThreadExecutor();
    // Shared IO monitor: serializes session teardown against in-flight command IO.
    final Object ioLock = new Object();
    final AtomicBoolean running = new AtomicBoolean(false);
    private Future<?> activeTask;
    // Package-private so DiagnosticScanRunner can persist VIN-derived vehicle rows by
    // calling recorder.runAsync(() -> localStore.upsertVehicleFromVin(...)) — the runAsync
    // dispatch keeps the SQLite write off the OBD IO thread and onto the recording executor.
    // All other callers go through SessionRecorder's dedicated write methods.
    ObdLocalStore localStore;
    SessionRecorder recorder;
    private ObdPollingEngine engine;
    private ObdNotifications notifications;
    LocationTracker locationTracker;
    // Bucket 2 (Bluetooth observability). Public-package access lets ObdPollingEngine call the
    // single pre-flight hook via service.bluetoothObservability.onPreConnect(address). The
    // companion helpers (SDP, competing apps, voltage) hang off the same field cluster so all
    // BT-observability state lives in one place on the service.
    BluetoothStateReporter bluetoothObservability;
    SdpProbe sdpProbe;
    CompetingAppDetector competingAppDetector;
    VoltageProbe voltageProbe;
    String activeName = "OBD adapter";
    long sessionStartedAtMs;
    private String lastSessionState = "";
    private String lastSessionDetail = "";
    volatile boolean appInForeground = true;
    volatile boolean foregroundServiceActive;
    // Bucket 4a (C6) — Bucket 1's ObdPollingEngine reads this between connect
    // attempts and bails out of the retry burst when true. Cleared at the
    // start of each new connect/scan session so a stale request from a prior
    // burst can't suppress a fresh user-initiated retry.
    public volatile boolean cancelRetryRequested = false;

    /**
     * Bucket 4a (C6) entry-point used by VoltBridge.cancelRetry(). Bucket 1's engine reads {@link
     * #cancelRetryRequested} on its retry loop.
     */
    public void requestCancelRetry() {
        cancelRetryRequested = true;
    }

    static boolean hasActiveSession() {
        return SESSION_ACTIVE.get();
    }

    void markSessionInactive() {
        running.set(false);
        SESSION_ACTIVE.set(false);
    }

    // === SHARED CONTRACT (prep commit): per-session signals merged into every status broadcast.
    // Bucket 1 writes lastFailureClass via setLastFailureClass / clears via clearLastFailureClass;
    // Bucket 2 writes lastVoltage and competingAppsCsv. The dashboard (Buckets 4a/4b) reads them
    // off the auto-merged status payload. Keeping them on the service means we don't thread them
    // through every broadcastStatus() call site. Volatile so the engine-thread write is visible
    // to the broadcast-thread read without needing the ioLock.
    /** Last classified failure (Bucket 1). Cleared when a session ends or a connect succeeds. */
    private volatile FailureClass lastFailureClass;

    /** Most recent control-module voltage in volts (Bucket 2's voltage probe). Null until known. */
    private volatile Double lastVoltage;

    /** Other installed BT-serial apps detected this session (Bucket 2). Comma-joined for JSON. */
    private volatile String competingAppsCsv;

    // setLastFailureClass / clearLastFailureClass / lastFailureClass() are defined below near the
    // engine hook helpers.

    void setLastVoltage(double volts) {
        this.lastVoltage = volts;
    }

    void setCompetingApps(String csv) {
        this.competingAppsCsv = csv;
    }

    /** Read-side accessors so the bridge / tests can inspect current state. */
    Double getLastVoltage() {
        return lastVoltage;
    }

    String getCompetingAppsCsv() {
        return competingAppsCsv;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        localStore = new ObdLocalStore(this);
        locationTracker = new LocationManagerTracker(this);
        notifications = new ObdNotifications(this);
        notifications.createChannel();
        // Bucket 3: rolling app log + summary store wired in alongside the per-session JSONL.
        // The summary store is read by the JS bridge for the dashboard's last-connected pill;
        // OBDLog.mirror tees every event/warn/error into the rolling log so the diagnostics
        // share zip carries cross-session context for a future diagnosis.
        RollingAppLog rollingAppLog = new RollingAppLog(new File(getFilesDir(), "app-log"));
        OBDLog.mirror(rollingAppLog);
        SessionSummaryStore summaryStore = SessionSummaryStore.getInstance(getFilesDir());
        recorder =
                new SessionRecorder(
                        ioLock,
                        new ObdSessionLog(new File(getFilesDir(), "obd-logs")),
                        localStore,
                        summaryStore,
                        () -> SystemSnapshot.collect(this, summaryStore));
        engine = new ObdPollingEngine(this);
        // Bucket 2: instantiate observability helpers and start listening for OS-side BT events.
        // The detector pass happens here too — competingApps surfaces on the very first status
        // broadcast even if the user never tries to connect.
        sdpProbe = new SdpProbe(this);
        bluetoothObservability = new BluetoothStateReporter(this, sdpProbe);
        voltageProbe = new VoltageProbe(this);
        competingAppDetector =
                new CompetingAppDetector(getPackageManager(), this, recorder, getPackageName());
        if (hasBluetoothConnectPermission()) {
            bluetoothObservability.register(this);
        } else {
            recorder.logEvent("bluetooth_reporter_skipped", "reason", "missing_bluetooth_connect");
        }
        refreshCompetingAppsAsync();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_DISCONNECT.equals(action)) {
            stopCurrentSession("Disconnected.");
            // ServiceCompat dispatches to the int overload on API 24+ and to the deprecated
            // boolean overload on minSdk=23 — without it lint fails with NewApi on the int call.
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
            foregroundServiceActive = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_APP_FOREGROUND.equals(action)) {
            recordAppVisibility(true);
            if (!running.get()) {
                stopSelf(startId);
            }
            return running.get() ? START_STICKY : START_NOT_STICKY;
        }
        if (ACTION_APP_BACKGROUND.equals(action)) {
            recordAppVisibility(false);
            if (!running.get()) {
                stopSelf(startId);
            }
            return running.get() ? START_STICKY : START_NOT_STICKY;
        }
        if (ACTION_CANCEL_RETRY.equals(action)) {
            // Bucket 4a (C6) — flip the flag the engine reads between
            // connect attempts. If no session is running there's nothing to
            // cancel, but we still acknowledge with a status broadcast so
            // the dashboard hides the in-flight cancel button.
            requestCancelRetry();
            broadcastStatus("idle", "Retry cancelled.", false);
            if (!running.get()) {
                stopSelf(startId);
            }
            return running.get() ? START_STICKY : START_NOT_STICKY;
        }
        if (ACTION_DEMO.equals(action)) {
            activeName = "Demo stream";
            startDemoSession();
            return START_STICKY;
        }
        if (ACTION_CONNECT.equals(action)) {
            String address = intent.getStringExtra(EXTRA_ADDRESS);
            activeName = intent.getStringExtra(EXTRA_NAME);
            if (activeName == null || activeName.trim().isEmpty()) {
                activeName = "OBD adapter";
            }
            startObdSession(address, false);
            return START_STICKY;
        }
        if (ACTION_SCAN.equals(action)) {
            String address = intent.getStringExtra(EXTRA_ADDRESS);
            activeName = intent.getStringExtra(EXTRA_NAME);
            if (activeName == null || activeName.trim().isEmpty()) {
                activeName = "OBD adapter";
            }
            startObdSession(address, true);
            return START_STICKY;
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopCurrentSession("Service stopped.");
        if (bluetoothObservability != null) {
            bluetoothObservability.unregister(this);
        }
        executor.shutdownNow();
        competingAppExecutor.shutdownNow();
        recorder.shutdown();
        if (localStore != null) {
            localStore.close();
            localStore = null;
        }
        super.onDestroy();
    }

    /**
     * Bucket 2 hook: invoked from {@link ObdPollingEngine#initializeElm327()} right after the
     * adapter answers a {@code 0100} prompt. Delegates to {@link VoltageProbe} via a {@link
     * VoltageProbe.Sender} backed by the engine's one-shot transact helper, so the probe doesn't
     * need to know how the engine owns its connection.
     */
    void maybeRunVoltageProbe(ObdPollingEngine engineRef) {
        if (voltageProbe == null || engineRef == null) {
            return;
        }
        voltageProbe.run(engineRef::transactOneShot);
    }

    private void startObdSession(String address, boolean scanMode) {
        stopCurrentSession(null);
        // Bucket 4a (C6): clear any stale cancel-retry flag so a user-initiated
        // retry burst doesn't inherit a cancel from the previous session.
        cancelRetryRequested = false;
        // Bucket 2 (C4): re-scan for competing BT-OBD apps now — the user may have installed one
        // (Voltage, Torque, …) since service onCreate, and we want the dashboard's force-stop
        // button to surface for the *current* session, not a snapshot from process start.
        //
        // The package-manager probe can take hundreds of milliseconds on devices with many apps.
        // Keep it on the bounded service worker instead of starting ad-hoc threads per tap.
        refreshCompetingAppsAsync();
        startForegroundSession((scanMode ? "Scanning " : "Connecting to ") + activeName);
        sessionStartedAtMs = System.currentTimeMillis();
        engine.beginSession("");
        openSessionLog(scanMode ? "scan" : "obd", address);
        startLocationTracking();
        running.set(true);
        SESSION_ACTIVE.set(true);
        activeTask = executor.submit(() -> engine.runBluetoothLoop(address, scanMode));
    }

    private void refreshCompetingAppsAsync() {
        final CompetingAppDetector detectorRef = competingAppDetector;
        if (detectorRef == null) {
            return;
        }
        try {
            competingAppExecutor.execute(
                    () -> {
                        try {
                            detectorRef.refresh();
                        } catch (RuntimeException ex) {
                            Log.w(MainActivity.TAG, "competing-app refresh failed", ex);
                        }
                    });
        } catch (RuntimeException ex) {
            Log.w(MainActivity.TAG, "competing-app refresh rejected", ex);
        }
    }

    private void startDemoSession() {
        stopCurrentSession(null);
        startForegroundSession("Running demo telemetry");
        sessionStartedAtMs = System.currentTimeMillis();
        engine.beginSession("demo");
        openSessionLog("demo", null);
        running.set(true);
        SESSION_ACTIVE.set(true);
        activeTask = executor.submit(engine::runDemoLoop);
    }

    private void startLocationTracking() {
        if (locationTracker == null) {
            return;
        }
        if (!hasLocationPermission()) {
            recorder.logEvent("gps_skipped", "reason", "missing_location_permission");
            return;
        }
        locationTracker.start(recorder::persistLocation);
        recorder.logEvent("gps_started");
    }

    private void stopLocationTracking() {
        if (locationTracker != null) {
            locationTracker.stop();
            recorder.logEvent("gps_stopped");
        }
    }

    private void stopCurrentSession(String statusMessage) {
        running.set(false);
        SESSION_ACTIVE.set(false);
        if (activeTask != null) {
            activeTask.cancel(true);
            activeTask = null;
        }
        stopLocationTracking();
        engine.closeSocket();
        if (statusMessage != null) {
            broadcastStatus("idle", statusMessage, false);
        }
        closeSessionLog();
    }

    boolean hasBluetoothConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED;
    }

    boolean hasBluetoothScanPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
    }

    void broadcastTelemetry(JSONObject payload) {
        if (payload == null || payload.length() == 0) {
            return;
        }
        recorder.logJson("telemetry", payload);
        recorder.persistTelemetry(payload);
        broadcast(BROADCAST_TELEMETRY, payload);
    }

    void broadcastStatus(String state, String detail, boolean blocked) {
        broadcastStatus(state, detail, blocked, null);
    }

    /**
     * Overload that accepts an extras JSON merged into the status payload. Bucket 1 uses this to
     * attach {@code failureClass}; other callers can pass {@code null}. The service also auto-
     * merges {@link #lastFailureClass}, {@link #lastVoltage}, and {@link #competingAppsCsv} so
     * downstream dashboard code always sees the latest values without each call site needing to
     * thread them through.
     */
    void broadcastStatus(String state, String detail, boolean blocked, JSONObject extras) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("state", state);
            payload.put("detail", detail);
            payload.put("blocked", blocked);
            payload.put("adapter", activeName);
            payload.put("updatedAt", System.currentTimeMillis());
            String logFileName = recorder.logFileName();
            if (logFileName != null) {
                payload.put("logFile", logFileName);
            }
            // Auto-merged shared signals (set by Buckets 1 & 2 on the service).
            FailureClass fc = lastFailureClass;
            if (fc != null) {
                // wireName() is the snake-case string Bucket 1's classifier also writes to JSONL
                // events (failureClass), so the dashboard sees the same shape on both event lines
                // and live status broadcasts.
                payload.put("failureClass", fc.wireName());
            }
            Double v = lastVoltage;
            if (v != null) {
                payload.put("lastVoltage", v.doubleValue());
            }
            String csv = competingAppsCsv;
            if (csv != null && !csv.isEmpty()) {
                payload.put("competingApps", csv);
            }
            // Caller extras win — they override the auto-merged values if both are present.
            if (extras != null) {
                java.util.Iterator<String> keys = extras.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    payload.put(key, extras.get(key));
                }
            }
        } catch (JSONException ignored) {
            // Local values are safe.
        }
        lastSessionState = state == null ? "" : state;
        lastSessionDetail = detail == null ? "" : detail;
        recorder.logJson("status", payload);
        recorder.persistStatus(state, detail, blocked, payload);
        broadcast(BROADCAST_STATUS, payload);
    }

    private void broadcast(String action, JSONObject payload) {
        Intent intent = new Intent(action);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_JSON, payload.toString());
        sendBroadcast(intent);
    }

    /**
     * Snapshot of the FGS type bits this service is currently registered with. We re-check on every
     * foreground/background transition (see {@link #applyAppVisibility(boolean)}) so that a user
     * revoking the LOCATION permission mid-session leads to a {@code startForeground} call that
     * drops the {@code FOREGROUND_SERVICE_TYPE_LOCATION} bit — without this, Android 14+ can throw
     * {@code SecurityException} the next time the system reconciles the FGS-type-restricted
     * permission against the live service. Re-granting later does the opposite.
     */
    private int activeForegroundServiceType = 0;

    private void startForegroundSession(String text) {
        Notification notification = notifications.build(text);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int serviceType = currentForegroundServiceType();
            startForeground(ObdNotifications.NOTIFICATION_ID, notification, serviceType);
            activeForegroundServiceType = serviceType;
            foregroundServiceActive = true;
            return;
        }
        startForeground(ObdNotifications.NOTIFICATION_ID, notification);
        foregroundServiceActive = true;
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private int currentForegroundServiceType() {
        int serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
        if (hasLocationPermission()) {
            serviceType |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        }
        return serviceType;
    }

    /**
     * Re-issue {@code startForeground} with the current set of granted permissions if the
     * permission state has drifted since the session started. No-op when the service isn't running,
     * when the platform is pre-Android 10 (no per-FGS-type contract), or when the computed type is
     * unchanged.
     */
    private void reevaluateForegroundServiceType() {
        if (!running.get() || !foregroundServiceActive) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }
        int desired = currentForegroundServiceType();
        if (desired == activeForegroundServiceType) {
            return;
        }
        Notification notification =
                notifications.build(
                        appInForeground
                                ? "Logging while app is open"
                                : "Background logging active");
        try {
            startForeground(ObdNotifications.NOTIFICATION_ID, notification, desired);
            activeForegroundServiceType = desired;
            recorder.logEvent(
                    "foreground_service_type_changed",
                    "type",
                    Integer.toHexString(desired),
                    "hasLocation",
                    String.valueOf(hasLocationPermission()));
        } catch (SecurityException ex) {
            // startForeground can refuse a type-change if the corresponding runtime permission
            // is missing AND the manifest doesn't declare it; log + keep going rather than crash.
            Log.w(MainActivity.TAG, "reevaluateForegroundServiceType refused", ex);
        }
    }

    void updateNotification(String text) {
        recorder.logEvent("notification", "text", text);
        notifications.post(text);
    }

    /**
     * Engine hook: remember the most recent classified connect failure so other buckets can merge
     * it into the next status broadcast as {@code lastFailureClass}. No-op if {@code fc} is null —
     * callers should use {@link #clearLastFailureClass()} to explicitly reset.
     */
    void setLastFailureClass(FailureClass fc) {
        if (fc == null) {
            return;
        }
        lastFailureClass = fc;
    }

    /** Engine hook: invoked on a successful connect so a stale classification cannot linger. */
    void clearLastFailureClass() {
        lastFailureClass = null;
    }

    /** Read-side accessor for the last classified connect failure. Returns null when unset. */
    FailureClass lastFailureClass() {
        return lastFailureClass;
    }

    private void recordAppVisibility(boolean foreground) {
        if (appInForeground == foreground) {
            return;
        }
        appInForeground = foreground;
        // Offload the logging/notification work: it shares a monitor with sendCommand,
        // so doing it inline would stall the calling (often main) thread on a slow probe.
        recorder.runAsync(() -> applyAppVisibility(foreground));
    }

    private void applyAppVisibility(boolean foreground) {
        synchronized (ioLock) {
            recorder.logEvent(
                    foreground ? "app_foregrounded" : "app_backgrounded",
                    "backgroundSampleCount",
                    String.valueOf(engine.backgroundSampleCount()),
                    "sampleGapCount",
                    String.valueOf(engine.sampleGapCount()));
            if (running.get()) {
                updateNotification(
                        foreground ? "Logging while app is open" : "Background logging active");
                // Permission state can change between foreground and background passes (the user
                // may have revoked location from the system settings while we were backgrounded).
                // Re-evaluate the FGS type so we don't stay registered with a bit we no longer
                // have a runtime permission for.
                reevaluateForegroundServiceType();
            }
        }
    }

    private void openSessionLog(String mode, String address) {
        synchronized (ioLock) {
            lastSessionState = "active";
            lastSessionDetail = "";
            recorder.openSession(
                    mode,
                    address,
                    activeName,
                    sessionStartedAtMs > 0 ? sessionStartedAtMs : System.currentTimeMillis());
        }
    }

    void closeSessionLog() {
        synchronized (ioLock) {
            recorder.closeSession(
                    lastSessionState,
                    lastSessionDetail,
                    engine.supportedPidsSummary(),
                    engine.sampleCount(),
                    // Pass Bucket 1's fine-grained class so the summary rollup records
                    // "instant_drop" / "connect_timeout" / … instead of the coarse "error".
                    lastFailureClass);
            // Reset so a fresh session doesn't inherit the previous session's failure class
            // through the auto-merge in broadcastStatus(). Bucket 1 also clears this on a
            // successful connect, but if the service is started anew before a connect succeeds
            // (e.g., ACTION_DISCONNECT, then user reconnects) the field would otherwise carry
            // over into the new session's first status payload.
            clearLastFailureClass();
            foregroundServiceActive = false;
        }
    }
}
