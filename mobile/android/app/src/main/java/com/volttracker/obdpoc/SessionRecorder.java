package com.volttracker.obdpoc;

import static com.volttracker.obdpoc.ObdElmDecode.finishStatusFor;
import static com.volttracker.obdpoc.ObdElmDecode.nameForCommand;
import static com.volttracker.obdpoc.ObdElmDecode.pidForCommand;
import static com.volttracker.obdpoc.ObdElmDecode.safeMessage;
import static com.volttracker.obdpoc.ObdElmDecode.summarizeForStorage;

import android.util.Log;
import com.volttracker.obdpoc.data.ObdLocalStore;
import com.volttracker.obdpoc.location.FilteredLocation;
import com.volttracker.obdpoc.materialize.ChargeSession;
import com.volttracker.obdpoc.materialize.ChargeSessionMaterializer;
import com.volttracker.obdpoc.materialize.MaterializerInput;
import com.volttracker.obdpoc.materialize.Trip;
import com.volttracker.obdpoc.materialize.TripMaterializer;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Owns the per-session diagnostic record: the {@code .jsonl} field log, the SQLite
 * session/telemetry/event/PID rows, and the database session lifecycle. Extracted from {@link
 * ObdService} so the recording concern is isolated from the OBD polling loop.
 *
 * <p>Recording is diagnostic-only and never interrupts polling — database writes run on a dedicated
 * executor and swallow their own failures. The synchronized methods share the {@code lock} monitor
 * with {@code ObdService}'s command IO so session teardown cannot race a command in flight.
 */
final class SessionRecorder {

    private final Object lock;
    private final ObdSessionLog sessionLog;
    // telemetryExecutor: high-volume telemetry/event/observation writes. Failures are
    // intentionally swallowed — these rows are diagnostic-only, never block OBD polling.
    private final ExecutorService telemetryExecutor = Executors.newSingleThreadExecutor();
    // lifecycleExecutor: session lifecycle writes (closeSession / finalizeSession).
    // Failures are surfaced via Log.e AND a persist_failure status_events row, so a
    // failed finalize cannot silently leave a session marked active forever.
    private final ExecutorService lifecycleExecutor = Executors.newSingleThreadExecutor();
    private ObdLocalStore localStore;

    private long activeSessionId;
    private long activeStartedAtMs;
    private String activeMode = "";
    private String activeAdapterName = "";
    private String activeAddress = "";
    private String currentHeader = "";
    private String lastPersistedStatusKey = "";
    private long lastPersistedStatusAtMs;

    SessionRecorder(Object lock, ObdSessionLog sessionLog, ObdLocalStore localStore) {
        this.lock = lock;
        this.sessionLog = sessionLog;
        this.localStore = localStore;
    }

    long activeSessionId() {
        return activeSessionId;
    }

    String activeMode() {
        return activeMode;
    }

    String logFileName() {
        return sessionLog.fileName();
    }

    /** Opens a fresh {@code .jsonl} log and starts a database session row. */
    void openSession(String mode, String address, String adapterName, long startedAtMs) {
        synchronized (lock) {
            // Any still-open session is closed under its own adapter identity, not the new one.
            closeSession("", "", "", 0);
            lastPersistedStatusKey = "";
            lastPersistedStatusAtMs = 0L;
            sessionLog.open(mode);
            if (!sessionLog.isOpen()) {
                activeSessionId = 0L;
                return;
            }
            try {
                activeMode = mode == null ? "" : mode;
                activeAdapterName = adapterName == null ? "" : adapterName;
                activeAddress = address == null ? "" : address;
                activeStartedAtMs = startedAtMs;
                activeSessionId =
                        localStore == null
                                ? 0L
                                : localStore.startSession(
                                        activeMode, activeAddress, activeAdapterName, startedAtMs);
                logEvent(
                        "session_start",
                        "mode",
                        mode,
                        "adapter",
                        adapterName,
                        "address",
                        activeAddress);
            } catch (RuntimeException ex) {
                activeSessionId = 0L;
                android.util.Log.w(MainActivity.TAG, "SessionRecorder.startSession failed", ex);
                logEvent(
                        "session_start_error",
                        "reason",
                        ex.getClass().getSimpleName(),
                        "message",
                        String.valueOf(ex.getMessage()));
            }
        }
    }

