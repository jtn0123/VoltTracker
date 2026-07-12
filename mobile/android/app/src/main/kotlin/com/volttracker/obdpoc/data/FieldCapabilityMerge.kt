package com.volttracker.obdpoc.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/**
 * Donor merge for the aggregated `field_capabilities` rows (audit item B5), extracted from
 * [DatabaseMerger] so the merger stays under its LargeClass ratchet (the [VehicleIdentityMerge]
 * decomposition direction).
 *
 * These rows are per-key counters like adapter history and diagnostic codes, but the generic
 * child copy used to skip duplicate-keyed donor rows outright — silently dropping the donor's
 * `response_count`, a newer `last_seen_ms`, and its `sample_json`. This applies the same
 * counter-merge + newest-wins descriptive-field pattern the adapter-history and diagnostic-code
 * merges already use, including the contained-interval idempotency guard so re-importing the same
 * backup never inflates counters.
 */
internal object FieldCapabilityMerge {
    /** Natural key of a capability row; every column except `command` may be NULL. */
    private val KEY_COLUMNS = arrayOf("vehicle_id", "adapter_key", "protocol", "header", "command", "pid")

    private val MERGE_COLUMNS =
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

    fun copy(
        target: SQLiteDatabase,
        donor: SQLiteDatabase,
        vehicleMap: Map<Long, Long>,
        progress: DatabaseMerger.MergeProgress,
    ): Long {
        var inserted = 0L
        DatabaseMerger.donorRows(donor, VoltTrackerDb.TABLE_FIELD_CAPABILITIES).use { c ->
            while (c.moveToNext()) {
                progress.step("Merging field capabilities")
                val cv = DatabaseMerger.readRow(c)
                cv.remove("_id")
                DatabaseMerger.remap(cv, "vehicle_id", vehicleMap)
                val existing = findExisting(target, cv)
                if (existing == null) {
                    target.insertOrThrow(VoltTrackerDb.TABLE_FIELD_CAPABILITIES, null, cv)
                    inserted++
                } else {
                    mergeIntoExisting(target, existing, cv)
                }
            }
        }
        return inserted
    }

    /** The live row sharing the donor row's natural key, or null (missing key columns insert). */
    private fun findExisting(
        target: SQLiteDatabase,
        cv: ContentValues,
    ): ContentValues? {
        val where = StringBuilder()
        val args = ArrayList<String>()
        for (column in KEY_COLUMNS) {
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
        return target
            .query(
                VoltTrackerDb.TABLE_FIELD_CAPABILITIES,
                MERGE_COLUMNS,
                where.toString(),
                args.toTypedArray(),
                null,
                null,
                null,
                "1",
            ).use { c -> if (c.moveToFirst()) DatabaseMerger.readRow(c) else null }
    }

    private fun mergeIntoExisting(
        target: SQLiteDatabase,
        existing: ContentValues,
        donorCv: ContentValues,
    ) {
        val merged = ContentValues()
        val donorContained = DatabaseMerger.donorIntervalContained(existing, donorCv)
        DatabaseMerger.mergeCounter(merged, "response_count", existing, donorCv, donorContained)
        merged.put(
            "first_seen_ms",
            DatabaseMerger.minLong(existing.getAsLong("first_seen_ms"), donorCv.getAsLong("first_seen_ms")),
        )
        val liveLast = DatabaseMerger.orZero(existing.getAsLong("last_seen_ms"))
        val donorLast = DatabaseMerger.orZero(donorCv.getAsLong("last_seen_ms"))
        merged.put("last_seen_ms", maxOf(liveLast, donorLast))
        if (donorLast >= liveLast) {
            DatabaseMerger.copyIfPresent(merged, donorCv, "name")
            DatabaseMerger.copyIfPresent(merged, donorCv, "unit")
            DatabaseMerger.copyIfPresent(merged, donorCv, "supported")
            DatabaseMerger.copyIfPresent(merged, donorCv, "sample_json")
        }
        target.update(
            VoltTrackerDb.TABLE_FIELD_CAPABILITIES,
            merged,
            "_id = ?",
            arrayOf(existing.getAsLong("_id").toString()),
        )
    }
}
