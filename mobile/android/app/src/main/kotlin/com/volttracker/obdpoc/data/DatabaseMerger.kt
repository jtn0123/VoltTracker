package com.volttracker.obdpoc.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.core.database.sqlite.transaction

/** Additively folds the rows of a donor VoltTracker database into a live one. */
object DatabaseMerger {
    class MergeResult internal constructor(
        @JvmField val ok: Boolean,
        @JvmField val error: String?,
        @JvmField val sessionsAdded: Int,
        @JvmField val sessionsSkipped: Int,
        @JvmField val vehiclesAdded: Int,
        @JvmField val vehiclesMerged: Int,
        @JvmField val rowsImported: Long,
    ) {
        fun summary(): String {
            if (!ok) {
                return error ?: "Merge failed."
            }
            val sb = StringBuilder("Merged backup - ")
            if (sessionsAdded > 0) {
                sb.append(sessionsAdded).append(if (sessionsAdded == 1) " new session" else " new sessions")
            } else {
                sb.append("no new sessions")
            }
            if (vehiclesAdded > 0) {
                sb.append(", ").append(vehiclesAdded).append(if (vehiclesAdded == 1) " new vehicle" else " new vehicles")
            }
            if (vehiclesMerged > 0) {
                sb
                    .append(", ")
                    .append(vehiclesMerged)
                    .append(if (vehiclesMerged == 1) " existing vehicle matched" else " existing vehicles matched")
            }
            if (sessionsAdded == 0 && vehiclesAdded == 0 && vehiclesMerged == 0 && rowsImported > 0) {
                sb
                    .append(", ")
                    .append(rowsImported)
                    .append(if (rowsImported == 1L) " other row imported" else " other rows imported")
            }
            if (sessionsSkipped > 0) {
                sb
                    .append(" (")
                    .append(sessionsSkipped)
                    .append(if (sessionsSkipped == 1) " duplicate skipped)" else " duplicates skipped)")
            }
            sb.append('.')
            return sb.toString()
        }

        companion object {
            @JvmStatic
            fun failure(error: String?): MergeResult = MergeResult(false, error, 0, 0, 0, 0, 0L)
        }
    }

    @JvmStatic
    fun merge(
        target: SQLiteDatabase?,
        donor: SQLiteDatabase?,
    ): MergeResult {
        if (target == null || donor == null) {
            return MergeResult.failure("Merge failed - database handle unavailable.")
        }
        val sessionMap = HashMap<Long, Long>()
        val vehicleMap = HashMap<Long, Long>()
        val telemetryMap = HashMap<Long, Long>()
        val batteryMap = HashMap<Long, Long>()
        val vehicleCounts = IntArray(2)
        val sessionCounts = IntArray(2)
        var rows = 0L

        try {
            target.transaction {
                rows += copyVehicles(target, donor, vehicleMap, vehicleCounts)
                rows += copySessions(target, donor, sessionMap, sessionCounts)
                rows += copyChildren(target, donor, VoltTrackerDb.TABLE_TELEMETRY, sessionMap, null, null, telemetryMap)
                rows += copyChildren(target, donor, VoltTrackerDb.TABLE_EVENTS, sessionMap, null, null, null)
                rows += copyChildren(target, donor, VoltTrackerDb.TABLE_PID_OBSERVATIONS, sessionMap, null, null, null)
                rows += copyChildren(target, donor, VoltTrackerDb.TABLE_LOCATION_SAMPLES, sessionMap, null, null, null)
                rows +=
                    copyChildren(
                        target,
                        donor,
                        VoltTrackerDb.TABLE_FIELD_CAPABILITIES,
                        sessionMap,
                        vehicleMap,
                        null,
                        null,
                    )
                rows +=
                    copyChildren(
                        target,
                        donor,
                        VoltTrackerDb.TABLE_TRIP_SEGMENTS,
                        sessionMap,
                        vehicleMap,
                        telemetryMap,
                        null,
                    )
                rows +=
                    copyChildren(
                        target,
                        donor,
                        VoltTrackerDb.TABLE_CHARGE_SESSIONS,
                        sessionMap,
                        vehicleMap,
                        telemetryMap,
                        null,
                    )
                rows +=
                    copyChildren(
                        target,
                        donor,
                        VoltTrackerDb.TABLE_BATTERY_SNAPSHOTS,
                        sessionMap,
                        vehicleMap,
                        null,
                        batteryMap,
                    )
                rows += copyCellSnapshots(target, donor, batteryMap)
                rows += copyChildren(target, donor, VoltTrackerDb.TABLE_EXPORTS, sessionMap, vehicleMap, null, null)
                rows += copyAdapterHistory(target, donor, sessionMap)
                rows += copyDiagnosticCodes(target, donor, sessionMap)
            }
        } catch (ex: RuntimeException) {
            return MergeResult.failure("Merge failed - ${ex.javaClass.simpleName}, no changes were made.")
        }
        return MergeResult(
            true,
            null,
            sessionCounts[0],
            sessionCounts[1],
            vehicleCounts[0],
            vehicleCounts[1],
            rows,
        )
    }

