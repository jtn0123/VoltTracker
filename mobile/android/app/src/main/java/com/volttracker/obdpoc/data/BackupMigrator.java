package com.volttracker.obdpoc.data;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * Brings an older-schema VoltTracker backup up to the current schema version so it can be restored
 * or merged. A backup exported by an earlier app version (e.g. a v8 file taken before an update)
 * carries forward-compatible data but an older {@code user_version}, and the staging validation
 * requires the current version — so without this it is rejected outright.
 *
 * <p>Rather than duplicate migration SQL (which would drift from the real schema), this reuses
 * {@link VoltTrackerDb}'s own {@code onUpgrade} path: it copies the file to a private working name
 * in the databases dir, opens it through the helper (which runs every intervening migration step
 * transactionally), then copies the upgraded file back over the staged candidate. Newer-than-app
 * backups are refused — SQLite has no safe downgrade.
 */
public final class BackupMigrator {

    private BackupMigrator() {}

    /** Outcome of an attempt to bring a staged backup to the current schema. */
    public enum Result {
        /** File is already at the current schema version. */
        ALREADY_CURRENT,
        /** File was older and has been upgraded in place. */
        MIGRATED,
        /** File is from a newer app version; downgrading is not safe. */
        TOO_NEW,
        /** Not a VoltTracker database we can migrate. */
        NOT_A_BACKUP,
        /** Migration was attempted but failed; the file is left unchanged. */
        FAILED
    }

    private static final String MIGRATION_DB_PREFIX = "restore-migrate-";
    private static final int IO_BUFFER_BYTES = 8192;

    /**
     * Upgrades {@code dbFile} in place to {@link VoltTrackerDb#DATABASE_VERSION} if it is an older
     * VoltTracker backup. The file is replaced only on a successful migration; on any failure it is
     * left byte-for-byte unchanged.
     */
    public static Result migrateToCurrentVersion(Context context, File dbFile) {
        if (context == null || dbFile == null || !dbFile.exists()) {
            return Result.NOT_A_BACKUP;
        }
        int version;
        boolean looksLikeBackup;
        try (SQLiteDatabase probe =
                SQLiteDatabase.openDatabase(dbFile.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
            version = probe.getVersion();
            looksLikeBackup = hasTable(probe, VoltTrackerDb.TABLE_SESSIONS);
        } catch (RuntimeException ex) {
            return Result.NOT_A_BACKUP;
        }
        if (version == VoltTrackerDb.DATABASE_VERSION) {
            return Result.ALREADY_CURRENT;
        }
        if (version > VoltTrackerDb.DATABASE_VERSION) {
            return Result.TOO_NEW;
        }
        // Only migrate something that is recognizably one of our backups; otherwise opening it
        // through the helper would run onCreate/onUpgrade against an unrelated SQLite file.
        if (version < 1 || !looksLikeBackup) {
            return Result.NOT_A_BACKUP;
        }

        String workingName = MIGRATION_DB_PREFIX + UUID.randomUUID() + ".db";
        File working = context.getDatabasePath(workingName);
        File parent = working.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return Result.FAILED;
        }
        deleteDbFamily(working);
        try {
            copyFile(dbFile, working);
            // Opening through the helper triggers onConfigure + onUpgrade(version -> current)
            // synchronously. The checkpoint folds the WAL back so the copy below is complete.
            VoltTrackerDb helper = new VoltTrackerDb(context, workingName);
            try {
                SQLiteDatabase db = helper.getWritableDatabase();
                // wal_checkpoint returns a row, so it must go through rawQuery (execSQL rejects
                // result-returning statements). Fold the WAL back into the main file before copy.
                try (Cursor ignored = db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null)) {
                    ignored.moveToFirst();
                }
            } finally {
                helper.close();
            }
            copyFile(working, dbFile);
            return Result.MIGRATED;
        } catch (IOException | RuntimeException ex) {
            android.util.Log.w("VoltTracker", "backup migration failed", ex);
            return Result.FAILED;
        } finally {
            deleteDbFamily(working);
        }
    }

    private static boolean hasTable(SQLiteDatabase db, String table) {
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
                        new String[] {table})) {
            return cursor.moveToFirst();
        }
    }

    private static void copyFile(File source, File dest) throws IOException {
        try (FileInputStream in = new FileInputStream(source);
                FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[IO_BUFFER_BYTES];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.getFD().sync();
        }
    }

    private static void deleteDbFamily(File file) {
        file.delete();
        new File(file.getPath() + "-wal").delete();
        new File(file.getPath() + "-shm").delete();
        new File(file.getPath() + "-journal").delete();
    }
}
