package com.volttracker.obdpoc

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.volttracker.obdpoc.data.ObdLocalStore
import com.volttracker.obdpoc.data.ObdStoreMaintenance
import com.volttracker.obdpoc.data.VoltTrackerDb
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
    fun encryptedBackupPassphraseCountsUserPerceivedCharacters() {
        assertFalse(
            "four emoji are eight UTF-16 units but only four user-perceived characters",
            DataBackup.hasMinimumPassphrase("🙂🙂🙂🙂"),
        )
        assertTrue(DataBackup.hasMinimumPassphrase("  🙂🙂🙂🙂🙂🙂🙂🙂  "))
    }

    @Test
    fun redactDebugLogTextTreatsNullAndEmptyAsEmptyText() {
        assertEquals("", DataBackup.redactDebugLogText(null))
        assertEquals("", DataBackup.redactDebugLogText(""))
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
    fun backupBuildersRefuseToCopyWhenWalCheckpointCannotFinish() {
        val context = RuntimeEnvironment.getApplication()
        val store = BusyCheckpointStore(context)
        try {
            store.clearAllData()

            assertNull(
                "a backup must fail rather than silently omit uncheckpointed WAL rows",
                DataBackup(context).buildBackupFile(store),
            )
            assertNull(
                "an encrypted backup must fail rather than silently omit uncheckpointed WAL rows",
                DataBackup(context).buildEncryptedBackupFile(store, "hunter22"),
            )
        } finally {
            store.close()
        }
    }

    @Test
    fun backupBuildersReturnNullForNullStoreOrUnavailableBackupDir() {
        val context = RuntimeEnvironment.getApplication()
        val backup = DataBackup(context)
        assertNull(backup.buildBackupFile(null))
        assertNull(backup.buildEncryptedBackupFile(null, "hunter22"))

        val store = ObdLocalStore(context)
        try {
            store.clearAllData()
            assertNull(backup.buildEncryptedBackupFile(store, "short"))

            val cacheRoot = File(context.cacheDir, "backup-cache-root-file").apply { writeText("not a directory") }
            val badBackup = DataBackup(contextWithCacheDir(cacheRoot))

            assertNull(badBackup.buildBackupFile(store))
            assertNull(badBackup.buildEncryptedBackupFile(store, "hunter22"))
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
        val old = System.currentTimeMillis() - 2L * 60L * 60L * 1000L
        assertTrue(plaintext.setLastModified(old))
        assertTrue(encrypted.setLastModified(old))

        DataBackup(context).sweepTransientCacheFiles()

        assertFalse(plaintext.exists())
        assertFalse(encrypted.exists())
        assertTrue(unrelated.exists())
        unrelated.delete()
    }

    @Test
    fun sweepClearsTransientDebugExportsFromPriorRuns() {
        val context = RuntimeEnvironment.getApplication()
        val exports = File(context.cacheDir, "exports")
        assertTrue(exports.mkdirs() || exports.isDirectory)
        val staleExport = File(exports, "volttracker-debug-summary-stale.json")
        val unrelated = File(exports, "keep.txt")
        writeFile(staleExport, "{}")
        writeFile(unrelated, "keep me")

        DataBackup(context).sweepTransientCacheFiles()

        assertFalse(staleExport.exists())
        assertTrue(unrelated.exists())
        unrelated.delete()
    }

    @Test
    fun sweepKeepsRecentBackupShareFiles() {
        val context = RuntimeEnvironment.getApplication()
        val backups = File(context.cacheDir, "backups")
        assertTrue(backups.mkdirs() || backups.isDirectory)
        val recent = File(backups, "volttracker-backup-recent.db")
        writeFile(recent, "pending share")
        assertTrue(recent.setLastModified(System.currentTimeMillis()))

        DataBackup(context).sweepTransientCacheFiles()

        assertTrue("recent share target should not be deleted", recent.exists())
        recent.delete()
    }

    @Test
    fun repeatedBackupBuildsKeepDistinctRecentFiles() {
        val context = RuntimeEnvironment.getApplication()
        val store = ObdLocalStore(context)
        try {
            store.clearAllData()
            val backup = DataBackup(context)
            val first = backup.buildBackupFile(store)
            val second = backup.buildBackupFile(store)

            assertNotNull(first)
            assertNotNull(second)
            assertTrue(first!!.exists())
            assertTrue(second!!.exists())
            assertTrue("backup filenames should be unique", first.name != second.name)
            first.delete()
            second.delete()
        } finally {
            store.close()
        }
    }

    @Test
    fun restoreImportLimitAllowsLargeMultiHundredMbBackups() {
        val twoHundredMb = 200L * 1000L * 1000L
        // Histories approaching ~1 GB (many logged drives) must import — the cap was raised from
        // 512 MiB to 4 GiB. Pin a >= 1 GiB floor so it can't silently regress below that need.
        val oneGib = 1024L * 1024L * 1024L

        assertTrue(
            "restore importer must accept 200 MB-class backups before SQLite validation",
            DataBackup.MAX_RESTORE_BYTES > twoHundredMb,
        )
        assertTrue(
            "restore importer must accept ~1 GB-class backups",
            DataBackup.MAX_RESTORE_BYTES >= oneGib,
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
        val skippedDirectory = File(sessionLogDir, "session-directory.jsonl")
        val appLogFile = File(appLogDir, "app.log")
        assertTrue(skippedDirectory.mkdir())
        val sessionLatitude = "34." + "052345"
        val sessionLongitude = "-118." + "252233"
        writeFile(
            sessionLog,
            "{\"type\":\"command\",\"payload\":{\"command\":\"010C\",\"response\":\"410C1AF8\"," +
                "\"adapter\":\"AA:BB:CC:DD:EE:FF\",\"vin\":\"1G1RD6E45CU" + "112233\"," +
                "\"latitude\":$sessionLatitude,\"longitude\":$sessionLongitude}}\n",
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
        assertEquals(file.name, result.optString("filename"))
        assertTrue(result.optString("content").contains("\"diagnostics\""))
        val exported =
            JSONObject(
                String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8),
            )
        assertNotNull(exported.optJSONObject("appState"))
        assertNotNull(exported.optJSONObject("storage"))
        val diagnostics = exported.getJSONObject("diagnostics")
        val sessionTraceArray = diagnostics.getJSONArray("sessionCommandTrace")
        assertEquals(
            "directories that match the session filename pattern must be skipped",
            1,
            sessionTraceArray.length(),
        )
        val sessionTrace = sessionTraceArray.getJSONObject(0)
        assertEquals("session-1000-obd.jsonl", sessionTrace.optString("name"))
        assertTrue(sessionTrace.optString("text").contains("\"command\":\"010C\""))
        assertFalse(sessionTrace.optString("text").contains("AA:BB:CC:DD:EE:FF"))
        assertFalse(sessionTrace.optString("text").contains("1G1RD6E45CU" + "112233"))
        assertFalse(sessionTrace.optString("text").contains(sessionLatitude))
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
    fun debugExportRealignsTailWhenCutStartsInsideUtf8Character() {
        val context = RuntimeEnvironment.getApplication()
        val sessionLogDir = File(context.filesDir, "obd-logs")
        assertTrue(sessionLogDir.mkdirs() || sessionLogDir.isDirectory)
        val sessionLog = File(sessionLogDir, "session-utf8-tail-obd.jsonl")
        val maxTailBytes = 64 * 1024
        val prefix =
            byteArrayOf(0xE2.toByte(), 0x82.toByte(), 0xAC.toByte()) +
                "partial-line\nkept-line\n".toByteArray(StandardCharsets.UTF_8)
        val bytes = prefix + ByteArray(maxTailBytes + 1 - prefix.size) { 'x'.code.toByte() }
        Files.write(sessionLog.toPath(), bytes)

        val result =
            JSONObject(
                DataBackup(context).exportDebugBundle(
                    "{\"session\":{\"state\":\"idle\"}}",
                    "{\"sampleCount\":0}",
                ),
            )

        assertTrue(result.optBoolean("ok"))
        val file = File(result.getString("path"))
        val exported =
            JSONObject(
                String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8),
            )
        val text =
            exported
                .getJSONObject("diagnostics")
                .getJSONArray("sessionCommandTrace")
                .getJSONObject(0)
                .optString("text")
        assertTrue(
            "tail should resume at the first full line after the split UTF-8 character",
            text.startsWith("kept-line"),
        )
        assertFalse("tail text should not contain a replacement character", text.contains("\uFFFD"))
        file.delete()
        sessionLog.delete()
    }

    @Test
    fun debugExportTailsLargeLogsFromALineBoundaryBeforeRedaction() {
        val context = RuntimeEnvironment.getApplication()
        val sessionLogDir = File(context.filesDir, "obd-logs")
        assertTrue(sessionLogDir.mkdirs() || sessionLogDir.isDirectory)
        val sessionLog = File(sessionLogDir, "session-2000-obd.jsonl")
        val keptLine = "{\"type\":\"command\",\"payload\":{\"command\":\"010D\",\"response\":\"410D28\"}}\n"
        writeFile(
            sessionLog,
            "A".repeat(70 * 1024) +
                " 00:11:22:33:44:55 1G1RD6E45CU" + "112233\n" +
                keptLine,
        )

        val result =
            JSONObject(
                DataBackup(context).exportDebugBundle(
                    "{\"session\":{\"state\":\"idle\"}}",
                    "{\"sampleCount\":0}",
                ),
            )

        assertTrue(result.optBoolean("ok"))
        val file = File(result.getString("path"))
        val exported =
            JSONObject(
                String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8),
            )
        val text =
            exported
                .getJSONObject("diagnostics")
                .getJSONArray("sessionCommandTrace")
                .getJSONObject(0)
                .optString("text")
        assertTrue(
            "tail export should drop the partial first line before redaction, got: $text",
            text.startsWith("{\"type\":\"command\""),
        )
        assertFalse(text.contains("00:11:22:33:44:55"))
        assertFalse(text.contains("1G1RD6E45CU" + "112233"))
        file.delete()
        sessionLog.delete()
    }

    @Test
    fun debugExportReportsExceptionsAsJsonErrors() {
        val brokenContext =
            object : ContextWrapper(RuntimeEnvironment.getApplication()) {
                override fun getCacheDir(): File = throw IllegalStateException("cache unavailable")
            }

        val result =
            JSONObject(
                DataBackup(brokenContext).exportDebugBundle(
                    "{\"session\":{\"state\":\"idle\"}}",
                    "{\"sampleCount\":0}",
                ),
            )

        assertFalse(result.optBoolean("ok", true))
        assertTrue(result.optString("error").contains("IllegalStateException: cache unavailable"))
    }

    @Test
    fun debugExportReportsErrorWhenExportDirCannotBeCreated() {
        val context = RuntimeEnvironment.getApplication()
        val cacheRoot = File(context.cacheDir, "debug-export-cache-root-file").apply { writeText("not a directory") }

        val result =
            JSONObject(
                DataBackup(contextWithCacheDir(cacheRoot)).exportDebugBundle(
                    "{\"session\":{\"state\":\"idle\"}}",
                    "{\"sampleCount\":0}",
                ),
            )

        assertFalse(result.optBoolean("ok", true))
        assertEquals("Could not create export directory.", result.optString("error"))
    }

    @Test
    fun stageRestoreFileWithStatusReturnsNoFileForNullUri() {
        val outcome = DataBackup(RuntimeEnvironment.getApplication()).stageRestoreFileWithStatus(null, null)

        assertFalse(outcome.ok)
        assertNull(outcome.file)
        assertEquals(DataBackup.RestoreStageStatus.NO_FILE, outcome.status)
    }

    @Test
    fun stageRestoreFileWithStatusReportsOpenFailedWhenUriCannotBeRead() {
        val context = RuntimeEnvironment.getApplication()
        val missing = File(context.cacheDir, "missing-restore-source.db")
        assertFalse(missing.exists())

        val outcome = DataBackup(context).stageRestoreFileWithStatus(Uri.fromFile(missing), null)

        assertFalse(outcome.ok)
        assertNull(outcome.file)
        assertEquals(DataBackup.RestoreStageStatus.OPEN_FAILED, outcome.status)
        assertEquals(0L, outcome.bytesRead)
    }

    @Test
    fun stageRestoreFileWithStatusAcceptsPlainCurrentBackupAndClearsRollups() {
        val context = RuntimeEnvironment.getApplication()
        val backupFile = currentBackupCopy(context, "plain-restore-current.db", seedRollup = true)
        val snapshots = ArrayList<DataBackup.ProgressSnapshot>()

        val outcome =
            DataBackup(context).stageRestoreFileWithStatus(Uri.fromFile(backupFile), null) {
                snapshots.add(it)
            }

        assertTrue(outcome.ok)
        assertNotNull(outcome.file)
        assertEquals(DataBackup.RestoreStageStatus.OK, outcome.status)
        assertFalse(outcome.encrypted)
        assertFalse(outcome.migrated)
        assertTrue("bytesRead should report the copied backup size", outcome.bytesRead > 0L)
        assertTrue(snapshots.any { it.phase == "Reading backup" && it.bytesDone == 0L })
        assertTrue(snapshots.any { it.phase == "Checking backup" })
        assertTrue(snapshots.any { it.phase == "Preparing restore" })
        assertTrue(snapshots.any { it.phase == "Backup verified" })

        val staged = outcome.file!!
        val verify = SQLiteDatabase.openDatabase(staged.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            assertEquals(0, rowCount(verify, "session_trip_rollups"))
            assertEquals(1, rowCount(verify, "obd_sessions"))
        } finally {
            verify.close()
            staged.delete()
            backupFile.delete()
        }
    }

    @Test
    fun stageRestoreFileWithStatusRejectsPlainTextAsNotBackup() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, "not-a-restore-db.txt")
        writeFile(file, "not a sqlite backup")

        val outcome = DataBackup(context).stageRestoreFileWithStatus(Uri.fromFile(file), null)

        assertFalse(outcome.ok)
        assertNull(outcome.file)
        assertEquals(DataBackup.RestoreStageStatus.NOT_A_BACKUP, outcome.status)
        assertFalse(outcome.encrypted)
        assertEquals(file.length(), outcome.bytesRead)
        file.delete()
    }

    @Test
    fun stageRestoreFileWithStatusRejectsNewerSchemaBackup() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, "too-new-restore.db")
        file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file.path, null)
        try {
            db.execSQL("CREATE TABLE obd_sessions (_id INTEGER PRIMARY KEY)")
            db.version = VoltTrackerDb.DATABASE_VERSION + 1
        } finally {
            db.close()
        }

        val outcome = DataBackup(context).stageRestoreFileWithStatus(Uri.fromFile(file), null)

        assertFalse(outcome.ok)
        assertNull(outcome.file)
        assertEquals(DataBackup.RestoreStageStatus.TOO_NEW, outcome.status)
        assertFalse(outcome.encrypted)
        assertEquals(file.length(), outcome.bytesRead)
        file.delete()
    }

    @Test
    fun stageRestoreEncryptedBackupRequiresPassphraseThenDecryptsShortLegacyPassphrase() {
        val context = RuntimeEnvironment.getApplication()
        val source = currentBackupCopy(context, "encrypted-restore-source.db")
        val encrypted = File(context.cacheDir, "encrypted-restore.vtdb")
        BackupCrypto.encryptFile(source, encrypted, "short")

        val missing = DataBackup(context).stageRestoreFileWithStatus(Uri.fromFile(encrypted), null)
        assertFalse(missing.ok)
        assertNull(missing.file)
        assertEquals(DataBackup.RestoreStageStatus.MISSING_PASSPHRASE, missing.status)
        assertTrue(missing.encrypted)
        assertEquals(encrypted.length(), missing.bytesRead)

        val restored = DataBackup(context).stageRestoreFileWithStatus(Uri.fromFile(encrypted), "short")

        assertTrue(restored.ok)
        assertNotNull(restored.file)
        assertEquals(DataBackup.RestoreStageStatus.OK, restored.status)
        assertTrue(restored.encrypted)
        assertTrue(DataBackup.isVoltTrackerBackup(restored.file))
        val snapshots = ArrayList<DataBackup.ProgressSnapshot>()
        val restoredWithProgress =
            DataBackup(context).stageRestoreFileWithStatus(Uri.fromFile(encrypted), "short") {
                snapshots.add(it)
            }
        assertTrue(restoredWithProgress.ok)
        assertTrue(snapshots.any { it.phase == "Decrypting backup" && it.bytesDone == 0L })
        assertTrue(
            snapshots.any {
                it.phase == "Decrypting backup" &&
                    it.detail == "Encrypted backup unlocked." &&
                    it.bytesDone == encrypted.length()
            },
        )
        restored.file!!.delete()
        restoredWithProgress.file!!.delete()
        encrypted.delete()
        source.delete()
    }

    @Test
    fun stageRestoreEncryptedBackupRejectsWrongPassphrase() {
        val context = RuntimeEnvironment.getApplication()
        val source = currentBackupCopy(context, "encrypted-wrong-pass-source.db")
        val encrypted = File(context.cacheDir, "encrypted-wrong-pass.vtdb")
        BackupCrypto.encryptFile(source, encrypted, "correct-pass")
        val snapshots = ArrayList<DataBackup.ProgressSnapshot>()

        val outcome =
            DataBackup(context).stageRestoreFileWithStatus(Uri.fromFile(encrypted), "wrong-pass") {
                snapshots.add(it)
            }

        assertFalse(outcome.ok)
        assertNull(outcome.file)
        assertEquals(DataBackup.RestoreStageStatus.DECRYPT_FAILED, outcome.status)
        assertTrue(outcome.encrypted)
        assertEquals(encrypted.length(), outcome.bytesRead)
        assertTrue(snapshots.any { it.phase == "Decrypting backup" && it.bytesDone == 0L })
        encrypted.delete()
        source.delete()
    }

    @Test
    fun copyFileEmitsProgressSnapshotsAndCopiesBytes() {
        val context = RuntimeEnvironment.getApplication()
        val source = File(context.cacheDir, "copy-source.txt")
        val dest = File(context.cacheDir, "copy-dest.txt")
        writeFile(source, "abcdef")
        val snapshots = ArrayList<DataBackup.ProgressSnapshot>()

        DataBackup.copyFile(
            source,
            dest,
            { snapshots.add(it) },
            phase = "Copying test",
            detail = "Copying fixture bytes.",
        )

        assertEquals("abcdef", dest.readText())
        assertEquals(0L, snapshots.first().bytesDone)
        assertEquals(source.length(), snapshots.last().bytesDone)
        assertTrue(snapshots.all { it.phase == "Copying test" })
        source.delete()
        dest.delete()
    }

    @Test
    fun copyFileStopsAtTheNextChunkWhenInterrupted() {
        val context = RuntimeEnvironment.getApplication()
        val source = File(context.cacheDir, "copy-interrupt-source.bin")
        val dest = File(context.cacheDir, "copy-interrupt-dest.bin")
        source.outputStream().use { out ->
            val block = ByteArray(64 * 1024) { 0x5A }
            repeat(32) { out.write(block) }
        }
        try {
            var interrupted = false
            try {
                DataBackup.copyFile(
                    source,
                    dest,
                    { snapshot ->
                        if (snapshot.bytesDone > 0L) Thread.currentThread().interrupt()
                    },
                )
            } catch (_: InterruptedIOException) {
                interrupted = true
            }

            assertTrue("copy should surface interruption", interrupted)
            assertTrue("interrupted copy must stop before the complete source", dest.length() < source.length())
        } finally {
            Thread.interrupted()
            source.delete()
            dest.delete()
        }
    }

    @Test
    fun backupBuildersReturnNullWhenDatabaseFileIsMissingOrUnreadable() {
        val context = RuntimeEnvironment.getApplication()
        val backup = DataBackup(context)
        val missing = MissingDatabaseFileStore(context)
        val unreadable = DirectoryDatabaseFileStore(context)
        try {
            assertNull(backup.buildBackupFile(missing))
            assertNull(backup.buildEncryptedBackupFile(missing, "hunter22"))
            assertNull(backup.buildBackupFile(unreadable))
            assertNull(backup.buildEncryptedBackupFile(unreadable, "hunter22"))
        } finally {
            missing.close()
            unreadable.close()
            unreadable.fakeDatabaseFile.delete()
        }
    }

    @Test
    fun renameFileMovesFileToExistingDestination() {
        val context = RuntimeEnvironment.getApplication()
        val source = File(context.cacheDir, "rename-source-ok.db")
        val dest = File(context.cacheDir, "rename-dest-ok.db")
        writeFile(source, "backup")
        dest.delete()

        DataBackup.renameFile(source, dest)

        assertFalse(source.exists())
        assertTrue(dest.exists())
        assertEquals("backup", dest.readText())
        dest.delete()
    }

    @Test
    fun renameFileThrowsWhenDestinationParentIsMissing() {
        val context = RuntimeEnvironment.getApplication()
        val source = File(context.cacheDir, "rename-source.db")
        val dest = File(File(context.cacheDir, "missing-parent-${System.nanoTime()}"), "dest.db")
        writeFile(source, "backup")

        try {
            DataBackup.renameFile(source, dest)
            fail("renameFile should throw when the destination parent does not exist")
        } catch (ex: IOException) {
            assertTrue(ex.message!!.contains("Could not move"))
        }

        assertTrue(source.exists())
        assertFalse(dest.exists())
        source.delete()
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

    @Test
    fun redactDebugLogTextRedactsQuotedCoordinatesAndCoordinateArrays() {
        val arrayLongitude = "-118." + "252233"
        val arrayLatitude = "34." + "052345"
        val redacted =
            DataBackup.redactDebugLogText(
                "{\"lat\":\"34.01\",\"lng\":\"-118.32\",\"coordinates\":[$arrayLongitude,$arrayLatitude]}",
            )

        assertFalse(redacted.contains("34.01"))
        assertFalse(redacted.contains("-118.32"))
        assertFalse(redacted.contains(arrayLongitude))
        assertFalse(redacted.contains(arrayLatitude))
        assertTrue(redacted.contains("[coordinate-redacted]"))
    }

    @Test
    fun companionFileHelpersHandleNullMissingAndExistingFiles() {
        val context = RuntimeEnvironment.getApplication()
        val existing = File(context.cacheDir, "delete-existing.tmp")
        val missing = File(context.cacheDir, "delete-missing.tmp")
        writeFile(existing, "delete me")
        missing.delete()

        assertFalse(DataBackup.isEncryptedBackup(null))
        assertFalse(DataBackup.isVoltTrackerBackup(null))
        DataBackup.clearRegenerableRollupCache(null)
        DataBackup.deleteIfExists(null)
        DataBackup.deleteIfExists(missing)
        DataBackup.deleteIfExists(existing)

        assertFalse(existing.exists())
        assertFalse(missing.exists())
    }

    /** Real store whose `PRAGMA quick_check` is stubbed to always report corruption. */
    private class CorruptQuickCheckStore(
        context: Context,
    ) : ObdLocalStore(context) {
        override fun quickCheck(): ObdStoreMaintenance.IntegrityResult =
            ObdStoreMaintenance.IntegrityResult(false, listOf(FAKE_INTEGRITY_PROBLEM))
    }

    private class MissingDatabaseFileStore(
        private val appContext: Context,
    ) : ObdLocalStore(appContext) {
        // Do not create or checkpoint the real DB; this fake is for backup-file absence.
        override fun checkpoint(): Boolean = true

        override fun quickCheck(): ObdStoreMaintenance.IntegrityResult =
            ObdStoreMaintenance.IntegrityResult(true, emptyList())

        override fun getDatabaseFile(): File = File(appContext.cacheDir, "missing-db-${System.nanoTime()}.db")
    }

    private class DirectoryDatabaseFileStore(
        appContext: Context,
    ) : ObdLocalStore(appContext) {
        val fakeDatabaseFile: File = File(appContext.cacheDir, "directory-db-${System.nanoTime()}.db")

        // The unreadable path is a directory, so copying/encrypting it must fail cleanly.
        override fun checkpoint(): Boolean = true

        override fun quickCheck(): ObdStoreMaintenance.IntegrityResult =
            ObdStoreMaintenance.IntegrityResult(true, emptyList())

        override fun getDatabaseFile(): File {
            fakeDatabaseFile.mkdirs()
            return fakeDatabaseFile
        }
    }

    /** Store fake for a busy WAL checkpoint that leaves committed frames outside the main file. */
    private class BusyCheckpointStore(
        appContext: Context,
    ) : ObdLocalStore(appContext) {
        override fun checkpoint(): Boolean = false
    }

    companion object {
        private const val FAKE_INTEGRITY_PROBLEM = "row 7 missing from index idx_samples"

        private fun contextWithCacheDir(cacheDir: File): Context =
            object : ContextWrapper(RuntimeEnvironment.getApplication()) {
                override fun getCacheDir(): File = cacheDir
            }

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

        @Throws(IOException::class)
        private fun currentBackupCopy(
            context: Context,
            fileName: String,
            seedRollup: Boolean = false,
        ): File {
            val store = ObdLocalStore(context)
            val source: File
            var sessionId = 0L
            try {
                store.clearAllData()
                sessionId = store.startSession("obd", "AA:BB", "Adapter")
                store.finishSession(sessionId, ObdLocalStore.STATUS_COMPLETE, 2_000L, "")
                store.checkpoint()
                source = store.getDatabaseFile()
            } finally {
                store.close()
            }
            if (seedRollup) {
                val db = SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READWRITE)
                try {
                    db.execSQL(
                        "INSERT OR REPLACE INTO session_trip_rollups" +
                            " (session_id, counted, distance_m, duration_ms, started_at_ms)" +
                            " VALUES ($sessionId, 1, 100.0, 1000, 1)",
                    )
                } finally {
                    db.close()
                }
            }
            val copy = File(context.cacheDir, fileName)
            Files.copy(source.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return copy
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
