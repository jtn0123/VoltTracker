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
        val args = arrayOf(cutoffMs.toString())
        var deleted = 0
        db.transaction {
            deleted += db.delete(VoltTrackerDb.TABLE_TELEMETRY, "captured_at_ms < ?", args)
            deleted += db.delete(VoltTrackerDb.TABLE_LOCATION_SAMPLES, "captured_at_ms < ?", args)
            deleted += db.delete(VoltTrackerDb.TABLE_EVENTS, "occurred_at_ms < ?", args)
            deleted += db.delete(VoltTrackerDb.TABLE_PID_OBSERVATIONS, "observed_at_ms < ?", args)
            db.delete(VoltTrackerDb.TABLE_SESSION_TRIP_ROLLUPS, null, null)
            db.delete(VoltTrackerDb.TABLE_TRIP_SEGMENTS, null, null)
        }
        return deleted
    }

    fun checkpoint() {
        try {
            helper.writableDatabase.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
        } catch (ex: RuntimeException) {
            Log.w("VoltTracker", "wal_checkpoint(TRUNCATE) failed; backup uses current file", ex)
        }
    }

    fun mergeFrom(donorDbFile: File?): DatabaseMerger.MergeResult {
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
            return DatabaseMerger.merge(target, donor)
        } catch (ex: RuntimeException) {
            return DatabaseMerger.MergeResult.failure("Merge failed - could not open the backup file.")
        } finally {
            donor?.close()
        }
    }

    companion object {
        /** Default retention for raw telemetry/location/event rows: 60 days. */
        const val DEFAULT_RAW_RETENTION_DAYS: Int = 60
    }
}
