package com.volttracker.obdpoc.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.core.database.sqlite.transaction
import java.io.File

/** Database maintenance: full-store reset, WAL checkpoint, and on-disk path lookup. */
class ObdStoreMaintenance(
    private val context: Context,
    private val helper: VoltTrackerDb,
) {
    fun clearAllData() {
        val db = helper.writableDatabase
        db.transaction {
            db.delete(VoltTrackerDb.TABLE_CELL_SNAPSHOTS, null, null)
            db.delete(VoltTrackerDb.TABLE_BATTERY_SNAPSHOTS, null, null)
            db.delete(VoltTrackerDb.TABLE_EXPORTS, null, null)
            db.delete(VoltTrackerDb.TABLE_CHARGE_SESSIONS, null, null)
            db.delete(VoltTrackerDb.TABLE_SESSION_TRIP_ROLLUPS, null, null)
            db.delete(VoltTrackerDb.TABLE_TRIP_SEGMENTS, null, null)
            db.delete(VoltTrackerDb.TABLE_FIELD_CAPABILITIES, null, null)
            db.delete(VoltTrackerDb.TABLE_LOCATION_SAMPLES, null, null)
            db.delete(VoltTrackerDb.TABLE_DIAGNOSTIC_CODES, null, null)
            db.delete(VoltTrackerDb.TABLE_PID_OBSERVATIONS, null, null)
            db.delete(VoltTrackerDb.TABLE_EVENTS, null, null)
            db.delete(VoltTrackerDb.TABLE_TELEMETRY, null, null)
            db.delete(VoltTrackerDb.TABLE_SESSIONS, null, null)
            db.delete(VoltTrackerDb.TABLE_VEHICLES, null, null)
            db.delete(VoltTrackerDb.TABLE_ADAPTER_HISTORY, null, null)
        }
    }

    fun getDatabaseFile(): File = context.getDatabasePath(VoltTrackerDb.DATABASE_NAME)

    fun pruneRawDataOlderThan(keepDays: Int): Int {
        if (keepDays <= 0) {
            return 0
        }
        val cutoffMs = System.currentTimeMillis() - keepDays * 86_400_000L
        val db = helper.writableDatabase
        // Sessions whose raw rows are about to be pruned: only their trip rollups / list cache /
        // segments become stale, so cache invalidation below is scoped to them instead of the old
        // global flush (which forced every session's trips to recompute on the next read).
        val affectedSessions = LinkedHashSet<Long>()
        collectAffectedSessions(db, VoltTrackerDb.TABLE_TELEMETRY, "captured_at_ms", cutoffMs, affectedSessions)
        collectAffectedSessions(db, VoltTrackerDb.TABLE_LOCATION_SAMPLES, "captured_at_ms", cutoffMs, affectedSessions)
        collectAffectedSessions(db, VoltTrackerDb.TABLE_EVENTS, "occurred_at_ms", cutoffMs, affectedSessions)
        collectAffectedSessions(db, VoltTrackerDb.TABLE_PID_OBSERVATIONS, "observed_at_ms", cutoffMs, affectedSessions)
        var deleted = 0
        deleted += deleteInBatches(db, VoltTrackerDb.TABLE_TELEMETRY, "captured_at_ms", cutoffMs)
        deleted += deleteInBatches(db, VoltTrackerDb.TABLE_LOCATION_SAMPLES, "captured_at_ms", cutoffMs)
        deleted += deleteInBatches(db, VoltTrackerDb.TABLE_EVENTS, "occurred_at_ms", cutoffMs)
        deleted += deleteInBatches(db, VoltTrackerDb.TABLE_PID_OBSERVATIONS, "observed_at_ms", cutoffMs)
        invalidateTripCachesForSessions(db, affectedSessions)
        return deleted
    }

    private fun collectAffectedSessions(
        db: SQLiteDatabase,
        table: String,
        timeColumn: String,
        cutoffMs: Long,
        into: MutableSet<Long>,
    ) {
        db
            .rawQuery(
                "SELECT DISTINCT session_id FROM $table WHERE $timeColumn < ? AND session_id IS NOT NULL",
                arrayOf(cutoffMs.toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    into.add(cursor.getLong(0))
                }
            }
    }

    /**
     * Deletes matching rows in [PRUNE_DELETE_BATCH]-sized batches (Android SQLite has no
     * `DELETE ... LIMIT`, hence the rowid subselect). Each batch commits on its own, so a large
     * backlog never holds the writer lock for one long delete — concurrent dashboard reads and
     * telemetry writes interleave between batches.
     */
    private fun deleteInBatches(
        db: SQLiteDatabase,
        table: String,
        timeColumn: String,
        cutoffMs: Long,
    ): Int {
        val statement =
            db.compileStatement(
                "DELETE FROM $table WHERE rowid IN " +
                    "(SELECT rowid FROM $table WHERE $timeColumn < ? LIMIT $PRUNE_DELETE_BATCH)",
            )
        var total = 0
        statement.use {
            var deleted: Int
            do {
                it.clearBindings()
                it.bindLong(1, cutoffMs)
                deleted = it.executeUpdateDelete()
                total += deleted
            } while (deleted >= PRUNE_DELETE_BATCH)
        }
        return total
    }

    private fun invalidateTripCachesForSessions(
        db: SQLiteDatabase,
        sessionIds: Set<Long>,
    ) {
        if (sessionIds.isEmpty()) {
            return
        }
        // Chunked IN-lists keep the statement under SQLite's bind-argument limit.
        for (chunk in sessionIds.chunked(500)) {
            val placeholders = chunk.joinToString(",") { "?" }
            val args = chunk.map { it.toString() }.toTypedArray()
            db.transaction {
                db.delete(VoltTrackerDb.TABLE_SESSION_TRIP_ROLLUPS, "session_id IN ($placeholders)", args)
                db.delete(VoltTrackerDb.TABLE_TRIP_LIST_CACHE, "session_id IN ($placeholders)", args)
                db.delete(VoltTrackerDb.TABLE_TRIP_SEGMENTS, "session_id IN ($placeholders)", args)
            }
        }
    }

    fun checkpoint() {
        try {
            // wal_checkpoint returns a result row (busy, log, checkpointed), so it must go
            // through rawQuery — execSQL rejects statements that return data, which made the
            // old execSQL version land in the catch below and skip the checkpoint entirely.
            helper.writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use {
                it.moveToFirst()
            }
        } catch (ex: RuntimeException) {
            Log.w(TAG, "wal_checkpoint(TRUNCATE) failed; backup uses current file", ex)
        }
    }

    /** Outcome of a `PRAGMA quick_check`: [ok] plus a bounded list of problem strings. */
    data class IntegrityResult(
        val ok: Boolean,
        val problems: List<String>,
    )

    /**
     * Runs `PRAGMA quick_check` (a faster `integrity_check` that skips index-content
     * verification) and reports up to [maxProblems] problem rows. Never throws — any
     * failure to even run the check is reported as a non-ok result.
     */
    fun quickCheck(maxProblems: Int = MAX_QUICK_CHECK_PROBLEMS): IntegrityResult {
        val bound = maxProblems.coerceAtLeast(1)
        return try {
            val problems = ArrayList<String>()
            helper.writableDatabase.rawQuery("PRAGMA quick_check($bound)", null).use { cursor ->
                while (cursor.moveToNext() && problems.size < bound) {
                    val row = cursor.getString(0)
                    if (!"ok".equals(row, ignoreCase = true)) {
                        problems.add(row ?: "unknown integrity problem")
                    }
                }
            }
            IntegrityResult(problems.isEmpty(), problems)
        } catch (ex: RuntimeException) {
            Log.w(TAG, "quick_check could not run; treating database as suspect", ex)
            IntegrityResult(
                false,
                listOf("quick_check failed: ${ex.javaClass.simpleName}: ${ex.message ?: "no detail"}"),
            )
        }
    }

    /**
     * Reclaims disk space freed by row deletion. Databases created by Android's framework
     * SQLite default to auto_vacuum=FULL (SQLITE_DEFAULT_AUTOVACUUM=1), so their freelist
     * self-drains and this is a cheap no-op — but database files that arrived via
     * restore/merge may have been created with auto_vacuum=NONE, where freed pages only
     * return to the OS through an explicit `VACUUM`. That rewrite is expensive, so it only
     * runs when `PRAGMA freelist_count` says more than [freePageThreshold] pages (~4 KiB
     * each) are reclaimable. The WAL is checkpointed first so the rewrite covers every
     * committed page (wal_checkpoint returns a result row, so it must go through rawQuery).
     * VACUUM cannot run inside a transaction; this skips (and never throws) in that case.
     *
     * @return true when a VACUUM actually ran.
     */
    fun vacuumIfNeeded(freePageThreshold: Long = VACUUM_FREE_PAGE_THRESHOLD): Boolean {
        return try {
            val db = helper.writableDatabase
            if (db.inTransaction()) {
                Log.w(TAG, "vacuumIfNeeded skipped: a transaction is open on this thread")
                return false
            }
            // Probed BEFORE the checkpoint on purpose: a pre-checkpoint count can miss frees
            // still sitting in the WAL, deferring the VACUUM to a later boot at worst — the
            // cheap probe avoids paying a wal_checkpoint on every single app start.
            val freePages = readLongPragma(db, "freelist_count")
            if (freePages <= freePageThreshold) {
                return false
            }
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            db.execSQL("VACUUM")
            Log.i(TAG, "VACUUM reclaimed $freePages free pages")
            true
        } catch (ex: RuntimeException) {
            Log.w(TAG, "vacuumIfNeeded failed; continuing without reclaiming space", ex)
            false
        }
    }

    /**
     * Startup maintenance entry point: prunes raw rows older than [keepDays], then reclaims
     * freed disk space when enough has accumulated. Returns the pruned row count.
     */
    fun runStartupMaintenance(keepDays: Int): Int {
        val pruned = pruneRawDataOlderThan(keepDays)
        vacuumIfNeeded()
        return pruned
    }

    private fun readLongPragma(
        db: SQLiteDatabase,
        pragma: String,
    ): Long =
        db.rawQuery("PRAGMA $pragma", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }

    fun mergeFrom(
        donorDbFile: File?,
        progressListener: DatabaseMerger.ProgressListener? = null,
    ): DatabaseMerger.MergeResult {
        if (donorDbFile == null || !donorDbFile.exists()) {
            return DatabaseMerger.MergeResult.failure("Merge failed - backup file is missing.")
        }
        val target = helper.writableDatabase
        var donor: SQLiteDatabase? = null
        try {
            donor = SQLiteDatabase.openDatabase(donorDbFile.path, null, SQLiteDatabase.OPEN_READONLY)
            if (donor.version != VoltTrackerDb.DATABASE_VERSION) {
                return DatabaseMerger.MergeResult.failure(
                    "Merge failed - that backup is from a different app version.",
                )
            }
            return DatabaseMerger.merge(target, donor, progressListener)
        } catch (ex: RuntimeException) {
            return DatabaseMerger.MergeResult.failure("Merge failed - could not open the backup file.")
        } finally {
            donor?.close()
        }
    }

    companion object {
        private const val TAG = "VoltTracker"

        /** Default retention for raw telemetry/location/event rows: 60 days. */
        const val DEFAULT_RAW_RETENTION_DAYS: Int = 60

        /** Rows deleted per prune batch; each batch is its own short write transaction. */
        const val PRUNE_DELETE_BATCH: Int = 500

        /** Free pages (~4 KiB each) that justify a full VACUUM: >256 pages is about 1 MiB. */
        const val VACUUM_FREE_PAGE_THRESHOLD: Long = 256L

        /** quick_check problem rows reported back; keeps the result (and any log line) bounded. */
        const val MAX_QUICK_CHECK_PROBLEMS: Int = 5
    }
}
