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
        val db = helper.readableDatabase
        if (ObdTripExclusions.isHidden(db, routeKey)) {
            return JSONObject()
        }
        return try {
            ObdStoreRouteProjection.routeForSession(
                db,
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
                        items.put(ObdStoreReportJson.capabilityFromCursor(cursor))
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
                    return ObdStoreReportJson.notFound("detailed signal log not found")
                }
                return try {
                    JSONObject()
                        .put("ok", true)
                        .put("kind", "detailed-signal-log")
                        .put("item", ObdStoreReportJson.capabilityFromCursor(cursor))
                } catch (ex: JSONException) {
                    ObdStoreReportJson.exportError("detailed signal log could not be exported")
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
        val counts = storageCountsProjection(db, databaseFile)
        val reviewSession = ObdStoreSessionReview.latestReviewableSession(db)
        return StorageSummaryRecord(
            counts.database,
            counts.databaseBytes,
            counts.sessionCount,
            counts.rawTelemetryCount,
            counts.sampleCount,
            counts.emptyTelemetryCount,
            counts.eventCount,
            counts.adapterCount,
            counts.pidObservationCount,
            counts.diagnosticCodeCount,
            counts.diagnosticCodeStatusCounts,
            counts.locationSampleCount,
            counts.vehicleCount,
            counts.fieldCapabilityCount,
            counts.tripSegmentCount,
            counts.chargeSessionCount,
            counts.batterySnapshotCount,
            counts.cellSnapshotCount,
            counts.exportCount,
            counts.latestSession,
            recentSessionSummaries(db, 6),
            getAdapterHistory(6),
            latestDiagnosticCodeReports(db, 12),
            if (reviewSession ==
                null
            ) {
                JSONObject()
            } else {
                safeJson { ObdStoreSessionReview.sessionReview(db, reviewSession) }
            },
            if (reviewSession == null) {
                JSONObject()
            } else {
                safeJson { ObdStoreRouteProjection.routeForSession(db, reviewSession, 240) }
            },
            recentRoutesProjectionJson(8, 500),
            overviewProjectionJson(),
            chargeSummaryProjectionJson(),
            batterySummaryProjectionJson(),
            safeJson { latestVehicleJson(db) },
            safeArray { enhancedCapabilitiesJson(24) },
        )
    }

    fun storageCountsJson(databaseFile: File): JSONObject =
        storageCountsProjection(helper.readableDatabase, databaseFile).toJson()

    fun recentRoutesProjectionJson(
        limit: Int,
        pointLimit: Int,
    ): JSONArray = safeArray { ObdStoreRouteProjection.recentRoutes(helper.readableDatabase, limit, pointLimit) }

    fun diagnosticsSummaryJson(limit: Int): JSONObject {
        val db = helper.readableDatabase
        val latest = JSONArray()
        for (report in latestDiagnosticCodeReports(db, limit)) {
            latest.put(report.toJson())
        }
        return JSONObject()
            .put("diagnosticCodeCount", ObdStoreSupport.countRows(db, VoltTrackerDb.TABLE_DIAGNOSTIC_CODES))
            .put("statusCounts", ObdStoreReportJson.statusCounts(diagnosticCodeStatusCounts(db)))
            .put("latestDiagnosticCodes", latest)
    }

    fun overviewProjectionJson(): JSONObject = safeJson { overviewJson(helper.readableDatabase) }

    fun chargeSummaryProjectionJson(): JSONObject = safeJson { chargeSummaryJson(helper.readableDatabase) }

    fun batterySummaryProjectionJson(): JSONObject = safeJson { batterySummaryJson(helper.readableDatabase) }

    private fun recentSessionSummaries(
        db: SQLiteDatabase,
        limit: Int,
    ): List<RecentSessionSummaryRecord> {
        val sessions = getRecentSessions(limit)
        val usefulBySession = ObdStoreSupport.usefulSampleCountsBySession(db, sessions.map { it.id })
        val records = ArrayList<RecentSessionSummaryRecord>(sessions.size)
        for (session in sessions) {
            val usefulSamples = usefulBySession[session.id] ?: 0L
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
                arrayOf(
                    "_id",
                    "display_name",
                    "make",
                    "model",
                    "model_year",
                    "vin_redacted",
                    "first_seen_ms",
                    "last_seen_ms",
                ),
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
        val sessions = getRecentSessions(limit)
        val usefulBySession = ObdStoreSupport.usefulSampleCountsBySession(db, sessions.map { it.id })
        for (record in sessions) {
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
                val usefulSamples = usefulBySession[record.id] ?: 0L
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

        private fun chargeSessionColumns(alias: String): String =
            CHARGE_SESSION_COLUMNS.joinToString(", ") {
                "$alias.$it AS $it"
            }

        private fun plausibleChargeWhere(alias: String): String =
            "NOT EXISTS (" +
                "SELECT 1 FROM ${VoltTrackerDb.TABLE_TELEMETRY} t " +
                "WHERE t.session_id = $alias.session_id " +
                "AND t.captured_at_ms >= $alias.started_at_ms " +
                "AND t.captured_at_ms <= COALESCE($alias.ended_at_ms, $alias.started_at_ms) " +
                "AND t.speed_kph IS NOT NULL AND t.speed_kph >= $CHARGE_DRIVING_SPEED_KPH" +
                ") AND NOT EXISTS (" +
                "SELECT 1 FROM ${VoltTrackerDb.TABLE_LOCATION_SAMPLES} l " +
                "WHERE l.session_id = $alias.session_id " +
                "AND l.captured_at_ms >= $alias.started_at_ms " +
                "AND l.captured_at_ms <= COALESCE($alias.ended_at_ms, $alias.started_at_ms) " +
                "AND l.speed_mps IS NOT NULL AND l.speed_mps >= $CHARGE_DRIVING_SPEED_MPS" +
                ")"

        private const val MIN_INFERRED_CHARGE_SOC_GAIN = 8.0
        private const val MIN_INFERRED_CHARGE_GAP_MS = 10L * 60_000L
        private const val MIN_OBSERVED_CHARGE_SOC_GAIN = 5.0
        private const val MIN_OBSERVED_CHARGE_DURATION_MS = 10L * 60_000L
        private const val MIN_OBSERVED_CHARGE_ENERGY_KWH = 1.0
        private const val MIN_OBSERVED_ENERGY_DURATION_MS = 20L * 60_000L
        private const val ESTIMATED_USABLE_BATTERY_KWH = 14.0
        private const val CHARGE_DRIVING_SPEED_KPH = 5
        private const val CHARGE_DRIVING_SPEED_MPS = CHARGE_DRIVING_SPEED_KPH / 3.6

        private data class ChargeSummaryRow(
            val id: Any,
            val startedAtMs: Long,
            val endedAtMs: Long?,
            val chargerType: String?,
            val startSoc: Double?,
            val endSoc: Double?,
            val powerKw: Double?,
            val energyKwh: Double?,
            val confidence: Double?,
        )

        private data class DriveSocBoundary(
            val sessionId: Long,
            val startedAtMs: Long,
            val endedAtMs: Long,
            val startSoc: Double,
            val endSoc: Double,
        )

        private data class SocDriveSample(
            val sessionId: Long,
            val atMs: Long,
            val soc: Double,
        )

        private data class StorageCountsProjection(
            val database: String,
            val databaseBytes: Long,
            val sessionCount: Long,
            val rawTelemetryCount: Long,
            val sampleCount: Long,
            val emptyTelemetryCount: Long,
            val eventCount: Long,
            val adapterCount: Long,
            val pidObservationCount: Long,
            val diagnosticCodeCount: Long,
            val diagnosticCodeStatusCounts: Map<String, Long>,
            val locationSampleCount: Long,
            val vehicleCount: Long,
            val fieldCapabilityCount: Long,
            val tripSegmentCount: Long,
            val chargeSessionCount: Long,
            val batterySnapshotCount: Long,
            val cellSnapshotCount: Long,
            val exportCount: Long,
            val latestSession: ObdSessionRecord?,
        ) {
            fun toJson(): JSONObject =
                JSONObject()
                    .put("database", database)
                    .put("databaseBytes", databaseBytes)
                    .put("sessionCount", sessionCount)
                    .put("rawTelemetryCount", rawTelemetryCount)
                    .put("sampleCount", sampleCount)
                    .put("emptyTelemetryCount", emptyTelemetryCount)
                    .put("eventCount", eventCount)
                    .put("adapterCount", adapterCount)
                    .put("pidObservationCount", pidObservationCount)
                    .put("diagnosticCodeCount", diagnosticCodeCount)
                    .put("diagnosticCodeStatusCounts", ObdStoreReportJson.statusCounts(diagnosticCodeStatusCounts))
                    .put("locationSampleCount", locationSampleCount)
                    .put("vehicleCount", vehicleCount)
                    .put("fieldCapabilityCount", fieldCapabilityCount)
                    .put("tripSegmentCount", tripSegmentCount)
                    .put("chargeSessionCount", chargeSessionCount)
                    .put("batterySnapshotCount", batterySnapshotCount)
                    .put("cellSnapshotCount", cellSnapshotCount)
                    .put("exportCount", exportCount)
                    .put("lastSessionId", latestSession?.id ?: JSONObject.NULL)
        }

        private fun storageCountsProjection(
            db: SQLiteDatabase,
            databaseFile: File,
        ): StorageCountsProjection {
            val rawTelemetryCount = ObdStoreSupport.countRows(db, VoltTrackerDb.TABLE_TELEMETRY)
            val usefulTelemetryCount =
                ObdStoreSupport.countRowsWhere(
                    db,
                    VoltTrackerDb.TABLE_TELEMETRY,
                    ObdStoreSupport.USEFUL_TELEMETRY_WHERE,
                    null,
                )
            val latest =
                db
                    .query(
                        VoltTrackerDb.TABLE_SESSIONS,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "started_at_ms DESC",
                        "1",
                    ).use { cursor ->
                        if (cursor.moveToFirst()) ObdStoreSupport.readSession(cursor) else null
                    }
            return StorageCountsProjection(
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
            )
        }

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
        private fun chargeSummaryJson(db: SQLiteDatabase): JSONObject {
            val rows = chargeSummaryRows(db)
            return JSONObject()
                .put("chargeSessionCount", rows.size)
                .put(
                    "chargingHintCount",
                    ObdStoreSupport.countRowsWhere(
                        db,
                        VoltTrackerDb.TABLE_TELEMETRY,
                        "charge_transition_hint = 1",
                        null,
                    ),
                ).put("maxPowerKw", maxChargePowerKw(rows))
                .put("latest", if (rows.isEmpty()) JSONObject() else chargeSummaryRowJson(rows[0]))
                .put("recentSessions", chargeSummaryRowsJson(rows, 12))
        }

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
                    item.put("speedKph", reportBoxed(ObdStoreSupport.nullableIntBoxed(cursor, "speed_kph")))
                    item.put("rpm", reportBoxed(ObdStoreSupport.nullableIntBoxed(cursor, "rpm")))
                    item.put("voltage", reportBoxed(ObdStoreSupport.nullableDoubleBoxed(cursor, "voltage")))
                    item.put("soc", reportBoxed(ObdStoreSupport.nullableDoubleBoxed(cursor, "soc")))
                    item.put("batteryTemp", reportBoxed(ObdStoreSupport.nullableDoubleBoxed(cursor, "battery_temp")))
                    item.put("powerKw", reportBoxed(ObdStoreSupport.nullableDoubleBoxed(cursor, "power_kw")))
                    item.put("packVoltage", reportBoxed(ObdStoreSupport.nullableDoubleBoxed(cursor, "pack_voltage")))
                    item.put("packCurrentA", reportBoxed(ObdStoreSupport.nullableDoubleBoxed(cursor, "pack_current_a")))
                    return item
                }
        }

        private fun boxedOrNull(value: Number?): Any = value ?: JSONObject.NULL

        private fun chargeSummaryRows(db: SQLiteDatabase): List<ChargeSummaryRow> {
            val inferred = inferredChargeRows(db)
            val observed =
                observedChargeRows(db)
                    .filterNot { observed ->
                        inferred.any { inferredRow -> isInsideInferredCharge(observed, inferredRow) }
                    }
            return (inferred + observed).sortedWith(
                compareByDescending<ChargeSummaryRow> { it.startedAtMs }.thenByDescending { it.endedAtMs ?: 0L },
            )
        }

        private fun observedChargeRows(db: SQLiteDatabase): List<ChargeSummaryRow> {
            val rows = ArrayList<ChargeSummaryRow>()
            db
                .rawQuery(
                    "SELECT ${chargeSessionColumns("c")} FROM ${VoltTrackerDb.TABLE_CHARGE_SESSIONS} c " +
                        "WHERE ${plausibleChargeWhere("c")} ORDER BY c.started_at_ms DESC",
                    null,
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val row = chargeSessionRow(cursor)
                        if (isMeaningfulObservedCharge(row)) {
                            rows.add(row)
                        }
                    }
                }
            return rows
        }

        private fun isMeaningfulObservedCharge(row: ChargeSummaryRow): Boolean {
            val durationMs = (row.endedAtMs ?: row.startedAtMs) - row.startedAtMs
            val startSoc = row.startSoc
            val endSoc = row.endSoc
            if (startSoc != null &&
                endSoc != null &&
                endSoc - startSoc >= MIN_OBSERVED_CHARGE_SOC_GAIN &&
                durationMs >= MIN_OBSERVED_CHARGE_DURATION_MS
            ) {
                return true
            }
            val energyKwh = row.energyKwh
            val powerKw = row.powerKw
            return energyKwh != null &&
                powerKw != null &&
                energyKwh >= MIN_OBSERVED_CHARGE_ENERGY_KWH &&
                powerKw > 0.0 &&
                durationMs >= MIN_OBSERVED_ENERGY_DURATION_MS
        }

        private fun inferredChargeRows(db: SQLiteDatabase): List<ChargeSummaryRow> {
            val rows = ArrayList<ChargeSummaryRow>()
            rows.addAll(inferredChargeRowsBetweenDriveBoundaries(db))
            rows.addAll(inferredChargeRowsWithinSessions(db))
            return mergeInferredChargeRows(rows)
        }

        private fun inferredChargeRowsBetweenDriveBoundaries(db: SQLiteDatabase): List<ChargeSummaryRow> {
            val boundaries = meaningfulDriveSocBoundaries(db)
            if (boundaries.size < 2) {
                return emptyList()
            }
            val rows = ArrayList<ChargeSummaryRow>()
            var previous = boundaries[0]
            for (i in 1 until boundaries.size) {
                val current = boundaries[i]
                val socGain = current.startSoc - previous.endSoc
                val gapMs = current.startedAtMs - previous.endedAtMs
                if (socGain >= MIN_INFERRED_CHARGE_SOC_GAIN && gapMs >= MIN_INFERRED_CHARGE_GAP_MS) {
                    rows.add(
                        ChargeSummaryRow(
                            "inferred:${previous.sessionId}:${previous.endedAtMs}:${current.sessionId}:${current.startedAtMs}",
                            previous.endedAtMs,
                            current.startedAtMs,
                            "inferred",
                            previous.endSoc,
                            current.startSoc,
                            null,
                            (socGain / 100.0) * ESTIMATED_USABLE_BATTERY_KWH,
                            0.7,
                        ),
                    )
                }
                previous = current
            }
            return rows
        }

        private fun inferredChargeRowsWithinSessions(db: SQLiteDatabase): List<ChargeSummaryRow> {
            val rows = ArrayList<ChargeSummaryRow>()
            val sessions =
                ObdStoreSupport
                    .getAllSessions(db)
                    .filter { ObdSessionClassifier.isTripSession(db, it) }
                    .sortedBy { it.startedAtMs }
            for (session in sessions) {
                var previous: SocDriveSample? = null
                db
                    .rawQuery(
                        "SELECT captured_at_ms, soc FROM ${VoltTrackerDb.TABLE_TELEMETRY} " +
                            "WHERE session_id = ? AND soc IS NOT NULL AND speed_kph IS NOT NULL " +
                            "AND speed_kph >= ? ORDER BY captured_at_ms ASC",
                        arrayOf(session.id.toString(), CHARGE_DRIVING_SPEED_KPH.toString()),
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            val current = SocDriveSample(session.id, cursor.getLong(0), cursor.getDouble(1))
                            val last = previous
                            if (last != null) {
                                maybeInferredChargeRow(last, current)?.let { rows.add(it) }
                            }
                            previous = current
                        }
                    }
            }
            return rows
        }

        private fun maybeInferredChargeRow(
            previous: SocDriveSample,
            current: SocDriveSample,
        ): ChargeSummaryRow? {
            val socGain = current.soc - previous.soc
            val gapMs = current.atMs - previous.atMs
            if (socGain < MIN_INFERRED_CHARGE_SOC_GAIN || gapMs < MIN_INFERRED_CHARGE_GAP_MS) {
                return null
            }
            return ChargeSummaryRow(
                "inferred:${previous.sessionId}:${previous.atMs}:${current.sessionId}:${current.atMs}",
                previous.atMs,
                current.atMs,
                "inferred",
                previous.soc,
                current.soc,
                null,
                (socGain / 100.0) * ESTIMATED_USABLE_BATTERY_KWH,
                0.7,
            )
        }

        private fun mergeInferredChargeRows(rows: List<ChargeSummaryRow>): List<ChargeSummaryRow> {
            if (rows.size < 2) {
                return rows
            }
            val merged = ArrayList<ChargeSummaryRow>()
            for (row in rows.sortedWith(compareBy<ChargeSummaryRow> { it.startedAtMs }.thenBy { it.endedAtMs ?: 0L })) {
                val existingIndex = merged.indexOfFirst { existing -> chargeIntervalsOverlap(existing, row) }
                if (existingIndex < 0) {
                    merged.add(row)
                    continue
                }
                if (socGain(row) > socGain(merged[existingIndex])) {
                    merged[existingIndex] = row
                }
            }
            return merged
        }

        private fun chargeIntervalsOverlap(
            left: ChargeSummaryRow,
            right: ChargeSummaryRow,
        ): Boolean {
            val leftEnd = left.endedAtMs ?: left.startedAtMs
            val rightEnd = right.endedAtMs ?: right.startedAtMs
            return left.startedAtMs <= rightEnd && right.startedAtMs <= leftEnd
        }

        private fun socGain(row: ChargeSummaryRow): Double {
            val start = row.startSoc ?: return 0.0
            val end = row.endSoc ?: return 0.0
            return end - start
        }

        private fun meaningfulDriveSocBoundaries(db: SQLiteDatabase): List<DriveSocBoundary> {
            val sessions =
                ObdStoreSupport
                    .getAllSessions(db)
                    .filter { ObdSessionClassifier.isTripSession(db, it) }
                    .sortedBy { it.startedAtMs }
            if (sessions.isEmpty()) {
                return emptyList()
            }
            val windowsBySession = DriveWindowDetector.windowsForSessions(db, sessions)
            val boundaries = ArrayList<DriveSocBoundary>()
            for (session in sessions) {
                for (window in windowsBySession[session.id].orEmpty()) {
                    val points =
                        ObdStoreRouteProjection.routePointsForSessionJson(
                            db,
                            session.id,
                            1000,
                            window.startedAtMs,
                            window.endedAtMs,
                        )
                    val distanceMeters = ObdStoreSupport.distanceMeters(points)
                    val maxSpeed = ObdSessionClassifier.maxSpeedKphForWindow(db, session.id, window)
                    if (!ObdSessionClassifier.isMeaningfulTrip(points.length(), distanceMeters, maxSpeed)) {
                        continue
                    }
                    val startSoc = socForWindow(db, session.id, window, "ASC") ?: continue
                    val endSoc = socForWindow(db, session.id, window, "DESC") ?: continue
                    boundaries.add(DriveSocBoundary(session.id, window.startedAtMs, window.endedAtMs, startSoc, endSoc))
                }
            }
            return boundaries.sortedBy { it.startedAtMs }
        }

        private fun socForWindow(
            db: SQLiteDatabase,
            sessionId: Long,
            window: DriveWindowDetector.DriveWindow,
            order: String,
        ): Double? =
            db
                .rawQuery(
                    "SELECT soc FROM ${VoltTrackerDb.TABLE_TELEMETRY} " +
                        "WHERE session_id = ? AND captured_at_ms >= ? AND captured_at_ms <= ? " +
                        "AND soc IS NOT NULL ORDER BY captured_at_ms $order LIMIT 1",
                    arrayOf(sessionId.toString(), window.startedAtMs.toString(), window.endedAtMs.toString()),
                ).use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getDouble(0) else null
                }

        private fun isInsideInferredCharge(
            observed: ChargeSummaryRow,
            inferred: ChargeSummaryRow,
        ): Boolean {
            val observedEnd = observed.endedAtMs ?: observed.startedAtMs
            val inferredEnd = inferred.endedAtMs ?: inferred.startedAtMs
            return observed.startedAtMs >= inferred.startedAtMs && observedEnd <= inferredEnd
        }

        private fun maxChargePowerKw(rows: List<ChargeSummaryRow>): Double {
            var max = 0.0
            for (row in rows) {
                val power = row.powerKw
                if (power != null && !power.isNaN() && !power.isInfinite() && power > max) {
                    max = power
                }
            }
            return max
        }

        private fun chargeSessionRow(cursor: Cursor): ChargeSummaryRow {
            val chargerType = cursor.getString(cursor.getColumnIndexOrThrow("charger_type"))
            return ChargeSummaryRow(
                cursor.getLong(cursor.getColumnIndexOrThrow("_id")),
                cursor.getLong(cursor.getColumnIndexOrThrow("started_at_ms")),
                ObdStoreSupport.nullableLongBoxed(cursor, "ended_at_ms"),
                if (chargerType == null) null else ObdStoreSupport.clean(chargerType),
                ObdStoreSupport.nullableDoubleBoxed(cursor, "start_soc"),
                ObdStoreSupport.nullableDoubleBoxed(cursor, "end_soc"),
                ObdStoreSupport.nullableDoubleBoxed(cursor, "power_kw"),
                ObdStoreSupport.nullableDoubleBoxed(cursor, "energy_kwh"),
                ObdStoreSupport.nullableDoubleBoxed(cursor, "confidence"),
            )
        }

        @Throws(JSONException::class)
        private fun chargeSummaryRowJson(row: ChargeSummaryRow): JSONObject =
            JSONObject()
                .put("id", row.id)
                .put("startedAtMs", row.startedAtMs)
                .put("endedAtMs", boxedOrNull(row.endedAtMs))
                .put("chargerType", row.chargerType ?: JSONObject.NULL)
                .put("startSoc", boxedOrNull(row.startSoc))
                .put("endSoc", boxedOrNull(row.endSoc))
                .put("powerKw", boxedOrNull(row.powerKw))
                .put("energyKwh", boxedOrNull(row.energyKwh))
                .put("confidence", boxedOrNull(row.confidence))

        @Throws(JSONException::class)
        private fun chargeSummaryRowsJson(
            rows: List<ChargeSummaryRow>,
            limit: Int,
        ): JSONArray {
            val out = JSONArray()
            val count = minOf(maxOf(1, limit), rows.size)
            for (i in 0 until count) {
                out.put(chargeSummaryRowJson(rows[i]))
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
                        .put("soc", reportBoxed(ObdStoreSupport.nullableDoubleBoxed(cursor, "soc")))
                        .put("capacityAh", reportBoxed(ObdStoreSupport.nullableDoubleBoxed(cursor, "capacity_ah")))
                        .put("sohPct", reportBoxed(ObdStoreSupport.nullableDoubleBoxed(cursor, "soh_pct")))
                        .put("packVoltage", reportBoxed(ObdStoreSupport.nullableDoubleBoxed(cursor, "pack_voltage")))
                        .put("packCurrentA", reportBoxed(ObdStoreSupport.nullableDoubleBoxed(cursor, "pack_current_a")))
                        .put("packPowerKw", reportBoxed(ObdStoreSupport.nullableDoubleBoxed(cursor, "pack_power_kw")))
                        .put("batteryTempC", reportBoxed(ObdStoreSupport.nullableDoubleBoxed(cursor, "battery_temp_c")))
                }
        }

        private fun reportBoxed(value: Number?): Any = ObdStoreReportJson.boxedOrNull(value)
    }
}