    /** Finishes the database session row and closes the {@code .jsonl} log. */
    void closeSession(String state, String detail, String supportedPids, int sampleCount) {
        synchronized (lock) {
            long closingSessionId = activeSessionId;
            long closingStartedAtMs = activeStartedAtMs;
            String closingMode = activeMode;
            String closingAdapterName = activeAdapterName;
            String closingAddress = activeAddress;
            if (sessionLog.isOpen()) {
                logEvent("session_end");
            }
            sessionLog.close();
            if (closingSessionId > 0 && localStore != null) {
                final ObdLocalStore store = localStore;
                final String status = finishStatusFor(state);
                final long endedAtMs = System.currentTimeMillis();
                // Single transaction: marks the session ended AND upserts the adapter
                // summary atomically, so a crash between the two writes can't leave the
                // session row out of sync with the adapter-history row. Lifecycle writes
                // run on lifecycleExecutor so a failure is observable (Log.e + status_events
                // persist_failure row) rather than silently swallowed.
                //
                // Before finalize, we drain telemetryExecutor — the two executors don't share
                // ordering, so a finalize submitted right after a burst of telemetry writes
                // could otherwise run before they land. Materializers read those rows back, so
                // a race here would yield "trips" that are missing the last few seconds of
                // telemetry.
                lifecycleAsync(
                        () -> {
                            awaitTelemetryDrain();
                            store.finalizeSession(
                                    closingSessionId,
                                    status,
                                    endedAtMs,
                                    supportedPids,
                                    closingAddress,
                                    closingAdapterName,
                                    closingMode,
                                    sampleCount,
                                    detail);
                            materializeIfEnabled(
                                    store,
                                    closingSessionId,
                                    closingStartedAtMs,
                                    endedAtMs,
                                    closingMode);
                        },
                        closingSessionId,
                        "finalizeSession");
            }
            activeSessionId = 0L;
            activeStartedAtMs = 0L;
            activeMode = "";
            activeAdapterName = "";
            activeAddress = "";
            currentHeader = "";
        }
    }

    /**
     * Runs the trip / charge-session materializers on the just-closed session and persists their
     * output. Demo sessions are skipped — demo telemetry is intentionally never written, so any
     * "trip" derived from it would be empty anyway. A failure here records a {@code
     * materialize_failure} status event but does not propagate: the session is already finalized
     * and we don't want to retry a derivation that will keep failing for the same reason.
     */
    private void materializeIfEnabled(
            ObdLocalStore store, long sessionId, long startedAtMs, long closedAtMs, String mode) {
        if (!BuildFlags.MATERIALIZE_SESSIONS) {
            return;
        }
        if (ObdLocalStore.MODE_DEMO.equals(mode)) {
            return;
        }
        try {
            MaterializerInput input = new MaterializerInput(sessionId, startedAtMs, closedAtMs);
            List<Trip> tripList = TripMaterializer.materialize(input, store);
            store.persistTrips(sessionId, tripList);
            List<ChargeSession> chargeSessions =
                    ChargeSessionMaterializer.materialize(input, store);
            store.persistChargeSessions(sessionId, chargeSessions);
        } catch (RuntimeException ex) {
            Log.w(MainActivity.TAG, "session materialization failed", ex);
            try {
                store.recordStatus(
                        sessionId,
                        "materialize_failure",
                        ex.getClass().getSimpleName(),
                        false,
                        null);
            } catch (RuntimeException ignored) {
                // Persisting the failure event is best-effort.
            }
        }
    }

