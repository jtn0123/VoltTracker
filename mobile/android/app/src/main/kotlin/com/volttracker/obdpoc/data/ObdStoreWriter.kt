package com.volttracker.obdpoc.data

import android.content.ContentValues
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import android.util.Log
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

    /**
     * sessionId -> adapter key, so the per-PID-observation capability upsert resolves the key with
     * one TABLE_SESSIONS query per session instead of one per observation. Safe to cache because a
     * session's adapter_address/mode never change after startSession. Bounded LRU (a handful of
     * sessions can be in flight at once at most); synchronized because reads can arrive from more
     * than one caller thread.
     */
    private val adapterKeyCache =
        object : LinkedHashMap<Long, String>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>?): Boolean =
                size > ADAPTER_KEY_CACHE_MAX
        }

    /** Consecutive passive-checkpoint failures; reset on the first success. */
    private var walCheckpointFailureStreak = 0

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
        checkpointWalPassive()
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
        checkpointWalPassive()
    }

    private fun checkpointWalPassive() {
        try {
            helper.writableDatabase.execSQL("PRAGMA wal_checkpoint(PASSIVE)")
            walCheckpointFailureStreak = 0
        } catch (ex: RuntimeException) {
            // Best-effort bound for very long sessions; regular maintenance still truncates. A
            // single failure is harmless, but a persistent streak means the WAL is growing
            // unchecked — surface that without logging on every session end.
            walCheckpointFailureStreak++
            if (walCheckpointFailureStreak == 1 ||
                walCheckpointFailureStreak % WAL_CHECKPOINT_LOG_EVERY == 0
            ) {
                Log.w(
                    TAG,
                    "wal_checkpoint(PASSIVE) failed ($walCheckpointFailureStreak consecutive)",
                    ex,
                )
            }
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
            recordBatterySnapshotIfPresent(db, sessionId, capturedAtMs, safeSample)
            val supportedPids = ObdStoreSupport.clean(safeSample.optString("supportedPids", ""))
            db.execSQL(
                ObdStatementCache.SQL_UPDATE_SESSION_AFTER_TELEMETRY,
                arrayOf<Any>(capturedAtMs, supportedPids, supportedPids, sessionId),
            )
        }
        return id
    }

    private fun recordBatterySnapshotIfPresent(
        db: SQLiteDatabase,
        sessionId: Long,
        capturedAtMs: Long,
        sample: JSONObject,
    ) {
        if (!sample.has("capacityAh") || sample.isNull("capacityAh")) {
            return
        }
        if (sample.has("capacityAhStaleMs") && sample.optLong("capacityAhStaleMs", Long.MAX_VALUE) > 2_500L) {
            return
        }
        val values = ContentValues()
        values.put("session_id", sessionId)
        values.put("captured_at_ms", capturedAtMs)
        putOptionalDouble(values, "soc", sample, "soc")
        putOptionalDouble(values, "capacity_ah", sample, "capacityAh")
        putOptionalDouble(values, "soh_pct", sample, "sohPct")
        putOptionalDouble(values, "pack_voltage", sample, "packVoltage")
        putOptionalDouble(values, "pack_current_a", sample, "packCurrentA")
        putOptionalDouble(values, "pack_power_kw", sample, "powerKw")
        putOptionalDouble(values, "battery_temp_c", sample, "batteryTemp")
        putOptionalDouble(values, "odometer_km", sample, "odometerKm")
        values.put("created_at_ms", System.currentTimeMillis())
        values.put("json", sample.toString())
        db.insertOrThrow(VoltTrackerDb.TABLE_BATTERY_SNAPSHOTS, null, values)
    }

    private fun putOptionalDouble(
        values: ContentValues,
        column: String,
        sample: JSONObject,
        key: String,
    ) {
        if (sample.has(key) && !sample.isNull(key)) {
            values.put(column, sample.optDouble(key))
        }
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

    fun recordPidObservations(
        sessionId: Long,
        observations: List<JSONObject>,
    ): Int {
        if (observations.isEmpty()) {
            return 0
        }
        val db = helper.writableDatabase
        var inserted = 0
        db.transaction {
            val adapterKey = adapterKeyForSession(db, sessionId)
            val capabilities = LinkedHashMap<CapabilityKey, CapabilityAccumulator>()
            var latestObservedAtMs = 0L
            for (observation in observations) {
                val observedAtMs =
                    ObdStoreSupport.optTimestamp(
                        observation,
                        "observedAtMs",
                        ObdStoreSupport.optTimestamp(
                            observation,
                            "observedAt",
                            observation.optLong("updatedAt", System.currentTimeMillis()),
                        ),
                    )
                val values = snapshots.pidObservationValues(sessionId, observation, observedAtMs)
                db.insertOrThrow(VoltTrackerDb.TABLE_PID_OBSERVATIONS, null, values)
                inserted += 1
                if (observedAtMs > latestObservedAtMs) {
                    latestObservedAtMs = observedAtMs
                }
                collectFieldCapability(adapterKey, observation, observedAtMs, capabilities)
            }
            upsertFieldCapabilities(db, adapterKey, capabilities)
            if (latestObservedAtMs > 0L) {
                ObdStoreSupport.updateSessionLastEvent(db, sessionId, latestObservedAtMs)
            }
        }
        return inserted
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

    private fun collectFieldCapability(
        adapterKey: String,
        observation: JSONObject,
        observedAtMs: Long,
        capabilities: MutableMap<CapabilityKey, CapabilityAccumulator>,
    ) {
        val command = ObdStoreSupport.clean(observation.optString("command", ""))
        val header = ObdStoreSupport.clean(observation.optString("header", ""))
        val profile = EnhancedPidProfiles.find(header, command) ?: return
        val pid = ObdStoreSupport.clean(observation.optString("pid", profile.pid))
        val protocol = ObdStoreSupport.clean(profile.protocol)
        val rawResponse = ObdStoreSupport.clean(observation.optString("rawResponse", ""))
        val positive = EnhancedPidProfiles.isPositiveResponse(command, rawResponse)
        val key = CapabilityKey(adapterKey, protocol, header, command, pid)
        val accumulator =
            capabilities.getOrPut(key) {
                CapabilityAccumulator(key, profile)
            }
        accumulator.add(observation, observedAtMs, positive)
    }

    private fun upsertFieldCapabilities(
        db: SQLiteDatabase,
        adapterKey: String,
        capabilities: Map<CapabilityKey, CapabilityAccumulator>,
    ) {
        if (capabilities.isEmpty()) {
            return
        }
        val existing = existingCapabilitiesForAdapter(db, adapterKey)
        for ((key, accumulator) in capabilities) {
            val row = existing[key]
            val firstSeenMs = row?.firstSeenMs ?: accumulator.firstSeenMs
            val nextResponseCount = (row?.responseCount ?: 0L) + accumulator.positiveCount
            val values = ContentValues()
            values.put("adapter_key", key.adapterKey)
            values.put("protocol", key.protocol)
            values.put("header", key.header)
            values.put("command", key.command)
            values.put("pid", key.pid)
            values.put("name", ObdStoreSupport.clean(accumulator.profile.name))
            values.put("unit", ObdStoreSupport.clean(accumulator.profile.unit))
            values.put("supported", if (nextResponseCount > 0L) 1 else 0)
            values.put("response_count", nextResponseCount)
            values.put("first_seen_ms", firstSeenMs)
            values.put("last_seen_ms", accumulator.lastSeenMs)
            values.put(
                "sample_json",
                capabilitySampleJson(
                    accumulator.profile,
                    accumulator.latestObservation ?: JSONObject(),
                    accumulator.latestPositive,
                ).toString(),
            )

            if (row != null) {
                db.update(VoltTrackerDb.TABLE_FIELD_CAPABILITIES, values, "_id = ?", arrayOf(row.id.toString()))
            } else {
                db.insertOrThrow(VoltTrackerDb.TABLE_FIELD_CAPABILITIES, null, values)
            }
        }
    }

    private fun existingCapabilitiesForAdapter(
        db: SQLiteDatabase,
        adapterKey: String,
    ): Map<CapabilityKey, ExistingCapability> {
        val rows = HashMap<CapabilityKey, ExistingCapability>()
        db
            .query(
                VoltTrackerDb.TABLE_FIELD_CAPABILITIES,
                arrayOf(
                    "_id",
                    "adapter_key",
                    "protocol",
                    "header",
                    "command",
                    "pid",
                    "first_seen_ms",
                    "response_count",
                ),
                "adapter_key = ?",
                arrayOf(adapterKey),
                null,
                null,
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val key =
                        CapabilityKey(
                            cursor.getString(cursor.getColumnIndexOrThrow("adapter_key")),
                            cursor.getString(cursor.getColumnIndexOrThrow("protocol")),
                            cursor.getString(cursor.getColumnIndexOrThrow("header")),
                            cursor.getString(cursor.getColumnIndexOrThrow("command")),
                            cursor.getString(cursor.getColumnIndexOrThrow("pid")),
                        )
                    rows[key] =
                        ExistingCapability(
                            cursor.getLong(cursor.getColumnIndexOrThrow("_id")),
                            cursor.getLong(cursor.getColumnIndexOrThrow("first_seen_ms")),
                            cursor.getLong(cursor.getColumnIndexOrThrow("response_count")),
                        )
                }
            }
        return rows
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

    /**
     * Records a completed per-trip export (GPX/CSV) into [VoltTrackerDb.TABLE_EXPORTS]. Until this
     * landed the table was scaffolded-but-unwired — created by the schema and carried by the backup
     * merger, but never inserted into. One row per share: the source session id, the kind, when it
     * ran, the on-disk byte size, and the file name. Returns the new row id (or -1 on failure;
     * recording is best-effort and must never break the share itself).
     */
    fun recordExport(
        sessionId: Long,
        exportType: String,
        fileName: String,
        mimeType: String,
        bytes: Long,
        rangeStartMs: Long,
        rangeEndMs: Long,
    ): Long {
        val now = System.currentTimeMillis()
        val values = ContentValues()
        if (sessionId > 0L) {
            values.put("session_id", sessionId)
        }
        values.put("created_at_ms", now)
        values.put("exported_at_ms", now)
        if (rangeStartMs > 0L) {
            values.put("range_start_ms", rangeStartMs)
        }
        if (rangeEndMs > 0L) {
            values.put("range_end_ms", rangeEndMs)
        }
        values.put("export_type", ObdStoreSupport.clean(exportType))
        values.put("status", "completed")
        values.put("file_name", ObdStoreSupport.clean(fileName))
        values.put("mime_type", ObdStoreSupport.clean(mimeType))
        values.put("bytes", bytes)
        values.put("destination", "share")
        // GPX/CSV track exports always carry precise lat/lng; flag it so a future audit/export-history
        // view can warn before re-sharing, matching the schema's privacy-intent columns.
        values.put("include_precise_location", 1)
        values.put("include_raw_logs", 0)
        return try {
            helper.writableDatabase.insertOrThrow(VoltTrackerDb.TABLE_EXPORTS, null, values)
        } catch (ex: SQLException) {
            Log.w(TAG, "recordExport failed", ex)
            -1L
        }
    }

    /**
     * Best-effort stamp of the user [label] onto any materialized `trip_segments` rows whose drive
     * window overlaps [startedAtMs]..[endedAtMs] for [sessionId]. Materialized segments are not 1:1
     * with the route-key drive windows the dashboard renders, so this is provenance only — the
     * authoritative label store is the route-key-keyed status event. A blank label clears the column.
     */
    fun setTripSegmentLabel(
        sessionId: Long,
        startedAtMs: Long,
        endedAtMs: Long,
        label: String,
    ) {
        val values = ContentValues()
        if (label.isEmpty()) {
            values.putNull("label")
        } else {
            values.put("label", label)
        }
        try {
            helper.writableDatabase.update(
                VoltTrackerDb.TABLE_TRIP_SEGMENTS,
                values,
                "session_id = ? AND started_at_ms <= ? AND (ended_at_ms IS NULL OR ended_at_ms >= ?)",
                arrayOf(sessionId.toString(), endedAtMs.toString(), startedAtMs.toString()),
            )
        } catch (ex: SQLException) {
            Log.w(TAG, "setTripSegmentLabel failed", ex)
        }
    }

    /**
     * Inserts a user-authored maintenance-log row (M5). [odometerKm] may be null (the user may not
     * know the reading); [type] and [note] are free text. [createdAtMs] dates the entry (the user
     * can backdate service performed earlier). [intervalKm] / [intervalMonths] are the optional
     * service interval (M1/C4) that drives the dashboard's "next due / overdue" line; null leaves a
     * plain history entry. Returns the new row id, or -1 on failure.
     */
    fun addMaintenanceEntry(
        createdAtMs: Long,
        odometerKm: Double?,
        type: String?,
        note: String?,
        intervalKm: Double? = null,
        intervalMonths: Int? = null,
    ): Long {
        val values = ContentValues()
        values.put("created_at_ms", createdAtMs)
        if (odometerKm != null && !odometerKm.isNaN() && !odometerKm.isInfinite() && odometerKm >= 0.0) {
            values.put("odometer_km", odometerKm)
        }
        values.put("type", ObdStoreSupport.clean(type))
        values.put("note", ObdStoreSupport.clean(note))
        if (intervalKm != null && !intervalKm.isNaN() && !intervalKm.isInfinite() && intervalKm > 0.0) {
            values.put("interval_km", intervalKm)
        }
        if (intervalMonths != null && intervalMonths > 0) {
            values.put("interval_months", intervalMonths)
        }
        return try {
            helper.writableDatabase.insertOrThrow(VoltTrackerDb.TABLE_MAINTENANCE_LOG, null, values)
        } catch (ex: SQLException) {
            Log.w(TAG, "addMaintenanceEntry failed", ex)
            -1L
        }
    }

    fun deleteMaintenanceEntry(id: Long): Int =
        try {
            helper.writableDatabase.delete(VoltTrackerDb.TABLE_MAINTENANCE_LOG, "_id = ?", arrayOf(id.toString()))
        } catch (ex: SQLException) {
            Log.w(TAG, "deleteMaintenanceEntry failed", ex)
            0
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

    /**
     * Resolves the adapter key for a session, served from [adapterKeyCache] after the first
     * lookup. Only a successful row read is cached; a miss (session not yet visible) falls back
     * to the empty key without poisoning the cache.
     */
    private fun adapterKeyForSession(
        db: SQLiteDatabase,
        sessionId: Long,
    ): String {
        synchronized(adapterKeyCache) {
            adapterKeyCache[sessionId]?.let { return it }
        }
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
                    val key = ObdStoreSupport.adapterKey(address, mode)
                    synchronized(adapterKeyCache) {
                        adapterKeyCache[sessionId] = key
                    }
                    return key
                }
            }
        return ObdStoreSupport.adapterKey("", "")
    }

    fun close() {
        statementCache.close()
        synchronized(adapterKeyCache) {
            adapterKeyCache.clear()
        }
    }

    private data class CapabilityKey(
        val adapterKey: String,
        val protocol: String,
        val header: String,
        val command: String,
        val pid: String,
    )

    private data class ExistingCapability(
        val id: Long,
        val firstSeenMs: Long,
        val responseCount: Long,
    )

    private class CapabilityAccumulator(
        val key: CapabilityKey,
        val profile: EnhancedPidProfile,
    ) {
        var firstSeenMs: Long = Long.MAX_VALUE
            private set
        var lastSeenMs: Long = 0L
            private set
        var positiveCount: Long = 0L
            private set
        var latestObservation: JSONObject? = null
            private set
        var latestPositive: Boolean = false
            private set

        fun add(
            observation: JSONObject,
            observedAtMs: Long,
            positive: Boolean,
        ) {
            if (observedAtMs < firstSeenMs) {
                firstSeenMs = observedAtMs
            }
            if (observedAtMs >= lastSeenMs) {
                lastSeenMs = observedAtMs
                latestObservation = observation
                latestPositive = positive
            }
            if (positive) {
                positiveCount += 1L
            }
        }
    }

    companion object {
        private const val TAG = "VoltTracker"

        /** Keep the adapter keys of the last few sessions; one session is active at a time. */
        private const val ADAPTER_KEY_CACHE_MAX = 4

        /** After the first failure, log again on every Nth consecutive checkpoint failure. */
        private const val WAL_CHECKPOINT_LOG_EVERY = 10

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
    }
}
