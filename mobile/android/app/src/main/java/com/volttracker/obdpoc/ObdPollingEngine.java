package com.volttracker.obdpoc;

import static com.volttracker.obdpoc.ObdElmDecode.appendRaw;
import static com.volttracker.obdpoc.ObdElmDecode.friendlyConnectionMessage;
import static com.volttracker.obdpoc.ObdElmDecode.hasElmPrompt;
import static com.volttracker.obdpoc.ObdElmDecode.initialConnectBackoffMs;
import static com.volttracker.obdpoc.ObdElmDecode.reconnectBackoffMs;
import static com.volttracker.obdpoc.ObdElmDecode.round1;
import static com.volttracker.obdpoc.ObdElmDecode.safeMessage;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.util.Log;
import com.volttracker.obdpoc.classify.ClassifierInput;
import com.volttracker.obdpoc.classify.ClassifierResult;
import com.volttracker.obdpoc.classify.VehicleStateClassifier;
import com.volttracker.obdpoc.location.FilteredLocation;
import java.io.IOException;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Runs the OBD adapter IO on the {@link ObdService} worker thread: the connect/reconnect loop,
 * ELM327 init, and the live-data poll. The diagnostic-scan and demo streams live in {@link
 * DiagnosticScanRunner} and {@link DemoPollingLoop} respectively and share this engine's command-IO
 * surface ({@link #sendRecoverableCommand}, {@link #appendLocation}, {@link #appendSessionHealth},
 * {@link #incrementSampleCount}). Command IO is serialized on {@code ObdService.ioLock} so a
 * reconnect cannot race a session stop; connection parameters and probe lists live in {@link
 * ObdProbes}.
 *
 * <p>Not {@code final}: {@code ObdPollingEngineTest} subclasses this to override {@link
 * #isBluetoothReady} and {@link #openBluetoothSocket} so the connect / poll / reconnect state
 * machine can be exercised without a real {@code BluetoothAdapter}.
 */
class ObdPollingEngine {

    private final ObdService service;
    // Non-final so tests can swap in a fake via setConnectionForTest(). Production code
    // never reassigns this — the constructor's initializer is the only real assignment.
    private ElmConnection connection = new ElmConnection();
    private final SpeedPlausibilityFilter speedFilter = new SpeedPlausibilityFilter();
    private final DemoPollingLoop demoLoop;
    private final DiagnosticScanRunner scanRunner;
    private int sampleCount;
    private String supportedPidsSummary = "";
    private int backgroundSampleCount;
    private int sampleGapCount;
    private long lastSampleAtMs;
    private long lastSampleGapMs;
    private long maxSampleGapMs;

    ObdPollingEngine(ObdService service) {
        this.service = service;
        this.demoLoop = new DemoPollingLoop(service, this);
        this.scanRunner = new DiagnosticScanRunner(service, this);
    }

    /** Resets the per-session counters before a new session's loop is submitted. */
    void beginSession(String supportedPidsSeed) {
        sampleCount = 0;
        resetSessionHealth();
        speedFilter.reset();
        supportedPidsSummary = supportedPidsSeed;
    }

    int sampleCount() {
        return sampleCount;
    }

    String supportedPidsSummary() {
        return supportedPidsSummary;
    }

    int backgroundSampleCount() {
        return backgroundSampleCount;
    }

    int sampleGapCount() {
        return sampleGapCount;
    }

    void closeSocket() {
        connection.close();
    }

    /**
     * Test seam: lets {@code ObdPollingEngineTest} substitute a fake {@link ElmConnection} that
     * scripts adapter responses instead of opening a real RFCOMM socket. Not called from
     * production.
     */
    void setConnectionForTest(ElmConnection replacement) {
        this.connection = replacement;
    }

    /**
     * Drives the BT-adapter session state machine for the lifetime of one session.
     *
     * <p>Two failure modes are tracked separately:
     *
     * <ol>
     *   <li><b>Never-connected</b> ({@code everConnected == false}): the very first {@code
     *       connectAndInitialize} threw, so the adapter was never reachable. Status messages say
     *       "Couldn't reach &lt;name&gt;" and the backoff curve used is {@link
     *       com.volttracker.obdpoc.ObdElmDecode#initialConnectBackoffMs} (gentler — the user may
     *       still be turning the car on).
     *   <li><b>Mid-session drop</b> ({@code everConnected == true}): we had a working session and
     *       lost it. Status messages say "Adapter link dropped" and the backoff curve used is
     *       {@link com.volttracker.obdpoc.ObdElmDecode#reconnectBackoffMs} (more aggressive — the
     *       link was just up).
     * </ol>
     *
     * <p>On either path, after {@link ObdProbes#MAX_RECONNECT_ATTEMPTS} consecutive failures, the
     * service stops itself cleanly via {@code service.stopSelf()}, which routes teardown through
     * {@code ObdService.onDestroy → stopCurrentSession} so GPS and foreground notifications are
     * released.
     *
     * <p>Scan mode short-circuits the live-poll loop: one connect, one {@link
     * DiagnosticScanRunner#run()}, then return.
     *
     * <p>This method must run on a worker thread (the service IO thread); the service's {@code
     * ioLock} serializes its writes against teardown so a reconnect cannot race a {@code
     * stopCurrentSession}.
     */
    @SuppressLint("MissingPermission")
    void runBluetoothLoop(String address, boolean scanMode) {
        if (address == null || address.trim().isEmpty()) {
            service.broadcastStatus("error", "No adapter selected.", true);
            service.closeSessionLog();
            return;
        }
        if (!service.hasBluetoothConnectPermission()) {
            service.broadcastStatus("error", "Bluetooth permission is missing.", true);
            service.closeSessionLog();
            return;
        }

        if (!isBluetoothReady()) {
            service.broadcastStatus("error", "Bluetooth is off or unavailable.", true);
            service.closeSessionLog();
            return;
        }

        int attempt = 0;
        // Distinguishes a never-established link from a mid-session drop: the wording
        // and backoff differ, since "reconnecting" makes no sense before a first connect.
        boolean everConnected = false;
        try {
            while (service.running.get()) {
                try {
                    connectAndInitialize(address);
                    everConnected = true;
                    OBDLog.event("ObdPollingEngine", "connect", Map.of("name", service.activeName));
                    if (scanMode) {
                        scanRunner.run();
                        return;
                    }
                    attempt = 0;
                    service.broadcastStatus(
                            "connected",
                            "Polling live OBD data from " + service.activeName + ".",
                            false);
                    service.updateNotification("Connected to " + service.activeName);
                    pollUntilStoppedOrBroken();
                    return; // running went false: a clean stop
                } catch (IOException ex) {
                    closeSocket();
                    if (everConnected) {
                        OBDLog.event(
                                "ObdPollingEngine",
                                "disconnect",
                                Map.of("reason", safeMessage(ex)));
                    }
                    if (!service.running.get()) {
                        return;
                    }
                    attempt += 1;
                    if (attempt > ObdProbes.MAX_RECONNECT_ATTEMPTS) {
                        // Catastrophic — also write to logcat so a post-crash bug report
                        // has the failure reason even if the JSONL log did not flush.
                        Log.w(
                                MainActivity.TAG,
                                "OBD reconnect exhausted for "
                                        + service.activeName
                                        + " after "
                                        + ObdProbes.MAX_RECONNECT_ATTEMPTS
                                        + " attempts",
                                ex);
                        service.recorder.logError("reconnect_exhausted", ex);
                        service.broadcastStatus(
                                "error",
                                everConnected
                                        ? "Lost the adapter link and could not reconnect after "
                                                + ObdProbes.MAX_RECONNECT_ATTEMPTS
                                                + " tries."
                                        : "Could not reach "
                                                + service.activeName
                                                + " after "
                                                + ObdProbes.MAX_RECONNECT_ATTEMPTS
                                                + " tries. Make sure the car is awake and the "
                                                + "adapter is plugged in.",
                                true);
                        // Give up cleanly: stopSelf() routes teardown through onDestroy ->
                        // stopCurrentSession, which stops the GPS tracker and foreground
                        // service rather than leaving them running with no session.
                        service.stopSelf();
                        return;
                    }
                    long backoffMs =
                            everConnected
                                    ? reconnectBackoffMs(attempt)
                                    : initialConnectBackoffMs(attempt);
                    OBDLog.event(
                            "ObdPollingEngine", "reconnect_attempt", Map.of("attempt", attempt));
                    service.recorder.logEvent(
                            "reconnect",
                            "attempt",
                            String.valueOf(attempt),
                            "backoffMs",
                            String.valueOf(backoffMs),
                            "reason",
                            safeMessage(ex),
                            "everConnected",
                            String.valueOf(everConnected));
                    service.broadcastStatus(
                            "connecting",
                            everConnected
                                    ? "Adapter link dropped - reconnecting ("
                                            + attempt
                                            + "/"
                                            + ObdProbes.MAX_RECONNECT_ATTEMPTS
                                            + ")..."
                                    : "Couldn't reach "
                                            + service.activeName
                                            + " - retrying ("
                                            + attempt
                                            + "/"
                                            + ObdProbes.MAX_RECONNECT_ATTEMPTS
                                            + ")...",
                            false);
                    sleep(backoffMs);
                }
            }
        } catch (RuntimeException ex) {
            Log.w(MainActivity.TAG, "OBD loop runtime failure for " + service.activeName, ex);
            service.recorder.logError("connection_failure", ex);
            service.broadcastStatus("error", friendlyConnectionMessage(ex), true);
        } finally {
            service.recorder.logEvent("socket_closing");
            closeSocket();
            service.closeSessionLog();
        }
    }

    @SuppressLint("MissingPermission")
    private void connectAndInitialize(String address) throws IOException {
        service.broadcastStatus(
                "connecting", "Opening serial connection to " + service.activeName + "...", false);
        service.recorder.logEvent(
                "bluetooth_socket_open",
                "address",
                address,
                "uuid",
                ObdProbes.ELM327_SPP_UUID.toString());
        openBluetoothSocket(address);
        service.recorder.logEvent("bluetooth_socket_connected", "address", address);
        service.broadcastStatus("initializing", "Connected. Initializing ELM327 adapter...", false);
        initializeElm327();
    }

    /**
     * Test seam: returns true when the Bluetooth adapter is present and enabled. Production
     * resolves the system {@link BluetoothAdapter}; tests override to bypass the adapter entirely.
     */
    @SuppressLint("MissingPermission")
    boolean isBluetoothReady() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        return adapter != null && adapter.isEnabled();
    }

    /**
     * Test seam: resolves the remote device and opens the RFCOMM socket. Pulled out of {@link
     * #connectAndInitialize} so tests can override the BT-specific bit while still exercising the
     * surrounding init / poll / reconnect logic.
     */
    @SuppressLint("MissingPermission")
    void openBluetoothSocket(String address) throws IOException {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (service.hasBluetoothScanPermission()) {
            adapter.cancelDiscovery();
        } else {
            service.recorder.logEvent(
                    "cancel_discovery_skipped", "reason", "missing BLUETOOTH_SCAN");
        }
        BluetoothDevice device = adapter.getRemoteDevice(address);
        connection.open(device, ObdProbes.ELM327_SPP_UUID, ObdProbes.CONNECT_TIMEOUT_MS);
    }

    // Polls until the session is stopped (returns) or the socket breaks (throws IOException,
    // which the caller turns into a reconnect attempt).
    private void pollUntilStoppedOrBroken() throws IOException {
        while (service.running.get()) {
            JSONObject sample = readObdSample();
            if (sample == null || sample.length() == 0) {
                // A non-fatal encoding glitch yielded no usable sample; skip it and keep
                // the session polling rather than ending it on a transient issue.
                service.recorder.logEvent("empty_sample_skipped");
                continue;
            }
            service.broadcastTelemetry(sample);
            sleep(850);
        }
    }

    /** Thin delegate kept on the engine so {@link ObdService} can submit it as a Runnable. */
    void runDemoLoop() {
        demoLoop.run();
    }

    /** Returns the new sample count after incrementing; used by {@link DemoPollingLoop}. */
    int incrementSampleCount() {
        return ++sampleCount;
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
            supportedPidsSummary = ObdProtocol.cleanSupportedPids(supportedPids);
            service.recorder.logEvent(
                    "protocol_probe_success", "command", "0100", "response", supportedPidsSummary);
        }
        if (!hasElmPrompt(supportedPids)) {
            service.recorder.logEvent(
                    "protocol_probe_no_prompt",
                    "command",
                    "0100",
                    "response",
                    ObdProtocol.summarize(supportedPids));
            sendEscape(600);
            sendCommand("ATPC", 1400);
            sendCommand("ATSP6", 1400);
            supportedPids = sendCommand("0100", 9000);
            if (hasElmPrompt(supportedPids)) {
                supportedPidsSummary = ObdProtocol.cleanSupportedPids(supportedPids);
                service.recorder.logEvent(
                        "protocol_probe_success",
                        "command",
                        "0100_after_ATSP6",
                        "response",
                        supportedPidsSummary);
            }
            if (!hasElmPrompt(supportedPids)) {
                service.recorder.logEvent(
                        "protocol_probe_no_prompt",
                        "command",
                        "0100_after_ATSP6",
                        "response",
                        ObdProtocol.summarize(supportedPids));
                sendEscape(600);
                sendCommand("ATPC", 1400);
                sendCommand("ATSP0", 1400);
            }
        }
        OBDLog.event("ObdPollingEngine", "protocol_init", Map.of("ok", true));
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
            if (speed != null && speedFilter.accept(speed, System.currentTimeMillis())) {
                sample.put("speedKph", speed);
                acceptedSpeed = speed;
            } else if (speed != null) {
                sample.put("speedRejectedKph", speed);
                service.recorder.logEvent("speed_rejected", "speedKph", String.valueOf(speed));
            } else if (chargeTransitionHint) {
                sample.put("speedRejectedKph", 255);
                sample.put("chargeTransitionHint", true);
                service.recorder.logEvent(
                        "speed_rejected", "speedKph", "255", "reason", "charge_transition_hint");
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

            // Volt HV pack metrics (battery temp, pack power) live on dedicated ECUs
            // reached via ATSH headers — see ObdProbes.VOLT_7E1_PROBES / VOLT_7E4_PROBES.
            // Restore the functional header (7DF) afterwards so the next cycle's mode-01
            // PIDs still broadcast to every ECU. A thrown IOException ends the poll and
            // the reconnect re-runs initializeElm327 (ATSP0), so the header self-heals.
            sendCommand("ATSH7E1", 1500);
            String packVoltageRaw = sendRecoverableCommand("222429", 1500);
            raw = appendRaw(raw, "222429", packVoltageRaw);
            String packCurrentRaw = sendRecoverableCommand("222414", 1500);
            raw = appendRaw(raw, "222414", packCurrentRaw);
            sendCommand("ATSH7E4", 1500);
            String batteryTempRaw = sendRecoverableCommand("22434F", 1500);
            raw = appendRaw(raw, "22434F", batteryTempRaw);
            sendCommand("ATSH7DF", 1500);
            ObdProtocol.ParsedPidValue batteryTemp =
                    ObdProtocol.parseKnownValue("22434F", batteryTempRaw);
            if (batteryTemp != null && batteryTemp.valueNumeric != null) {
                sample.put("batteryTemp", round1(batteryTemp.valueNumeric));
            }
            Double powerKw = ObdProtocol.parsePackPowerKw(packVoltageRaw, packCurrentRaw);
            if (powerKw != null) {
                sample.put("powerKw", round1(powerKw));
            }

            sampleCount += 1;
            sample.put("source", "obd");
            sample.put("connected", true);
            sample.put("adapter", service.activeName);
            sample.put("sampleCount", sampleCount);
            sample.put(
                    "sessionMs",
                    Math.max(0, System.currentTimeMillis() - service.sessionStartedAtMs));
            sample.put("supportedPids", supportedPidsSummary);
            ObdProtocol.ParsedPidValue packCurrent =
                    ObdProtocol.parseKnownValue("222414", packCurrentRaw);
            Double packCurrentA = packCurrent == null ? null : packCurrent.valueNumeric;
            Boolean engineRunningHint = rpm == null ? null : (rpm > 200f);
            ClassifierResult classified =
                    VehicleStateClassifier.classify(
                            new ClassifierInput(
                                    acceptedSpeed == null ? null : acceptedSpeed.doubleValue(),
                                    rpm == null ? null : Math.round(rpm),
                                    voltage == null ? null : voltage.doubleValue(),
                                    packCurrentA,
                                    chargeTransitionHint ? Boolean.TRUE : null,
                                    engineRunningHint,
                                    System.currentTimeMillis()));
            sample.put("vehicleState", classified.state.asPayloadKey());
            sample.put("vehicleStateConfidence", classified.confidence.asPayloadKey());
            sample.put("vehicleStateReasons", new JSONArray(classified.reasons));
            sample.put("updatedAt", System.currentTimeMillis());
            appendSessionHealth(sample);
            appendLocation(sample);
            sample.put("raw", raw.trim());
        } catch (JSONException ex) {
            // Local numeric values are safe; an encoding error is non-fatal, keep polling.
            service.recorder.logError("sample_encoding_error", ex);
        }
        return sample;
    }

    void appendLocation(JSONObject sample) throws JSONException {
        FilteredLocation location =
                service.locationTracker == null ? null : service.locationTracker.getLastLocation();
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

    private void resetSessionHealth() {
        synchronized (service.ioLock) {
            backgroundSampleCount = 0;
            sampleGapCount = 0;
            lastSampleAtMs = 0L;
            lastSampleGapMs = 0L;
            maxSampleGapMs = 0L;
        }
    }

    void appendSessionHealth(JSONObject sample) throws JSONException {
        synchronized (service.ioLock) {
            long now = sample.optLong("updatedAt", System.currentTimeMillis());
            long gapMs = lastSampleAtMs > 0L ? Math.max(0L, now - lastSampleAtMs) : 0L;
            if (gapMs > 0L) {
                lastSampleGapMs = gapMs;
                maxSampleGapMs = Math.max(maxSampleGapMs, gapMs);
                long expectedGapMs = "demo".equals(service.recorder.activeMode()) ? 3500L : 6000L;
                if (gapMs > expectedGapMs) {
                    sampleGapCount += 1;
                    service.recorder.logEvent(
                            "sample_gap",
                            "gapMs",
                            String.valueOf(gapMs),
                            "mode",
                            service.recorder.activeMode());
                }
            }
            lastSampleAtMs = now;
            if (!service.appInForeground) {
                backgroundSampleCount += 1;
            }
            sample.put("appForeground", service.appInForeground);
            sample.put("foregroundServiceActive", service.foregroundServiceActive);
            sample.put("backgroundSampleCount", backgroundSampleCount);
            sample.put("sampleGapCount", sampleGapCount);
            sample.put("lastSampleGapMs", lastSampleGapMs);
            sample.put("maxSampleGapMs", maxSampleGapMs);
        }
    }

    String sendRecoverableCommand(String command, long timeoutMs) throws IOException {
        String response = sendCommand(command, timeoutMs);
        if (!hasElmPrompt(response)) {
            service.recorder.logEvent(
                    "command_no_prompt_recovery",
                    "command",
                    command,
                    "response",
                    ObdProtocol.summarize(response));
            sendEscape(700);
        }
        return response;
    }

    private String sendCommand(String command, long timeoutMs) throws IOException {
        synchronized (service.ioLock) {
            long startedAt = System.currentTimeMillis();
            String rawResponse = connection.transact(command, timeoutMs, service.running::get);
            service.recorder.logCommand(
                    command, timeoutMs, System.currentTimeMillis() - startedAt, rawResponse);
            return rawResponse;
        }
    }

    private void sendEscape(long settleMs) throws IOException {
        synchronized (service.ioLock) {
            connection.sendEscape(settleMs);
            service.recorder.logEvent("elm_escape_sent", "settleMs", String.valueOf(settleMs));
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
