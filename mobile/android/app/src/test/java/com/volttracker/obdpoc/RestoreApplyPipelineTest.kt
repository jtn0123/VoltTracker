package com.volttracker.obdpoc

import android.os.Bundle
import com.volttracker.obdpoc.data.DatabaseCorruption
import com.volttracker.obdpoc.data.ObdLocalStore
import com.volttracker.obdpoc.data.VoltTrackerDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile

/**
 * Exercises [RestoreApplyPipeline] directly (the [BackupControllerDialogTest] suite drives the
 * same code through the Replace / Merge dialog): the replace file-swap, the merge fold-in, the
 * lease / logging-active / missing-store fail-closed branches, cancellation, the rollback when the
 * restored file fails its first open or quick check, and the safety-copy preservation invariant
 * behind the replace rollback ladder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreApplyPipelineTest {
    private lateinit var controller: ActivityController<HarnessActivity>
    private lateinit var activity: HarnessActivity
    private lateinit var pipeline: RestoreApplyPipeline<HarnessActivity>
    private lateinit var presenter: BackupRestoreProgressPresenter

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(HarnessActivity::class.java).create()
        activity = controller.get()
        activity.localStore!!.clearAllData()
        pipeline = RestoreApplyPipeline(activity) { false }
        presenter = BackupRestoreProgressPresenter(activity) { false }
    }

    @After
    fun tearDown() {
        try {
            controller.destroy()
        } catch (ignored: RuntimeException) {
            // Store teardown can race Robolectric background work; not part of these assertions.
        }
    }

    /** Seeds one finished session, exports it as a staged plain-DB file, then clears the live store. */
    private fun stagedBackupOfOneClearedSession(): File {
        val store = activity.localStore!!
        val id = store.startSession("obd", "AA:BB", "Adapter")
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, 4000L, "")
        val dataBackup = DataBackup(activity)
        val staged = dataBackup.buildBackupFile(store)
        assertNotNull("buildBackupFile should produce a backup", staged)
        dataBackup.removeSettingsManifest(staged!!)
        store.clearAllData()
        return staged
    }

    private fun emitter(): BackupRestoreProgressPresenter.Emitter = presenter.emitter("title", "detail", "restore")

    @Test
    fun applyReplaceSwapsTheStagedDatabaseIntoTheLivePathAndCleansUpEveryStagingFile() {
        val staged = stagedBackupOfOneClearedSession()
        val dbPath = activity.localStore!!.getDatabaseFile().path

        val result = pipeline.applyReplace(staged, emitter())

        assertEquals(RestoreApplyPipeline.Result.OK, result)
        assertNotNull("replace must reopen the live store", activity.localStore)
        assertEquals(1, activity.localStore!!.getRecentSessions(10).size)
        assertFalse("staged file must be consumed", staged.exists())
        assertFalse("staging temp must not survive", File("$dbPath.restore-new").exists())
        assertFalse("safety copy must be dropped after success", File("$dbPath.restore-backup").exists())
    }

    @Test
    fun replaceWaitsForPersistenceEvenAfterLoggingStops() {
        val staged = stagedBackupOfOneClearedSession()
        val liveStore = activity.localStore
        val owner = DatabaseOperationLease.tryRegisterPersistence() ?: error("owner expected")
        val executor =
            java.util.concurrent.Executors
                .newSingleThreadExecutor()
        try {
            val result = executor.submit<RestoreApplyPipeline.Result> { pipeline.applyReplace(staged, emitter()) }
            val deadline = System.currentTimeMillis() + 5_000L
            while (!DatabaseOperationLease.isHeld() && System.currentTimeMillis() < deadline) Thread.yield()
            assertTrue(DatabaseOperationLease.isHeld())
            assertFalse(activity.isLoggingActive())
            assertFalse("replace must wait for the old writer", result.isDone)
            assertTrue("live handle is not closed while a writer owns it", activity.localStore === liveStore)
            assertTrue(staged.exists())
            assertTrue(
                "new persistence is refused during restore",
                DatabaseOperationLease.tryRegisterPersistence() == null,
            )
            owner.close()
            assertEquals(RestoreApplyPipeline.Result.OK, result.get(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            owner.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun applyMergeFoldsTheStagedSessionsBackIntoTheLiveStore() {
        val staged = stagedBackupOfOneClearedSession()

        val outcome = pipeline.applyMerge(staged, emitter())

        assertEquals(RestoreApplyPipeline.Result.OK, outcome.result)
        assertNotNull("merge summary should describe the folded-in rows", outcome.message)
        assertEquals(1, activity.localStore!!.getRecentSessions(10).size)
        assertFalse("staged file must be consumed", staged.exists())
    }

    @Test
    fun applyReplaceFailsClosedWhileAnotherDatabaseOperationHoldsTheLease() {
        val staged = stagedBackupOfOneClearedSession()
        val lease = DatabaseOperationLease.tryAcquire("backup")
        assertNotNull(lease)
        try {
            assertEquals(RestoreApplyPipeline.Result.OTHER, pipeline.applyReplace(staged, emitter()))
        } finally {
            lease!!.close()
        }
        assertEquals(0, activity.localStore!!.getRecentSessions(10).size)
    }

    @Test
    fun applyReplaceReportsLoggingActiveWhenTheServiceRefusesToStop() {
        val staged = stagedBackupOfOneClearedSession()
        activity.loggingActive = true
        activity.stopObdThrows = true

        val result = pipeline.applyReplace(staged, emitter())

        assertEquals(RestoreApplyPipeline.Result.LOGGING_ACTIVE, result)
        assertEquals(1, activity.stopObdCalls)
        assertFalse("staged file must still be cleaned up", staged.exists())
    }

    @Test
    fun applyMergeFailsWhenTheLiveStoreIsGone() {
        val staged = stagedBackupOfOneClearedSession()
        activity.localStore?.close()
        activity.localStore = null

        val outcome = pipeline.applyMerge(staged, emitter())

        assertEquals(RestoreApplyPipeline.Result.OTHER, outcome.result)
        assertFalse("staged file must still be cleaned up", staged.exists())
    }

    @Test
    fun cancelledPipelineAbortsTheReplaceWithoutTouchingTheLiveDatabase() {
        val staged = stagedBackupOfOneClearedSession()
        val id = activity.localStore!!.startSession("obd", "CC:DD", "Live")
        activity.localStore!!.finishSession(id, ObdLocalStore.STATUS_COMPLETE, 4000L, "")
        val cancelled = RestoreApplyPipeline(activity) { true }

        val result = cancelled.applyReplace(staged, emitter())

        assertEquals(RestoreApplyPipeline.Result.OTHER, result)
        assertFalse("staged file must still be cleaned up", staged.exists())
        assertEquals(
            "the live session must survive a cancelled replace",
            1,
            activity.localStore!!.getRecentSessions(10).size,
        )
    }

    @Test
    fun aRestoredFileThatIsNotADatabaseRollsBackToTheOriginal() {
        val staged = File(activity.cacheDir, "not-a-database.db")
        staged.writeBytes(ByteArray(8 * 1024) { 0x5A })

        assertReplaceRollsBack(staged)
    }

    @Test
    fun aFailedRestoreLeavesEarlierDamagedCopiesAndTheirNoticeAlone() {
        val dbFile = activity.localStore!!.getDatabaseFile()
        activity.localStore!!.close()
        dbFile.writeText("older damaged history")
        DatabaseCorruption.setAside(activity, dbFile, 1_000L)
        dbFile.writeText("newer damaged history")
        DatabaseCorruption.setAside(activity, dbFile, 2_000L)
        activity.localStore = ObdLocalStore(activity)
        val staged = File(activity.cacheDir, "not-a-database.db")
        staged.writeBytes(ByteArray(8 * 1024) { 0x5A })

        assertReplaceRollsBack(staged, expectedCopies = listOf("1000", "2000"), expectedNoticeAtMs = 2_000L)
        val copies = DatabaseCorruption.setAsideCopies(dbFile)
        assertEquals("older damaged history", copies.first().readText())
        assertEquals("newer damaged history", copies.last().readText())
    }

    @Test
    fun aRestoredFileThatFailsItsQuickCheckRollsBackToTheOriginal() {
        val staged = File(activity.cacheDir, "damaged-pages.db")
        VoltTrackerDb(activity, "damaged-source.db").use { helper ->
            val db = helper.writableDatabase
            repeat(300) { index ->
                db.execSQL(
                    "INSERT INTO ${VoltTrackerDb.TABLE_SESSIONS} " +
                        "(mode, adapter_name, started_at_ms, status, created_at_ms) VALUES ('obd', ?, ?, 'complete', 1)",
                    arrayOf<Any>("x".repeat(1024), index.toLong()),
                )
            }
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        }
        activity.getDatabasePath("damaged-source.db").copyTo(staged, overwrite = true)
        activity.deleteDatabase("damaged-source.db")
        RandomAccessFile(staged, "rw").use { raf ->
            // Header intact, so the file opens; pages in the middle are garbage.
            raf.seek(maxOf(PAGE_SIZE, raf.length() / 2 / PAGE_SIZE * PAGE_SIZE))
            raf.write(ByteArray(2 * PAGE_SIZE.toInt()) { 0x5A })
        }

        assertReplaceRollsBack(staged)
    }

    /**
     * Replace with [staged] must fail, leave the live session in place and leave no file behind. A
     * bad restore is neither kept as damaged history nor announced as a reset: the set-aside copies
     * (by stamp) and the pending notice stay exactly as they were.
     */
    private fun assertReplaceRollsBack(
        staged: File,
        expectedCopies: List<String> = emptyList(),
        expectedNoticeAtMs: Long? = null,
    ) {
        val store = activity.localStore!!
        val id = store.startSession("obd", "CC:DD", "Live")
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, 4000L, "")
        val dbFile = store.getDatabaseFile()

        val result = pipeline.applyReplace(staged, emitter())

        assertEquals(RestoreApplyPipeline.Result.OTHER, result)
        assertNotNull("the original store must be reopened", activity.localStore)
        assertEquals("the live session must survive", 1, activity.localStore!!.getRecentSessions(10).size)
        assertTrue(
            "the original passes its own check",
            activity.localStore!!
                .dbMaintenance
                .quickCheck()
                .ok,
        )
        assertFalse("safety copy is rolled back into place", File("${dbFile.path}.restore-backup").exists())
        assertFalse(File("${dbFile.path}.restore-new").exists())
        assertFalse("staged file must be consumed", staged.exists())
        assertEquals(
            "a bad restore is not kept as history",
            expectedCopies,
            DatabaseCorruption.setAsideCopies(dbFile).map { it.name.substringAfterLast("-") },
        )
        assertEquals(
            "nor announced as a history reset",
            expectedNoticeAtMs,
            DatabaseCorruption.pendingNoticeAtMs(activity),
        )
    }

    @Test
    fun failedRollbackPreservesTheOnlyRestoreSafetyCopy() {
        assertFalse(RestoreApplyPipeline.shouldDeleteRestoreSafetyCopy(rollbackFailed = true))
        assertTrue(RestoreApplyPipeline.shouldDeleteRestoreSafetyCopy(rollbackFailed = false))
    }

    private companion object {
        const val PAGE_SIZE = 4096L
    }

    /** Minimal MainActivity that wires a real store and stubs the WebView-facing surfaces. */
    class HarnessActivity : MainActivity() {
        @JvmField var loggingActive = false

        @JvmField var stopObdThrows = false

        @JvmField var stopObdCalls = 0

        override fun onCreate(savedInstanceState: Bundle?) {
            localStore = ObdLocalStore(this)
        }

        override fun isLoggingActive(): Boolean = loggingActive

        override fun stopObdService() {
            stopObdCalls += 1
            if (stopObdThrows) {
                throw IllegalStateException("stop failed")
            }
            loggingActive = false
        }

        override fun publishStatus(
            state: String?,
            detail: String?,
            blocked: Boolean,
        ) {
            // No WebView in the harness.
        }

        override fun publishRestoreProgress(
            visible: Boolean,
            busy: Boolean,
            title: String?,
            detail: String?,
            tone: String?,
            phase: String?,
            bytesDone: Long,
            bytesTotal: Long,
            rowsDone: Long,
            rowsTotal: Long,
            percent: Int,
            etaSeconds: Long,
            operation: String?,
        ) {
            // No WebView in the harness.
        }

        override fun publishDeviceList() {
            // No WebView in the harness.
        }

        override fun publishStorageSummary() {
            // No WebView in the harness.
        }
    }
}
