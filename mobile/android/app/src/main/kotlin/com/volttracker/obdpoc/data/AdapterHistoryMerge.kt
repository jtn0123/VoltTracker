package com.volttracker.obdpoc.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/**
 * Donor merge for the aggregated `adapter_history` counter rows, extracted from [DatabaseMerger]
 * so the merger stays under its LargeClass ratchet (the [VehicleIdentityMerge] /
 * [FieldCapabilityMerge] decomposition direction, grade item A2).
 *
 * Adapter rows are keyed by `adapter_key` and carry lifetime counters (connect/scan/demo/sample),
 * so a duplicate-keyed donor row merges instead of skipping: counters sum — except when the
 * donor's seen interval is contained in the live row's (a re-import of an already-merged backup),
 * where the larger value wins so re-imports stay idempotent — and descriptive fields follow
 * newest-wins on `last_seen_ms`. See [DatabaseMerger.donorIntervalContained].
 */
internal object AdapterHistoryMerge {
    fun copy(
        target: SQLiteDatabase,
        donor: SQLiteDatabase,
        sessionMap: Map<Long, Long>,
        progress: DatabaseMerger.MergeProgress,
    ): Long {
        var touched = 0L
        DatabaseMerger.donorRows(donor, VoltTrackerDb.TABLE_ADAPTER_HISTORY).use { c ->
            while (c.moveToNext()) {
                progress.step("Merging adapter history")
                val cv = DatabaseMerger.readRow(c)
                val key = cv.getAsString("adapter_key") ?: continue
                val canCopyLastSession = DatabaseMerger.canCopyMappedReference(cv, "last_session_id", sessionMap)
                DatabaseMerger.remap(cv, "last_session_id", sessionMap)
                val existing =
                    DatabaseMerger.queryOne(
                        target,
                        VoltTrackerDb.TABLE_ADAPTER_HISTORY,
                        "adapter_key = ?",
                        arrayOf(key),
                    )
                if (existing == null) {
                    target.insertOrThrow(VoltTrackerDb.TABLE_ADAPTER_HISTORY, null, cv)
                    touched++
                } else {
                    mergeIntoExisting(target, key, existing, cv, canCopyLastSession)
                }
            }
        }
        return touched
    }

    private fun mergeIntoExisting(
        target: SQLiteDatabase,
        key: String,
        existing: ContentValues,
        cv: ContentValues,
        canCopyLastSession: Boolean,
    ) {
        val merged = ContentValues()
        val donorContained = DatabaseMerger.donorIntervalContained(existing, cv)
        DatabaseMerger.mergeCounter(merged, "connect_count", existing, cv, donorContained)
        DatabaseMerger.mergeCounter(merged, "scan_count", existing, cv, donorContained)
        DatabaseMerger.mergeCounter(merged, "demo_count", existing, cv, donorContained)
        DatabaseMerger.mergeCounter(merged, "sample_count", existing, cv, donorContained)
        merged.put(
            "first_seen_ms",
            DatabaseMerger.minLong(existing.getAsLong("first_seen_ms"), cv.getAsLong("first_seen_ms")),
        )
        val liveLast = DatabaseMerger.orZero(existing.getAsLong("last_seen_ms"))
        val donorLast = DatabaseMerger.orZero(cv.getAsLong("last_seen_ms"))
        merged.put("last_seen_ms", maxOf(liveLast, donorLast))
        if (donorLast >= liveLast) {
            DatabaseMerger.copyIfPresent(merged, cv, "address")
            DatabaseMerger.copyIfPresent(merged, cv, "name")
            if (canCopyLastSession) {
                DatabaseMerger.copyIfPresent(merged, cv, "last_session_id")
            }
            DatabaseMerger.copyIfPresent(merged, cv, "last_mode")
            DatabaseMerger.copyIfPresent(merged, cv, "last_status")
            DatabaseMerger.copyIfPresent(merged, cv, "supported_pids")
            DatabaseMerger.copyIfPresent(merged, cv, "last_event_detail")
        }
        target.update(VoltTrackerDb.TABLE_ADAPTER_HISTORY, merged, "adapter_key = ?", arrayOf(key))
    }
}
