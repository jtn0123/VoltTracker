package com.volttracker.obdpoc;

import static com.volttracker.obdpoc.ObdElmDecode.finishStatusFor;
import static com.volttracker.obdpoc.ObdElmDecode.nameForCommand;
import static com.volttracker.obdpoc.ObdElmDecode.pidForCommand;
import static com.volttracker.obdpoc.ObdElmDecode.safeMessage;
import static com.volttracker.obdpoc.ObdElmDecode.summarizeForStorage;

import android.util.Log;
import com.volttracker.obdpoc.data.ObdLocalStore;
import com.volttracker.obdpoc.location.FilteredLocation;
import java.util.List;
import java.util.Locale;
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

    // Queue caps retained as the contract some tests assert against; the actual queues now live
    // in ObdPersistenceWorker and are sized from these via that class's own copies.
    static final int TELEMETRY_QUEUE_CAPACITY = ObdPersistenceWorker.TELEMETRY_QUEUE_CAPACITY;
    static final int LIFECYCLE_QUEUE_CAPACITY = ObdPersistenceWorker.LIFECYCLE_QUEUE_CAPACITY;

    private final Object lock;
    private final ObdSessionLog sessionLog;
    // Appended-to on session start/end so the dashboard can render last-connected /
    // adapter-health pills without parsing every per-session JSONL. Null in legacy tests.
    private final SessionSummaryStore summaryStore;
    // Snapshot source for the system_snapshot event emitted at session start.
    // Held as a Context-bearing Supplier so unit tests can wire it as null.
    private final SystemSnapshotSource snapshotSource;
    // Owns the async-persistence executors, bounded queues, backpressure policy, dropped-task
    // counter, and drain/shutdown plumbing. SessionRecorder decides WHAT to persist (reading
    // mutable session state under lock); the worker decides HOW it runs.
    private final ObdPersistenceWorker worker;
    private ObdLocalStore localStore;

    private long activeSessionId;
    private long activeStartedAtMs;
    private String activeMode = "";
    private String activeAdapterName = "";
    private String activeAddress = "";
    private String currentHeader = "";
    private String lastPersistedStatusKey = "";
    private long lastPersistedStatusAtMs;

    // Content-independent status-write rate cap. The content+time dedupe above
    // (lastPersistedStatusKey) only suppresses *identical* status events; a flapping adapter
    // that alternates between two distinct details makes every key look "new", so the dedupe
    // never engages and the events table bloats under a bad connection. This rolling-window ring
    // caps the absolute number of status writes per window regardless of how the detail varies.
    // Sized so genuine state transitions (a handful per window) pass freely while a tight
    // flapping loop is throttled. Guarded by lock (same monitor as shouldThrottleStatus).
    static final int STATUS_RATE_MAX_PER_WINDOW = 12;
    static final long STATUS_RATE_WINDOW_MS = 5000L;
    private final long[] recentStatusWriteMs = new long[STATUS_RATE_MAX_PER_WINDOW];
    private int recentStatusWriteCount;
    private int recentStatusWriteHead;

    SessionRecorder(Object lock, ObdSessionLog sessionLog, ObdLocalStore localStore) {
        this(lock, sessionLog, localStore, null, null);
    }

    SessionRecorder(
            Object lock,
            ObdSessionLog sessionLog,
            ObdLocalStore localStore,
            SessionSummaryStore summaryStore,
            SystemSnapshotSource snapshotSource) {
        this.lock = lock;
        this.sessionLog = sessionLog;
        this.localStore = localStore;
        this.summaryStore = summaryStore;
        this.snapshotSource = snapshotSource;
        this.worker = new ObdPersistenceWorker(localStore);
    }

    /** Snapshot supplier so {@link ObdService} can hand the {@link android.content.Context} in. */
    interface SystemSnapshotSource {
        JSONObject collect();
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
            recentStatusWriteCount = 0;
            recentStatusWriteHead = 0;
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
                // Rollup row + diagnostic snapshot stamped at start. These are optional
                // observability hooks — a failure here must not abort the core session start,
                // since the .jsonl session row above is the source of truth for the run.
                if (summaryStore != null) {
                    try {
                        summaryStore.recordStart(startedAtMs, activeAdapterName, activeAddress);
                    } catch (RuntimeException ex) {
                        android.util.Log.w(MainActivity.TAG, "summaryStore.recordStart failed", ex);
                    }
                }
                if (snapshotSource != null) {
                    try {
                        JSONObject snapshot = snapshotSource.collect();
                        if (snapshot != null) {
                            logJson("system_snapshot", snapshot);
                        }
                    } catch (RuntimeException ex) {
                        android.util.Log.w(MainActivity.TAG, "system_snapshot capture failed", ex);
                    }
                }
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
        closeSession(state, detail, supportedPids, sampleCount, null);
    }

    /**
     * Same as {@link #closeSession(String, String, String, int)} but accepts the fine-grained
     * {@link FailureClass} from {@link ObdService#lastFailureClass()} so the session summary row
     * carries the classifier output (e.g. {@code instant_drop}) instead of the coarse state string
     * ({@code "error"}).
     */
    void closeSession(
            String state,
            String detail,
            String supportedPids,
            int sampleCount,
            FailureClass sessionFailureClass) {
        synchronized (lock) {
            long closingSessionId = activeSessionId;
            long closingStartedAtMs = activeStartedAtMs;
            String closingMode = activeMode;
            String closingAdapterName = activeAdapterName;
            String closingAddress = activeAddress;
            if (sessionLog.isOpen()) {
                logEvent("session_end");
                // Append a summary row for the dashboard's last-connected pill.
                if (summaryStore != null) {
                    try {
                        summaryStore.recordEnd(
                                System.currentTimeMillis(),
                                outcomeFor(state, sampleCount),
                                sampleCount,
                                failureClassFor(state, sessionFailureClass));
                    } catch (RuntimeException ex) {
                        android.util.Log.w(MainActivity.TAG, "summaryStore.recordEnd failed", ex);
                    }
                }
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
                final long droppedBeforeClose = worker.drainDroppedTelemetryCount();
                worker.submitLifecycle(
                        () -> {
                            worker.awaitTelemetryDrain();
                            if (droppedBeforeClose > 0) {
                                JSONObject droppedPayload = new JSONObject();
                                try {
                                    droppedPayload.put("dropped", droppedBeforeClose);
                                } catch (JSONException ignored) {
                                    // Local numeric value is safe.
                                }
                                store.recordEvent(
                                        closingSessionId,
                                        "telemetry_dropped",
                                        "persist",
                                        "Dropped "
                                                + droppedBeforeClose
                                                + " queued telemetry writes before finalize.",
                                        false,
                                        droppedPayload);
                            }
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
            store.materializeSession(sessionId, startedAtMs, closedAtMs);
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
            // Error rows fsync to disk so a process death between the JSON
            // appearing in the log and the OS flushing the page cache can't lose the most
            // diagnostic line the file would have had.
            if ("error".equals(type)) {
                sessionLog.writeDurable(type, payload);
            } else {
                sessionLog.write(type, payload);
            }
            if ("telemetry".equals(type) || "status".equals(type)) {
                // These types have their own dedicated write paths (recordTelemetry /
                // recordStatus); the generic event row would be a duplicate.
                return;
            }
            if ("command".equals(type) && !BuildFlags.TRACE_COMMANDS_TO_STATUS_EVENTS) {
                // pid_observations + the .jsonl file already capture every OBD command; the
                // status_events copy is gated off to keep the table from bloating at ≈8 rows/s
                // during normal polling. Flip the build flag on to debug a decode regression.
                return;
            }
            persistEvent(type, payload);
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
        worker.submitTelemetry(() -> localStore.recordTelemetry(sessionId, payload));
    }

    void persistStatus(String state, String detail, boolean blocked, JSONObject payload) {
        final long sessionId = activeSessionId;
        if (sessionId <= 0 || payload == null || localStore == null) {
            return;
        }
        if (shouldThrottleStatus(state, detail, blocked)) {
            return;
        }
        worker.submitTelemetry(
                () -> localStore.recordStatus(sessionId, state, detail, blocked, payload));
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
        worker.submitTelemetry(
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
        worker.submitTelemetry(task);
    }

    /** Drains pending database writes and shuts both recording executors down. */
    void shutdown() {
        worker.shutdown();
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
            // Fast path: identical content within the window is the common repeat (a stable
            // connection re-publishing the same status) and is suppressed without touching the
            // rate ring — those repeats never reach persistence so they don't count against it.
            if (key.equals(lastPersistedStatusKey) && now - lastPersistedStatusAtMs < 5000L) {
                return true;
            }
            // Content-independent cap: a flapping adapter alternating between distinct details
            // bypasses the key dedupe entirely (every key looks new), so cap the absolute number
            // of status writes per rolling window regardless of content variation.
            if (countRecentStatusWrites(now) >= STATUS_RATE_MAX_PER_WINDOW) {
                return true;
            }
            recordStatusWrite(now);
            lastPersistedStatusKey = key;
            lastPersistedStatusAtMs = now;
            return false;
        }
    }

    /** Counts accepted status writes within the trailing {@link #STATUS_RATE_WINDOW_MS}. */
    private int countRecentStatusWrites(long now) {
        int live = 0;
        for (int i = 0; i < recentStatusWriteCount; i++) {
            int idx = (recentStatusWriteHead + i) % recentStatusWriteMs.length;
            if (now - recentStatusWriteMs[idx] < STATUS_RATE_WINDOW_MS) {
                live++;
            }
        }
        return live;
    }

    /** Appends {@code now} to the rolling ring, overwriting the oldest entry when full. */
    private void recordStatusWrite(long now) {
        int tail = (recentStatusWriteHead + recentStatusWriteCount) % recentStatusWriteMs.length;
        if (recentStatusWriteCount < recentStatusWriteMs.length) {
            recentStatusWriteMs[tail] = now;
            recentStatusWriteCount++;
        } else {
            recentStatusWriteMs[recentStatusWriteHead] = now;
            recentStatusWriteHead = (recentStatusWriteHead + 1) % recentStatusWriteMs.length;
        }
    }

    private void persistEvent(String type, JSONObject payload) {
        final long sessionId = activeSessionId;
        if (sessionId <= 0 || payload == null || localStore == null) {
            return;
        }
        worker.submitTelemetry(
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
        worker.submitTelemetry(
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

    // The rollup file groups every session into one of three outcomes so the
    // dashboard can render its health pill without re-deriving on the JS side.
    private static String outcomeFor(String state, int sampleCount) {
        if ("blocked".equals(state) || "error".equals(state)) {
            return SessionSummary.OUTCOME_FAILED;
        }
        if ("connected".equals(state)
                || "scanning".equals(state)
                || "scan-complete".equals(state)
                || sampleCount > 0) {
            return SessionSummary.OUTCOME_SUCCESS;
        }
        return SessionSummary.OUTCOME_ABORTED;
    }

    // Failure class is only meaningful for a failed session. When ObdService threads in the
    // service-level lastFailureClass via the 5-arg closeSession overload, we record its
    // wireName() (e.g. "instant_drop") so the dashboard's adapter-health pill can distinguish
    // wedged-adapter from car-asleep retroactively. Falls back to the coarse state string for
    // legacy callers / tests that use the 4-arg closeSession.
    private static String failureClassFor(String state, FailureClass sessionFailureClass) {
        if (sessionFailureClass != null) {
            return sessionFailureClass.wireName();
        }
        return failureClassFor(state);
    }

    private static String failureClassFor(String state) {
        if ("blocked".equals(state) || "error".equals(state)) {
            return state;
        }
        return null;
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
