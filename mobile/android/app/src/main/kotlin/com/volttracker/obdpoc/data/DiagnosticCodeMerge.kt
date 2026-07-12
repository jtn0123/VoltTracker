package com.volttracker.obdpoc.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/**
 * Donor merge for the aggregated `diagnostic_codes` counter rows, extracted from [DatabaseMerger]
 * so the merger stays under its LargeClass ratchet (the [VehicleIdentityMerge] /
 * [FieldCapabilityMerge] decomposition direction, grade item A2).
 *
 * Code rows are keyed by (`module_key`, `dtc`, `status`) and carry a lifetime `seen_count`, so a
 * duplicate-keyed donor row merges instead of skipping: the counter sums — except when the donor's
 * seen interval is contained in the live row's (a re-import of an already-merged backup), where
 * the larger value wins so re-imports stay idempotent — and descriptive fields follow newest-wins
 * on `last_seen_ms`. See [DatabaseMerger.donorIntervalContained].
 */
internal object DiagnosticCodeMerge {
    /** Live-row columns read for the merge: [DIAGNOSTIC_CODE_COLUMNS][DatabaseMerger.donorColumnsFor] minus `_id`. */
    private val MERGE_COLUMNS =
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

    fun copy(
        target: SQLiteDatabase,
        donor: SQLiteDatabase,
        sessionMap: Map<Long, Long>,
        progress: DatabaseMerger.MergeProgress,
    ): Long {
        var touched = 0L
        DatabaseMerger.donorRows(donor, VoltTrackerDb.TABLE_DIAGNOSTIC_CODES).use { c ->
            while (c.moveToNext()) {
                progress.step("Merging diagnostic codes")
                val cv = DatabaseMerger.readRow(c)
                val moduleKey = cv.getAsString("module_key")
                val dtc = cv.getAsString("dtc")
                val status = cv.getAsString("status")
                if (moduleKey == null || dtc == null || status == null) {
                    continue
                }
                cv.remove("_id")
                val canCopyLastSession = DatabaseMerger.canCopyMappedReference(cv, "last_session_id", sessionMap)
                DatabaseMerger.remap(cv, "last_session_id", sessionMap)
                val where = "module_key = ? AND dtc = ? AND status = ?"
                val args = arrayOf(moduleKey, dtc, status)
                val existing =
                    DatabaseMerger.queryOne(
                        target,
                        VoltTrackerDb.TABLE_DIAGNOSTIC_CODES,
                        where,
                        args,
                        MERGE_COLUMNS,
                    )
                if (existing == null) {
                    target.insertOrThrow(VoltTrackerDb.TABLE_DIAGNOSTIC_CODES, null, cv)
                    touched++
                } else {
                    mergeIntoExisting(target, where, args, existing, cv, canCopyLastSession)
                }
            }
        }
        return touched
    }

    private fun mergeIntoExisting(
        target: SQLiteDatabase,
        where: String,
        args: Array<String>,
        existing: ContentValues,
        cv: ContentValues,
        canCopyLastSession: Boolean,
    ) {
        val merged = ContentValues()
        val donorContained = DatabaseMerger.donorIntervalContained(existing, cv)
        DatabaseMerger.mergeCounter(merged, "seen_count", existing, cv, donorContained)
        merged.put(
            "first_seen_ms",
            DatabaseMerger.minLong(existing.getAsLong("first_seen_ms"), cv.getAsLong("first_seen_ms")),
        )
        val liveLast = DatabaseMerger.orZero(existing.getAsLong("last_seen_ms"))
        val donorLast = DatabaseMerger.orZero(cv.getAsLong("last_seen_ms"))
        merged.put("last_seen_ms", maxOf(liveLast, donorLast))
        if (donorLast >= liveLast) {
            DatabaseMerger.copyIfPresent(merged, cv, "status_label")
            DatabaseMerger.copyIfPresent(merged, cv, "module_name")
            DatabaseMerger.copyIfPresent(merged, cv, "header")
            if (canCopyLastSession) {
                DatabaseMerger.copyIfPresent(merged, cv, "last_session_id")
            }
            DatabaseMerger.copyIfPresent(merged, cv, "raw_response")
            DatabaseMerger.copyIfPresent(merged, cv, "json")
        }
        target.update(VoltTrackerDb.TABLE_DIAGNOSTIC_CODES, merged, where, args)
    }
}
