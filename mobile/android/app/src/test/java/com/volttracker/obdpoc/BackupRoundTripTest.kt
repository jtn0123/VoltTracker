package com.volttracker.obdpoc

import android.content.ContentResolver
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.volttracker.obdpoc.data.ObdLocalStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileWriter
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * End-to-end backup/restore round-trip: seed an [ObdLocalStore], export it via
 * [DataBackup.buildBackupFile], hand the file back through [DataBackup.stageRestoreFile] (the SAF
 * URI path that [BackupController] uses in production), swap the staged file into the live DB path,
 * and re-open the store to confirm every seeded row survived.
 *
 * The other tests in this suite cover only the schema sniff in [DataBackup.isVoltTrackerBackup];
 * this one exercises the file IO + content-resolver staging + post-restore reopen that the existing
 * tests miss.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRoundTripTest {
    private lateinit var context: Context
    private lateinit var dataBackup: DataBackup
    private var store: ObdLocalStore? = null

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        dataBackup = DataBackup(context)
        store = ObdLocalStore(context)
        store!!.clearAllData()
    }

    @After
    fun tearDown() {
        store?.let {
            it.close()
            store = null
        }
    }

    // ---- helpers ------------------------------------------------------------

    /**
     * Stages [source] as if the user had picked it from the file picker. [DataBackup.stageRestoreFile]
     * reads from [ContentResolver.openInputStream], so we hand Robolectric a real [FileInputStream]
     * keyed to the URI we return.
     */
    @Throws(IOException::class)
    private fun registerAsSafUri(source: File): Uri {
        val uri = Uri.parse("content://test/" + source.name)
        shadowOf(context.contentResolver)
            .registerInputStream(uri, FileInputStream(source))
        return uri
    }

    private fun registerBytesAsSafUri(
        suffix: String,
        bytes: ByteArray,
    ): Uri {
        val uri = Uri.parse("content://test/$suffix")
        shadowOf(context.contentResolver)
            .registerInputStream(uri, ByteArrayInputStream(bytes))
        return uri
    }

    /**
     * Mirrors the file-swap that [BackupController.applyRestore] performs after staging: close the
     * live store, copy the staged file over the live DB file, drop the WAL/SHM sidecars, then re-open
     * the store. The test calls this instead of going through BackupController so we don't need a
     * MainActivity instance.
     */
    @Throws(IOException::class)
    private fun swapStagedFileIntoLiveDb(staged: File) {
        val dbFile = store!!.getDatabaseFile()
        store!!.close()
        store = null
        DataBackup.copyFile(staged, dbFile)
        DataBackup.deleteIfExists(File(dbFile.path + "-wal"))
        DataBackup.deleteIfExists(File(dbFile.path + "-shm"))
        store = ObdLocalStore(context)
    }

    // ---- tests --------------------------------------------------------------

    /**
     * Happy path: seed one row per high-cardinality table, export, restore, verify every seeded row
     * came back. This is the exact path a user takes when they back up on one install and restore on
     * a fresh install.
     */
    @Test
    fun roundTripPreservesAllSeededRows() {
        // -- seed --
        val sessionId = store!!.startSession("obd", "AA:BB:CC", "Adapter X", 1_000L)
        store!!.recordTelemetry(sessionId, telemetrySample(40, 1500, 32.70, -117.10, 1_100L))
        store!!.recordTelemetry(sessionId, telemetrySample(45, 1550, 32.71, -117.10, 1_200L))
        store!!.recordTelemetry(sessionId, telemetrySample(50, 1600, 32.72, -117.10, 1_300L))
        store!!.recordStatus(sessionId, "ready", "connected", false, JSONObject())
        store!!.recordPidObservation(
            sessionId,
            1_400L,
            "0105",
            "7E0",
            "0105",
            "Engine coolant temp",
            "85",
            85.0,
            "C",
            "0105",
            "41 05 55",
        )
        store!!.recordLocationSample(
            sessionId,
            1_500L,
            "gps",
            32.73,
            -117.10,
            5.0,
            null,
            null,
            null,
            null,
            null,
        )
        store!!.finalizeSession(
            sessionId,
            ObdLocalStore.STATUS_COMPLETE,
            2_000L,
            "0100,0105",
            "AA:BB:CC",
            "Adapter X",
            "obd",
            3,
            "trip end",
        )

        val before = StorageSummaryJson.build(store!!.getStorageSummaryRecord())
        assertEquals(1, before.optInt("sessionCount"))
        assertEquals(3L, before.optLong("sampleCount"))
        assertTrue("seeded status event", before.optLong("eventCount") >= 1L)
        assertEquals(1L, before.optLong("pidObservationCount"))
        assertEquals(1L, before.optLong("locationSampleCount"))

        // -- export --
        val backup = dataBackup.buildBackupFile(store)
        assertNotNull("buildBackupFile should produce a file", backup)
        assertTrue(backup!!.exists())
        assertTrue("backup must be a real Volt Tracker DB", DataBackup.isVoltTrackerBackup(backup))

        // -- stage via SAF URI (the production path) --
        val uri = registerAsSafUri(backup)
        val staged = dataBackup.stageRestoreFile(uri)
        assertNotNull("stageRestoreFile should accept the produced backup", staged)

        // -- swap into the live DB path, reopen --
        swapStagedFileIntoLiveDb(staged!!)
        staged.delete()

        // -- verify every seeded row survived --
        val session = store!!.getSession(sessionId)
        assertNotNull("session row should survive restore", session)
        assertEquals(ObdLocalStore.STATUS_COMPLETE, session!!.status)
        assertEquals(2_000L, session.endedAtMs)
        assertEquals("0100,0105", session.supportedPids)

        val telemetry = store!!.getRecentTelemetry(sessionId, 100)
        assertEquals(
            "all telemetry rows should round-trip through the backup",
            3,
            telemetry.size,
        )

        val events = store!!.getRecentEvents(sessionId, 100)
        assertTrue("status event should round-trip", events.size >= 1)

        val after = StorageSummaryJson.build(store!!.getStorageSummaryRecord())
        assertEquals(1, after.optInt("sessionCount"))
        assertEquals(3L, after.optLong("sampleCount"))
        assertEquals(before.optLong("eventCount"), after.optLong("eventCount"))
        assertEquals(1L, after.optLong("pidObservationCount"))
        assertEquals(1L, after.optLong("locationSampleCount"))
    }

    /**
     * Restore over existing data: production performs a full file replace (not a merge), so the
     * post-restore store must contain only the backup's rows and none of the data that was in the
     * target store before the restore.
     */
    @Test
    fun restoreReplacesExistingDataInTargetStore() {
        // -- seed the source (this is what we'll back up) --
        val sourceSessionId = store!!.startSession("obd", "AA:AA:AA", "Source", 1_000L)
        store!!.recordTelemetry(sourceSessionId, telemetrySample(40, 1500, 32.70, -117.10, 1_100L))
        store!!.finalizeSession(
            sourceSessionId,
            ObdLocalStore.STATUS_COMPLETE,
            2_000L,
            "0100",
            "AA:AA:AA",
            "Source",
            "obd",
            1,
            "source done",
        )

        val backup = dataBackup.buildBackupFile(store)
        assertNotNull(backup)

        // -- now overwrite the live store with completely different data, as if a different
        //    install accumulated its own sessions before the user clicked "restore" --
        store!!.clearAllData()
        val throwawaySessionId = store!!.startSession("obd", "ZZ:ZZ:ZZ", "Other", 5_000L)
        store!!.recordTelemetry(throwawaySessionId, telemetrySample(99, 9999, 0.0, 0.0, 5_100L))
        store!!.recordStatus(throwawaySessionId, "blocked", "before restore", true, JSONObject())
        assertEquals(
            1,
            StorageSummaryJson.build(store!!.getStorageSummaryRecord()).optInt("sessionCount"),
        )

        // -- restore --
        val uri = registerAsSafUri(backup!!)
        val staged = dataBackup.stageRestoreFile(uri)
        assertNotNull(staged)
        swapStagedFileIntoLiveDb(staged!!)
        staged.delete()

        // -- post-restore state: only the source session is present; the throwaway row is gone --
        val restoredSource = store!!.getSession(sourceSessionId)
        assertNotNull("source session must be present after restore", restoredSource)
        assertEquals("AA:AA:AA", restoredSource!!.adapterAddress)

        val throwaway = store!!.getSession(throwawaySessionId)
        assertNull("throwaway session from the pre-restore target store must be wiped", throwaway)

        assertEquals(
            1,
            StorageSummaryJson.build(store!!.getStorageSummaryRecord()).optInt("sessionCount"),
        )
        assertEquals(
            1L,
            StorageSummaryJson.build(store!!.getStorageSummaryRecord()).optLong("sampleCount"),
        )
    }

    @Test
    fun encryptedBackupRequiresPassphraseAndRestoresWithCorrectPassphrase() {
        val sessionId = store!!.startSession("obd", "AA:BB:CC", "Adapter", 1_000L)
        store!!.recordTelemetry(sessionId, telemetrySample(41, 1500, 32.70, -117.10, 1_100L))
        store!!.finalizeSession(
            sessionId,
            ObdLocalStore.STATUS_COMPLETE,
            2_000L,
            "0100",
            "AA:BB:CC",
            "Adapter",
            "obd",
            1,
            "done",
        )

        val encrypted = dataBackup.buildEncryptedBackupFile(store, "correct horse battery")
        assertNotNull(encrypted)
        assertTrue(encrypted!!.exists())
        assertTrue(DataBackup.isEncryptedBackup(encrypted))
        assertFalse(
            "encrypted backup must not be readable as plaintext SQLite",
            DataBackup.isVoltTrackerBackup(encrypted),
        )

        val wrongUri = registerAsSafUri(encrypted)
        assertNull(dataBackup.stageRestoreFile(wrongUri, "wrong passphrase"))

        val correctUri = registerAsSafUri(encrypted)
        val staged = dataBackup.stageRestoreFile(correctUri, "correct horse battery")
        assertNotNull("encrypted restore should stage with the right passphrase", staged)
        assertTrue(DataBackup.isVoltTrackerBackup(staged))

        swapStagedFileIntoLiveDb(staged!!)
        staged.delete()
        assertNotNull(store!!.getSession(sessionId))
        assertEquals(
            1L,
            StorageSummaryJson.build(store!!.getStorageSummaryRecord()).optLong("sampleCount"),
        )
    }

    /**
     * A non-SQLite file passed to [DataBackup.stageRestoreFile] must be rejected (return null) and
     * must not touch the live store. We seed the target store first and verify it is untouched after
     * the rejected attempt — a regression here would mean a corrupt user file silently trashes their
     * data.
     */
    @Test
    fun restoreOfInvalidFileIsRejectedAndLeavesTargetStoreIntact() {
        val sessionId = store!!.startSession("obd", "AA:BB:CC", "Adapter", 1_000L)
        store!!.recordTelemetry(sessionId, telemetrySample(40, 1500, 32.70, -117.10, 1_100L))
        val beforeSamples =
            StorageSummaryJson.build(store!!.getStorageSummaryRecord()).optLong("sampleCount")
        val beforeSessions =
            StorageSummaryJson.build(store!!.getStorageSummaryRecord()).optInt("sessionCount")

        val uri =
            registerBytesAsSafUri(
                "junk.bin",
                "not a database, just plain text".toByteArray(StandardCharsets.UTF_8),
            )
        val staged = dataBackup.stageRestoreFile(uri)

        assertNull("stageRestoreFile must reject a non-SQLite file", staged)
        // stageRestoreFile cleans up its temp file when it rejects.
        assertFalse(File(context.cacheDir, "restore-tmp.db").exists())

        // The live store is untouched.
        assertEquals(
            beforeSessions,
            StorageSummaryJson.build(store!!.getStorageSummaryRecord()).optInt("sessionCount"),
        )
        assertEquals(
            beforeSamples,
            StorageSummaryJson.build(store!!.getStorageSummaryRecord()).optLong("sampleCount"),
        )
        assertNotNull(store!!.getSession(sessionId))
    }

    /**
     * A SQLite file with a foreign schema must also be rejected — the schema sniff is the only thing
     * that prevents another app's database (or a stale, pre-migration DB from a different tool) from
     * being copied over the live file and breaking every query the app makes.
     */
    @Test
    fun restoreOfForeignSchemaIsRejected() {
        // Build a real SQLite file with a wrong schema.
        val foreign = tempDbFile("foreign-schema")
        val db = SQLiteDatabase.openOrCreateDatabase(foreign, null)
        try {
            db.execSQL("CREATE TABLE wrong (id INTEGER PRIMARY KEY, value TEXT)")
            db.execSQL("INSERT INTO wrong (value) VALUES ('not us')")
        } finally {
            db.close()
        }

        // The low-level sniff rejects it directly.
        assertFalse(
            "isVoltTrackerBackup must reject a SQLite file with foreign tables",
            DataBackup.isVoltTrackerBackup(foreign),
        )

        // And the SAF path rejects it too, since stageRestoreFile pipes through the same sniff.
        val uri = registerAsSafUri(foreign)
        val staged = dataBackup.stageRestoreFile(uri)
        assertNull("stageRestoreFile must reject a foreign-schema SQLite file", staged)
        assertFalse(File(context.cacheDir, "restore-tmp.db").exists())

        foreign.delete()
    }

    /**
     * Verifies the file path used by [DataBackup.buildBackupFile] lives under the cache directory so
     * the `FileProvider` share-sheet hand-off can grant a content URI for it.
     */
    @Test
    fun buildBackupFileWritesToCacheDir() {
        store!!.startSession("obd", "AA:BB:CC", "Adapter", 1_000L)

        val backup = dataBackup.buildBackupFile(store)
        assertNotNull(backup)
        assertTrue(backup!!.exists())
        assertTrue(
            "backup must live under the app cache dir so it's eligible for the file provider",
            backup.absolutePath.startsWith(context.cacheDir.absolutePath),
        )
    }

    /**
     * A [DataBackup.stageRestoreFile] call with a URI that has no registered stream returns null —
     * the SAF handoff can fail (file picked from a now-revoked URI permission) and the stager must
     * surface that as a rejected restore, not a crash.
     */
    @Test
    fun stageRestoreFileReturnsNullForUnreadableUri() {
        val uri = Uri.parse("content://test/missing.db")
        // Intentionally do NOT register a stream for this URI.
        val staged = dataBackup.stageRestoreFile(uri)
        assertNull(staged)
        assertFalse(File(context.cacheDir, "restore-tmp.db").exists())
    }

    @Test
    fun buildBackupClearsPreviousBackupButLeavesUnrelatedFiles() {
        // buildBackupFile must drop only its own previous output (`volttracker-backup-*.db`).
        // Anything else dropped into the same dir by other code (or by a future feature) has to
        // survive — without this filter we'd silently wipe unrelated caches every backup.
        val backupsDir = File(context.cacheDir, "backups")
        assertTrue(backupsDir.mkdirs() || backupsDir.isDirectory)
        val priorBackup = File(backupsDir, "volttracker-backup-19700101-000000.db")
        FileWriter(priorBackup).use { writer ->
            writer.write("stale-backup")
        }
        val unrelated = File(backupsDir, "leftover.db")
        FileWriter(unrelated).use { writer ->
            writer.write("not-a-backup")
        }
        assertTrue(priorBackup.exists())
        assertTrue(unrelated.exists())

        store!!.startSession("obd", "AA:BB:CC", "Adapter", 1_000L)
        val backup = dataBackup.buildBackupFile(store)
        assertNotNull(backup)
        assertFalse("prior backup should be cleared", priorBackup.exists())
        assertTrue("unrelated file should NOT be cleared", unrelated.exists())
    }

    companion object {
        @Throws(Exception::class)
        private fun telemetrySample(
            speedKph: Int,
            rpm: Int,
            lat: Double,
            lng: Double,
            atMs: Long,
        ): JSONObject {
            val sample = JSONObject()
            sample.put("source", "obd")
            sample.put("speedKph", speedKph)
            sample.put("rpm", rpm)
            sample.put("latitude", lat)
            sample.put("longitude", lng)
            sample.put("updatedAt", atMs)
            return sample
        }

        @Throws(IOException::class)
        private fun tempDbFile(prefix: String): File {
            val file = File.createTempFile(prefix, ".db")
            // SQLiteDatabase.openOrCreateDatabase needs to create the file itself.
            Files.delete(file.toPath())
            return file
        }
    }
}