    void logCommand(String command, long timeoutMs, long durationMs, String response) {
        synchronized (lock) {
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
    }

    void logError(String type, Exception ex) {
        synchronized (lock) {
            JSONObject payload = new JSONObject();
            try {
                payload.put("errorType", type);
                payload.put("exception", ex.getClass().getName());
                payload.put("message", safeMessage(ex));
            } catch (JSONException ignored) {
            }
            logJson("error", payload);
        }
    }

    void logEvent(String event, String... pairs) {
        synchronized (lock) {
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
    }

    void logJson(String type, JSONObject payload) {
        synchronized (lock) {
            sessionLog.write(type, payload);
            if (!"telemetry".equals(type) && !"status".equals(type)) {
                persistEvent(type, payload);
            }
        }
    }

    void persistTelemetry(JSONObject payload) {
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

    void persistStatus(String state, String detail, boolean blocked, JSONObject payload) {
        final long sessionId = activeSessionId;
        if (sessionId <= 0 || payload == null || localStore == null) {
            return;
        }
        if (shouldThrottleStatus(state, detail, blocked)) {
            return;
        }
        persistAsync(() -> localStore.recordStatus(sessionId, state, detail, blocked, payload));
    }

    // Each accepted fix is persisted straight to the route on the GPS callback (not the OBD
    // poll), so the trace keeps recording across reconnects and idle OBD periods.
    void persistLocation(FilteredLocation location) {
        final long sessionId = activeSessionId;
        if (sessionId <= 0
                || localStore == null
                || location == null
                || ObdLocalStore.MODE_DEMO.equals(activeMode)) {
            return;
        }
        persistAsync(
                () ->
                        localStore.recordLocationSample(
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

    /** Runs {@code task} on the recording executor — off the OBD poll and main threads. */
    void runAsync(Runnable task) {
        persistAsync(task);
    }

    /** Drains pending database writes and shuts both recording executors down. */
    void shutdown() {
        telemetryExecutor.shutdown();
        lifecycleExecutor.shutdown();
        try {
            telemetryExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        try {
            lifecycleExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean shouldThrottleStatus(String state, String detail, boolean blocked) {
        synchronized (lock) {
            long now = System.currentTimeMillis();
            String key =
                    (state == null ? "" : state)
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
    }

    private void persistEvent(String type, JSONObject payload) {
        final long sessionId = activeSessionId;
        if (sessionId <= 0 || payload == null || localStore == null) {
            return;
        }
        persistAsync(
                () -> {
                    String detail =
                            payload.optString(
                                    "detail",
                                    payload.optString(
                                            "message",
                                            payload.optString(
                                                    "event", payload.optString("command", ""))));
                    localStore.recordEvent(
                            sessionId,
                            type,
                            payload.optString("state", ""),
                            detail,
                            false,
                            payload);
                });
    }

    private void persistPidObservation(
            String command,
            String header,
            long observedAtMs,
            long timeoutMs,
            long durationMs,
            String response) {
        final long sessionId = activeSessionId;
        if (sessionId <= 0 || localStore == null) {
            return;
        }
        String safeCommand = command == null ? "" : command.trim().toUpperCase(Locale.US);
        String summary = summarizeForStorage(safeCommand, response);
        ObdProtocol.ParsedPidValue parsed = ObdProtocol.parseKnownValue(safeCommand, response);
        List<ObdProtocol.DiagnosticTroubleCode> diagnosticCodes =
                ObdProtocol.parseDiagnosticTroubleCodes(safeCommand, response, header);
        persistAsync(
                () -> {
                    JSONObject payload = new JSONObject();
                    try {
                        payload.put("observedAtMs", observedAtMs);
                        payload.put("command", safeCommand);
                        payload.put("header", header == null ? "" : header);
                        payload.put("pid", pidForCommand(safeCommand));
                        payload.put(
                                "name", parsed == null ? nameForCommand(safeCommand) : parsed.name);
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
                    for (ObdProtocol.DiagnosticTroubleCode code : diagnosticCodes) {
                        localStore.recordDiagnosticCode(
                                sessionId, diagnosticCodeJson(code, observedAtMs, safeCommand));
                    }
                });
    }

    private static JSONObject diagnosticCodeJson(
            ObdProtocol.DiagnosticTroubleCode code, long observedAtMs, String command) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("seenAtMs", observedAtMs);
            payload.put("observedAtMs", observedAtMs);
            payload.put("command", command == null ? "" : command);
            payload.put("dtc", code.code);
            payload.put("status", code.status);
            payload.put("statusLabel", code.statusLabel);
            payload.put("moduleKey", code.moduleKey);
            payload.put("moduleName", code.moduleName);
            payload.put("header", code.header);
            payload.put("rawResponse", code.rawResponse);
        } catch (JSONException ignored) {
            // Local values are safe.
        }
        return payload;
    }

    private void persistAsync(Runnable task) {
        try {
            telemetryExecutor.execute(
                    () -> {
                        try {
                            task.run();
                        } catch (RuntimeException ignored) {
                            // Persistence is diagnostic; never interrupt OBD polling for it.
                        }
                    });
        } catch (RejectedExecutionException ignored) {
        }
    }

    // Lifecycle writes (session finalize) get visibility on failure: a Log.e plus a
    // best-effort status_events row with kind=persist_failure so the next launch can see
    // that the session was supposed to be finalized but wasn't.
    //
    // If the lifecycle executor itself rejects the task (e.g. shutdown raced with a final
    // close), we fall back to a synchronous in-thread run: dropping the finalize silently
    // would leave the session row marked active forever.
    private void lifecycleAsync(Runnable task, long sessionId, String op) {
        try {
            lifecycleExecutor.execute(
                    () -> {
                        try {
                            task.run();
                        } catch (RuntimeException ex) {
                            Log.e(MainActivity.TAG, "session lifecycle persist failed", ex);
                            recordPersistFailure(sessionId, op, ex);
                        }
                    });
        } catch (RejectedExecutionException ex) {
            Log.e(MainActivity.TAG, "lifecycle executor rejected " + op + "; running inline", ex);
            try {
                task.run();
            } catch (RuntimeException inner) {
                Log.e(MainActivity.TAG, "inline lifecycle fallback failed for " + op, inner);
                recordPersistFailure(sessionId, op, inner);
            }
        }
    }

    /**
     * Submits a no-op to {@link #telemetryExecutor} and waits up to two seconds for it to run.
     * Because the executor is single-threaded with FIFO ordering, by the time our marker task runs
     * every previously-submitted telemetry write has already finished. Used before
     * finalize/materialize so those code paths see the full session in the database.
     */
    private void awaitTelemetryDrain() {
        try {
            Future<?> marker = telemetryExecutor.submit(() -> {});
            marker.get(2, TimeUnit.SECONDS);
        } catch (RejectedExecutionException ex) {
            // Executor already shut down — nothing left to drain.
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException ex) {
            Log.w(MainActivity.TAG, "telemetry drain marker failed", ex.getCause());
        } catch (TimeoutException ex) {
            Log.w(
                    MainActivity.TAG,
                    "telemetry drain timed out after 2s; proceeding with finalize",
                    ex);
        }
    }

    // Best-effort: this itself can throw if the database is the source of the trouble,
    // so we catch and log without escalating.
    private void recordPersistFailure(long sessionId, String op, RuntimeException cause) {
        if (localStore == null) {
            return;
        }
        try {
            JSONObject payload = new JSONObject();
            try {
                payload.put("op", op);
                payload.put("exception", cause.getClass().getName());
                payload.put("message", safeMessage(cause));
                payload.put("updatedAt", System.currentTimeMillis());
            } catch (JSONException ignored) {
                // Local values are safe.
            }
            localStore.recordEvent(sessionId, "persist_failure", "", op, true, payload);
        } catch (RuntimeException ex) {
            Log.e(MainActivity.TAG, "recording persist_failure also failed", ex);
        }
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
}
