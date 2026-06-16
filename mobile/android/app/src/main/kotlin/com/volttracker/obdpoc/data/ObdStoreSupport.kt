package com.volttracker.obdpoc.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

/**
 * Stateless helpers shared by the [ObdLocalStore] layer.
 */
object ObdStoreSupport {
    const val USEFUL_TELEMETRY_WHERE: String =
        "(" +
            "speed_kph IS NOT NULL" +
            " OR rpm IS NOT NULL" +
            " OR coolant_c IS NOT NULL" +
            " OR load_pct IS NOT NULL" +
            " OR throttle_pct IS NOT NULL" +
            " OR voltage IS NOT NULL" +
            " OR soc IS NOT NULL" +
            " OR battery_temp IS NOT NULL" +
            " OR power_kw IS NOT NULL" +
            " OR pack_voltage IS NOT NULL" +
            " OR pack_current_a IS NOT NULL" +
            " OR latitude IS NOT NULL" +
            " OR longitude IS NOT NULL" +
            ")"

    @JvmStatic
    fun putOptionalInt(
        values: ContentValues,
        column: String,
        json: JSONObject,
        key: String,
    ) {
        if (json.has(key) && !json.isNull(key)) {
            values.put(column, json.optInt(key))
        }
    }

    @JvmStatic
    fun putOptionalLong(
        values: ContentValues,
        column: String,
        json: JSONObject,
        key: String,
    ) {
        if (json.has(key) && !json.isNull(key)) {
            values.put(column, json.optLong(key))
        }
    }

    @JvmStatic
    fun putOptionalBool(
        values: ContentValues,
        column: String,
        json: JSONObject,
        key: String,
    ) {
        if (json.has(key) && !json.isNull(key)) {
            values.put(column, if (json.optBoolean(key)) 1 else 0)
        }
    }

    @JvmStatic
    fun putOptionalDouble(
        values: ContentValues,
        column: String,
        json: JSONObject,
        key: String,
    ) {
        if (json.has(key) && !json.isNull(key)) {
            values.put(column, json.optDouble(key))
        }
    }

