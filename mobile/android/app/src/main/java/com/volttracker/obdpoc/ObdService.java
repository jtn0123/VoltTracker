package com.volttracker.obdpoc;

import android.Manifest;
import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
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
    public static final String BROADCAST_TELEMETRY = "com.volttracker.obdpoc.broadcast.TELEMETRY";
    public static final String BROADCAST_STATUS = "com.volttracker.obdpoc.broadcast.STATUS";
    public static final String EXTRA_ADDRESS = "address";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_JSON = "json";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    // Shared IO monitor: serializes session teardown against in-flight command IO.
    final Object ioLock = new Object();
    final AtomicBoolean running = new AtomicBoolean(false);
    private Future<?> activeTask;
    private ObdLocalStore localStore;
    SessionRecorder recorder;
    private ObdPollingEngine engine;
    private ObdNotifications notifications;
    LocationTracker locationTracker;
    String activeName = "OBD adapter";
    long sessionStartedAtMs;
    private String lastSessionState = "";
    private String lastSessionDetail = "";
    volatile boolean appInForeground = true;
    volatile boolean foregroundServiceActive;

    @Override
    public void onCreate() {
        super.onCreate();
        localStore = new ObdLocalStore(this);
        locationTracker = new LocationManagerTracker(this);
        notifications = new ObdNotifications(this);
        notifications.createChannel();
        recorder =
                new SessionRecorder(
                        ioLock, new ObdSessionLog(new File(getFilesDir(), "obd-logs")), localStore);
        engine = new ObdPollingEngine(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_DISCONNECT.equals(action)) {
            stopCurrentSession("Disconnected.");
            stopForeground(true);
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
        executor.shutdownNow();
        recorder.shutdown();
        if (localStore != null) {
            localStore.close();
            localStore = null;
        }
        super.onDestroy();
    }

    private void startObdSession(String address, boolean scanMode) {
        stopCurrentSession(null);
        startForegroundSession((scanMode ? "Scanning " : "Connecting to ") + activeName);
        sessionStartedAtMs = System.currentTimeMillis();
        engine.beginSession("");
        openSessionLog(scanMode ? "scan" : "obd", address);
        startLocationTracking();
        running.set(true);
        activeTask = executor.submit(() -> engine.runBluetoothLoop(address, scanMode));
    }

    private void startDemoSession() {
        stopCurrentSession(null);
        startForegroundSession("Running demo telemetry");
        sessionStartedAtMs = System.currentTimeMillis();
        engine.beginSession("demo");
        openSessionLog("demo", null);
        running.set(true);
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

    private void startForegroundSession(String text) {
        Notification notification = notifications.build(text);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            if (hasLocationPermission()) {
                serviceType |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            }
            startForeground(ObdNotifications.NOTIFICATION_ID, notification, serviceType);
            foregroundServiceActive = true;
            return;
        }
        startForeground(ObdNotifications.NOTIFICATION_ID, notification);
        foregroundServiceActive = true;
    }

    void updateNotification(String text) {
        recorder.logEvent("notification", "text", text);
        notifications.post(text);
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
                    engine.sampleCount());
            foregroundServiceActive = false;
        }
    }
}
