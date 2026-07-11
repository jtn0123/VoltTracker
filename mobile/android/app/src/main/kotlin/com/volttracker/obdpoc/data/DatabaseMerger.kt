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
                sb
                    .append(", ")
                    .append(vehiclesAdded)
                    .append(if (vehiclesAdded == 1) " new vehicle" else " new vehicles")
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
                    .append(if (sessionsSkipped == 1) " duplicate session matched)" else " duplicate sessions matched)")
            }
            sb.append('.')
            return sb.toString()
        }

        companion object {
            @JvmStatic
            fun failure(error: String?): MergeResult = MergeResult(false, error, 0, 0, 0, 0, 0L)
        }
    }

    fun interface ProgressListener {
        fun onProgress(
            phase: String,
            rowsDone: Long,
            rowsTotal: Long,
        )
    }

    @JvmStatic
    fun merge(
        target: SQLiteDatabase?,
        donor: SQLiteDatabase?,
        progressListener: ProgressListener? = null,
    ): MergeResult {
        if (target == null || donor == null) {
            return MergeResult.failure("Merge failed - database handle unavailable.")
        }
        val progress = MergeProgress(progressListener, countDonorRows(donor))
        val sessionMap = HashMap<Long, Long>()
        val vehicleMap = HashMap<Long, Long>()
        val telemetryMap = HashMap<Long, Long>()
        val batteryMap = HashMap<Long, Long>()
        val vehicleCounts = IntArray(2)
        val sessionCounts = IntArray(2)
        var rows = 0L

        try {
            progress.publish("Preparing merge")
            target.transaction {
                rows += copyVehicles(target, donor, vehicleMap, vehicleCounts, progress)
                rows += copySessions(target, donor, sessionMap, sessionCounts, progress)
                rows +=
                    copyChildren(
                        target,
                        donor,
                        VoltTrackerDb.TABLE_TELEMETRY,
                        sessionMap,
                        null,
                        null,
                        telemetryMap,
                        progress,
                    )
                rows += copyChildren(target, donor, VoltTrackerDb.TABLE_EVENTS, sessionMap, null, null, null, progress)
                rows +=
                    copyChildren(
                        target,
                        donor,
                        VoltTrackerDb.TABLE_PID_OBSERVATIONS,
                        sessionMap,
                        null,
                        null,
                        null,
                        progress,
                    )
                rows +=
                    copyChildren(
                        target,
                        donor,
                        VoltTrackerDb.TABLE_LOCATION_SAMPLES,
                        sessionMap,
                        null,
                        null,
                        null,
                        progress,
                    )
                rows +=
                    copyChildren(
                        target,
                        donor,
                        VoltTrackerDb.TABLE_FIELD_CAPABILITIES,
                        sessionMap,
                        vehicleMap,
                        null,
                        null,
                        progress,
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
                        progress,
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
                        progress,
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
                        progress,
                    )
                rows += copyCellSnapshots(target, donor, batteryMap, progress)
                rows +=
                    copyChildren(
                        target,
                        donor,
                        VoltTrackerDb.TABLE_EXPORTS,
                        sessionMap,
                        vehicleMap,
                        null,
                        null,
                        progress,
                    )
                rows += copyAdapterHistory(target, donor, sessionMap, progress)
                rows += copyDiagnosticCodes(target, donor, sessionMap, progress)
                rows += copyMaintenanceLog(target, donor, progress)
                progress.publish("Refreshing trip projections")
                invalidateTripProjectionCaches(target, sessionMap.values)
            }
        } catch (ex: RuntimeException) {
            return MergeResult.failure("Merge failed - ${ex.javaClass.simpleName}, no changes were made.")
        }
        progress.finish("Merge complete")
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

    /**
     * Mutable view of a live vehicle row during the merge: alias-set unions and first/last-seen
     * widening from matched donor rows accumulate here before being written back.
     */
    private class LiveVehicle(
        val id: Long,
        val key: String,
        val aliases: MutableSet<String>,
        var firstSeenMs: Long?,
        var lastSeenMs: Long?,
    )

    /**
     * ADR 0009 (B8): vehicle keys are HMACs under a per-install secret, so the same physical car
     * can arrive keyed differently than the live row. Donor and live rows are therefore matched on
     * the intersection of their `{vehicle_key} ∪ vehicle_key_aliases` sets — the aliases having
     * been recorded while the VIN was actually in hand (ObdStoreVehicles). Rows without aliases
     * (pre-v16 backups, no-VIN rows) degrade to the historical strict `vehicle_key` match.
     */
    private fun copyVehicles(
        target: SQLiteDatabase,
        donor: SQLiteDatabase,
        vehicleMap: MutableMap<Long, Long>,
        counts: IntArray,
        progress: MergeProgress,
    ): Long {
        val liveById = HashMap<Long, LiveVehicle>()
        val idByKey = HashMap<String, Long>()
        target
            .rawQuery(
                "SELECT _id, vehicle_key, ${VehicleKeyAliases.COLUMN}, first_seen_ms, last_seen_ms" +
                    " FROM ${VoltTrackerDb.TABLE_VEHICLES}",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    val live =
                        LiveVehicle(
                            c.getLong(0),
                            c.getString(1),
                            VehicleKeyAliases.parse(c.getString(2)).toMutableSet(),
                            if (c.isNull(3)) null else c.getLong(3),
                            if (c.isNull(4)) null else c.getLong(4),
                        )
                    liveById[live.id] = live
                    registerVehicleKeys(idByKey, live)
                }
            }
        var inserted = 0L
        donorRows(donor, VoltTrackerDb.TABLE_VEHICLES).use { c ->
            while (c.moveToNext()) {
                progress.step("Merging vehicles")
                val cv = readRow(c)
                val oldId = cv.getAsLong("_id")
                val key = cv.getAsString("vehicle_key")
                if (oldId == null || key == null) {
                    continue
                }
                val donorKeys = VehicleKeyAliases.parse(cv.getAsString(VehicleKeyAliases.COLUMN)) + key
                val liveId = donorKeys.firstNotNullOfOrNull { idByKey[it] }
                if (liveId != null) {
                    vehicleMap[oldId] = liveId
                    counts[1]++
                    foldDonorIdentity(target, liveById.getValue(liveId), cv, donorKeys, idByKey)
                    continue
                }
                cv.remove("_id")
                val newId = target.insertOrThrow(VoltTrackerDb.TABLE_VEHICLES, null, cv)
                vehicleMap[oldId] = newId
                val live =
                    LiveVehicle(
                        newId,
                        key,
                        (donorKeys - key).toMutableSet(),
                        cv.getAsLong("first_seen_ms"),
                        cv.getAsLong("last_seen_ms"),
                    )
                liveById[newId] = live
                registerVehicleKeys(idByKey, live)
                counts[0]++
                inserted++
            }
        }
        return inserted
    }

    private fun registerVehicleKeys(
        idByKey: MutableMap<String, Long>,
        live: LiveVehicle,
    ) {
        // First registration wins: an unlikely alias collision between two live rows keeps
        // matching deterministic instead of flip-flopping between them.
        // Map.putIfAbsent needs API 24 (minSdk is 23), so spell out the same first-wins check.
        if (live.key !in idByKey) idByKey[live.key] = live.id
        for (alias in live.aliases) {
            if (alias !in idByKey) idByKey[alias] = live.id
        }
    }

    /**
     * A donor row matched a live vehicle: union the identity lineage (the donor's key + aliases
     * become aliases of the live row, so future merges keep matching) and widen the seen window.
     * The live row's own key and descriptive fields stay authoritative.
     */
    private fun foldDonorIdentity(
        target: SQLiteDatabase,
        live: LiveVehicle,
        donorCv: ContentValues,
        donorKeys: Set<String>,
        idByKey: MutableMap<String, Long>,
    ) {
        var changed = false
        for (donorKey in donorKeys) {
            if (donorKey != live.key && live.aliases.add(donorKey)) {
                if (donorKey !in idByKey) idByKey[donorKey] = live.id
                changed = true
            }
        }
        val donorFirst = donorCv.getAsLong("first_seen_ms")
        val liveFirst = live.firstSeenMs
        if (donorFirst != null && (liveFirst == null || donorFirst < liveFirst)) {
            live.firstSeenMs = donorFirst
            changed = true
        }
        val donorLast = donorCv.getAsLong("last_seen_ms")
        val liveLast = live.lastSeenMs
        if (donorLast != null && (liveLast == null || donorLast > liveLast)) {
            live.lastSeenMs = donorLast
            changed = true
        }
        if (!changed) {
            return
        }
        val update = ContentValues()
        update.put(VehicleKeyAliases.COLUMN, VehicleKeyAliases.serialize(live.aliases))
        live.firstSeenMs?.let { update.put("first_seen_ms", it) }
        live.lastSeenMs?.let { update.put("last_seen_ms", it) }
        target.update(VoltTrackerDb.TABLE_VEHICLES, update, "_id = ?", arrayOf(live.id.toString()))
    }

    private fun copySessions(
        target: SQLiteDatabase,
        donor: SQLiteDatabase,
        sessionMap: MutableMap<Long, Long>,
        counts: IntArray,
        progress: MergeProgress,
    ): Long {
        val existingByKey = HashMap<String, Long>()
        target
            .rawQuery(
                "SELECT _id, started_at_ms, mode, adapter_address FROM ${VoltTrackerDb.TABLE_SESSIONS}",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    existingByKey[sessionKey(c.getLong(1), c.getString(2), c.getString(3))] = c.getLong(0)
                }
            }
        var inserted = 0L
        donorRows(donor, VoltTrackerDb.TABLE_SESSIONS).use { c ->
            while (c.moveToNext()) {
                progress.step("Merging sessions")
                val cv = readRow(c)
                val oldId = cv.getAsLong("_id")
                val startedAt = cv.getAsLong("started_at_ms")
                if (oldId == null || startedAt == null) {
                    continue
                }
                val key = sessionKey(startedAt, cv.getAsString("mode"), cv.getAsString("adapter_address"))
                val existingId = existingByKey[key]
                if (existingId != null) {
                    sessionMap[oldId] = existingId
                    counts[1]++
                    continue
                }
                cv.remove("_id")
                val newId = target.insertOrThrow(VoltTrackerDb.TABLE_SESSIONS, null, cv)
                sessionMap[oldId] = newId
                existingByKey[key] = newId
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
        progress: MergeProgress,
    ): Long {
        var inserted = 0L
        donorRows(donor, table).use { c ->
            while (c.moveToNext()) {
                progress.step("Merging ${tableLabel(table)}")
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
                val existingId = matchingExistingRowId(target, table, cv)
                if (existingId != null) {
                    if (outIdMap != null && oldId != null) {
                        outIdMap[oldId] = existingId
                    }
                    continue
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
        progress: MergeProgress,
    ): Long {
        var inserted = 0L
        donorRows(donor, VoltTrackerDb.TABLE_CELL_SNAPSHOTS).use { c ->
            while (c.moveToNext()) {
                progress.step("Merging cell snapshots")
                val cv = readRow(c)
                val parent = cv.getAsLong("battery_snapshot_id")
                if (parent == null || !batteryMap.containsKey(parent)) {
                    continue
                }
                cv.remove("_id")
                cv.put("battery_snapshot_id", batteryMap[parent])
                if (matchingExistingRowId(target, VoltTrackerDb.TABLE_CELL_SNAPSHOTS, cv) != null) {
                    continue
                }
                target.insertOrThrow(VoltTrackerDb.TABLE_CELL_SNAPSHOTS, null, cv)
                inserted++
            }
        }
        return inserted
    }

    private fun invalidateTripProjectionCaches(
        db: SQLiteDatabase,
        sessionIds: Collection<Long>,
    ) {
        val unique = LinkedHashSet<Long>()
        for (sessionId in sessionIds) {
            if (sessionId > 0L) {
                unique.add(sessionId)
            }
        }
        for (sessionId in unique) {
            val args = arrayOf(sessionId.toString())
            db.delete(VoltTrackerDb.TABLE_SESSION_TRIP_ROLLUPS, "session_id = ?", args)
            db.delete(VoltTrackerDb.TABLE_TRIP_LIST_CACHE, "session_id = ?", args)
            // G2: imported telemetry can change a session's inferred-charge scan, so drop its
            // cached charge rollup; it recomputes on the next storage-summary read.
            db.delete(VoltTrackerDb.TABLE_CHARGE_SESSION_ROLLUPS, "session_id = ?", args)
        }
    }

    private fun copyAdapterHistory(
        target: SQLiteDatabase,
        donor: SQLiteDatabase,
        sessionMap: Map<Long, Long>,
        progress: MergeProgress,
    ): Long {
        var touched = 0L
        donorRows(donor, VoltTrackerDb.TABLE_ADAPTER_HISTORY).use { c ->
            while (c.moveToNext()) {
                progress.step("Merging adapter history")
                val cv = readRow(c)
                val key = cv.getAsString("adapter_key") ?: continue
                val canCopyLastSession = canCopyMappedReference(cv, "last_session_id", sessionMap)
                remap(cv, "last_session_id", sessionMap)
                val existing =
                    queryOne(
                        target,
                        VoltTrackerDb.TABLE_ADAPTER_HISTORY,
                        "adapter_key = ?",
                        arrayOf(key),
                        ADAPTER_HISTORY_COLUMNS,
                    )
                if (existing == null) {
                    target.insertOrThrow(VoltTrackerDb.TABLE_ADAPTER_HISTORY, null, cv)
                    touched++
                } else {
                    val merged = ContentValues()
                    val donorContained = donorIntervalContained(existing, cv)
                    mergeCounter(merged, "connect_count", existing, cv, donorContained)
                    mergeCounter(merged, "scan_count", existing, cv, donorContained)
                    mergeCounter(merged, "demo_count", existing, cv, donorContained)
                    mergeCounter(merged, "sample_count", existing, cv, donorContained)
                    merged.put(
                        "first_seen_ms",
                        minLong(existing.getAsLong("first_seen_ms"), cv.getAsLong("first_seen_ms")),
                    )
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
            }
        }
        return touched
    }

    private fun copyDiagnosticCodes(
        target: SQLiteDatabase,
        donor: SQLiteDatabase,
        sessionMap: Map<Long, Long>,
        progress: MergeProgress,
    ): Long {
        var touched = 0L
        donorRows(donor, VoltTrackerDb.TABLE_DIAGNOSTIC_CODES).use { c ->
            while (c.moveToNext()) {
                progress.step("Merging diagnostic codes")
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
                val existing =
                    queryOne(
                        target,
                        VoltTrackerDb.TABLE_DIAGNOSTIC_CODES,
                        where,
                        args,
                        DIAGNOSTIC_MERGE_COLUMNS,
                    )
                if (existing == null) {
                    target.insertOrThrow(VoltTrackerDb.TABLE_DIAGNOSTIC_CODES, null, cv)
                    touched++
                } else {
                    val merged = ContentValues()
                    mergeCounter(merged, "seen_count", existing, cv, donorIntervalContained(existing, cv))
                    merged.put(
                        "first_seen_ms",
                        minLong(existing.getAsLong("first_seen_ms"), cv.getAsLong("first_seen_ms")),
                    )
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
            }
        }
        return touched
    }

    /**
     * Copies the donor's user maintenance log (M5). The table has no foreign keys (entries are
     * independent of any session/vehicle), so rows copy verbatim with no key remapping. Dedupes on
     * (created_at_ms, type, note, odometer_km) so re-merging the same backup never double-inserts an
     * entry the user only logged once.
     */
    private fun copyMaintenanceLog(
        target: SQLiteDatabase,
        donor: SQLiteDatabase,
        progress: MergeProgress,
    ): Long {
        // Pre-v12 backups predate the maintenance log; querying the missing donor table would throw
        // and abort the whole merge, so skip the copy when the donor lacks the table entirely.
        if (!tableExists(donor, VoltTrackerDb.TABLE_MAINTENANCE_LOG)) {
            return 0L
        }
        var touched = 0L
        donorRows(donor, VoltTrackerDb.TABLE_MAINTENANCE_LOG).use { c ->
            while (c.moveToNext()) {
                progress.step("Merging maintenance log")
                val cv = readRow(c)
                cv.remove("_id")
                if (matchingExistingRowId(target, VoltTrackerDb.TABLE_MAINTENANCE_LOG, cv) == null) {
                    target.insertOrThrow(VoltTrackerDb.TABLE_MAINTENANCE_LOG, null, cv)
                    touched++
                }
            }
        }
        return touched
    }

    private class MergeProgress(
        private val listener: ProgressListener?,
        private val rowsTotal: Long,
    ) {
        private var rowsDone = 0L

        fun publish(phase: String) {
            listener?.onProgress(phase, rowsDone.coerceAtMost(rowsTotal), rowsTotal)
        }

        fun step(phase: String) {
            if (rowsTotal > 0L) {
                rowsDone = (rowsDone + 1L).coerceAtMost(rowsTotal)
            }
            publish(phase)
        }

        fun finish(phase: String) {
            if (rowsTotal > 0L) {
                rowsDone = rowsTotal
            }
            publish(phase)
        }
    }

    private fun countDonorRows(donor: SQLiteDatabase): Long {
        var total = 0L
        for (table in MERGE_PROGRESS_TABLES) {
            total += countRows(donor, table)
        }
        return total
    }

    private fun countRows(
        db: SQLiteDatabase,
        table: String,
    ): Long =
        try {
            db.rawQuery("SELECT COUNT(*) FROM $table", null).use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            }
        } catch (ex: RuntimeException) {
            0L
        }

    private fun tableExists(
        db: SQLiteDatabase,
        table: String,
    ): Boolean =
        db
            .rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
                arrayOf(table),
            ).use { it.moveToFirst() }

    private fun tableLabel(table: String): String =
        when (table) {
            VoltTrackerDb.TABLE_TELEMETRY -> "telemetry"
            VoltTrackerDb.TABLE_EVENTS -> "status events"
            VoltTrackerDb.TABLE_PID_OBSERVATIONS -> "PID observations"
            VoltTrackerDb.TABLE_LOCATION_SAMPLES -> "map samples"
            VoltTrackerDb.TABLE_FIELD_CAPABILITIES -> "field capabilities"
            VoltTrackerDb.TABLE_TRIP_SEGMENTS -> "trip segments"
            VoltTrackerDb.TABLE_CHARGE_SESSIONS -> "charge sessions"
            VoltTrackerDb.TABLE_BATTERY_SNAPSHOTS -> "battery snapshots"
            VoltTrackerDb.TABLE_CELL_SNAPSHOTS -> "cell snapshots"
            VoltTrackerDb.TABLE_EXPORTS -> "exports"
            else -> table
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

    private fun donorRows(
        db: SQLiteDatabase,
        table: String,
    ): Cursor = db.query(table, availableColumns(db, table, donorColumnsFor(table)), null, null, null, null, null)

    private fun availableColumns(
        db: SQLiteDatabase,
        table: String,
        desired: Array<String>,
    ): Array<String> {
        val existing = HashSet<String>()
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            while (c.moveToNext()) {
                existing.add(c.getString(1))
            }
        }
        return desired.filter { existing.contains(it) }.toTypedArray()
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

    private fun matchingExistingRowId(
        db: SQLiteDatabase,
        table: String,
        cv: ContentValues,
    ): Long? {
        val columns = dedupeColumnsFor(table)
        if (columns.isEmpty()) {
            return null
        }
        val where = StringBuilder()
        val args = ArrayList<String>()
        for (column in columns) {
            if (!cv.containsKey(column)) {
                return null
            }
            if (where.isNotEmpty()) {
                where.append(" AND ")
            }
            val value = cv[column]
            if (value == null) {
                where.append(column).append(" IS NULL")
            } else {
                where.append(column).append(" = ?")
                args.add(value.toString())
            }
        }
        db.query(table, arrayOf("_id"), where.toString(), args.toTypedArray(), null, null, null, "1").use { c ->
            return if (c.moveToFirst()) c.getLong(0) else null
        }
    }

    private fun dedupeColumnsFor(table: String): Array<String> =
        when (table) {
            VoltTrackerDb.TABLE_TELEMETRY -> arrayOf("session_id", "captured_at_ms", "json")
            VoltTrackerDb.TABLE_EVENTS -> arrayOf("session_id", "occurred_at_ms", "kind", "payload")
            VoltTrackerDb.TABLE_PID_OBSERVATIONS ->
                arrayOf(
                    "session_id",
                    "observed_at_ms",
                    "command",
                    "header",
                    "pid",
                    "json",
                )
            VoltTrackerDb.TABLE_LOCATION_SAMPLES -> arrayOf("session_id", "captured_at_ms", "latitude", "longitude")
            VoltTrackerDb.TABLE_FIELD_CAPABILITIES ->
                arrayOf(
                    "vehicle_id",
                    "adapter_key",
                    "protocol",
                    "header",
                    "command",
                    "pid",
                )
            VoltTrackerDb.TABLE_TRIP_SEGMENTS -> arrayOf("session_id", "started_at_ms", "ended_at_ms", "classification")
            VoltTrackerDb.TABLE_CHARGE_SESSIONS -> arrayOf("session_id", "started_at_ms", "ended_at_ms", "charger_type")
            VoltTrackerDb.TABLE_BATTERY_SNAPSHOTS -> arrayOf("session_id", "captured_at_ms", "json")
            VoltTrackerDb.TABLE_CELL_SNAPSHOTS -> arrayOf("battery_snapshot_id", "cell_index")
            VoltTrackerDb.TABLE_EXPORTS -> arrayOf("session_id", "created_at_ms", "export_type", "file_name")
            VoltTrackerDb.TABLE_MAINTENANCE_LOG -> arrayOf("created_at_ms", "type", "note", "odometer_km")
            else -> emptyArray()
        }

    private fun queryOne(
        db: SQLiteDatabase,
        table: String,
        where: String,
        args: Array<String>,
        columns: Array<String> = donorColumnsFor(table),
    ): ContentValues? =
        db.query(table, columns, where, args, null, null, null, "1").use { c ->
            if (c.moveToFirst()) readRow(c) else null
        }

    /**
     * Idempotency heuristic for aggregated counter rows (adapter history, diagnostic codes), which
     * have no per-event row identity to dedupe on: when the donor row's
     * `[first_seen_ms, last_seen_ms]` interval is fully contained within the existing row's
     * interval, the donor adds no new time span — it is almost certainly a re-import of a backup
     * that was already merged, so summing would double the counters on every re-import. A donor
     * that extends the interval on either side genuinely carries new activity and keeps summing.
     */
    private fun donorIntervalContained(
        existing: ContentValues,
        donor: ContentValues,
    ): Boolean {
        val donorFirst = orZero(donor.getAsLong("first_seen_ms"))
        val donorLast = orZero(donor.getAsLong("last_seen_ms"))
        val liveFirst = orZero(existing.getAsLong("first_seen_ms"))
        val liveLast = orZero(existing.getAsLong("last_seen_ms"))
        return donorFirst >= liveFirst && donorLast <= liveLast
    }

    /**
     * Sums the counter, except when [donorContained] says the donor is a re-import of an
     * already-merged span — then the larger of the two values is kept so re-importing the same
     * backup leaves counters unchanged. See [donorIntervalContained].
     */
    private fun mergeCounter(
        out: ContentValues,
        column: String,
        existing: ContentValues,
        donor: ContentValues,
        donorContained: Boolean,
    ) {
        val live = orZero(existing.getAsLong(column))
        val donated = orZero(donor.getAsLong(column))
        out.put(column, if (donorContained) maxOf(live, donated) else live + donated)
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

    // Internal (not private) so DatabaseMergerColumnsTest can assert these lists stay in sync
    // with the live schema — a migration that adds a column without updating them would
    // otherwise silently drop that column's data during merges.
    internal fun donorColumnsFor(table: String): Array<String> =
        when (table) {
            VoltTrackerDb.TABLE_VEHICLES -> VEHICLE_COLUMNS
            VoltTrackerDb.TABLE_SESSIONS -> SESSION_COLUMNS
            VoltTrackerDb.TABLE_TELEMETRY -> TELEMETRY_COLUMNS
            VoltTrackerDb.TABLE_EVENTS -> EVENT_COLUMNS
            VoltTrackerDb.TABLE_PID_OBSERVATIONS -> PID_OBSERVATION_COLUMNS
            VoltTrackerDb.TABLE_LOCATION_SAMPLES -> LOCATION_SAMPLE_COLUMNS
            VoltTrackerDb.TABLE_FIELD_CAPABILITIES -> FIELD_CAPABILITY_COLUMNS
            VoltTrackerDb.TABLE_TRIP_SEGMENTS -> TRIP_SEGMENT_COLUMNS
            VoltTrackerDb.TABLE_CHARGE_SESSIONS -> CHARGE_SESSION_COLUMNS
            VoltTrackerDb.TABLE_BATTERY_SNAPSHOTS -> BATTERY_SNAPSHOT_COLUMNS
            VoltTrackerDb.TABLE_CELL_SNAPSHOTS -> CELL_SNAPSHOT_COLUMNS
            VoltTrackerDb.TABLE_EXPORTS -> EXPORT_COLUMNS
            VoltTrackerDb.TABLE_ADAPTER_HISTORY -> ADAPTER_HISTORY_COLUMNS
            VoltTrackerDb.TABLE_DIAGNOSTIC_CODES -> DIAGNOSTIC_CODE_COLUMNS
            VoltTrackerDb.TABLE_MAINTENANCE_LOG -> MAINTENANCE_LOG_COLUMNS
            else -> throw IllegalArgumentException("Unknown merge table: $table")
        }

    private val VEHICLE_COLUMNS =
        arrayOf(
            "_id",
            "vehicle_key",
            "display_name",
            "make",
            "model",
            "model_year",
            "vin_redacted",
            "vin_hash",
            "vin_source",
            "first_seen_ms",
            "last_seen_ms",
            "created_at_ms",
            "updated_at_ms",
            "metadata_json",
            "vehicle_key_aliases",
        )
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
            "created_at_ms",
        )
    private val TELEMETRY_COLUMNS =
        arrayOf(
            "_id",
            "session_id",
            "captured_at_ms",
            "source",
            "vehicle_state",
            "speed_kph",
            "rpm",
            "coolant_c",
            "load_pct",
            "throttle_pct",
            "voltage",
            "soc",
            "battery_temp",
            "power_kw",
            "pack_voltage",
            "pack_current_a",
            "latitude",
            "longitude",
            "accuracy_m",
            "gps_speed_mps",
            "bearing_deg",
            "location_age_ms",
            "sample_number",
            "session_ms",
            "charge_transition_hint",
            "app_foreground",
            "raw",
            "json",
        )
    private val EVENT_COLUMNS =
        arrayOf("_id", "session_id", "occurred_at_ms", "kind", "state", "detail", "blocked", "payload")
    private val PID_OBSERVATION_COLUMNS =
        arrayOf(
            "_id",
            "session_id",
            "observed_at_ms",
            "command",
            "header",
            "pid",
            "name",
            "value_text",
            "value_numeric",
            "unit",
            "raw_request",
            "raw_response",
            "json",
        )
    private val LOCATION_SAMPLE_COLUMNS =
        arrayOf(
            "_id",
            "session_id",
            "captured_at_ms",
            "provider",
            "latitude",
            "longitude",
            "accuracy_m",
            "altitude_m",
            "speed_mps",
            "bearing_deg",
            "location_age_ms",
            "elapsed_realtime_nanos",
            "json",
        )
    private val FIELD_CAPABILITY_COLUMNS =
        arrayOf(
            "_id",
            "vehicle_id",
            "adapter_key",
            "protocol",
            "header",
            "command",
            "pid",
            "name",
            "unit",
            "supported",
            "response_count",
            "first_seen_ms",
            "last_seen_ms",
            "sample_json",
        )
    private val TRIP_SEGMENT_COLUMNS =
        arrayOf(
            "_id",
            "session_id",
            "vehicle_id",
            "started_at_ms",
            "ended_at_ms",
            "start_sample_id",
            "end_sample_id",
            "route_available",
            "distance_m",
            "max_speed_kph",
            "avg_speed_kph",
            "energy_kwh",
            "classification",
            "confidence",
            "label",
            "created_at_ms",
            "summary_json",
        )
    private val CHARGE_SESSION_COLUMNS =
        arrayOf(
            "_id",
            "session_id",
            "vehicle_id",
            "started_at_ms",
            "ended_at_ms",
            "charger_type",
            "start_soc",
            "end_soc",
            "start_sample_id",
            "end_sample_id",
            "voltage",
            "current_a",
            "power_kw",
            "energy_kwh",
            "interrupted",
            "confidence",
            "created_at_ms",
            "summary_json",
        )
    private val BATTERY_SNAPSHOT_COLUMNS =
        arrayOf(
            "_id",
            "session_id",
            "vehicle_id",
            "captured_at_ms",
            "soc",
            "capacity_ah",
            "soh_pct",
            "pack_voltage",
            "pack_current_a",
            "pack_power_kw",
            "battery_temp_c",
            "odometer_km",
            "created_at_ms",
            "json",
        )
    private val CELL_SNAPSHOT_COLUMNS =
        arrayOf(
            "_id",
            "battery_snapshot_id",
            "cell_index",
            "voltage",
            "temperature_c",
            "resistance_mohm",
            "balance_mv",
            "json",
        )
    private val EXPORT_COLUMNS =
        arrayOf(
            "_id",
            "session_id",
            "vehicle_id",
            "created_at_ms",
            "exported_at_ms",
            "range_start_ms",
            "range_end_ms",
            "export_type",
            "status",
            "file_name",
            "mime_type",
            "bytes",
            "destination",
            "include_precise_location",
            "include_raw_logs",
            "error",
            "manifest_json",
        )
    private val ADAPTER_HISTORY_COLUMNS =
        arrayOf(
            "adapter_key",
            "address",
            "name",
            "first_seen_ms",
            "last_seen_ms",
            "connect_count",
            "scan_count",
            "demo_count",
            "sample_count",
            "last_session_id",
            "last_mode",
            "last_status",
            "supported_pids",
            "last_event_detail",
        )
    private val DIAGNOSTIC_CODE_COLUMNS =
        arrayOf(
            "_id",
            "dtc",
            "status",
            "status_label",
            "module_key",
            "module_name",
            "header",
            "first_seen_ms",
            "last_seen_ms",
            "seen_count",
            "last_session_id",
            "raw_response",
            "json",
        )
    private val DIAGNOSTIC_MERGE_COLUMNS =
        arrayOf(
            "dtc",
            "status",
            "status_label",
            "module_key",
            "module_name",
            "header",
            "first_seen_ms",
            "last_seen_ms",
            "seen_count",
            "last_session_id",
            "raw_response",
            "json",
        )
    private val MAINTENANCE_LOG_COLUMNS =
        arrayOf(
            "_id",
            "created_at_ms",
            "odometer_km",
            "type",
            "note",
            "interval_km",
            "interval_months",
        )

    private val MERGE_PROGRESS_TABLES =
        arrayOf(
            VoltTrackerDb.TABLE_VEHICLES,
            VoltTrackerDb.TABLE_SESSIONS,
            VoltTrackerDb.TABLE_TELEMETRY,
            VoltTrackerDb.TABLE_EVENTS,
            VoltTrackerDb.TABLE_PID_OBSERVATIONS,
            VoltTrackerDb.TABLE_LOCATION_SAMPLES,
            VoltTrackerDb.TABLE_FIELD_CAPABILITIES,
            VoltTrackerDb.TABLE_TRIP_SEGMENTS,
            VoltTrackerDb.TABLE_CHARGE_SESSIONS,
            VoltTrackerDb.TABLE_BATTERY_SNAPSHOTS,
            VoltTrackerDb.TABLE_CELL_SNAPSHOTS,
            VoltTrackerDb.TABLE_EXPORTS,
            VoltTrackerDb.TABLE_ADAPTER_HISTORY,
            VoltTrackerDb.TABLE_DIAGNOSTIC_CODES,
            VoltTrackerDb.TABLE_MAINTENANCE_LOG,
        )
}
