package com.volttracker.obdpoc.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Brings an older-schema VoltTracker backup up to the current schema version so it can be restored
 * or merged. A backup exported by an earlier app version carries forward-compatible data but an
 * older `user_version`, and the staging validation requires the current version — so without this it
 * is rejected outright.
 *
 * Rather than duplicate migration SQL (which would drift from the real schema), this reuses
 * [VoltTrackerDb]'s own `onUpgrade` path: it copies the file to a private working name in the
 * databases dir, opens it through the helper (which runs every intervening migration step
 * transactionally), then copies the upgraded file back over the staged candidate. Newer-than-app
 * backups are refused — SQLite has no safe downgrade.
 */
object BackupMigrator {
    /** Outcome of an attempt to bring a staged backup to the current schema. */
    enum class Result {
        /** File is already at the current schema version. */
        ALREADY_CURRENT,

        /** File was older and has been upgraded in place. */
        MIGRATED,

        /** File is from a newer app version; downgrading is not safe. */
        TOO_NEW,

        /** Not a VoltTracker database we can migrate. */
        NOT_A_BACKUP,

        /** Migration was attempted but failed; the file is left unchanged. */
        FAILED,
    }

    private const val MIGRATION_DB_PREFIX = "restore-migrate-"
    private const val IO_BUFFER_BYTES = 8192

    /**
     * Upgrades [dbFile] in place to [VoltTrackerDb.DATABASE_VERSION] if it is an older VoltTracker
     * backup. The file is replaced only on a successful migration; on any failure it is left
     * byte-for-byte unchanged.
     */
    @JvmStatic
    fun migrateToCurrentVersion(
        context: Context?,
        dbFile: File?,
    ): Result {
        if (context == null || dbFile == null || !dbFile.exists()) {
            return Result.NOT_A_BACKUP
        }
        val probe =
            try {
                SQLiteDatabase
                    .openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
                    .use { db -> db.version to hasTable(db, VoltTrackerDb.TABLE_SESSIONS) }
            } catch (ex: RuntimeException) {
                return Result.NOT_A_BACKUP
            }
        val version = probe.first
        val looksLikeBackup = probe.second
        if (version == VoltTrackerDb.DATABASE_VERSION) {
            return Result.ALREADY_CURRENT
        }
        if (version > VoltTrackerDb.DATABASE_VERSION) {
            return Result.TOO_NEW
        }
        // Only migrate something that is recognizably one of our backups; otherwise opening it
        // through the helper would run onCreate/onUpgrade against an unrelated SQLite file.
        if (version < 1 || !looksLikeBackup) {
            return Result.NOT_A_BACKUP
        }

        val workingName = MIGRATION_DB_PREFIX + UUID.randomUUID() + ".db"
        val working = context.getDatabasePath(workingName)
        val parent = working.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return Result.FAILED
        }
        deleteDbFamily(working)
        try {
            copyFile(dbFile, working)
            // Opening through the helper triggers onConfigure + onUpgrade(version -> current)
            // synchronously. The checkpoint folds the WAL back so the copy below is complete.
            val helper = VoltTrackerDb(context, workingName)
            try {
                val db = helper.writableDatabase
                // wal_checkpoint returns a row, so it must go through rawQuery (execSQL rejects
                // result-returning statements). Fold the WAL back into the main file before copy.
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            } finally {
                helper.close()
            }
            copyFile(working, dbFile)
            return Result.MIGRATED
        } catch (ex: IOException) {
            android.util.Log.w("VoltTracker", "backup migration failed", ex)
            return Result.FAILED
        } catch (ex: RuntimeException) {
            android.util.Log.w("VoltTracker", "backup migration failed", ex)
            return Result.FAILED
        } finally {
            deleteDbFamily(working)
        }
    }

    private fun hasTable(
        db: SQLiteDatabase,
        table: String,
    ): Boolean =
        db
            .rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
                arrayOf(table),
            ).use { cursor -> cursor.moveToFirst() }

    private fun copyFile(
        source: File,
        dest: File,
    ) {
        FileInputStream(source).use { input ->
            FileOutputStream(dest).use { output ->
                val buffer = ByteArray(IO_BUFFER_BYTES)
                var read: Int
                while (input.read(buffer).also { read = it } > 0) {
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
        }
    }

    private fun deleteDbFamily(file: File) {
        file.delete()
        File(file.path + "-wal").delete()
        File(file.path + "-shm").delete()
        File(file.path + "-journal").delete()
    }
}
