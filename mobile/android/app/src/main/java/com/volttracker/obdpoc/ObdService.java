package com.volttracker.obdpoc;

import com.volttracker.obdpoc.data.ObdLocalStore;

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
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
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
    public static final String BROADCAST_TELEMETRY = "com.volttracker.obdpoc.broadcast.TELEMETRY";
    public static final String BROADCAST_STATUS = "com.volttracker.obdpoc.broadcast.STATUS";
    public static final String EXTRA_ADDRESS = "address";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_JSON = "json";

    private static final String CHANNEL_ID = "volt_obd_connection";
    private static final int NOTIFICATION_ID = 4207;
    private static final UUID ELM327_SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String[] PROTOCOL_PROBES = {"ATSP0", "ATSP6", "ATSP7", "ATSP8"};
    private static final String[] CAPABILITY_PROBES = {"0100", "0120", "0140", "0160"};
    private static final String[] LIVE_PROBES = {"ATRV", "010D", "010C", "0105", "0104", "0111", "0142", "011F", "012F", "015C"};
    private static final String[] VOLT_7E4_PROBES = {"2243AF1", "228334", "2241A31", "2234B2", "0902"};
    private static final String[] VOLT_7E7_CELL_SAMPLE_PROBES = {
            "2241811", "2241821", "2241831", "2241841",
            "2241981", "2241B01", "2241C81", "2241F01", "2242401"
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
    private LocationManager locationManager;
    private volatile Location lastLocation;
    private String activeName = "OBD adapter";
    private long sessionStartedAtMs;
    private int sampleCount;
    private String supportedPidsSummary = "";
    private long activeSessionId;
    private String activeMode = "";
    private String activeAddress = "";
    private String lastSessionState = "";
    private String lastSessionDetail = "";
    private Integer lastAcceptedSpeedKph;
    private long lastAcceptedSpeedAtMs;
    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if (location != null) {
                lastLocation = location;
            }
        }

        @Override
        public void onProviderDisabled(String provider) {
            logEvent("gps_provider_disabled", "provider", provider);
        }

        @Override
        public void onProviderEnabled(String provider) {
            logEvent("gps_provider_enabled", "provider", provider);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
            // Deprecated but still required by older Android API levels.
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        localStore = new ObdLocalStore(this);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_DISCONNECT.equals(action)) {
            stopCurrentSession("Disconnected.");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_DEMO.equals(action)) {
            activeName = "Demo stream";
            startForegroundSession("Running demo telemetry");
            startDemoSession();
            return START_STICKY;
        }
        if (ACTION_CONNECT.equals(action)) {
            String address = intent.getStringExtra(EXTRA_ADDRESS);
            activeName = intent.getStringExtra(EXTRA_NAME);
            if (activeName == null || activeName.trim().isEmpty()) {
                activeName = "OBD adapter";
            }
            startForegroundSession("Connecting to " + activeName);
            startBluetoothSession(address);
            return START_STICKY;
        }
        if (ACTION_SCAN.equals(action)) {
            String address = intent.getStringExtra(EXTRA_ADDRESS);
            activeName = intent.getStringExtra(EXTRA_NAME);
            if (activeName == null || activeName.trim().isEmpty()) {
                activeName = "OBD adapter";
            }
            startForegroundSession("Scanning " + activeName);
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
        sessionStartedAtMs = System.currentTimeMillis();
        sampleCount = 0;
        lastAcceptedSpeedKph = null;
        lastAcceptedSpeedAtMs = 0L;
        supportedPidsSummary = "";
        openSessionLog(scanMode ? "scan" : "obd", address);
        startLocationCapture();
        running.set(true);
        activeTask = executor.submit(() -> runBluetoothLoop(address, scanMode));
    }

    private void startDemoSession() {
        stopCurrentSession(null);
        sessionStartedAtMs = System.currentTimeMillis();
        sampleCount = 0;
        supportedPidsSummary = "demo";
        openSessionLog("demo", null);
        running.set(true);
        activeTask = executor.submit(this::runDemoLoop);
    }

    @SuppressLint("MissingPermission")
    private void startLocationCapture() {
        lastLocation = null;
        if (!hasLocationPermission()) {
            logEvent("gps_skipped", "reason", "missing_location_permission");
            return;
        }
        locationManager = getSystemService(LocationManager.class);
        if (locationManager == null) {
            logEvent("gps_skipped", "reason", "no_location_manager");
            return;
        }
        try {
            Location gps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location network = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            lastLocation = newestLocation(gps, network);
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    1.5f,
                    locationListener
            );
            locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    5000L,
                    10f,
                    locationListener
            );
            logEvent("gps_started");
        } catch (IllegalArgumentException | SecurityException ex) {
            logEvent("gps_start_failed", "message", safeMessage(ex));
        }
    }

    private void stopLocationCapture() {
        if (locationManager == null) {
            return;
        }
        try {
            locationManager.removeUpdates(locationListener);
            logEvent("gps_stopped");
        } catch (SecurityException ignored) {
        }
        locationManager = null;
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

        try {
            broadcastStatus("connecting", "Opening serial connection to " + activeName + "...", false);
            if (hasBluetoothScanPermission()) {
                adapter.cancelDiscovery();
            } else {
                logEvent("cancel_discovery_skipped", "reason", "missing BLUETOOTH_SCAN");
            }
            BluetoothDevice device = adapter.getRemoteDevice(address);
            logEvent("bluetooth_socket_open", "address", address, "uuid", ELM327_SPP_UUID.toString());
            socket = device.createRfcommSocketToServiceRecord(ELM327_SPP_UUID);
            socket.connect();
            input = socket.getInputStream();
            output = socket.getOutputStream();
            logEvent("bluetooth_socket_connected", "address", address);

            broadcastStatus("initializing", "Connected. Initializing ELM327 adapter...", false);
            initializeElm327();
            if (scanMode) {
                runDiagnosticScan();
                return;
            }
            broadcastStatus("connected", "Polling live OBD data from " + activeName + ".", false);
            updateNotification("Connected to " + activeName);

            while (running.get()) {
                JSONObject sample = readObdSample();
                broadcastTelemetry(sample);
                sleep(850);
            }
        } catch (IOException | RuntimeException ex) {
            logError("connection_failure", ex);
            broadcastStatus("error", friendlyConnectionMessage(ex), true);
        } finally {
            logEvent("socket_closing");
            closeSocket();
            closeSessionLog();
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
                sample.put("batteryTemp", round1(72.0 + Math.sin(t / 8.0)));
                sample.put("powerKw", round1(16.0 + Math.sin(t / 2.2) * 12.0));
                sample.put("updatedAt", System.currentTimeMillis());
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

        for (String probe : LIVE_PROBES) {
            probeCommand(probe, 3200, raw);
        }

        appendProbeLine(raw, "volt-discovery", "ATSH7E4 pack/state probes");
        probeCommand("ATSH7E4", 1800, raw);
        for (String probe : VOLT_7E4_PROBES) {
            probeCommand(probe, 4200, raw);
        }
        appendProbeLine(raw, "volt-discovery", "ATSH7E7 sample cell probes");
        probeCommand("ATSH7E7", 1800, raw);
        for (String probe : VOLT_7E7_CELL_SAMPLE_PROBES) {
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

    private JSONObject readObdSample() {
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
            Integer acceptedSpeed = null;
            if (speed != null && isPlausibleSpeed(speed)) {
                sample.put("speedKph", speed);
                acceptedSpeed = speed;
            } else if (speed != null) {
                sample.put("speedRejectedKph", speed);
                logEvent("speed_rejected", "speedKph", String.valueOf(speed));
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

            sampleCount += 1;
            sample.put("source", "obd");
            sample.put("connected", true);
            sample.put("adapter", activeName);
            sample.put("sampleCount", sampleCount);
            sample.put("sessionMs", Math.max(0, System.currentTimeMillis() - sessionStartedAtMs));
            sample.put("supportedPids", supportedPidsSummary);
            sample.put("vehicleState", classifyVehicleState(voltage, acceptedSpeed, rpm, load));
            sample.put("updatedAt", System.currentTimeMillis());
            appendLocation(sample);
            sample.put("raw", raw.trim());
        } catch (IOException | JSONException ex) {
            logError("polling_error", ex);
            broadcastStatus("error", "OBD polling error: " + safeMessage(ex), true);
        }
        return sample;
    }

    private void appendLocation(JSONObject sample) throws JSONException {
        Location location = lastLocation;
        if (location == null) {
            return;
        }
        sample.put("latitude", round6(location.getLatitude()));
        sample.put("longitude", round6(location.getLongitude()));
        if (location.hasAccuracy()) {
            sample.put("accuracyM", round1(location.getAccuracy()));
        }
        if (location.hasSpeed()) {
            sample.put("gpsSpeedMps", round1(location.getSpeed()));
        }
        if (location.hasBearing()) {
            sample.put("bearingDeg", round1(location.getBearing()));
        }
        sample.put("locationProvider", location.getProvider());
        sample.put("locationAgeMs", Math.max(0L, System.currentTimeMillis() - location.getTime()));
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

    private static boolean hasElmPrompt(String response) {
        return response != null && response.indexOf('>') >= 0;
    }

    private void drainInput() throws IOException {
        if (input == null) {
            return;
        }
        byte[] buffer = new byte[128];
        while (input.available() > 0) {
            int ignored = input.read(buffer, 0, Math.min(buffer.length, input.available()));
            if (ignored < 0) {
                break;
            }
        }
    }

    private void stopCurrentSession(String statusMessage) {
        running.set(false);
        if (activeTask != null) {
            activeTask.cancel(true);
            activeTask = null;
        }
        stopLocationCapture();
        closeSocket();
        if (statusMessage != null) {
            broadcastStatus("idle", statusMessage, false);
        }
        closeSessionLog();
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
            return;
        }
        startForeground(NOTIFICATION_ID, notification);
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
        activeMode = "";
        activeAddress = "";
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
        try {
            payload.put("command", command);
            payload.put("timeoutMs", timeoutMs);
            payload.put("durationMs", durationMs);
            payload.put("response", summarizeForStorage(command, response));
            payload.put("gotPrompt", response != null && response.indexOf('>') >= 0);
            payload.put("empty", response == null || ObdProtocol.summarize(response).isEmpty());
        } catch (JSONException ignored) {
        }
        logJson("command", payload);
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
        persistAsync(() -> localStore.recordTelemetry(sessionId, payload));
    }

    private void persistStatus(String state, String detail, boolean blocked, JSONObject payload) {
        final long sessionId = activeSessionId;
        if (sessionId <= 0 || payload == null || localStore == null) {
            return;
        }
        persistAsync(() -> localStore.recordStatus(sessionId, state, detail, blocked, payload));
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

    private static String finishStatusFor(String state) {
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

    private static String summarizeForStorage(String command, String response) {
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

    private static void appendProbeLine(StringBuilder raw, String label, String value) {
        raw.append(label).append(": ").append(value == null ? "" : value).append('\n');
    }

    private static String tail(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(value.length() - maxLength);
    }

    private static Location newestLocation(Location first, Location second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.getTime() >= second.getTime() ? first : second;
    }

    private static String classifyVehicleState(Float voltage, Integer speed, Float rpm, Integer load) {
        boolean stationary = speed == null || speed == 0;
        boolean engineOff = rpm == null || rpm < 80;
        boolean dcDcActive = voltage != null && voltage >= 13.0f;
        boolean hasLoad = load != null && load > 0;
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

    private static String friendlyConnectionMessage(Exception ex) {
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

    private static double round6(double value) {
        return Math.round(value * 1000000.0) / 1000000.0;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
