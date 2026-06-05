package com.volttracker.obdpoc.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/** Read-side projections for records and dashboard JSON consumed by [ObdLocalStore]. */
class ObdStoreReports(
    private val helper: VoltTrackerDb,
    private val trips: ObdStoreTrips,
) {
    fun getSession(sessionId: Long): ObdSessionRecord? =
        helper.readableDatabase
            .query(
                VoltTrackerDb.TABLE_SESSIONS,
                null,
                "_id = ?",
                arrayOf(sessionId.toString()),
                null,
                null,
                null,
            ).use { cursor ->
                if (cursor.moveToFirst()) ObdStoreSupport.readSession(cursor) else null
            }

    fun tripRouteJson(sessionId: Long): JSONObject {
        val session = getSession(sessionId) ?: return JSONObject()
        return try {
            ObdStoreRouteProjection.routeForSession(
                helper.readableDatabase,
                session,
                ObdStoreRouteProjection.MAX_TRACK_POINTS,
            )
        } catch (ex: JSONException) {
            JSONObject()
        }
    }

    fun tripRouteJson(routeKey: String?): JSONObject {
        val parsed = DriveWindowDetector.parseRouteKey(routeKey) ?: return JSONObject()
        val session = getSession(parsed.sessionId) ?: return JSONObject()
        return try {
            ObdStoreRouteProjection.routeForSession(
                helper.readableDatabase,
                session,
                ObdStoreRouteProjection.MAX_TRACK_POINTS,
                parsed.startedAtMs,
                parsed.endedAtMs,
                routeKey,
            )
        } catch (ex: JSONException) {
            JSONObject()
        }
    }

    fun getRecentSessions(limit: Int): List<ObdSessionRecord> {
        val records = ArrayList<ObdSessionRecord>()
        helper.readableDatabase
            .query(
                VoltTrackerDb.TABLE_SESSIONS,
                null,
                null,
                null,
                null,
                null,
                "started_at_ms DESC",
                ObdStoreSupport.boundedLimit(limit),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    records.add(ObdStoreSupport.readSession(cursor))
                }
            }
        return records
    }

    fun getRecentTelemetry(
        sessionId: Long,
        limit: Int,
    ): List<TelemetrySampleRecord> {
        val records = ArrayList<TelemetrySampleRecord>()
        helper.readableDatabase
            .query(
                VoltTrackerDb.TABLE_TELEMETRY,
                null,
                "session_id = ? AND ${ObdStoreSupport.USEFUL_TELEMETRY_WHERE}",
                arrayOf(sessionId.toString()),
                null,
                null,
                "captured_at_ms DESC",
                ObdStoreSupport.boundedLimit(limit),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    records.add(ObdStoreSupport.readTelemetry(cursor))
                }
            }
        return records
    }

    fun getRecentEvents(
        sessionId: Long,
        limit: Int,
    ): List<StatusEventRecord> {
        val records = ArrayList<StatusEventRecord>()
        helper.readableDatabase
            .query(
                VoltTrackerDb.TABLE_EVENTS,
                null,
                "session_id = ?",
                arrayOf(sessionId.toString()),
                null,
                null,
                "occurred_at_ms DESC",
                ObdStoreSupport.boundedLimit(limit),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    records.add(ObdStoreSupport.readStatusEvent(cursor))
                }
            }
        return records
    }

    fun getAdapterHistory(limit: Int): List<AdapterHistoryRecord> {
        val records = ArrayList<AdapterHistoryRecord>()
        helper.readableDatabase
            .query(
                VoltTrackerDb.TABLE_ADAPTER_HISTORY,
                null,
                null,
                null,
                null,
                null,
                "last_seen_ms DESC",
                ObdStoreSupport.boundedLimit(limit),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    records.add(ObdStoreSupport.readAdapterHistory(cursor))
                }
            }
        return records
    }

    fun enhancedCapabilitiesJson(limit: Int): JSONArray {
        val items = JSONArray()
        helper.readableDatabase
            .query(
                VoltTrackerDb.TABLE_FIELD_CAPABILITIES,
                null,
                null,
                null,
                null,
                null,
                "last_seen_ms DESC",
                ObdStoreSupport.boundedLimit(limit),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    try {
                        items.put(capabilityJsonFromCursor(cursor))
                    } catch (ignored: JSONException) {
                        // Skip malformed rows instead of breaking the debug payload.
                    }
                }
            }
        return items
    }

    fun enhancedCapabilityJson(id: Long): JSONObject {
        helper.readableDatabase
            .query(
                VoltTrackerDb.TABLE_FIELD_CAPABILITIES,
                null,
                "_id = ?",
                arrayOf(id.toString()),
                null,
                null,
                null,
                "1",
            ).use { cursor ->
                if (!cursor.moveToFirst()) {
                    return notFound("detailed signal log not found")
                }
                return try {
                    JSONObject()
                        .put("ok", true)
                        .put("kind", "detailed-signal-log")
                        .put("item", capabilityJsonFromCursor(cursor))
                } catch (ex: JSONException) {
                    error("detailed signal log could not be exported")
                }
            }
    }

    fun enhancedCapabilitiesExportJson(limit: Int): JSONObject {
        val payload = JSONObject()
        try {
            payload.put("ok", true)
            payload.put("kind", "detailed-signal-logs")
            payload.put("exportedAtMs", System.currentTimeMillis())
            payload.put("items", enhancedCapabilitiesJson(limit))
        } catch (ignored: JSONException) {
            // Local keys are safe.
        }
        return payload
    }

    fun hasRecentEnhancedCapability(
        adapterAddress: String?,
        header: String?,
        command: String?,
        minAgeMs: Long,
    ): Boolean {
        val adapterKey = ObdStoreSupport.adapterKey(adapterAddress, ObdLocalStore.MODE_OBD)
        val cutoff = System.currentTimeMillis() - maxOf(0L, minAgeMs)
        helper.readableDatabase
            .query(
                VoltTrackerDb.TABLE_FIELD_CAPABILITIES,
                arrayOf("last_seen_ms"),
                "adapter_key = ? AND header = ? AND command = ? AND last_seen_ms >= ?",
                arrayOf(adapterKey, ObdStoreSupport.clean(header), ObdStoreSupport.clean(command), cutoff.toString()),
                null,
                null,
                "last_seen_ms DESC",
                "1",
            ).use { cursor ->
                return cursor.moveToFirst()
            }
    }

    fun deleteEnhancedCapability(id: Long): Int =
        helper.writableDatabase.delete(
            VoltTrackerDb.TABLE_FIELD_CAPABILITIES,
            "_id = ?",
            arrayOf(id.toString()),
        )

    fun hasRejectedEnhancedCapability(
        adapterAddress: String?,
        header: String?,
        command: String?,
    ): Boolean {
        val adapterKey = ObdStoreSupport.adapterKey(adapterAddress, ObdLocalStore.MODE_OBD)
        helper.readableDatabase
            .query(
                VoltTrackerDb.TABLE_FIELD_CAPABILITIES,
                arrayOf("supported", "response_count"),
                "adapter_key = ? AND header = ? AND command = ?",
                arrayOf(adapterKey, ObdStoreSupport.clean(header), ObdStoreSupport.clean(command)),
                null,
                null,
                "last_seen_ms DESC",
                "1",
            ).use { cursor ->
                if (!cursor.moveToFirst()) {
                    return false
                }
                val supported = cursor.getInt(cursor.getColumnIndexOrThrow("supported")) == 1
                val responseCount = cursor.getLong(cursor.getColumnIndexOrThrow("response_count"))
                return !supported && responseCount <= 0L
            }
    }

    fun storageSummaryRecord(databaseFile: File): StorageSummaryRecord {
        val db = helper.readableDatabase
        val rawTelemetryCount = ObdStoreSupport.countRows(db, VoltTrackerDb.TABLE_TELEMETRY)
        val usefulTelemetryCount =
            ObdStoreSupport.countRowsWhere(
                db,
                VoltTrackerDb.TABLE_TELEMETRY,
                ObdStoreSupport.USEFUL_TELEMETRY_WHERE,
                null,
            )
        val latest = ObdStoreSupport.firstOrNull(getRecentSessions(1))
        val reviewSession = ObdStoreSessionReview.latestReviewableSession(db)
        return StorageSummaryRecord(
            VoltTrackerDb.DATABASE_NAME,
            databaseFile.length(),
            ObdStoreSupport.countRows(db, VoltTrackerDb.TABLE_SESSIONS),
            rawTelemetryCount,
            usefulTelemetryCount,
            maxOf(0L, rawTelemetryCount - usefulTelemetryCount),
            ObdStoreSupport.countRows(db, VoltTrackerDb.TABLE_EVENTS),
            ObdStoreSupport.countRows(db, VoltTrackerDb.TABLE_ADAPTER_HISTORY),
            ObdStoreSupport.countRows(db, VoltTrackerDb.TABLE_PID_OBSERVATIONS),
            ObdStoreSupport.countRows(db, VoltTrackerDb.TABLE_DIAGNOSTIC_CODES),
            diagnosticCodeStatusCounts(db),
            ObdStoreSupport.countRows(db, VoltTrackerDb.TABLE_LOCATION_SAMPLES),
            ObdStoreSupport.countRows(db, VoltTrackerDb.TABLE_VEHICLES),
            ObdStoreSupport.countRows(db, VoltTrackerDb.TABLE_FIELD_CAPABILITIES),
            ObdStoreSupport.countRows(db, VoltTrackerDb.TABLE_TRIP_SEGMENTS),
            ObdStoreSupport.countRows(db, VoltTrackerDb.TABLE_CHARGE_SESSIONS),
            ObdStoreSupport.countRows(db, VoltTrackerDb.TABLE_BATTERY_SNAPSHOTS),
            ObdStoreSupport.countRows(db, VoltTrackerDb.TABLE_CELL_SNAPSHOTS),
            ObdStoreSupport.countRows(db, VoltTrackerDb.TABLE_EXPORTS),
            latest,
            recentSessionSummaries(db, 6),
            getAdapterHistory(6),
            latestDiagnosticCodeReports(db, 12),
            if (reviewSession == null) JSONObject() else safeJson { ObdStoreSessionReview.sessionReview(db, reviewSession) },
            if (reviewSession == null) {
                JSONObject()
            } else {
                safeJson { ObdStoreRouteProjection.routeForSession(db, reviewSession, 240) }
            },
            safeArray { ObdStoreRouteProjection.recentRoutes(db, 8, 500) },
            safeJson { overviewJson(db) },
            safeJson { chargeSummaryJson(db) },
            safeJson { batterySummaryJson(db) },
            safeJson { latestVehicleJson(db) },
            safeArray { enhancedCapabilitiesJson(24) },
        )
    }

    private fun recentSessionSummaries(
        db: SQLiteDatabase,
        limit: Int,
    ): List<RecentSessionSummaryRecord> {
        val records = ArrayList<RecentSessionSummaryRecord>()
        for (session in getRecentSessions(limit)) {
            val usefulSamples =
                ObdStoreSupport.countRowsWhere(
                    db,
                    VoltTrackerDb.TABLE_TELEMETRY,
                    "session_id = ? AND ${ObdStoreSupport.USEFUL_TELEMETRY_WHERE}",
                    arrayOf(session.id.toString()),
                )
            records.add(
                RecentSessionSummaryRecord(
                    session,
                    usefulSamples,
                    maxOf(0L, session.sampleCount - usefulSamples),
                ),
            )
        }
        return records
    }

    private fun latestVehicleJson(db: SQLiteDatabase): JSONObject {
        val result = JSONObject()
        db
            .query(
                VoltTrackerDb.TABLE_VEHICLES,
                arrayOf("_id", "display_name", "make", "model", "model_year", "vin_redacted", "first_seen_ms", "last_seen_ms"),
                null,
                null,
                null,
                null,
                "last_seen_ms DESC",
                "1",
            ).use { cursor ->
                if (!cursor.moveToFirst()) {
                    return result
                }
                try {
                    val displayName = cursor.getString(cursor.getColumnIndexOrThrow("display_name"))
                    val make = cursor.getString(cursor.getColumnIndexOrThrow("make"))
                    val model = cursor.getString(cursor.getColumnIndexOrThrow("model"))
                    val vinRedacted = cursor.getString(cursor.getColumnIndexOrThrow("vin_redacted"))
                    val yearIndex = cursor.getColumnIndexOrThrow("model_year")
                    if (displayName != null) {
                        result.put("name", displayName)
                    }
                    if (make != null) {
                        result.put("make", make)
                    }
                    if (model != null) {
                        result.put("model", model)
                    }
                    if (!vinRedacted.isNullOrEmpty()) {
                        result.put("vin", "…$vinRedacted")
                    }
                    if (!cursor.isNull(yearIndex)) {
                        result.put("year", cursor.getInt(yearIndex))
                        result.put("modelYear", cursor.getInt(yearIndex))
                    }
                    result.put("vehicleId", cursor.getLong(cursor.getColumnIndexOrThrow("_id")))
                    result.put("firstSeenMs", cursor.getLong(cursor.getColumnIndexOrThrow("first_seen_ms")))
                    result.put("lastSeenMs", cursor.getLong(cursor.getColumnIndexOrThrow("last_seen_ms")))
                } catch (ignored: JSONException) {
                    // Local values are safe.
                }
            }
        return result
    }

    fun recentSessionsJson(limit: Int): JSONArray {
        val payload = JSONArray()
        val db = helper.readableDatabase
        for (record in getRecentSessions(limit)) {
            val item = JSONObject()
            try {
                item.put("id", record.id)
                item.put("mode", record.mode)
                item.put("adapterAddress", record.adapterAddress)
                item.put("adapterName", record.adapterName)
                item.put("startedAtMs", record.startedAtMs)
                item.put("endedAtMs", record.endedAtMs)
                item.put("status", record.status)
                item.put("supportedPids", record.supportedPids)
                item.put("sampleCount", record.sampleCount)
                val usefulSamples =
                    ObdStoreSupport.countRowsWhere(
                        db,
                        VoltTrackerDb.TABLE_TELEMETRY,
                        "session_id = ? AND ${ObdStoreSupport.USEFUL_TELEMETRY_WHERE}",
                        arrayOf(record.id.toString()),
                    )
                item.put("usefulSampleCount", usefulSamples)
                item.put("emptySampleCount", maxOf(0L, record.sampleCount - usefulSamples))
                item.put("lastEventAtMs", record.lastEventAtMs)
            } catch (ignored: JSONException) {
                // Local fields are safe.
            }
            payload.put(item)
        }
        return payload
    }

    fun adapterHistoryJson(limit: Int): JSONArray {
        val payload = JSONArray()
        for (record in getAdapterHistory(limit)) {
            val item = JSONObject()
            try {
                item.put("adapterKey", record.adapterKey)
                item.put("address", record.address)
                item.put("name", record.name)
                item.put("firstSeenMs", record.firstSeenMs)
                item.put("lastSeenMs", record.lastSeenMs)
                item.put("connectCount", record.connectCount)
                item.put("scanCount", record.scanCount)
                item.put("demoCount", record.demoCount)
                item.put("sampleCount", record.sampleCount)
                item.put("lastSessionId", record.lastSessionId)
                item.put("lastMode", record.lastMode)
                item.put("lastStatus", record.lastStatus)
                item.put("supportedPids", record.supportedPids)
                item.put("lastEventDetail", record.lastEventDetail)
            } catch (ignored: JSONException) {
                // Local fields are safe.
            }
            payload.put(item)
        }
        return payload
    }

    private fun overviewJson(db: SQLiteDatabase): JSONObject {
        val payload = JSONObject()
        payload.put("distanceMeters", trips.totalDistanceMeters())
        payload.put("maxSpeedKph", ObdStoreSupport.maxInt(db, VoltTrackerDb.TABLE_TELEMETRY, "speed_kph"))
        payload.put("avgSampleIntervalMs", ObdStoreSupport.averageSampleIntervalMs(db))
        payload.put(
            "drivingSamples",
            ObdStoreSupport.countRowsWhere(
                db,
                VoltTrackerDb.TABLE_TELEMETRY,
                "vehicle_state LIKE ?",
                arrayOf("%driving%"),
            ),
        )
        payload.put(
            "chargingHints",
            ObdStoreSupport.countRowsWhere(db, VoltTrackerDb.TABLE_TELEMETRY, "charge_transition_hint = 1", null),
        )
        payload.put("latestTelemetry", latestTelemetryJson(db))
        return payload
    }

    companion object {
        private val CHARGE_SESSION_COLUMNS =
            arrayOf(
                "_id",
                "started_at_ms",
                "ended_at_ms",
                "charger_type",
                "start_soc",
                "end_soc",
                "power_kw",
                "energy_kwh",
                "confidence",
            )

        @Throws(JSONException::class)
        private fun capabilityJsonFromCursor(cursor: Cursor): JSONObject =
            JSONObject()
                .put("id", cursor.getLong(cursor.getColumnIndexOrThrow("_id")))
                .put("adapterKey", ObdStoreSupport.clean(cursor.getString(cursor.getColumnIndexOrThrow("adapter_key"))))
                .put("protocol", ObdStoreSupport.clean(cursor.getString(cursor.getColumnIndexOrThrow("protocol"))))
                .put("header", ObdStoreSupport.clean(cursor.getString(cursor.getColumnIndexOrThrow("header"))))
                .put("command", ObdStoreSupport.clean(cursor.getString(cursor.getColumnIndexOrThrow("command"))))
                .put("pid", ObdStoreSupport.clean(cursor.getString(cursor.getColumnIndexOrThrow("pid"))))
                .put("name", ObdStoreSupport.clean(cursor.getString(cursor.getColumnIndexOrThrow("name"))))
                .put("unit", ObdStoreSupport.clean(cursor.getString(cursor.getColumnIndexOrThrow("unit"))))
                .put("supported", cursor.getInt(cursor.getColumnIndexOrThrow("supported")) == 1)
                .put("responseCount", cursor.getLong(cursor.getColumnIndexOrThrow("response_count")))
                .put("firstSeenMs", cursor.getLong(cursor.getColumnIndexOrThrow("first_seen_ms")))
                .put("lastSeenMs", cursor.getLong(cursor.getColumnIndexOrThrow("last_seen_ms")))
                .put("sample", ObdStoreSupport.parseObject(cursor.getString(cursor.getColumnIndexOrThrow("sample_json"))))

        private fun notFound(message: String): JSONObject =
            JSONObject().safePut("ok", false).safePut("error", "not_found").safePut("message", message)

        private fun error(message: String): JSONObject =
            JSONObject().safePut("ok", false).safePut("error", "export_failed").safePut("message", message)

        private inline fun safeJson(block: () -> JSONObject?): JSONObject =
            try {
                block() ?: JSONObject()
            } catch (ex: JSONException) {
                JSONObject()
            }

        private inline fun safeArray(block: () -> JSONArray?): JSONArray =
            try {
                block() ?: JSONArray()
            } catch (ex: JSONException) {
                JSONArray()
            }

        private fun latestDiagnosticCodeReports(
            db: SQLiteDatabase,
            limit: Int,
        ): List<DiagnosticCodeReport> {
            val reports = ArrayList<DiagnosticCodeReport>()
            db
                .query(
                    VoltTrackerDb.TABLE_DIAGNOSTIC_CODES,
                    DiagnosticCodeReport.QUERY_COLUMNS,
                    null,
                    null,
                    null,
                    null,
                    "last_seen_ms DESC",
                    ObdStoreSupport.boundedLimit(limit),
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        reports.add(DiagnosticCodeReport.fromCursor(cursor))
                    }
                }
            return reports
        }

        private fun diagnosticCodeStatusCounts(db: SQLiteDatabase): Map<String, Long> {
            val payload = LinkedHashMap<String, Long>()
            db
                .rawQuery(
                    "SELECT status, COUNT(*) AS count FROM ${VoltTrackerDb.TABLE_DIAGNOSTIC_CODES} GROUP BY status",
                    null,
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        var key = ObdStoreSupport.clean(cursor.getString(cursor.getColumnIndexOrThrow("status")))
                        if (key.isEmpty()) {
                            key = "stored"
                        }
                        payload[key] = cursor.getLong(cursor.getColumnIndexOrThrow("count"))
                    }
                }
            return payload
        }

        @Throws(JSONException::class)
        private fun chargeSummaryJson(db: SQLiteDatabase): JSONObject =
            JSONObject()
                .put("chargeSessionCount", ObdStoreSupport.countRows(db, VoltTrackerDb.TABLE_CHARGE_SESSIONS))
                .put(
                    "chargingHintCount",
                    ObdStoreSupport.countRowsWhere(
                        db,
                        VoltTrackerDb.TABLE_TELEMETRY,
                        "charge_transition_hint = 1",
                        null,
                    ),
                ).put("maxPowerKw", ObdStoreSupport.maxDouble(db, VoltTrackerDb.TABLE_TELEMETRY, "power_kw"))
                .put("latest", latestChargeSessionJson(db))
                .put("recentSessions", recentChargeSessionsJson(db, 12))

        @Throws(JSONException::class)
        private fun batterySummaryJson(db: SQLiteDatabase): JSONObject =
            JSONObject()
                .put("snapshotCount", ObdStoreSupport.countRows(db, VoltTrackerDb.TABLE_BATTERY_SNAPSHOTS))
                .put("cellSnapshotCount", ObdStoreSupport.countRows(db, VoltTrackerDb.TABLE_CELL_SNAPSHOTS))
                .put("latestTelemetry", latestTelemetryJson(db))
                .put("latestBatterySnapshot", latestBatterySnapshotJson(db))

        @JvmStatic
        @Throws(JSONException::class)
        fun latestTelemetryJson(db: SQLiteDatabase): JSONObject {
            db
                .query(
                    VoltTrackerDb.TABLE_TELEMETRY,
                    arrayOf(
                        "captured_at_ms",
                        "vehicle_state",
                        "speed_kph",
                        "rpm",
                        "voltage",
                        "soc",
                        "battery_temp",
                        "power_kw",
                        "pack_voltage",
                        "pack_current_a",
                        "json",
                    ),
                    ObdStoreSupport.USEFUL_TELEMETRY_WHERE,
                    null,
                    null,
                    null,
                    "captured_at_ms DESC",
                    "1",
                ).use { cursor ->
                    if (!cursor.moveToFirst()) {
                        return JSONObject()
                    }
                    val item = ObdStoreSupport.parseObject(cursor.getString(cursor.getColumnIndexOrThrow("json")))
                    item.put("capturedAtMs", cursor.getLong(cursor.getColumnIndexOrThrow("captured_at_ms")))
                    item.put(
                        "vehicleState",
                        ObdStoreSupport.clean(cursor.getString(cursor.getColumnIndexOrThrow("vehicle_state"))),
                    )
                    item.put("speedKph", boxedOrNull(ObdStoreSupport.nullableIntBoxed(cursor, "speed_kph")))
                    item.put("rpm", boxedOrNull(ObdStoreSupport.nullableIntBoxed(cursor, "rpm")))
                    item.put("voltage", boxedOrNull(ObdStoreSupport.nullableDoubleBoxed(cursor, "voltage")))
                    item.put("soc", boxedOrNull(ObdStoreSupport.nullableDoubleBoxed(cursor, "soc")))
                    item.put("batteryTemp", boxedOrNull(ObdStoreSupport.nullableDoubleBoxed(cursor, "battery_temp")))
                    item.put("powerKw", boxedOrNull(ObdStoreSupport.nullableDoubleBoxed(cursor, "power_kw")))
                    item.put("packVoltage", boxedOrNull(ObdStoreSupport.nullableDoubleBoxed(cursor, "pack_voltage")))
                    item.put("packCurrentA", boxedOrNull(ObdStoreSupport.nullableDoubleBoxed(cursor, "pack_current_a")))
                    return item
                }
        }

        private fun boxedOrNull(value: Number?): Any = value ?: JSONObject.NULL

        @Throws(JSONException::class)
        private fun chargeSessionRowJson(cursor: Cursor): JSONObject {
            val chargerType = cursor.getString(cursor.getColumnIndexOrThrow("charger_type"))
            return JSONObject()
                .put("id", cursor.getLong(cursor.getColumnIndexOrThrow("_id")))
                .put("startedAtMs", cursor.getLong(cursor.getColumnIndexOrThrow("started_at_ms")))
                .put("endedAtMs", boxedOrNull(ObdStoreSupport.nullableLongBoxed(cursor, "ended_at_ms")))
                .put("chargerType", if (chargerType == null) JSONObject.NULL else ObdStoreSupport.clean(chargerType))
                .put("startSoc", boxedOrNull(ObdStoreSupport.nullableDoubleBoxed(cursor, "start_soc")))
                .put("endSoc", boxedOrNull(ObdStoreSupport.nullableDoubleBoxed(cursor, "end_soc")))
                .put("powerKw", boxedOrNull(ObdStoreSupport.nullableDoubleBoxed(cursor, "power_kw")))
                .put("energyKwh", boxedOrNull(ObdStoreSupport.nullableDoubleBoxed(cursor, "energy_kwh")))
                .put("confidence", boxedOrNull(ObdStoreSupport.nullableDoubleBoxed(cursor, "confidence")))
        }

        @Throws(JSONException::class)
        private fun latestChargeSessionJson(db: SQLiteDatabase): JSONObject =
            db
                .query(
                    VoltTrackerDb.TABLE_CHARGE_SESSIONS,
                    CHARGE_SESSION_COLUMNS,
                    null,
                    null,
                    null,
                    null,
                    "started_at_ms DESC",
                    "1",
                ).use { cursor ->
                    if (cursor.moveToFirst()) chargeSessionRowJson(cursor) else JSONObject()
                }

        @Throws(JSONException::class)
        private fun recentChargeSessionsJson(
            db: SQLiteDatabase,
            limit: Int,
        ): JSONArray {
            val out = JSONArray()
            db
                .query(
                    VoltTrackerDb.TABLE_CHARGE_SESSIONS,
                    CHARGE_SESSION_COLUMNS,
                    null,
                    null,
                    null,
                    null,
                    "started_at_ms DESC",
                    maxOf(1, limit).toString(),
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        out.put(chargeSessionRowJson(cursor))
                    }
                }
            return out
        }

        @Throws(JSONException::class)
        private fun latestBatterySnapshotJson(db: SQLiteDatabase): JSONObject {
            db
                .query(
                    VoltTrackerDb.TABLE_BATTERY_SNAPSHOTS,
                    arrayOf(
                        "_id",
                        "captured_at_ms",
                        "soc",
                        "capacity_ah",
                        "soh_pct",
                        "pack_voltage",
                        "pack_current_a",
                        "pack_power_kw",
                        "battery_temp_c",
                    ),
                    null,
                    null,
                    null,
                    null,
                    "captured_at_ms DESC",
                    "1",
                ).use { cursor ->
                    if (!cursor.moveToFirst()) {
                        return JSONObject()
                    }
                    return JSONObject()
                        .put("id", cursor.getLong(cursor.getColumnIndexOrThrow("_id")))
                        .put("capturedAtMs", cursor.getLong(cursor.getColumnIndexOrThrow("captured_at_ms")))
                        .put("soc", boxedOrNull(ObdStoreSupport.nullableDoubleBoxed(cursor, "soc")))
                        .put("capacityAh", boxedOrNull(ObdStoreSupport.nullableDoubleBoxed(cursor, "capacity_ah")))
                        .put("sohPct", boxedOrNull(ObdStoreSupport.nullableDoubleBoxed(cursor, "soh_pct")))
                        .put("packVoltage", boxedOrNull(ObdStoreSupport.nullableDoubleBoxed(cursor, "pack_voltage")))
                        .put("packCurrentA", boxedOrNull(ObdStoreSupport.nullableDoubleBoxed(cursor, "pack_current_a")))
                        .put("packPowerKw", boxedOrNull(ObdStoreSupport.nullableDoubleBoxed(cursor, "pack_power_kw")))
                        .put("batteryTempC", boxedOrNull(ObdStoreSupport.nullableDoubleBoxed(cursor, "battery_temp_c")))
                }
        }

        private fun JSONObject.safePut(
            key: String,
            value: Any?,
        ): JSONObject =
            try {
                put(key, value)
            } catch (ignored: JSONException) {
                this
            }
    }
}
