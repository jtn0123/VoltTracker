package com.volttracker.obdpoc

import android.util.Log
import com.volttracker.obdpoc.data.ObdLocalStore
import com.volttracker.obdpoc.location.FilteredLocation
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

/**
 * Owns the per-session diagnostic record: JSONL field log, SQLite rows, and session lifecycle.
 */
class SessionRecorder {
    private val lock: Any
    private val sessionLog: ObdSessionLog
    private val summaryStore: SessionSummaryStore?
    private val snapshotSource: SystemSnapshotSource?
    private val worker: ObdPersistenceWorker
    private var localStore: ObdLocalStore?

    private var activeSessionId = 0L
    private var activeStartedAtMs = 0L
    private var activeMode = ""
    private var activeAdapterName = ""
    private var activeAddress = ""
    private var currentHeader = ""
    private var lastPersistedStatusKey = ""
    private var lastPersistedStatusAtMs = 0L

    /**
     * True once [SessionSummaryStore.recordStart] succeeded for the active session, so closeSession
     * can write the matching end row even when the per-session `.jsonl` log failed to open —
     * storage-failure sessions are exactly the ones the troubleshooter needs summaries for.
     */
    private var summaryStartRecorded = false

    private val recentStatusWriteMs = LongArray(STATUS_RATE_MAX_PER_WINDOW)
    private var recentStatusWriteCount = 0
    private var recentStatusWriteHead = 0

    constructor(
        lock: Any,
        sessionLog: ObdSessionLog,
        localStore: ObdLocalStore?,
    ) : this(lock, sessionLog, localStore, null, null)

    constructor(
        lock: Any,
        sessionLog: ObdSessionLog,
        localStore: ObdLocalStore?,
        summaryStore: SessionSummaryStore?,
        snapshotSource: SystemSnapshotSource?,
    ) {
        this.lock = lock
        this.sessionLog = sessionLog
        this.localStore = localStore
        this.summaryStore = summaryStore
        this.snapshotSource = snapshotSource
        worker = ObdPersistenceWorker(localStore)
    }

    /** Snapshot supplier so [ObdService] can hand the Android context in. */
    fun interface SystemSnapshotSource {
        fun collect(): JSONObject?
    }

    fun activeSessionId(): Long = activeSessionId

    fun activeMode(): String = activeMode

    fun logFileName(): String = sessionLog.fileName() ?: ""

    /** Opens a fresh `.jsonl` log and starts a database session row. */
    fun openSession(
        mode: String?,
        address: String?,
        adapterName: String?,
        startedAtMs: Long,
    ) {
        synchronized(lock) {
            closeSession("", "", "", 0)
            lastPersistedStatusKey = ""
            lastPersistedStatusAtMs = 0L
            recentStatusWriteCount = 0
            recentStatusWriteHead = 0
            try {
                activeMode = mode ?: ""
                activeAdapterName = adapterName ?: ""
                activeAddress = address ?: ""
                activeStartedAtMs = startedAtMs
                activeSessionId =
                    localStore?.startSession(activeMode, activeAddress, activeAdapterName, startedAtMs) ?: 0L
                sessionLog.open(mode ?: "")
                if (!sessionLog.isOpen()) {
                    logEvent("session_log_unavailable", "mode", activeMode)
                }
                logEvent("session_start", "mode", mode, "adapter", adapterName, "address", activeAddress)
                if (summaryStore != null && ObdLocalStore.MODE_DEMO != mode) {
                    try {
                        summaryStore.recordStart(startedAtMs, activeAdapterName, activeAddress)
                        summaryStartRecorded = true
                    } catch (ex: RuntimeException) {
                        Log.w(MainActivity.TAG, "summaryStore.recordStart failed", ex)
                    }
                }
                if (snapshotSource != null) {
                    try {
                        val snapshot = snapshotSource.collect()
                        if (snapshot != null) {
                            logJson("system_snapshot", snapshot)
                        }
                    } catch (ex: RuntimeException) {
                        Log.w(MainActivity.TAG, "system_snapshot capture failed", ex)
                    }
                }
            } catch (ex: RuntimeException) {
                activeSessionId = 0L
                Log.w(MainActivity.TAG, "SessionRecorder.startSession failed", ex)
                logEvent(
                    "session_start_error",
                    "reason",
                    ex.javaClass.simpleName,
                    "message",
                    ex.message.toString(),
                )
            }
        }
    }

    /** Finishes the database session row and closes the `.jsonl` log. */
    fun closeSession(
        state: String?,
        detail: String?,
        supportedPids: String?,
        sampleCount: Int,
    ) {
        closeSession(state, detail, supportedPids, sampleCount, null)
    }

