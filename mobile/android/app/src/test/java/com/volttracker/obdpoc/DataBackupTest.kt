package com.volttracker.obdpoc

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.volttracker.obdpoc.data.ObdLocalStore
import com.volttracker.obdpoc.data.ObdStoreMaintenance
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** Verifies [DataBackup.isVoltTrackerBackup] accepts only real Volt Tracker databases. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataBackupTest {
    @Test
    fun encryptedBackupPassphraseMustMeetMinimumLength() {
        assertFalse(DataBackup.hasMinimumPassphrase(null))
        assertFalse(DataBackup.hasMinimumPassphrase("hunter2"))
        assertTrue(DataBackup.hasMinimumPassphrase("hunter22"))
    }

    @Test
    fun rejectsAFileThatIsNotSqlite() {
        val file = File.createTempFile("not-sqlite", ".db")
        FileWriter(file).use { writer ->
            writer.write("this is plain text, not a database")
        }
        assertFalse(DataBackup.isVoltTrackerBackup(file))
        file.delete()
    }

    @Test
    fun rejectsASqliteDatabaseMissingTheCoreTables() {
        val file = tempDbFile("foreign-schema")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("CREATE TABLE unrelated (id INTEGER)")
        db.close()
        assertFalse(DataBackup.isVoltTrackerBackup(file))
        file.delete()
    }

    @Test
    fun rejectsOldTwoTableSqliteDatabase() {
        val file = tempDbFile("old-volt-backup")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("CREATE TABLE obd_sessions (_id INTEGER PRIMARY KEY)")
        db.execSQL("CREATE TABLE telemetry_samples (_id INTEGER PRIMARY KEY)")
        db.close()
        assertFalse(DataBackup.isVoltTrackerBackup(file))
        file.delete()
    }

    @Test
    fun rejectsCurrentVersionDatabaseMissingRequiredColumns() {
        val file = tempDbFile("missing-current-columns")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        createMinimalVoltSchema(db, false, false)
        db.close()
        assertFalse(DataBackup.isVoltTrackerBackup(file))
        file.delete()
    }

    @Test
    fun rejectsDatabaseWithForeignKeyViolations() {
        val file = tempDbFile("foreign-key-violations")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        createMinimalVoltSchema(db, true, true)
        db.execSQL(
            "INSERT INTO telemetry_samples (session_id, captured_at_ms, json)" +
                " VALUES (999, 1, '{}')",
        )
        db.close()
        assertFalse(DataBackup.isVoltTrackerBackup(file))
        file.delete()
    }

    @Test
    fun acceptsASqliteDatabaseWithTheCurrentVoltTrackerSchema() {
        val context = RuntimeEnvironment.getApplication()
        val store = ObdLocalStore(context)
        try {
            store.clearAllData()
            store.checkpoint()
            assertTrue(DataBackup.isVoltTrackerBackup(store.getDatabaseFile()))
        } finally {
            store.close()
        }
    }

    @Test
    fun clearsRegenerableRollupCacheFromStagedRestore() {
        // session_trip_rollups is a v9 regenerable cache. A hand-edited backup could carry rollups
        // that reference sessions that won't survive; the restore staging clears them as
        // defense-in-depth (they rebuild lazily). Seed a row, clear, and confirm it is gone while
        // the rest of the database is left intact.
        val context = RuntimeEnvironment.getApplication()
        val store = ObdLocalStore(context)
        try {
            store.clearAllData()
            store.checkpoint()
            val dbFile = store.getDatabaseFile()

            val db = SQLiteDatabase.openDatabase(dbFile.path, null, 0)
            try {
                db.execSQL(
                    "INSERT INTO obd_sessions" +
                        " (_id, mode, started_at_ms, status, sample_count, created_at_ms)" +
                        " VALUES (1, 'obd', 1, 'closed', 0, 1)",
                )
                db.execSQL(
                    "INSERT INTO session_trip_rollups" +
                        " (session_id, counted, distance_m, duration_ms, started_at_ms)" +
                        " VALUES (1, 1, 100.0, 1000, 1)",
                )
                assertEquals(1, rowCount(db, "session_trip_rollups"))
            } finally {
                db.close()
            }

            DataBackup.clearRegenerableRollupCache(dbFile)

            val verify = SQLiteDatabase.openDatabase(dbFile.path, null, 0)
            try {
                assertEquals(0, rowCount(verify, "session_trip_rollups"))
                // The originating session row must remain — only the cache is cleared.
                assertEquals(1, rowCount(verify, "obd_sessions"))
            } finally {
                verify.close()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun buildBackupFileEmitsIntegrityWarningSnapshotWhenQuickCheckFails() {
        val context = RuntimeEnvironment.getApplication()
        val store = CorruptQuickCheckStore(context)
        try {
            store.clearAllData()
            val snapshots = ArrayList<DataBackup.ProgressSnapshot>()
            val backup = DataBackup(context).buildBackupFile(store) { snapshots.add(it) }
            // A possibly damaged backup beats no backup: the file must still be produced.
            assertNotNull("backup must still be created when quick_check fails", backup)
            val warning = snapshots.firstOrNull { it.warning }
            assertNotNull("a warning-flagged progress snapshot should reach the listener", warning)
            assertEquals("Integrity warning", warning!!.phase)
            assertTrue(
                "warning detail should carry the first quick_check problem, got: ${warning.detail}",
                warning.detail!!.contains(FAKE_INTEGRITY_PROBLEM),
            )
            backup!!.delete()
        } finally {
            store.close()
        }
    }

    @Test
    fun buildEncryptedBackupFileEmitsIntegrityWarningSnapshotWhenQuickCheckFails() {
        val context = RuntimeEnvironment.getApplication()
        val store = CorruptQuickCheckStore(context)
        try {
            store.clearAllData()
            val snapshots = ArrayList<DataBackup.ProgressSnapshot>()
            val backup =
                DataBackup(context).buildEncryptedBackupFile(store, "hunter22") { snapshots.add(it) }
            assertNotNull("encrypted backup must still be created when quick_check fails", backup)
            assertTrue(
                "a warning-flagged progress snapshot should reach the listener",
                snapshots.any { it.warning },
            )
            backup!!.delete()
        } finally {
            store.close()
        }
    }

    @Test
    fun buildBackupFileEmitsNoIntegrityWarningForHealthyDatabase() {
        val context = RuntimeEnvironment.getApplication()
        val store = ObdLocalStore(context)
        try {
            store.clearAllData()
            val snapshots = ArrayList<DataBackup.ProgressSnapshot>()
            val backup = DataBackup(context).buildBackupFile(store) { snapshots.add(it) }
            assertNotNull(backup)
            assertTrue(
                "no progress snapshot should be warning-flagged for a healthy database",
                snapshots.none { it.warning },
            )
            backup!!.delete()
        } finally {
            store.close()
        }
    }

    @Test
    fun sweepClearsTransientRestoreFilesFromPriorRuns() {
        val context = RuntimeEnvironment.getApplication()
        val restoreDb = File(context.cacheDir, "restore-stale.db")
        val restoreBackup = File(context.cacheDir, "restore-stale.backup")
        writeFile(restoreDb, "plain decrypted restore cache")
        writeFile(restoreBackup, "encrypted staging cache")
        assertTrue(restoreDb.exists())
        assertTrue(restoreBackup.exists())

        DataBackup(context).sweepTransientCacheFiles()

        assertFalse(restoreDb.exists())
        assertFalse(restoreBackup.exists())
    }

    @Test
    fun sweepClearsTransientBackupShareFilesFromPriorRuns() {
        val context = RuntimeEnvironment.getApplication()
        val backups = File(context.cacheDir, "backups")
        assertTrue(backups.mkdirs() || backups.isDirectory)
        val plaintext = File(backups, "volttracker-backup-stale.db")
        val encrypted = File(backups, "volttracker-backup-stale.vtdb")
        val unrelated = File(backups, "notes.txt")
        writeFile(plaintext, "plaintext backup handoff")
        writeFile(encrypted, "encrypted backup handoff")
        writeFile(unrelated, "keep me")

        DataBackup(context).sweepTransientCacheFiles()

        assertFalse(plaintext.exists())
        assertFalse(encrypted.exists())
        assertTrue(unrelated.exists())
        unrelated.delete()
    }

    @Test
    fun restoreImportLimitAllowsTwoHundredMbBackups() {
        val twoHundredMb = 200L * 1000L * 1000L

        assertTrue(
            "restore importer must accept 200 MB-class backups before SQLite validation",
            DataBackup.MAX_RESTORE_BYTES > twoHundredMb,
        )
    }

    @Test
    fun debugExportWritesJsonFile() {
        val context = RuntimeEnvironment.getApplication()
        val sessionLogDir = File(context.filesDir, "obd-logs")
        val appLogDir = File(context.filesDir, "app-log")
        assertTrue(sessionLogDir.mkdirs() || sessionLogDir.isDirectory)
        assertTrue(appLogDir.mkdirs() || appLogDir.isDirectory)
        val sessionLog = File(sessionLogDir, "session-1000-obd.jsonl")
        val appLogFile = File(appLogDir, "app.log")
        writeFile(
            sessionLog,
            "{\"type\":\"command\",\"payload\":{\"command\":\"010C\",\"response\":\"410C1AF8\"," +
                "\"adapter\":\"AA:BB:CC:DD:EE:FF\",\"vin\":\"1G1RD6E45CU" + "112233\"," +
                "\"latitude\":34.052345,\"longitude\":-118.252233}}\n",
        )
        writeFile(
            appLogFile,
            "2026-06-07T20:00:00.000 INFO OBD: command trace mirrored\n",
        )
        val backup = DataBackup(context)

        val result =
            JSONObject(
                backup.exportDebugBundle(
                    "{\"session\":{\"state\":\"idle\"}}",
                    "{\"sampleCount\":0}",
                ),
            )

        assertTrue(result.optBoolean("ok"))
        val file = File(result.getString("path"))
        assertTrue(file.exists())
        val exported =
            JSONObject(
                String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8),
            )
        assertNotNull(exported.optJSONObject("appState"))
        assertNotNull(exported.optJSONObject("storage"))
        val diagnostics = exported.getJSONObject("diagnostics")
        val sessionTrace = diagnostics.getJSONArray("sessionCommandTrace").getJSONObject(0)
        assertEquals("session-1000-obd.jsonl", sessionTrace.optString("name"))
        assertTrue(sessionTrace.optString("text").contains("\"command\":\"010C\""))
        assertFalse(sessionTrace.optString("text").contains("AA:BB:CC:DD:EE:FF"))
        assertFalse(sessionTrace.optString("text").contains("1G1RD6E45CU" + "112233"))
        assertFalse(sessionTrace.optString("text").contains("34.052345"))
        assertTrue(sessionTrace.optString("text").contains("[bluetooth-address-redacted]"))
        assertTrue(sessionTrace.optString("text").contains("[vin-redacted]"))
        assertTrue(sessionTrace.optString("text").contains("[coordinate-redacted]"))
        val appLog = diagnostics.getJSONArray("appLog").getJSONObject(0)
        assertEquals("app.log", appLog.optString("name"))
        assertTrue(appLog.optString("text").contains("command trace mirrored"))
        file.delete()
        sessionLog.delete()
        appLogFile.delete()
    }

    @Test
    fun redactDebugLogTextRedactsKnownSensitiveFields() {
        val redacted =
            DataBackup.redactDebugLogText(
                "adapter=00:11:22:33:44:55 vin=1G1RD6E45CU" + "112233 " +
                    "{\"lat\":34.01,\"lng\":-118.32}",
            )

        assertFalse(redacted.contains("00:11:22:33:44:55"))
        assertFalse(redacted.contains("1G1RD6E45CU" + "112233"))
        assertFalse(redacted.contains("34.01"))
        assertFalse(redacted.contains("-118.32"))
        assertTrue(redacted.contains("[bluetooth-address-redacted]"))
        assertTrue(redacted.contains("[vin-redacted]"))
        assertTrue(redacted.contains("[coordinate-redacted]"))
    }

    /** Real store whose `PRAGMA quick_check` is stubbed to always report corruption. */
    private class CorruptQuickCheckStore(
        context: Context,
    ) : ObdLocalStore(context) {
        override fun quickCheck(): ObdStoreMaintenance.IntegrityResult =
            ObdStoreMaintenance.IntegrityResult(false, listOf(FAKE_INTEGRITY_PROBLEM))
    }

    companion object {
        private const val FAKE_INTEGRITY_PROBLEM = "row 7 missing from index idx_samples"

        private fun rowCount(
            db: SQLiteDatabase,
            table: String,
        ): Int =
            db.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }

        @Throws(IOException::class)
        private fun tempDbFile(prefix: String): File {
            val file = File.createTempFile(prefix, ".db")
            // SQLiteDatabase.openOrCreateDatabase needs to create the file itself.
            Files.delete(file.toPath())
            return file
        }

        @Throws(IOException::class)
        private fun writeFile(
            file: File,
            contents: String,
        ) {
            val parent = file.parentFile
            parent?.mkdirs()
            FileWriter(file, false).use { writer ->
                writer.write(contents)
            }
        }

        private fun createMinimalVoltSchema(
            db: SQLiteDatabase,
            includeHvPackColumns: Boolean,
            includeForeignKey: Boolean,
        ) {
            db.setVersion(9)
            db.execSQL(
                "CREATE TABLE obd_sessions (" +
                    "_id INTEGER PRIMARY KEY," +
                    "mode TEXT NOT NULL," +
                    "started_at_ms INTEGER NOT NULL," +
                    "status TEXT NOT NULL," +
                    "sample_count INTEGER NOT NULL DEFAULT 0)",
            )
            db.execSQL(
                "CREATE TABLE telemetry_samples (" +
                    "_id INTEGER PRIMARY KEY," +
                    "session_id INTEGER NOT NULL," +
                    "captured_at_ms INTEGER NOT NULL," +
                    (if (includeHvPackColumns) "pack_voltage REAL,pack_current_a REAL," else "") +
                    "json TEXT NOT NULL" +
                    (
                        if (includeForeignKey) {
                            ",FOREIGN KEY(session_id) REFERENCES obd_sessions(_id)"
                        } else {
                            ""
                        }
                    ) +
                    ")",
            )
            db.execSQL(
                "CREATE TABLE status_events (" +
                    "_id INTEGER PRIMARY KEY," +
                    "occurred_at_ms INTEGER NOT NULL," +
                    "kind TEXT NOT NULL," +
                    "payload TEXT NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE adapter_history (" +
                    "adapter_key TEXT PRIMARY KEY," +
                    "last_seen_ms INTEGER NOT NULL," +
                    "last_status TEXT)",
            )
            db.execSQL(
                "CREATE TABLE pid_observations (" +
                    "_id INTEGER PRIMARY KEY," +
                    "session_id INTEGER," +
                    "observed_at_ms INTEGER," +
                    "json TEXT)",
            )
            db.execSQL(
                "CREATE TABLE diagnostic_codes (" +
                    "_id INTEGER PRIMARY KEY," +
                    "dtc TEXT NOT NULL," +
                    "status TEXT NOT NULL," +
                    "last_seen_ms INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE location_samples (" +
                    "_id INTEGER PRIMARY KEY," +
                    "session_id INTEGER NOT NULL," +
                    "captured_at_ms INTEGER NOT NULL," +
                    "latitude REAL NOT NULL," +
                    "longitude REAL NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE vehicles (" +
                    "_id INTEGER PRIMARY KEY," +
                    "vin_hash TEXT," +
                    "vin_redacted TEXT," +
                    "last_seen_ms INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE field_capabilities (" +
                    "_id INTEGER PRIMARY KEY," +
                    "command TEXT NOT NULL," +
                    "first_seen_ms INTEGER NOT NULL," +
                    "last_seen_ms INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE trip_segments (" +
                    "_id INTEGER PRIMARY KEY," +
                    "started_at_ms INTEGER NOT NULL," +
                    "created_at_ms INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE session_trip_rollups (" +
                    "session_id INTEGER PRIMARY KEY," +
                    "counted INTEGER NOT NULL," +
                    "distance_m REAL NOT NULL," +
                    "duration_ms INTEGER NOT NULL," +
                    "started_at_ms INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE charge_sessions (" +
                    "_id INTEGER PRIMARY KEY," +
                    "started_at_ms INTEGER NOT NULL," +
                    "created_at_ms INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE battery_snapshots (" +
                    "_id INTEGER PRIMARY KEY," +
                    "captured_at_ms INTEGER NOT NULL," +
                    "created_at_ms INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE cell_snapshots (" +
                    "_id INTEGER PRIMARY KEY," +
                    "battery_snapshot_id INTEGER NOT NULL," +
                    "cell_index INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE exports (" +
                    "_id INTEGER PRIMARY KEY," +
                    "created_at_ms INTEGER NOT NULL," +
                    "export_type TEXT NOT NULL," +
                    "status TEXT NOT NULL)",
            )
        }
    }
}