    @JvmStatic
    fun putOptionalDouble(
        values: ContentValues,
        column: String,
        json: JSONObject,
        primaryKey: String,
        fallbackKey: String,
    ) {
        if (json.has(primaryKey) && !json.isNull(primaryKey)) {
            values.put(column, json.optDouble(primaryKey))
        } else if (json.has(fallbackKey) && !json.isNull(fallbackKey)) {
            values.put(column, json.optDouble(fallbackKey))
        }
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun putNullable(
        json: JSONObject,
        key: String,
        value: Double?,
    ) {
        if (value != null) {
            json.put(key, value)
        }
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun putNullable(
        json: JSONObject,
        key: String,
        value: Long?,
    ) {
        if (value != null) {
            json.put(key, value)
        }
    }

    @JvmStatic
    fun optTimestamp(
        json: JSONObject,
        key: String,
        fallback: Long,
    ): Long = if (json.has(key) && !json.isNull(key)) json.optLong(key, fallback) else fallback

    @JvmStatic
    fun nullableInt(
        cursor: Cursor,
        column: String,
    ): Int {
        val index = cursor.getColumnIndexOrThrow(column)
        return if (cursor.isNull(index)) 0 else cursor.getInt(index)
    }

    @JvmStatic
    fun nullableIntBoxed(
        cursor: Cursor,
        column: String,
    ): Int? {
        val index = cursor.getColumnIndexOrThrow(column)
        return if (cursor.isNull(index)) null else cursor.getInt(index)
    }

    @JvmStatic
    fun nullableLong(
        cursor: Cursor,
        column: String,
    ): Long {
        val index = cursor.getColumnIndexOrThrow(column)
        return if (cursor.isNull(index)) 0L else cursor.getLong(index)
    }

    @JvmStatic
    fun nullableLongBoxed(
        cursor: Cursor,
        column: String,
    ): Long? {
        val index = cursor.getColumnIndexOrThrow(column)
        return if (cursor.isNull(index)) null else cursor.getLong(index)
    }

    @JvmStatic
    fun nullableDouble(
        cursor: Cursor,
        column: String,
    ): Double {
        val index = cursor.getColumnIndexOrThrow(column)
        return if (cursor.isNull(index)) 0.0 else cursor.getDouble(index)
    }

    @JvmStatic
    fun nullableDoubleBoxed(
        cursor: Cursor,
        column: String,
    ): Double? {
        val index = cursor.getColumnIndexOrThrow(column)
        return if (cursor.isNull(index)) null else cursor.getDouble(index)
    }

    @JvmStatic
    fun readSession(cursor: Cursor): ObdSessionRecord =
        ObdSessionRecord(
            cursor.getLong(cursor.getColumnIndexOrThrow("_id")),
            cursor.getString(cursor.getColumnIndexOrThrow("mode")),
            cursor.getString(cursor.getColumnIndexOrThrow("adapter_address")),
            cursor.getString(cursor.getColumnIndexOrThrow("adapter_name")),
            cursor.getLong(cursor.getColumnIndexOrThrow("started_at_ms")),
            nullableLong(cursor, "ended_at_ms"),
            cursor.getString(cursor.getColumnIndexOrThrow("status")),
            cursor.getString(cursor.getColumnIndexOrThrow("supported_pids")),
            cursor.getInt(cursor.getColumnIndexOrThrow("sample_count")),
            nullableLong(cursor, "last_event_at_ms"),
        )

    @JvmStatic
    fun readTelemetry(cursor: Cursor): TelemetrySampleRecord =
        TelemetrySampleRecord(
            cursor.getLong(cursor.getColumnIndexOrThrow("_id")),
            cursor.getLong(cursor.getColumnIndexOrThrow("session_id")),
            cursor.getLong(cursor.getColumnIndexOrThrow("captured_at_ms")),
            cursor.getString(cursor.getColumnIndexOrThrow("source")),
            cursor.getString(cursor.getColumnIndexOrThrow("vehicle_state")),
            nullableInt(cursor, "speed_kph"),
            nullableInt(cursor, "rpm"),
            nullableInt(cursor, "coolant_c"),
            nullableInt(cursor, "load_pct"),
            nullableInt(cursor, "throttle_pct"),
            nullableDouble(cursor, "voltage"),
            nullableDouble(cursor, "soc"),
            nullableDouble(cursor, "battery_temp"),
            nullableDouble(cursor, "power_kw"),
            nullableInt(cursor, "sample_number"),
            nullableLong(cursor, "session_ms"),
            cursor.getString(cursor.getColumnIndexOrThrow("raw")),
            cursor.getString(cursor.getColumnIndexOrThrow("json")),
        )

    @JvmStatic
    fun readStatusEvent(cursor: Cursor): StatusEventRecord =
        StatusEventRecord(
            cursor.getLong(cursor.getColumnIndexOrThrow("_id")),
            nullableLong(cursor, "session_id"),
            cursor.getLong(cursor.getColumnIndexOrThrow("occurred_at_ms")),
            cursor.getString(cursor.getColumnIndexOrThrow("kind")),
            cursor.getString(cursor.getColumnIndexOrThrow("state")),
            cursor.getString(cursor.getColumnIndexOrThrow("detail")),
            cursor.getInt(cursor.getColumnIndexOrThrow("blocked")) != 0,
            cursor.getString(cursor.getColumnIndexOrThrow("payload")),
        )

    @JvmStatic
    fun readAdapterHistory(cursor: Cursor): AdapterHistoryRecord =
        AdapterHistoryRecord(
            cursor.getString(cursor.getColumnIndexOrThrow("adapter_key")),
            cursor.getString(cursor.getColumnIndexOrThrow("address")),
            cursor.getString(cursor.getColumnIndexOrThrow("name")),
            cursor.getLong(cursor.getColumnIndexOrThrow("first_seen_ms")),
            cursor.getLong(cursor.getColumnIndexOrThrow("last_seen_ms")),
            cursor.getInt(cursor.getColumnIndexOrThrow("connect_count")),
            cursor.getInt(cursor.getColumnIndexOrThrow("scan_count")),
            cursor.getInt(cursor.getColumnIndexOrThrow("demo_count")),
            cursor.getInt(cursor.getColumnIndexOrThrow("sample_count")),
            nullableLong(cursor, "last_session_id"),
            cursor.getString(cursor.getColumnIndexOrThrow("last_mode")),
            cursor.getString(cursor.getColumnIndexOrThrow("last_status")),
            cursor.getString(cursor.getColumnIndexOrThrow("supported_pids")),
            cursor.getString(cursor.getColumnIndexOrThrow("last_event_detail")),
        )

    @JvmStatic
    fun countForMode(
        existing: AdapterHistoryRecord?,
        countedMode: String,
        newMode: String,
    ): Int {
        var current = 0
        if (existing != null) {
            current =
                when (countedMode) {
                    ObdLocalStore.MODE_OBD -> existing.connectCount
                    ObdLocalStore.MODE_SCAN -> existing.scanCount
                    ObdLocalStore.MODE_DEMO -> existing.demoCount
                    else -> 0
                }
        }
        return if (countedMode == newMode) current + 1 else current
    }

    @JvmStatic
    fun adapterKey(
        address: String?,
        mode: String,
    ): String {
        val cleanAddress = clean(address)
        if (cleanAddress.isNotEmpty()) {
            return cleanAddress.uppercase(Locale.US)
        }
        return if (ObdLocalStore.MODE_DEMO == mode) "demo" else "unknown"
    }

    @JvmStatic
    fun cleanMode(mode: String?): String {
        val cleaned = clean(mode).lowercase(Locale.US)
        if (ObdLocalStore.MODE_SCAN == cleaned || ObdLocalStore.MODE_DEMO == cleaned) {
            return cleaned
        }
        return ObdLocalStore.MODE_OBD
    }

    @JvmStatic
    fun cleanStatus(status: String?): String {
        val cleaned = clean(status).lowercase(Locale.US)
        if (ObdLocalStore.STATUS_COMPLETE == cleaned ||
            ObdLocalStore.STATUS_ERROR == cleaned ||
            ObdLocalStore.STATUS_DISCONNECTED == cleaned ||
            ObdLocalStore.STATUS_ACTIVE == cleaned
        ) {
            return cleaned
        }
        return if (cleaned.isEmpty()) ObdLocalStore.STATUS_COMPLETE else cleaned
    }

    @JvmStatic
    fun chooseLatest(
        candidate: String?,
        fallback: String?,
    ): String = if (candidate == null || candidate.trim().isEmpty()) clean(fallback) else candidate.trim()

    @JvmStatic
    fun isUsefulTelemetry(sample: JSONObject?): Boolean {
        if (sample == null || sample.length() == 0) {
            return false
        }
        return sample.has("speedKph") ||
            sample.has("rpm") ||
            sample.has("coolantC") ||
            sample.has("loadPct") ||
            sample.has("throttlePct") ||
            sample.has("packVoltage") ||
            sample.has("packCurrentA") ||
            sample.has("voltage") ||
            sample.has("latitude") ||
            sample.has("longitude") ||
            sample.has("soc") ||
            sample.has("batteryTemp") ||
            sample.has("capacityAh") ||
            sample.has("sohPct") ||
            sample.has("powerKw") ||
            sample.has("clearDtcOk") ||
            sample.has("clearDtcCode")
    }

    @JvmStatic
    fun clean(value: String?): String = value?.trim() ?: ""

    @JvmStatic
    fun boundedLimit(limit: Int): String = maxOf(1, minOf(limit, 500)).toString()

    private const val DEFAULT_SESSION_READ_LIMIT = 500

    private val SESSION_COLUMNS =
        arrayOf(
            "_id",
            "mode",
            "adapter_address",
            "adapter_name",
            "started_at_ms",
            "ended_at_ms",
            "status",
            "supported_pids",
            "sample_count",
            "last_event_at_ms",
        )

    private fun requireKnownTable(table: String): String {
        if (!VoltTrackerDb.KNOWN_TABLES.contains(table)) {
            throw IllegalArgumentException("Unknown SQL table: $table")
        }
        return table
    }

    private fun requireSimpleIdentifier(column: String?): String {
        if (column == null || column.isEmpty() || !Regex("[A-Za-z_][A-Za-z0-9_]*").matches(column)) {
            throw IllegalArgumentException("Unsafe SQL identifier: $column")
        }
        return column
    }

    @JvmStatic
    fun countRows(
        db: SQLiteDatabase,
        table: String,
    ): Long =
        db.rawQuery("SELECT COUNT(*) FROM ${requireKnownTable(table)}", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }

    @JvmStatic
    fun countRowsWhere(
        db: SQLiteDatabase,
        table: String,
        where: String,
        args: Array<String>?,
    ): Long =
        db.rawQuery("SELECT COUNT(*) FROM ${requireKnownTable(table)} WHERE $where", args).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }

    /**
     * Useful-telemetry-row counts for many sessions in ONE grouped query, replacing the per-session
     * COUNT(*) N+1 in the recent-sessions reads. Returns sessionId → count; sessions with zero useful
     * rows are absent (callers default to 0).
     */
    @JvmStatic
    fun usefulSampleCountsBySession(
        db: SQLiteDatabase,
        sessionIds: List<Long>,
    ): Map<Long, Long> {
        if (sessionIds.isEmpty()) {
            return emptyMap()
        }
        val placeholders = sessionIds.joinToString(",") { "?" }
        val args = Array(sessionIds.size) { sessionIds[it].toString() }
        val counts = HashMap<Long, Long>(sessionIds.size)
        db
            .rawQuery(
                "SELECT session_id, COUNT(*) FROM ${VoltTrackerDb.TABLE_TELEMETRY} " +
                    "WHERE session_id IN ($placeholders) AND $USEFUL_TELEMETRY_WHERE GROUP BY session_id",
                args,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    counts[cursor.getLong(0)] = cursor.getLong(1)
                }
            }
        return counts
    }