    private fun copyVehicles(
        target: SQLiteDatabase,
        donor: SQLiteDatabase,
        vehicleMap: MutableMap<Long, Long>,
        counts: IntArray,
    ): Long {
        val existingByKey = HashMap<String, Long>()
        target.rawQuery("SELECT _id, vehicle_key FROM ${VoltTrackerDb.TABLE_VEHICLES}", null).use { c ->
            while (c.moveToNext()) {
                existingByKey[c.getString(1)] = c.getLong(0)
            }
        }
        var inserted = 0L
        donor.rawQuery("SELECT * FROM ${VoltTrackerDb.TABLE_VEHICLES}", null).use { c ->
            while (c.moveToNext()) {
                val cv = readRow(c)
                val oldId = cv.getAsLong("_id")
                val key = cv.getAsString("vehicle_key")
                if (oldId == null || key == null) {
                    continue
                }
                val liveId = existingByKey[key]
                if (liveId != null) {
                    vehicleMap[oldId] = liveId
                    counts[1]++
                    continue
                }
                cv.remove("_id")
                val newId = target.insertOrThrow(VoltTrackerDb.TABLE_VEHICLES, null, cv)
                vehicleMap[oldId] = newId
                existingByKey[key] = newId
                counts[0]++
                inserted++
            }
        }
        return inserted
    }