    fun closeSession(
        state: String?,
        detail: String?,
        supportedPids: String?,
        sampleCount: Int,
        sessionFailureClass: FailureClass?,
    ) {
        synchronized(lock) {
            val closingSessionId = activeSessionId
            val closingStartedAtMs = activeStartedAtMs
            val closingMode = activeMode
            val closingAdapterName = activeAdapterName
            val closingAddress = activeAddress
            if (sessionLog.isOpen()) {
                logEvent("session_end")
            }
            // The summary end row is written whenever a start row was recorded — even when the
            // per-session .jsonl never opened (storage failure). Only the "session_end" detail-log
            // line above stays conditional on the log being open.
            if (summaryStartRecorded && summaryStore != null && ObdLocalStore.MODE_DEMO != closingMode) {
                try {
                    summaryStore.recordEnd(
                        System.currentTimeMillis(),
                        outcomeFor(state, sampleCount),
                        sampleCount,
                        failureClassFor(state, sessionFailureClass),
                    )
                } catch (ex: RuntimeException) {
                    Log.w(MainActivity.TAG, "summaryStore.recordEnd failed", ex)
                }
            }
            summaryStartRecorded = false
            sessionLog.close()
            val store = localStore
            if (closingSessionId > 0 && store != null) {
                val status = ObdElmDecode.finishStatusFor(state)
                val endedAtMs = System.currentTimeMillis()
                val droppedBeforeClose = worker.drainDroppedTelemetryCount()
                worker.submitLifecycle(
                    {
                        worker.awaitTelemetryDrain()
                        if (droppedBeforeClose > 0) {
                            val droppedPayload = JSONObject()
                            try {
                                droppedPayload.put("dropped", droppedBeforeClose)
                            } catch (ignored: JSONException) {
                                // Local numeric value is safe.
                            }
                            store.recordEvent(
                                closingSessionId,
                                "telemetry_dropped",
                                "persist",
                                "Dropped $droppedBeforeClose queued telemetry writes before finalize.",
                                false,
                                droppedPayload,
                            )
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
                            detail,
                        )
                        materializeIfEnabled(store, closingSessionId, closingStartedAtMs, endedAtMs, closingMode)
                    },
                    closingSessionId,
                    "finalizeSession",
                )
            }
            activeSessionId = 0L
            activeStartedAtMs = 0L
            activeMode = ""
            activeAdapterName = ""
            activeAddress = ""
            currentHeader = ""
        }
    }

    private fun materializeIfEnabled(
        store: ObdLocalStore,
        sessionId: Long,
        startedAtMs: Long,
        closedAtMs: Long,
        mode: String?,
    ) {
        if (!BuildFlags.MATERIALIZE_SESSIONS || ObdLocalStore.MODE_DEMO == mode) {
            return
        }
        try {
            store.materializeSession(sessionId, startedAtMs, closedAtMs)
        } catch (ex: RuntimeException) {
            Log.w(MainActivity.TAG, "session materialization failed", ex)
            try {
                store.recordStatus(sessionId, "materialize_failure", ex.javaClass.simpleName, false, null)
            } catch (ignored: RuntimeException) {
                // Persisting the failure event is best-effort.
            }
        }
    }

    fun logCommand(
        command: String?,
        timeoutMs: Long,
        durationMs: Long,
        response: String?,
        truncatedOnDeadline: Boolean = false,
    ) {
        synchronized(lock) {
            val payload = JSONObject()
            val observedAtMs = System.currentTimeMillis()
            val header = currentHeader
            try {
                payload.put("command", command)
                payload.put("header", header)
                payload.put("timeoutMs", timeoutMs)
                payload.put("durationMs", durationMs)
                payload.put("response", ObdElmDecode.summarizeForStorage(command, response))
                payload.put("gotPrompt", response != null && response.indexOf('>') >= 0)
                payload.put("truncatedOnDeadline", truncatedOnDeadline)
                if (truncatedOnDeadline) {
                    payload.put("warning", "Response ended at the command deadline before the ELM prompt arrived.")
                }
                payload.put("empty", response == null || ObdProtocol.summarize(response).isEmpty())
                payload.put("observedAtMs", observedAtMs)
            } catch (ignored: JSONException) {
            }
            logJson("command", payload)
            persistPidObservation(command, header, observedAtMs, timeoutMs, durationMs, response)
            updateHeaderState(command)
        }
    }

