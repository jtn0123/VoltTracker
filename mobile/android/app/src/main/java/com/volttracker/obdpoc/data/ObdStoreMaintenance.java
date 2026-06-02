package com.volttracker.obdpoc.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;

/**
 * Database maintenance: full-store reset, WAL checkpoint, and the on-disk path lookup. Pulled out
 * of {@link ObdLocalStore} so the writable facade stays focused on session lifecycle / telemetry /
 * event writes, mirroring the read-side split into {@link ObdStoreReports} / {@link ObdStoreTrips}
 * / {@link ObdStoreSupport}.
 *
 * <p>Package-private — callers go through the {@link ObdLocalStore#clearAllData()} / {@link
 * ObdLocalStore#checkpoint()} / {@link ObdLocalStore#getDatabaseFile()} delegates so the existing
 * test surface is unchanged.
 */
final class ObdStoreMaintenance {

    private final Context context;
    private final VoltTrackerDb helper;

    ObdStoreMaintenance(Context context, VoltTrackerDb helper) {
        this.context = context;
        this.helper = helper;
    }

    /**
     * Truncates every table the app writes to. Used by the dashboard "wipe data" affordance and by
     * tests' {@code tearDown}. Wrapped in a transaction so a crash between deletes doesn't leave
     * (e.g.) telemetry orphaned without its parent session row.
     */
    void clearAllData() {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(VoltTrackerDb.TABLE_CELL_SNAPSHOTS, null, null);
            db.delete(VoltTrackerDb.TABLE_BATTERY_SNAPSHOTS, null, null);
            db.delete(VoltTrackerDb.TABLE_EXPORTS, null, null);
            db.delete(VoltTrackerDb.TABLE_CHARGE_SESSIONS, null, null);
            db.delete(VoltTrackerDb.TABLE_SESSION_TRIP_ROLLUPS, null, null);
            db.delete(VoltTrackerDb.TABLE_TRIP_SEGMENTS, null, null);
            db.delete(VoltTrackerDb.TABLE_FIELD_CAPABILITIES, null, null);
            db.delete(VoltTrackerDb.TABLE_LOCATION_SAMPLES, null, null);
            db.delete(VoltTrackerDb.TABLE_DIAGNOSTIC_CODES, null, null);
            db.delete(VoltTrackerDb.TABLE_PID_OBSERVATIONS, null, null);
            db.delete(VoltTrackerDb.TABLE_EVENTS, null, null);
            db.delete(VoltTrackerDb.TABLE_TELEMETRY, null, null);
            db.delete(VoltTrackerDb.TABLE_SESSIONS, null, null);
            db.delete(VoltTrackerDb.TABLE_VEHICLES, null, null);
            db.delete(VoltTrackerDb.TABLE_ADAPTER_HISTORY, null, null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    File getDatabaseFile() {
        return context.getDatabasePath(VoltTrackerDb.DATABASE_NAME);
    }

    /**
     * Default retention for raw telemetry/location/event rows: 60 days. Sessions and derived
     * summaries are kept forever.
     */
    static final int DEFAULT_RAW_RETENTION_DAYS = 60;

    /**
     * Prunes raw rows older than {@code keepDays} days from the high-cardinality tables: {@code
     * telemetry_samples}, {@code location_samples}, {@code status_events}, {@code
     * pid_observations}. Per-session summary rows in {@code obd_sessions} and derived rows (trips,
     * charge sessions, battery snapshots, adapter history) are NOT touched — they're the long-term
     * history the user wants kept.
     *
     * <p>Run on a background thread; this method is safe to call repeatedly (it's just a DELETE
     * WHERE captured_at_ms &lt; cutoff). Indexes on these tables make the cutoff scan cheap.
     * Returns the total number of rows deleted, mostly useful for tests.
     *
     * @param keepDays days of raw data to retain; values &lt;= 0 are treated as no-op.
     * @return total rows deleted across the four pruned tables.
     */
    int pruneRawDataOlderThan(int keepDays) {
        if (keepDays <= 0) {
            return 0;
        }
        long cutoffMs = System.currentTimeMillis() - keepDays * 86_400_000L;
        SQLiteDatabase db = helper.getWritableDatabase();
        String[] args = {String.valueOf(cutoffMs)};
        int deleted = 0;
        db.beginTransaction();
        try {
            deleted += db.delete(VoltTrackerDb.TABLE_TELEMETRY, "captured_at_ms < ?", args);
            deleted += db.delete(VoltTrackerDb.TABLE_LOCATION_SAMPLES, "captured_at_ms < ?", args);
            // status_events uses occurred_at_ms (not captured_at_ms) — see VoltTrackerDb schema.
            deleted += db.delete(VoltTrackerDb.TABLE_EVENTS, "occurred_at_ms < ?", args);
            deleted += db.delete(VoltTrackerDb.TABLE_PID_OBSERVATIONS, "observed_at_ms < ?", args);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return deleted;
    }

    /** Flushes the write-ahead log into the main DB file so a file copy is a complete backup. */
    void checkpoint() {
        try {
            helper.getWritableDatabase().execSQL("PRAGMA wal_checkpoint(TRUNCATE)");
        } catch (RuntimeException ex) {
            // Backup proceeds with whatever is already in the main file.
            android.util.Log.w(
                    "VoltTracker", "wal_checkpoint(TRUNCATE) failed; backup uses current file", ex);
        }
    }

    /**
     * Folds a verified donor backup into the live database via {@link DatabaseMerger}. The donor
     * file is opened read-only; the live connection owns the merge transaction, so a failure leaves
     * the live database untouched. The caller is responsible for having stopped logging first.
     */
    DatabaseMerger.MergeResult mergeFrom(File donorDbFile) {
        if (donorDbFile == null || !donorDbFile.exists()) {
            return DatabaseMerger.MergeResult.failure("Merge failed - backup file is missing.");
        }
        SQLiteDatabase target = helper.getWritableDatabase();
        SQLiteDatabase donor = null;
        try {
            donor =
                    SQLiteDatabase.openDatabase(
                            donorDbFile.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            // Merge only supports same-schema backups. BackupController already gates on this via
            // stageRestoreFile, but mergeFrom is a public entry point — enforce it here too so a
            // direct caller can't drive a mismatched donor into the column-by-column copy.
            if (donor.getVersion() != VoltTrackerDb.DATABASE_VERSION) {
                return DatabaseMerger.MergeResult.failure(
                        "Merge failed - that backup is from a different app version.");
            }
            return DatabaseMerger.merge(target, donor);
        } catch (RuntimeException ex) {
            return DatabaseMerger.MergeResult.failure(
                    "Merge failed - could not open the backup file.");
        } finally {
            if (donor != null) {
                donor.close();
            }
        }
    }
}