    private fun copySessions(
        target: SQLiteDatabase,
        donor: SQLiteDatabase,
        sessionMap: MutableMap<Long, Long>,
        counts: IntArray,
    ): Long {
        val existingKeys = HashSet<String>()
        target
            .rawQuery(
                "SELECT started_at_ms, mode, adapter_address FROM ${VoltTrackerDb.TABLE_SESSIONS}",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    existingKeys.add(sessionKey(c.getLong(0), c.getString(1), c.getString(2)))
                }
            }
        var inserted = 0L
        donor.rawQuery("SELECT * FROM ${VoltTrackerDb.TABLE_SESSIONS}", null).use { c ->
            while (c.moveToNext()) {
                val cv = readRow(c)
                val oldId = cv.getAsLong("_id")
                val startedAt = cv.getAsLong("started_at_ms")
                if (oldId == null || startedAt == null) {
                    continue
                }
                val key = sessionKey(startedAt, cv.getAsString("mode"), cv.getAsString("adapter_address"))
                if (existingKeys.contains(key)) {
                    counts[1]++
                    continue
                }
                cv.remove("_id")
                val newId = target.insertOrThrow(VoltTrackerDb.TABLE_SESSIONS, null, cv)
                sessionMap[oldId] = newId
                existingKeys.add(key)
                counts[0]++
                inserted++
            }
        }
        return inserted
    }

    private fun copyChildren(
        target: SQLiteDatabase,
        donor: SQLiteDatabase,
        table: String,
        sessionMap: Map<Long, Long>,
        vehicleMap: Map<Long, Long>?,
        telemetryMap: Map<Long, Long>?,
        outIdMap: MutableMap<Long, Long>?,
    ): Long {
        var inserted = 0L
        donor.rawQuery("SELECT * FROM $table", null).use { c ->
            while (c.moveToNext()) {
                val cv = readRow(c)
                val oldId = cv.getAsLong("_id")
                if (cv.containsKey("session_id")) {
                    val donorSession = cv.getAsLong("session_id")
                    if (donorSession != null && !sessionMap.containsKey(donorSession)) {
                        continue
                    }
                }
                cv.remove("_id")
                remap(cv, "session_id", sessionMap)
                if (vehicleMap != null) {
                    remap(cv, "vehicle_id", vehicleMap)
                }
                if (telemetryMap != null) {
                    remap(cv, "start_sample_id", telemetryMap)
                    remap(cv, "end_sample_id", telemetryMap)
                }
                val newId = target.insertOrThrow(table, null, cv)
                if (outIdMap != null && oldId != null) {
                    outIdMap[oldId] = newId
                }
                inserted++
            }
        }
        return inserted
    }

    private fun copyCellSnapshots(
        target: SQLiteDatabase,
        donor: SQLiteDatabase,
        batteryMap: Map<Long, Long>,
    ): Long {
        var inserted = 0L
        donor.rawQuery("SELECT * FROM ${VoltTrackerDb.TABLE_CELL_SNAPSHOTS}", null).use { c ->
            while (c.moveToNext()) {
                val cv = readRow(c)
                val parent = cv.getAsLong("battery_snapshot_id")
                if (parent == null || !batteryMap.containsKey(parent)) {
                    continue
                }
                cv.remove("_id")
                cv.put("battery_snapshot_id", batteryMap[parent])
                target.insertOrThrow(VoltTrackerDb.TABLE_CELL_SNAPSHOTS, null, cv)
                inserted++
            }
        }
        return inserted
    }

    private fun copyAdapterHistory(
        target: SQLiteDatabase,
        donor: SQLiteDatabase,
        sessionMap: Map<Long, Long>,
    ): Long {
        var touched = 0L
        donor.rawQuery("SELECT * FROM ${VoltTrackerDb.TABLE_ADAPTER_HISTORY}", null).use { c ->
            while (c.moveToNext()) {
                val cv = readRow(c)
                val key = cv.getAsString("adapter_key") ?: continue
                val canCopyLastSession = canCopyMappedReference(cv, "last_session_id", sessionMap)
                remap(cv, "last_session_id", sessionMap)
                val existing = queryOne(target, VoltTrackerDb.TABLE_ADAPTER_HISTORY, "adapter_key = ?", arrayOf(key))
                if (existing == null) {
                    target.insertOrThrow(VoltTrackerDb.TABLE_ADAPTER_HISTORY, null, cv)
                } else {
                    val merged = ContentValues()
                    sumLong(merged, "connect_count", existing, cv)
                    sumLong(merged, "scan_count", existing, cv)
                    sumLong(merged, "demo_count", existing, cv)
                    sumLong(merged, "sample_count", existing, cv)
                    merged.put("first_seen_ms", minLong(existing.getAsLong("first_seen_ms"), cv.getAsLong("first_seen_ms")))
                    val liveLast = orZero(existing.getAsLong("last_seen_ms"))
                    val donorLast = orZero(cv.getAsLong("last_seen_ms"))
                    merged.put("last_seen_ms", maxOf(liveLast, donorLast))
                    if (donorLast >= liveLast) {
                        copyIfPresent(merged, cv, "address")
                        copyIfPresent(merged, cv, "name")
                        if (canCopyLastSession) {
                            copyIfPresent(merged, cv, "last_session_id")
                        }
                        copyIfPresent(merged, cv, "last_mode")
                        copyIfPresent(merged, cv, "last_status")
                        copyIfPresent(merged, cv, "supported_pids")
                        copyIfPresent(merged, cv, "last_event_detail")
                    }
                    target.update(VoltTrackerDb.TABLE_ADAPTER_HISTORY, merged, "adapter_key = ?", arrayOf(key))
                }
                touched++
            }
        }
        return touched
    }

    private fun copyDiagnosticCodes(
        target: SQLiteDatabase,
        donor: SQLiteDatabase,
        sessionMap: Map<Long, Long>,
    ): Long {
        var touched = 0L
        donor.rawQuery("SELECT * FROM ${VoltTrackerDb.TABLE_DIAGNOSTIC_CODES}", null).use { c ->
            while (c.moveToNext()) {
                val cv = readRow(c)
                val moduleKey = cv.getAsString("module_key")
                val dtc = cv.getAsString("dtc")
                val status = cv.getAsString("status")
                if (moduleKey == null || dtc == null || status == null) {
                    continue
                }
                cv.remove("_id")
                val canCopyLastSession = canCopyMappedReference(cv, "last_session_id", sessionMap)
                remap(cv, "last_session_id", sessionMap)
                val where = "module_key = ? AND dtc = ? AND status = ?"
                val args = arrayOf(moduleKey, dtc, status)
                val existing = queryOne(target, VoltTrackerDb.TABLE_DIAGNOSTIC_CODES, where, args)
                if (existing == null) {
                    target.insertOrThrow(VoltTrackerDb.TABLE_DIAGNOSTIC_CODES, null, cv)
                } else {
                    val merged = ContentValues()
                    sumLong(merged, "seen_count", existing, cv)
                    merged.put("first_seen_ms", minLong(existing.getAsLong("first_seen_ms"), cv.getAsLong("first_seen_ms")))
                    val liveLast = orZero(existing.getAsLong("last_seen_ms"))
                    val donorLast = orZero(cv.getAsLong("last_seen_ms"))
                    merged.put("last_seen_ms", maxOf(liveLast, donorLast))
                    if (donorLast >= liveLast) {
                        copyIfPresent(merged, cv, "status_label")
                        copyIfPresent(merged, cv, "module_name")
                        copyIfPresent(merged, cv, "header")
                        if (canCopyLastSession) {
                            copyIfPresent(merged, cv, "last_session_id")
                        }
                        copyIfPresent(merged, cv, "raw_response")
                        copyIfPresent(merged, cv, "json")
                    }
                    target.update(VoltTrackerDb.TABLE_DIAGNOSTIC_CODES, merged, where, args)
                }
                touched++
            }
        }
        return touched
    }

    private fun readRow(c: Cursor): ContentValues {
        val cv = ContentValues()
        for (i in 0 until c.columnCount) {
            val name = c.getColumnName(i)
            when (c.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> cv.putNull(name)
                Cursor.FIELD_TYPE_INTEGER -> cv.put(name, c.getLong(i))
                Cursor.FIELD_TYPE_FLOAT -> cv.put(name, c.getDouble(i))
                Cursor.FIELD_TYPE_BLOB -> cv.put(name, c.getBlob(i))
                else -> cv.put(name, c.getString(i))
            }
        }
        return cv
    }

    private fun remap(
        cv: ContentValues,
        column: String,
        map: Map<Long, Long>,
    ) {
        if (!cv.containsKey(column)) {
            return
        }
        val old = cv.getAsLong(column) ?: return
        val mapped = map[old]
        if (mapped == null) {
            cv.putNull(column)
        } else {
            cv.put(column, mapped)
        }
    }

    private fun canCopyMappedReference(
        cv: ContentValues,
        column: String,
        map: Map<Long, Long>,
    ): Boolean {
        if (!cv.containsKey(column)) {
            return false
        }
        val old = cv.getAsLong(column)
        return old == null || map.containsKey(old)
    }

    private fun sessionKey(
        startedAtMs: Long,
        mode: String?,
        adapterAddress: String?,
    ): String = "$startedAtMs|${normalizeKeyPart(mode)}|${normalizeKeyPart(adapterAddress)}"

    private fun normalizeKeyPart(value: String?): String = value ?: ""

    private fun queryOne(
        db: SQLiteDatabase,
        table: String,
        where: String,
        args: Array<String>,
    ): ContentValues? =
        db.query(table, null, where, args, null, null, null, "1").use { c ->
            if (c.moveToFirst()) readRow(c) else null
        }

    private fun sumLong(
        out: ContentValues,
        column: String,
        a: ContentValues,
        b: ContentValues,
    ) {
        out.put(column, orZero(a.getAsLong(column)) + orZero(b.getAsLong(column)))
    }

    private fun copyIfPresent(
        out: ContentValues,
        src: ContentValues,
        column: String,
    ) {
        if (!src.containsKey(column)) {
            return
        }
        when (val value = src[column]) {
            null -> out.putNull(column)
            is Long -> out.put(column, value)
            is Int -> out.put(column, value)
            is Double -> out.put(column, value)
            is ByteArray -> out.put(column, value)
            else -> out.put(column, value.toString())
        }
    }

    private fun minLong(
        a: Long?,
        b: Long?,
    ): Long {
        if (a == null) {
            return orZero(b)
        }
        if (b == null) {
            return a
        }
        return minOf(a, b)
    }

    private fun orZero(value: Long?): Long = value ?: 0L
}
