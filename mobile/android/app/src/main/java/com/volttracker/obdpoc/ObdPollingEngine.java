package com.volttracker.obdpoc;

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
import com.volttracker.obdpoc.PidSchedule.Header;
import com.volttracker.obdpoc.PidSchedule.PidSpec;
import com.volttracker.obdpoc.classify.ClassifierInput;
import com.volttracker.obdpoc.classify.ClassifierResult;
import com.volttracker.obdpoc.classify.VehicleStateClassifier;
import com.volttracker.obdpoc.location.FilteredLocation;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    /**
     * A3 long-backoff schedule. Activated when two consecutive {@link FailureClass#INSTANT_DROP}s
     * fire in &lt;500 ms each (the wedged-adapter signature). The default exponential ramp burns
     * through all 6 retries in ~25 s; this gives the adapter ~20 s of quiet to recover before
     * giving up — empirically the shortest interval that lets a wedged OBDLink MX+ recover without
     * a power-cycle. Indexed by retry count after wedge detection trips: index 0 covers the first
     * retry done in wedged mode (the third overall failed attempt), index 1 the fourth, and the
     * fallback kicks in for attempt 5 / 6 so the loop still terminates.
     */
    static final long[] LONG_BACKOFFS_MS = {8_000L, 12_000L};

    /**
     * Max stack-frame characters captured into the {@code stackHead} field on reconnect /
     * reconnect_exhausted events. Beyond ~1 KB the row stops being grep-friendly and starts costing
     * real bytes in the JSONL.
     */
    private static final int STACK_HEAD_MAX_CHARS = 1000;

    /** Number of frames pulled off the throwable when assembling {@code stackHead}. */
    private static final int STACK_HEAD_FRAMES = 5;

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

    // B6 — staggered polling state. cycleNum drives PidSchedule.dueOnCycle(); the maps
    // carry forward the most recent raw response (and wall-clock time it was taken) for
    // every PID so cycles that don't re-poll a slow PID can still emit its last-known value
    // and a *StaleMs companion. Reset at the start of every session.
    private int cycleNum;
    private final Map<String, String> lastRawByCommand = new HashMap<>();
    private final Map<String, Long> lastPolledAtMsByCommand = new HashMap<>();

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
        cycleNum = 0;
        lastRawByCommand.clear();
        lastPolledAtMsByCommand.clear();
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
        // A3: track consecutive instant_drop attempts so the engine can switch into
        // long-backoff mode after two failures in <500 ms each — the wedged-adapter signature.
        int consecutiveInstantDrops = 0;
        boolean wedgedMode = false;
        try {
            while (service.running.get()) {
                // C6: honor a user cancel that arrived between attempts (or before the very first
                // one) — without this check the engine could press on into another multi-second
                // connectAndInitialize that the user has already asked us to abandon. The post-
                // sleep check below catches cancels that arrive during the backoff; this one
                // catches everything else.
                if (service.cancelRetryRequested) {
                    service.cancelRetryRequested = false;
                    service.recorder.logEvent(
                            "retry_cancelled_by_user",
                            "attempt",
                            String.valueOf(attempt),
                            "everConnected",
                            String.valueOf(everConnected),
                            "phase",
                            "pre_connect");
                    service.broadcastStatus("idle", "Retry cancelled.", false);
                    service.stopSelf();
                    return;
                }
                long attemptStart = System.currentTimeMillis();
                try {
                    connectAndInitialize(address);
                    everConnected = true;
                    consecutiveInstantDrops = 0;
                    wedgedMode = false;
                    service.clearLastFailureClass();
                    // C6: a successful connect clears any stale cancel-retry request so a fresh
                    // user-initiated mid-session retry burst can proceed unhindered.
                    service.cancelRetryRequested = false;
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
                    long attemptDurationMs =
                            Math.max(0L, System.currentTimeMillis() - attemptStart);
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
                    // Classify before any logging so the failure class can be stamped onto
                    // every event in this catch block.
                    String phase = connection.lastErrorPhase;
                    if (phase == null || phase.isEmpty()) {
                        phase = "post_connect";
                    }
                    FailureClass fc =
                            ConnectionFailureClassifier.classify(
                                    phase, attemptDurationMs, ex, isBluetoothReady());
                    service.setLastFailureClass(fc);

                    // Track instant_drop streak for A3's adaptive-backoff decision. Anything
                    // other than an instant_drop breaks the streak so we don't trip the long
                    // backoff on, say, alternating connect_timeout / instant_drop sequences.
                    if (fc == FailureClass.INSTANT_DROP
                            && attemptDurationMs
                                    < ConnectionFailureClassifier.INSTANT_DROP_THRESHOLD_MS) {
                        consecutiveInstantDrops += 1;
                    } else {
                        consecutiveInstantDrops = 0;
                        wedgedMode = false;
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
                        // B6: an additional structured event carries failureClass +
                        // exceptionClass + stackHead so post-hoc triage doesn't depend on
                        // the unstructured "error" row.
                        service.recorder.logEvent(
                                "reconnect_exhausted",
                                "attempts",
                                String.valueOf(ObdProbes.MAX_RECONNECT_ATTEMPTS),
                                "failureClass",
                                fc.wireName(),
                                "exceptionClass",
                                exceptionClassName(ex),
                                "stackHead",
                                stackHead(ex));
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

                    // A3: once we've seen two instant_drops in a row, switch to the long-backoff
                    // schedule (8 s / 12 s for attempts 3+4 / 4+5). The default exponential
                    // ramp would burn through attempts in ~25 s — barely a chance for the
                    // adapter to recover from a wedge. Emit wedged_suspected once per streak.
                    if (consecutiveInstantDrops >= 2 && !wedgedMode) {
                        wedgedMode = true;
                        service.recorder.logEvent(
                                "wedged_suspected",
                                "consecutiveInstantDrops",
                                String.valueOf(consecutiveInstantDrops),
                                "lastAttemptDurationMs",
                                String.valueOf(attemptDurationMs),
                                "failureClass",
                                fc.wireName());
                    }

                    long backoffMs = computeBackoffMs(attempt, everConnected, wedgedMode);
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
                            String.valueOf(everConnected),
                            "failureClass",
                            fc.wireName(),
                            "exceptionClass",
                            exceptionClassName(ex),
                            "stackHead",
                            stackHead(ex),
                            "attemptDurationMs",
                            String.valueOf(attemptDurationMs),
                            "wedgedMode",
                            String.valueOf(wedgedMode));
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
                    // C6: honor a user cancel that arrived during the backoff sleep — bail out of
                    // the retry burst instead of pressing on through the remaining attempts.
                    if (service.cancelRetryRequested) {
                        service.cancelRetryRequested = false;
                        service.recorder.logEvent(
                                "retry_cancelled_by_user",
                                "attempt",
                                String.valueOf(attempt),
                                "everConnected",
                                String.valueOf(everConnected));
                        service.broadcastStatus("idle", "Retry cancelled.", false);
                        service.stopSelf();
                        return;
                    }
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
        // Bucket 2 hook: snapshot bond/SDP/adapter state right before the RFCOMM attempt so a
        // failure later in this method can be correlated with the pre-flight signals.
        if (service.bluetoothObservability != null) {
            service.bluetoothObservability.onPreConnect(address);
        }
        // A1: socket_open_attempt fires BEFORE openBluetoothSocket so timing is comparable to
        // the subsequent socket_open_result. The legacy "bluetooth_socket_open" event used to
        // straddle both, which made it impossible to tell from a JSONL whether a failure
        // happened during rfcomm connect, getStreams, or the first read.
        service.recorder.logEvent(
                "socket_open_attempt",
                "address",
                address,
                "uuid",
                ObdProbes.ELM327_SPP_UUID.toString());
        long openStart = System.currentTimeMillis();
        boolean openOk = false;
        String openErrorPhase = "post_connect";
        try {
            openBluetoothSocket(address);
            openOk = true;
        } catch (IOException ex) {
            // Prefer the phase the connection itself recorded (rfcomm_connect / get_streams);
            // fall back to "post_connect" if the throw came from elsewhere in the open chain.
            String phase = connection.lastErrorPhase;
            openErrorPhase = (phase == null || phase.isEmpty()) ? "post_connect" : phase;
            logSocketOpenResult(false, openStart, openErrorPhase);
            throw ex;
        }
        // A4: wake-nudge. Send a single \r and tolerate up to 200 ms of silence. On a healthy
        // adapter this returns gotResponse=true and ATZ lands cleanly; on a wedged adapter the
        // exception bubbles up as a first_read failure, classified downstream.
        try {
            ElmConnection.WakeNudgeResult nudge = connection.wakeNudge(200L);
            service.recorder.logEvent(
                    "wake_nudge",
                    "durationMs",
                    String.valueOf(nudge.durationMs),
                    "gotResponse",
                    String.valueOf(nudge.gotResponse));
        } catch (IOException ex) {
            openOk = false;
            openErrorPhase = "first_read";
            service.recorder.logEvent(
                    "wake_nudge",
                    "durationMs",
                    String.valueOf(connection.firstReadMs),
                    "gotResponse",
                    "false",
                    "error",
                    safeMessage(ex));
            logSocketOpenResult(false, openStart, openErrorPhase);
            throw ex;
        }
        logSocketOpenResult(openOk, openStart, openErrorPhase);
        service.broadcastStatus("initializing", "Connected. Initializing ELM327 adapter...", false);
        initializeElm327();
    }

    /**
     * Emits a {@code socket_open_result} with the timing buckets the connection collected. {@code
     * errorPhase} is empty when {@code ok==true}; otherwise it identifies the phase the throw
     * happened in.
     */
    private void logSocketOpenResult(boolean ok, long openStart, String errorPhase) {
        long totalMs = Math.max(0L, System.currentTimeMillis() - openStart);
        service.recorder.logEvent(
                "socket_open_result",
                "durationMs",
                String.valueOf(totalMs),
                "ok",
                String.valueOf(ok),
                "errorPhase",
                ok ? "" : (errorPhase == null ? "post_connect" : errorPhase),
                "rfcommConnectMs",
                String.valueOf(connection.rfcommConnectMs),
                "getStreamsMs",
                String.valueOf(connection.getStreamsMs),
                "firstReadMs",
                String.valueOf(connection.firstReadMs));
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
        service.maybeRunVoltageProbe(this);
    }

    /**
     * Bucket 2 helper: a single-command IO round-trip exposed package-private so {@link
     * VoltageProbe} can run its one-shot {@code 0142} probe without taking on the rest of the
     * engine's connection state. Delegates to the same {@link #sendCommand} path the engine uses,
     * so the {@code ioLock} synchronization and the {@code logCommand} accounting still apply.
     */
    String transactOneShot(String command, long timeoutMs) throws IOException {
        return sendCommand(command, timeoutMs);
    }

    // Throws IOException when the adapter socket has broken so the caller can reconnect.
    private JSONObject readObdSample() throws IOException {
        JSONObject sample = new JSONObject();
        StringBuilder rawThisCycle = new StringBuilder();
        try {
            // B6: poll only the PIDs due on this cycle, grouped by ATSH header so we only
            // pay the 7E4 switch cost when batteryTemp is actually being read. The first
            // cycle (cycleNum == 0) intentionally polls every spec so the dashboard has a
            // complete baseline within one cycle instead of phasing in over ~10 seconds.
            boolean isInitialCycle = (cycleNum == 0);
            List<PidSpec> due =
                    isInitialCycle ? PidSchedule.SPECS : PidSchedule.dueOnCycle(cycleNum);
            runScheduledPolls(due, rawThisCycle);
            cycleNum += 1;

            // Every parse below reads the carry-forward last-known raw. That way cycles that
            // skip a slow PID still emit its last value (and a *StaleMs companion below) and
            // the dashboard sees a complete sample with no flicker.
            String voltageRaw = lastRawByCommand.get("ATRV");
            Float voltage = ObdProtocol.parseVoltage(voltageRaw);
            if (voltage != null) {
                sample.put("voltage", voltage);
            }

            String speedRaw = lastRawByCommand.get("010D");
            Integer speed = ObdProtocol.parseSpeedKph(speedRaw);
            // The speed sentinel (0xFF) is the Volt's "charging" hint. We only re-evaluate the
            // charge-transition hint / speed filter when this cycle actually polled speed —
            // otherwise we'd double-count old samples as "rejected" every cycle.
            boolean polledSpeedThisCycle = !isInitialCycle ? wasPolledThisCycle(due, "010D") : true;
            boolean chargeTransitionHint =
                    polledSpeedThisCycle && ObdProtocol.hasMaxSpeedSentinel(speedRaw);
            Integer acceptedSpeed = null;
            if (polledSpeedThisCycle) {
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
                            "speed_rejected",
                            "speedKph",
                            "255",
                            "reason",
                            "charge_transition_hint");
                }
            } else if (speed != null) {
                // Carry-forward path: re-emit the last accepted speed without re-running the
                // filter (it's time-sensitive) so the dashboard keeps showing the last value.
                sample.put("speedKph", speed);
                acceptedSpeed = speed;
            }

            Float rpm = ObdProtocol.parseRpm(lastRawByCommand.get("010C"));
            if (rpm != null) {
                sample.put("rpm", Math.round(rpm));
            }
            Integer coolant = ObdProtocol.parseCoolantC(lastRawByCommand.get("0105"));
            if (coolant != null) {
                sample.put("coolantC", coolant);
            }
            Integer load = ObdProtocol.parseEngineLoadPct(lastRawByCommand.get("0104"));
            if (load != null) {
                sample.put("loadPct", load);
            }
            // Prefer the drive-by-wire accelerator pedal (0149) over the legacy ICE throttle
            // body (0111). On a Chevy Volt 0111 returns a constant ~33% because the engine's
            // throttle body isn't user-actuated — the real pedal position lives behind 0149.
            // Fall back to 0111 only when 0149 hasn't responded (older vehicles or unsupported
            // by the ECM), so users without the Volt-specific PID still see *something*.
            Integer pedal = ObdProtocol.parseAccelPedalPct(lastRawByCommand.get("0149"));
            if (pedal != null) {
                sample.put("throttlePct", pedal);
                sample.put("throttleSource", "accelPedal");
            } else {
                Integer throttle = ObdProtocol.parseThrottlePct(lastRawByCommand.get("0111"));
                if (throttle != null) {
                    sample.put("throttlePct", throttle);
                    sample.put("throttleSource", "iceThrottleBody");
                }
            }
            Integer soc = ObdProtocol.parseStateOfChargePct(lastRawByCommand.get("015B"));
            if (soc != null) {
                sample.put("soc", soc);
            }

            String packVoltageRaw = lastRawByCommand.get("222429");
            String packCurrentRaw = lastRawByCommand.get("222414");
            String batteryTempRaw = lastRawByCommand.get("22434F");
            ObdProtocol.ParsedPidValue batteryTemp =
                    ObdProtocol.parseKnownValue("22434F", batteryTempRaw);
            if (batteryTemp != null && batteryTemp.valueNumeric != null) {
                sample.put("batteryTemp", round1(batteryTemp.valueNumeric));
            }
            ObdProtocol.ParsedPidValue packVoltage =
                    ObdProtocol.parseKnownValue("222429", packVoltageRaw);
            if (packVoltage != null && packVoltage.valueNumeric != null) {
                sample.put("packVoltage", round1(packVoltage.valueNumeric));
            }
            // packCurrent is parsed below as part of the vehicle-state classifier inputs.
            Double powerKw = ObdProtocol.parsePackPowerKw(packVoltageRaw, packCurrentRaw);
            if (powerKw != null) {
                sample.put("powerKw", round1(powerKw));
            }

            // B6: per-PID staleness companion fields. Only emit for slow PIDs (period > 1)
            // since Tier 1 PIDs are always fresh on every cycle. Dashboard may ignore these
            // (and most rendering does) — they exist so a future stale-tile UI can show
            // "value last updated N seconds ago" without flicker between polls.
            long now = System.currentTimeMillis();
            putStaleMsIfTracked(sample, "voltageStaleMs", "ATRV", now);
            putStaleMsIfTracked(sample, "socStaleMs", "015B", now);
            putStaleMsIfTracked(sample, "coolantCStaleMs", "0105", now);
            putStaleMsIfTracked(sample, "batteryTempStaleMs", "22434F", now);

            sampleCount += 1;
            sample.put("source", "obd");
            sample.put("connected", true);
            sample.put("adapter", service.activeName);
            sample.put("sampleCount", sampleCount);
            sample.put("sessionMs", Math.max(0, now - service.sessionStartedAtMs));
            sample.put("supportedPids", supportedPidsSummary);
            ObdProtocol.ParsedPidValue packCurrent =
                    ObdProtocol.parseKnownValue("222414", packCurrentRaw);
            Double packCurrentA = packCurrent == null ? null : packCurrent.valueNumeric;
            if (packCurrentA != null) {
                sample.put("packCurrentA", round1(packCurrentA));
            }
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
                                    now));
            sample.put("vehicleState", classified.state.asPayloadKey());
            sample.put("vehicleStateConfidence", classified.confidence.asPayloadKey());
            sample.put("vehicleStateReasons", new JSONArray(classified.reasons));
            sample.put("updatedAt", now);
            appendSessionHealth(sample);
            appendLocation(sample);
            sample.put("raw", rawThisCycle.toString().trim());
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

    /**
     * Issues every PID read due on this cycle, grouped by ATSH header so we don't pay an
     * unnecessary header switch for headers that have nothing due. Captures the wire transcript
     * into {@code rawThisCycle} and updates the carry-forward maps. Throws {@link IOException} only
     * if the underlying socket breaks — non-fatal "no prompt" or empty-response cases are absorbed
     * by {@link #sendRecoverableCommand}.
     */
    private void runScheduledPolls(List<PidSpec> due, StringBuilder rawThisCycle)
            throws IOException {
        if (due.isEmpty()) {
            return;
        }
        boolean switchedHeader = false;
        Header lastHeaderSet = Header.BROADCAST;
        for (Header header : Header.values()) {
            List<PidSpec> headerSpecs = filterByHeader(due, header);
            if (headerSpecs.isEmpty()) {
                continue;
            }
            if (header != Header.BROADCAST && header != lastHeaderSet) {
                sendCommand(header.atCommand, 1500);
                switchedHeader = true;
                lastHeaderSet = header;
            }
            for (PidSpec spec : headerSpecs) {
                String response = sendRecoverableCommand(spec.command, 1500);
                appendRawTo(rawThisCycle, spec.command, response);
                lastRawByCommand.put(spec.command, response);
                lastPolledAtMsByCommand.put(spec.command, System.currentTimeMillis());
            }
        }
        // Restore the broadcast header only if we actually changed it — most cycles only
        // touch BROADCAST + 7E1, so the restore lands once. The init path runs ATSP0 on
        // reconnect, so a half-restored header self-heals on the next reconnect.
        if (switchedHeader) {
            sendCommand(PidSchedule.RESTORE_BROADCAST_HEADER_COMMAND, 1500);
        }
    }

    private static List<PidSpec> filterByHeader(List<PidSpec> specs, Header header) {
        List<PidSpec> out = new ArrayList<>();
        for (PidSpec spec : specs) {
            if (spec.header == header) {
                out.add(spec);
            }
        }
        return out;
    }

    /**
     * True if the given command appears in {@code due}. Used so the speed/sentinel logic only fires
     * on cycles that actually polled speed; carry-forward cycles use the last-accepted-speed path
     * without re-running the time-sensitive filter.
     */
    private static boolean wasPolledThisCycle(List<PidSpec> due, String command) {
        for (PidSpec spec : due) {
            if (command.equals(spec.command)) {
                return true;
            }
        }
        return false;
    }

    private void putStaleMsIfTracked(JSONObject sample, String key, String command, long now)
            throws JSONException {
        Long polledAtMs = lastPolledAtMsByCommand.get(command);
        if (polledAtMs != null) {
            sample.put(key, Math.max(0L, now - polledAtMs));
        }
    }

    private static void appendRawTo(StringBuilder buf, String command, String response) {
        if (buf.length() > 0) {
            buf.append(' ');
        }
        buf.append('[').append(command).append("] ").append(ObdProtocol.summarize(response));
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

    /**
     * Compute the backoff before the next reconnect attempt. In wedged mode (A3) the first two
     * wedged-mode retries use {@link #LONG_BACKOFFS_MS}; everything else falls back to the existing
     * exponential / initial curves so the retry math stays predictable for non-wedge failures.
     *
     * <p>Package-private (not private) so unit tests can drive the decision table directly without
     * having to script a full Bluetooth stack.
     */
    static long computeBackoffMs(int attempt, boolean everConnected, boolean wedgedMode) {
        if (wedgedMode) {
            // attempt == 3 is the *third* failed connect overall; if the wedge tripped at
            // attempt 2, this is the first wedge-mode retry → index 0 → 8 s.
            int wedgeIndex = Math.max(0, attempt - 3);
            if (wedgeIndex < LONG_BACKOFFS_MS.length) {
                return LONG_BACKOFFS_MS[wedgeIndex];
            }
            // Falls through to the standard curve for the final retries so MAX_RECONNECT_ATTEMPTS
            // still terminates the loop on schedule.
        }
        return everConnected ? reconnectBackoffMs(attempt) : initialConnectBackoffMs(attempt);
    }

    /**
     * B6: short, grep-friendly fully-qualified class name for the exception that ended the attempt.
     * Returns the simple name when {@code getName()} is empty (shouldn't happen for real exceptions
     * but defensive against synthetic ones in tests).
     */
    static String exceptionClassName(Throwable ex) {
        if (ex == null) {
            return "";
        }
        String name = ex.getClass().getName();
        if (name == null || name.isEmpty()) {
            return ex.getClass().getSimpleName();
        }
        return name;
    }

    /**
     * B6: render the first few stack frames of {@code ex} into a single newline-free string capped
     * at {@link #STACK_HEAD_MAX_CHARS}. Frames are joined with {@code " | "} so the field stays
     * readable in {@code jq}-style flat JSONL dumps. Returns the empty string for a null exception
     * or one with no stack trace (which a JVM can elide under heavy load).
     */
    static String stackHead(Throwable ex) {
        if (ex == null) {
            return "";
        }
        StackTraceElement[] frames = ex.getStackTrace();
        if (frames == null || frames.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int frameLimit = Math.min(STACK_HEAD_FRAMES, frames.length);
        for (int i = 0; i < frameLimit; i++) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(frames[i].toString());
            if (sb.length() >= STACK_HEAD_MAX_CHARS) {
                break;
            }
        }
        if (sb.length() > STACK_HEAD_MAX_CHARS) {
            sb.setLength(STACK_HEAD_MAX_CHARS);
        }
        return sb.toString();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