    @JvmStatic
    fun maxInt(
        db: SQLiteDatabase,
        table: String,
        column: String,
    ): Int {
        val safeTable = requireKnownTable(table)
        val safeColumn = requireSimpleIdentifier(column)
        val where = if (VoltTrackerDb.TABLE_TELEMETRY == safeTable) " WHERE $USEFUL_TELEMETRY_WHERE" else ""
        return db.rawQuery("SELECT MAX($safeColumn) FROM $safeTable$where", null).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getInt(0) else 0
        }
    }

    @JvmStatic
    fun maxIntForSession(
        db: SQLiteDatabase,
        column: String,
        sessionId: Long,
    ): Int = maxIntForSessionBoxed(db, column, sessionId) ?: 0

    @JvmStatic
    fun maxIntForSessionBoxed(
        db: SQLiteDatabase,
        column: String,
        sessionId: Long,
    ): Int? =
        db
            .rawQuery(
                "SELECT MAX(${requireSimpleIdentifier(column)}) FROM ${VoltTrackerDb.TABLE_TELEMETRY}" +
                    " WHERE session_id = ? AND $USEFUL_TELEMETRY_WHERE",
                arrayOf(sessionId.toString()),
            ).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getInt(0) else null
            }

    @JvmStatic
    fun averageSampleIntervalMs(
        db: SQLiteDatabase,
        sessionId: Long,
    ): Long =
        db
            .rawQuery(
                "SELECT MIN(captured_at_ms), MAX(captured_at_ms), COUNT(*) FROM ${VoltTrackerDb.TABLE_TELEMETRY}" +
                    " WHERE session_id = ? AND $USEFUL_TELEMETRY_WHERE",
                arrayOf(sessionId.toString()),
            ).use { cursor ->
                if (!cursor.moveToFirst() || cursor.getLong(2) < 2) {
                    0L
                } else {
                    maxOf(0L, Math.round((cursor.getDouble(1) - cursor.getDouble(0)) / (cursor.getLong(2) - 1)))
                }
            }

    @JvmStatic
    fun averageSampleIntervalMs(db: SQLiteDatabase): Long =
        db
            .rawQuery(
                "SELECT AVG(avg_interval) FROM (" +
                    "SELECT (MAX(captured_at_ms) - MIN(captured_at_ms)) * 1.0" +
                    " / (COUNT(*) - 1) AS avg_interval FROM ${VoltTrackerDb.TABLE_TELEMETRY}" +
                    " WHERE $USEFUL_TELEMETRY_WHERE GROUP BY session_id HAVING COUNT(*) > 1)",
                null,
            ).use { cursor ->
                if (!cursor.moveToFirst() || cursor.isNull(0)) 0L else maxOf(0L, Math.round(cursor.getDouble(0)))
            }

    @JvmStatic
    fun updateSessionLastEvent(
        db: SQLiteDatabase,
        sessionId: Long,
        occurredAtMs: Long,
    ) {
        if (sessionId <= 0) {
            return
        }
        val values = ContentValues()
        values.put("last_event_at_ms", occurredAtMs)
        db.update(VoltTrackerDb.TABLE_SESSIONS, values, "_id = ?", arrayOf(sessionId.toString()))
    }

    @JvmStatic
    fun parseObject(json: String?): JSONObject =
        try {
            if (json == null || json.trim().isEmpty()) JSONObject() else JSONObject(json)
        } catch (ex: JSONException) {
            JSONObject()
        }

    @JvmStatic
    @Throws(JSONException::class)
    fun reverse(source: JSONArray): JSONArray {
        val target = JSONArray()
        for (i in source.length() - 1 downTo 0) {
            target.put(source.get(i))
        }
        return target
    }

    @JvmStatic
    fun getRecentSessions(
        db: SQLiteDatabase,
        limit: Int,
    ): List<ObdSessionRecord> = getSessions(db, boundedLimit(limit))

    @JvmStatic
    fun getAllSessions(db: SQLiteDatabase): List<ObdSessionRecord> =
        getSessions(db, boundedLimit(DEFAULT_SESSION_READ_LIMIT))

    private fun getSessions(
        db: SQLiteDatabase,
        limit: String?,
    ): List<ObdSessionRecord> {
        val records = ArrayList<ObdSessionRecord>()
        db
            .query(
                VoltTrackerDb.TABLE_SESSIONS,
                SESSION_COLUMNS,
                null,
                null,
                null,
                null,
                "started_at_ms DESC",
                limit,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    records.add(readSession(cursor))
                }
            }
        return records
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun sessionToJson(record: ObdSessionRecord): JSONObject {
        val item = JSONObject()
        item.put("id", record.id)
        item.put("mode", record.mode)
        item.put("adapterName", record.adapterName)
        item.put("adapterAddress", record.adapterAddress)
        item.put("startedAtMs", record.startedAtMs)
        item.put("endedAtMs", record.endedAtMs)
        item.put("status", record.status)
        item.put("sampleCount", record.sampleCount)
        item.put("lastEventAtMs", record.lastEventAtMs)
        return item
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun distanceMeters(points: JSONArray): Double {
        var total = 0.0
        var previous: JSONObject? = null
        for (i in 0 until points.length()) {
            val point = points.getJSONObject(i)
            if (!hasFiniteLatLng(point)) {
                previous = point
                continue
            }
            val prior = previous
            if (prior != null && hasFiniteLatLng(prior)) {
                total +=
                    haversineMeters(
                        prior.optDouble("lat"),
                        prior.optDouble("lng"),
                        point.optDouble("lat"),
                        point.optDouble("lng"),
                    )
            }
            previous = point
        }
        return total
    }

    private fun hasFiniteLatLng(point: JSONObject): Boolean {
        val lat = point.optDouble("lat")
        val lng = point.optDouble("lng")
        return !lat.isNaN() && !lng.isNaN()
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun boundsFor(points: JSONArray): JSONObject {
        val payload = JSONObject()
        if (points.length() == 0) {
            return payload
        }
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLng = Double.MAX_VALUE
        var maxLng = -Double.MAX_VALUE
        var sawFinite = false
        for (i in 0 until points.length()) {
            val point = points.getJSONObject(i)
            val lat = point.optDouble("lat")
            val lng = point.optDouble("lng")
            if (lat.isNaN() || lng.isNaN()) {
                continue
            }
            sawFinite = true
            minLat = minOf(minLat, lat)
            maxLat = maxOf(maxLat, lat)
            minLng = minOf(minLng, lng)
            maxLng = maxOf(maxLng, lng)
        }
        if (!sawFinite) {
            return payload
        }
        payload.put("minLat", minLat)
        payload.put("maxLat", maxLat)
        payload.put("minLng", minLng)
        payload.put("maxLng", maxLng)
        return payload
    }

    @JvmStatic
    fun haversineMeters(
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double,
    ): Double {
        val earthMeters = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a =
            Math.sin(dLat / 2.0) * Math.sin(dLat / 2.0) +
                Math.cos(Math.toRadians(lat1)) *
                Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2.0) *
                Math.sin(dLng / 2.0)
        return earthMeters * 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a))
    }
}
