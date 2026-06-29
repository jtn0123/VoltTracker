package com.volttracker.obdpoc

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupControllerLifecycleTest {
    @Test
    fun restorePickerIsBlockedWhileLoggingIsActive() {
        val controller =
            Robolectric.buildActivity(HarnessActivity::class.java).create()
        try {
            val activity = controller.get()
            activity.loggingActive = true

            activity.backupController!!.launchRestorePicker()

            assertNull(activity.launchedRestoreIntent)
            assertEquals("blocked", activity.lastState)
            assertEquals("Stop logging before restoring a backup.", activity.lastDetail)
        } finally {
            destroyQuietly(controller)
        }
    }

    @Test
    fun restorePickerStartsSafIntentWhenIdle() {
        val controller =
            Robolectric.buildActivity(HarnessActivity::class.java).create()
        try {
            val activity = controller.get()
            activity.loggingActive = false

            activity.backupController!!.launchRestorePicker()

            val started = activity.launchedRestoreIntent
            assertNotNull(started)
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, started!!.action)
            assertEquals("application/octet-stream", started.type)
            assertEquals("ready", activity.lastState)
            assertEquals("Choose a Volt Tracker backup file.", activity.lastDetail)
            assertArrayEquals(
                arrayOf(
                    "application/octet-stream",
                    "application/vnd.sqlite3",
                    "application/x-sqlite3",
                ),
                started.getStringArrayExtra(Intent.EXTRA_MIME_TYPES),
            )
        } finally {
            destroyQuietly(controller)
        }
    }

    @Test
    fun restorePickerCancelReportsNoFileSelected() {
        val controller =
            Robolectric.buildActivity(HarnessActivity::class.java).create()
        try {
            val activity = controller.get()

            activity.backupController!!.onRestorePickerResult(Activity.RESULT_CANCELED, null)

            assertEquals("ready", activity.lastState)
            assertEquals("Restore cancelled - no file selected.", activity.lastDetail)
        } finally {
            destroyQuietly(controller)
        }
    }

    @Test
    fun restorePickerBlocksSecondLaunchWhilePickerIsOpen() {
        val controller =
            Robolectric.buildActivity(HarnessActivity::class.java).create()
        try {
            val activity = controller.get()

            activity.backupController!!.launchEncryptedRestorePicker("old1")
            activity.backupController!!.launchRestorePicker()

            assertEquals("blocked", activity.lastState)
            assertEquals("Backup file picker is already open.", activity.lastDetail)
        } finally {
            destroyQuietly(controller)
        }
    }

    @Test
    fun encryptedRestorePassphraseIsNotSavedAcrossControllerRecreation() {
        val controller =
            Robolectric.buildActivity(HarnessActivity::class.java).create()
        try {
            val activity = controller.get()
            activity.backupController!!.launchEncryptedRestorePicker("old1")
            val state = Bundle()
            activity.backupController!!.saveState(state)
            assertFalse(state.containsKey("volttracker.pending_restore_passphrase"))

            activity.backupController =
                BackupController(activity, DataBackup(activity), null).also {
                    it.restoreState(state)
                }
            activity.backupController!!.onRestorePickerResult(
                Activity.RESULT_OK,
                Intent().setData(Uri.parse("content://volttracker.test/backup.vtdb")),
            )

            assertTrue(activity.progressTitles.contains("Reading backup"))
            assertFalse(activity.progressTitles.contains("Reading encrypted backup"))
        } finally {
            destroyQuietly(controller)
        }
    }

    @Test
    fun stopObdServiceSurfacesRejectedServiceStart() {
        val controller =
            Robolectric.buildActivity(HarnessActivity::class.java).create()
        try {
            val activity = controller.get()
            activity.rejectStartService = true

            activity.stopObdService()

            assertEquals("ready", activity.lastState)
            assertEquals(
                "Stop request noted; reopen the app if logging is still active.",
                activity.lastDetail,
            )
        } finally {
            destroyQuietly(controller)
        }
    }

    class HarnessActivity : MainActivity() {
        @JvmField var loggingActive = false

        @JvmField var rejectStartService = false

        @JvmField var launchedRestoreIntent: Intent? = null

        @JvmField var lastState: String? = null

        @JvmField var lastDetail: String? = null

        @JvmField val progressTitles: MutableList<String?> = ArrayList()

        override fun onCreate(savedInstanceState: Bundle?) {
            backupController = BackupController(this, DataBackup(this), null)
        }

        override fun isLoggingActive(): Boolean = loggingActive

        override fun publishStatus(
            state: String?,
            detail: String?,
            blocked: Boolean,
        ) {
            lastState = state
            lastDetail = detail
        }

        override fun launchRestoreFilePicker(intent: Intent) {
            launchedRestoreIntent = intent
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
            progressTitles.add(title)
        }

        override fun startService(service: Intent?): ComponentName? {
            if (rejectStartService) {
                throw IllegalStateException("background service start blocked")
            }
            return ComponentName(this, ObdService::class.java)
        }
    }

    companion object {
        private fun destroyQuietly(controller: ActivityController<out MainActivity>) {
            try {
                controller.destroy()
            } catch (ignored: RuntimeException) {
                // WebView teardown can race Robolectric background work; not part of this assertion.
            }
        }
    }
}
