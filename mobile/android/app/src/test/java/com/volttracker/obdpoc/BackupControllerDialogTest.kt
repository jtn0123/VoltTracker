package com.volttracker.obdpoc

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import com.volttracker.obdpoc.data.ObdLocalStore
import com.volttracker.obdpoc.data.VoltTrackerDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/**
 * Drives the native Replace / Merge / Cancel dialog in [BackupController]. This is the one
 * restore-flow surface Playwright can't reach (it's an Android AlertDialog, not the WebView): after a
 * picked file is staged + verified, the dialog must offer the three choices, and each button must
 * route to the right outcome (additive merge / destructive replace / cancel-and-clean-up).
 *
 * Uses a direct (inline) executor and a Robolectric content URI so the whole stage → dialog →
 * button-click → outcome chain runs synchronously on the test thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupControllerDialogTest {
    private lateinit var controller: ActivityController<HarnessActivity>
    private lateinit var activity: HarnessActivity

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(HarnessActivity::class.java).create()
        activity = controller.get()
        activity.localStore!!.clearAllData()
    }

    @After
    fun tearDown() {
        try {
            controller.destroy()
        } catch (ignored: RuntimeException) {
            // WebView teardown can race Robolectric background work; not part of these assertions.
        }
    }

    /** Seeds one session, exports a backup, then clears the live store so a merge has work to do. */
    @Throws(Exception::class)
    private fun stagedBackupOfOneClearedSession(): Uri {
        val id = activity.localStore!!.startSession("obd", "AA:BB", "Adapter")
        activity.localStore!!.finishSession(id, ObdLocalStore.STATUS_COMPLETE, 4000L, "")
        val backup = DataBackup(activity).buildBackupFile(activity.localStore)
        assertNotNull("buildBackupFile should produce a backup", backup)
        activity.localStore!!.clearAllData()
        val uri = Uri.parse("content://test/" + backup!!.name)
        shadowOf(activity.contentResolver)
            .registerInputStream(uri, FileInputStream(backup))
        return uri
    }

    /** AlertDialog button clicks post their listener to the main looper; run it. */
    private fun clickAndSettle(
        dialog: AlertDialog,
        which: Int,
    ) {
        dialog.getButton(which).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun openRestoreDialog(uri: Uri): AlertDialog {
        val data = Intent()
        data.setData(uri)
        activity.backupController!!.onRestorePickerResult(Activity.RESULT_OK, data)
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull("a verified backup must offer the restore-mode dialog", dialog)
        return dialog
    }

    private fun registerFileForPicker(file: File): Uri {
        val uri = Uri.parse("content://test/" + file.name)
        shadowOf(activity.contentResolver)
            .registerInputStream(uri, FileInputStream(file))
        return uri
    }

    private fun sqliteFixture(
        fileName: String,
        version: Int,
        ddl: SQLiteDatabase.() -> Unit,
    ): File {
        val file = File(activity.cacheDir, fileName)
        file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file.path, null)
        try {
            db.ddl()
            db.version = version
        } finally {
            db.close()
        }
        return file
    }

    @Test
    fun verifiedBackupOffersMergeReplaceAndCancel() {
        val dialog = openRestoreDialog(stagedBackupOfOneClearedSession())
        assertEquals(
            "Merge",
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).text.toString(),
        )
        assertEquals(
            "Replace all",
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).text.toString(),
        )
        assertEquals(
            "Cancel",
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).text.toString(),
        )
    }

    @Test
    fun mergeButtonFoldsTheBackupIntoTheLiveStore() {
        val dialog = openRestoreDialog(stagedBackupOfOneClearedSession())

        clickAndSettle(dialog, DialogInterface.BUTTON_POSITIVE) // Merge

        assertEquals("ready", activity.lastState)
        assertTrue(
            "merge status should report the folded-in session, got: " + activity.lastDetail,
            activity.lastDetail!!.contains("Merged"),
        )
        // The cleared live store has the donor session back.
        assertEquals(1, activity.localStore!!.getRecentSessions(10).size)
        assertTrue(
            "merge should publish row progress for the restore overlay",
            activity.restoreProgress.any {
                it.title == "Merging backup" &&
                    it.rowsTotal > 0L &&
                    it.percent in 0..100
            },
        )
    }

    @Test
    fun mergeButtonReportsFailureWhenStoreIsUnavailable() {
        val dialog = openRestoreDialog(stagedBackupOfOneClearedSession())
        activity.localStore?.close()
        activity.localStore = null

        clickAndSettle(dialog, DialogInterface.BUTTON_POSITIVE) // Merge

        assertEquals("blocked", activity.lastState)
        assertEquals("Merge failed - the backup could not be merged.", activity.lastDetail)
        val failed = activity.restoreProgress.last()
        assertEquals(false, failed.busy)
        assertEquals("blocked", failed.tone)
        assertEquals("Restore failed", failed.title)
    }

    @Test
    fun replaceButtonRestoresTheBackupWholesale() {
        val dialog = openRestoreDialog(stagedBackupOfOneClearedSession())

        clickAndSettle(dialog, DialogInterface.BUTTON_NEGATIVE) // Replace all

        assertEquals("ready", activity.lastState)
        assertTrue(
            "replace status should confirm the restore, got: " + activity.lastDetail,
            activity.lastDetail!!.contains("Backup restored"),
        )
        assertEquals(1, activity.localStore!!.getRecentSessions(10).size)
    }

    @Test
    fun replaceButtonStopsLoggingBeforeReplacing() {
        val dialog = openRestoreDialog(stagedBackupOfOneClearedSession())
        activity.loggingActive = true

        clickAndSettle(dialog, DialogInterface.BUTTON_NEGATIVE) // Replace all

        assertEquals(1, activity.stopObdCalls)
        assertEquals(false, activity.loggingActive)
        assertEquals("ready", activity.lastState)
        assertTrue(
            "replace status should confirm the restore after logging stops, got: " + activity.lastDetail,
            activity.lastDetail!!.contains("Backup restored"),
        )
        assertEquals(1, activity.localStore!!.getRecentSessions(10).size)
    }

    @Test
    fun mergeButtonReportsLoggingActiveWhenStopFails() {
        val dialog = openRestoreDialog(stagedBackupOfOneClearedSession())
        activity.loggingActive = true
        activity.stopObdThrows = true

        clickAndSettle(dialog, DialogInterface.BUTTON_POSITIVE) // Merge

        assertEquals(1, activity.stopObdCalls)
        assertEquals("blocked", activity.lastState)
        assertEquals(
            "Restore failed - logging is still active. Stop logging and try again.",
            activity.lastDetail,
        )
        val failed = activity.restoreProgress.last()
        assertEquals(false, failed.busy)
        assertEquals("blocked", failed.tone)
        assertEquals("Restore failed", failed.title)
        assertEquals(
            "Restore failed - logging is still active. Stop logging and try again.",
            failed.detail,
        )
    }

    @Test
    fun replaceButtonReportsFailureWhenStoreIsUnavailable() {
        val dialog = openRestoreDialog(stagedBackupOfOneClearedSession())
        activity.localStore?.close()
        activity.localStore = null

        clickAndSettle(dialog, DialogInterface.BUTTON_NEGATIVE) // Replace all

        assertEquals("blocked", activity.lastState)
        assertEquals("Restore failed - could not replace the on-device database.", activity.lastDetail)
        val failed = activity.restoreProgress.last()
        assertEquals(false, failed.busy)
        assertEquals("blocked", failed.tone)
        assertEquals("Restore failed", failed.title)
    }

    @Test
    fun replaceButtonPublishesBusyAndCompleteRestoreProgress() {
        val dialog = openRestoreDialog(stagedBackupOfOneClearedSession())

        clickAndSettle(dialog, DialogInterface.BUTTON_NEGATIVE) // Replace all

        val titles = activity.restoreProgress.map { it.title }
        assertTrue("restore should publish a busy overlay phase", titles.contains("Restoring backup"))
        val complete = activity.restoreProgress.last()
        assertEquals(true, complete.visible)
        assertEquals(false, complete.busy)
        assertEquals("ok", complete.tone)
        assertEquals("Restore complete", complete.title)
        assertTrue(complete.detail!!.contains("Backup restored"))
    }

    @Test
    fun cancelButtonAbortsWithoutChangingData() {
        val dialog = openRestoreDialog(stagedBackupOfOneClearedSession())

        clickAndSettle(dialog, DialogInterface.BUTTON_NEUTRAL) // Cancel

        assertEquals("ready", activity.lastState)
        assertEquals("Restore cancelled.", activity.lastDetail)
        // The store stays as it was (cleared) — cancel imports nothing.
        assertEquals(0, activity.localStore!!.getRecentSessions(10).size)
    }

    @Test
    fun verifiedBackupIsCancelledWhenActivityFinishesBeforeOptionsDialog() {
        val data = Intent().setData(stagedBackupOfOneClearedSession())
        activity.finish()

        activity.backupController!!.onRestorePickerResult(Activity.RESULT_OK, data)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("ready", activity.lastState)
        assertEquals("Restore cancelled.", activity.lastDetail)
        assertEquals(0, activity.localStore!!.getRecentSessions(10).size)
        val hidden = activity.restoreProgress.last()
        assertEquals(false, hidden.visible)
        assertEquals("idle", hidden.tone)
    }

    @Test
    fun invalidFileIsRejectedWithoutOfferingTheDialog() {
        val uri = Uri.parse("content://test/not-a-backup.db")
        shadowOf(activity.contentResolver)
            .registerInputStream(uri, ByteArrayInputStream("garbage".toByteArray()))
        val data = Intent()
        data.setData(uri)

        activity.backupController!!.onRestorePickerResult(Activity.RESULT_OK, data)

        assertEquals("blocked", activity.lastState)
        assertTrue(activity.lastDetail!!.contains("not a valid Volt Tracker backup"))
        val progress = activity.restoreProgress.last()
        assertEquals(true, progress.visible)
        assertEquals(false, progress.busy)
        assertEquals("blocked", progress.tone)
        assertEquals("Restore failed", progress.title)

        val appLog = File(activity.filesDir, "app-log/app.log")
        val logText = appLog.readText()
        assertTrue(logText.contains("backup_restore_stage_failed"))
        assertTrue(logText.contains("status=NOT_A_BACKUP"))
    }

    @Test
    fun missingRestoreUriPublishesOpenFailedWithoutOfferingDialog() {
        val missing = File(activity.cacheDir, "restore-source-missing.db")
        missing.delete()
        val data = Intent().setData(Uri.fromFile(missing))

        activity.backupController!!.onRestorePickerResult(Activity.RESULT_OK, data)

        assertEquals("blocked", activity.lastState)
        assertEquals(
            "Restore failed - Android could not read the selected file.",
            activity.lastDetail,
        )
        val progress = activity.restoreProgress.last()
        assertEquals(false, progress.busy)
        assertEquals("blocked", progress.tone)
        assertEquals("Restore failed", progress.title)
    }

    @Test
    fun newerSchemaBackupPublishesTooNewWithoutOfferingDialog() {
        val newer =
            sqliteFixture("restore-too-new-controller.db", VoltTrackerDb.DATABASE_VERSION + 1) {
                execSQL("CREATE TABLE obd_sessions (_id INTEGER PRIMARY KEY)")
            }
        val data = Intent().setData(registerFileForPicker(newer))

        activity.backupController!!.onRestorePickerResult(Activity.RESULT_OK, data)

        assertEquals("blocked", activity.lastState)
        assertEquals(
            "Restore failed - that backup was created by a newer Volt Tracker app.",
            activity.lastDetail,
        )
        assertEquals("Restore failed", activity.restoreProgress.last().title)
        assertNull("newer backups must not reach the restore-mode dialog", ShadowAlertDialog.getLatestAlertDialog())
        newer.delete()
    }

    @Test
    fun brokenLegacyBackupPublishesMigrationFailedWithoutOfferingDialog() {
        val brokenLegacy =
            sqliteFixture("restore-broken-legacy-controller.db", 1) {
                execSQL("CREATE TABLE obd_sessions (_id INTEGER PRIMARY KEY)")
                // Version 1 should have telemetry_samples. Leaving it out forces the migration
                // path to fail instead of silently treating a partial backup as restorable.
            }
        val data = Intent().setData(registerFileForPicker(brokenLegacy))

        activity.backupController!!.onRestorePickerResult(Activity.RESULT_OK, data)

        assertEquals("blocked", activity.lastState)
        assertEquals(
            "Restore failed - the backup could not be upgraded for this app.",
            activity.lastDetail,
        )
        assertEquals("blocked", activity.restoreProgress.last().tone)
        assertNull("failed migration must not offer merge/replace choices", ShadowAlertDialog.getLatestAlertDialog())
        brokenLegacy.delete()
    }

    @Test
    fun encryptedBackupWrongPassphrasePublishesDecryptFailedWithoutOfferingDialog() {
        val id = activity.localStore!!.startSession("obd", "AA:BB", "Adapter")
        activity.localStore!!.finishSession(id, ObdLocalStore.STATUS_COMPLETE, 4000L, "")
        val backup = DataBackup(activity).buildEncryptedBackupFile(activity.localStore, "correct-pass")
        assertNotNull("buildEncryptedBackupFile should produce a backup", backup)
        val data = Intent().setData(registerFileForPicker(backup!!))

        activity.backupController!!.launchEncryptedRestorePicker("wrong-pass")
        activity.backupController!!.onRestorePickerResult(Activity.RESULT_OK, data)

        assertEquals("blocked", activity.lastState)
        assertEquals(
            "Restore failed - the passphrase did not unlock that backup.",
            activity.lastDetail,
        )
        assertEquals("Restore failed", activity.restoreProgress.last().title)
        assertNull("decrypt failures must not offer merge/replace choices", ShadowAlertDialog.getLatestAlertDialog())
        backup.delete()
    }

    @Test
    fun encryptedBackupWithoutPassphraseExplainsWhatWentWrong() {
        val id = activity.localStore!!.startSession("obd", "AA:BB", "Adapter")
        activity.localStore!!.finishSession(id, ObdLocalStore.STATUS_COMPLETE, 4000L, "")
        val backup = DataBackup(activity).buildEncryptedBackupFile(activity.localStore, "secret-pass")
        assertNotNull("buildEncryptedBackupFile should produce a backup", backup)
        val uri = Uri.parse("content://test/" + backup!!.name)
        shadowOf(activity.contentResolver)
            .registerInputStream(uri, FileInputStream(backup))
        val data = Intent()
        data.setData(uri)

        activity.backupController!!.onRestorePickerResult(Activity.RESULT_OK, data)

        assertEquals("blocked", activity.lastState)
        assertTrue(activity.lastDetail!!.contains("encrypted"))
        assertTrue(activity.lastDetail!!.contains("passphrase"))
    }

    /** Inline executor: stage/apply work runs synchronously so the test can assert outcomes. */
    private class DirectExecutorService : AbstractExecutorService() {
        @Volatile
        private var shutdown = false

        override fun execute(command: Runnable) {
            command.run()
        }

        override fun shutdown() {
            shutdown = true
        }

        override fun shutdownNow(): List<Runnable> {
            shutdown = true
            return emptyList()
        }

        override fun isShutdown(): Boolean = shutdown

        override fun isTerminated(): Boolean = shutdown

        override fun awaitTermination(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean = true
    }

    /** Minimal MainActivity that wires a real store + inline executor and captures status. */
    class HarnessActivity : MainActivity() {
        @JvmField var loggingActive = false

        @JvmField var stopObdThrows = false

        @JvmField var stopObdCalls = 0

        @JvmField var lastState: String? = null

        @JvmField var lastDetail: String? = null

        @JvmField val restoreProgress = ArrayList<RestoreProgressEvent>()

        override fun onCreate(savedInstanceState: Bundle?) {
            localStore = ObdLocalStore(this)
            backupController =
                BackupController(this, DataBackup(this), DirectExecutorService())
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
            lastState = state
            lastDetail = detail
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
            restoreProgress.add(
                RestoreProgressEvent(
                    visible,
                    busy,
                    title,
                    detail,
                    tone,
                    phase,
                    bytesDone,
                    bytesTotal,
                    rowsDone,
                    rowsTotal,
                    percent,
                    etaSeconds,
                    operation,
                ),
            )
        }

        override fun publishDeviceList() {
            // No WebView in the harness.
        }

        override fun publishStorageSummary() {
            // No WebView in the harness.
        }
    }

    data class RestoreProgressEvent(
        val visible: Boolean,
        val busy: Boolean,
        val title: String?,
        val detail: String?,
        val tone: String?,
        val phase: String?,
        val bytesDone: Long,
        val bytesTotal: Long,
        val rowsDone: Long,
        val rowsTotal: Long,
        val percent: Int,
        val etaSeconds: Long,
        val operation: String?,
    )
}