    fun logError(
        type: String?,
        ex: Exception,
    ) {
        synchronized(lock) {
            val payload = JSONObject()
            try {
                payload.put("errorType", type)
                payload.put("exception", ex.javaClass.name)
                payload.put("message", ObdElmDecode.safeMessage(ex))
            } catch (ignored: JSONException) {
            }
            logJson("error", payload)
        }
    }

    fun logEvent(
        event: String?,
        vararg pairs: String?,
    ) {
        synchronized(lock) {
            val payload = JSONObject()
            try {
                payload.put("event", event)
                var i = 0
                while (i + 1 < pairs.size) {
                    payload.put(pairs[i] ?: "", pairs[i + 1])
                    i += 2
                }
            } catch (ignored: JSONException) {
            }
            logJson("event", payload)
        }
    }

    fun logJson(
        type: String?,
        payload: JSONObject?,
    ) {
        synchronized(lock) {
            val safeType = type ?: ""
            if ("error" == safeType) {
                sessionLog.writeDurable(safeType, payload ?: JSONObject())
            } else {
                sessionLog.write(safeType, payload ?: JSONObject())
            }
            if ("telemetry" == type || "status" == type) {
                return
            }
            if ("command" == type && !BuildFlags.TRACE_COMMANDS_TO_STATUS_EVENTS) {
                return
            }
            persistEvent(type, payload)
        }
    }

    fun persistTelemetry(payload: JSONObject?) {
        val sessionId = activeSessionId
        val store = localStore
        if (sessionId <= 0 || payload == null || store == null) {
            return
        }
        if (ObdLocalStore.MODE_DEMO == activeMode) {
            return
        }
        worker.submitTelemetry {
            store.recordTelemetry(sessionId, payload)
        }
    }

    fun persistStatus(
        state: String?,
        detail: String?,
        blocked: Boolean,
        payload: JSONObject?,
    ) {
        val sessionId = activeSessionId
        val store = localStore
        if (sessionId <= 0 || payload == null || store == null) {
            return
        }
        if (shouldThrottleStatus(state, detail, blocked)) {
            return
        }
        worker.submitTelemetry {
            store.recordStatus(sessionId, state, detail, blocked, payload)
        }
    }

    fun persistLocation(location: FilteredLocation?) {
        val sessionId = activeSessionId
        val store = localStore
        if (sessionId <= 0 || store == null || location == null || ObdLocalStore.MODE_DEMO == activeMode) {
            return
        }
        worker.submitTelemetry {
            store.recordLocationSample(
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
                location.elapsedRealtimeNanos,
            )
        }
    }

    /** Runs `task` on the recording executor, off the OBD poll and main threads. */
    fun runAsync(task: Runnable) {
        worker.submitTelemetry(task)
    }

    /** Drains pending database writes and shuts both recording executors down. */
    fun shutdown() {
        worker.shutdown()
    }

    fun drainFailedTelemetryCount(): Long = worker.drainFailedTelemetryCount()

