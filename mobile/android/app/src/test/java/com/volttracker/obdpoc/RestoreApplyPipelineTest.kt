package com.volttracker.obdpoc

import android.os.Bundle
import com.volttracker.obdpoc.data.ObdLocalStore
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

/**
 * Exercises [RestoreApplyPipeline] directly (the [BackupControllerDialogTest] suite drives the
 * same code through the Replace / Merge dialog): the replace file-swap, the merge fold-in, the
 * lease / logging-active / missing-store fail-closed branches, cancellation, and the
 * safety-copy preservation invariant behind the replace rollback ladder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreApplyPipelineTest {
    private lateinit var controller: ActivityController<HarnessActivity>
    private lateinit var activity: HarnessActivity
    private lateinit var pipeline: RestoreApplyPipeline
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
    fun failedRollbackPreservesTheOnlyRestoreSafetyCopy() {
        assertFalse(RestoreApplyPipeline.shouldDeleteRestoreSafetyCopy(rollbackFailed = true))
        assertTrue(RestoreApplyPipeline.shouldDeleteRestoreSafetyCopy(rollbackFailed = false))
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
