package com.volttracker.obdpoc;

import com.volttracker.obdpoc.data.ObdLocalStore;
import com.volttracker.obdpoc.location.FilteredLocation;
import com.volttracker.obdpoc.location.LocationManagerTracker;
import com.volttracker.obdpoc.location.LocationTracker;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ObdService extends Service {
    public static final String ACTION_CONNECT = "com.volttracker.obdpoc.action.CONNECT";
    public static final String ACTION_SCAN = "com.volttracker.obdpoc.action.SCAN";
    public static final String ACTION_DEMO = "com.volttracker.obdpoc.action.DEMO";
    public static final String ACTION_DISCONNECT = "com.volttracker.obdpoc.action.DISCONNECT";
    public static final String ACTION_APP_FOREGROUND = "com.volttracker.obdpoc.action.APP_FOREGROUND";
    public static final String ACTION_APP_BACKGROUND = "com.volttracker.obdpoc.action.APP_BACKGROUND";
    public static final String BROADCAST_TELEMETRY = "com.volttracker.obdpoc.broadcast.TELEMETRY";
    public static final String BROADCAST_STATUS = "com.volttracker.obdpoc.broadcast.STATUS";
    public static final String EXTRA_ADDRESS = "address";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_JSON = "json";

    private static final String CHANNEL_ID = "volt_obd_connection";
    private static final int NOTIFICATION_ID = 4207;
    private static final UUID ELM327_SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final long CONNECT_TIMEOUT_MS = 15000L;
    // After this many consecutive failed reconnects the OBD loop gives up; GPS keeps logging.
    static final int MAX_RECONNECT_ATTEMPTS = 6;
    // Package-private so the unit tests can assert these probe lists stay well-formed.
    static final String[] PROTOCOL_PROBES = {"ATSP0", "ATSP6", "ATSP7", "ATSP8"};
    static final String[] CAPABILITY_PROBES = {"0100", "0120", "0140", "0160"};
    // 015B is the standard mode-01 hybrid-battery SOC PID; it answers on the normal bus
    // with no ATSH header, so it is probed alongside the other live-data PIDs.
    static final String[] LIVE_PROBES = {"ATRV", "010D", "010C", "0105", "0104", "0111", "015B", "0142", "011F", "012F", "015C"};
    // Community-validated Chevy Volt mode-22 PIDs (Volt PID community sheet, see
    // docs/volt-pids-community-sheet.csv). ATSH selects the controller before each group;
    // decode formulas live in ObdProtocol.
    static final String[] VOLT_7E1_PROBES = {
            "222429",  // HV pack voltage
            "222414"   // HV pack current (signed: discharge positive)
    };
    static final String[] VOLT_7E4_PROBES = {
            "22434F",  // HV battery temperature
            "224368",  // charger AC input voltage
            "224369",  // charger AC input current
            "22436B",  // charger HV output voltage
            "22436C",  // charger HV output current
            "224373",  // charger HV output power
            "22437D"   // last charge AC energy
    };

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService databaseExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Future<?> activeTask;
    private ObdLocalStore localStore;
    private BluetoothSocket socket;
    private InputStream input;
    private OutputStream output;
    private BufferedWriter sessionLog;
    private File sessionLogFile;
    private LocationTracker locationTracker;
    private String activeName = "OBD adapter";
    private long sessionStartedAtMs;
    private int sampleCount;
    private String supportedPidsSummary = "";
    private long activeSessionId;
    private String activeMode = "";
    private String activeAddress = "";
    private String lastSessionState = "";
    private String lastSessionDetail = "";
    private String currentHeader = "";
    private Integer lastAcceptedSpeedKph;
    private long lastAcceptedSpeedAtMs;
    private volatile boolean appInForeground = true;
    private volatile boolean foregroundServiceActive;
    private String lastPersistedStatusKey = "";
    private long lastPersistedStatusAtMs = 0L;
    private int backgroundSampleCount;
    private int sampleGapCount;
    private long lastSampleAtMs;
    private long lastSampleGapMs;
    private long maxSampleGapMs;
    @Override
    public void onCreate() {
        super.onCreate();
        localStore = new ObdLocalStore(this);
        locationTracker = new LocationManagerTracker(this);
        createNotificationChannel();
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
            startBluetoothSession(address);
            return START_STICKY;
        }
        if (ACTION_SCAN.equals(action)) {
            String address = intent.getStringExtra(EXTRA_ADDRESS);
            activeName = intent.getStringExtra(EXTRA_NAME);
            if (activeName == null || activeName.trim().isEmpty()) {
                activeName = "OBD adapter";
            }
            startScanSession(address);
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
        databaseExecutor.shutdown();
        try {
            databaseExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        if (localStore != null) {
            localStore.close();
            localStore = null;
        }
        super.onDestroy();
    }

    private void startBluetoothSession(String address) {
        startObdSession(address, false);
    }

    private void startScanSession(String address) {
        startObdSession(address, true);
    }

    private void startObdSession(String address, boolean scanMode) {
        stopCurrentSession(null);
        startForegroundSession((scanMode ? "Scanning " : "Connecting to ") + activeName);
        sessionStartedAtMs = System.currentTimeMillis();
        sampleCount = 0;
        resetSessionHealth();
        lastAcceptedSpeedKph = null;
        lastAcceptedSpeedAtMs = 0L;
        lastPersistedStatusKey = "";
        lastPersistedStatusAtMs = 0L;
        supportedPidsSummary = "";
        openSessionLog(scanMode ? "scan" : "obd", address);
        startLocationTracking();
        running.set(true);
        activeTask = executor.submit(() -> runBluetoothLoop(address, scanMode));
    }

    private void startDemoSession() {
        stopCurrentSession(null);
        startForegroundSession("Running demo telemetry");
        sessionStartedAtMs = System.currentTimeMillis();
        sampleCount = 0;
        resetSessionHealth();
        lastPersistedStatusKey = "";
        lastPersistedStatusAtMs = 0L;
        supportedPidsSummary = "demo";
        openSessionLog("demo", null);
        running.set(true);
        activeTask = executor.submit(this::runDemoLoop);
    }

    private void startLocationTracking() {
        if (locationTracker == null) {
            return;
        }
        if (!hasLocationPermission()) {
            logEvent("gps_skipped", "reason", "missing_location_permission");
            return;
        }
        locationTracker.start(this::onTrackedLocation);
        logEvent("gps_started");
    }

    private void stopLocationTracking() {
        if (locationTracker != null) {
            locationTracker.stop();
            logEvent("gps_stopped");
        }
    }

    // Each accepted fix is persisted straight to the route on the GPS callback (not the OBD
    // poll), so the trace keeps recording across reconnects and idle OBD periods.
    private void onTrackedLocation(FilteredLocation location) {
        final long sessionId = activeSessionId;
        if (sessionId <= 0 || localStore == null || location == null
                || ObdLocalStore.MODE_DEMO.equals(activeMode)) {
            return;
        }
        persistAsync(() -> localStore.recordLocationSample(
                sessionId,
                location.fixTimeMs,
                location.provider,
                location.latitude,
                location.longitude,
                location.accuracyM,
                location.altitudeM,
                location.speedMps,
                location.bearingDeg,
                location.locationAgeMs,
                location.elapsedRealtimeNanos));
    }

    @SuppressLint("MissingPermission")
    private void runBluetoothLoop(String address, boolean scanMode) {
        if (address == null || address.trim().isEmpty()) {
            broadcastStatus("error", "No adapter selected.", true);
            closeSessionLog();
            return;
        }
        if (!hasBluetoothConnectPermission()) {
            broadcastStatus("error", "Bluetooth permission is missing.", true);
            closeSessionLog();
            return;
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            broadcastStatus("error", "Bluetooth is off or unavailable.", true);
            closeSessionLog();
            return;
        }

        int attempt = 0;
        try {
            while (running.get()) {
                try {
                    connectAndInitialize(adapter, address);
                    if (scanMode) {
                        runDiagnosticScan();
                        return;
                    }
                    attempt = 0;
                    broadcastStatus("connected", "Polling live OBD data from " + activeName + ".", false);
                    updateNotification("Connected to " + activeName);
                    pollUntilStoppedOrBroken();
                    return; // running went false: a clean stop
                } catch (IOException ex) {
                    closeSocket();
                    if (!running.get()) {
                        return;
                    }
                    attempt += 1;
                    if (attempt > MAX_RECONNECT_ATTEMPTS) {
                        logError("reconnect_exhausted", ex);
                        broadcastStatus("error",
                                "Lost the adapter link and could not reconnect after "
                                        + MAX_RECONNECT_ATTEMPTS + " tries.", true);
                        // Give up cleanly: stopSelf() routes teardown through onDestroy ->
                        // stopCurrentSession, which stops the GPS tracker and foreground
                        // service rather than leaving them running with no session.
                        stopSelf();
                        return;
                    }
                    long backoffMs = reconnectBackoffMs(attempt);
                    logEvent("reconnect", "attempt", String.valueOf(attempt),
                            "backoffMs", String.valueOf(backoffMs), "reason", safeMessage(ex));
                    broadcastStatus("connecting",
                            "Adapter link dropped - reconnecting (" + attempt + "/"
                                    + MAX_RECONNECT_ATTEMPTS + ")...", false);
                    sleep(backoffMs);
                }
            }
        } catch (RuntimeException ex) {
            logError("connection_failure", ex);
            broadcastStatus("error", friendlyConnectionMessage(ex), true);
        } finally {
            logEvent("socket_closing");
            closeSocket();
            closeSessionLog();
        }
    }

    @SuppressLint("MissingPermission")
    private void connectAndInitialize(BluetoothAdapter adapter, String address) throws IOException {
        broadcastStatus("connecting", "Opening serial connection to " + activeName + "...", false);
        if (hasBluetoothScanPermission()) {
            adapter.cancelDiscovery();
        } else {
            logEvent("cancel_discovery_skipped", "reason", "missing BLUETOOTH_SCAN");
        }
        BluetoothDevice device = adapter.getRemoteDevice(address);
        logEvent("bluetooth_socket_open", "address", address, "uuid", ELM327_SPP_UUID.toString());
        socket = device.createRfcommSocketToServiceRecord(ELM327_SPP_UUID);
        connectWithTimeout(socket);
        input = socket.getInputStream();
        output = socket.getOutputStream();
        logEvent("bluetooth_socket_connected", "address", address);
        broadcastStatus("initializing", "Connected. Initializing ELM327 adapter...", false);
        initializeElm327();
    }

    // Polls until the session is stopped (returns) or the socket breaks (throws IOException,
    // which the caller turns into a reconnect attempt).
    private void pollUntilStoppedOrBroken() throws IOException {
        while (running.get()) {
            JSONObject sample = readObdSample();
            if (sample == null || sample.length() == 0) {
                // A non-fatal encoding glitch yielded no usable sample; skip it and keep
                // the session polling rather than ending it on a transient issue.
                logEvent("empty_sample_skipped");
                continue;
            }
            broadcastTelemetry(sample);
            sleep(850);
        }
    }

    private void runDemoLoop() {
        broadcastStatus("connected", "Demo telemetry is running without an OBD adapter.", false);
        long start = System.currentTimeMillis();
        while (running.get()) {
            double t = (System.currentTimeMillis() - start) / 1000.0;
            JSONObject sample = new JSONObject();
            try {
                sampleCount += 1;
                sample.put("source", "demo");
                sample.put("connected", true);
                sample.put("adapter", activeName);
                sample.put("sampleCount", sampleCount);
                sample.put("sessionMs", Math.max(0, System.currentTimeMillis() - sessionStartedAtMs));
                sample.put("supportedPids", supportedPidsSummary);
                sample.put("vehicleState", "demo-preview");
                sample.put("speedKph", Math.max(0, Math.round(54 + 23 * Math.sin(t / 3.4))));
                sample.put("rpm", Math.round(1260 + 420 * Math.sin(t / 2.1)));
                sample.put("coolantC", Math.round(82 + 4 * Math.sin(t / 8.0)));
                sample.put("loadPct", Math.round(34 + 18 * Math.sin(t / 4.4)));
                sample.put("throttlePct", Math.round(18 + 14 * Math.sin(t / 2.7)));
                sample.put("voltage", round1(13.8 + 0.2 * Math.sin(t / 5.0)));
                sample.put("soc", Math.max(13.4, round1(77.8 - t * 0.01)));
                sample.put("batteryTemp", round1(24.0 + Math.sin(t / 8.0)));
                sample.put("powerKw", round1(16.0 + Math.sin(t / 2.2) * 12.0));
                sample.put("updatedAt", System.currentTimeMillis());
                appendSessionHealth(sample);
                sample.put("raw", "demo");
            } catch (JSONException ignored) {
                // Local numeric values are safe.
            }
            broadcastTelemetry(sample);
            sleep(1000);
        }
    }

    private void initializeElm327() throws IOException {
        sendCommand("ATZ", 3200);
        sendCommand("ATE0", 1400);
        sendCommand("ATL0", 1400);
        sendCommand("ATS0", 1400);
        sendCommand("ATH0", 1400);
        sendCommand("ATAT1", 1400);
        sendCommand("ATST64", 1400);
        sendCommand("ATSP0", 1800);
        String supportedPids = sendCommand("0100", 9000);
        if (hasElmPrompt(supportedPids)) {
            supportedPidsSummary = ObdProtocol.summarize(supportedPids);
            logEvent("protocol_probe_success", "command", "0100", "response", supportedPidsSummary);
        }
        if (!hasElmPrompt(supportedPids)) {
            logEvent("protocol_probe_no_prompt", "command", "0100", "response", ObdProtocol.summarize(supportedPids));
            sendEscape(600);
            sendCommand("ATPC", 1400);
            sendCommand("ATSP6", 1400);
            supportedPids = sendCommand("0100", 9000);
            if (hasElmPrompt(supportedPids)) {
                supportedPidsSummary = ObdProtocol.summarize(supportedPids);
                logEvent("protocol_probe_success", "command", "0100_after_ATSP6", "response", supportedPidsSummary);
            }
            if (!hasElmPrompt(supportedPids)) {
                logEvent("protocol_probe_no_prompt", "command", "0100_after_ATSP6", "response", ObdProtocol.summarize(supportedPids));
                sendEscape(600);
                sendCommand("ATPC", 1400);
                sendCommand("ATSP0", 1400);
            }
        }
    }

    private void runDiagnosticScan() throws IOException {
        broadcastStatus("scanning", "Running protocol, PID, VIN, DTC, and live-data probes...", false);
        updateNotification("Scanning " + activeName);

        StringBuilder raw = new StringBuilder();
        appendProbeLine(raw, "adapter", activeName);
        probeCommand("ATI", 1800, raw);
        probeCommand("ATDP", 1800, raw);
        probeCommand("ATDPN", 1800, raw);
        probeCommand("ATRV", 1800, raw);

        for (String protocol : PROTOCOL_PROBES) {
            probeCommand(protocol, 1800, raw);
            for (String capability : CAPABILITY_PROBES) {
                probeCommand(capability, "0100".equals(capability) ? 9000 : 3500, raw);
            }
            probeCommand("0902", 6000, raw);
            probeCommand("03", 3500, raw);
        }

        // The protocol sweep above leaves the adapter on the last probe (ATSP8), which is
        // the wrong CAN protocol for this car. Restore auto-detect so the live-data and
        // Volt PID probes below run on the vehicle's real protocol instead of CAN ERROR.
        appendProbeLine(raw, "volt-discovery", "restore auto protocol for live + Volt probes");
        probeCommand("ATSP0", 1800, raw);
        probeCommand("0100", 9000, raw);

        for (String probe : LIVE_PROBES) {
            probeCommand(probe, 3200, raw);
        }

        appendProbeLine(raw, "volt-discovery", "ATSH7E4 battery and charger probes");
        probeCommand("ATSH7E4", 1800, raw);
        for (String probe : VOLT_7E4_PROBES) {
            probeCommand(probe, 4200, raw);
        }
        appendProbeLine(raw, "volt-discovery", "ATSH7E1 pack voltage and current probes");
        probeCommand("ATSH7E1", 1800, raw);
        for (String probe : VOLT_7E1_PROBES) {
            probeCommand(probe, 4200, raw);
        }
        probeCommand("ATSH7DF", 1800, raw);

        JSONObject sample = new JSONObject();
        try {
            sample.put("source", "scan");
            sample.put("connected", true);
            sample.put("adapter", activeName);
            sample.put("updatedAt", System.currentTimeMillis());
            appendLocation(sample);
            sample.put("raw", tail(raw.toString(), 7200));
        } catch (JSONException ignored) {
            // Local values are safe.
        }
        broadcastTelemetry(sample);
        broadcastStatus("scan-complete", "Diagnostic scan complete. You can disconnect and bring the phone back for the log.", false);
        updateNotification("Scan complete for " + activeName);
    }

    // Throws IOException when the adapter socket has broken so the caller can reconnect.
    private JSONObject readObdSample() throws IOException {
        JSONObject sample = new JSONObject();
        String raw = "";
        try {
            String voltageRaw = sendRecoverableCommand("ATRV", 1500);
            raw = appendRaw(raw, "ATRV", voltageRaw);
            Float voltage = ObdProtocol.parseVoltage(voltageRaw);
            if (voltage != null) {
                sample.put("voltage", voltage);
            }

            String speedRaw = sendRecoverableCommand("010D", 1500);
            raw = appendRaw(raw, "010D", speedRaw);
            Integer speed = ObdProtocol.parseSpeedKph(speedRaw);
            boolean chargeTransitionHint = ObdProtocol.hasMaxSpeedSentinel(speedRaw);
            Integer acceptedSpeed = null;
            if (speed != null && isPlausibleSpeed(speed)) {
                sample.put("speedKph", speed);
                acceptedSpeed = speed;
            } else if (speed != null) {
                sample.put("speedRejectedKph", speed);
                logEvent("speed_rejected", "speedKph", String.valueOf(speed));
            } else if (chargeTransitionHint) {
                sample.put("speedRejectedKph", 255);
                sample.put("chargeTransitionHint", true);
                logEvent("speed_rejected", "speedKph", "255", "reason", "charge_transition_hint");
            }

            String rpmRaw = sendRecoverableCommand("010C", 1500);
            raw = appendRaw(raw, "010C", rpmRaw);
            Float rpm = ObdProtocol.parseRpm(rpmRaw);
            if (rpm != null) {
                sample.put("rpm", Math.round(rpm));
            }

            String coolantRaw = sendRecoverableCommand("0105", 1500);
            raw = appendRaw(raw, "0105", coolantRaw);
            Integer coolant = ObdProtocol.parseCoolantC(coolantRaw);
            if (coolant != null) {
                sample.put("coolantC", coolant);
            }

            String loadRaw = sendRecoverableCommand("0104", 1500);
            raw = appendRaw(raw, "0104", loadRaw);
            Integer load = ObdProtocol.parseEngineLoadPct(loadRaw);
            if (load != null) {
                sample.put("loadPct", load);
            }

            String throttleRaw = sendRecoverableCommand("0111", 1500);
            raw = appendRaw(raw, "0111", throttleRaw);
            Integer throttle = ObdProtocol.parseThrottlePct(throttleRaw);
            if (throttle != null) {
                sample.put("throttlePct", throttle);
            }

            String socRaw = sendRecoverableCommand("015B", 1500);
            raw = appendRaw(raw, "015B", socRaw);
            Integer soc = ObdProtocol.parseStateOfChargePct(socRaw);
            if (soc != null) {
                sample.put("soc", soc);
            }

            sampleCount += 1;
            sample.put("source", "obd");
            sample.put("connected", true);
            sample.put("adapter", activeName);
            sample.put("sampleCount", sampleCount);
            sample.put("sessionMs", Math.max(0, System.currentTimeMillis() - sessionStartedAtMs));
            sample.put("supportedPids", supportedPidsSummary);
            sample.put("vehicleState", classifyVehicleState(voltage, acceptedSpeed, rpm, load, chargeTransitionHint));
            sample.put("vehicleStateConfidence", classifyVehicleStateConfidence(voltage, acceptedSpeed, rpm, chargeTransitionHint));
            sample.put("updatedAt", System.currentTimeMillis());
            appendSessionHealth(sample);
            appendLocation(sample);
            sample.put("raw", raw.trim());
        } catch (JSONException ex) {
            // Local numeric values are safe; an encoding error is non-fatal, keep polling.
            logError("sample_encoding_error", ex);
        }
        return sample;
    }

    private void appendLocation(JSONObject sample) throws JSONException {
        FilteredLocation location = locationTracker == null ? null : locationTracker.getLastLocation();
        if (location == null) {
            return;
        }
        sample.put("latitude", location.latitude);
        sample.put("longitude", location.longitude);
        if (location.accuracyM != null) {
            sample.put("accuracyM", location.accuracyM);
        }
        if (location.speedMps != null) {
            sample.put("gpsSpeedMps", location.speedMps);
        }
        if (location.bearingDeg != null) {
            sample.put("bearingDeg", location.bearingDeg);
        }
        if (location.provider != null) {
            sample.put("provider", location.provider);
            sample.put("locationProvider", location.provider);
        }
        sample.put("locationAgeMs", Math.max(0L, System.currentTimeMillis() - location.fixTimeMs));
    }

    private synchronized void resetSessionHealth() {
        backgroundSampleCount = 0;
        sampleGapCount = 0;
        lastSampleAtMs = 0L;
        lastSampleGapMs = 0L;
        maxSampleGapMs = 0L;
    }

    private synchronized void appendSessionHealth(JSONObject sample) throws JSONException {
        long now = sample.optLong("updatedAt", System.currentTimeMillis());
        long gapMs = lastSampleAtMs > 0L ? Math.max(0L, now - lastSampleAtMs) : 0L;
        if (gapMs > 0L) {
            lastSampleGapMs = gapMs;
            maxSampleGapMs = Math.max(maxSampleGapMs, gapMs);
            if (gapMs > expectedSampleGapMs()) {
                sampleGapCount += 1;
                logEvent("sample_gap", "gapMs", String.valueOf(gapMs), "mode", activeMode);
            }
        }
        lastSampleAtMs = now;
        if (!appInForeground) {
            backgroundSampleCount += 1;
        }
        sample.put("appForeground", appInForeground);
        sample.put("foregroundServiceActive", foregroundServiceActive);
        sample.put("backgroundSampleCount", backgroundSampleCount);
        sample.put("sampleGapCount", sampleGapCount);
        sample.put("lastSampleGapMs", lastSampleGapMs);
        sample.put("maxSampleGapMs", maxSampleGapMs);
    }

    private long expectedSampleGapMs() {
        return "demo".equals(activeMode) ? 3500L : 6000L;
    }

    private String probeCommand(String command, long timeoutMs, StringBuilder raw) throws IOException {
        String response = sendRecoverableCommand(command, timeoutMs);
        appendProbeLine(raw, command, summarizeForStorage(command, response));
        return response;
    }

    private String sendRecoverableCommand(String command, long timeoutMs) throws IOException {
        String response = sendCommand(command, timeoutMs);
        if (!hasElmPrompt(response)) {
            logEvent("command_no_prompt_recovery", "command", command, "response", ObdProtocol.summarize(response));
            sendEscape(700);
        }
        return response;
    }

    private synchronized String sendCommand(String command, long timeoutMs) throws IOException {
        if (output == null || input == null) {
            throw new IOException("Adapter stream is not open");
        }
        drainInput();
        long startedAt = System.currentTimeMillis();
        output.write((command + "\r").getBytes(StandardCharsets.US_ASCII));
        output.flush();

        StringBuilder response = new StringBuilder();
        long deadline = System.currentTimeMillis() + timeoutMs;
        byte[] buffer = new byte[128];
        while (System.currentTimeMillis() < deadline && running.get()) {
            int available = input.available();
            if (available > 0) {
                int read = input.read(buffer, 0, Math.min(buffer.length, available));
                if (read > 0) {
                    String chunk = new String(buffer, 0, read, StandardCharsets.US_ASCII);
                    response.append(chunk);
                    if (chunk.indexOf('>') >= 0) {
                        break;
                    }
                }
            } else {
                sleep(25);
            }
        }
        String rawResponse = response.toString();
        logCommand(command, timeoutMs, System.currentTimeMillis() - startedAt, rawResponse);
        return rawResponse;
    }

    private synchronized void sendEscape(long settleMs) throws IOException {
        if (output == null || input == null) {
            return;
        }
        output.write(0x1B);
        output.flush();
        sleep(settleMs);
        drainInput();
        logEvent("elm_escape_sent", "settleMs", String.valueOf(settleMs));
    }

    static boolean hasElmPrompt(String response) {
        return response != null && response.indexOf('>') >= 0;
    }

    private void drainInput() throws IOException {
        if (input == null) {
            return;
        }
        byte[] buffer = new byte[128];
        int drained = 0;
        // Cap the drain so a chatty/garbage adapter cannot spin this loop forever.
        while (input.available() > 0 && drained < 8192) {
            int read = input.read(buffer, 0, Math.min(buffer.length, input.available()));
            if (read < 0) {
                break;
            }
            drained += read;
        }
    }

    private void stopCurrentSession(String statusMessage) {
        running.set(false);
        if (activeTask != null) {
            activeTask.cancel(true);
            activeTask = null;
        }
        stopLocationTracking();
        closeSocket();
        if (statusMessage != null) {
            broadcastStatus("idle", statusMessage, false);
        }
        closeSessionLog();
    }

    // BluetoothSocket.connect() has no timeout and can block for a long time on a dead
    // adapter. A daemon watchdog closes the socket after CONNECT_TIMEOUT_MS, which makes
    // the blocked connect() throw IOException so the normal failure path takes over.
    private void connectWithTimeout(BluetoothSocket pendingSocket) throws IOException {
        Thread watchdog = new Thread(() -> {
            sleep(CONNECT_TIMEOUT_MS);
            if (!pendingSocket.isConnected()) {
                try {
                    pendingSocket.close();
                } catch (IOException ignored) {
                }
            }
        });
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            pendingSocket.connect();
        } finally {
            watchdog.interrupt();
        }
    }

    private void closeSocket() {
        try {
            if (input != null) {
                input.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (output != null) {
                output.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
        input = null;
        output = null;
        socket = null;
    }

    private boolean hasBluetoothConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBluetoothScanPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void broadcastTelemetry(JSONObject payload) {
        if (payload == null || payload.length() == 0) {
            return;
        }
        logJson("telemetry", payload);
        persistTelemetry(payload);
        broadcast(BROADCAST_TELEMETRY, payload);
    }

    private void broadcastStatus(String state, String detail, boolean blocked) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("state", state);
            payload.put("detail", detail);
            payload.put("blocked", blocked);
            payload.put("adapter", activeName);
            payload.put("updatedAt", System.currentTimeMillis());
            if (sessionLogFile != null) {
                payload.put("logFile", sessionLogFile.getName());
            }
        } catch (JSONException ignored) {
            // Local values are safe.
        }
        lastSessionState = state == null ? "" : state;
        lastSessionDetail = detail == null ? "" : detail;
        logJson("status", payload);
        persistStatus(state, detail, blocked, payload);
        broadcast(BROADCAST_STATUS, payload);
    }

    private void broadcast(String action, JSONObject payload) {
        Intent intent = new Intent(action);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_JSON, payload.toString());
        sendBroadcast(intent);
    }

    private void startForegroundSession(String text) {
        Notification notification = buildNotification(text);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            if (hasLocationPermission()) {
                serviceType |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            }
            startForeground(NOTIFICATION_ID, notification, serviceType);
            foregroundServiceActive = true;
            return;
        }
        startForeground(NOTIFICATION_ID, notification);
        foregroundServiceActive = true;
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                open,
                PendingIntent.FLAG_IMMUTABLE
        );
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_stat_obd)
                .setContentTitle("Volt Tracker OBD")
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        logEvent("notification", "text", text);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private void recordAppVisibility(boolean foreground) {
        if (appInForeground == foreground) {
            return;
        }
        appInForeground = foreground;
        // Offload the logging/notification work: it shares a monitor with sendCommand,
        // so doing it inline would stall the calling (often main) thread on a slow probe.
        persistAsync(() -> applyAppVisibility(foreground));
    }

    private synchronized void applyAppVisibility(boolean foreground) {
        logEvent(foreground ? "app_foregrounded" : "app_backgrounded",
                "backgroundSampleCount", String.valueOf(backgroundSampleCount),
                "sampleGapCount", String.valueOf(sampleGapCount));
        if (running.get()) {
            updateNotification(foreground ? "Logging while app is open" : "Background logging active");
        }
    }

    private synchronized void openSessionLog(String mode, String address) {
        closeSessionLog();
        File dir = new File(getFilesDir(), "obd-logs");
        if (!dir.exists() && !dir.mkdirs()) {
            return;
        }
        sessionLogFile = new File(dir, "session-" + System.currentTimeMillis() + "-" + mode + ".jsonl");
        try {
            sessionLog = new BufferedWriter(new FileWriter(sessionLogFile, true));
            activeMode = mode == null ? "" : mode;
            activeAddress = address == null ? "" : address;
            lastSessionState = "active";
            lastSessionDetail = "";
            activeSessionId = localStore == null ? 0L : localStore.startSession(
                    activeMode,
                    activeAddress,
                    activeName,
                    sessionStartedAtMs > 0 ? sessionStartedAtMs : System.currentTimeMillis()
            );
            logEvent("session_start", "mode", mode, "adapter", activeName, "address", address == null ? "" : address);
            writeLatestPointer(sessionLogFile);
        } catch (IOException | RuntimeException ex) {
            sessionLog = null;
            sessionLogFile = null;
            activeSessionId = 0L;
        }
    }

    private synchronized void closeSessionLog() {
        long closingSessionId = activeSessionId;
        String closingMode = activeMode;
        String closingAddress = activeAddress;
        String closingState = lastSessionState;
        String closingDetail = lastSessionDetail;
        if (sessionLog != null) {
            try {
                logEvent("session_end");
                sessionLog.flush();
                sessionLog.close();
            } catch (IOException ignored) {
            }
        }
        if (closingSessionId > 0 && localStore != null) {
            try {
                String status = finishStatusFor(closingState);
                localStore.finishSession(closingSessionId, status, System.currentTimeMillis(), supportedPidsSummary);
                localStore.recordAdapterSummary(
                        closingAddress,
                        activeName,
                        closingMode,
                        closingSessionId,
                        status,
                        sampleCount,
                        supportedPidsSummary,
                        closingDetail
                );
            } catch (RuntimeException ignored) {
                // Field logging must keep working even if DB persistence has a bad day.
            }
        }
        sessionLog = null;
        activeSessionId = 0L;
        foregroundServiceActive = false;
        activeMode = "";
        activeAddress = "";
        currentHeader = "";
    }

    private void writeLatestPointer(File file) {
        File pointer = new File(new File(getFilesDir(), "obd-logs"), "latest.txt");
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(pointer, false))) {
            writer.write(file.getName());
            writer.newLine();
        } catch (IOException ignored) {
        }
    }

    private synchronized void logCommand(String command, long timeoutMs, long durationMs, String response) {
        JSONObject payload = new JSONObject();
        long observedAtMs = System.currentTimeMillis();
        String header = currentHeader;
        try {
            payload.put("command", command);
            payload.put("header", header);
            payload.put("timeoutMs", timeoutMs);
            payload.put("durationMs", durationMs);
            payload.put("response", summarizeForStorage(command, response));
            payload.put("gotPrompt", response != null && response.indexOf('>') >= 0);
            payload.put("empty", response == null || ObdProtocol.summarize(response).isEmpty());
            payload.put("observedAtMs", observedAtMs);
        } catch (JSONException ignored) {
        }
        logJson("command", payload);
        persistPidObservation(command, header, observedAtMs, timeoutMs, durationMs, response);
        updateHeaderState(command);
    }

    private synchronized void logError(String type, Exception ex) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("errorType", type);
            payload.put("exception", ex.getClass().getName());
            payload.put("message", safeMessage(ex));
        } catch (JSONException ignored) {
        }
        logJson("error", payload);
    }

    private synchronized void logEvent(String event, String... pairs) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("event", event);
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                payload.put(pairs[i], pairs[i + 1]);
            }
        } catch (JSONException ignored) {
        }
        logJson("event", payload);
    }

    private synchronized void logJson(String type, JSONObject payload) {
        if (sessionLog == null) {
            return;
        }
        JSONObject line = new JSONObject();
        try {
            line.put("ts", System.currentTimeMillis());
            line.put("type", type);
            line.put("payload", payload);
            if (sessionLogFile != null) {
                line.put("file", sessionLogFile.getName());
            }
            sessionLog.write(line.toString());
            sessionLog.newLine();
            sessionLog.flush();
        } catch (IOException | JSONException ignored) {
        }
        if (!"telemetry".equals(type) && !"status".equals(type)) {
            persistEvent(type, payload);
        }
    }

    private void persistTelemetry(JSONObject payload) {
        final long sessionId = activeSessionId;
        if (sessionId <= 0 || payload == null || localStore == null) {
            return;
        }
        if (ObdLocalStore.MODE_DEMO.equals(activeMode)) {
            // Demo telemetry is a UI preview only; keep it out of the real database.
            return;
        }
        persistAsync(() -> localStore.recordTelemetry(sessionId, payload));
    }

    private void persistStatus(String state, String detail, boolean blocked, JSONObject payload) {
        final long sessionId = activeSessionId;
        if (sessionId <= 0 || payload == null || localStore == null) {
            return;
        }
        if (shouldThrottleStatus(state, detail, blocked)) {
            return;
        }
        persistAsync(() -> localStore.recordStatus(sessionId, state, detail, blocked, payload));
    }

    private synchronized boolean shouldThrottleStatus(String state, String detail, boolean blocked) {
        long now = System.currentTimeMillis();
        String key = (state == null ? "" : state)
                + "|"
                + (detail == null ? "" : detail)
                + "|"
                + blocked;
        if (key.equals(lastPersistedStatusKey) && now - lastPersistedStatusAtMs < 5000L) {
            return true;
        }
        lastPersistedStatusKey = key;
        lastPersistedStatusAtMs = now;
        return false;
    }

    private void persistEvent(String type, JSONObject payload) {
        final long sessionId = activeSessionId;
        if (sessionId <= 0 || payload == null || localStore == null) {
            return;
        }
        persistAsync(() -> {
            String detail = payload.optString("detail",
                    payload.optString("message",
                            payload.optString("event", payload.optString("command", ""))));
            localStore.recordEvent(sessionId, type, payload.optString("state", ""), detail, false, payload);
        });
    }

    private void persistPidObservation(
            String command,
            String header,
            long observedAtMs,
            long timeoutMs,
            long durationMs,
            String response
    ) {
        final long sessionId = activeSessionId;
        if (sessionId <= 0 || localStore == null) {
            return;
        }
        String safeCommand = command == null ? "" : command.trim().toUpperCase(Locale.US);
        String summary = summarizeForStorage(safeCommand, response);
        ObdProtocol.ParsedPidValue parsed = ObdProtocol.parseKnownValue(safeCommand, response);
        persistAsync(() -> {
            JSONObject payload = new JSONObject();
            try {
                payload.put("observedAtMs", observedAtMs);
                payload.put("command", safeCommand);
                payload.put("header", header == null ? "" : header);
                payload.put("pid", pidForCommand(safeCommand));
                payload.put("name", parsed == null ? nameForCommand(safeCommand) : parsed.name);
                if (parsed != null) {
                    payload.put("valueText", parsed.valueText);
                    if (parsed.valueNumeric != null) {
                        payload.put("valueNumeric", parsed.valueNumeric.doubleValue());
                    }
                    payload.put("unit", parsed.unit);
                }
                payload.put("rawRequest", safeCommand);
                payload.put("rawResponse", summary);
                payload.put("gotPrompt", response != null && response.indexOf('>') >= 0);
                payload.put("timeoutMs", timeoutMs);
                payload.put("durationMs", durationMs);
            } catch (JSONException ignored) {
                // Local values are safe.
            }
            localStore.recordPidObservation(sessionId, payload, observedAtMs);
        });
    }

    private void persistAsync(Runnable task) {
        try {
            databaseExecutor.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException ignored) {
                    // Persistence is diagnostic; never interrupt OBD polling for it.
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    static String finishStatusFor(String state) {
        if ("error".equals(state) || "blocked".equals(state)) {
            return ObdLocalStore.STATUS_ERROR;
        }
        if ("idle".equals(state)) {
            return ObdLocalStore.STATUS_DISCONNECTED;
        }
        return ObdLocalStore.STATUS_COMPLETE;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "OBD connection",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Shows while Volt Tracker is connected to an OBD adapter.");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private static String appendRaw(String raw, String command, String response) {
        return raw + command + ": " + summarizeForStorage(command, response) + "\n";
    }

    static String summarizeForStorage(String command, String response) {
        String summary = ObdProtocol.summarize(response);
        if (!isVinCommand(command)) {
            return summary;
        }
        if (summary.isEmpty()) {
            return "";
        }
        return "[VIN redacted; responseLength=" + summary.length() + "]";
    }

    private static boolean isVinCommand(String command) {
        return command != null && "0902".equals(command.trim().toUpperCase(Locale.US));
    }

    private void updateHeaderState(String command) {
        if (command == null) {
            return;
        }
        String clean = command.trim().toUpperCase(Locale.US);
        if (clean.startsWith("ATSH") && clean.length() > 4) {
            currentHeader = clean.substring(4);
        } else if ("ATZ".equals(clean) || "ATD".equals(clean) || "ATPC".equals(clean)) {
            currentHeader = "";
        }
    }

    static String pidForCommand(String command) {
        if (command == null) {
            return "";
        }
        String clean = command.trim().toUpperCase(Locale.US);
        if (clean.startsWith("01") && clean.length() >= 4) {
            return clean.substring(2, 4);
        }
        if (clean.startsWith("22") && clean.length() >= 6) {
            return clean.substring(2);
        }
        if (clean.startsWith("09") && clean.length() >= 4) {
            return clean.substring(2, 4);
        }
        return "";
    }

    static String nameForCommand(String command) {
        if (command == null) {
            return "";
        }
        String clean = command.trim().toUpperCase(Locale.US);
        if ("ATRV".equals(clean)) {
            return "adapter voltage";
        }
        if ("010D".equals(clean)) {
            return "vehicle speed";
        }
        if ("010C".equals(clean)) {
            return "engine rpm";
        }
        if ("0105".equals(clean)) {
            return "coolant temperature";
        }
        if ("0104".equals(clean)) {
            return "engine load";
        }
        if ("0111".equals(clean)) {
            return "throttle position";
        }
        if ("0902".equals(clean)) {
            return "vin";
        }
        return "";
    }

    private static void appendProbeLine(StringBuilder raw, String label, String value) {
        raw.append(label).append(": ").append(value == null ? "" : value).append('\n');
    }

    private static String tail(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(value.length() - maxLength);
    }

    // Exponential backoff between OBD reconnect attempts, capped at 30 s.
    static long reconnectBackoffMs(int attempt) {
        if (attempt < 1) {
            return 0L;
        }
        long base = 2000L * (1L << Math.min(attempt - 1, 4));
        return Math.min(30000L, base);
    }

    static String classifyVehicleState(Float voltage, Integer speed, Float rpm, Integer load, boolean chargeTransitionHint) {
        boolean stationary = speed == null || speed == 0;
        boolean engineOff = rpm == null || rpm < 80;
        boolean dcDcActive = voltage != null && voltage >= 13.0f;
        boolean hasLoad = load != null && load > 0;
        if (stationary && engineOff && chargeTransitionHint) {
            return "plugged-or-charging";
        }
        if (stationary && engineOff && dcDcActive) {
            return "ready-parked";
        }
        if (stationary && engineOff) {
            return hasLoad ? "awake-parked" : "parked";
        }
        if (!engineOff) {
            return stationary ? "engine-idle" : "driving-gas";
        }
        return "driving-ev";
    }

    static String classifyVehicleStateConfidence(Float voltage, Integer speed, Float rpm, boolean chargeTransitionHint) {
        if (chargeTransitionHint) {
            return "inferred";
        }
        if (voltage != null && speed != null && rpm != null) {
            return "observed";
        }
        if (voltage != null || speed != null || rpm != null) {
            return "partial";
        }
        return "unknown";
    }

    private boolean isPlausibleSpeed(int speedKph) {
        long now = System.currentTimeMillis();
        if (speedKph < 0 || speedKph >= 255) {
            return false;
        }
        if (lastAcceptedSpeedKph == null || lastAcceptedSpeedAtMs <= 0L) {
            lastAcceptedSpeedKph = speedKph;
            lastAcceptedSpeedAtMs = now;
            return true;
        }
        double elapsedSeconds = Math.max(0.5, (now - lastAcceptedSpeedAtMs) / 1000.0);
        double jumpPerSecond = Math.abs(speedKph - lastAcceptedSpeedKph) / elapsedSeconds;
        if (jumpPerSecond > 45.0) {
            return false;
        }
        lastAcceptedSpeedKph = speedKph;
        lastAcceptedSpeedAtMs = now;
        return true;
    }

    static String friendlyConnectionMessage(Exception ex) {
        String message = safeMessage(ex).toLowerCase(Locale.US);
        if (message.contains("socket might closed") || message.contains("timeout") || message.contains("read failed")) {
            return "Adapter serial channel did not open. Make sure the car is awake, close other OBD apps, then retry.";
        }
        if (message.contains("permission")) {
            return "Bluetooth permission is missing. Grant permissions, then retry.";
        }
        return "OBD connection failed: " + safeMessage(ex);
    }

    private static String safeMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return ex.getClass().getSimpleName();
        }
        return message;
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