    private fun shouldThrottleStatus(
        state: String?,
        detail: String?,
        blocked: Boolean,
    ): Boolean =
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val key = "${state ?: ""}|${detail ?: ""}|$blocked"
            if (key == lastPersistedStatusKey && now - lastPersistedStatusAtMs < 5000L) {
                true
            } else if (countRecentStatusWrites(now) >= STATUS_RATE_MAX_PER_WINDOW) {
                true
            } else {
                recordStatusWrite(now)
                lastPersistedStatusKey = key
                lastPersistedStatusAtMs = now
                false
            }
        }

    private fun countRecentStatusWrites(now: Long): Int {
        var live = 0
        for (i in 0 until recentStatusWriteCount) {
            val idx = (recentStatusWriteHead + i) % recentStatusWriteMs.size
            if (now - recentStatusWriteMs[idx] < STATUS_RATE_WINDOW_MS) {
                live++
            }
        }
        return live
    }

    private fun recordStatusWrite(now: Long) {
        val tail = (recentStatusWriteHead + recentStatusWriteCount) % recentStatusWriteMs.size
        if (recentStatusWriteCount < recentStatusWriteMs.size) {
            recentStatusWriteMs[tail] = now
            recentStatusWriteCount++
        } else {
            recentStatusWriteMs[recentStatusWriteHead] = now
            recentStatusWriteHead = (recentStatusWriteHead + 1) % recentStatusWriteMs.size
        }
    }

    private fun persistEvent(
        type: String?,
        payload: JSONObject?,
    ) {
        val sessionId = activeSessionId
        val store = localStore
        if (sessionId <= 0 || payload == null || store == null) {
            return
        }
        worker.submitTelemetry {
            val detail =
                payload.optString(
                    "detail",
                    payload.optString("message", payload.optString("event", payload.optString("command", ""))),
                )
            store.recordEvent(sessionId, type, payload.optString("state", ""), detail, false, payload)
        }
    }

    private fun persistPidObservation(
        command: String?,
        header: String?,
        observedAtMs: Long,
        timeoutMs: Long,
        durationMs: Long,
        response: String?,
    ) {
        val sessionId = activeSessionId
        val store = localStore
        if (sessionId <= 0 || store == null) {
            return
        }
        val safeCommand = command?.trim()?.uppercase(Locale.US) ?: ""
        val summary = ObdElmDecode.summarizeForStorage(safeCommand, response)
        val parsed = ObdProtocol.parseKnownValue(safeCommand, response)
        val diagnosticCodes = ObdProtocol.parseDiagnosticTroubleCodes(safeCommand, response, header)
        worker.submitTelemetry {
            val payload = JSONObject()
            try {
                payload.put("observedAtMs", observedAtMs)
                payload.put("command", safeCommand)
                payload.put("header", header ?: "")
                payload.put("pid", ObdElmDecode.pidForCommand(safeCommand))
                payload.put("name", parsed?.name ?: ObdElmDecode.nameForCommand(safeCommand))
                if (parsed != null) {
                    payload.put("valueText", parsed.valueText)
                    if (parsed.valueNumeric != null) {
                        payload.put("valueNumeric", parsed.valueNumeric.toDouble())
                    }
                    payload.put("unit", parsed.unit)
                }
                payload.put("rawRequest", safeCommand)
                payload.put("rawResponse", summary)
                payload.put("gotPrompt", response != null && response.indexOf('>') >= 0)
                payload.put("timeoutMs", timeoutMs)
                payload.put("durationMs", durationMs)
            } catch (ignored: JSONException) {
                // Local values are safe.
            }
            store.recordPidObservation(sessionId, payload, observedAtMs)
            for (code in diagnosticCodes) {
                store.recordDiagnosticCode(sessionId, diagnosticCodeJson(code, observedAtMs, safeCommand))
            }
        }
    }

    private fun updateHeaderState(command: String?) {
        if (command == null) {
            return
        }
        val clean = command.trim().uppercase(Locale.US)
        if (clean.startsWith("ATSH") && clean.length > 4) {
            currentHeader = clean.substring(4)
        } else if ("ATZ" == clean || "ATD" == clean || "ATPC" == clean) {
            currentHeader = ""
        }
    }

    companion object {
        const val TELEMETRY_QUEUE_CAPACITY: Int = ObdPersistenceWorker.TELEMETRY_QUEUE_CAPACITY
        const val LIFECYCLE_QUEUE_CAPACITY: Int = ObdPersistenceWorker.LIFECYCLE_QUEUE_CAPACITY
        const val STATUS_RATE_MAX_PER_WINDOW: Int = 12
        const val STATUS_RATE_WINDOW_MS: Long = 5000L

        @JvmStatic
        fun diagnosticCodeJson(
            code: ObdProtocol.DiagnosticTroubleCode,
            observedAtMs: Long,
            command: String?,
        ): JSONObject {
            val payload = JSONObject()
            try {
                payload.put("seenAtMs", observedAtMs)
                payload.put("observedAtMs", observedAtMs)
                payload.put("command", command ?: "")
                payload.put("dtc", code.code)
                payload.put("status", code.status)
                payload.put("statusLabel", code.statusLabel)
                payload.put("moduleKey", code.moduleKey)
                payload.put("moduleName", code.moduleName)
                payload.put("header", code.header)
                payload.put("rawResponse", code.rawResponse)
            } catch (ignored: JSONException) {
                // Local values are safe.
            }
            return payload
        }

        @JvmStatic
        fun outcomeFor(
            state: String?,
            sampleCount: Int,
        ): String {
            if ("blocked" == state || "error" == state) {
                return SessionSummary.OUTCOME_FAILED
            }
            if ("connected" == state || "scanning" == state || "scan-complete" == state || sampleCount > 0) {
                return SessionSummary.OUTCOME_SUCCESS
            }
            return SessionSummary.OUTCOME_ABORTED
        }

        @JvmStatic
        fun failureClassFor(
            state: String?,
            sessionFailureClass: FailureClass?,
        ): String? {
            if (sessionFailureClass != null) {
                return sessionFailureClass.wireName()
            }
            return failureClassFor(state)
        }

        @JvmStatic
        fun failureClassFor(state: String?): String? {
            if ("blocked" == state || "error" == state) {
                return state
            }
            return null
        }
    }
}
