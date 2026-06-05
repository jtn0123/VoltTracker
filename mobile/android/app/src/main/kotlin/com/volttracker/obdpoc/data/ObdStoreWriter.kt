package com.volttracker.obdpoc.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.core.database.sqlite.transaction
import com.volttracker.obdpoc.EnhancedPidProfile
import com.volttracker.obdpoc.EnhancedPidProfiles
import org.json.JSONException
import org.json.JSONObject

/**
 * The write half of the local store: session lifecycle, telemetry/PID/location/event inserts, and
 * adapter-history upserts.
 */
class ObdStoreWriter(
    private val helper: VoltTrackerDb,
    private val snapshots: ObdStoreSnapshots,
) {
    private val statementCache = ObdStatementCache()

    fun startSession(
        mode: String?,
        adapterAddress: String?,
        adapterName: String?,
    ): Long = startSession(mode, adapterAddress, adapterName, System.currentTimeMillis())

    fun startSession(
        mode: String?,
        adapterAddress: String?,
        adapterName: String?,
        startedAtMs: Long,
    ): Long {
        val values = ContentValues()
        values.put("mode", ObdStoreSupport.cleanMode(mode))
        values.put("adapter_address", ObdStoreSupport.clean(adapterAddress))
        values.put("adapter_name", ObdStoreSupport.clean(adapterName))
        values.put("started_at_ms", startedAtMs)
        values.put("status", ObdLocalStore.STATUS_ACTIVE)
        values.put("created_at_ms", System.currentTimeMillis())
        return helper.writableDatabase.insertOrThrow(VoltTrackerDb.TABLE_SESSIONS, null, values)
    }

    fun finishSession(
        sessionId: Long,
        status: String?,
    ) {
        finishSession(sessionId, status, System.currentTimeMillis(), null)
    }

    fun finishSession(
        sessionId: Long,
        status: String?,
        endedAtMs: Long,
        supportedPids: String?,
    ) {
        val values = ContentValues()
        values.put("ended_at_ms", endedAtMs)
        values.put("status", ObdStoreSupport.cleanStatus(status))
        if (supportedPids != null) {
            values.put("supported_pids", supportedPids)
        }
        helper.writableDatabase.update(
            VoltTrackerDb.TABLE_SESSIONS,
            values,
            "_id = ?",
            arrayOf(sessionId.toString()),
        )
    }

    fun finalizeSession(
        sessionId: Long,
        status: String?,
        endedAtMs: Long,
        supportedPids: String?,
        address: String?,
        adapterName: String?,
        mode: String?,
        sampleCount: Int,
        lastEventDetail: String?,
    ) {
        val cleanedStatus = ObdStoreSupport.cleanStatus(status)
        val cleanMode = ObdStoreSupport.cleanMode(mode)
        val key = ObdStoreSupport.adapterKey(address, cleanMode)
        val now = System.currentTimeMillis()
        val db = helper.writableDatabase
        db.transaction {
            val sessionValues = ContentValues()
            sessionValues.put("ended_at_ms", endedAtMs)
            sessionValues.put("status", cleanedStatus)
            if (supportedPids != null) {
                sessionValues.put("supported_pids", supportedPids)
            }
            db.update(VoltTrackerDb.TABLE_SESSIONS, sessionValues, "_id = ?", arrayOf(sessionId.toString()))

            val existing = snapshots.findAdapterHistory(db, key)
            val adapterValues =
                snapshots.adapterHistoryValues(
                    existing,
                    key,
                    address,
                    adapterName,
                    cleanMode,
                    cleanedStatus,
                    sampleCount,
                    supportedPids,
                    lastEventDetail,
                    sessionId,
                    now,
                )
            db.insertWithOnConflict(
                VoltTrackerDb.TABLE_ADAPTER_HISTORY,
                null,
                adapterValues,
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    fun recordTelemetry(
        sessionId: Long,
        sample: JSONObject?,
    ): Long {
        val capturedAtMs = sample?.optLong("updatedAt", System.currentTimeMillis()) ?: System.currentTimeMillis()
        return recordTelemetry(sessionId, sample, capturedAtMs)
    }

    fun recordTelemetry(
        sessionId: Long,
        sample: JSONObject?,
        capturedAtMs: Long,
    ): Long {
        val safeSample = sample ?: JSONObject()
        if (!ObdStoreSupport.isUsefulTelemetry(safeSample)) {
            return -1L
        }
        val db = helper.writableDatabase
        var id = -1L
        db.transaction {
            id = statementCache.bindAndInsertTelemetry(db, sessionId, capturedAtMs, safeSample)
            val supportedPids = ObdStoreSupport.clean(safeSample.optString("supportedPids", ""))
            db.execSQL(
                ObdStatementCache.SQL_UPDATE_SESSION_AFTER_TELEMETRY,
                arrayOf<Any>(capturedAtMs, supportedPids, supportedPids, sessionId),
            )
        }
        return id
    }

    fun recordPidObservation(
        sessionId: Long,
        observation: JSONObject?,
    ): Long {
        val safeObservation = observation ?: JSONObject()
        val observedAtMs =
            ObdStoreSupport.optTimestamp(
                safeObservation,
                "observedAtMs",
                ObdStoreSupport.optTimestamp(
                    safeObservation,
                    "observedAt",
                    safeObservation.optLong("updatedAt", System.currentTimeMillis()),
                ),
            )
        return recordPidObservation(sessionId, safeObservation, observedAtMs)
    }

    fun recordPidObservation(
        sessionId: Long,
        observation: JSONObject?,
        observedAtMs: Long,
    ): Long {
        val safeObservation = observation ?: JSONObject()
        val db = helper.writableDatabase
        var id = -1L
        db.transaction {
            val values = snapshots.pidObservationValues(sessionId, safeObservation, observedAtMs)
            id = db.insertOrThrow(VoltTrackerDb.TABLE_PID_OBSERVATIONS, null, values)
            upsertFieldCapability(db, sessionId, safeObservation, observedAtMs)
            ObdStoreSupport.updateSessionLastEvent(db, sessionId, observedAtMs)
        }
        return id
    }

    fun recordPidObservation(
        sessionId: Long,
        observedAtMs: Long,
        command: String?,
        header: String?,
        pid: String?,
        name: String?,
        valueText: String?,
        valueNumeric: Double?,
        unit: String?,
        rawRequest: String?,
        rawResponse: String?,
    ): Long {
        val payload =
            snapshots.pidObservationPayload(
                observedAtMs,
                command,
                header,
                pid,
                name,
                valueText,
                valueNumeric,
                unit,
                rawRequest,
                rawResponse,
            )
        return recordPidObservation(sessionId, payload, observedAtMs)
    }

    private fun upsertFieldCapability(
        db: SQLiteDatabase,
        sessionId: Long,
        observation: JSONObject,
        observedAtMs: Long,
    ) {
        val command = ObdStoreSupport.clean(observation.optString("command", ""))
        val header = ObdStoreSupport.clean(observation.optString("header", ""))
        val profile = EnhancedPidProfiles.find(header, command) ?: return

        val adapterKey = adapterKeyForSession(db, sessionId)
        val pid = ObdStoreSupport.clean(observation.optString("pid", profile.pid))
        val protocol = ObdStoreSupport.clean(profile.protocol)
        val rawResponse = ObdStoreSupport.clean(observation.optString("rawResponse", ""))
        val positive = EnhancedPidProfiles.isPositiveResponse(command, rawResponse)

        var existingId = -1L
        var firstSeenMs = observedAtMs
        var responseCount = 0L
        db
            .query(
                VoltTrackerDb.TABLE_FIELD_CAPABILITIES,
                arrayOf("_id", "first_seen_ms", "response_count"),
                "adapter_key = ? AND protocol = ? AND header = ? AND command = ? AND pid = ?",
                arrayOf(adapterKey, protocol, header, command, pid),
                null,
                null,
                null,
                "1",
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    existingId = cursor.getLong(cursor.getColumnIndexOrThrow("_id"))
                    firstSeenMs = cursor.getLong(cursor.getColumnIndexOrThrow("first_seen_ms"))
                    responseCount = cursor.getLong(cursor.getColumnIndexOrThrow("response_count"))
                }
            }

        val nextResponseCount = responseCount + if (positive) 1L else 0L
        val values = ContentValues()
        values.put("adapter_key", adapterKey)
        values.put("protocol", protocol)
        values.put("header", header)
        values.put("command", command)
        values.put("pid", pid)
        values.put("name", ObdStoreSupport.clean(profile.name))
        values.put("unit", ObdStoreSupport.clean(profile.unit))
        values.put("supported", if (positive || responseCount > 0) 1 else 0)
        values.put("response_count", nextResponseCount)
        values.put("first_seen_ms", firstSeenMs)
        values.put("last_seen_ms", observedAtMs)
        values.put("sample_json", capabilitySampleJson(profile, observation, positive).toString())

        if (existingId > 0) {
            db.update(VoltTrackerDb.TABLE_FIELD_CAPABILITIES, values, "_id = ?", arrayOf(existingId.toString()))
            return
        }
        db.insertOrThrow(VoltTrackerDb.TABLE_FIELD_CAPABILITIES, null, values)
    }

    fun recordDiagnosticCode(
        sessionId: Long,
        diagnosticCode: JSONObject?,
    ): Long {
        val safeCode = diagnosticCode ?: JSONObject()
        val seenAtMs =
            ObdStoreSupport.optTimestamp(
                safeCode,
                "seenAtMs",
                ObdStoreSupport.optTimestamp(safeCode, "observedAtMs", System.currentTimeMillis()),
            )
        val db = helper.writableDatabase
        var id = -1L
        db.transaction {
            id = snapshots.upsertDiagnosticCode(db, sessionId, safeCode, seenAtMs)
            if (id >= 0) {
                ObdStoreSupport.updateSessionLastEvent(db, sessionId, seenAtMs)
            }
        }
        return id
    }

    fun recordLocationSample(
        sessionId: Long,
        sample: JSONObject?,
    ): Long {
        val safeSample = sample ?: JSONObject()
        val capturedAtMs =
            ObdStoreSupport.optTimestamp(
                safeSample,
                "capturedAtMs",
                ObdStoreSupport.optTimestamp(
                    safeSample,
                    "timestampMs",
                    safeSample.optLong("updatedAt", System.currentTimeMillis()),
                ),
            )
        return recordLocationSample(sessionId, safeSample, capturedAtMs)
    }

    fun recordLocationSample(
        sessionId: Long,
        sample: JSONObject?,
        capturedAtMs: Long,
    ): Long {
        val safeSample = sample ?: JSONObject()
        if (!safeSample.has("latitude") ||
            safeSample.isNull("latitude") ||
            !safeSample.has("longitude") ||
            safeSample.isNull("longitude")
        ) {
            return -1L
        }
        val db = helper.writableDatabase
        var id = -1L
        db.transaction {
            val values = snapshots.locationSampleValues(sessionId, safeSample, capturedAtMs)
            id = db.insertOrThrow(VoltTrackerDb.TABLE_LOCATION_SAMPLES, null, values)
            ObdStoreSupport.updateSessionLastEvent(db, sessionId, capturedAtMs)
        }
        return id
    }

    fun recordLocationSample(
        sessionId: Long,
        capturedAtMs: Long,
        provider: String?,
        latitude: Double,
        longitude: Double,
        accuracyM: Double?,
        altitudeM: Double?,
        speedMps: Double?,
        bearingDeg: Double?,
        locationAgeMs: Long?,
        elapsedRealtimeNanos: Long?,
    ): Long {
        val payload =
            snapshots.locationSamplePayload(
                capturedAtMs,
                provider,
                latitude,
                longitude,
                accuracyM,
                altitudeM,
                speedMps,
                bearingDeg,
                locationAgeMs,
                elapsedRealtimeNanos,
            )
        return recordLocationSample(sessionId, payload, capturedAtMs)
    }

    fun recordStatus(
        sessionId: Long,
        state: String?,
        detail: String?,
        blocked: Boolean,
        payload: JSONObject?,
    ): Long = recordEvent(sessionId, "status", state, detail, blocked, payload)

    fun recordEvent(
        sessionId: Long,
        kind: String?,
        state: String?,
        detail: String?,
        blocked: Boolean,
        payload: JSONObject?,
    ): Long {
        val occurredAtMs = payload?.optLong("updatedAt", System.currentTimeMillis()) ?: System.currentTimeMillis()
        val safePayload = payload ?: JSONObject()
        val db = helper.writableDatabase
        var id = -1L
        db.transaction {
            val values = ContentValues()
            if (sessionId > 0) {
                values.put("session_id", sessionId)
            }
            values.put("occurred_at_ms", occurredAtMs)
            val cleanKind = ObdStoreSupport.clean(kind)
            values.put("kind", if (cleanKind.isEmpty()) "event" else cleanKind)
            values.put("state", ObdStoreSupport.clean(state))
            values.put("detail", ObdStoreSupport.clean(detail))
            values.put("blocked", if (blocked) 1 else 0)
            values.put("payload", safePayload.toString())
            id = db.insertOrThrow(VoltTrackerDb.TABLE_EVENTS, null, values)
            ObdStoreSupport.updateSessionLastEvent(db, sessionId, occurredAtMs)
        }
        return id
    }

    fun recordAdapterSummary(
        address: String?,
        name: String?,
        mode: String?,
        sessionId: Long,
        status: String?,
        samples: Int,
        supportedPids: String?,
        lastEventDetail: String?,
    ) {
        val now = System.currentTimeMillis()
        val cleanMode = ObdStoreSupport.cleanMode(mode)
        val key = ObdStoreSupport.adapterKey(address, cleanMode)
        val db = helper.writableDatabase
        db.transaction {
            val existing = snapshots.findAdapterHistory(db, key)
            val values =
                snapshots.adapterHistoryValues(
                    existing,
                    key,
                    address,
                    name,
                    cleanMode,
                    ObdStoreSupport.cleanStatus(status),
                    samples,
                    supportedPids,
                    lastEventDetail,
                    sessionId,
                    now,
                )
            db.insertWithOnConflict(
                VoltTrackerDb.TABLE_ADAPTER_HISTORY,
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    fun close() {
        statementCache.close()
    }

    companion object {
        private fun capabilitySampleJson(
            profile: EnhancedPidProfile,
            observation: JSONObject,
            positive: Boolean,
        ): JSONObject {
            val payload = JSONObject()
            try {
                payload.put("profileKey", profile.key)
                payload.put("category", profile.category)
                payload.put("network", profile.network)
                payload.put("pollLane", profile.pollLane)
                payload.put("scanStage", profile.scanStage)
                payload.put("risk", profile.risk)
                payload.put("retryAfterMs", profile.retryAfterMs)
                payload.put("validationStatus", profile.validationStatus)
                payload.put("source", profile.source)
                payload.put("notes", profile.notes)
                payload.put("positiveResponse", positive)
                payload.put("rawResponse", ObdStoreSupport.clean(observation.optString("rawResponse", "")))
                payload.put("valueText", ObdStoreSupport.clean(observation.optString("valueText", "")))
                if (observation.has("valueNumeric")) {
                    payload.put("valueNumeric", observation.optDouble("valueNumeric"))
                }
            } catch (ignored: JSONException) {
                // Local strings/numbers are safe.
            }
            return payload
        }

        private fun adapterKeyForSession(
            db: SQLiteDatabase,
            sessionId: Long,
        ): String {
            db
                .query(
                    VoltTrackerDb.TABLE_SESSIONS,
                    arrayOf("adapter_address", "mode"),
                    "_id = ?",
                    arrayOf(sessionId.toString()),
                    null,
                    null,
                    null,
                    "1",
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        val address = cursor.getString(cursor.getColumnIndexOrThrow("adapter_address"))
                        val mode = cursor.getString(cursor.getColumnIndexOrThrow("mode"))
                        return ObdStoreSupport.adapterKey(address, mode)
                    }
                }
            return ObdStoreSupport.adapterKey("", "")
        }
    }
}
